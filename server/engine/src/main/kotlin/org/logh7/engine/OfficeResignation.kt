package org.logh7.engine

import org.logh7.engine.strategy.*

data class OfficeResignationRequest(val account:String,val connection:String,val characterId:Int,val country:CountryId?,
    val generation:Long,val card:Int,val politics:Int=50,val administration:Int=50) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,card:Int,politics:Int=50,administration:Int=50):
        this(account,connection,characterId,power.country,generation,card,politics,administration)
}
enum class ResignationRejection { SESSION,IDENTITY,CARD,AUTHORITY,AMBIGUOUS,BUSY,CP,STORAGE }
data class OfficeResignationResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:ResignationRejection?=null,
    val changed:Boolean=false,val recovery:NavigationResult?=null)

/** A native resignation identifies the card kind, not its seat. Never guess between duplicate seats. */
internal object OfficeResignation {
    fun evaluate(galaxy:Galaxy,r:OfficeResignationRequest,session:SessionState,player:Navigator?,stocks:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):OfficeResignationResult {
        fun reject(reason:ResignationRejection)=OfficeResignationResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(ResignationRejection.SESSION)
        if(session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection) return reject(ResignationRejection.SESSION)
        if(player==null || r.country==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.country!=r.country || participant.country!=r.country) return reject(ResignationRejection.IDENTITY)
        val policy=OrganizationalOfficePolicy(galaxy,countries)
        if(OrganizationOffices.of(r.card)==null)return reject(ResignationRejection.CARD)
        val role=policy.role(r.card,r.country) ?: return reject(ResignationRejection.AUTHORITY)
        val holdings=policy.holders(stocks,role.card,r.country).filter {it.character==r.characterId}
        if(holdings.isEmpty())return reject(ResignationRejection.AUTHORITY)
        if(holdings.size!=1)return reject(ResignationRejection.AMBIGUOUS)
        if(player.cpPaused || player.navigationPending) return reject(ResignationRejection.BUSY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),80,CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(ResignationRejection.CP)
        // An abdicating emperor leaves the throne to the line of succession at the next settlement.
        val replacement=policy.set(SuccessionActions.vacated(stocks,r.characterId,r.country).takeIf {r.card==1} ?: stocks,holdings.single(),remove=true)
        return OfficeResignationResult(player.paidWith(paid.balance),replacement,changed=true)
    }
}
