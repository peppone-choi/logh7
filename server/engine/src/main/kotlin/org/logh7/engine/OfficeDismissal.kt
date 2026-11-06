package org.logh7.engine

import org.logh7.engine.strategy.*

data class OfficeDismissalRequest(val account:String,val connection:String,val characterId:Int,val country:CountryId?,
    val generation:Long,val target:Int,val card:Int,val actorRank:Int,val targetCountry:CountryId?,
    val politics:Int=50,val administration:Int=50) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,target:Int,card:Int,
        actorRank:Int,targetCountry:CountryId?,politics:Int=50,administration:Int=50):
        this(account,connection,characterId,power.country,generation,target,card,actorRank,targetCountry,politics,administration)
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,target:Int,card:Int,
        actorRank:Int,targetPower:Int,politics:Int=50,administration:Int=50):
        this(account,connection,characterId,power,generation,target,card,actorRank,
            when(targetPower) {2,3->CountryId(targetPower);else->null},politics,administration)
}
enum class DismissalRejection { SESSION,IDENTITY,TARGET,CARD,AUTHORITY,AMBIGUOUS,BUSY,CP,STORAGE }
data class OfficeDismissalResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:DismissalRejection?=null,
    val changed:Boolean=false,val recovery:NavigationResult?=null,val targetPlayer:Navigator?=null)

/** Native 0708 identifies the person and card kind, without a base/outfit identity. Author: Peppone Choi. */
class OfficeDismissalPolicy(private val galaxy:Galaxy,private val countries:CountryCatalog=CountryCatalogLoader.current()) {
    data class Seat(val card:Int,val target:Int,val base:Int,val outfit:Int=0,
        val country:CountryId=CountryId(requireNotNull(OrganizationOffices.of(card)).power))
    private val offices=OrganizationalOfficePolicy(galaxy,countries)
    fun seats(stocks:PlanetStocks,card:Int,country:CountryId?=null):List<Seat> {
        val owner=country ?: OrganizationOffices.of(card)?.let {CountryId(it.power)} ?: return emptyList()
        val role=offices.role(card,owner) ?: return emptyList()
        if(role.authority==null)return emptyList()
        return offices.holders(stocks,card,owner).map {holding ->
            if(role.scope==OfficeScope.OUTFIT)Seat(card,holding.character,stocks.outfits.getValue(holding.seat).baseId,holding.seat,owner)
            else Seat(card,holding.character,holding.seat,country=owner)
        }
    }
    fun authorized(stocks:PlanetStocks,seat:Seat,actor:Int,power:Int,rank:Int):Boolean {
        val role=offices.role(seat.card,seat.country) ?: return false
        return seat.country.value==power && offices.authorized(stocks,role,
            if(role.scope==OfficeScope.OUTFIT)seat.outfit else seat.base,actor,rank)
    }
    fun available(stocks:PlanetStocks,actor:Int,power:Int,rank:Int):List<Seat> =
        (if(power>0)OrganizationOffices.forCountry(CountryId(power),countries) else emptyList())
        .filter {it.authority!=null}.flatMap {seats(stocks,it.card,it.country)}
        .groupBy {it.card to it.target}.values.filter {it.size==1}.map {it.single()}
        .filter {it.target!=actor && authorized(stocks,it,actor,power,rank)}.sortedWith(compareBy({it.target},{it.card}))
    fun clear(stocks:PlanetStocks,seat:Seat)=offices.set(stocks,OfficeHolding(seat.card,
        if(OrganizationOffices.of(seat.card)?.scope==OfficeScope.OUTFIT)seat.outfit else seat.base,seat.target,seat.country),remove=true)
}
internal object OfficeDismissal {
    fun evaluate(galaxy:Galaxy,r:OfficeDismissalRequest,session:SessionState,player:Navigator?,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):OfficeDismissalResult {
        fun reject(reason:DismissalRejection)=OfficeDismissalResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(DismissalRejection.SESSION)
        if(session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection) return reject(DismissalRejection.SESSION)
        if(player==null || r.country==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.country!=r.country || participant.country!=r.country) return reject(DismissalRejection.IDENTITY)
        val power=r.country.value
        if(r.target<=0 || r.target==r.characterId || r.targetCountry!=player.country) return reject(DismissalRejection.TARGET)
        if(OrganizationOffices.of(r.card)?.authority==null)
            return reject(DismissalRejection.CARD)
        val policy=OfficeDismissalPolicy(galaxy,countries)
        val seats=policy.seats(stocks,r.card,r.country).filter {it.target==r.target}
        if(seats.isEmpty()) return reject(DismissalRejection.AUTHORITY)
        if(seats.size!=1) return reject(DismissalRejection.AMBIGUOUS)
        val seat=seats.single()
        if(!policy.authorized(stocks,seat,r.characterId,power,r.actorRank)) return reject(DismissalRejection.AUTHORITY)
        if(player.cpPaused || player.navigationPending) return reject(DismissalRejection.BUSY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),160,CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(DismissalRejection.CP)
        return OfficeDismissalResult(player.paidWith(paid.balance),policy.clear(stocks,seat),changed=true)
    }
}
