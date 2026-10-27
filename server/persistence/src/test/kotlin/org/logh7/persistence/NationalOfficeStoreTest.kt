package org.logh7.persistence

import java.nio.file.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class NationalOfficeStoreTest {
    @TempDir lateinit var directory:Path
    private val stocks=PlanetStocks(1,22,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,
        governorId=10010,capitalDefenseCommanderId=10011,guardInspectorId=10012)),
        nationalOffices=NationalOffice.entries.associateWith {10000+it.card})
    @Test fun allNationalOfficesAndCapitalFieldsSurviveAlternatingWorldCommits() {
        val root=directory.resolve("world")
        repeat(3) { FileWorldStateStore(root).use { store ->
            if(it>0)assertEquals(stocks,store.loadStocks())
            store.saveWorld(emptyMap(),stocks)
        } }
        FileWorldStateStore(root).use {assertEquals(stocks,it.loadStocks())}
    }
    @Test fun nineMigrationRetainsCapitalRolesWithoutInventingNationalAuthority() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val old=Files.readAllLines(path).filterNot {it.startsWith("N\t")}.mapIndexed {i,line ->
            if(i==0)line.split('\t').take(9).joinToString("\t").replace("STOCK-14","STOCK-9") else if(line.startsWith("P\t"))line.substringBeforeLast('\t') else line }
        Files.write(path,old)
        FilePlanetStockStore(path).use {store ->
            val value=store.load()!!
            assertEquals(stocks.copy(nationalOffices=emptyMap()),value)
            assertEquals(old,Files.readAllLines(path));store.save(value)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-14\t"))
    }
    @Test fun duplicateUnknownNegativeAndUncountedNationalRowsFailWithoutRewrite() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use {it.save(stocks)}
        val valid=Files.readString(path)
        val bad=listOf(valid.replace("N\t17\t10017","N\t2\t10017"),valid.replace("N\t2\t10002","N\t260\t10002"),
            valid.replace("N\t2\t10002","N\t2\t-1"),valid.replace("N\t2\t10002\n",""),
            valid.replace("\t1\t7\nP","\t1\t6\nP"))
        for(value in bad) {
            assertNotEquals(valid,value)
            Files.writeString(path,value)
            FilePlanetStockStore(path).use {assertFails {it.load()}}
            assertEquals(value,Files.readString(path))
        }
    }
}
