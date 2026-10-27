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
        if (r.targetCard==39) return FleetCommanderAppointment.evaluate(galaxy,r,player,stocks)
        if (r.targetCard in setOf(43,179)) return FleetAideAppointment.evaluate(galaxy,r,player,stocks)
        if (r.outfit!=0) return reject(AppointmentRejection.TARGET)
        val capitalRule=CapitalAppointmentPolicy.rule(r.targetCard)
        if (capitalRule==null && (r.power!=Power.EMPIRE || r.targetCard!=55)) return reject(AppointmentRejection.AUTHORITY)
        val base=stocks.bases[r.base] ?: return reject(AppointmentRejection.BASE)
        val seed=galaxy.bases.singleOrNull { it.id==r.base } ?: return reject(AppointmentRejection.BASE)
        val power=if(r.power==Power.EMPIRE)2 else 3
        if (seed.kind!=0 || base.power!=power || base.camp!=0 ||
            if(capitalRule==null) Capital.of(seed)!=null else Capital.of(seed)!=capitalRule.capital)
            return reject(AppointmentRejection.BASE)
        if (if(capitalRule==null) base.governorId!=r.characterId else
            !CapitalAppointmentPolicy(galaxy).authorized(capitalRule,stocks,r.characterId,power))
            return reject(AppointmentRejection.AUTHORITY)
        if (r.target<=0 || r.target==r.characterId || r.targetPower!=power) return reject(AppointmentRejection.TARGET)
        // Native rank indices: 1 politician; 2 marshal..10 major. Smaller military index is senior.
        if (if(capitalRule==null) r.actorRank !in 0..20 || r.targetRank !in 2..10 || r.actorRank>=r.targetRank
            else !capitalRule.eligible(r.actorRank,r.targetRank)) return reject(AppointmentRejection.RANK)
        if (player.cpPaused || player.fuelReadyAt!=null || player.dockTarget!=null) return reject(AppointmentRejection.BUSY)
        val holder=capitalRule?.holder(base) ?: base.garrisonCommanderId
        if (holder==r.target) return GarrisonAppointmentResult(player,stocks)
        if (holder!=0) return reject(AppointmentRejection.OCCUPIED)
        val offices=BaseOfficePolicy(galaxy)
        val held=stocks.bases.values.sumOf { offices.holders(it).values.count { id -> id==r.target } }+
            stocks.outfits.values.sumOf { (if(it.commanderId==r.target)1 else 0)+(if(it.aideId==r.target)1 else 0) }+
            stocks.nationalOffices.values.count { it==r.target }+(if(r.target in stocks.logisticsOfficers)1 else 0)
        if (held>=14) return reject(AppointmentRejection.CAPACITY)
        val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),COST.toLong(),CpPool.MCP)
        if (paid !is PaymentResult.Paid) return reject(AppointmentRejection.CP)
        return GarrisonAppointmentResult(player.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),
            stocks.copy(bases=stocks.bases+(base.baseId to (capitalRule?.assign(base,r.target) ?: base.copy(garrisonCommanderId=r.target)))),changed=true)
    }
}
