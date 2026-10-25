package org.logh7.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import kotlin.test.*

class GarrisonStockStoreTest {
    @TempDir lateinit var directory: Path
    private val stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,
        taxRate=35,governorId=10000,garrisonCommanderId=10001)))
    @Test fun oldSevenRetainsGovernorAndDoesNotInventAGarrisonAppointment() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use { it.save(stock) }
        val old=Files.readAllLines(path).map { line ->
            if (line.startsWith("P\t")) line.split('\t').dropLast(1).joinToString("\t")
            else line.replace("LOGH7-STOCK-8","LOGH7-STOCK-7")
        }.joinToString("\n",postfix="\n")
        Files.writeString(path,old)
        FilePlanetStockStore(path).use {
            val imported=it.load()!!
            assertEquals(10000,imported.bases.getValue(1049).governorId)
            assertEquals(0,imported.bases.getValue(1049).garrisonCommanderId)
            assertEquals(35,imported.bases.getValue(1049).taxRate)
            assertEquals(old,Files.readString(path))
            it.save(imported)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-8\t"))
        FilePlanetStockStore(path).use { assertEquals(0,it.load()!!.bases.getValue(1049).garrisonCommanderId) }
    }
    @Test fun worldCommitAndReopenKeepGovernorAndGarrisonSeparate() {
        val root=directory.resolve("world")
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        FileWorldStateStore(root).use { it.saveWorld(mapOf("pilot" to player),stock) }
        repeat(2) { FileWorldStateStore(root).use {
            assertEquals(stock,it.loadStocks());assertEquals(player,it.loadNavigation()["pilot"])
            it.saveWorld(it.loadNavigation(),it.loadStocks())
        } }
    }
    @Test fun truncatedOrNegativeAppointmentIsRejectedWithoutRewritingTheFile() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use { it.save(stock) }
        val valid=Files.readString(path)
        for (bad in listOf(valid.replace("\t10001\n","\n"),valid.replace("\t10001\n","\t-1\n"))) {
            Files.writeString(path,bad)
            FilePlanetStockStore(path).use { assertFails { it.load() } }
            assertEquals(bad,Files.readString(path))
        }
        assertFails { stock.bases.getValue(1049).copy(power=0) }
        assertFails { stock.bases.getValue(1049).copy(camp=1) }
    }
}
