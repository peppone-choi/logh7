package org.logh7.engine

import org.junit.jupiter.api.Test
import org.logh7.engine.strategy.CpBalance
import kotlin.test.*

/** CP 사용 누계의 결제 기록과 10 CP당 경험치 1 정산. 작성: Peppone Choi. */
class CpExperienceTest {
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private fun stocks(abilities:List<Int>?=List(8){50},experience:List<Int>?=null)=PlanetStocks(1,0,
        mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        strategic=StrategicState(people=if(abilities==null)emptyMap() else mapOf(10000 to StrategicPerson(rank=10,merit=7,abilities=abilities))),
        social=SocialState(academy=AcademyState(experience=if(experience==null)emptyMap() else mapOf(10000 to experience))))

    @Test fun paymentsRecordOnlyTheAmountActuallySpentFromEachPool() {
        val mcp=player.paidWith(CpBalance(1000,840));assertEquals(840,mcp.mcp);assertEquals(160,mcp.mcpUsed);assertEquals(0,mcp.pcpUsed)
        // A PCP substitute for an MCP command counts toward PCP.
        val substitute=mcp.paidWith(CpBalance(680,840));assertEquals(320,substitute.pcpUsed);assertEquals(160,substitute.mcpUsed)
        assertEquals(CpExperience.USED_LIMIT,player.copy(mcpUsed=CpExperience.USED_LIMIT).paidWith(CpBalance(1000,0)).mcpUsed)
    }
    @Test fun tenCpGiveOnePointToTheLowestExperienceAbilityOfThePaidPool() {
        val p=player.copy(mcpUsed=165,pcpUsed=9)
        val (players,next)=assertNotNull(CpExperience.settle(mapOf("a" to p),stocks(experience=listOf(0,0,0,0,40,0,3,3))) {null})
        // 16 points: mobility 0→... spread to the lowest each time, ties to the earlier ability.
        assertEquals(listOf(0,0,0,0,40,8,7,7),next.social.academy.experience.getValue(10000))
        assertEquals(5,players.getValue("a").mcpUsed);assertEquals(9,players.getValue("a").pcpUsed)
        assertNull(CpExperience.settle(players,next) {null})
    }
    @Test fun experienceOverflowRaisesTheAbilityAndCappedAbilitiesAreSkipped() {
        val p=player.copy(mcpUsed=20)
        val (_,next)=assertNotNull(CpExperience.settle(mapOf("a" to p),stocks(List(4){50}+listOf(100,100,100,60),List(7){0}+99)) {null})
        assertEquals(61,next.strategic.people.getValue(10000).abilities!![7]);assertEquals(1,next.social.academy.experience.getValue(10000)[7])
        val full=stocks(List(4){50}+List(4){100})
        val (spent,unchanged)=assertNotNull(CpExperience.settle(mapOf("a" to p),full) {null})
        assertEquals(0,spent.getValue("a").mcpUsed);assertEquals(List(8){0},unchanged.social.academy.experience.getValue(10000))
    }
    @Test fun aMissingStrategicRecordIsSeededFromTheJoinProfileOrWaits() {
        val p=player.copy(pcpUsed=30)
        assertNull(CpExperience.settle(mapOf("a" to p),stocks(null)) {null})
        val profile=StrategicPerson(rank=10,merit=123,money=45,abilities=List(8){70})
        val (_,next)=assertNotNull(CpExperience.settle(mapOf("a" to p),stocks(null)) {profile})
        val person=next.strategic.people.getValue(10000);assertEquals(123,person.merit);assertEquals(45,person.money)
        assertEquals(listOf(1,1,1,0,0,0,0,0),next.social.academy.experience.getValue(10000))
    }
}
