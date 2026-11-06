package org.logh7.persistence

import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.io.path.*
import kotlin.test.*

class CountryOfficeStateStoreTest {
    @TempDir lateinit var directory:Path
    private val country=CountryId(42)
    private val state=StrategicState(offices=listOf(OfficeHolding(24,1050,10001,country),OfficeHolding(24,1051,10002,CountryId(43))),
        capitals=mapOf(country to 1050,CountryId(43) to 1051))
    private val stocks=PlanetStocks(1,0,mapOf(1050 to PlanetStock(1050,Allegiance.Country(country),0,100,200,300),
        1051 to PlanetStock(1051,Allegiance.Country(CountryId(43)),0,100,200,300)),strategic=state)

    @Test fun explicitOfficeCountriesAndCapitalsSurviveFileReopeningAndRejectDowngradedHeaders() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val bytes=path.readBytes();val text=path.readText()
        assertTrue(text.startsWith("LOGH7-STOCK-43\t"));assertTrue(text.contains("24:1050:10001:42;24:1051:10002:43"))
        FilePlanetStockStore(path).use {assertEquals(stocks,it.load());it.save(stocks)}
        assertContentEquals(bytes,path.readBytes())
        for(header in listOf("LOGH7-STOCK-42","LOGH7-STOCK-41")) {
            val forged=directory.resolve("$header.tsv");forged.writeText(text.replaceFirst("LOGH7-STOCK-43",header))
            FilePlanetStockStore(forged).use {assertFailsWith<IllegalArgumentException>{it.load()}}
        }
        assertContentEquals(bytes,path.readBytes())
    }

    @Test fun originalOfficeRowsKeepTheirCanonicalThreeFieldsAndPreviousStoreFormat() {
        val legacy=StrategicState(offices=listOf(OfficeHolding(24,1049,10001),OfficeHolding(164,1177,10002)))
        val encoded=StrategicStateCodec.encode(legacy)
        assertEquals("24:1049:10001;164:1177:10002",encoded.split('|')[2])
        assertEquals(legacy,StrategicStateCodec.decode(encoded));assertFalse(legacy.countryOffices)
        val old=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),strategic=legacy)
        val path=directory.resolve("legacy.tsv")
        FilePlanetStockStore(path).use {it.save(old)}
        assertTrue(path.readText().startsWith("LOGH7-STOCK-29\t"))
        val bytes=path.readBytes()
        FilePlanetStockStore(path).use {assertEquals(old,it.load());it.save(old)}
        assertContentEquals(bytes,path.readBytes())
    }

    @Test fun invalidCountryCapitalAndDuplicateIdentityPayloadsCannotBeRecovered() {
        val text=StrategicStateCodec.encode(state)
        assertEquals(state,StrategicStateCodec.decode(text,true))
        for(bad in listOf(text.replace(":10001:42",":10001:0"),text.replace("42:1050;43:1051","42:1050;42:1051"),
            text.replace("42:1050;43:1051","42:1050;43:1050"),text.replace("24:1050:10001:42","24:1050:10001:42;24:1050:10001:42")))
            assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(bad,true)}
        assertFailsWith<IllegalArgumentException>{StrategicStateCodec.decode(text)}
        assertFailsWith<IllegalArgumentException>{stocks.copy(strategic=state.copy(capitals=mapOf(country to 9999)))}
    }

    @Test fun aFailedWorldCommitKeepsBothOfficeCountriesAndThePreviousCpBalance() {
        val root=directory.resolve("world");val nav=mapOf("officer" to Navigator(1,10001,country,2487,1000,1000,1000,
            shipStyle=ShipStyleId("alliance")))
        FileWorldStateStore(root).use {it.saveWorld(nav,stocks)}
        FileWorldStateStore(root).use {store->
            store.beforeSwitch={throw IOException("injected office commit failure")}
            assertFailsWith<IOException>{store.saveWorld(nav.mapValues {it.value.copy(mcp=840)},
                stocks.copy(strategic=state.copy(offices=state.offices.drop(1))))}
        }
        FileWorldStateStore(root).use {store->assertEquals(nav,store.loadNavigation());assertEquals(stocks,store.loadStocks())}
    }
}
