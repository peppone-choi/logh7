package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * 資金投入 95 (manual p.70, PCP 80): money from the private account goes to the treasury of a planet the character
 * governs (value 0), to the 信任ボックス for (target 1) or against (target 2) the current emperor/chairman (value 1), or
 * to the 支持ボックス for a same-side candidate (value 2). The boxes only keep running totals for the election rules
 * that are not restored yet. Which planets count as governed and the tally form are evidence:guess. Author: Peppone Choi.
 */
data class FundState(
    /** power -> (for, against) totals of the 信任ボックス. */
    val confidence:Map<Int,Pair<Long,Long>> = emptyMap(),
    /** candidate -> total of the 支持ボックス. */
    val support:Map<Int,Long> = emptyMap()) {
    init {require(confidence.size<=2 && confidence.all {(k,v)->k in 2..3 && v.first>=0 && v.second>=0} &&
        support.size<=4096 && support.all {(k,v)->k>0 && v>0})}
    fun detached()=copy(confidence=java.util.Collections.unmodifiableMap(LinkedHashMap(confidence)),
        support=java.util.Collections.unmodifiableMap(LinkedHashMap(support)))
}

object FundActions {
    const val FUNDS=95
    const val COST=80L
    const val LOCAL=0
    const val CONFIDENCE=1
    const val SUPPORT=2
    /** Planets whose treasury the character may aid: governed (知事/司政官) or held as a fief. */
    fun governed(s:PlanetStocks,character:Int,power:Int)=s.bases.values.filter {b->b.power==power && b.camp==0 &&
        (b.governorId==character || b.capitalAdministratorId==character || s.strategic.fiefs[b.baseId]==character)}.map {it.baseId}.sorted()

    fun evaluate(r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks):SocialResult {
        val i=r.identity
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.power.country || member.country!=i.power.country)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        if(i.card!=0)return reject(SocialRejection.CARD)
        if(i.value !in LOCAL..SUPPORT || i.amount<=0)return reject(SocialRejection.COMMAND)
        val own=if(p.power==Power.EMPIRE)2 else 3
        when(i.value) {
            LOCAL -> if(i.target !in governed(s,i.characterId,own))return reject(SocialRejection.TARGET)
            CONFIDENCE -> if(i.target !in 1..2)return reject(SocialRejection.TARGET)
            SUPPORT -> if(i.target==i.characterId || i.identities.singleOrNull {it.id==i.target}?.country!=p.country)
                return reject(SocialRejection.TARGET)
        }
        val person=s.strategic.people[i.characterId] ?: StrategicPerson()
        if(person.money<i.amount)return reject(SocialRejection.MAXIMUM)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),COST,CpPool.PCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        var next=s.copy(strategic=s.strategic.copy(people=s.strategic.people+(i.characterId to person.copy(money=person.money-i.amount))))
        val f=next.social.funds
        next=when(i.value) {
            LOCAL -> next.copy(bases=next.bases+(i.target to next.bases.getValue(i.target).let {it.copy(treasury=Math.addExact(it.treasury,i.amount))}))
            CONFIDENCE -> {
                val (yes,no)=f.confidence[own] ?: (0L to 0L)
                next.copy(social=next.social.copy(funds=f.copy(confidence=f.confidence+(own to
                    if(i.target==1)Math.addExact(yes,i.amount) to no else yes to Math.addExact(no,i.amount)))))
            }
            else -> next.copy(social=next.social.copy(funds=f.copy(support=f.support+(i.target to Math.addExact(f.support[i.target] ?: 0,i.amount)))))
        }
        return SocialResult(p.paidWith(paid.balance),next,changed=true,outcome=1,
            found=minOf(next.strategic.people.getValue(i.characterId).money,0x7fffffffL).toInt())
    }
}
