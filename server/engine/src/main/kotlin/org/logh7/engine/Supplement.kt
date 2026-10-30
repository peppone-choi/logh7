package org.logh7.engine

import org.logh7.engine.strategy.*

class SupplementRequest(val account: String,val connection: String,val characterId: Int,val power: Power,
    val generation: Long,val base: Int,val outfit: Int,val kind: Int,val mode: Int,
    units: Map<Int,List<Int>>,val supplies: Int=0,val politics: Int=50,val administration: Int=50) {
    val units: Map<Int,List<Int>> = java.util.Collections.unmodifiableMap(units.mapValues {
        java.util.Collections.unmodifiableList(ArrayList(it.value)) })
    init {require(characterId>0 && generation>0 && units.size<=99 && units.all {it.value.size<=99} &&
        supplies!=Int.MIN_VALUE && politics in 0..65535 && administration in 0..65535)}
}
data class SupplementResult(val player: Navigator?,val stocks: PlanetStocks?,val rejection: LogisticsRejection?=null,
    val changed: Boolean=false,val troops: Map<UnitStack,Int> = emptyMap(),val supplies: Int=0,
    val maxCrew: Int=0,val recovery: NavigationResult?=null)

/** Manual §7.5: one exact ship kind, warehouse replacements and automatic crew.
 * Keep active unit identity/grade; unpacked boats remain in the warehouse. Partial crew and
 * signed supply return are designed policies, not a recovered original server formula. Author: Peppone Choi.
 */
internal object Supplement {
    const val COST=160
    fun evaluate(r: SupplementRequest,s: SessionState,p: Navigator?,stocks: PlanetStocks): SupplementResult {
        fun reject(reason: LogisticsRejection)=SupplementResult(p,stocks,reason)
        val member=s.participants[r.account]
        if(s.ended!=null || s.generation!=r.generation || stocks.generation!=r.generation || member==null ||
            !member.online || member.connection!=r.connection) return reject(LogisticsRejection.SESSION)
        if(p==null || p.characterId!=r.characterId || p.generation!=s.generation || p.power!=r.power || member.power!=r.power)
            return reject(LogisticsRejection.IDENTITY)
        if(r.mode !in 0..1)return reject(LogisticsRejection.MODE)
        val outfit=stocks.outfits[r.outfit] ?: return reject(LogisticsRejection.OUTFIT)
        val power=if(r.power==Power.EMPIRE)2 else 3
        val owner=stocks.bases.getValue(outfit.baseId)
        if(outfit.power!=power || owner.power!=power || owner.camp!=0 || outfit.commanderId!=r.characterId)
            return reject(LogisticsRejection.ACCESS)
        if(r.base!=outfit.baseId || (outfit.position?.baseId ?: outfit.baseId)!=outfit.baseId ||
            r.kind!=outfit.kind.wire || p.baseId!=outfit.baseId || p.cpPaused ||
            p.dockTarget!=null || p.fuelReadyAt!=null) return reject(LogisticsRejection.LOCATION)
        val active=outfit.activeShips ?: return reject(LogisticsRejection.STOCK)
        if(r.units.size>1 || r.units.values.any {it.isEmpty()} ||
            r.units.values.flatten().let {it.distinct().size!=it.size}) return reject(LogisticsRejection.STOCK)
        var warehouse=stocks.warehouses.getValue(outfit.warehouseId)
        val selected=r.units.values.flatten().toSet()
        if(selected.any {id -> active.values.none {it.id==id && it.stack.kind in r.units}})
            return reject(LogisticsRejection.STOCK)
        val restored=try {
            active.values.map {unit ->
                if(unit.id !in selected)unit else {
                    val full=UnitSpecifications.ships.single {it.kind==unit.stack.kind}.number
                    warehouse=ReplacementBoats.take(warehouse,unit.stack.kind,full-unit.boats)
                    unit.copy(boats=full)
                }
            }
        } catch(_:IllegalArgumentException){return reject(LogisticsRejection.STOCK)}
        val required=Math.toIntExact(restored.sumOf {unit ->
            val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
            // Full-unit crew count scaled to surviving boats, rounded up per unit.
            (unit.boats.toLong()*spec.number("必要乗組員ユニット数:必要乗組員ユニット数")+spec.number-1)/spec.number
        })
        val crewKinds=UnitSpecifications.crews.filter {it.power==power && it.type==3}.map {it.kind}.toSet()
        val current=outfit.units.troops.filterKeys {it.kind in crewKinds}.values.sumOf {it.toLong()}
        var needed=maxOf(0L,required-current)
        val moved=linkedMapOf<UnitStack,Int>()
        val troopStock=warehouse.units.troops.toMutableMap();val troops=outfit.units.troops.toMutableMap()
        for((stack,count) in warehouse.units.troops.entries.filter {it.key.kind in crewKinds}.sortedBy {it.key.level}) {
            val amount=minOf(needed,count.toLong()).toInt();if(amount==0)continue
            moved[stack]=amount;needed-=amount
            if(count==amount)troopStock.remove(stack) else troopStock[stack]=count-amount
            troops[stack]=Math.addExact(troops[stack] ?: 0,amount)
        }
        if(required>0 && current+moved.values.sumOf {it.toLong()}==0L) return reject(LogisticsRejection.CREW)
        val cargo=outfit.supplies.toLong()+r.supplies;val stockCargo=warehouse.supplies.toLong()-r.supplies
        if(cargo !in 0..OutfitSupplies.capacity(outfit.copy(activeShips=ActiveShipUnits(restored))).toLong() || stockCargo !in 0..Int.MAX_VALUE.toLong())
            return reject(LogisticsRejection.STOCK)
        val nextOutfit=outfit.copy(activeShips=ActiveShipUnits(restored),
            units=UnitInventory(outfit.units.ships,troops),supplies=cargo.toInt())
        warehouse=warehouse.copy(units=UnitInventory(warehouse.units.ships,troopStock),supplies=stockCargo.toInt())
        val next=stocks.copy(outfits=stocks.outfits+(outfit.id to nextOutfit),warehouses=stocks.warehouses+(warehouse.id to warehouse))
        val changed=next!=stocks
        if(r.mode==0 || !changed)return SupplementResult(p,if(r.mode==0)next else stocks,
            troops=moved,supplies=r.supplies,maxCrew=required)
        val payment=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),COST.toLong(),CpPool.MCP)
        if(payment !is PaymentResult.Paid)return reject(LogisticsRejection.CP)
        return SupplementResult(p.paidWith(payment.balance),
            next,changed=true,troops=moved,supplies=r.supplies,maxCrew=required)
    }
}
