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

class FlagshipCombatWireTest {
    @TempDir lateinit var directory:Path
    @Test fun enemyFlagshipDestructionNotifiesBothAndReturnsVictimInPersistedDestroyer() = duel(false)
    @Test fun automaticAttackAcknowledgesNativeModeThenNotifiesBothAndReturnsDefeatedPlayer() = duel(true)
    private fun duel(automatic:Boolean) = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        val sourcePoint=TacticalPoint(-180f,-97f);val targetPoint=TacticalPoint(-180f,-95f)
        var savedPlayers=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2488,1000,1000,900,cpPaused=true,
            energy=TacticalEnergy(List(11){if(it==3)100 else 0}),charge=TacticalCharge(now,24000),hull=FlagshipHull(0,now),
            motion=TacticalMotion(2488,now,TacticalPose(sourcePoint,0f),0f,0f,listOf(sourcePoint))),
            "load-001" to Navigator(1,10001,Power.ALLIANCE,2488,1000,1000,900,cpPaused=true,returnBase=1000,
                hull=FlagshipHull(89,now,hp=1,shields=List(6){0L}),
                motion=TacticalMotion(2488,now,TacticalPose(targetPoint,0f),0f,0f,listOf(targetPoint))))
        var inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,100,100)))
        val navStore=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(players:Map<String,Navigator>){savedPlayers=players.toMap()}
        }
        val stockStore=object:PlanetStockStore {
            override fun load()=inventory
            override fun save(stocks:PlanetStocks){inventory=stocks}
        }
        val commit=WorldStateCommitter {next,stocks->savedPlayers=next.toMap();inventory=checkNotNull(stocks)}
        fun through(client:SyntheticClient,opcode:Int):ByteArray {
            repeat(400){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
            error("Missing $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=navStore,stockStore=stockStore,
            worldStateCommitter=commit,powerByIndex={if(it==1)3 else 2}).use {fixture->
            val clients=(0..1).map {i->fixture.join(fixture.account(i),true,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)
            }}
            val command=if(automatic)TacticalAttackMessages.reply(TacticalAttackMessages.Attack(10000,listOf(1010000),2,0),0,0)
                else TacticalAttackMessages.reply(TacticalAttackMessages.Shoot(10000,listOf(1010000),0,1,1010001),0,0)
            clients[0].send(command.drop(4).toByteArray())
            val acknowledgement=ByteBuffer.wrap(through(clients[0],if(automatic)0x0405 else 0x0406))
            assertEquals(0,acknowledgement.int)
            if(automatic)assertEquals(120,acknowledgement.getInt(10))
            fixture.engine.submit(WorldCommand.AdvanceTo(5000))
            withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+120)delay(1)}
            val hit=ByteBuffer.wrap(through(clients[0],0x0426))
            assertEquals(1010001,hit.getInt(16));assertEquals(1,hit.getShort(20).toInt());assertEquals(1,hit.getShort(22).toInt())
            val exit=through(clients[1],0x0f1f)
            assertEquals(0,exit[6].toInt());assertEquals(2488,ByteBuffer.wrap(exit).getInt(7))
            assertEquals(1010001,ByteBuffer.wrap(through(clients[0],0x0425)).getInt(21))
            // No winner clock poll: the committed kill publishes damage before battle end.
            val won=through(clients[0],0x0f1f)
            assertEquals(0,won[6].toInt());assertEquals(2488,ByteBuffer.wrap(won).getInt(7))
            val winner=savedPlayers.getValue("load-000")
            assertFalse(winner.cpPaused);assertEquals(now+120,winner.cpRecoveryAt)
            assertNull(winner.attack);assertNull(winner.motion);assertNull(winner.charge)

            val returned=savedPlayers.getValue("load-001")
            assertEquals(1000,returned.baseId);assertEquals(93,returned.hull!!.kind)
            clients[1].send(SyntheticClient.opcode(0x0300))
            val unit=ByteBuffer.wrap(through(clients[1],0x0325))
            assertEquals(1010001,unit.getInt(8));assertEquals(93,unit.getShort(12).toInt());assertEquals(0,unit.getInt(38))
            assertEquals(1000,unit.getInt(28));assertEquals(4,unit.get(14).toInt())
            assertFalse(fixture.engine.snapshot().navigators.getValue("load-001").cpPaused)
        }
    }
}
