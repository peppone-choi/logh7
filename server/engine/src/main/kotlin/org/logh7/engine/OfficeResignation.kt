package org.logh7.engine

import org.logh7.engine.strategy.*

data class OfficeResignationRequest(val account:String,val connection:String,val characterId:Int,val power:Power,
    val generation:Long,val card:Int,val politics:Int=50,val administration:Int=50)
enum class ResignationRejection { SESSION,IDENTITY,CARD,AUTHORITY,AMBIGUOUS,BUSY,CP,STORAGE }
data class OfficeResignationResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:ResignationRejection?=null,
    val changed:Boolean=false,val recovery:NavigationResult?=null)

/** A native resignation identifies the card kind, not its seat. Never guess between duplicate seats. */
internal object OfficeResignation {
    fun evaluate(galaxy:Galaxy,r:OfficeResignationRequest,session:SessionState,player:Navigator?,stocks:PlanetStocks):OfficeResignationResult {
        fun reject(reason:ResignationRejection)=OfficeResignationResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(ResignationRejection.SESSION)
        if(session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection) return reject(ResignationRejection.SESSION)
        if(player==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.power!=r.power || participant.power!=r.power) return reject(ResignationRejection.IDENTITY)
        val power=if(r.power==Power.EMPIRE)2 else 3
        val role=OrganizationOffices.of(r.card) ?: return reject(ResignationRejection.CARD)
        if(role.power!=power)return reject(ResignationRejection.AUTHORITY)
        val policy=OrganizationalOfficePolicy(galaxy)
        val holdings=policy.holders(stocks,r.card).filter {it.character==r.characterId}
        if(holdings.isEmpty())return reject(ResignationRejection.AUTHORITY)
        if(holdings.size!=1)return reject(ResignationRejection.AMBIGUOUS)
        if(player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(ResignationRejection.BUSY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),80,CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(ResignationRejection.CP)
        val replacement=policy.set(stocks,holdings.single(),remove=true)
        return OfficeResignationResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),replacement,changed=true)
    }
}
