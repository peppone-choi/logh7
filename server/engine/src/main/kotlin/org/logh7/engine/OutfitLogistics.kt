package org.logh7.engine

enum class LogisticsMode { ALLOCATION, REORGANIZATION }
enum class LogisticsPhase { PREPARE, COMMIT, CANCEL }
enum class LogisticsRejection { SESSION, IDENTITY, OUTFIT, ACCESS, LOCATION, BUSY, PREPARATION, STOCK, CREW, CP, MODE, STORAGE, QUOTE_CHANGED, CARGO_LOADED }
data class LogisticsRequest(val account: String, val connection: String, val characterId: Int,
    val country: CountryId?, val generation: Long, val outfitId: Int, val mode: LogisticsMode,
    val phase: LogisticsPhase, val transfer: UnitTransfer = UnitTransfer(), val supplies: Int = 0,
    val reservation: Long? = null) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,outfitId:Int,
        mode:LogisticsMode,phase:LogisticsPhase,transfer:UnitTransfer=UnitTransfer(),supplies:Int=0,reservation:Long?=null):
        this(account,connection,characterId,power.country,generation,outfitId,mode,phase,transfer,supplies,reservation)
    init { require(characterId > 0 && outfitId > 0 && generation > 0 && supplies != Int.MIN_VALUE &&
        (if (phase == LogisticsPhase.PREPARE) reservation == null else reservation != null && reservation > 0)) }
}
data class LogisticsResult(val stocks: PlanetStocks? = null, val rejection: LogisticsRejection? = null,
    val reservation: Long? = null)

