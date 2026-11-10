package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * 国家目標 15 (MCP 320) and 統治目標 21 (MCP 80), manual p.73 (user decision 2026-11-01):
 * the head of state or the prime minister names an enemy base as the national goal, and the side's occupiers of that
 * base earn [GOAL_MERIT] merit each; a planet's governor or lord sets its focus (production, approval or public order)
 * and the daily change leans that way. The card sets, the merit and the daily lean are evidence:guess. Author: Peppone Choi.
 */
data class NationalGoal(val base:Int,val setter:Int,val since:Long) {
    init {require(base in 1..65535 && setter>0 && since in 0..0xffffffffL)}
}
data class GoalState(/** Actual country ID -> goal. */ val national:Map<Int,NationalGoal> = emptyMap(),
    /** base -> focus (1 production, 2 approval, 3 public order). */
    val focus:Map<Int,Int> = emptyMap()) {
    init {require(national.size<=350 && national.keys.all {it>0} && focus.size<=350 && focus.all {(k,v)->k in 1..65535 && v in 1..3})}
    fun detached()=copy(national=java.util.Collections.unmodifiableMap(LinkedHashMap(national)),
        focus=java.util.Collections.unmodifiableMap(LinkedHashMap(focus)))
}

object GoalActions {
    const val NATIONAL=15
    const val GOVERNANCE=21
    val commands=setOf(NATIONAL,GOVERNANCE)
    /** 皇帝 1 / 帝国宰相 5, 議長 132 / 副議長 133. */
    val nationalCards=setOf(1,5,132,133)
    /** Governors (54, 190), the capital administrator (192) and a fief lord's 封土 card (66). */
    val governanceCards=setOf(54,66,190,192)
    const val PRODUCTION=1
    const val APPROVAL=2
    const val PEACE=3
    const val GOAL_MERIT=100L
    /** Production focus adds a tenth of the day's positive supply production. */
    fun productionBonus(produced:Int)=if(produced>0)produced/10 else 0

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,
        catalog:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        val i=r.identity
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        if(i.country.value !in catalog.countries)return reject(SocialRejection.CARD)
        if(i.command !in commands)return reject(SocialRejection.COMMAND)
        if(i.country in session.eliminated)return reject(SocialRejection.SESSION)
        if(p.cpPaused || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val own=i.country
        val cards=if(i.command==NATIONAL)nationalCards else governanceCards
        val offices=OrganizationalOfficePolicy(g,catalog)
        val held=cards.filter {offices.holders(s,it,own).any {holder->holder.character==i.characterId}}.toSet()
        // A fief lord's 封土 card is the fief itself, not an office seat.
        if(i.card !in cards || i.card !in held && !(i.card==66 && s.strategic.fiefs.containsValue(i.characterId)))
            return reject(SocialRejection.CARD)
        if(i.command==NATIONAL) {
            if(s.bases[i.target]?.let {it.country!=null && it.country!=own}!=true || i.value!=0)return reject(SocialRejection.TARGET)
        } else if(i.target !in FundActions.governed(g,s,i.characterId,own,catalog,card=i.card) || i.value !in PRODUCTION..PEACE)return reject(SocialRejection.TARGET)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command==NATIONAL)320L else 80L,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val goals=s.social.goals;val now=session.gameMillis/1000
        val next=if(i.command==NATIONAL)goals.copy(national=goals.national+(own.value to NationalGoal(i.target,i.characterId,now)))
            else goals.copy(focus=goals.focus+(i.target to i.value))
        return SocialResult(p.paidWith(paid.balance),s.copy(social=s.social.copy(goals=next)),changed=true,outcome=1,found=i.target)
    }

    /** One day's lean toward the planet's focus (skipped while production is stopped). */
    fun lean(day:PlanetStock,before:PlanetStock,focus:Int?,stopped:Boolean,maximumStock:Int=Int.MAX_VALUE):PlanetStock {
        if(focus==null || stopped)return day
        val e=day.economy
        return when(focus) {
            PRODUCTION -> {
                val bonus = productionBonus(day.supplies - before.supplies)
                val ceiling = maxOf(day.supplies, maximumStock).toLong()
                day.copy(supplies = minOf(ceiling, day.supplies.toLong() + bonus).toInt())
            }
            APPROVAL -> if(e==null)day else day.copy(economy=e.copy(approval=minOf(100,e.approval+1)))
            else -> if(e==null)day else day.copy(economy=e.copy(peace=minOf(100,e.peace+1)))
        }
    }
    /** The occupation of a national goal: [earners] gain merit and the goal is achieved (cleared). */
    fun achieved(original:PlanetStocks,base:Int,winner:CountryId,earners:Collection<Int>):PlanetStocks {
        // A captured planet drops the old governor's focus.
        val s=if(original.social.goals.focus.containsKey(base))original.copy(social=original.social.copy(goals=original.social.goals.copy(focus=original.social.goals.focus-base))) else original
        if(s.social.goals.national[winner.value]?.base!=base)return s
        val people=LinkedHashMap(s.strategic.people)
        for(id in earners.filter {it>0}.distinct())people[id]=(people[id] ?: StrategicPerson()).let {it.copy(merit=it.merit+GOAL_MERIT)}
        return s.copy(strategic=s.strategic.copy(people=people),
            social=s.social.copy(goals=s.social.goals.copy(national=s.social.goals.national-winner.value)))
    }
}
