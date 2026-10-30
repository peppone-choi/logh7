package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** 저장34·33 호환과 금고/견적/CP의 동시 저장. 작성: 최병호. */
class BudgetStoreTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val quote=BudgetQuote(7,10000,18,8,2,1049,33,50,Long.MAX_VALUE,200,
        BudgetActions.percentage(Long.MAX_VALUE,33),now,now+86400)
    private val budget=BudgetState(mapOf(1049 to 75),8,mapOf(10000 to quote))
    private val civic=CivicState(mapOf(10001 to CivicOrder(10001,55,59,2,5,1049,0,10490001,now,now+86400,CivicPhase.GUARDING)))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,treasury=200)),
        nationalTreasuries=mapOf(2 to Long.MAX_VALUE),civic=civic,budget=budget)
    @Test fun format34PreservesRatesLongTreasuriesQuoteReceiptAndTheExistingVGrammarAndStillReads33() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks);assertEquals(stocks,it.load())}
        val lines=Files.readAllLines(path);assertEquals(18,lines.first().split('\t').size)
        assertTrue(lines.first().startsWith("LOGH7-STOCK-34\t"));assertEquals(1,lines.count {it.startsWith("B\t")})
        assertEquals(1,lines.count {it.startsWith("V\t")})
        val complete=stocks.copy(budget=budget.copy(quotes=mapOf(10000 to quote.copy(phase=BudgetPhase.COMPLETED))))
        FilePlanetStockStore(path).use {it.save(complete);assertEquals(complete,it.load())}
        val old=stocks.copy(budget=BudgetState())
        FilePlanetStockStore(path).use {it.save(old);assertEquals(old,it.load())}
        assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-33\t"))
        val only=stocks.copy(civic=CivicState(),budget=BudgetState(mapOf(1049 to 0)))
        FilePlanetStockStore(path).use {it.save(only);assertEquals(only,it.load())}
    }
    @Test fun noncanonicalInvalidIdsRatesAndMissingOrDuplicateSectionsAreRejected() {
        val text=BudgetStateCodec.encode(budget);assertEquals(budget,BudgetStateCodec.decode(text))
        for(bad in listOf(text.replace("1049:75","1049:101"),text.replace("1049:75","1049:75,1049:75"),
            text.replace("8|","7|"),text+"|trailing",text.replace("7:10000:18:","7:10000:17:")))
            assertFailsWith<IllegalArgumentException>{BudgetStateCodec.decode(bad)}
        val path=directory.resolve("bad.tsv");FilePlanetStockStore(path).use {it.save(stocks)}
        val lines=Files.readAllLines(path)
        for(bad in listOf(lines.filterNot {it.startsWith("B\t")},lines+lines.single {it.startsWith("B\t")})) {
            Files.write(path,bad);FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
        }
    }
    @Test fun pointerFailurePreservesBothBalancesCpAndQuoteThenReopenRestoresACompletedReceipt() {
        val path=directory.resolve("world");val p=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val amount=quote.amount;val next=stocks.copy(nationalTreasuries=mapOf(2 to Long.MAX_VALUE-amount),
            bases=stocks.bases.mapValues {(_,b)->b.copy(treasury=b.treasury+amount)},
            budget=budget.copy(quotes=mapOf(10000 to quote.copy(phase=BudgetPhase.COMPLETED))))
        FileWorldStateStore(path).use {store ->
            store.saveWorld(mapOf("actor" to p),stocks);store.beforeSwitch={throw IOException("injected budget pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(mapOf("actor" to p.copy(mcp=680)),next)}
        }
        FileWorldStateStore(path).use {store ->
            assertEquals(stocks,store.loadStocks());assertEquals(p,store.loadNavigation()["actor"])
            store.saveWorld(mapOf("actor" to p.copy(mcp=680)),next)
        }
        FileWorldStateStore(path).use {store ->
            val loaded=assertNotNull(store.loadStocks());assertEquals(next,loaded);assertEquals(680,store.loadNavigation()["actor"]?.mcp)
            assertFailsWith<UnsupportedOperationException>{(loaded.budget.remittanceRates as MutableMap<Int,Int>).clear()}
            assertFailsWith<UnsupportedOperationException>{(loaded.budget.quotes as MutableMap<Int,BudgetQuote>).clear()}
        }
    }
}
