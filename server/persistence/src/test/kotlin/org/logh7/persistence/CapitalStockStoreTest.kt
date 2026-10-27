package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class CapitalStockStoreTest {
    @TempDir lateinit var directory: Path
    private val stocks=PlanetStocks(1,22,mapOf(
        1049 to PlanetStock(1049,2,0,100,200,300,governorId=10000,garrisonCommanderId=10001,
            capitalDefenseCommanderId=10002,guardInspectorId=10003),
        1200 to PlanetStock(1200,3,0,400,500,600,capitalAdministratorId=10004,capitalDefenseCommanderId=10005)))
    @Test fun formatEightKeepsLegacyIdsAndDoesNotInventCapitalAppointments() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use { it.save(stocks) }
        val old=Files.readAllLines(path).joinToString("\n",postfix="\n") {
            if(it.startsWith("P\t")) it.split('\t').take(15).joinToString("\t") else it.split('\t').take(9).joinToString("\t").replace("STOCK-14","STOCK-8") }
        Files.writeString(path,old)
        FilePlanetStockStore(path).use {
            val loaded=it.load()!!
            assertEquals(10000,loaded.bases.getValue(1049).governorId)
            assertEquals(10001,loaded.bases.getValue(1049).garrisonCommanderId)
            assertTrue(loaded.bases.values.all { b -> b.capitalAdministratorId==0 && b.capitalDefenseCommanderId==0 && b.guardInspectorId==0 })
            assertEquals(old,Files.readString(path));it.save(loaded)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-14\t"))
    }
    @Test fun pairedWorldSlotsRetainAllDistinctCapitalAndLegacyHolders() {
        val root=directory.resolve("world")
        repeat(3) { FileWorldStateStore(root).use { store ->
            if(it>0) assertEquals(stocks,store.loadStocks())
            store.saveWorld(emptyMap(),stocks)
        } }
        FileWorldStateStore(root).use { assertEquals(stocks,it.loadStocks()) }
    }
    @Test fun eachNewColumnMustBePresentNonnegativeAndStructurallyValid() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use { it.save(stocks) }
        val valid=Files.readAllLines(path)
        for(column in 15..17) {
            val bad=valid.mapIndexed { i,line -> if(i==1) line.split('\t').toMutableList().also { it[column]="-1" }.joinToString("\t") else line }
            Files.write(path,bad)
            FilePlanetStockStore(path).use { assertFails { it.load() } }
            assertEquals(bad,Files.readAllLines(path))
        }
    }
}
