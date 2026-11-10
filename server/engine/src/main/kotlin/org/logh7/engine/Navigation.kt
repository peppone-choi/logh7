package org.logh7.engine

import org.logh7.engine.strategy.*
import kotlin.math.abs

/** Local navigation policy where original range, fuel and timing formulas remain unknown. cruiseCost is per 100-ly grid. */
data class NavigationPolicy(val initialCp: Int = 1000, val initialCruise: Int = 1000,
    val maximumRange: Int = 3, val warpCost: Int = 40, val cruiseCost: Int = 100,
    val maximumCp: Int = 1000, val recoveryBase: Int = 50,
    val maximumCruise: Int = 1000, val fuelSupplyCost: Int = 160) {
    init { require(initialCp in 0..maximumCp && initialCruise >= 100 && maximumRange in 1..10 &&
        warpCost >= 0 && cruiseCost >= 0 && maximumCp > 0 && recoveryBase >= 0 &&
        maximumCruise >= initialCruise && fuelSupplyCost == 160) }
}
data class Navigator(val generation: Long, val characterId: Int, val country: CountryId?, val grid: Int,
    val pcp: Int, val mcp: Int, val cruise: Int, val moves: Long = 0,
    val baseId: Int = 0, val mode: Int = 5, val spotId: Int = 0, val spotOwner: Int = 0,
    val cpRecoveryAt: Long? = null, val cpPaused: Boolean = false,
    val fuelReadyAt: Long? = null, val strategy: StrategyCheckpoint? = null, val dockTarget: Int? = null,
    val returnBase: Int = 0, val energy: TacticalEnergy? = null, val motion: TacticalMotion? = null,
    val retreat: TacticalRetreat? = null, val charge: TacticalCharge? = null, val fire: TacticalFire? = null,
    val supplies: Int = 10_000, val hull: FlagshipHull? = null, val attack: TacticalAttackOrder? = null,
    val commandRange:TacticalCommandRange?=null,val posture:TacticalPosture?=null,
    /** Reserved warp destination; the fleet stays at [grid] until the strategy job completes. */
    val warpTarget: Int? = null,
    /** Strategic CP spent but not yet converted into ability experience (CpExperience). */
    val pcpUsed: Int = 0, val mcpUsed: Int = 0,
    val shipStyle:ShipStyleId=ShipStyleId.fromLegacy(country.legacyPower()),
    val offlineWarpCancellation: NavigationRejection? = null,
    /** The first assigned home in this world. Zero means a legacy or unassigned home. */
    val originBase: Int = 0) {
    constructor(generation:Long,characterId:Int,power:Power,grid:Int,pcp:Int,mcp:Int,cruise:Int,moves:Long=0,
        baseId:Int=0,mode:Int=5,spotId:Int=0,spotOwner:Int=0,cpRecoveryAt:Long?=null,cpPaused:Boolean=false,
        fuelReadyAt:Long?=null,strategy:StrategyCheckpoint?=null,dockTarget:Int?=null,returnBase:Int=0,
        energy:TacticalEnergy?=null,motion:TacticalMotion?=null,retreat:TacticalRetreat?=null,
        charge:TacticalCharge?=null,fire:TacticalFire?=null,supplies:Int=10_000,hull:FlagshipHull?=null,
        attack:TacticalAttackOrder?=null,commandRange:TacticalCommandRange?=null,posture:TacticalPosture?=null,
        warpTarget:Int?=null,pcpUsed:Int=0,mcpUsed:Int=0):this(generation,characterId,power.country,grid,pcp,mcp,
            cruise,moves,baseId,mode,spotId,spotOwner,cpRecoveryAt,cpPaused,fuelReadyAt,strategy,dockTarget,
            returnBase,energy,motion,retreat,charge,fire,supplies,hull,attack,commandRange,posture,warpTarget,
            pcpUsed,mcpUsed,ShipStyleId.fromLegacy(power))
    val power:Power get()=country.legacyPower()
    val unitId: Int get() = Math.addExact(characterId, 1_000_000)
    private val travelReadyAt: Long get() = maxOf(strategy!!.jobs.single().completesAtGameMillis / 1000, hull?.woundedUntil ?: 0)
    val dockReadyAt: Long? get() = if (dockTarget == null) null else travelReadyAt
    val warpReadyAt: Long? get() = if (warpTarget == null) null else travelReadyAt
    val navigationPending: Boolean get() = fuelReadyAt != null || dockTarget != null || warpTarget != null
    init { require(generation > 0 && characterId in 1..0x7fefffff && grid in 0..4999 &&
        pcp >= 0 && mcp >= 0 && cruise >= 0 && pcpUsed in 0..CpExperience.USED_LIMIT && mcpUsed in 0..CpExperience.USED_LIMIT && moves >= 0 && baseId in 0..65535 && mode in 0..5 &&
        spotId >= 0 && spotOwner >= 0 && returnBase in 0..65535 && originBase in 0..65535 && supplies in 0..10_000 &&
        (offlineWarpCancellation == null || offlineWarpCancellation == NavigationRejection.GRID ||
            offlineWarpCancellation == NavigationRejection.CRUISE || offlineWarpCancellation == NavigationRejection.STAR_SYSTEM_NOT_ADJACENT ||
            offlineWarpCancellation == NavigationRejection.RANGE) &&
        (hull == null || Flagships.find(hull.kind)?.power == shipStyle.nativePower) &&
        (charge == null || cpPaused && baseId == 0 && mode == 5) &&
        (commandRange == null || cpPaused && baseId == 0 && mode == 5) &&
        (attack == null || cpPaused && baseId == 0 && mode == 5 && hull != null) &&
        (fire?.pending == null || cpPaused && baseId == 0 && mode == 5 && fire.pending.grid == grid) &&
        (retreat == null || (cpPaused && baseId == 0 && mode == 5 && motion != null && retreat.destination != grid &&
            abs(retreat.destination % 100 - grid % 100) <= 1 && abs(retreat.destination / 100 - grid / 100) <= 1 &&
            energy?.values?.get(1) == TacticalEnergy.WARP_MAX)) &&
        (motion == null || (motion.grid == grid && cpPaused && baseId == 0 && mode == 5 &&
            motion.velocity <= (energy ?: TacticalEnergy.ZERO).values[0] / 100f*(if(motion.parallel)0.5f else 1f))) &&
        (cpRecoveryAt == null || cpRecoveryAt in 0..0xffffffffL) &&
        (fuelReadyAt == null || fuelReadyAt in 0..0xffffffffL) &&
        (dockTarget == null || dockTarget in 1..65535 && strategy != null && fuelReadyAt == null) &&
        (warpTarget == null || warpTarget in 0..4999 && warpTarget != grid && strategy != null && fuelReadyAt == null &&
            dockTarget == null && baseId == 0 && mode == 5) &&
        (strategy == null || (strategy.jobs.size <= 1 && strategy.jobs.isEmpty() == (fuelReadyAt == null && dockTarget == null && warpTarget == null) && strategy.jobs.all {
            it.state != JobState.COMPLETED && it.completesAtGameMillis % 1000 == 0L &&
                it.completesAtGameMillis / 1000 in 0..0xffffffffL && when (it.command) {
                    NavigationWork.FUEL -> dockTarget == null && warpTarget == null && fuelReadyAt == it.completesAtGameMillis / 1000
                    NavigationWork.DOCK -> dockTarget != null && fuelReadyAt == null && it.startsAtGameMillis == it.completesAtGameMillis
                    NavigationWork.WARP -> warpTarget != null && it.startsAtGameMillis == it.completesAtGameMillis
                    else -> false
                } })) &&
        (baseId != 0 || (mode == 5 && spotId == 0 && spotOwner == 0))) }
}
sealed interface PlaceMove {
    data class Dock(val baseId: Int, val mode: Int) : PlaceMove
    data class Spot(val id: Int, val owner: Int) : PlaceMove
    data class SwitchMode(val mode: Int) : PlaceMove
}
interface NavigationStore : AutoCloseable {
    fun load(): Map<String, Navigator>
    /** Must finish durable replacement before an ACK or a new snapshot is published. */
    fun save(players: Map<String, Navigator>)
    override fun close() {}
}
internal fun Navigator.blocksFirstEntry(request: NavigationRequest, stocks: PlanetStocks?): Boolean =
    generation == request.generation && (characterId == request.characterId || !BattleDeath.deceased(stocks,characterId))

