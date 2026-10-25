package org.logh7.engine

import org.logh7.engine.strategy.*

data class GarrisonAppointmentRequest(val account: String,val connection: String,val characterId: Int,val power: Power,
    val generation: Long,val base: Int,val target: Int,val targetCard: Int,val actorRank: Int,val targetRank: Int,
    val targetPower: Int,val politics: Int=50,val administration: Int=50)
enum class AppointmentRejection { SESSION,IDENTITY,AUTHORITY,TARGET,BASE,RANK,OCCUPIED,CAPACITY,BUSY,CP,STORAGE }
data class GarrisonAppointmentResult(val player: Navigator?,val stocks: PlanetStocks?,val rejection: AppointmentRejection?=null,
    val changed: Boolean=false,val recovery: NavigationResult?=null,val targetPlayer: Navigator?=null)

/** Empire planet governor -> planetary garrison commander. Other appointment authorities remain separate. */
internal object GarrisonAppointment {
    const val COST=160 // CD constmsg group 0 entry 72: MCP 160, immediate.
    fun evaluate(galaxy: Galaxy,r: GarrisonAppointmentRequest,session: SessionState,player: Navigator?,stocks: PlanetStocks): GarrisonAppointmentResult {
        fun reject(reason: AppointmentRejection)=GarrisonAppointmentResult(player,stocks,reason)
        val participant=session.participants[r.account] ?: return reject(AppointmentRejection.SESSION)
        if (session.ended!=null || session.generation!=r.generation || stocks.generation!=r.generation ||
            !participant.online || participant.connection!=r.connection) return reject(AppointmentRejection.SESSION)
        if (player==null || player.generation!=r.generation || player.characterId!=r.characterId ||
            player.power!=r.power || participant.power!=r.power) return reject(AppointmentRejection.IDENTITY)
        if (r.power!=Power.EMPIRE || r.targetCard!=55) return reject(AppointmentRejection.AUTHORITY)
        val base=stocks.bases[r.base] ?: return reject(AppointmentRejection.BASE)
        val seed=galaxy.bases.singleOrNull { it.id==r.base } ?: return reject(AppointmentRejection.BASE)
        if (seed.kind!=0 || base.power!=2 || base.camp!=0) return reject(AppointmentRejection.BASE)
        if (base.governorId!=r.characterId) return reject(AppointmentRejection.AUTHORITY)
        if (r.target<=0 || r.target==r.characterId || r.targetPower!=2) return reject(AppointmentRejection.TARGET)
        // Native rank indices: 1 politician; 2 marshal..10 major. Smaller military index is senior.
        if (r.actorRank !in 0..20 || r.targetRank !in 2..10 || r.actorRank>=r.targetRank) return reject(AppointmentRejection.RANK)
        if (player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(AppointmentRejection.BUSY)
        if (base.garrisonCommanderId==r.target) return GarrisonAppointmentResult(player,stocks)
        if (base.garrisonCommanderId!=0) return reject(AppointmentRejection.OCCUPIED)
        val held=stocks.bases.values.sumOf { (if (it.governorId==r.target) 1 else 0)+(if (it.garrisonCommanderId==r.target) 1 else 0) }+
            stocks.outfits.values.count { it.commanderId==r.target }
        if (held>=14) return reject(AppointmentRejection.CAPACITY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),COST.toLong(),CpPool.MCP)
        if (paid !is PaymentResult.Paid) return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),
            stocks.copy(bases=stocks.bases+(base.baseId to base.copy(garrisonCommanderId=r.target))),changed=true)
    }
}
