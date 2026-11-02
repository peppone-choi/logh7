package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 황위 계승: 지명 후계자→작위→영향력→공적 서열, 공석 즉시 즉위, 후계 지명, 계승 거부 내전. 작성: Peppone Choi. */
class SuccessionActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Haine",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val now=session.gameMillis/1000
    private fun id(id:Int,power:Int=2)=StrategicIdentity(id,power,5,0,List(8){50})
    private val ids=listOf(id(10000),id(20),id(21),id(22),id(30,3))
    private val people=mapOf(20 to StrategicPerson(peerage=Peerage.COUNT,merit=5),21 to StrategicPerson(peerage=Peerage.DUKE,influence=10),
        22 to StrategicPerson(peerage=Peerage.DUKE,influence=40))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1177 to PlanetStock(1177,3,0,1,1,1)),
        strategic=StrategicState(people=people))
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private fun act(command:Int,card:Int,target:Int,s:PlanetStocks,who:Navigator=a)=SocialActions.evaluate(galaxy,SocialRequest(
        StrategyActionRequest("a","a",who.characterId,Power.EMPIRE,1,command,card,5,target=target,identities=ids)),session,who,s,listOf(who),mapOf("a" to who))

    @Test fun theLineRunsHeirThenPeerageInfluenceMeritAndSkipsTheDead() {
        assertEquals(listOf(22,21,20,10000),SuccessionActions.order(stocks,ids))
        val heir=stocks.copy(social=stocks.social.copy(succession=SuccessionState(heir=20)))
        assertEquals(20,SuccessionActions.order(heir,ids).first())
        val dead=stocks.copy(strategic=stocks.strategic.copy(people=people+(22 to people.getValue(22).copy(deceasedAt=5))))
        assertEquals(21,SuccessionActions.order(dead,ids).first())
    }
    @Test fun aVacatedThroneCrownsTheFirstInLineAndRecordsTheRival() {
        val reigning=policy.set(stocks,OfficeHolding(1,1049,10000))
        val vacated=StrategyActions.clearOffices(reigning,10000)
        assertTrue(vacated.social.succession.vacant);assertTrue(policy.holders(vacated,1).isEmpty())
        val crowned=SuccessionActions.accede(galaxy,vacated,ids,now)
        assertEquals(listOf(22),policy.holders(crowned,1).map {it.character})
        assertEquals(SuccessionState(0,false,22,21,maxOf(now,1L)),crowned.social.succession)
    }
    @Test fun theEmperorDesignatesAnHeir() {
        val reigning=policy.set(stocks,OfficeHolding(1,1049,10000))
        assertEquals(SocialRejection.TARGET,act(SuccessionActions.DESIGNATE,1,30,reigning).rejection,"an alliance character")
        assertEquals(SocialRejection.CARD,act(SuccessionActions.DESIGNATE,1,20,stocks).rejection,"not the emperor")
        val r=act(SuccessionActions.DESIGNATE,1,20,reigning)
        assertNull(r.rejection,"${r.rejection}");assertEquals(20,r.stocks?.social?.succession?.heir)
    }
    @Test fun theRunnerUpRefusesAndRisesInRevolt() {
        val crowned=SuccessionActions.accede(galaxy,StrategyActions.clearOffices(policy.set(stocks,OfficeHolding(1,1049,10000)),10000),ids,now)
        val rival=a.copy(characterId=21)
        val other=a.copy(characterId=20)
        assertEquals(SocialRejection.STALE,act(SuccessionActions.CONTEST,0,0,crowned,other).rejection,"only the runner-up")
        val r=act(SuccessionActions.CONTEST,0,0,crowned,rival)
        assertNull(r.rejection,"${r.rejection}")
        val s=assertNotNull(r.stocks)
        assertTrue(CoupActions.rebel(s,21));assertEquals(1,s.bases.getValue(1049).camp,"the claimant's base rises")
        assertEquals(0,s.social.succession.rival)
        assertEquals(SocialRejection.STALE,act(SuccessionActions.CONTEST,0,0,s,rival).rejection,"once")
        assertEquals(listOf(SuccessionNotice(1,21,SuccessionNotice.Kind.CONTEST,9)),SuccessionActions.notices(crowned,s,9))
    }
    @Test fun anAbdicationLeavesTheThroneToTheLine() {
        val reigning=policy.set(stocks,OfficeHolding(1,1049,10000))
        val r=OfficeResignation.evaluate(galaxy,OfficeResignationRequest("a","a",10000,Power.EMPIRE,1,1),session,a,reigning)
        val s=assertNotNull(r.stocks);assertTrue(r.changed);assertTrue(s.social.succession.vacant)
        assertEquals(listOf(22),policy.holders(SuccessionActions.accede(galaxy,s,ids,now),1).map {it.character})
        val first=policy.set(stocks.copy(strategic=stocks.strategic.copy(people=people+(10000 to StrategicPerson(peerage=Peerage.DUKE,influence=99)))),
            OfficeHolding(1,1049,10000))
        val abdicated=assertNotNull(OfficeResignation.evaluate(galaxy,OfficeResignationRequest("a","a",10000,Power.EMPIRE,1,1),session,a,first).stocks)
        assertEquals(listOf(22),policy.holders(SuccessionActions.accede(galaxy,abdicated,ids,now),1).map {it.character},"not straight back")
    }
    @Test fun anAccessionIsAnnouncedOnce() {
        val crowned=SuccessionActions.accede(galaxy,StrategyActions.clearOffices(policy.set(stocks,OfficeHolding(1,1049,10000)),10000),ids,now)
        assertEquals(listOf(SuccessionNotice(1,22,SuccessionNotice.Kind.ACCESSION,5)),SuccessionActions.notices(stocks,crowned,5))
        assertEquals(emptyList(),SuccessionActions.notices(crowned,crowned,5))
        assertEquals(emptyList(),SuccessionActions.notices(crowned,crowned.copy(social=crowned.social.copy(succession=crowned.social.succession.copy(heir=20))),5))
    }
}
