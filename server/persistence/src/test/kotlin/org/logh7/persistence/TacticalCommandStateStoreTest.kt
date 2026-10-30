package org.logh7.persistence

import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.logh7.engine.*
import kotlin.test.*

class TacticalCommandStateStoreTest {
    @TempDir lateinit var directory:Path
    private fun state():TacticalCommandState {
        val motion=TacticalMotion(2488,100,TacticalPose(TacticalPoint(1f,2f),0f),.5f,0f,listOf(TacticalPoint(3f,4f)))
        return TacticalCommandState(listOf(TacticalQueuedCommand(10000,2488,100,105,
            TacticalCommand(0x421,listOf(1010001),kind=4,target=99,payload=listOf(1,2,3))),
            TacticalQueuedCommand(10000,2488,100,105,TacticalCommand(0x406,listOf(1010000),kind=3,targetKind=0x81,target=99))),
            morale=mapOf(3 to 17),authority=mapOf(3 to 10001),formations=mapOf(10000 to 6),
            missions=mapOf(10001 to TacticalMissionState(10001,1,0,1000,100)),
            proposals=mapOf(10000 to TacticalMissionState(10000,5,0,0,100)),
            air=mapOf(3 to TacticalAirWing(10000,2488,3,99,1,105,.5f)),
            fortresses=mapOf(1000 to TacticalFortressMotion(2488,motion)),ranks=mapOf(10000 to 5),merits=mapOf(10000 to 123L),emergencySupplies=mapOf(1000 to 2),formationLeaders=mapOf(3 to 3))
    }
    @Test fun allPendingAndExecutingStateRoundTripsInStock30AndKeepsOldTreasury() {
        val stock=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,0,0,treasury=77)),
            nationalTreasuries=mapOf(2 to 88),tacticalCommands=state())
        val path=directory.resolve("stock")
        FilePlanetStockStore(path).use {it.save(stock);assertEquals(stock,it.load())}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-30\t"))
        FilePlanetStockStore(path).use {assertEquals(stock,it.load())}
        // Relabeling the combined S+C header as strategy-only STOCK-29 must be rejected.
        Files.writeString(path,Files.readString(path).replace("LOGH7-STOCK-30","LOGH7-STOCK-29"))
        FilePlanetStockStore(path).use {assertFails {it.load()}}
    }
    @Test fun legacyVersionOneRemainsCanonicalAndVersionTwoRetainsAiAndRewardReceipts() {
        fun version(text:String)=java.nio.ByteBuffer.wrap(java.util.Base64.getUrlDecoder().decode(text)).int
        val legacy=state();val old=TacticalCommandStateCodec.encode(legacy)
        assertEquals(1,version(old));assertEquals(old,TacticalCommandStateCodec.encode(TacticalCommandStateCodec.decode(old)))
        val current=legacy.copy(aiSwitches=mapOf(10000 to 21),creditedMerits=mapOf(10000 to 120L),
            aiNextAt=mapOf(10000 to 105L),missions=mapOf(10001 to legacy.missions.getValue(10001).copy(grid=2488)))
        val encoded=TacticalCommandStateCodec.encode(current)
        assertEquals(2,version(encoded));assertEquals(current,TacticalCommandStateCodec.decode(encoded))
        val path=directory.resolve("ai-world")
        val world=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)),tacticalCommands=current,
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(merit=120))))
        FileWorldStateStore(path).use {it.saveWorld(emptyMap(),world)}
        FileWorldStateStore(path).use {store->
            val loaded=checkNotNull(store.loadStocks());assertEquals(world,loaded)
            assertFailsWith<UnsupportedOperationException>{(loaded.tacticalCommands.aiSwitches as MutableMap).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.tacticalCommands.creditedMerits as MutableMap).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.tacticalCommands.aiNextAt as MutableMap).clear()}
        }
    }
    @Test fun truncatedDuplicateAndTrailingDataAreRejected() {
        val encoded=TacticalCommandStateCodec.encode(state())
        assertEquals(state(),TacticalCommandStateCodec.decode(encoded))
        assertFails {TacticalCommandStateCodec.decode(encoded.dropLast(4))}
        assertFails {TacticalCommandStateCodec.decode(encoded+"AAAA")}
        val path=directory.resolve("stock")
        FilePlanetStockStore(path).use {it.save(PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1,0,0)),tacticalCommands=state()))}
        val lines=Files.readAllLines(path);Files.write(path,lines+lines.last {it.startsWith("C\t")})
        FilePlanetStockStore(path).use {assertFails {it.load()}}
    }
    @Test fun reverseSideSurvivesMotionCodecAndLegacyLeftEncodingStaysCanonical() {
        for(side in 0..1) {
            val motion=TacticalMotion(2488,100,TacticalPose(TacticalPoint(0f,0f),0f),0f,
                if(side==0)-Math.PI.toFloat() else Math.PI.toFloat(),listOf(TacticalPoint(0f,0f)),
                rotation=TacticalRotation(TacticalManeuver.REVERSE,3,10,listOf(3),side))
            assertEquals(motion,TacticalMotionCodec.decode(TacticalMotionCodec.encode(motion)))
        }
        for(wait in listOf(0L,120L)) {
            val motion=TacticalMotion(2488,100,TacticalPose(TacticalPoint(0f,0f),0f),.5f,0f,listOf(TacticalPoint(1f,2f)),waitSeconds=wait)
            assertEquals(motion,TacticalMotionCodec.decode(TacticalMotionCodec.encode(motion)))
            assertEquals(100+wait,motion.startsAt)
        }
    }
    @Test fun committedOrdersAndFortressPathsCannotBeMutatedThroughCallerOrLoadedSnapshot() {
        val ids=mutableListOf(3);val points=mutableListOf(TacticalPoint(3f,4f));val payload=mutableListOf(1,2,3)
        val orders=mutableListOf(TacticalQueuedCommand(10000,2488,100,105,
            TacticalCommand(0x421,ids,points=points,payload=payload)))
        val paths=mutableListOf(TacticalPoint(5f,6f));val morale=mutableMapOf(3 to 17)
        val commands=state().copy(orders=orders,morale=morale,fortresses=mapOf(1000 to TacticalFortressMotion(2488,
            TacticalMotion(2488,100,TacticalPose(TacticalPoint(1f,2f),0f),.5f,0f,paths))))
        val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)),tacticalCommands=commands)
        val expected=stocks.copy(tacticalCommands=commands.detached())
        val path=directory.resolve("isolated-world")
        FileWorldStateStore(path).use {store->
            store.saveWorld(emptyMap(),stocks)
            ids.clear();points.clear();payload.clear();paths.clear();orders.clear();morale.clear()
            val saved=checkNotNull(store.loadStocks())
            assertEquals(expected,saved)
            val loaded=saved.tacticalCommands
            assertFailsWith<UnsupportedOperationException>{(loaded.orders as MutableList).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.orders.single().command.units as MutableList).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.orders.single().command.points as MutableList).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.orders.single().command.payload as MutableList).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.fortresses.getValue(1000).motion.waypoints as MutableList).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.morale as MutableMap).clear()}
            assertEquals(expected,store.loadStocks())
        }
        FileWorldStateStore(path).use {assertEquals(expected,it.loadStocks())}
    }
}
