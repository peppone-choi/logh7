package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Country routes/tariffs keep old records readable and protect upgrades. Author: Peppone Choi. */
class CountryTradeStoreTest {
    @TempDir lateinit var directory:Path
    private val legacy=TradeState(routes=mapOf(10000 to TradeRoute(10000,1000,1001,700,100)),tariffs=mapOf(2 to 20))
    private fun stock(trade:TradeState)=PlanetStocks(7,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),social=SocialState(trade=trade))

    @Test fun oldFiveColumnRoutesKeepTheirExactGrammarUntilBound() {
        val state=SocialState(trade=legacy);val text=SocialStateCodec.encode(state)
        assertEquals("10000:1000:1001:700:100/2:20/-/-/-",text.split('|')[12])
        assertEquals(state,SocialStateCodec.decode(text))
        val file=directory.resolve("old.tsv")
        FilePlanetStockStore(file).use {it.save(stock(legacy))}
        val bytes=Files.readString(file);assertFalse(bytes.startsWith("LOGH7-STOCK-45"))
        FilePlanetStockStore(file).use {it.save(it.load()!!)}
        assertEquals(bytes,Files.readString(file))
    }

    @Test fun explicitOriginOrAddedCountryTariffChooses45AndReopensWithEitherStrategicShape() {
        val file=directory.resolve("current.tsv")
        val origins=legacy.copy(routes=legacy.routes.mapValues {(_,r)->r.copy(country=CountryId(2))})
        for(trade in listOf(origins,legacy.copy(tariffs=mapOf(42 to 30)))) {
            for(strategic in listOf(StrategicState(),StrategicState(offices=listOf(OfficeHolding(8,1000,10000)),
                capitals=mapOf(CountryId(2) to 1000)))) {
                val s=stock(trade).copy(strategic=strategic,social=SocialState(trade=trade,
                    succession=SuccessionState(countries=mapOf(CountryId(42) to ThroneState(heir=10001)))))
                FilePlanetStockStore(file).use {it.save(s)}
                val bytes=Files.readString(file);assertTrue(bytes.startsWith("LOGH7-STOCK-45"))
                FilePlanetStockStore(file).use {assertEquals(s,it.load())}
                Files.writeString(file,bytes.replaceFirst("LOGH7-STOCK-45","LOGH7-STOCK-44"))
                FilePlanetStockStore(file).use {assertFails {it.load()}}
            }
        }
    }

    @Test fun routesRejectDuplicateIdsInvalidOriginsAndWrappedOrRepeatedTariffs() {
        val text=SocialStateCodec.encode(SocialState(trade=legacy))
        fun broken(route:String="10000:1000:1001:700:100",tariff:String="2:20")=
            text.split('|').toMutableList().also {it[12]="$route/$tariff/-/-/-"}.joinToString("|")
        for(route in listOf("10000:1000:1001:700:100:0","10000:1000:1001:700:100:-1","10000:1000:1001:700:100:042",
            "10000:1000:1001:700:100,10000:1000:1001:700:100"))assertFails {SocialStateCodec.decode(broken(route))}
        for(tariff in listOf("42:4294967316","42:20,42:30","0:20","42:51"))assertFails {SocialStateCodec.decode(broken(tariff=tariff))}
        val route=legacy.routes.getValue(10000).copy(country=CountryId(42))
        val mutable=linkedMapOf(10000 to route);val tariffs=linkedMapOf(42 to 20)
        val detached=TradeState(routes=mutable,tariffs=tariffs).detached()
        mutable.clear();tariffs.clear();assertEquals(route,detached.routes[10000]);assertEquals(20,detached.tariffs[42])
        assertFails {(detached.routes as MutableMap).clear()};assertFails {(detached.tariffs as MutableMap).clear()}
    }

    @Test fun additionalCountryPactsPassagesViolationsAndPaymentCountsRequire46AndResumeWithoutReplay() {
        val trade=legacy.copy(pacts=mapOf(42 to 1000),passages=mapOf(43 to 2000),violations=mapOf(44 to 3000),subsidies=mapOf(44 to 7))
        val s=stock(trade);val file=directory.resolve("diplomacy.tsv")
        FilePlanetStockStore(file).use {it.save(s)}
        val bytes=Files.readString(file);assertTrue(bytes.startsWith("LOGH7-STOCK-46"))
        FilePlanetStockStore(file).use {assertEquals(s,it.load());assertEquals(7,it.load()!!.social.trade.subsidies[44])}
        Files.writeString(file,bytes.replaceFirst("LOGH7-STOCK-46","LOGH7-STOCK-45"))
        FilePlanetStockStore(file).use {assertFails {it.load()}}
        val mutable=linkedMapOf(42 to 1000L)
        val detached=TradeState(pacts=mutable,passages=mutable,violations=mutable,subsidies=mapOf(42 to 1)).detached()
        mutable.clear();assertEquals(1000L,detached.pacts[42]);assertEquals(1000L,detached.passages[42]);assertEquals(1000L,detached.violations[42])
        for(m in listOf(detached.pacts,detached.passages,detached.violations))assertFails {(m as MutableMap).clear()}
    }
}
