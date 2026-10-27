package org.logh7.engine

import org.logh7.engine.strategy.*

/** Manual organization: Empire fleet commander (lieutenant-general..marshal), appointed by military minister. */
class FleetCommanderAppointmentPolicy(private val galaxy: Galaxy) {
    fun authorized(stocks: PlanetStocks,character: Int,power: Int,rank: Int): Boolean =
        power==2 && rank in NationalOffice.MILITARY_MINISTER.ranks &&
            stocks.nationalOffices[NationalOffice.MILITARY_MINISTER]==character &&
            NationalOffice.MILITARY_MINISTER.active(galaxy,stocks)
    fun eligible(actorRank: Int,targetRank: Int)=actorRank==2 && targetRank in 3..5
    fun available(stocks: PlanetStocks,character: Int,power: Int,rank: Int): List<OutfitStock> =
        if (!authorized(stocks,character,power,rank)) emptyList() else stocks.outfits.values.filter {
            it.power==2 && it.kind==OutfitKind.FLEET && it.commanderId==0 && friendly(stocks,it)
        }.sortedBy { it.id }
    internal fun friendly(stocks: PlanetStocks,outfit: OutfitStock): Boolean =
        stocks.bases[outfit.baseId]?.let { it.power==2 && it.camp==0 }==true
}

/** Called only after the common authenticated session/actor checks. */
internal object FleetCommanderAppointment {
    fun evaluate(galaxy: Galaxy,r: GarrisonAppointmentRequest,player: Navigator,stocks: PlanetStocks): GarrisonAppointmentResult {
        fun reject(reason: AppointmentRejection)=GarrisonAppointmentResult(player,stocks,reason)
        val policy=FleetCommanderAppointmentPolicy(galaxy)
        if (r.power!=Power.EMPIRE || !policy.authorized(stocks,r.characterId,2,r.actorRank))
            return reject(AppointmentRejection.AUTHORITY)
        val outfit=stocks.outfits[r.outfit] ?: return reject(AppointmentRejection.TARGET)
        if (outfit.power!=2 || outfit.kind!=OutfitKind.FLEET) return reject(AppointmentRejection.TARGET)
        if (r.base!=0 && r.base!=outfit.baseId || !policy.friendly(stocks,outfit)) return reject(AppointmentRejection.BASE)
        if (r.target<=0 || r.target==r.characterId || r.targetPower!=2) return reject(AppointmentRejection.TARGET)
        if (!policy.eligible(r.actorRank,r.targetRank)) return reject(AppointmentRejection.RANK)
        if (player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(AppointmentRejection.BUSY)
        if (outfit.commanderId==r.target) return GarrisonAppointmentResult(player,stocks)
        if (outfit.commanderId!=0) return reject(AppointmentRejection.OCCUPIED)
        val offices=BaseOfficePolicy(galaxy)
        val held=stocks.bases.values.sumOf { offices.holders(it).values.count { id -> id==r.target } }+
            stocks.outfits.values.count { it.commanderId==r.target }+
            stocks.nationalOffices.values.count { it==r.target }+
            if (r.target in stocks.logisticsOfficers) 1 else 0
        if (held>=14) return reject(AppointmentRejection.CAPACITY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),GarrisonAppointment.COST.toLong(),CpPool.MCP)
        if (paid !is PaymentResult.Paid) return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),
            stocks.copy(outfits=stocks.outfits+(outfit.id to outfit.copy(commanderId=r.target))),changed=true)
    }
}
