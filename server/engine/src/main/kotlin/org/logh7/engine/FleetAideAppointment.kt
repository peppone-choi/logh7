package org.logh7.engine

import org.logh7.engine.strategy.*

/** Manual organization pp.57/63: one commander aide, lieutenant..marshal, appointed by that commander. */
class FleetAideAppointmentPolicy {
    fun card(power: Int) = if (power == 2) 43 else 179
    fun commanderRank(power:Int,rank:Int) = if(power==2)rank in 2..5 else power==3 && rank in setOf(2,4,5,6)
    fun eligible(actorRank: Int,targetRank: Int,power:Int=2) = commanderRank(power,actorRank) &&
        targetRank in 2..12 && actorRank < targetRank && (power!=3 || targetRank!=3)
    fun available(stocks: PlanetStocks,character: Int,power: Int,rank: Int): List<OutfitStock> =
        if(!commanderRank(power,rank)) emptyList() else stocks.outfits.values.filter {
            it.power==power && it.kind==OutfitKind.FLEET && it.commanderId==character && it.aideId==0 &&
                stocks.bases[it.baseId]?.let { base -> base.power==power && base.camp==0 }==true
        }.sortedBy { it.id }
}

internal object FleetAideAppointment {
    fun evaluate(galaxy: Galaxy,r: GarrisonAppointmentRequest,player: Navigator,stocks: PlanetStocks): GarrisonAppointmentResult {
        fun reject(reason: AppointmentRejection)=GarrisonAppointmentResult(player,stocks,reason)
        val power=if(r.power==Power.EMPIRE)2 else 3
        val policy=FleetAideAppointmentPolicy()
        if(r.targetCard!=policy.card(power)) return reject(AppointmentRejection.AUTHORITY)
        val outfit=stocks.outfits[r.outfit] ?: return reject(AppointmentRejection.TARGET)
        if(outfit.power!=power || outfit.kind!=OutfitKind.FLEET || outfit.commanderId!=r.characterId)
            return reject(AppointmentRejection.AUTHORITY)
        if(r.base!=0 && r.base!=outfit.baseId || stocks.bases[outfit.baseId]?.let {it.power==power && it.camp==0}!=true)
            return reject(AppointmentRejection.BASE)
        if(r.target<=0 || r.target==r.characterId || r.targetPower!=power) return reject(AppointmentRejection.TARGET)
        if(!policy.eligible(r.actorRank,r.targetRank,power)) return reject(AppointmentRejection.RANK)
        if(player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(AppointmentRejection.BUSY)
        if(outfit.aideId==r.target) return GarrisonAppointmentResult(player,stocks)
        if(outfit.aideId!=0) return reject(AppointmentRejection.OCCUPIED)
        val offices=BaseOfficePolicy(galaxy)
        val held=stocks.bases.values.sumOf { offices.holders(it).values.count {id -> id==r.target} }+
            stocks.outfits.values.sumOf { (if(it.commanderId==r.target)1 else 0)+(if(it.aideId==r.target)1 else 0) }+
            stocks.nationalOffices.values.count {it==r.target}+(if(r.target in stocks.logisticsOfficers)1 else 0)
        if(held>=14) return reject(AppointmentRejection.CAPACITY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),GarrisonAppointment.COST.toLong(),CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),
            stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(aideId=r.target))),changed=true)
    }
}
