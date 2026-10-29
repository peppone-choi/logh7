package org.logh7.engine

import org.logh7.engine.strategy.*

data class GarrisonAppointmentRequest(val account: String,val connection: String,val characterId: Int,val power: Power,
    val generation: Long,val base: Int,val target: Int,val targetCard: Int,val actorRank: Int,val targetRank: Int,
    val targetPower: Int,val politics: Int=50,val administration: Int=50,val outfit: Int=0)
enum class AppointmentRejection { SESSION,IDENTITY,AUTHORITY,TARGET,BASE,RANK,OCCUPIED,CAPACITY,BUSY,CP,STORAGE }
data class GarrisonAppointmentResult(val player: Navigator?,val stocks: PlanetStocks?,val rejection: AppointmentRejection?=null,
    val changed: Boolean=false,val recovery: NavigationResult?=null,val targetPlayer: Navigator?=null)

/** Stored planetary or national authority, charged with the office change in one world commit. */
internal object GarrisonAppointment {
    const val COST=160 // CD constmsg group 0 entry 72: MCP 160, immediate.
    fun evaluate(galaxy: Galaxy,r: GarrisonAppointmentRequest,session: SessionState,player: Navigator?,stocks: PlanetStocks): GarrisonAppointmentResult {
        fun reject(reason: AppointmentRejection)=GarrisonAppointmentResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(AppointmentRejection.SESSION)
        if (session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection) return reject(AppointmentRejection.SESSION)
        if (player==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.power!=r.power || participant.power!=r.power) return reject(AppointmentRejection.IDENTITY)
        val role=OrganizationOffices.of(r.targetCard) ?: return reject(AppointmentRejection.AUTHORITY)
        val power=if(r.power==Power.EMPIRE)2 else 3
        if(role.power!=power) return reject(AppointmentRejection.AUTHORITY)
        if(role.scope==OfficeScope.OUTFIT && r.outfit<=0 || role.scope!=OfficeScope.OUTFIT && r.outfit!=0)
            return reject(AppointmentRejection.TARGET)
        if(role.scope in setOf(OfficeScope.PLANET,OfficeScope.FORTRESS) && r.base<=0)
            return reject(AppointmentRejection.BASE)
        val seat=if(role.scope==OfficeScope.OUTFIT)r.outfit else r.base.takeIf {it>0} ?: galaxy.bases
            .singleOrNull {Capital.of(it)?.power==power}?.id ?: return reject(AppointmentRejection.BASE)
        if(role.scope==OfficeScope.OUTFIT && r.base!=0 && stocks.outfits[r.outfit]?.baseId!=r.base)
            return reject(AppointmentRejection.BASE)
        val policy=OrganizationalOfficePolicy(galaxy)
        if(!policy.active(stocks,role,seat))return reject(AppointmentRejection.BASE)
        if(!policy.authorized(stocks,role,seat,r.characterId,r.actorRank))return reject(AppointmentRejection.AUTHORITY)
        if(r.target<=0 || r.target==r.characterId || r.targetPower!=power)return reject(AppointmentRejection.TARGET)
        if(!role.eligible(r.actorRank,r.targetRank))return reject(AppointmentRejection.RANK)
        if(player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null)return reject(AppointmentRejection.BUSY)
        val current=policy.holders(stocks,r.targetCard).filter {it.seat==seat}
        if(current.any {it.character==r.target})return GarrisonAppointmentResult(player,stocks)
        if(current.size>=role.seats)return reject(AppointmentRejection.OCCUPIED)
        if(policy.cardCount(stocks,r.target)>=14)return reject(AppointmentRejection.CAPACITY)
        if(role.scope==OfficeScope.OUTFIT && r.target in stocks.outfits.getValue(seat).members())
            return reject(AppointmentRejection.TARGET)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),COST.toLong(),CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),
            policy.set(stocks,OfficeHolding(r.targetCard,seat,r.target)),changed=true)
    }
}
