package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

/** U-13 guard roster survives reopen; ordinary outfits keep the 17-field row. Author: Peppone Choi. */
class GuardUnitStoreTest {
    @TempDir lateinit var directory:Path
    @Test fun guardRosterRoundTripsAndInvalidRostersAreRejected() {
        val path=directory.resolve("stocks")
        val stack=UnitStack(32)
        val guard=OutfitStock(1,2,OutfitKind.PATROL,1000,10,10000,UnitInventory(mapOf(stack to 2)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(5,stack),ActiveShipUnit(6,stack))),
            position=OutfitPosition(2487,1000),guard=mapOf(5 to 0,6 to 1))
        val plain=OutfitStock(2,2,OutfitKind.PATROL,1000,10)
        val stocks=PlanetStocks(1,4,mapOf(1000 to PlanetStock(1000,2,0,10000,10000,10000)),
            outfits=mapOf(1 to guard,2 to plain),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1000)),
            deploymentInitialized=true,nextShipId=7)
        FilePlanetStockStore(path).use {it.save(stocks)}
        val text=Files.readString(path)
        val rows=text.lines().filter {it.startsWith("O\t")}
        assertEquals(listOf(18,17),rows.map {it.split('\t').size})
        assertTrue(rows[0].endsWith("\t5=0,6=1"))
        repeat(2) {FilePlanetStockStore(path).use {assertEquals(stocks,it.load());it.save(stocks)}}
        for(corrupt in listOf("5=0,6=13","5=0","5=0,7=1","5=x,6=1")) {
            Files.writeString(path,text.replace("5=0,6=1",corrupt))
            FilePlanetStockStore(path).use {assertFails {it.load()}}
        }
    }
}