/** World-actor-owned reservations. Only COMMIT changes durable stock. Author: Peppone Choi. */
internal class OutfitLogistics(private val clock: () -> Long,galaxy:Galaxy?=null,
    private val leaseMillis:Long=120_000,countries:CountryCatalog?=null) {
    private val offices=galaxy?.let {LogisticsOfficePolicy(it,countries)}
    private data class Reservation(val id: Long, val request: LogisticsRequest, val warehouse: Int, val base: Int, val camp: Int, val expires: Long)
    private val reservations = mutableListOf<Reservation>()
    private var sequence = 0L
    init { require(leaseMillis in 1..3_600_000) }

    private fun allocationAllowed(stocks:PlanetStocks,request:LogisticsRequest,country:CountryId,outfit:OutfitStock)=
        offices?.permits(stocks,request.characterId,country,outfit.kind)==true

    private fun lostAccess(lock:Reservation,stocks:PlanetStocks):Boolean {
        val request=lock.request
        val outfit=stocks.outfits[request.outfitId] ?: return true
        val country=request.country ?: return true
        if(outfit.baseId!=lock.base || outfit.warehouseId!=lock.warehouse || stocks.bases[lock.base]?.camp!=lock.camp ||
            !TacticalAuthority.canAccessOutfitWarehouse(stocks,outfit,request.characterId,country))return true
        return when(request.mode) {
            LogisticsMode.ALLOCATION -> !allocationAllowed(stocks,request,country,outfit)
            LogisticsMode.REORGANIZATION -> outfit.commanderId!=request.characterId
        }
    }

    fun expireIneligible(stocks:PlanetStocks) {
        reservations.removeAll {lostAccess(it,stocks)}
    }

    private fun expireReservations(session:SessionState,now:Long,stocks:PlanetStocks?) {
        reservations.removeAll { lock ->
            val request=lock.request
            lock.expires<=now || request.generation!=session.generation || request.country in session.eliminated ||
                session.participants[request.account]?.let {
                    !it.online || it.connection!=request.connection || it.country!=request.country
                }!=false || stocks?.let {lostAccess(lock,it)}==true
        }
    }

    fun occupationCommitted(base:Int) {
        reservations.removeAll {it.base==base}
    }

    fun blocksMovement(session:SessionState,outfit:Int,stocks:PlanetStocks?=null):Boolean {
        expireReservations(session,clock(),stocks)
        return reservations.any {it.request.mode==LogisticsMode.REORGANIZATION && it.request.outfitId==outfit}
    }

    fun blocksSupply(session:SessionState,stocks:PlanetStocks,outfits:Set<Int>):Boolean {
        expireReservations(session,clock(),stocks)
        val warehouses=outfits.mapNotNull {stocks.outfits[it]?.warehouseId}.toSet()
        return reservations.any {it.request.mode==LogisticsMode.ALLOCATION || it.warehouse in warehouses}
    }

    fun apply(request: LogisticsRequest, session: SessionState, player: Navigator?, production: PlanetProduction,
        persist: Boolean = true): LogisticsResult {
        fun reject(reason: LogisticsRejection) = LogisticsResult(rejection = reason)
        val now = clock()
        expireReservations(session,now,production.snapshot(session.generation))
        val member = session.participants[request.account]
        if (session.ended != null || request.country in session.eliminated || request.generation != session.generation || member == null || !member.online ||
            member.connection != request.connection) return reject(LogisticsRejection.SESSION)
        if (member.country != request.country || player == null || player.generation != session.generation ||
            player.characterId != request.characterId || player.country != request.country) return reject(LogisticsRejection.IDENTITY)
        val country = request.country ?: return reject(LogisticsRejection.ACCESS)
        val existing = reservations.singleOrNull { it.request.account == request.account && it.request.connection == request.connection }
        if(existing!=null && existing.request.characterId!=request.characterId)return reject(LogisticsRejection.PREPARATION)
        if (request.phase == LogisticsPhase.CANCEL) {
            if (existing == null || existing.id != request.reservation || existing.request.outfitId != request.outfitId || existing.request.mode != request.mode)
                return reject(LogisticsRejection.PREPARATION)
            reservations.remove(existing)
            return LogisticsResult(production.snapshot(session.generation))
        }
        val stocks = production.settle(session.generation, session.gameMillis / 1000)
        val outfit = stocks.outfits[request.outfitId] ?: return reject(LogisticsRejection.OUTFIT)
        val base = stocks.bases.getValue(outfit.baseId)
        if (!TacticalAuthority.canAccessOutfitWarehouse(stocks,outfit,request.characterId,country)) return reject(LogisticsRejection.ACCESS)
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        if (request.mode == LogisticsMode.ALLOCATION) {
            if (!allocationAllowed(stocks,request,country,outfit)) return reject(LogisticsRejection.ACCESS)
        } else {
            if (outfit.guard != null || outfit.commanderId != request.characterId) return reject(LogisticsRejection.ACCESS)
            if (player.baseId != outfit.baseId || (outfit.position?.baseId ?: outfit.baseId) != outfit.baseId ||
                player.navigationPending || player.cpPaused)
                return reject(LogisticsRejection.LOCATION)
            if (request.supplies != 0) return reject(LogisticsRejection.STOCK)
        }
        if (request.phase == LogisticsPhase.PREPARE) {
            if (existing != null) return if (existing.request.outfitId == request.outfitId && existing.request.mode == request.mode)
                LogisticsResult(stocks, reservation = existing.id) else reject(LogisticsRejection.BUSY)
            // Allocation is a global writer; reorganization also excludes peers using the same shared warehouse.
            if (reservations.any { it.request.mode == LogisticsMode.ALLOCATION || request.mode == LogisticsMode.ALLOCATION ||
                    it.warehouse == outfit.warehouseId }) return reject(LogisticsRejection.BUSY)
            sequence = Math.addExact(sequence, 1)
            reservations.add(Reservation(sequence, request, outfit.warehouseId, outfit.baseId, base.camp, Math.addExact(now, leaseMillis)))
            return LogisticsResult(stocks, reservation = sequence)
        }
        if (existing == null || existing.id != request.reservation || existing.request.mode != request.mode || existing.request.outfitId != request.outfitId ||
            existing.warehouse != outfit.warehouseId || existing.base != outfit.baseId) return reject(LogisticsRejection.PREPARATION)
        val next = try {
            if (request.mode == LogisticsMode.ALLOCATION) {
                StockAssignment.applyTransfer(stocks, outfit, request.transfer, request.supplies)
            } else {
                if (request.transfer.ships.keys.any { key -> ProductionUnits.definitions.none { it.ship && it.kind == key.kind && it.power == outfit.shipStyle.nativePower } } ||
                    request.transfer.troops.keys.any { key -> ProductionUnits.definitions.none { !it.ship && it.kind == key.kind && it.power == outfit.shipStyle.nativePower } })
                    return reject(LogisticsRejection.STOCK)
                val reorganized=ReplacementBoats.reorganize(stocks,outfit.id,request.transfer)
                val destination=reorganized.outfits.getValue(outfit.id)
                val required=ReplacementBoats.crewRequired(destination)
                val crewKinds = UnitSpecifications.crews.filter { it.power == outfit.shipStyle.nativePower && it.type == 3 }.map { it.kind }.toSet()
                val crew = destination.units.troops.filterKeys { it.kind in crewKinds }.values.sumOf { it.toLong() }
                if (required != crew) return reject(LogisticsRejection.CREW)
                if (outfit.supplies > OutfitSupplies.capacity(destination)) return reject(LogisticsRejection.STOCK)
                reorganized
            }
        } catch (_: ReplacementBoats.LoadedCargo) { return reject(LogisticsRejection.CARGO_LOADED) }
        catch (_: IllegalArgumentException) { return reject(LogisticsRejection.STOCK) }
        // The native command combines CP and inventory in the common world store.
        // Preview still runs all reservation, ownership, crew and identity checks.
        val saved = if (persist) production.replace(stocks, next) else next
        reservations.remove(existing)
        return LogisticsResult(saved)
    }
}
