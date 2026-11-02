package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * Imperial succession and the succession war (user decision 2026-11-02: designated heir then peerage, immediate
 * accession, the runner-up may refuse and rise). The CD manual p.38 only names the politician ladder; this order, the
 * 後継指名 233 / 継承拒否 234 commands, their costs and the [CONTEST_DAYS] window are evidence:guess.
 * Order: the designated heir if alive, then peerage (公爵 first, none last), influence, merit and the lowest ID, among
 * living empire characters outside a revolt. A refusal turns the runner-up into the 盟主 of a revolt (CoupActions):
 * holding the capital for CoupActions.CAPITAL_DAYS then crowns the claimant.
 * Author: Peppone Choi.
 */
data class SuccessionState(
    /** 後継指名: the emperor's designated heir; 0 none. */
    val heir:Int=0,
    /** The throne was vacated by death or removal; the next settlement crowns the first in line. */
    val vacant:Boolean=false,
    /** The last accession: emperor, runner-up who may refuse, and the game second it happened. */
    val emperor:Int=0,val rival:Int=0,val at:Long=0) {
    init {require(heir>=0 && emperor>=0 && rival>=0 && at in 0..0xffffffffL && (rival==0 || emperor>0))}
}

/** Announcement of an accession (claimant 0) or of the runner-up's refusal (claimant set), detected between two published stocks. */
data class SuccessionNotice(val generation:Long,val emperor:Int,val claimant:Int=0,val seconds:Long=0)

object SuccessionActions {
    /** The change from [old] to [new] that the whole session hears, if any. */
    fun notice(old:PlanetStocks,new:PlanetStocks,seconds:Long=0):SuccessionNotice? {
        if(old.generation!=new.generation)return null
        val was=old.social.succession;val now=new.social.succession
        if(now.emperor>0 && now.at!=was.at)
            return SuccessionNotice(new.generation,now.emperor,seconds=seconds)
        if(was.rival>0 && now.rival==0 && CoupActions.rebel(new,was.rival) && !CoupActions.rebel(old,was.rival))
            return SuccessionNotice(new.generation,now.emperor,was.rival,seconds)
        return null
    }
    const val DESIGNATE=233
    const val CONTEST=234
    val commands=setOf(DESIGNATE,CONTEST)
    const val DESIGNATE_COST=160L
    const val CONTEST_COST=640L
    const val CONTEST_DAYS=7L
    private const val EMPEROR=1

    fun alive(s:PlanetStocks,id:Int)=(s.strategic.people[id]?.deceasedAt ?: 0)==0L
    /** Line of succession (see the class comment). */
    fun order(s:PlanetStocks,identities:List<StrategicIdentity>):List<Int> {
        val people=s.strategic.people;val heir=s.social.succession.heir
        return identities.filter {it.power==2 && alive(s,it.id) && !CoupActions.rebel(s,it.id)}
            .sortedWith(compareBy<StrategicIdentity> {if(it.id==heir)0 else 1}
                .thenBy {people[it.id]?.peerage?.ordinal ?: Peerage.entries.size}
                .thenByDescending {people[it.id]?.influence ?: 0}
                .thenByDescending {people[it.id]?.merit ?: 0L}.thenBy {it.id}).map {it.id}
    }
    private fun throne(g:Galaxy,s:PlanetStocks):Int? {
        val capital=g.bases.singleOrNull {Capital.of(it)==Capital.EMPIRE} ?: return null
        val role=OrganizationOffices.of(EMPEROR) ?: return null
        return capital.id.takeIf {OrganizationalOfficePolicy(g).active(s,role,it)}
    }
    fun throneVacant(g:Galaxy,s:PlanetStocks)=throne(g,s)!=null && OrganizationalOfficePolicy(g).holders(s,EMPEROR).isEmpty() &&
        !s.social.coup.revolting(2)
    /** Crowns the first in line on a vacant throne and records the runner-up who may refuse. */
    fun accede(g:Galaxy,s:PlanetStocks,identities:List<StrategicIdentity>,now:Long):PlanetStocks {
        val cleared=if(s.social.succession.vacant)s.copy(social=s.social.copy(succession=s.social.succession.copy(vacant=false))) else s
        if(!throneVacant(g,cleared))return if(cleared.social.succession==s.social.succession)s else cleared
        // The emperor who abdicated or was removed does not take the throne straight back.
        val previous=s.social.succession.emperor.takeIf {s.social.succession.vacant}
        val line=order(cleared,identities).filter {it!=previous}
        val emperor=line.firstOrNull() ?: return cleared
        val crowned=OrganizationalOfficePolicy(g).set(cleared,OfficeHolding(EMPEROR,checkNotNull(throne(g,cleared)),emperor))
        return crowned.copy(social=crowned.social.copy(succession=SuccessionState(heir=0,vacant=false,emperor=emperor,
            rival=line.getOrElse(1) {0},at=maxOf(now,1L))))
    }
    /** StrategyActions.clearOffices marks a vacated throne here. */
    fun vacated(s:PlanetStocks,character:Int)=if(s.strategic.offices.any {it.card==EMPEROR && it.character==character})
        s.copy(social=s.social.copy(succession=s.social.succession.copy(vacant=true,emperor=character))) else s

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(p.power!=Power.EMPIRE)return reject(SocialRejection.CARD)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val policy=OrganizationalOfficePolicy(g);val state=s.social.succession
        when(i.command) {
            DESIGNATE -> {
                if(i.card!=EMPEROR || policy.holders(s,EMPEROR).none {it.character==i.characterId})return reject(SocialRejection.CARD)
                val target=i.identities.singleOrNull {it.id==i.target}
                if(target==null || target.power!=2 || target.id==i.characterId || !alive(s,target.id) || CoupActions.rebel(s,target.id))
                    return reject(SocialRejection.TARGET)
            }
            CONTEST -> {
                if(i.card!=0)return reject(SocialRejection.CARD)
                if(state.rival!=i.characterId || now-state.at>CONTEST_DAYS*Justice.DAY ||
                    policy.holders(s,EMPEROR).none {it.character==state.emperor})return reject(SocialRejection.STALE)
                if(s.social.coup.plotOf(i.characterId)!=null || s.social.coup.revolting(2))return reject(SocialRejection.BUSY)
            }
            else -> return reject(SocialRejection.COMMAND)
        }
        val cost=if(i.command==DESIGNATE)DESIGNATE_COST else CONTEST_COST
        val pool=if(i.command==DESIGNATE)CpPool.MCP else CpPool.PCP
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,pool)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        if(i.command==DESIGNATE)
            return SocialResult(payer,s.copy(social=s.social.copy(succession=state.copy(heir=i.target))),changed=true,outcome=1,found=i.target)
        // 継承拒否: the claimant's own base and fiefs rise with it.
        val stations=(listOf(p.baseId)+s.strategic.fiefs.filterValues {it==i.characterId}.keys)
            .filter {it>0 && s.bases[it]?.let {b->b.power==2 && b.camp==0}==true}.toSet()
        val next=CoupActions.contest(g,s.copy(social=s.social.copy(succession=state.copy(rival=0))),i.characterId,stations,now)
        return SocialResult(payer,next,changed=true,outcome=1,found=stations.size)
    }
}
