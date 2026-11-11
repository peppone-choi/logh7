package org.logh7.engine

enum class GroundTransferAction { LOAD, UNLOAD }
data class GroundTransferRequest(val account:String,val connection:String,val characterId:Int,val country:CountryId?,
    val generation:Long,val outfit:Int,val action:GroundTransferAction,val politics:Int=50,val administration:Int=50) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,outfit:Int,
        action:GroundTransferAction,politics:Int=50,administration:Int=50):
        this(account,connection,characterId,power.country,generation,outfit,action,politics,administration)
    init {require(characterId>0 && generation>0 && outfit>0 && politics in 0..65535 && administration in 0..65535)}
}
data class GroundTransferResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:LogisticsRejection?=null,
    val changed:Boolean=false,val moved:List<Int> = emptyList(),val recovery:NavigationResult?=null)

/** Manual PDF69: transfer all own-outfit marines, CP80, no waiting.
 * MCP, ten slots per commissioned ship, stable ID-first carrier selection and resolution of
 * unknown local legacy allocation are replacement policies. The native three wire values
 * are not interpreted as quantities or identities. Author: Peppone Choi.
 */
internal object GroundTransfer {
    const val COST=80
    const val CARRIER_SLOTS=10 // Verified wire bound (00419ca0 holds at most ten troops per unit).
    const val WARSHIP_SLOTS=2
    /** Per-hull capacity (user decision 2026-10-31, evidence:guess): troop transports and landing
     * ships use the full wire bound of 10, every other hull 2. The manual table has no column. */
    fun capacity(kind:Int):Int = ProductionUnits.definitions.singleOrNull {it.ship && it.kind==kind}?.name
        ?.let {if(it.startsWith("兵員輸送艦") || it.startsWith("揚陸艦"))CARRIER_SLOTS else WARSHIP_SLOTS} ?: WARSHIP_SLOTS
    fun freeBerths(roster:ActiveGroundUnits,ships:List<ActiveShipUnit>):Map<Int,Int> =
        ships.associate {ship->ship.id to (capacity(ship.stack.kind)-roster.values.count {it.carrier==ship.id})}

    /** Both manual transfer and NPC loading keep the unit and prefer its original carrier when available. */
    fun board(roster:ActiveGroundUnits,ships:List<ActiveShipUnit>,selected:List<ActiveGroundUnit>):ActiveGroundUnits {
        val free=freeBerths(roster,ships).toMutableMap()
        val placements=selected.associate {unit->
            val carrier=ships.firstOrNull {it.id==unit.origin && free.getValue(it.id)>0}
                ?: ships.first {free.getValue(it.id)>0}
            free[carrier.id]=free.getValue(carrier.id)-1
            unit.id to unit.copy(carrier=carrier.id,base=0,origin=0)
        }
        return ActiveGroundUnits(roster.values.map {placements[it.id] ?: it})
    }

    fun evaluate(r:GroundTransferRequest,s:SessionState,p:Navigator?,stocks:PlanetStocks):GroundTransferResult {
        fun reject(reason:LogisticsRejection)=GroundTransferResult(p,stocks,reason)
        val member=s.participants[r.account]
        if(s.ended!=null || r.country in s.eliminated || s.generation!=r.generation || stocks.generation!=r.generation || member==null ||
            !member.online || member.connection!=r.connection)return reject(LogisticsRejection.SESSION)
        if(p==null || p.characterId!=r.characterId || p.generation!=r.generation || p.country!=r.country || member.country!=r.country)
            return reject(LogisticsRejection.IDENTITY)
        val o=stocks.outfits[r.outfit] ?: return reject(LogisticsRejection.OUTFIT)
        if(r.country==null)return reject(LogisticsRejection.ACCESS)
        val base=o.position?.baseId ?: o.baseId
        val owner=stocks.bases[base] ?: return reject(LogisticsRejection.LOCATION)
        val camp=TacticalAuthority.camp(p,stocks)
        if(o.commanderId!=r.characterId || TacticalAuthority.camp(o,stocks)!=camp || TacticalAuthority.camp(owner)!=camp)
            return reject(LogisticsRejection.ACCESS)
        if(p.baseId!=base || p.cpPaused || p.navigationPending)return reject(LogisticsRejection.LOCATION)
        val roster=o.activeGround ?: return reject(LogisticsRejection.STOCK)
        val ships=o.activeShips ?: return reject(LogisticsRejection.STOCK)
        if(roster.values.any {it.order!=null} || ships.values.any {it.support!=null || it.posture?.order!=null ||
                it.tactical?.retreat!=null || it.attack!=null || it.shot!=null})return reject(LogisticsRejection.BUSY)
        val alive=ships.values.filter {it.boats>0}.sortedBy {it.id}
        if(alive.isEmpty())return reject(LogisticsRejection.STOCK)
        val selected=roster.values.filter {if(r.action==GroundTransferAction.UNLOAD)
            it.carrier>0 || it.base==0 || it.base==base && it.origin>0
            else it.carrier==0 && (it.base==base || it.base==0)}.sortedBy {it.id}
        val loaded=if(r.action==GroundTransferAction.UNLOAD) {
            val ids=selected.map {it.id}.toSet()
            ActiveGroundUnits(roster.values.map {if(it.id in ids)it.copy(carrier=0,base=base,origin=0) else it})
        } else {
            val free=freeBerths(roster,alive)
            if(alive.any {capacity(it.stack.kind)-free.getValue(it.id)>CARRIER_SLOTS} ||
                selected.size>free.values.sumOf {maxOf(0,it)} ||
                o.kind==OutfitKind.TRANSPORT && selected.isNotEmpty() && selected.size>TransportActions.freeUnitSlots(o,stocks.strategic.transports))
                return reject(LogisticsRejection.STOCK)
            board(roster,alive,selected)
        }
        val moved=roster.values.zip(loaded.values).mapNotNull {(before,after)->before.id.takeIf {before!=after}}
        if(moved.isEmpty())return GroundTransferResult(p,stocks)
        if(p.mcp<COST)return reject(LogisticsRejection.CP)
        val changed=o.copy(activeGround=loaded)
        return GroundTransferResult(p.paidWith(org.logh7.engine.strategy.CpBalance(p.pcp.toLong(),(p.mcp-COST).toLong())),stocks.copy(outfits=stocks.outfits+(o.id to changed)),
            changed=true,moved=moved)
    }
}
