package org.logh7.engine

import java.util.Collections
import org.logh7.engine.strategy.*

/** Organization pp.57/63: deputy, chief of staff and up to six staff officers. Author: Peppone Choi. */
class FleetStaff(val deputy: Int=0,val chief: Int=0,officers: List<Int> = emptyList()) {
    val officers:List<Int> = Collections.unmodifiableList(ArrayList(officers))
    val members:List<Int> get()=listOf(deputy,chief).filter {it>0}+officers
    init {
        require(deputy>=0 && chief>=0 && officers.size<=6 && officers.all {it>0})
        require(members.distinct().size==members.size)
    }
    override fun equals(other:Any?)=other is FleetStaff && deputy==other.deputy && chief==other.chief && officers==other.officers
    override fun hashCode()=31*(31*deputy+chief)+officers.hashCode()
}

/** Card, appointing authority, lowest military rank and seat count from the manual organization table. */
enum class FleetOffice(val card:Int,val authority:Int,val lowest:Int,val seats:Int=1) {
    EMPIRE_COMMANDER(39,17,5),EMPIRE_DEPUTY(40,17,6),EMPIRE_CHIEF(41,3,6),
    EMPIRE_STAFF(42,39,11,6),EMPIRE_AIDE(43,39,12),
    ALLIANCE_COMMANDER(175,169,6),ALLIANCE_DEPUTY(176,169,7),ALLIANCE_CHIEF(177,171,7),
    ALLIANCE_STAFF(178,171,11,6),ALLIANCE_AIDE(179,175,12);
    val power get()=if(card<136)2 else 3
    val offset get()=if(power==2)card-39 else card-175
    fun holders(outfit:OutfitStock):List<Int> = when(offset) {
        0->listOf(outfit.commanderId);1->listOf(outfit.staff.deputy);2->listOf(outfit.staff.chief)
        3->outfit.staff.officers;4->listOf(outfit.aideId);else->error("Unknown fleet office")
    }.filter {it>0}
    fun assign(outfit:OutfitStock,character:Int):OutfitStock = when(offset) {
        0->outfit.copy(commanderId=character)
        1->outfit.copy(staff=FleetStaff(character,outfit.staff.chief,outfit.staff.officers))
        2->outfit.copy(staff=FleetStaff(outfit.staff.deputy,character,outfit.staff.officers))
        3->outfit.copy(staff=FleetStaff(outfit.staff.deputy,outfit.staff.chief,outfit.staff.officers+character))
        4->outfit.copy(aideId=character);else->error("Unknown fleet office")
    }
    fun remove(outfit:OutfitStock,character:Int):OutfitStock = if(offset==3)
        outfit.copy(staff=FleetStaff(outfit.staff.deputy,outfit.staff.chief,outfit.staff.officers-character))
        else assign(outfit,0)
    fun authorized(galaxy:Galaxy,stocks:PlanetStocks,outfit:OutfitStock,actor:Int,rank:Int):Boolean {
        if(outfit.kind!=OutfitKind.FLEET || outfit.power!=power) return false
        if(authority in setOf(39,175)) return outfit.commanderId==actor &&
            FleetAideAppointmentPolicy().commanderRank(power,rank)
        val office=checkNotNull(NationalOffice.of(authority))
        return stocks.nationalOffices[office]==actor && rank in office.ranks && office.active(galaxy,stocks)
    }
    fun eligible(actorRank:Int,targetRank:Int)=targetRank in 2..lowest && actorRank<targetRank && (power!=3 || targetRank!=3)
    fun friendly(stocks:PlanetStocks,outfit:OutfitStock)=stocks.bases[outfit.baseId]?.let {it.power==power && it.camp==0}==true
    fun available(galaxy:Galaxy,stocks:PlanetStocks,actor:Int,rank:Int)=stocks.outfits.values.filter {
        authorized(galaxy,stocks,it,actor,rank) && friendly(stocks,it) && holders(it).size<seats
    }.sortedBy {it.id}
    companion object { fun of(card:Int)=entries.singleOrNull {it.card==card} }
}

fun OutfitStock.members():List<Int> = listOf(commanderId,aideId).filter {it>0}+staff.members

internal object FleetOfficeAppointment {
    fun evaluate(galaxy:Galaxy,r:GarrisonAppointmentRequest,player:Navigator,stocks:PlanetStocks):GarrisonAppointmentResult {
        fun reject(reason:AppointmentRejection)=GarrisonAppointmentResult(player,stocks,reason)
        val role=FleetOffice.of(r.targetCard) ?: return reject(AppointmentRejection.TARGET)
        val power=if(r.power==Power.EMPIRE)2 else 3
        if(power!=role.power) return reject(AppointmentRejection.AUTHORITY)
        val outfit=stocks.outfits[r.outfit] ?: return reject(AppointmentRejection.TARGET)
        if(outfit.power!=power || outfit.kind!=OutfitKind.FLEET) return reject(AppointmentRejection.TARGET)
        if(!role.authorized(galaxy,stocks,outfit,r.characterId,r.actorRank)) return reject(AppointmentRejection.AUTHORITY)
        if(r.base!=0 && r.base!=outfit.baseId || !role.friendly(stocks,outfit)) return reject(AppointmentRejection.BASE)
        if(r.target<=0 || r.target==r.characterId || r.targetPower!=power) return reject(AppointmentRejection.TARGET)
        if(!role.eligible(r.actorRank,r.targetRank)) return reject(AppointmentRejection.RANK)
        if(player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(AppointmentRejection.BUSY)
        if(r.target in role.holders(outfit)) return GarrisonAppointmentResult(player,stocks)
        if(role.holders(outfit).size>=role.seats) return reject(AppointmentRejection.OCCUPIED)
        if(r.target in outfit.members()) return reject(AppointmentRejection.TARGET)
        val offices=BaseOfficePolicy(galaxy)
        val held=stocks.bases.values.sumOf {offices.holders(it).values.count {id->id==r.target}}+
            stocks.outfits.values.sumOf {it.members().count {id->id==r.target}}+
            stocks.nationalOffices.values.count {it==r.target}+(if(r.target in stocks.logisticsOfficers)1 else 0)
        if(held>=14) return reject(AppointmentRejection.CAPACITY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),GarrisonAppointment.COST.toLong(),CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),
            stocks.copy(outfits=stocks.outfits+(outfit.id to role.assign(outfit,r.target))),changed=true)
    }
}
