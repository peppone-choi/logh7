package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BaseCountryStoreTest {
    @TempDir lateinit var directory:Path

    @Test fun oldOwnershipCodesAndCampBitsSurviveAndNewCountryOwnersUseFormat40() {
        val path=directory.resolve("stocks.tsv")
        val legacy="LOGH7-STOCK-1\t1\t0\n"+(0..4).joinToString("") {"${1000+it}\t$it\t${it%2}\t100\t200\t300\n"}
        Files.writeString(path,legacy)
        val expected:PlanetStocks
        FilePlanetStockStore(path).use {store->
            val old=assertNotNull(store.load())
            for(code in 0..4)assertEquals(Allegiance.fromLegacy(code),old.bases.getValue(1000+code).owner)
            store.save(old)
            assertFalse(Files.readString(path).startsWith("LOGH7-STOCK-40"))
            expected=old.copy(bases=old.bases+listOf(1,4,42).associate {id->
                (1100+id) to PlanetStock(1100+id,Allegiance.Country(CountryId(id)),0,100,200,300)},
                nationalTreasuries=mapOf(CountryId(42) to 777L))
            store.save(expected)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-40\t"))
        FilePlanetStockStore(path).use {store->
            assertEquals(expected,store.load())
            assertNotEquals(store.load()?.bases?.get(1001)?.owner,store.load()?.bases?.get(1101)?.owner)
            assertNotEquals(store.load()?.bases?.get(1004)?.owner,store.load()?.bases?.get(1104)?.owner)
        }
        val world=directory.resolve("world")
        FileWorldStateStore(world).use {it.saveWorld(emptyMap(),expected)}
        FileWorldStateStore(world).use {assertEquals(expected,it.loadStocks())}
    }

    @Test fun invalidAndDisguisedOwnerTokensAreRejectedWithoutRewritingTheInput() {
        val path=directory.resolve("stocks.tsv")
        val stock=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,Allegiance.Country(CountryId(42)),0,1,2,3)))
        FilePlanetStockStore(path).use {it.save(stock)}
        val valid=Files.readString(path)
        val invalid=listOf("C:0","C:-1","C:042","C:42:3","N:42","2","unknown").map {valid.replace("P\t1000\tC:42\t","P\t1000\t$it\t")}+
            valid.replace("LOGH7-STOCK-40","LOGH7-STOCK-39")
        for(text in invalid) {
            assertNotEquals(valid,text)
            Files.writeString(path,text)
            assertFailsWith<IllegalArgumentException> {FilePlanetStockStore(path).use {it.load()}}
            assertEquals(text,Files.readString(path))
        }
    }
}
