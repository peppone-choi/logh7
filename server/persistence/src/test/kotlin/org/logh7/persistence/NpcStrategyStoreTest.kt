package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class NpcStrategyStoreTest {
    @TempDir lateinit var directory:Path
    @Test fun pendingAndIdleReservationsSurviveReopenAndInvalidOrDowngradedRowsAreRejected() {
        val path=directory.resolve("stocks")
        for(order in listOf(NpcStrategicState(21600,2488,3600),NpcStrategicState(25200))) {
            val stack=UnitStack(32)
            val stocks=PlanetStocks(1,4,mapOf(1000 to PlanetStock(1000,2,0,10000,10000,10000)),
                outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1),mapOf(UnitStack(0) to 1)),
                    activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack))),position=OutfitPosition(2487,1000),activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1000))),strategy=order)),
                warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=3)
            FilePlanetStockStore(path).use {it.save(stocks)}
            val text=Files.readString(path);assertTrue(text.startsWith("LOGH7-STOCK-27"))
            repeat(2) {FilePlanetStockStore(path).use {assertEquals(stocks,it.load());it.save(stocks)}}
            val encoded="${order.nextDecisionAt},${order.destination ?: "-"},${order.arrivesAt ?: "-"}"
            for(corrupt in listOf("10,2488,-","10,-,9","10,5000,9","10,2488,11","10,2488,9,0")) {
                Files.writeString(path,text.replace(encoded,corrupt))
                FilePlanetStockStore(path).use {assertFails {it.load()}}
            }
            Files.writeString(path,text.replace("LOGH7-STOCK-27","LOGH7-STOCK-26"))
            FilePlanetStockStore(path).use {assertFails {it.load()}}
        }
    }
}
