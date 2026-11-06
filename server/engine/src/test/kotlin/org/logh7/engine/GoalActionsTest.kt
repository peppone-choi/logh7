package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 国家目標·統治目標: 적 거점 목표와 점령 공적, 통치 행성 중점과 일일 변화. 작성: Peppone Choi. */
class GoalActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Far",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),GalaxyBase(1050,2490,80,0,"Far",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val stocks=policy.set(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,PlanetEconomy(1000,500,60,60)),
        1050 to PlanetStock(1050,3,0,1,1,1)),strategic=StrategicState(fiefs=mapOf(1049 to 10000))),OfficeHolding(5,1049,10000))
    private fun act(command:Int,card:Int,target:Int,value:Int=0,s:PlanetStocks=stocks)=SocialActions.evaluate(galaxy,SocialRequest(
        StrategyActionRequest("a","a",10000,Power.EMPIRE,1,command,card,5,target=target,value=value)),session,a,s,listOf(a),mapOf("a" to a))

    @Test fun aNationalGoalNamesAnEnemyBaseAndItsOccupiersEarnMerit() {
        assertEquals(SocialRejection.TARGET,act(GoalActions.NATIONAL,5,1049).rejection,"own base")
        assertEquals(SocialRejection.CARD,act(GoalActions.NATIONAL,1,1050).rejection,"not the emperor")
        val set=act(GoalActions.NATIONAL,5,1050)
        assertNull(set.rejection);assertEquals(680,set.player?.mcp)
        val s=assertNotNull(set.stocks);assertEquals(1050,s.social.goals.national.getValue(2).base)
        val won=GoalActions.achieved(s,1050,CountryId(2),listOf(10000,10000))
        assertEquals(GoalActions.GOAL_MERIT,won.strategic.people.getValue(10000).merit);assertTrue(won.social.goals.national.isEmpty())
        assertSame(s,GoalActions.achieved(s,1049,CountryId(2),listOf(10000)),"another base")
    }
    @Test fun aLordSetsTheFocusOfAGovernedPlanetAndTheDayLeansToIt() {
        assertEquals(SocialRejection.TARGET,act(GoalActions.GOVERNANCE,66,1050,2).rejection)
        val s=assertNotNull(act(GoalActions.GOVERNANCE,66,1049,GoalActions.APPROVAL).stocks)
        assertEquals(2,s.social.goals.focus[1049])
        val day=s.bases.getValue(1049)
        assertEquals(61,GoalActions.lean(day,day,GoalActions.APPROVAL,false).economy?.approval)
        assertEquals(60,GoalActions.lean(day,day,GoalActions.APPROVAL,true).economy?.approval,"stopped")
        assertEquals(210+10,GoalActions.lean(day.copy(supplies=210),day.copy(supplies=110),GoalActions.PRODUCTION,false).supplies)
    }
}
