package org.logh7.engine

import org.logh7.engine.strategy.*

data class GarrisonAppointmentRequest(val account: String,val connection: String,val characterId: Int,val country: CountryId?,
    val generation: Long,val base: Int,val target: Int,val targetCard: Int,val actorRank: Int,val targetRank: Int,
    val targetCountry: CountryId?,val politics: Int=50,val administration: Int=50,val outfit: Int=0) {
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,base:Int,target:Int,
        targetCard:Int,actorRank:Int,targetRank:Int,targetCountry:CountryId?,politics:Int=50,administration:Int=50,outfit:Int=0):
        this(account,connection,characterId,power.country,generation,base,target,targetCard,actorRank,targetRank,targetCountry,politics,administration,outfit)
    constructor(account:String,connection:String,characterId:Int,power:Power,generation:Long,base:Int,target:Int,
        targetCard:Int,actorRank:Int,targetRank:Int,targetPower:Int,politics:Int=50,administration:Int=50,outfit:Int=0):
        this(account,connection,characterId,power,generation,base,target,targetCard,actorRank,targetRank,
            when(targetPower) {2,3->CountryId(targetPower);else->null},politics,administration,outfit)
}
enum class AppointmentRejection { SESSION,IDENTITY,AUTHORITY,TARGET,BASE,RANK,OCCUPIED,CAPACITY,BUSY,CP,STORAGE }
data class GarrisonAppointmentResult(val player: Navigator?,val stocks: PlanetStocks?,val rejection: AppointmentRejection?=null,
    val changed: Boolean=false,val recovery: NavigationResult?=null,val targetPlayer: Navigator?=null)

/** Stored planetary or national authority, charged with the office change in one world commit. */
internal object GarrisonAppointment {
    const val COST=160 // CD constmsg group 0 entry 72: MCP 160, immediate.
    fun evaluate(galaxy: Galaxy,r: GarrisonAppointmentRequest,session: SessionState,player: Navigator?,stocks: PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()): GarrisonAppointmentResult {
        fun reject(reason: AppointmentRejection)=GarrisonAppointmentResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(AppointmentRejection.SESSION)
        if (session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection || participant.country in session.eliminated)
            return reject(AppointmentRejection.SESSION)
        if (player==null || r.country==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.country!=r.country || participant.country!=r.country) return reject(AppointmentRejection.IDENTITY)
        if(BattleDeath.deceased(stocks,r.characterId))return reject(AppointmentRejection.IDENTITY)
        val country=r.country
        val policy=OrganizationalOfficePolicy(galaxy,countries)
        val role=policy.role(r.targetCard,country) ?: return reject(AppointmentRejection.AUTHORITY)
        if(role.scope==OfficeScope.OUTFIT && r.outfit<=0 || role.scope!=OfficeScope.OUTFIT && r.outfit!=0)
            return reject(AppointmentRejection.TARGET)
        if(role.scope in setOf(OfficeScope.PLANET,OfficeScope.FORTRESS) && r.base<=0)
            return reject(AppointmentRejection.BASE)
        val seat=if(role.scope==OfficeScope.OUTFIT)r.outfit else r.base.takeIf {it>0} ?: policy.capital(stocks,country)
            ?: return reject(AppointmentRejection.BASE)
        if(role.scope==OfficeScope.OUTFIT && r.base!=0 && stocks.outfits[r.outfit]?.baseId!=r.base)
            return reject(AppointmentRejection.BASE)
        if(!policy.active(stocks,role,seat))return reject(AppointmentRejection.BASE)
        // The country's rank system (server ladder), as the appointment directory uses; the
        // profile ranks are only the fallback when no stored rank exists.
        val actorRank=ProposalActions.rank(stocks,r.characterId,country,r.actorRank,countries)
        if(!policy.authorized(stocks,role,seat,r.characterId,actorRank))return reject(AppointmentRejection.AUTHORITY)
        if(r.target<=0 || r.target==r.characterId || r.targetCountry!=player.country ||
            BattleDeath.deceased(stocks,r.target) || stocks.social.justice.detained(r.target))
            return reject(AppointmentRejection.TARGET)
        if(!role.eligible(actorRank,ProposalActions.rank(stocks,r.target,country,r.targetRank,countries)))
            return reject(AppointmentRejection.RANK)
        if(player.cpPaused || player.navigationPending)return reject(AppointmentRejection.BUSY)
        val current=policy.holders(stocks,r.targetCard,country).filter {it.seat==seat}
        if(current.any {it.character==r.target})return GarrisonAppointmentResult(player,stocks)
        if(current.size>=role.seats)return reject(AppointmentRejection.OCCUPIED)
        if(policy.cardCount(stocks,r.target)>=14)return reject(AppointmentRejection.CAPACITY)
        if(!policy.canTakeAnySeat(stocks,role,r.target,listOf(seat)))
            return reject(AppointmentRejection.TARGET)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),COST.toLong(),CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.paidWith(paid.balance),
            policy.set(stocks,OfficeHolding(r.targetCard,seat,r.target,country)),changed=true)
    }
}
