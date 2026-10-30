package org.logh7.engine

enum class GroundTransferAction { LOAD, UNLOAD }
data class GroundTransferRequest(val account:String,val connection:String,val characterId:Int,val power:Power,
    val generation:Long,val outfit:Int,val action:GroundTransferAction,val politics:Int=50,val administration:Int=50) {
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
    const val CARRIER_SLOTS=10 // Verified wire bound; original per-hull capacity is still unknown.
    fun evaluate(r:GroundTransferRequest,s:SessionState,p:Navigator?,stocks:PlanetStocks):GroundTransferResult {
        fun reject(reason:LogisticsRejection)=GroundTransferResult(p,stocks,reason)
        val member=s.participants[r.account]
        if(s.ended!=null || s.generation!=r.generation || stocks.generation!=r.generation || member==null ||
            !member.online || member.connection!=r.connection)return reject(LogisticsRejection.SESSION)
        if(p==null || p.characterId!=r.characterId || p.generation!=r.generation || p.power!=r.power || member.power!=r.power)
            return reject(LogisticsRejection.IDENTITY)
        val o=stocks.outfits[r.outfit] ?: return reject(LogisticsRejection.OUTFIT)
        val power=if(r.power==Power.EMPIRE)2 else 3
        val base=o.position?.baseId ?: o.baseId
        val owner=stocks.bases[base] ?: return reject(LogisticsRejection.LOCATION)
        if(o.commanderId!=r.characterId || o.power!=power || owner.power!=power || owner.camp!=0)
            return reject(LogisticsRejection.ACCESS)
        if(p.baseId!=base || p.cpPaused || p.dockTarget!=null || p.fuelReadyAt!=null)return reject(LogisticsRejection.LOCATION)
        val roster=o.activeGround ?: return reject(LogisticsRejection.STOCK)
        val ships=o.activeShips ?: return reject(LogisticsRejection.STOCK)
        if(roster.values.any {it.order!=null} || ships.values.any {it.support!=null || it.posture?.order!=null ||
                it.tactical?.retreat!=null || it.attack!=null || it.shot!=null})return reject(LogisticsRejection.BUSY)
        val alive=ships.values.filter {it.boats>0}.sortedBy {it.id}
        if(alive.isEmpty())return reject(LogisticsRejection.STOCK)
        val locations=roster.values.associateBy {it.id}.toMutableMap()
        val selected=roster.values.filter {if(r.action==GroundTransferAction.UNLOAD)
            it.carrier>0 || it.base==0 || it.base==base && it.origin>0
            else it.carrier==0 && (it.base==base || it.base==0)}.sortedBy {it.id}
        if(r.action==GroundTransferAction.UNLOAD) {
            for(u in selected)locations[u.id]=u.copy(carrier=0,base=base,origin=0)
        } else {
            val occupied=alive.associate {ship->ship.id to roster.values.count {it.carrier==ship.id}}.toMutableMap()
            if(occupied.values.any {it>CARRIER_SLOTS} || selected.size>alive.size*CARRIER_SLOTS-occupied.values.sum())
                return reject(LogisticsRejection.STOCK)
            for(u in selected) {
                val carrier=alive.firstOrNull {it.id==u.origin && occupied.getValue(it.id)<CARRIER_SLOTS}
                    ?: alive.firstOrNull {occupied.getValue(it.id)<CARRIER_SLOTS} ?: return reject(LogisticsRejection.STOCK)
                occupied[carrier.id]=occupied.getValue(carrier.id)+1
                locations[u.id]=u.copy(carrier=carrier.id,base=0,origin=0)
            }
        }
        val moved=roster.values.filter {it!=locations.getValue(it.id)}.map {it.id}
        if(moved.isEmpty())return GroundTransferResult(p,stocks)
        if(p.mcp<COST)return reject(LogisticsRejection.CP)
        val changed=o.copy(activeGround=ActiveGroundUnits(roster.values.map {locations.getValue(it.id)}))
        return GroundTransferResult(p.copy(mcp=p.mcp-COST),stocks.copy(outfits=stocks.outfits+(o.id to changed)),
            changed=true,moved=moved)
    }
}
