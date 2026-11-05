package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CountryTreasuryStoreTest {
    @TempDir lateinit var directory:Path
    private val balances=mapOf(CountryId(2) to 76_543_210_000L,CountryId(3) to 17L,
        CountryId(42) to 777L,CountryId(43) to 888L)

    @Test fun legacyTreasuryRowsKeepTheirAmountsAndAdditionalCountriesUseAnExplicitNewFormat() {
        val path=directory.resolve("stocks.tsv")
        // Frozen legacy fiscal header, no bases/outfits, two national accounts.
        val legacy="LOGH7-STOCK-28\t1\t0\t0\t0\t0\t0\t0\t1\t0\t0\t2\nT\t2\t76543210000\nT\t3\t17\n"
        Files.writeString(path,legacy)
        val expected:PlanetStocks
        FilePlanetStockStore(path).use {store->
            val old=assertNotNull(store.load())
            assertEquals(balances.filterKeys {it.value in 2..3},old.nationalTreasuries)
            store.save(old)
            assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-28\t"))
            expected=old.copy(nationalTreasuries=balances)
            store.save(expected)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-39\t"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
    }

    @Test fun malformedDuplicateAndLegacyDisguisedCountryAccountsAreRejectedWithoutRewriting() {
        val path=directory.resolve("stocks.tsv")
        FilePlanetStockStore(path).use {it.save(PlanetStocks(1,0,emptyMap(),nationalTreasuries=balances))}
        val valid=Files.readString(path)
        val invalid=listOf(valid.replace("T\t42\t777","T\t0\t777"),
            valid.replace("T\t42\t777","T\t42\t-1"),
            valid.replace("T\t43\t888","T\t42\t888"),valid.replace("LOGH7-STOCK-39","LOGH7-STOCK-38"))
        invalid.forEach {text->
            assertNotEquals(valid,text)
            Files.writeString(path,text)
            assertFailsWith<IllegalArgumentException> {FilePlanetStockStore(path).use {it.load()}}
            assertEquals(text,Files.readString(path))
        }
    }

    @Test fun allCountryAccountsCommitWithTheWorldAndAFailedPointerSwitchPublishesNeitherChange() {
        val root=directory.resolve("world")
        val player=Navigator(1,10000,Power.EMPIRE,2015,1000,1000,1000)
        val before=PlanetStocks(1,0,emptyMap(),nationalTreasuries=balances)
        FileWorldStateStore(root).use {store->
            store.saveWorld(mapOf("pilot" to player),before)
            store.beforeSwitch={throw IOException("fixture failure before pointer switch")}
            assertFailsWith<IOException> {store.saveWorld(mapOf("pilot" to player.copy(grid=2016)),
                before.copy(nationalTreasuries=balances+(CountryId(42) to 700L)))}
        }
        FileWorldStateStore(root).use {store->
            assertEquals(mapOf("pilot" to player),store.loadNavigation())
            assertEquals(before,store.loadStocks())
            val after=before.copy(nationalTreasuries=balances+(CountryId(42) to 700L))
            store.saveWorld(mapOf("pilot" to player.copy(grid=2016)),after)
        }
        FileWorldStateStore(root).use {store->
            assertEquals(2016,store.loadNavigation().getValue("pilot").grid)
            assertEquals(700L,store.loadStocks()?.nationalTreasuries?.get(CountryId(42)))
            assertEquals(balances[CountryId(43)],store.loadStocks()?.nationalTreasuries?.get(CountryId(43)))
        }
    }
}
