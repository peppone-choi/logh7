package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class PlanetTreasuryPersistenceTest {
    @TempDir lateinit var directory:Path
    @Test fun localAndNationalBalancesRoundTripAndCorruptFiscalRowsAreRejected() {
        val path=directory.resolve("stocks.tsv")
        val stocks=PlanetStocks(1,5,mapOf(1000 to PlanetStock(1000,2,0,100,100,100,treasury=Long.MAX_VALUE)),
            nationalTreasuries=mapOf(2 to 76543210000L,3 to 17))
        FilePlanetStockStore(path).use {it.save(stocks)}
        FilePlanetStockStore(path).use {assertEquals(stocks,it.load())}
        val valid=Files.readString(path)
        assertTrue(valid.startsWith("LOGH7-STOCK-28"))
        for(bad in listOf(valid.replace("T\t3\t17","T\t3\t-1"),
            valid.replace("T\t3\t17","T\t2\t17"),
            valid.replace("T\t3\t17","T\t4\t17"),
            valid.replace(Long.MAX_VALUE.toString(),"-1"),
            valid.replace("T\t3\t17\n",""),
            valid.replace("LOGH7-STOCK-28","LOGH7-STOCK-27"))) {
            Files.writeString(path,bad)
            FilePlanetStockStore(path).use {assertFails {it.load()}}
        }
    }
    @Test fun legacyBalancesRemainZeroAndResaveWithoutRetroactiveFiscalData() {
        val path=directory.resolve("legacy.tsv")
        val stock=PlanetStocks(1,12,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)))
        FilePlanetStockStore(path).use {it.save(stock)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-14"))
        FilePlanetStockStore(path).use {assertEquals(stock,it.load())}
    }
    @Test fun actorDailySettlementAndRestartPreserveActualCollectedBalances()=runBlocking {
        val path=directory.resolve("actor.tsv")
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        suspend fun inventory(engine:WorldEngine):PlanetStocks {
            val result=CompletableDeferred<PlanetStocks>()
            engine.submit(WorldCommand.Stock(1,result))
            return withTimeout(5000){result.await()}
        }
        val first=WorldEngine(this,ticking=false,galaxy=galaxy,stockStore=FilePlanetStockStore(path))
        val before=inventory(first)
        first.submit(WorldCommand.AdvanceTo(3600000))
        val collected=inventory(first)
        assertTrue(collected.nationalTreasuries.getValue(2)>0)
        assertTrue(collected.bases.getValue(1000).treasury>0)
        assertEquals(before.producedDays+1,collected.producedDays)
        first.drainAndClose()
        FilePlanetStockStore(path).use {assertEquals(collected,it.load())}
        val second=WorldEngine(this,ticking=false,galaxy=galaxy,stockStore=FilePlanetStockStore(path))
        assertEquals(collected,inventory(second))
        second.submit(WorldCommand.AdvanceTo(3600000))
        assertEquals(collected,inventory(second))
        second.drainAndClose()
    }
}
