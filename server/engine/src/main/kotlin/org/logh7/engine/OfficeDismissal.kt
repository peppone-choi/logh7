package org.logh7.engine

import org.logh7.engine.strategy.*

data class OfficeDismissalRequest(val account:String,val connection:String,val characterId:Int,val power:Power,
    val generation:Long,val target:Int,val card:Int,val actorRank:Int,val targetPower:Int,
    val politics:Int=50,val administration:Int=50)
enum class DismissalRejection { SESSION,IDENTITY,TARGET,CARD,AUTHORITY,AMBIGUOUS,BUSY,CP,STORAGE }
data class OfficeDismissalResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:DismissalRejection?=null,
    val changed:Boolean=false,val recovery:NavigationResult?=null,val targetPlayer:Navigator?=null)

/** Native 0708 identifies the person and card kind, without a base/outfit identity. Author: 최병호. */
class OfficeDismissalPolicy(private val galaxy:Galaxy) {
    data class Seat(val card:Int,val target:Int,val base:Int,val outfit:Int=0)
    fun seats(stocks:PlanetStocks,card:Int):List<Seat> {
        val national=NationalOffice.of(card)
        if(national!=null) {
            if(national.appointer==null || !national.active(galaxy,stocks)) return emptyList()
            val base=galaxy.bases.single {Capital.of(it)==national.capital}
            return stocks.nationalOffices[national]?.let {listOf(Seat(card,it,base.id))}.orEmpty()
        }
        val fleet=FleetOffice.of(card)
        if(fleet!=null) return stocks.outfits.values.filter {it.kind==OutfitKind.FLEET && it.power==fleet.power}
            .flatMap {outfit->fleet.holders(outfit).map {Seat(card,it,outfit.baseId,outfit.id)}}
        val capital=CapitalAppointmentPolicy.rule(card)
        if(capital!=null) return stocks.bases.values.filter {base->
            base.power==capital.power && base.camp==0 && galaxy.bases.any {it.id==base.baseId && Capital.of(it)==capital.capital}
        }.mapNotNull {base->capital.holder(base).takeIf {it>0}?.let {Seat(card,it,base.baseId)}}
        if(card==55) return stocks.bases.values.filter {
            it.power==2 && it.camp==0 && it.garrisonCommanderId>0 && BaseOfficePolicy(galaxy).ordinaryPlanet(it.baseId)
        }.map {Seat(card,it.garrisonCommanderId,it.baseId)}
        return emptyList()
    }
    fun authorized(stocks:PlanetStocks,seat:Seat,actor:Int,power:Int,rank:Int):Boolean {
        val national=NationalOffice.of(seat.card)
        if(national!=null) {
            val authority=national.appointer ?: return false
            return national.power==power && rank in authority.ranks &&
                stocks.nationalOffices[authority]==actor && authority.active(galaxy,stocks) &&
                national.active(galaxy,stocks) && stocks.nationalOffices[national]==seat.target &&
                galaxy.bases.any {it.id==seat.base && Capital.of(it)==national.capital} && seat.outfit==0
        }
        val fleet=FleetOffice.of(seat.card)
        if(fleet!=null) return stocks.outfits[seat.outfit]?.let {
            fleet.power==power && fleet.friendly(stocks,it) && fleet.authorized(galaxy,stocks,it,actor,rank)
        }==true
        val capital=CapitalAppointmentPolicy.rule(seat.card)
        if(capital!=null) return rank in capital.authority.ranks &&
            CapitalAppointmentPolicy(galaxy).authorized(capital,stocks,actor,power)
        return seat.card==55 && power==2 && rank in 0..20 && stocks.bases[seat.base]?.governorId==actor
    }
    fun available(stocks:PlanetStocks,actor:Int,power:Int,rank:Int):List<Seat> =
        (NationalOffice.entries.filter {it.power==power && it.appointer!=null}.map {it.card}+
            FleetOffice.entries.filter {it.power==power}.map {it.card}+CapitalAppointmentPolicy.rules.filter {it.power==power}.map {it.card}+
            if(power==2)listOf(55) else emptyList()).flatMap {seats(stocks,it)}
            .groupBy {it.card to it.target}.values.filter {it.size==1}.map {it.single()}
            .filter {it.target!=actor && authorized(stocks,it,actor,power,rank)}.sortedWith(compareBy({it.target},{it.card}))
    fun clear(stocks:PlanetStocks,seat:Seat):PlanetStocks {
        val national=NationalOffice.of(seat.card)
        if(national!=null) return stocks.copy(nationalOffices=stocks.nationalOffices-national)
        val fleet=FleetOffice.of(seat.card)
        if(fleet!=null) return stocks.copy(outfits=stocks.outfits+(seat.outfit to fleet.remove(stocks.outfits.getValue(seat.outfit),seat.target)))
        val base=stocks.bases.getValue(seat.base)
        return stocks.copy(bases=stocks.bases+(seat.base to (CapitalAppointmentPolicy.rule(seat.card)?.assign(base,0)
            ?: base.copy(garrisonCommanderId=0))))
    }
}

internal object OfficeDismissal {
    fun evaluate(galaxy:Galaxy,r:OfficeDismissalRequest,session:SessionState,player:Navigator?,stocks:PlanetStocks):OfficeDismissalResult {
        fun reject(reason:DismissalRejection)=OfficeDismissalResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(DismissalRejection.SESSION)
        if(session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection) return reject(DismissalRejection.SESSION)
        if(player==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.power!=r.power || participant.power!=r.power) return reject(DismissalRejection.IDENTITY)
        val power=if(r.power==Power.EMPIRE)2 else 3
        if(r.target<=0 || r.target==r.characterId || r.targetPower!=power) return reject(DismissalRejection.TARGET)
        if(NationalOffice.of(r.card)?.appointer==null && FleetOffice.of(r.card)==null && CapitalAppointmentPolicy.rule(r.card)==null && r.card!=55)
            return reject(DismissalRejection.CARD)
        val policy=OfficeDismissalPolicy(galaxy)
        val seats=policy.seats(stocks,r.card).filter {it.target==r.target}
        if(seats.isEmpty()) return reject(DismissalRejection.AUTHORITY)
        if(seats.size!=1) return reject(DismissalRejection.AMBIGUOUS)
        val seat=seats.single()
        if(!policy.authorized(stocks,seat,r.characterId,power,r.actorRank)) return reject(DismissalRejection.AUTHORITY)
        if(player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(DismissalRejection.BUSY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),160,CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(DismissalRejection.CP)
        return OfficeDismissalResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),policy.clear(stocks,seat),changed=true)
    }
}
