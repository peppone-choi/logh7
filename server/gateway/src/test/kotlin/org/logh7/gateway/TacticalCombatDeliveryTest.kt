package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TacticalCombatDeliveryTest {
    @TempDir lateinit var directory:Path
    @Test fun blockedPeerGetsBothTargetsInOrderWithoutPollingAndObserverFailureCannotUndoCommittedFire() = runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val now=SessionRules.load().startSeconds
        // Targets 1 and 21 occupy adjacent formation columns and are both inside the forward beam arc.
        val point=TacticalPoint(-178f,-100f)
        var savedPlayers=(0..1).associate {index->"load-00$index" to Navigator(1,10000+index,Power.EMPIRE,2488,1000,960,900,
            cpPaused=true,energy=TacticalEnergy(List(11){if(it==3)100 else 0}),charge=TacticalCharge(now,24000),
            motion=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point)))}
        val stack=UnitStack(119)
        var inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,3,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 21)),
                activeShips=ActiveShipUnits((1..21).map {ActiveShipUnit(it,stack)}),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,3,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=22)
        val navStore=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(players:Map<String,Navigator>){savedPlayers=players.toMap()}
        }
        val stockStore=object:PlanetStockStore {
            override fun load()=inventory
            override fun save(stocks:PlanetStocks){inventory=stocks}
        }
        val commit=WorldStateCommitter {players,stocks->savedPlayers=players.toMap();inventory=checkNotNull(stocks)}
        val hold=AtomicBoolean()
        val entered=CompletableFuture<Unit>()
        val release=CompletableFuture<Unit>()
        val subscriptions=AtomicInteger()
        fun decorate(delegate:GameNavigation) = object:GameNavigation {
            override fun snapshot()=delegate.snapshot()
            override fun observeCombat(observer:(TacticalImpact)->Unit):AutoCloseable {
                val subscription=delegate.observeCombat(observer);subscriptions.incrementAndGet()
                return AutoCloseable {subscription.close();subscriptions.decrementAndGet()}
            }
            override fun apply(request:NavigationRequest):CompletableFuture<NavigationResult> = delegate.apply(request).thenCompose {result->
                if(request.account=="load-001" && hold.compareAndSet(true,false)) {
                    entered.complete(Unit);release.thenApply {result}
                } else CompletableFuture.completedFuture(result)
            }
        }
        fun through(client:SyntheticClient,opcode:Int):ByteArray {
            repeat(300){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
            error("Missing $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=stockStore,navigationStore=navStore,
            worldStateCommitter=commit,navigationDecorator=::decorate).use {fixture->
            val clients=(0..1).map {index->fixture.join(fixture.account(index),true,false).client.also {client->
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01)
                client.send(SyntheticClient.opcode(0x0f02));through(client,0x0f03)
            }}
            val observed=CopyOnWriteArrayList<TacticalImpact>()
            val bad=fixture.engine.observeCombat {throw IllegalStateException("unavailable subscriber")}
            val subscription=fixture.engine.observeCombat {impact->
                val boats=inventory.outfits.getValue(1).activeShips!!.values.single {it.id==impact.hit.target}.boats
                assertEquals(300-impact.hit.destroyed,boats)
                assertEquals(inventory,fixture.engine.snapshot().planetStocks)
                assertEquals(savedPlayers.getValue("load-000"),fixture.engine.snapshot().navigators.getValue("load-000"))
                observed+=impact
            }
            try {
                hold.set(true);clients[1].send(SyntheticClient.opcode(0x0300))
                entered.get(5,TimeUnit.SECONDS)
                fun shoot(target:Int) {
                    val command=TacticalAttackMessages.Shoot(10000,listOf(1010000),0,1,target)
                    clients[0].send(TacticalAttackMessages.reply(command,0,0).drop(4).toByteArray())
                    assertEquals(0,ByteBuffer.wrap(through(clients[0],0x0406)).int)
                }
                suspend fun advance(realMillis:Long) {
                    fixture.engine.submit(WorldCommand.AdvanceTo(realMillis))
                    withTimeout(5000){while(fixture.engine.snapshot().session!!.gameSeconds<now+realMillis*24/1000)delay(1)}
                }
                shoot(1);advance(5000)
                assertEquals(1,ByteBuffer.wrap(through(clients[0],0x0426)).getInt(16))
                advance(15000);shoot(21);advance(20000)
                assertEquals(21,ByteBuffer.wrap(through(clients[0],0x0426)).getInt(16))
                release.complete(Unit)
                val hits=List(2){ByteBuffer.wrap(through(clients[1],0x0426))}
                assertEquals(listOf(1,21),hits.map {it.getInt(16)})
                assertTrue(hits.all {it.getShort(22)>0})
                assertEquals(listOf(1,21),observed.map {it.hit.target})
                assertTrue(observed.all {it.recipients.keys==setOf(10000,10001)})
                clients[1].send(SyntheticClient.opcode(0x0300))
                for(index in 0 until 300) {
                    val opcode=ByteBuffer.wrap(clients[1].receive()).getShort(4).toInt()
                    assertNotEquals(0x0426,opcode,"A later refresh replayed an already delivered shot")
                    if(opcode==0x0301)break
                }
            } finally {release.complete(Unit);subscription.close();bad.close()}
        }
        assertEquals(0,subscriptions.get())
    }
}
