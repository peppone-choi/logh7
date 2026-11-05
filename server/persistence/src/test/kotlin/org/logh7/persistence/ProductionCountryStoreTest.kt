package org.logh7.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class ProductionCountryStoreTest {
    @TempDir lateinit var directory:Path
    private val base=PlanetStock(1049,2,0,1000,2000,3000,units=UnitInventory(work=mapOf("S32" to 37,"S119" to 13)),productionCountry=CountryId(2))
    private val stocks=PlanetStocks(1,7,mapOf(1049 to base))

    @Test fun progressOwnershipAndBothModelQueuesRoundTripInFormat42() {
        val file=directory.resolve("stocks")
        FilePlanetStockStore(file).use {it.save(stocks)}
        assertTrue(Files.readString(file).startsWith("LOGH7-STOCK-42\t"))
        FilePlanetStockStore(file).use {assertEquals(stocks,it.load())}
        val player=Navigator(1,10000,CountryId(2),2487,900,800,700,shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(89,0))
        val world=directory.resolve("world")
        FileWorldStateStore(world).use {it.saveWorld(mapOf("a" to player),stocks)}
        FileWorldStateStore(world).use {assertEquals(stocks,it.loadStocks());assertEquals(mapOf("a" to player),it.loadNavigation())}
    }

    @Test fun untaggedLegacyProgressDoesNotAcquireAnOwnerOrANewFormatOnRead() {
        val file=directory.resolve("legacy")
        val legacy=stocks.copy(bases=mapOf(1049 to base.copy(productionCountry=null)))
        FilePlanetStockStore(file).use {it.save(legacy)}
        val before=Files.readString(file)
        assertFalse(before.startsWith("LOGH7-STOCK-42"))
        FilePlanetStockStore(file).use {store->val loaded=assertNotNull(store.load());assertEquals(legacy,loaded);store.save(loaded)}
        assertEquals(before,Files.readString(file))
    }

    @Test fun malformedProgressCountriesAndOlderHeadersCannotSilentlyDropOwnership() {
        val source=directory.resolve("source")
        FilePlanetStockStore(source).use {it.save(stocks)}
        val valid=Files.readString(source)
        val badCountries=listOf("0","-1","02","+2","bad").map {country->valid.lineSequence().map {line->
            if(line.startsWith("P\t"))line.substringBeforeLast('\t')+"\t"+country else line
        }.joinToString("\n")}
        val malformed=badCountries+valid.replace("LOGH7-STOCK-42","LOGH7-STOCK-41")+
            valid.lineSequence().map {if(it.startsWith("P\t"))it.substringBeforeLast('\t') else it}.joinToString("\n")
        for((index,text) in malformed.withIndex()) {
            val file=directory.resolve("bad-$index");Files.writeString(file,text)
            assertFails {FilePlanetStockStore(file).use {it.load()}}
        }
        FilePlanetStockStore(source).use {assertEquals(stocks,it.load())}
    }
}
