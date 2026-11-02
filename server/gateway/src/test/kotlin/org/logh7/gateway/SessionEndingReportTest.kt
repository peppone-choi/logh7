package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class SessionEndingReportTest {
    private val actor=CharacterMessages.Generate(4,10000,2,0,0,"Alpha","Pilot",18,1,1,1000001,
        listOf(60)+List(7){50},0,0,0,20,3,0,"AlphaShip",1)
    private val player=Navigator(1,10000,Power.EMPIRE,2487,840,920,1000)
    private fun report(reason:EndReason=EndReason.CAPITAL_CAPTURED,conditions:VictoryConditions=VictoryConditions(true,true)):
        Pair<WorldSnapshot,SessionResult> {
        val end=SessionEnd(reason,setOf(Power.ALLIANCE),123)
        val value=SessionResults.assess(end,SessionStatistics(4,2,100_000_000_001,1010,66),mapOf(
            Power.EMPIRE to SessionStatistics(2,1,90_000_009_999,1000,33),
            Power.ALLIANCE to SessionStatistics(2,1,9_999_990_002,10,33)),conditions)
        val session=SessionSimulation(SessionRules.load()).snapshot().copy(ended=end)
        return WorldSnapshot(0,0,session,result=value) to value
    }
    private fun message(world:WorldSnapshot,who:CharacterMessages.Generate=actor,nav:Navigator=player)=
        SessionEndingReport.message(world,who,nav,listOf(actor,actor.copy(id=10001,power=3)),emptyList())
    private fun ByteBuffer.u8()=get().toInt() and 255
    private fun ByteBuffer.u32()=int.toLong() and 0xffffffffL
    private fun ByteBuffer.skipString(){val count=u8();position(position()+count*2)}
    private fun session(message:ByteArray):ByteBuffer {
        val input=ByteBuffer.wrap(message);assertEquals(0x035a,input.getShort(4).toInt())
        input.position(BootstrapMessages.character(actor,840,920).size)
        assertTrue(ByteArray(17){input.get()}.all {it==0.toByte()})
        assertEquals(1,input.short.toInt());return input
    }
    @Test fun localPresentationUsesExactEngineRatiosButProjectsPopulationInTenThousandsAndTransmitsEachPowerOnce() {
        val (world,result)=report();val input=session(message(world))
        assertEquals(SessionGrade.DECISIVE,result.powers.getValue(Power.EMPIRE).grade)
        assertEquals(3,input.u8());input.skipString();input.skipString();assertEquals(123L,input.u32())
        for(power in 2..3) {
            assertEquals(power,input.u8());assertEquals(0L,input.u32());assertEquals(1L,input.u32());assertEquals(3L,input.u32())
            assertEquals(1,input.u8());input.skipString();assertEquals(0,input.short.toInt())
            assertEquals(if(power==2)2 else 3,input.u8())
            assertEquals(if(power==2)1 else 0,input.u8());assertEquals(if(power==2)1 else 0,input.u8())
            assertEquals(1,input.u8());assertEquals(1,input.u8())
            assertEquals(2,input.short.toInt());assertEquals(1,input.short.toInt())
            assertEquals(result.powers.getValue(if(power==2)Power.EMPIRE else Power.ALLIANCE).statistics.population/10_000,input.u32())
            assertEquals(if(power==2)1000L else 10L,input.u32());assertEquals(33L,input.u32())
        }
        assertEquals(1,input.u8());assertEquals(4,input.short.toInt());assertEquals(2,input.short.toInt())
        assertEquals(10_000_000L,input.u32());assertEquals(1010L,input.u32());assertEquals(66L,input.u32());assertFalse(input.hasRemaining())
    }
    @Test fun missingPoliticalConditionsAreTransmittedAsPendingInsteadOfGrantingDecisiveVictory() {
        val (world,_)=report(conditions=VictoryConditions());val input=session(message(world))
        input.get();input.skipString();input.skipString();input.int
        input.position(input.position()+14);input.skipString();input.short
        assertEquals(255,input.u8());input.get();input.get();assertEquals(255,input.u8());assertEquals(255,input.u8())
        for((reason,state) in listOf(EndReason.SYSTEMS_REDUCED to 4,EndReason.TIME_LIMIT to 5))
            assertEquals(state,session(message(report(reason).first)).u8())
    }
    @Test fun resultCannotBeSentForAnotherGenerationOrIdentityOrFaction() {
        val (world,_)=report()
        assertFailsWith<IllegalArgumentException>{message(world,nav=player.copy(generation=2))}
        assertFailsWith<IllegalArgumentException>{message(world,who=actor.copy(id=10001))}
        assertFailsWith<IllegalArgumentException>{message(world,who=actor.copy(power=3))}
    }
}
