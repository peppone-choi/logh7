package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalRetreatWireTest {
    @TempDir lateinit var directory:Path
    @Test fun acceptedPreparationLeavesOnClockAdvanceAndNativeTransitionRestoresStrategyInAdjacentGrid() = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",3)),
            listOf(GalaxyBase(1049,2487,80,0,"Battle",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val pose=TacticalPose(TacticalPoint(-300f,0f),1f)
        var saved=(0..1).associate { i->"load-%03d".format(i) to
            Navigator(1,10000+i,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,
                energy=TacticalEnergy(listOf(0,50)+List(9){0}),
                motion=TacticalMotion(2487,0,pose,0f,1f,listOf(pose.position))) }
        val store=object:NavigationStore {
            override fun load()=saved
            override fun save(players:Map<String,Navigator>){saved=players.toMap()}
        }
        fun through(client:SyntheticClient,op:Int):ByteArray {
            repeat(300){val p=client.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==op)return p}
            error("Missing $op")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=store).use { fixture->
            val first=fixture.join(fixture.account(0),true,false).client
            first.send(SyntheticClient.opcode(0x0f00));through(first,0x0f01)
            val second=fixture.join(fixture.account(1),true,false).client
            second.send(SyntheticClient.opcode(0x0f00));through(second,0x0f01)
            first.send(SyntheticClient.opcode(0x0f02));through(first,0x0f03)
            val packet=TacticalRetreatMessages.reply(TacticalRetreatMessages.Retreat(10000,listOf(1010000)),0,0)
            first.send(packet.copyOfRange(4,packet.size))
            val accepted=ByteBuffer.wrap(through(first,0x0404))
            assertEquals(0,accepted.int);assertEquals(120,accepted.getInt(10))
            val pending=saved.getValue(fixture.account(0)).retreat!!
            assertEquals(2486,pending.destination)
            first.send(SyntheticClient.opcode(0x0f02));through(first,0x0f03)
            through(first,0x0404) // Preparation is restored rather than charged/submitted again.
            fixture.engine.submit(WorldCommand.AdvanceTo(154999))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<3719)delay(1)}
            assertEquals(2487,saved.getValue(fixture.account(0)).grid)
            fixture.engine.submit(WorldCommand.AdvanceTo(155000))
            withTimeout(5000){while(fixture.engine.snapshot().navigators[fixture.account(0)]?.grid!=2486)delay(1)}
            // A position poll may beat the periodic world refresh at the exact completion tick.
            first.send(ByteBuffer.allocate(8).putShort(0x0348).putShort(1).putInt(1010000).array())
            val notification=ByteBuffer.wrap(through(first,0x0425))
            assertEquals(2486,notification.getInt(10));assertEquals(1010000,notification.getInt(21))
            val exit=ByteBuffer.wrap(through(first,0x0f1f))
            assertEquals(0,exit.get(6).toInt());assertEquals(2486,exit.getInt(7))
            first.send(SyntheticClient.opcode(0x0f02))
            val grid=ByteBuffer.wrap(through(first,0x0317))
            assertEquals(2486,grid.getShort(6).toInt());assertEquals(0,grid.get(8).toInt())
            through(first,0x0f03)
            first.send(ByteBuffer.allocate(8).putShort(0x0348).putShort(1).putInt(1010000).array())
            assertEquals(0,ByteBuffer.wrap(through(first,0x0349)).getShort(6).toInt())
            first.send(SyntheticClient.opcode(0x0300));through(first,0x0301)
            val exited=saved.getValue(fixture.account(0))
            assertFalse(exited.cpPaused);assertNull(exited.retreat);assertNull(exited.motion)
            assertEquals(1000,exited.mcp);assertEquals(1000,exited.cruise)
            assertEquals(2487,saved.getValue(fixture.account(1)).grid)
        }
    }
}
