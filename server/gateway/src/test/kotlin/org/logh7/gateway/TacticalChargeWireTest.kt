package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.TacticsMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class TacticalChargeWireTest {
    @TempDir lateinit var directory:Path
    @Test fun authoritativeChargeReachesCorpsQueriesRefreshAndReinitialization() = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",3)),
            listOf(GalaxyBase(1049,2487,80,0,"Battle",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val energy=TacticalEnergy(List(11){when(it){3->130;4->50;else->0}})
        var saved=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,960,900,cpPaused=true,
            energy=energy,charge=TacticalCharge(0)))
        val store=object:NavigationStore {
            override fun load()=saved
            override fun save(players:Map<String,Navigator>){saved=players.toMap()}
        }
        fun through(client:SyntheticClient,opcode:Int):ByteArray {
            repeat(300){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
            error("Missing $opcode")
        }
        fun assertCharge(packet:ByteArray,beam:Int,gun:Int) {
            val input=ByteBuffer.wrap(packet)
            assertEquals(1,input.getShort(6).toInt());assertEquals(10000,input.getInt(8))
            assertEquals(beam,input.getShort(35).toInt());assertEquals(gun,input.getShort(37).toInt())
        }
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,navigationStore=store).use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
            client.send(SyntheticClient.opcode(0x0f02));assertCharge(through(client,0x033f),0,0);through(client,0x0f03)
            val maximum=TacticsMessages.controlReply(TacticsMessages.Control(10000,1010000,energy.values),0,0)
            client.send(maximum.copyOfRange(4,maximum.size))
            assertEquals(0,ByteBuffer.wrap(through(client,0x040c)).int)
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<120)delay(1)}
            client.send(ByteBuffer.allocate(12).putShort(0x033e).putShort(2).putInt(10000).putInt(99999).array())
            assertCharge(through(client,0x033f),65,25)
            client.send(SyntheticClient.opcode(0x0f02));assertCharge(through(client,0x033f),65,25);through(client,0x0f03)
            val switched=TacticalEnergy(List(11){if(it==4)100 else 0})
            val control=TacticsMessages.controlReply(TacticsMessages.Control(10000,1010000,switched.values),0,0)
            client.send(control.copyOfRange(4,control.size));through(client,0x040c)
            fixture.engine.submit(WorldCommand.AdvanceTo(10000))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<240)delay(1)}
            client.send(SyntheticClient.opcode(0x0300));through(client,0x0301)
            assertCharge(through(client,0x033f),65,75)
            assertEquals(120,saved.getValue(fixture.account(0)).charge!!.at)
        }
    }
}
