package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** F 수명주기·기존 S/C/M·원자 저장과 재시작. 작성: Peppone Choi. */
class OutfitFormationStoreTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=1000))),
        tacticalCommands=TacticalCommandState(morale=mapOf(1 to 75)),
        flagshipMarket=FlagshipMarketState(listOf(FlagshipExchangeOffer(10000,10001,0,2,1049,10494622,now+86400))),
        outfitSequence=OutfitSequence(201,301))
    @Test fun format32RetainsAllPriorSectionsAndCountersAfterAllOutfitsAreGone() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks);assertEquals(stocks,it.load())}
        val rows=Files.readAllLines(path);val header=rows.first().split('\t')
        assertEquals("LOGH7-STOCK-32",header[0]);assertEquals(16,header.size);assertEquals(listOf("1","1","1","1"),header.takeLast(4))
        for(prefix in listOf("S","C","M","F"))assertEquals(1,rows.count {it.startsWith("$prefix\t")})
        assertTrue("F\t201\t301" in rows)
        // Empty S/C/M still have explicit records in 32; no existing row grammar is changed.
        val empty=stocks.copy(strategic=StrategicState(),tacticalCommands=TacticalCommandState(),flagshipMarket=FlagshipMarketState())
        FilePlanetStockStore(path).use {it.save(empty);assertEquals(empty,it.load())}
        val lower=stocks.copy(outfitSequence=OutfitSequence())
        FilePlanetStockStore(path).use {it.save(lower);assertEquals(lower,it.load())}
        assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-31\t"))
    }
    @Test fun malformedMissingDuplicateOrNonPositiveCountersAreRejected() {
        val path=directory.resolve("invalid.tsv");FilePlanetStockStore(path).use {it.save(stocks)}
        val text=Files.readString(path)
        for(bad in listOf(text.replace("F\t201\t301","F\t0\t301"),text.replace("F\t201\t301","F\t201\t-1"),
            text.replace("F\t201\t301\n",""),text+"F\t201\t301\n",text.replace("F\t201\t301","F\t201\t301\t1"))) {
            Files.writeString(path,bad)
            FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
        }
    }
    @Test fun failedFormationCheckpointCannotPublishOnlyTheCounterOrOnlyTheCp() {
        val path=directory.resolve("world");val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,hull=FlagshipHull(0,now))
        val before=stocks.copy(outfitSequence=OutfitSequence(200,300));val after=mapOf("actor" to player.copy(mcp=680))
        FileWorldStateStore(path).use {store ->
            store.saveWorld(mapOf("actor" to player),before);store.beforeSwitch={throw IOException("injected formation pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(after,stocks)}
        }
        FileWorldStateStore(path).use {store ->
            assertEquals(before,store.loadStocks());assertEquals(mapOf("actor" to player),store.loadNavigation())
            store.saveWorld(after,stocks)
        }
        FileWorldStateStore(path).use {store ->assertEquals(stocks,store.loadStocks());assertEquals(after,store.loadNavigation())}
    }
}