data class NavigationRequest(val account: String, val characterId: Int, val country: CountryId?,
    val connection: String, val generation: Long, val destination: Int? = null, val unitId: Int? = null,
    val captainCard: Int? = null, val place: PlaceMove? = null, val rank: Int = 20,
    val politics: Int = 50, val administration: Int = 50, val tactical: Boolean? = null,
    val supplyFuel: Boolean = false, val returnBase: Int? = null,
    val energy: TacticalEnergy? = null, val shipKind: Int? = null, val tacticalMove: TacticalMove? = null,
    val tacticalStop: TacticalStop? = null, val tacticalRetreat: List<Int>? = null,
    val tacticalShoot: TacticalShoot? = null, val tacticalAttack: TacticalAttack? = null,
    val tacticalSupport: TacticalSupport? = null, val commandAbility:Int?=null,
    val tacticalPosture:TacticalPostureChange?=null,val tacticalGround:TacticalGroundCommand?=null,
    val tacticalCommand:TacticalCommand?=null,val tacticalRanks:Map<Int,Int> = emptyMap(),val offlineDirection:Int?=null,
    /** World-clock settlement of an offline captain's due navigation job; it carries no action of its own. */
    val offlineSettle:Boolean=false,
    /**
     * The offline request carries the captain's last joined abilities, so CP recovery is settled first as at login.
     * Without them recovery waits for login; the caller must not settle a warp then (tactical contact resets the clock).
     */
    val offlineAbilities:Boolean=false,
    /** Server-resolved model family; never taken from a native client field. */
    val shipStyle:ShipStyleId?=null,
    val acknowledgedOfflineWarpCancellation: NavigationRejection? = null,
    /** Authenticated original-character bootstrap, never a client-selected placement. */
    val initializeOriginal:Boolean=false,val startingOffice:OriginalStartingOffice?=null) {
    /** Compatibility for old internal callers; membership checks use [country]. */
    constructor(account: String, characterId: Int, power: Power,
    connection: String, generation: Long, destination: Int? = null, unitId: Int? = null,
    captainCard: Int? = null, place: PlaceMove? = null, rank: Int = 20,
    politics: Int = 50, administration: Int = 50, tactical: Boolean? = null,
    supplyFuel: Boolean = false, returnBase: Int? = null,
    energy: TacticalEnergy? = null, shipKind: Int? = null, tacticalMove: TacticalMove? = null,
    tacticalStop: TacticalStop? = null, tacticalRetreat: List<Int>? = null,
    tacticalShoot: TacticalShoot? = null, tacticalAttack: TacticalAttack? = null,
    tacticalSupport: TacticalSupport? = null, commandAbility:Int?=null,
    tacticalPosture:TacticalPostureChange?=null,tacticalGround:TacticalGroundCommand?=null,
    tacticalCommand:TacticalCommand?=null,tacticalRanks:Map<Int,Int> = emptyMap(),offlineDirection:Int?=null,
    /** World-clock settlement of an offline captain's due navigation job; it carries no action of its own. */
    offlineSettle:Boolean=false,
    /**
     * The offline request carries the captain's last joined abilities, so CP recovery is settled first as at login.
     * Without them recovery waits for login; the caller must not settle a warp then (tactical contact resets the clock).
     */
    offlineAbilities:Boolean=false,
    /** Server-resolved model family; never taken from a native client field. */
    shipStyle:ShipStyleId?=null) : this(account,characterId,power.country,connection,generation,destination,
        unitId,captainCard,place,rank,politics,administration,
        tactical,supplyFuel,returnBase,energy,shipKind,tacticalMove,
        tacticalStop,tacticalRetreat,tacticalShoot,tacticalAttack,tacticalSupport,commandAbility,
        tacticalPosture,tacticalGround,tacticalCommand,tacticalRanks,offlineDirection,offlineSettle,
        offlineAbilities,shipStyle)
    val power:Power get()=country.legacyPower()

    init {require(!offlineAbilities || offlineSettle)}
    init {require(!offlineSettle || (acknowledgedOfflineWarpCancellation == null && offlineDirection==null && destination==null && place==null && !supplyFuel && returnBase==null &&
        tactical==null && energy==null && shipKind==null && tacticalMove==null && tacticalStop==null && tacticalRetreat==null &&
        tacticalShoot==null && tacticalAttack==null && tacticalSupport==null && tacticalPosture==null && tacticalGround==null &&
        tacticalCommand==null && tacticalRanks.isEmpty()))}
    init {require(offlineDirection==null || (offlineDirection in 0..31 && destination==null && place==null && !supplyFuel &&
        returnBase==null && tactical==null && energy==null && tacticalMove==null && tacticalStop==null && tacticalRetreat==null &&
        tacticalShoot==null && tacticalAttack==null && tacticalSupport==null && tacticalPosture==null && tacticalGround==null && tacticalCommand==null))}
    init {require(tacticalRanks.size<=600 && tacticalRanks.all {(id,v)->id>0 && v>=0})
        require(tacticalCommand==null || (destination==null && place==null && !supplyFuel && returnBase==null && tactical==null &&
            energy==null && tacticalMove==null && tacticalStop==null && tacticalRetreat==null && tacticalShoot==null &&
            tacticalAttack==null && tacticalSupport==null && tacticalPosture==null && tacticalGround==null))}
    init { require((tacticalGround==null || (destination==null && place==null && !supplyFuel && returnBase==null && tactical==null &&
        energy==null && tacticalMove==null && tacticalStop==null && tacticalRetreat==null && tacticalShoot==null && tacticalAttack==null &&
        tacticalSupport==null && tacticalPosture==null && shipKind!=null)) && (tacticalPosture==null || (destination==null && place==null && !supplyFuel && returnBase==null &&
        tactical==null && energy==null && tacticalMove==null && tacticalStop==null && tacticalRetreat==null &&
        tacticalShoot==null && tacticalAttack==null && tacticalSupport==null && shipKind!=null)) &&
        politics in 0..65535 && administration in 0..65535 &&
        (commandAbility==null || commandAbility in 0..32767) &&
        (tacticalSupport == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null &&
            energy == null && tacticalMove == null && tacticalStop == null && tacticalRetreat == null && tacticalShoot == null && tacticalAttack == null)) &&
        (tacticalAttack == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null &&
            energy == null && tacticalMove == null && tacticalStop == null && tacticalRetreat == null && tacticalShoot == null && shipKind != null)) &&
        (tacticalShoot == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null &&
            energy == null && tacticalMove == null && tacticalStop == null && tacticalRetreat == null && shipKind != null)) &&
        (tactical == null || (destination == null && place == null && !supplyFuel)) &&
        (!supplyFuel || (destination == null && place == null)) &&
        (returnBase == null || (returnBase in 0..65535 && destination == null && place == null && !supplyFuel && tactical == null)) &&
        (energy == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && shipKind != null)) &&
        (tacticalMove == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && energy == null && shipKind != null)) &&
        (tacticalStop == null || (destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && energy == null && tacticalMove == null && shipKind != null)) &&
        (tacticalRetreat == null || (tacticalRetreat.size in 1..32 && tacticalRetreat.distinct().size == tacticalRetreat.size &&
            destination == null && place == null && !supplyFuel && returnBase == null && tactical == null && energy == null &&
            tacticalMove == null && tacticalStop == null && shipKind != null))) }
}
enum class NavigationRejection { DISABLED, SESSION, IDENTITY, UNIT, CARD, SAME_GRID, RANGE, GRID, CRUISE, CP, TACTICAL,
    BASE, SPOT, ACCESS, EMBARKED, SUPPLYING, FUEL_FULL, CLOCK, TRAVELLING, BUSY, ENERGY, INSIDE_RADAR, WEAPON, TARGET, SUPPLY, WOUNDED,
    /** A crew type the landing site bars (p.51: 装甲兵 on special planets/fortresses). Appended to keep wire ordinals. */
    CREW, STAR_SYSTEM_NOT_ADJACENT }
