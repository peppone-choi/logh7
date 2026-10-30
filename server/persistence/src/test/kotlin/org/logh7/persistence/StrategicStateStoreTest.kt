package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** CP·훈련/계급·예약을 같은 월드 슬롯에 저장. 작성: Peppone Choi. */
class StrategicStateStoreTest {
    @TempDir lateinit var directory:Path
    private val navigator=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val state=StrategicState(mapOf(10000 to StrategicPerson(1,2,3_000_000,Peerage.BARON,100,25,30,40,List(8){55},setOf(0,127))),
        listOf(SuppressionOrder(10001,200,1049,2,4_000_000)),listOf(OfficeHolding(6,1049,10000),OfficeHolding(4,1049,10001)),
        mapOf(1049 to 10000),listOf(StrategicOperation(1,10001,24,2,2487,StrategicForce.FLEET,StrategicPurpose.DEFEND,1,
            3_000_000,defended=setOf(1049))))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),strategic=state)

    @Test fun checkpointHasStrictCanonicalRoundTripAndRejectsDuplicateIdentities() {
        val encoded=StrategicStateCodec.encode(state)
        assertEquals(state,StrategicStateCodec.decode(encoded))
        val person=encoded.substringBefore('|')
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode("$person;$encoded")}
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode(encoded.replace(":1:2:3000000:",":1:4:3000000:"))}
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode("0$encoded")}
        assertFailsWith<IllegalArgumentException> {StrategicStateCodec.decode("-|-|-|-|-|-")}
    }
    @Test fun airTacticsIsAnOptionalTwelfthPersonFieldThatKeepsOlderRowsCanonical() {
        val trained=state.copy(people=mapOf(10000 to state.people.getValue(10000).copy(airTactics=2)))
        val encoded=StrategicStateCodec.encode(trained);assertEquals(trained,StrategicStateCodec.decode(encoded))
        assertTrue(encoded.substringBefore('|').endsWith(":0,127:2"))
        assertFalse(StrategicStateCodec.encode(state).substringBefore('|').endsWith(":0"))
        for(bad in listOf(encoded.replace(":0,127:2",":0,127:0"),encoded.replace(":0,127:2",":0,127:4")))
            assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(bad)}
    }
    @Test fun format29ReopensAlongsideLegacyEmptyStrategicState() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val header=Files.readAllLines(path).first().split('\t')
        assertEquals("LOGH7-STOCK-29",header[0]);assertEquals(13,header.size);assertEquals("1",header[12])
        repeat(2){FilePlanetStockStore(path).use {assertEquals(stocks,it.load())}}
        FilePlanetStockStore(path).use {it.save(stocks.copy(strategic=StrategicState()))}
        FilePlanetStockStore(path).use {assertEquals(StrategicState(),it.load()?.strategic)}
    }
    @Test fun paymentAndPendingEffectAreCommittedTogetherOrBothPreservedOnFailure() {
        val root=directory.resolve("world");val empty=stocks.copy(strategic=StrategicState())
        FileWorldStateStore(root).use {store->
            store.saveWorld(mapOf("actor" to navigator),empty)
            store.beforeSwitch={throw IOException("injected")}
            assertFailsWith<IOException>{store.saveWorld(mapOf("actor" to navigator.copy(mcp=840)),stocks)}
        }
        FileWorldStateStore(root).use {store->
            assertEquals(navigator,store.loadNavigation()["actor"]);assertEquals(empty,store.loadStocks())
            store.saveWorld(mapOf("actor" to navigator.copy(mcp=840)),stocks)
        }
        FileWorldStateStore(root).use {store->
            assertEquals(840,store.loadNavigation().getValue("actor").mcp);assertEquals(stocks,store.loadStocks())
        }
    }
    @Test fun strategy29MigratesToCombined30WithoutLosingEitherPendingCommandOrTreasury() {
        val path=directory.resolve("combined.tsv")
        val original=stocks.copy(nationalTreasuries=mapOf(2 to 123L))
        FilePlanetStockStore(path).use {it.save(original)}
        assertEquals("LOGH7-STOCK-29",Files.readAllLines(path).first().substringBefore('\t'))
        val tactical=TacticalCommandState(orders=listOf(TacticalQueuedCommand(10000,2487,100,105,
            TacticalCommand(0x40d,listOf(1010000),kind=3))),morale=mapOf(1010000 to 80))
        val combined=original.copy(tacticalCommands=tactical)
        FilePlanetStockStore(path).use {assertEquals(original,it.load());it.save(combined)}
        val lines=Files.readAllLines(path);val header=lines.first().split('\t')
        assertEquals("LOGH7-STOCK-30",header[0]);assertEquals(14,header.size)
        assertEquals(listOf("1","1"),header.takeLast(2))
        assertEquals(1,lines.count {it.startsWith("S\t")});assertEquals(1,lines.count {it.startsWith("C\t")})
        repeat(2){FilePlanetStockStore(path).use {assertEquals(combined,it.load())}}
        Files.write(path,lines.filterNot {it.startsWith("S\t")})
        FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
    }
}
