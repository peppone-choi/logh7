package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** 기존 저장32 보존·예약/경비/최종 기록·실패 원자성과 분리된 스냅샷. 작성: 최병호. */
class CivicStoreTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val wait=CivicOrder(10000,52,59,2,5,1049,200,0,now,now+86400,CivicPhase.WAITING,setOf(1,2),token=37,connection="first-connection")
    private val guard=CivicOrder(10001,55,59,2,5,1049,0,10490001,now,now+86400,CivicPhase.GUARDING,token=38,connection="second")
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,200,300)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=5,evaluation=1000))),
        tacticalCommands=TacticalCommandState(morale=mapOf(1 to 75)),
        flagshipMarket=FlagshipMarketState(listOf(FlagshipExchangeOffer(10000,10001,0,2,1049,10494622,now+86400))),
        outfitSequence=OutfitSequence(201,301),civic=CivicState(mapOf(10000 to wait,10001 to guard)))
    @Test fun format33RoundTripsS_C_M_FAndBothKindsOfDutiesAndStillLoads32() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks);assertEquals(stocks,it.load())}
        val lines=Files.readAllLines(path);val header=lines.first().split('\t')
        assertEquals("LOGH7-STOCK-33",header[0]);assertEquals(17,header.size);assertEquals(List(5){"1"},header.takeLast(5))
        for(prefix in listOf("S","C","M","F","V"))assertEquals(1,lines.count {it.startsWith("$prefix\t")})
        val completed=stocks.copy(civic=CivicState(mapOf(10000 to wait.copy(phase=CivicPhase.COMPLETED,effect=5))))
        FilePlanetStockStore(path).use {it.save(completed);assertEquals(completed,it.load())}
        val lower=stocks.copy(civic=CivicState())
        FilePlanetStockStore(path).use {it.save(lower);assertEquals(lower,it.load())}
        assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-32\t"))
        val vOnly=lower.copy(strategic=StrategicState(),tacticalCommands=TacticalCommandState(),flagshipMarket=FlagshipMarketState(),
            outfitSequence=OutfitSequence(),civic=stocks.civic)
        FilePlanetStockStore(path).use {it.save(vOnly);assertEquals(vOnly,it.load())}
    }
    @Test fun malformedOrDuplicateDutyStateFailsClosedAndCanonicalIdsCannotBeHidden() {
        val encoded=CivicStateCodec.encode(stocks.civic);assertEquals(stocks.civic,CivicStateCodec.decode(encoded))
        for(text in listOf(encoded+";"+encoded,encoded.replace(":1,2:",":2,1:"),encoded.replace(":1,2:",":1,1,2:"),
            encoded.replace("10000:52:","10000:53:"),encoded+":trailing"))
            assertFailsWith<IllegalArgumentException>{CivicStateCodec.decode(text)}
        val path=directory.resolve("bad.tsv");FilePlanetStockStore(path).use {it.save(stocks)}
        val lines=Files.readAllLines(path)
        for(bad in listOf(lines.filterNot {it.startsWith("V\t")},lines+lines.single {it.startsWith("V\t")},
            lines.map {if(it.startsWith("V\t"))"V\t-\textra" else it})) {
            Files.write(path,bad);FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
        }
    }
    @Test fun checkpointFailurePreservesCpAndOrdersAndCompletionIsDurableAcrossReopen() {
        val path=directory.resolve("world");val before=stocks.copy(civic=CivicState())
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,hull=FlagshipHull(0,now))
        val after=mapOf("actor" to player.copy(mcp=840))
        FileWorldStateStore(path).use {store ->
            store.saveWorld(mapOf("actor" to player),before);store.beforeSwitch={throw IOException("injected civic pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(after,stocks)}
        }
        FileWorldStateStore(path).use {store ->
            assertEquals(before,store.loadStocks());assertEquals(mapOf("actor" to player),store.loadNavigation());store.saveWorld(after,stocks)
        }
        FileWorldStateStore(path).use {store ->
            assertEquals(stocks,store.loadStocks());assertEquals(after,store.loadNavigation())
            val loaded=assertNotNull(store.loadStocks())
            assertFailsWith<UnsupportedOperationException>{(loaded.civic.orders as MutableMap<Int,CivicOrder>).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.civic.orders.getValue(10000).units as MutableSet<Int>).clear()}
            val complete=loaded.copy(civic=CivicState(mapOf(10000 to wait.copy(phase=CivicPhase.COMPLETED,effect=5))))
            store.saveWorld(after,complete)
        }
        FileWorldStateStore(path).use {store ->assertEquals(CivicPhase.COMPLETED,store.loadStocks()?.civic?.orders?.get(10000)?.phase)}
    }
}
