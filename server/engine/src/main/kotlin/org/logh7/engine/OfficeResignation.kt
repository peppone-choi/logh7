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
        if(r.card !in setOf(2,17,39,43,44,47,50,52,54,55,56,57,134,161,175,179,180,183,186,188,190,191,192,193))
            return reject(ResignationRejection.CARD)
        val policy=BaseOfficePolicy(galaxy)
        val bases=stocks.bases.values.filter {it.power==power && policy.holders(it)[r.card]==r.characterId}
        val national=NationalOffice.of(r.card)?.takeIf {it.power==power && it.active(galaxy,stocks) &&
            stocks.nationalOffices[it]==r.characterId}
        val outfits=stocks.outfits.values.filter { outfit ->
            val kind=when(outfit.kind) {OutfitKind.FLEET->39;OutfitKind.TRANSPORT->44;OutfitKind.PATROL->47;OutfitKind.GROUND->50}
            outfit.power==power && (outfit.commanderId==r.characterId && kind+(if(power==3)136 else 0)==r.card ||
                outfit.kind==OutfitKind.FLEET && outfit.aideId==r.characterId && r.card==(if(power==2)43 else 179))
        }
        val count=bases.size+outfits.size+(if(national==null)0 else 1)
        if(count==0) return reject(ResignationRejection.AUTHORITY)
        if(count!=1) return reject(ResignationRejection.AMBIGUOUS)
        if(player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(ResignationRejection.BUSY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),80,CpPool.MCP)
        if(paid !is PaymentResult.Paid) return reject(ResignationRejection.CP)
        val replacement=when {
            national!=null -> stocks.copy(nationalOffices=stocks.nationalOffices-national)
            outfits.isNotEmpty() -> outfits.single().let {stocks.copy(outfits=stocks.outfits+(it.id to
                if(r.card in setOf(43,179))it.copy(aideId=0) else it.copy(commanderId=0)))}
            else -> bases.single().let {base ->
                val cleared=when(r.card) {
                    54,190->base.copy(governorId=0)
                    52,55,188,191->base.copy(garrisonCommanderId=0)
                    else->checkNotNull(CapitalAppointmentPolicy.rule(r.card)).assign(base,0)
                }
                stocks.copy(bases=stocks.bases+(base.baseId to cleared))
            }
        }
        return OfficeResignationResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),replacement,changed=true)
    }
}