data class NavigationResult(val player: Navigator?, val previous: Navigator? = null, val rejection: NavigationRejection? = null,
    val completedFuel: Boolean = false, val completedDock: Boolean = false, val supportReadyAt: Long? = null,
    /** A reserved warp reached its execution time; [warpCancellation] gives the reason the fleet stayed at its origin. */
    val completedWarp: Boolean = false, val warpCancellation: NavigationRejection? = null,
    val supportNotifications: List<TacticalSupportResult> = emptyList(),
    val tacticalMotions: Map<Int,TacticalMotion> = emptyMap(),val postureReadyAt:Long?=null,val groundReadyAt:Long?=null,
    val commandReadyAt:Long?=null,val commandNotifications:List<TacticalCommandResult> = emptyList(),
    val groundStopped:Set<TacticalGroundAction> = emptySet(),
    val blockedByDetention: Boolean = false)

/** Called only on the world actor. No input can choose its own identity, balance or origin. */
internal class Navigation(private val galaxy: Galaxy, private val store: NavigationStore?,
    private val policy: NavigationPolicy, private val cpIntervalSeconds: Long = 7200, private val clockScale: Long = 24,
    restored:Map<String,Navigator> = store?.load().orEmpty(),
    private val configuredCountries:CountryCatalog?=null,
    private val recoveryPenalty: (Navigator) -> Int = { 0 }) {
    init { require(cpIntervalSeconds in 1..86400 && clockScale in 1..1000) }
    private var players = restored.toMap()
    fun snapshot(generation: Long) = players.filterValues { it.generation == generation }.toMap()

    fun warpCruise(origin: Int, target: Int) = Math.toIntExact((WarpCourse.cruiseCost(origin, target).toLong() *
        policy.cruiseCost + WarpCourse.LIGHT_YEARS_PER_GRID - 1) / WarpCourse.LIGHT_YEARS_PER_GRID)
    fun apply(request: NavigationRequest, session: SessionState, stocks: PlanetStocks? = null,
        persist: Boolean = true, movingUnits: Int = 1, soloShip: Boolean = true, initialDock:OutfitPosition?=null,
        retreatStocks: PlanetStocks? = stocks): NavigationResult {
        require(stocks == null || stocks.generation == session.generation)
        var current: Navigator? = null
        var completedFuel = false
        var completedDock = false
        var completedWarp = false
        var warpCancellation: NavigationRejection? = null
        fun reject(reason: NavigationRejection) = NavigationResult(null, previous = current, rejection = reason,
            completedFuel = completedFuel, completedDock = completedDock, completedWarp = completedWarp, warpCancellation = warpCancellation)
        val member = session.participants[request.account] ?: return reject(NavigationRejection.SESSION)
        if (session.ended != null || member.country in session.eliminated || session.generation != request.generation ||
            (if (request.offlineSettle) member.online else !member.online || member.connection != request.connection))
            return reject(NavigationRejection.SESSION)
        if (member.country != request.country) return reject(NavigationRejection.IDENTITY)
        val deceased = BattleDeath.deceased(stocks,request.characterId)
        if (persist && !deceased) settleRetreats(session, retreatStocks)
        // U-11: a dead character's navigator gives way to the account's next character.
        val existing = players[request.account]?.takeIf { it.blocksFirstEntry(request,stocks) }
        if (existing != null && (existing.characterId != request.characterId || existing.country != request.country))
            return reject(NavigationRejection.IDENTITY)
        if(existing!=null && request.shipStyle!=null && existing.shipStyle!=request.shipStyle)
            return reject(NavigationRejection.IDENTITY)
        if (deceased) {
            current = existing
            if (existing == null || request.offlineSettle || request.destination != null || request.place != null ||
                request.supplyFuel || request.returnBase != null || request.offlineDirection != null ||
                request.tactical != null || request.energy != null || request.tacticalMove != null ||
                request.tacticalStop != null || request.tacticalRetreat != null || request.tacticalShoot != null ||
                request.tacticalAttack != null || request.tacticalSupport != null || request.tacticalPosture != null ||
                request.tacticalGround != null || request.tacticalCommand != null)
                return reject(NavigationRejection.IDENTITY)
            return NavigationResult(existing, existing)
        }
        // Offline settlement only finishes existing work; it never creates a flagship for an absent captain.
        if (request.offlineSettle && existing == null) return reject(NavigationRejection.SESSION)
        val countries=configuredCountries ?: CountryCatalogLoader.current()
        if(request.country!=null && request.country.value !in countries.countries)return reject(NavigationRejection.IDENTITY)
        val start=existing?.grid ?: initialDock?.grid ?: galaxy.startFor(request.country) ?: request.country?.let {country->
            CountryFounding.activeCapitals(stocks,countries)[country]?.let {capital->
                galaxy.conditions[capital]?.grid
            }
        } ?: return reject(NavigationRejection.BASE)
        val style=existing?.shipStyle ?: request.shipStyle ?: request.country?.let {
            if(it.value !in countries.countries)return reject(NavigationRejection.IDENTITY)
            countries.shipStyle(it)
        } ?: return reject(NavigationRejection.IDENTITY)
        if(style.value !in setOf("empire","alliance"))return reject(NavigationRejection.UNIT)
        var initial = existing ?: run {
            val country = request.country
            val home = country?.let { owner ->
                val candidates = galaxy.bases.filter { it.grid == start }.filter { base ->
                    val condition = galaxy.conditions.getValue(base.id)
                    val stock = stocks?.bases?.get(base.id)
                    if(stocks!=null && stock==null)return@filter false
                    val allegiance = stock?.owner ?: galaxy.initialOwners.getValue(base.id)
                    condition.habitability == 1 && allegiance == Allegiance.Country(owner) &&
                        (stock?.camp ?: condition.camp) == 0
                }
                candidates.firstOrNull { Capital.of(it)?.power == owner.value } ?: candidates.minByOrNull { it.id }
            }
            Navigator(request.generation, request.characterId, country, start, policy.initialCp,
                policy.initialCp, policy.initialCruise, baseId=initialDock?.baseId ?: 0, mode=if(initialDock==null)5 else 4,
                shipStyle = style, originBase = home?.id ?: 0)
        }
        if (stocks != null) initial = clearInvalidReturnBase(initial, stocks)
        if (initial.offlineWarpCancellation != null && initial.offlineWarpCancellation == request.acknowledgedOfflineWarpCancellation)
            initial = initial.copy(offlineWarpCancellation = null)
        val now = session.gameMillis / 1000
        if (initial.hull == null && request.shipKind != null) {
            val ship=Flagships.find(request.shipKind) ?: return reject(NavigationRejection.UNIT)
            if(ship.power != initial.shipStyle.nativePower) return reject(NavigationRejection.UNIT)
            initial=initial.copy(hull=FlagshipHull(ship.kind,now,clockScale=clockScale))
        }
        val wounded=initial.hull?.woundedUntil?.let {now<it} == true
        val detained=stocks?.social?.justice?.detained(request.characterId)==true
        // Combat settlement still needs a detainee's last pose to cancel saved movement.
        val tactical = if(wounded) false else if(detained) initial.cpPaused else request.tactical ?: if(stocks == null) initial.cpPaused else
            TacticalContact.active(galaxy,initial,snapshot(session.generation).values,stocks,session.eliminated.keys)
        val ready = (if(initial.cpPaused == tactical) initial else initial.copy(cpPaused=tactical,cpRecoveryAt=now,
            commandRange=if(tactical)TacticalCommandRange(now,command=request.commandAbility ?: 50) else null,
            motion=if(tactical)initial.motion else null,retreat=if(tactical)initial.retreat else null,
            charge=if(tactical)initial.charge else null,
            attack=if(tactical)initial.attack else null,
            fire=if(tactical)initial.fire else initial.fire?.copy(pending=null))).let {
                if(it.cpPaused && it.baseId==0 && it.charge==null &&
                    it.energy?.values?.let { values -> values[3]>0 || values[4]>0 }==true)
                    it.copy(charge=TacticalCharge(now, clockScale=clockScale)) else it
            }
        // Legacy tactical saves have no CRC history. Migrate once at full radius;
        // new tactical entry above starts at zero and reconnect never refills it.
        val ranged=if(ready.cpPaused && ready.baseId==0) ready.copy(commandRange=
            ready.commandRange?.let {if(request.commandAbility!=null && request.commandAbility!=it.command)
                it.settle(now,request.commandAbility) else it}
                ?: TacticalCommandRange(now,TacticalCommandRange.MAXIMUM,request.commandAbility ?: 50)) else ready
        val fueled = settleWork(ranged, now)
        completedFuel = initial.fuelReadyAt != null && fueled.fuelReadyAt == null
        completedDock = initial.dockTarget != null && fueled.dockTarget == null
        val dueWarp = ranged.warpTarget?.takeIf { fueled.warpTarget == null && !fueled.cpPaused }
        completedWarp = dueWarp != null
        // Docking reservations cannot be cancelled or refunded (design 2026-10-30, evidence:guess). A destination
        // that stopped belonging to the player's camp before execution keeps the flagship at its origin.
        val dueDock = ranged.dockTarget?.takeIf { fueled.dockTarget == null }
        val dockOwner=dueDock?.let {id->
            val base=galaxy.conditions[id]
            val stock=stocks?.bases?.get(id)
            base!=null && (stocks==null || stock!=null) &&
                ownBase(ranged,stock?.owner ?: galaxy.initialOwners.getValue(id),stock?.camp ?: base.camp,stocks)
        }
        val docked = if (dueDock != null && dockOwner!=true)
            fueled.copy(baseId = ranged.baseId, mode = ranged.mode, moves = ranged.moves) else fueled
        // Without known abilities CP recovery waits for the next login (its clock is not advanced).
        val recovered = if (request.offlineSettle && !request.offlineAbilities) docked else
            recoverCp(docked, request.politics, request.administration, now)
        val settled = recovered
        fun contact(player: Navigator): Navigator {
            val active = if(stocks == null) player.cpPaused else
                TacticalContact.active(galaxy,player,snapshot(session.generation).values,stocks,session.eliminated.keys)
            return if(active == player.cpPaused) player else player.copy(cpPaused=active,cpRecoveryAt=now,
                commandRange=if(active)TacticalCommandRange(now,command=request.commandAbility ?: 50) else null,
                motion=if(active)player.motion else null,retreat=if(active)player.retreat else null,
                charge=if(active)player.charge else null,
                attack=if(active)player.attack else null,
                fire=if(active)player.fire else player.fire?.copy(pending=null))
        }
        fun enterableFrom(player: Navigator, units: Int, solo: Boolean): (Int) -> Boolean {
            val forces = GridForces.collect(galaxy, session.generation, snapshot(session.generation).values, stocks, session.eliminated.keys)
            return { grid -> GridEntryRules.evaluate(GridForces.state(galaxy, grid, forces),
                GridEntry(Camp.of(player, stocks?.let { CoupActions.rebel(it, player.characterId,player.country) } == true), units, solo)) == GridEntryVerdict.ALLOWED }
        }
        fun warpObstacle(player: Navigator, target: Int): NavigationRejection? {
            val dx = abs(target % 100 - player.grid % 100)
            val dy = abs(target / 100 - player.grid / 100)
            if (dx * dx + dy * dy > policy.maximumRange * policy.maximumRange) return NavigationRejection.RANGE
            return when (WarpPreconditions.evaluate(WarpContext(player.cruise, galaxy.kind(target) == GridKind.STAR_SYSTEM,
                maxOf(dx, dy) == 1))) {
                WarpDecision.STAR_SYSTEM_NOT_ADJACENT -> NavigationRejection.STAR_SYSTEM_NOT_ADJACENT
                WarpDecision.INSUFFICIENT_CRUISE -> NavigationRejection.CRUISE
                WarpDecision.ALLOWED -> if (player.cruise < warpCruise(player.grid, target)) NavigationRejection.CRUISE else null
            }
        }
        fun executeWarp(player: Navigator, target: Int, enterable: (Int) -> Boolean): Navigator {
            // The salt changes with every completed move, so a rejected attempt cannot be retried for a new roll.
            val arrival = WarpCourse.arrival(galaxy, player.grid, target,
                session.generation * 0x9E3779B1L xor (player.characterId.toLong() shl 20) xor player.moves, enterable)
            return contact(player.copy(grid = arrival, cruise = player.cruise - warpCruise(player.grid, target),
                moves = Math.addExact(player.moves, 1), motion = null,
                posture = player.posture.takeIf { arrival == player.grid }))
        }
        val before = if (dueWarp == null) settled else {
            // The reserved route is rechecked at execution without refunding the paid CP.
            val enterable = enterableFrom(settled, movingUnits, soloShip)
            warpCancellation = warpObstacle(settled, dueWarp) ?: NavigationRejection.GRID.takeUnless { enterable(dueWarp) }
            if (warpCancellation == null) executeWarp(settled, dueWarp, enterable)
            else if (request.offlineSettle) settled.copy(offlineWarpCancellation = warpCancellation)
            else settled
        }
        if (persist && existing != before) commit(request.account, before, session.generation)
        current = before
        if (wounded && (request.destination != null || request.place != null || request.supplyFuel ||
            request.energy != null || request.tacticalMove != null || request.tacticalStop != null ||
            request.tacticalRetreat != null || request.tacticalShoot != null || request.tacticalAttack != null)) return reject(NavigationRejection.WOUNDED)
        // U-25: a detainee stays in the holding room until released (warp, docking, spot moves and supply refused).
        if (detained &&
            (request.destination != null || request.place != null || request.supplyFuel ||
                request.tacticalMove != null || request.tacticalRetreat != null))
            return reject(NavigationRejection.BUSY).copy(blockedByDetention = true)
        if (request.shipKind!=null && before.hull!=null && request.shipKind!=before.hull.kind &&
            (request.energy!=null || request.tacticalMove!=null || request.tacticalRetreat!=null ||
                request.tacticalStop!=null || request.tacticalShoot!=null || request.tacticalAttack!=null)) return reject(NavigationRejection.UNIT)
        request.tacticalRetreat?.let { units ->
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            if (units != listOf(before.unitId)) return reject(NavigationRejection.UNIT)
            if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(before.hull?.kind ?: checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != before.shipStyle.nativePower) return reject(NavigationRejection.UNIT)
            if (before.retreat != null) return reject(NavigationRejection.BUSY)
            if (before.energy?.values?.get(1) != TacticalEnergy.WARP_MAX) return reject(NavigationRejection.ENERGY)
            val pose = before.motion?.pose(now,before.posture,galaxy) ?: TacticalDeployment.initial(before.unitId, before.grid,
                snapshot(session.generation).values + before, stocks, galaxy,countries)
            if (!RetreatRules.outside(pose)) return reject(NavigationRejection.INSIDE_RADAR)
            val target = RetreatRules.destination(galaxy, before, snapshot(session.generation).values, stocks, pose, session.eliminated.keys)
                ?: return reject(NavigationRejection.GRID)
            if (now > 0xffffffffL - 155 * clockScale) return reject(NavigationRejection.CLOCK)
            val after = before.copy(retreat = TacticalRetreat(target, now, 5 * clockScale, 150 * clockScale))
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.tacticalStop?.let { stop ->
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            // Fleet/base authority is not yet implemented. Never silently stop another commander's units.
            if (stop.units != listOf(before.unitId) || stop.bases.isNotEmpty()) return reject(NavigationRejection.UNIT)
            if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(before.hull?.kind ?: checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != before.shipStyle.nativePower) return reject(NavigationRejection.UNIT)
            if (now > 0xffffffffL - TacticalMotion.WAIT_SECONDS) return reject(NavigationRejection.CLOCK)
            val pose = before.motion?.pose(now,before.posture,galaxy) ?: TacticalDeployment.initial(before.unitId, before.grid,
                snapshot(session.generation).values + before, stocks, galaxy,countries)
            // Retain an authoritative stationary pose; null would restore the initial deployment on reconnect.
            val after = before.copy(motion = TacticalMotion(before.grid, now, pose, 0f, pose.direction, listOf(pose.position)),
                attack = null, fire = before.fire?.copy(pending = null),posture=before.posture?.copy(order=null))
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.tacticalMove?.let { move ->
            // Formation rotations require the actor's joint navigation/stock transaction.
            if(move.maneuver!=null)return reject(NavigationRejection.DISABLED)
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            if (move.units != listOf(before.unitId)) return reject(NavigationRejection.UNIT)
            if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(before.hull?.kind ?: checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != before.shipStyle.nativePower) return reject(NavigationRejection.UNIT)
            val maximum = (before.energy ?: TacticalEnergy.ZERO).values[0] / 100f
            // The CD sends its full speed (1.0) whatever the ENGINE slider says (run-32, ENGINE 10): cap it to the
            // allocation, as an energy change caps a running motion, and refuse only a move without any ENGINE.
            if (move.velocity > 0f && maximum <= 0f) return reject(NavigationRejection.ENERGY)
            val velocity = minOf(move.velocity, maximum)
            if (now > 0xffffffffL - 5*clockScale) return reject(NavigationRejection.CLOCK)
            val origin = before.motion?.takeIf { it.grid == before.grid }?.pose(now,before.posture,galaxy) ?:
                TacticalDeployment.initial(before.unitId, before.grid,
                    snapshot(session.generation).values + before, stocks, galaxy,countries)
            if (TacticalTerrain.pathBlocked(galaxy, before.grid, origin.position, move.waypoints)) return reject(NavigationRejection.RANGE)
            val after = before.copy(motion = TacticalMotion(before.grid, now, origin,
                velocity*(if(move.parallel)0.5f else 1f),if(move.parallel)origin.direction else move.direction, move.waypoints,move.parallel,
                turnRate=TacticalMotion.NATIVE_TURN_RATE,waitSeconds=5*clockScale))
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.energy?.let { energy ->
            if (detained) return reject(NavigationRejection.BUSY)
            if (!before.cpPaused) return reject(NavigationRejection.TACTICAL)
            if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
            if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
            val ship = Flagships.find(before.hull?.kind ?: checkNotNull(request.shipKind)) ?: return reject(NavigationRejection.UNIT)
            if (ship.power != before.shipStyle.nativePower) return reject(NavigationRejection.UNIT)
            if (energy.allocated > ship.number("出力:出力") || energy.values.zip(TacticalEnergy.MAXIMUM).any {it.first>it.second})
                return reject(NavigationRejection.ENERGY)
            val motion = before.motion?.let { motion ->
                val slowed = stocks?.tacticalCommands?.air?.values?.any {
                    it.grid == before.grid && it.target == before.unitId && it.mode == 1 && it.slowVelocity != null
                } == true
                val maximum = energy.values[0] / 100f * (if (motion.parallel) 0.5f else 1f) * (if (slowed) 0.5f else 1f)
                if (motion.grid != before.grid || motion.velocity <= maximum) motion else motion.copy(
                    acceptedAt = maxOf(now, motion.startsAt) - motion.waitSeconds,
                    origin = motion.pose(now,before.posture,galaxy), velocity = maximum, waypoints = motion.remaining(now),reportedWaypoints=0)
            }
            val charge = if(before.charge!=null || energy.values[3]>0 || energy.values[4]>0)
                (before.charge ?: TacticalCharge(now, clockScale=clockScale)).settle(now, before.energy ?: TacticalEnergy.ZERO) else null
            val after = before.copy(energy = energy, motion = motion, charge = charge,
                hull=before.hull?.settle(now,before.energy ?: TacticalEnergy.ZERO),
                retreat = if (energy.values[1] == TacticalEnergy.WARP_MAX) before.retreat else null)
            if (persist && after != before) commit(request.account, after, session.generation)
            return NavigationResult(after, before)
        }
        request.returnBase?.let { target ->
            if (target != 0) returnBaseRejection(before, target, stocks)?.let { return reject(it) }
            val after = before.copy(returnBase = target)
            if (persist && after != before) commit(request.account, after, session.generation)
            return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock, completedWarp = completedWarp, warpCancellation = warpCancellation)
        }
        val destination = request.destination
        if (destination == null && request.place == null && !request.supplyFuel) {
            return NavigationResult(before, existing, completedFuel = completedFuel, completedDock = completedDock, completedWarp = completedWarp, warpCancellation = warpCancellation)
        }
        if (before.cpPaused) return reject(NavigationRejection.TACTICAL)
        if (before.fuelReadyAt != null) return reject(NavigationRejection.SUPPLYING)
        if (before.dockTarget != null || before.warpTarget != null) return reject(NavigationRejection.TRAVELLING)
        if (request.supplyFuel) {
            if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
            if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
            if (before.baseId != 0 || before.spotId != 0 || before.mode != 5) return reject(NavigationRejection.EMBARKED)
            if (before.cruise >= policy.maximumCruise) return reject(NavigationRejection.FUEL_FULL)
            // W68: 48..960 hours. The missing-fuel interpolation is a local policy.
            val hours = maxOf(48L, ((policy.maximumCruise - before.cruise).toLong() * 960 + policy.maximumCruise - 1) / policy.maximumCruise)
            val pipeline = NavigationWork.pipeline(before, policy, hours)
            val submitted = pipeline.submit(NavigationWork.FUEL, listOf(NavigationWork.CAPTAIN), now * 1000, before.cpPaused)
            if (submitted is SubmissionResult.Rejected) return reject(if (submitted.reason == StrategyRejection.TIME_REVERSED)
                NavigationRejection.CLOCK else NavigationRejection.CP)
            val job = (submitted as SubmissionResult.Accepted).job
            if (job.completesAtGameMillis / 1000 > 0xffffffffL) return reject(NavigationRejection.CLOCK)
            val after = before.copy(fuelReadyAt = job.completesAtGameMillis / 1000, strategy = pipeline.checkpoint()).paidWith(pipeline.balance)
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock, completedWarp = completedWarp, warpCancellation = warpCancellation)
        }
        if (request.place != null) {
            if (destination != null) return reject(NavigationRejection.BASE)
            val moved = movePlace(request, before, now, stocks,countries)
            val player = moved.player?.let(::contact)
            val result = if (player?.cpPaused == true && player.baseId == 0 && before.baseId != 0) {
                val base = galaxy.bases.single { it.id == before.baseId }
                moved.copy(player = player.copy(motion = TacticalDeployment.departure(base, now, stocks, slot = 0)))
            } else moved.copy(player = player)
            if (persist) result.player?.let { commit(request.account, it, session.generation) }
            return result.copy(completedFuel = completedFuel, completedDock = completedDock, completedWarp = completedWarp, warpCancellation = warpCancellation)
        }
        if (before.baseId != 0 || before.spotId != 0) return reject(NavigationRejection.EMBARKED)
        if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
        if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
        if (destination == null || destination !in 0..4999) return reject(NavigationRejection.GRID)
        if (destination == before.grid) return reject(NavigationRejection.SAME_GRID)
        // Cruise is light-years (CD p.31); policy.cruiseCost is the cost of one 100-ly grid.
        warpObstacle(before, destination)?.let { return reject(it) }
        val enterable = enterableFrom(before, movingUnits, soloShip)
        if (!enterable(destination)) return reject(NavigationRejection.GRID)
        // W p.68: CP and execution wait grow with distance. 40 per started 100 ly (CD help: x4 at most) and
        // one G-hour per grid beyond the first are revival policy (design 2026-10-30, evidence:guess).
        val grids = (WarpCourse.cruiseCost(before.grid, destination) + WarpCourse.LIGHT_YEARS_PER_GRID - 1) / WarpCourse.LIGHT_YEARS_PER_GRID
        val cost = policy.warpCost.toLong() * if (operationTarget(before, destination, now, stocks)) 1 else minOf(4, grids)
        val waitHours = (grids - 1).toLong()
        if (waitHours > 0) {
            val pipeline = NavigationWork.pipeline(before, policy, warpCost = cost, warpWaitHours = waitHours)
            val submitted = pipeline.submit(NavigationWork.WARP, listOf(NavigationWork.CAPTAIN), now * 1000, before.cpPaused)
            if (submitted is SubmissionResult.Rejected) return reject(if (submitted.reason == StrategyRejection.TIME_REVERSED)
                NavigationRejection.CLOCK else NavigationRejection.CP)
            val job = (submitted as SubmissionResult.Accepted).job
            if (job.completesAtGameMillis / 1000 > 0xffffffffL) return reject(NavigationRejection.CLOCK)
            val after = before.copy(warpTarget = destination, strategy = pipeline.checkpoint()).paidWith(pipeline.balance)
            if (persist) commit(request.account, after, session.generation)
            return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock,
                completedWarp = completedWarp, warpCancellation = warpCancellation)
        }
        val payment = CpPayment.charge(CpBalance(before.pcp.toLong(), before.mcp.toLong()), cost, CpPool.MCP)
        if (payment !is PaymentResult.Paid) return reject(NavigationRejection.CP)
        val after = executeWarp(before.paidWith(payment.balance), destination, enterable)
        if (persist) commit(request.account, after, session.generation)
        return NavigationResult(after, before, completedFuel = completedFuel, completedDock = completedDock,
            completedWarp = completedWarp, warpCancellation = warpCancellation)
    }
    internal fun clearInvalidReturnBase(player:Navigator,stocks:PlanetStocks):Navigator =
        if(player.returnBase!=0 && returnBaseRejection(player,player.returnBase,stocks)!=null)
            player.copy(returnBase=0) else player

    internal fun clearLostDockReservation(player: Navigator, previous: PlanetStocks, updated: PlanetStocks): Navigator {
        val target = player.dockTarget ?: return player
        val before = previous.bases[target] ?: return player
        val after = updated.bases[target] ?: return player
        if (!ownBase(player, before.owner, before.camp, previous) || ownBase(player, after.owner, after.camp, updated)) return player
        return player.copy(dockTarget = null, strategy = null)
    }

    private fun returnBaseRejection(player: Navigator, target: Int, stocks: PlanetStocks?): NavigationRejection? {
        val condition = galaxy.conditions[target] ?: return NavigationRejection.BASE
        if (condition.habitability == 0) return NavigationRejection.BASE
        val stock = stocks?.bases?.get(target)
        if (stocks != null && stock == null) return NavigationRejection.BASE
        val owner = stock?.owner ?: galaxy.initialOwners.getValue(target)
        val camp = stock?.camp ?: condition.camp
        return NavigationRejection.ACCESS.takeUnless { ownBase(player, owner, camp, stocks) }
    }

    private fun ownBase(player:Navigator,owner:Allegiance,camp:Int,stocks:PlanetStocks?):Boolean {
        val playerCamp=stocks?.let {CoupActions.camp(it,player.characterId,player.country)} ?: 0
        return TacticalAuthority.ownsBase(player,owner,camp,playerCamp)
    }
    internal fun captainCard(request:NavigationRequest,player:Navigator,
        countries:CountryCatalog=configuredCountries ?: CountryCatalogLoader.current()):Boolean {
        val card=TacticalAuthority.captainCard(player,countries) ?: return false
        return request.captainCard==card
    }
    /** CD confirmation: approaching an issued operation target costs the base price (80 of 320 before the patch). */
    private fun operationTarget(player: Navigator, destination: Int, now: Long, stocks: PlanetStocks?): Boolean =
        stocks != null && !CoupActions.rebel(stocks,player.characterId,player.country) &&
        stocks.strategic.operations.any { operation -> operation.live &&
            operation.country==player.country && operation.state != StrategicPlanState.PLANNED && player.characterId in operation.characters &&
            operation.issuedAt?.let { now in it until OperationPlanRules.endGameSeconds(it) } == true &&
            abs(operation.grid % 100 - destination % 100) <= 1 && abs(operation.grid / 100 - destination / 100) <= 1 }
    /** Runs on clock advances as well as requests, including while the captain is offline. */
    fun settleRetreats(session: SessionState, stocks: PlanetStocks?) {
        if (session.ended != null) return
        val now = session.gameMillis / 1000
        val next = snapshot(session.generation).toMutableMap()
        var changed = false
        for ((account, player) in next.toSortedMap()) {
            val retreat = player.retreat ?: continue
            val delegationEnded = retreat.issuedByOfflineAi &&
                stocks?.let { TacticalOfflineAi.eligible(session, account, player, it) } != true
            if (player.country in session.eliminated || session.participants[account]?.country in session.eliminated ||
                BattleDeath.deceased(stocks,player.characterId) ||
                stocks?.social?.justice?.detained(player.characterId)==true || delegationEnded) {
                next[account] = player.copy(retreat=null)
                changed = true
                continue
            }
            if (!TacticalContact.active(galaxy, player, next.values, stocks, session.eliminated.keys)) {
                next[account] = player.copy(cpPaused = false, motion = null, retreat = null, charge = null,commandRange=null,
                    attack=null,fire=player.fire?.copy(pending=null),cpRecoveryAt = now)
                changed = true
                continue
            }
            if (now < retreat.completesAt) continue
            val pose = player.motion?.pose(now,player.posture,galaxy) ?: continue
            if (!RetreatRules.outside(pose)) continue
            val forces = GridForces.collect(galaxy, session.generation, next.values, stocks, session.eliminated.keys)
            if (!RetreatRules.allowed(galaxy, player, retreat.destination, forces, stocks)) continue
            val moved = player.copy(grid = retreat.destination, motion = null, retreat = null, charge = null,commandRange=null,
                attack=null,fire=player.fire?.copy(pending=null),posture=null,
                cpRecoveryAt = now, moves = Math.addExact(player.moves, 1))
            val active = TacticalContact.active(galaxy, moved, next.values, stocks, session.eliminated.keys)
            next[account] = moved.copy(cpPaused = active,commandRange=if(active)TacticalCommandRange(now,
                command=player.commandRange?.command ?: 50) else null)
            changed = true
        }
        if (changed) {
            store?.save(next)
            players = next.toMap()
        }
    }
    private fun settleWork(player: Navigator, now: Long): Navigator {
        if (player.cpPaused) return player
        // An injury can begin after the travel order was paid for. Leave it queued until recovery.
        if ((player.warpTarget != null || player.dockTarget != null) && now < (player.hull?.woundedUntil ?: 0)) return player
        val checkpoint = player.strategy
        if (checkpoint == null) { // Preserve deadlines already acknowledged by pre-job servers.
            return if (player.fuelReadyAt?.let { now >= it } == true)
                player.copy(cruise = maxOf(player.cruise, policy.maximumCruise), fuelReadyAt = null) else player
        }
        if (checkpoint.jobs.isEmpty() || now * 1000 < checkpoint.lastGameMillis) return player
        val pipeline = NavigationWork.pipeline(player, policy)
        val events = pipeline.advance(now * 1000)
        if (events.isEmpty()) return player
        val completed = events.any { it.kind == JobEventKind.COMPLETED }
        return player.copy(strategy = pipeline.checkpoint(dropCompleted = true),
            cruise = if (completed && player.fuelReadyAt != null) maxOf(player.cruise, policy.maximumCruise) else player.cruise,
            fuelReadyAt = if (completed) null else player.fuelReadyAt,
            baseId = if (completed && player.dockTarget != null) player.dockTarget else player.baseId,
            mode = if (completed && player.dockTarget != null) 4 else player.mode,
            moves = if (completed && player.dockTarget != null) Math.addExact(player.moves, 1) else player.moves,
            dockTarget = if (completed) null else player.dockTarget,
            warpTarget = if (completed) null else player.warpTarget)
    }
    /** W27 fixes the interval and offline recovery. Cap and ability formula are provisional. */
    fun recoverCp(player: Navigator, politics: Int, administration: Int, now: Long,
        penalty: Int? = null): Navigator {
        val last = player.cpRecoveryAt
        // Legacy files have no trustworthy recovery time. Start a clock without inventing a backlog.
        if (last == null || now < last || player.cpPaused) return player.copy(cpRecoveryAt = now)
        val periods = (now - last) / cpIntervalSeconds
        if (periods == 0L) return player
        val reduction = penalty ?: recoveryPenalty(player)
        fun balance(current: Int, ability: Int) = if (current >= policy.maximumCp) current else
            minOf(policy.maximumCp.toLong(), current.toLong() + periods * (policy.recoveryBase.toLong() + ability / 2)).toInt()
        return player.copy(pcp = balance(player.pcp, OfficeCpRecovery.effective(politics, reduction)),
            mcp = balance(player.mcp, OfficeCpRecovery.effective(administration, reduction)),
            cpRecoveryAt = last + periods * cpIntervalSeconds)
    }
    private fun movePlace(request: NavigationRequest, before: Navigator, now: Long, stocks: PlanetStocks?,countries:CountryCatalog): NavigationResult {
        fun reject(reason: NavigationRejection) = NavigationResult(null, previous = before, rejection = reason)
        fun departureRejection(): NavigationRejection? {
            if (before.baseId == 0 || before.mode != 4) return NavigationRejection.BASE
            val base = galaxy.conditions[before.baseId] ?: return NavigationRejection.BASE
            val stock = stocks?.bases?.get(base.baseId)
            return NavigationRejection.ACCESS.takeIf {
                base.grid != before.grid || !ownBase(before, stock?.owner ?: galaxy.initialOwners.getValue(base.baseId),
                    stock?.camp ?: base.camp, stocks)
            }
        }
        val after: Navigator
        val cost: Long
        val pool: CpPool
        when (val move = checkNotNull(request.place)) {
            is PlaceMove.SwitchMode -> {
                if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
                if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
                if (move.mode != 5) return reject(NavigationRejection.BASE)
                departureRejection()?.let { return reject(it) }
                // The CD departs from its flagship quay. A public lobby or another
                // person's private room must never move this character's ship.
                if (before.spotId != 0 && (before.spotOwner != 0 || stocks != null && before.baseId !in stocks.bases ||
                    galaxy.facilitiesAt(before.baseId, stocks,countries).none { facility -> facility.kind == 4 &&
                        facility.spots.any { it.id == before.spotId && it.kind == 6 } }))
                    return reject(NavigationRejection.EMBARKED)
                after = before.copy(baseId = 0, mode = 5, spotId = 0, spotOwner = 0)
                cost = 0; pool = CpPool.MCP // Same provisional departure policy as the existing Dock(0, 5).
            }
            is PlaceMove.Dock -> {
                if (request.unitId != before.unitId) return reject(NavigationRejection.UNIT)
                if (!captainCard(request,before,countries)) return reject(NavigationRejection.CARD)
                if (before.spotId != 0) return reject(NavigationRejection.EMBARKED)
                if (move.baseId == 0) {
                    if (move.mode != 5) return reject(NavigationRejection.BASE)
                    departureRejection()?.let { return reject(it) }
                } else {
                    val base = galaxy.conditions[move.baseId] ?: return reject(NavigationRejection.BASE)
                    if (base.grid != before.grid || move.mode != 4) return reject(NavigationRejection.BASE)
                    val stock=stocks?.bases?.get(base.baseId)
                    if (stocks != null && stock == null) return reject(NavigationRejection.BASE)
                    if (!ownBase(before,stock?.owner ?: galaxy.initialOwners.getValue(base.baseId),stock?.camp ?: base.camp,stocks))
                        return reject(NavigationRejection.ACCESS)
                    if (before.baseId == move.baseId && before.mode == move.mode) return reject(NavigationRejection.BASE)
                    val pipeline = NavigationWork.pipeline(before, policy)
                    val submitted = pipeline.submit(NavigationWork.DOCK, listOf(NavigationWork.CAPTAIN), now * 1000, before.cpPaused)
                    if (submitted is SubmissionResult.Rejected) return reject(if (submitted.reason == StrategyRejection.TIME_REVERSED)
                        NavigationRejection.CLOCK else NavigationRejection.CP)
                    val job = (submitted as SubmissionResult.Accepted).job
                    if (job.completesAtGameMillis / 1000 > 0xffffffffL) return reject(NavigationRejection.CLOCK)
                    return NavigationResult(before.copy(dockTarget = move.baseId, strategy = pipeline.checkpoint()).paidWith(pipeline.balance), before)
                }
                after = before.copy(baseId = move.baseId, mode = move.mode, spotId = 0, spotOwner = 0)
                cost = 0 // Departure retains its existing explicit local policy.
                pool = CpPool.MCP
            }
            is PlaceMove.Spot -> {
                if (before.baseId == 0) return reject(NavigationRejection.BASE)
                val condition = galaxy.conditions[before.baseId]
                val stored = stocks?.bases?.get(before.baseId)
                val available = condition != null && (stocks == null || stored != null)
                if (move.id != 0 && !available) return reject(NavigationRejection.BASE)
                val facilities = if (available) galaxy.facilitiesAt(before.baseId, stocks,countries) else emptyList()
                val target = facilities.singleOrNull { f -> f.spots.any { it.id == move.id } }
                if (move.id == 0) {
                    if (move.owner != before.characterId || before.spotId == 0) return reject(NavigationRejection.SPOT)
                } else {
                    val base = checkNotNull(condition)
                    val friendly = ownBase(before,stored?.owner ?: galaxy.initialOwners.getValue(base.baseId),
                        stored?.camp ?: base.camp,stocks)
                    val spot = target?.spots?.single { it.id == move.id } ?: return reject(NavigationRejection.SPOT)
                    if (move.id == before.spotId && move.owner == before.spotOwner) return reject(NavigationRejection.SPOT)
                    val allowed = when (spot.access) {
                        SpotAccess.CLOSED -> false
                        SpotAccess.PUBLIC -> move.owner == 0
                        SpotAccess.GUARDED -> move.owner == 0 && friendly
                        SpotAccess.RANK -> move.owner == 0 && request.rank <= if (spot.kind == 10) 8 else 13
                        SpotAccess.PRIVATE -> when (spot.kind) {
                            // CD room picker sends owner 0 for the quay template. Its owner is
                            // resolved from the authenticated flagship, never from a client ID.
                            6 -> move.owner == 0 && request.unitId == before.unitId &&
                                friendly
                            13 -> move.owner == before.characterId
                            // Own mansion rooms (U-18): the ballroom in a capital, the forest on one's own fief.
                            // The CD knows these rooms only with owner 0; entry itself checks the right (U-18).
                            // The country's current capital (moved capitals included), still owned by it and
                            // not rebel-held; a country missing from the catalogue has none.
                            14 -> move.owner == 0 && before.country?.let { country -> country.value in countries.countries &&
                                (if (stocks != null) OrganizationalOfficePolicy(galaxy, countries).capital(stocks, country)
                                else galaxy.bases.singleOrNull { Capital.of(it)?.power == country.value }?.id) == before.baseId } == true
                            15 -> move.owner == 0 && stocks?.strategic?.fiefs?.get(before.baseId) == before.characterId
                            // constmsg 74/63 is the imperial planetary governor's office (card 54).
                            63 -> move.owner == 0 && friendly && stocks != null && before.country?.let {country->
                                OrganizationalOfficePolicy(galaxy,countries).holders(stocks,54,country)
                                    .any {it.seat==before.baseId && it.character==before.characterId}
                            } == true
                            else -> false
                        }
                    }
                    if (!allowed) return reject(NavigationRejection.ACCESS)
                }
                val origin = facilities.singleOrNull { f -> f.spots.any { it.id == before.spotId } }
                cost = if (target != null && origin?.id == target.id) 5 else 10 // W p.69
                pool = CpPool.PCP
                after = before.copy(spotId = move.id, spotOwner = if (move.id == 0) 0 else move.owner)
            }
        }
        val payment = CpPayment.charge(CpBalance(before.pcp.toLong(), before.mcp.toLong()), cost, pool)
        if (payment !is PaymentResult.Paid) return reject(NavigationRejection.CP)
        return NavigationResult(after.copy(posture = if(before.baseId!=0 && after.baseId==0)null else after.posture,
            moves = Math.addExact(before.moves, 1)).paidWith(payment.balance), before)
    }
    private fun commit(account: String, player: Navigator, generation: Long) {
        val next = snapshot(generation) + (account to player)
        store?.save(next)
        players = next
    }
    fun publishCommitted(account: String, expected: Navigator, next: Navigator) {
        check(players[account] == expected)
        players = snapshot(next.generation) + (account to next)
    }
    fun saveReplacement(expected: Map<String, Navigator>, next: Map<String, Navigator>, generation: Long) {
        check(snapshot(generation) == expected)
        store?.save(next)
        publishCommitted(expected, next, generation)
    }
    fun publishCommitted(expected: Map<String, Navigator>, next: Map<String, Navigator>, generation: Long) {
        check(snapshot(generation) == expected)
        require(next.values.all { it.generation == generation })
        players = next.toMap()
    }
}
