package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 실제 인사 결과와 래더/정원/직무 상실/봉토 상태를 검사한다. 작성: Peppone Choi. */
class PersonnelActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"Estate",0,30f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private val p=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val identities=listOf(StrategicIdentity(10000,2,2,0,List(8){50}),
        StrategicIdentity(10001,2,9,0,List(8){50}),StrategicIdentity(10002,2,9,0,List(8){50}))
    private fun stocks(card:Int=19):PlanetStocks {
        val initial=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1050 to PlanetStock(1050,2,0,100,200,300)),
            strategic=StrategicState(people=mapOf(10001 to StrategicPerson(merit=30),10002 to StrategicPerson(merit=10)),
                offices=listOf(OfficeHolding(4,1049,10001))))
        return policy.set(initial,OfficeHolding(card,1049,10000))
    }
    private fun request(command:Int,target:Int=10001,card:Int=19)=StrategyActionRequest("actor","c",10000,Power.EMPIRE,
        1,command,card,2,target=target,identities=identities)
    private fun act(r:StrategyActionRequest,s:PlanetStocks=stocks())=StrategyActions.evaluate(galaxy,r,session,p,s)
    @Test fun promotionRequiresTheCurrentTopAndClearsOfficesWithMeritReset() {
        val s=stocks();val promoted=act(request(0),s)
        assertNull(promoted.rejection);assertEquals(840,promoted.player?.mcp)
        val stored=assertNotNull(promoted.stocks);val person=stored.strategic.people.getValue(10001)
        assertEquals(8,person.rank);assertEquals(0L,person.merit);assertTrue(policy.held(stored,10001).isEmpty())
        assertEquals(10L,stored.strategic.people.getValue(10002).merit)
        val denied=act(request(0,10002),s)
        assertEquals(StrategyActionRejection.RANK,denied.rejection);assertEquals(s,denied.stocks);assertEquals(p,denied.player)
    }
    @Test fun specialPromotionAndDemotionExcludeTheTopAndUseTheManualCosts() {
        val s=stocks()
        assertEquals(StrategyActionRejection.RANK,act(request(1),s).rejection)
        assertEquals(StrategyActionRejection.RANK,act(request(2),s).rejection)
        val selected=act(request(1,10002),s)
        assertNull(selected.rejection);assertEquals(360,selected.player?.mcp)
        assertEquals(8,selected.stocks?.strategic?.people?.get(10002)?.rank)
        val demoted=act(request(2,10002),s)
        assertNull(demoted.rejection);assertEquals(680,demoted.player?.mcp)
        assertEquals(10,demoted.stocks?.strategic?.people?.get(10002)?.rank)
        assertEquals(100L,demoted.stocks?.strategic?.people?.get(10002)?.merit)
        assertEquals(StrategyActionRejection.AUTHORITY,act(request(0,card=17),s).rejection)
    }
    @Test fun fullQuotaCannotBeBypassed() {
        val ids=listOf(StrategicIdentity(10000,2,2,0,List(8){50}),StrategicIdentity(10001,2,8,0,List(8){50}))+
            (20000..20079).map {StrategicIdentity(it,2,7,0,List(8){50})}
        val s=stocks(17)
        val denied=act(request(0,card=17).copy(identities=ids),s)
        assertEquals(StrategyActionRejection.MAXIMUM,denied.rejection);assertEquals(p,denied.player)
    }
    @Test fun sixPeeragesAreOrderedAndBaronOrHigherReceivesAnActualEstateCardOwner() {
        assertEquals(6,Peerage.entries.size)
        val ids=identities.map {if(it.id==10001)it.copy(rank=6) else it}
        val s=stocks(1)
        val grant=act(request(3,card=1).copy(value=Peerage.BARON.ordinal,identities=ids),s)
        assertNull(grant.rejection);assertEquals(Peerage.BARON,grant.stocks?.strategic?.people?.get(10001)?.peerage)
        val stored=assertNotNull(grant.stocks)
        val fief=act(request(8,card=1).copy(base=1050,identities=ids),stored)
        assertNull(fief.rejection);assertEquals(10001,fief.stocks?.strategic?.fiefs?.get(1050));assertEquals(360,fief.player?.mcp)
        val revoke=act(request(9,card=1).copy(base=1050,identities=ids),assertNotNull(fief.stocks))
        assertNull(revoke.rejection);assertTrue(assertNotNull(revoke.stocks).strategic.fiefs.isEmpty())
        val noNoble=act(request(3,card=1).copy(value=4,identities=ids.map {if(it.id==10001)it.copy(origin=1) else it}),s)
        assertEquals(StrategyActionRejection.RANK,noNoble.rejection)
    }
    @Test fun decorationsRequireStoredAuthorityAndMeritAndCannotBeChargedTwice() {
        val s=stocks(17).let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10001 to StrategicPerson(merit=100))))}
        val q=request(4,card=17).copy(identities=identities.map {if(it.id==10001)it.copy(rank=6) else it})
        val awarded=act(q,s);assertNull(awarded.rejection)
        assertEquals(setOf(0),awarded.stocks?.strategic?.people?.get(10001)?.decorations)
        val replay=act(q,assertNotNull(awarded.stocks))
        assertEquals(StrategyActionRejection.MAXIMUM,replay.rejection);assertEquals(p,replay.player)
    }
}
