package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-10 쿠데타: 叛意 자격, 참가, 説得 충성도, 叛乱의 반란 진영·盟主 카드, 수모자 구금 시 진압. 작성: Peppone Choi. */
class CoupActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a");it.join("b",Power.EMPIRE,false,"b")}.exportState()
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val b=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val accounts=mapOf("a" to a,"b" to b)
    private fun stocks(rank:Int=5)=policy.set(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=rank),10001 to StrategicPerson(rank=8)))),
        OfficeHolding(58,1049,10000))
    private fun act(command:Int,s:PlanetStocks,account:String="a",target:Int=0):SocialResult {
        val p=accounts.getValue(account)
        return SocialActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(account,account,p.characterId,Power.EMPIRE,1,command,0,9,
            target=target)),session,p,s,accounts.values,accounts)
    }

    @Test fun aLeaderNeedsAnOfficeAndBrigadierRankAndPlotsOnlyOnce() {
        assertEquals(SocialRejection.RANK,act(CoupActions.INTENT,stocks(rank=8)).rejection)
        val started=act(CoupActions.INTENT,stocks())
        assertNull(started.rejection);assertEquals(360,started.player?.pcp)
        val s=assertNotNull(started.stocks);assertEquals(2,s.social.coup.plots.getValue(10000).power)
        assertEquals(SocialRejection.BUSY,act(CoupActions.INTENT,s).rejection)
        assertEquals(SocialRejection.STALE,act(CoupActions.REVOLT,stocks()).rejection,"no plot yet")
        val asked=act(CoupActions.CONSPIRACY,s,target=10001)
        assertNull(asked.rejection);assertEquals(10001,asked.found)
        if(asked.outcome==1)assertTrue(10001 in assertNotNull(asked.stocks).social.coup.plots.getValue(10000).consenting)
    }
    @Test fun aRevoltTurnsTheBaseRebelGivesTheLeaderCardsAndEndsWhenTheLeaderIsDetained() {
        val plotted=stocks().let {it.copy(social=it.social.copy(coup=CoupState(mapOf(10000 to Plot(10000,2,0,setOf(10001))))))}
        val joined=assertNotNull(act(CoupActions.JOIN,plotted,"b").stocks)
        assertEquals(listOf(10001),joined.social.coup.plots.getValue(10000).members)
        val revolt=act(CoupActions.REVOLT,joined)
        assertNull(revolt.rejection);assertEquals(1,revolt.outcome)
        val s=assertNotNull(revolt.stocks)
        assertEquals(1,s.bases.getValue(1049).camp)
        val held=policy.held(s,10000).map {it.card}.toSet()
        assertTrue(68 in held && 58 !in held,"盟主 replaces the regular card: $held")
        assertEquals(CoupActions.alias(58),held.single {it!=68})
        assertEquals(setOf(69),policy.held(s,10001).map {it.card}.toSet(),"副盟主")
        assertTrue(s.social.coup.revolting(2))
        val caught=s.copy(social=s.social.copy(justice=JusticeState(detentions=mapOf(10000 to Detention(10000,2,0,86400)))))
        val ended=CoupActions.settle(caught)
        assertEquals(0,ended.bases.getValue(1049).camp);assertTrue(ended.social.coup.plots.isEmpty())
        assertTrue(policy.held(ended,10000).isEmpty())
    }
}
