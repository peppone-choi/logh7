package org.logh7.engine

import org.logh7.engine.strategy.*

data class OfficeDismissalRequest(val account:String,val connection:String,val characterId:Int,val power:Power,
    val generation:Long,val target:Int,val card:Int,val actorRank:Int,val targetPower:Int,
    val politics:Int=50,val administration:Int=50)
enum class DismissalRejection { SESSION,IDENTITY,TARGET,CARD,AUTHORITY,AMBIGUOUS,BUSY,CP,STORAGE }
data class OfficeDismissalResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:DismissalRejection?=null,
    val changed:Boolean=false,val recovery:NavigationResult?=null,val targetPlayer:Navigator?=null)

/** Native 0708 identifies the person and card kind, without a base/outfit identity. Author: Peppone Choi. */
class OfficeDismissalPolicy(private val galaxy:Galaxy) {
    data class Seat(val card:Int,val target:Int,val base:Int,val outfit:Int=0)
    private val offices=OrganizationalOfficePolicy(galaxy)
    fun seats(stocks:PlanetStocks,card:Int):List<Seat> {
        val role=OrganizationOffices.of(card) ?: return emptyList()
        if(role.authority==null)return emptyList()
        return offices.holders(stocks,card).map {holding ->
            if(role.scope==OfficeScope.OUTFIT)Seat(card,holding.character,stocks.outfits.getValue(holding.seat).baseId,holding.seat)
            else Seat(card,holding.character,holding.seat)
        }
    }
    fun authorized(stocks:PlanetStocks,seat:Seat,actor:Int,power:Int,rank:Int):Boolean {
        val role=OrganizationOffices.of(seat.card) ?: return false
        return role.power==power && offices.authorized(stocks,role,
            if(role.scope==OfficeScope.OUTFIT)seat.outfit else seat.base,actor,rank)
    }
    fun available(stocks:PlanetStocks,actor:Int,power:Int,rank:Int):List<Seat> = OrganizationOffices.all.values
        .filter {it.power==power && it.authority!=null}.flatMap {seats(stocks,it.card)}
        .groupBy {it.card to it.target}.values.filter {it.size==1}.map {it.single()}
        .filter {it.target!=actor && authorized(stocks,it,actor,power,rank)}.sortedWith(compareBy({it.target},{it.card}))
    fun clear(stocks:PlanetStocks,seat:Seat)=offices.set(stocks,OfficeHolding(seat.card,
        if(OrganizationOffices.of(seat.card)?.scope==OfficeScope.OUTFIT)seat.outfit else seat.base,seat.target),remove=true)
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
        if(OrganizationOffices.of(r.card)?.authority==null)
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
        return OfficeDismissalResult(player.paidWith(paid.balance),policy.clear(stocks,seat),changed=true)
    }
}
