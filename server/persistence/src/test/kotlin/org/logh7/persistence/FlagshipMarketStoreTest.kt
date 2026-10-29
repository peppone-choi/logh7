package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** S/C 보존·M 제안 복원·양측 기함/CP 저장 실패의 원자성. 작성: 최병호. */
class FlagshipMarketStoreTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val offer=FlagshipExchangeOffer(10000,10001,0,2,1049,10494622,now+86400)
    private val market=FlagshipMarketState(listOf(offer))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=1000))),
        tacticalCommands=TacticalCommandState(morale=mapOf(1 to 75)),flagshipMarket=market)
    private val giver=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,hull=FlagshipHull(0,now))
    private val receiver=giver.copy(characterId=10001,hull=FlagshipHull(2,now))
    @Test fun format31KeepsSAndCAndStrictMRecordsAndOlderFormatsRemainReadable() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val rows=Files.readAllLines(path);val header=rows.first().split('\t')
        assertEquals("LOGH7-STOCK-31",header[0]);assertEquals(15,header.size)
        assertEquals(listOf("1","1","1"),header.takeLast(3))
        assertEquals(1,rows.count {it.startsWith("S\t")});assertEquals(1,rows.count {it.startsWith("C\t")});assertEquals(1,rows.count {it.startsWith("M\t")})
        FilePlanetStockStore(path).use {assertEquals(stocks,it.load())}
        val lower=stocks.copy(flagshipMarket=FlagshipMarketState())
        FilePlanetStockStore(path).use {it.save(lower);assertEquals(lower,it.load())}
        assertEquals("LOGH7-STOCK-30",Files.readAllLines(path).first().substringBefore('\t'))
        val oldest=lower.copy(tacticalCommands=TacticalCommandState())
        FilePlanetStockStore(path).use {it.save(oldest);assertEquals(oldest,it.load())}
        assertEquals("LOGH7-STOCK-29",Files.readAllLines(path).first().substringBefore('\t'))
        assertEquals(market,FlagshipMarketStateCodec.decode(FlagshipMarketStateCodec.encode(market)))
        assertFailsWith<IllegalArgumentException>{FlagshipMarketStateCodec.decode("0"+FlagshipMarketStateCodec.encode(market))}
        assertFailsWith<IllegalArgumentException>{FlagshipMarketStateCodec.decode(FlagshipMarketStateCodec.encode(market)+";"+FlagshipMarketStateCodec.encode(market))}
    }
    @Test fun aFailedExchangeNeverPublishesOnlyOnePersonsHullOrConsumesTheOffer() {
        val path=directory.resolve("world");val before=mapOf("giver" to giver,"receiver" to receiver)
        val after=mapOf("giver" to giver.copy(hull=receiver.hull),"receiver" to receiver.copy(hull=giver.hull,pcp=920))
        val accepted=stocks.copy(flagshipMarket=FlagshipMarketState())
        FileWorldStateStore(path).use {store->
            store.saveWorld(before,stocks);store.beforeSwitch={throw IOException("injected exchange pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(after,accepted)}
        }
        FileWorldStateStore(path).use {store->
            assertEquals(before,store.loadNavigation());assertEquals(stocks,store.loadStocks())
            store.saveWorld(after,accepted)
        }
        FileWorldStateStore(path).use {store->
            assertEquals(after,store.loadNavigation());assertEquals(accepted,store.loadStocks())
        }
    }
}
