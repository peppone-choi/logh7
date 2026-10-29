package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class NpcStrategyWireTest {
    @TempDir lateinit var directory:Path
    @Test fun adjacentNpcArrivalNotifiesBothStrategyClientsAndTheirTacticalBootstrapContainsRealUnit()=arrival(false)
    @Test fun simultaneousReinforcementsRefreshBothExistingViewersOnceWithTheFullBattlefield()=arrival(true)
    private fun arrival(alreadyTactical:Boolean)=runBlocking {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Front",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        var savedPlayers=(0..1).associate {i->"load-00$i" to Navigator(1,10000+i,Power.ALLIANCE,2488,1000,1000,1000,cpPaused=alreadyTactical,hull=FlagshipHull(89,0))}
        val stack=UnitStack(32)
        var savedStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100000,100000,100000)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack))),position=OutfitPosition(2487,1000))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
        if(alreadyTactical) {
            val existing=savedStocks.outfits.getValue(1).copy(id=2,warehouseId=11,position=OutfitPosition(2488),
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(2,stack))))
            val reinforcing=savedStocks.outfits.getValue(1).copy(id=3,warehouseId=12,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(3,stack))))
            savedStocks=savedStocks.copy(outfits=savedStocks.outfits+mapOf(2 to existing,3 to reinforcing),
                warehouses=savedStocks.warehouses+mapOf(11 to OutfitWarehouse(11,2,OutfitKind.FLEET,1000,2),
                    12 to OutfitWarehouse(12,2,OutfitKind.FLEET,1000,3)),nextShipId=4)
        }
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(players:Map<String,Navigator>){savedPlayers=players.toMap()}}
        val store=object:PlanetStockStore {override fun load()=savedStocks;override fun save(stocks:PlanetStocks){savedStocks=stocks}}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun through(client:SyntheticClient,opcode:Int,forbidTransition:Boolean=false):ByteArray {
            repeat(500){val p=client.receive();val kind=ByteBuffer.wrap(p).getShort(4).toInt() and 65535
                if(forbidTransition)assertNotEquals(0x0f1f,kind,"Duplicate transition while tactical initialization is pending")
                if(kind==opcode)return p}
            error("Missing opcode $opcode")
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=store,
            worldStateCommitter=commit,powerByIndex={3},npcStrategy=true).use {fixture->
            val clients=(0..1).map {fixture.join(fixture.account(it),true,false).client.also {c->
                c.send(SyntheticClient.opcode(0x0f00));through(c,0x0f01)
                if(alreadyTactical) {c.send(SyntheticClient.opcode(0x0f02));through(c,0x0f03)}
            }}
            fixture.engine.submit(WorldCommand.AdvanceTo(150000))
            withTimeout(5000){while(fixture.engine.snapshot().planetStocks?.outfits?.get(1)?.position?.grid!=2488)delay(1)}
            val arrivedStocks=savedStocks
            val arrivedPlayers=savedPlayers
            for(client in clients) {
                val notice=ByteBuffer.wrap(through(client,0x0f1f));assertEquals(1,notice.get(6).toInt());assertEquals(2488,notice.getInt(7))
                client.send(SyntheticClient.opcode(0x0f00));through(client,0x0f01,alreadyTactical)
                client.send(SyntheticClient.opcode(0x0f02))
                val state=ByteBuffer.wrap(through(client,0x033b,alreadyTactical));val count=state.getShort(6).toInt() and 65535
                assertEquals(if(alreadyTactical)setOf(1,2,3,1010000,1010001) else setOf(1,1010000,1010001),
                    (0 until count).map {state.getInt(8+it*47)}.toSet())
                through(client,0x0f03,alreadyTactical)
            }
            assertTrue(fixture.engine.snapshot().navigators.values.all {it.cpPaused})
            assertEquals(arrivedStocks,savedStocks,"Rendering the reinforcement cannot mutate inventory")
            // Initialization advances the paused-CP bookkeeping timestamp, but must
            // preserve CP, hull/shields, supplies and all existing tactical orders.
            assertEquals(arrivedPlayers.mapValues {(_,p)->p.copy(cpRecoveryAt=3600)},savedPlayers,
                "Rendering the reinforcement may only advance paused-CP bookkeeping")
            assertEquals(if(alreadyTactical)94000 else 97000,savedStocks.bases.getValue(1000).supplies)
            assertEquals(2700,savedStocks.outfits.getValue(1).supplies)
            assertEquals(1,savedStocks.outfits.getValue(1).activeShips!!.values.single().id)
        }
    }
}
