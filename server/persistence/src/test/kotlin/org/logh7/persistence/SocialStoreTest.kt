package org.logh7.persistence

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

/** 저장35·34 호환과 CP/영향력/지지/재실행 제한의 원자 저장. 작성: Peppone Choi. */
class SocialStoreTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val receipt=SpeechReceipt(1,10000,2,1049,10490404,now,60,10,11,70,72,now+86400)
    private val social=SocialState(2,mapOf(10000 to receipt),mapOf(10000 to receipt.nextAt),mapOf(1049 to receipt.nextAt))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,PlanetEconomy(1000,600,72,80),treasury=200)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,influence=11,money=789))),
        budget=BudgetState(mapOf(1049 to 75)),social=social)
    @Test fun format35PreservesBudgetAndSAndExplicitSpeakerAndSquareLimitsWhileLoading34() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks);assertEquals(stocks,it.load())}
        val lines=Files.readAllLines(path);assertTrue(lines.first().startsWith("LOGH7-STOCK-35\t"))
        assertEquals(19,lines.first().split('\t').size)
        for(section in listOf("S","C","M","F","V","B","X"))assertEquals(1,lines.count {it.startsWith("$section\t")})
        val old=stocks.copy(social=SocialState());FilePlanetStockStore(path).use {it.save(old);assertEquals(old,it.load())}
        assertTrue(Files.readAllLines(path).first().startsWith("LOGH7-STOCK-34\t"))
        val only=stocks.copy(budget=BudgetState());FilePlanetStockStore(path).use {it.save(only);assertEquals(only,it.load())}
    }
    @Test fun malformedReceiptsMissingLimitsDuplicateSectionsAndReusedIdsAreRejected() {
        val text=SocialStateCodec.encode(social);assertEquals(social,SocialStateCodec.decode(text))
        for(bad in listOf(text.replaceFirst("2|","1|"),text+"|trailing",text.replace("10000:${receipt.nextAt}","10000:0"),
            text.replace("1049:${receipt.nextAt}","1049:0"),text.replace(":10:11:70:72:",":10:99:70:72:")))
            assertFailsWith<IllegalArgumentException>{SocialStateCodec.decode(bad)}
        val path=directory.resolve("bad.tsv");FilePlanetStockStore(path).use {it.save(stocks)};val lines=Files.readAllLines(path)
        for(bad in listOf(lines.filterNot {it.startsWith("X\t")},lines+lines.single {it.startsWith("X\t")})) {
            Files.write(path,bad);FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
        }
    }
    @Test fun pointerFailureRestoresAllPreviousValuesAndSuccessfulReopenKeepsReceiptAndLimitsImmutable() {
        val path=directory.resolve("world");val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val before=stocks.copy(social=SocialState(),strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,influence=10,money=789))),
            bases=stocks.bases.mapValues {(_,b)->b.copy(economy=b.economy!!.copy(approval=70))})
        FileWorldStateStore(path).use {store ->
            store.saveWorld(mapOf("actor" to player),before);store.beforeSwitch={throw IOException("injected speech pointer failure")}
            assertFailsWith<IOException>{store.saveWorld(mapOf("actor" to player.copy(mcp=680)),stocks)}
        }
        FileWorldStateStore(path).use {store ->
            assertEquals(before,store.loadStocks());assertEquals(player,store.loadNavigation()["actor"])
            store.saveWorld(mapOf("actor" to player.copy(mcp=680)),stocks)
        }
        FileWorldStateStore(path).use {store ->
            val saved=assertNotNull(store.loadStocks());assertEquals(stocks,saved);assertEquals(680,store.loadNavigation()["actor"]?.mcp)
            assertFailsWith<UnsupportedOperationException>{(saved.social.speeches as MutableMap<Int,SpeechReceipt>).clear()}
            assertFailsWith<UnsupportedOperationException>{(saved.social.speakers as MutableMap<Int,Long>).clear()}
            assertFailsWith<UnsupportedOperationException>{(saved.social.squares as MutableMap<Int,Long>).clear()}
        }
    }
}
