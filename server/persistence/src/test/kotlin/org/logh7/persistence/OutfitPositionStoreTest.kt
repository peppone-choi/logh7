package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class OutfitPositionStoreTest {
    @TempDir lateinit var directory:Path
    private fun stocks()=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,200,300)),
        mapOf(1 to OutfitStock(1,3,OutfitKind.FLEET,1000,10,commanderId=10000),
            2 to OutfitStock(2,3,OutfitKind.PATROL,1000,20)),
        mapOf(10 to OutfitWarehouse(10,3,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1000)))
    @Test fun legacyFourteenRetainsDepotAndFifteenRoundTripsIndependentSpacePosition() {
        val path=directory.resolve("stocks")
        val legacy=stocks()
        FilePlanetStockStore(path).use {it.save(legacy)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-14\t"))
        FilePlanetStockStore(path).use {assertEquals(legacy,it.load())}
        val moved=legacy.copy(outfits=legacy.outfits+(1 to legacy.outfits.getValue(1).copy(position=OutfitPosition(2488))))
        FilePlanetStockStore(path).use {it.save(moved)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-15\t"))
        FilePlanetStockStore(path).use {assertEquals(moved,it.load())}
        val world=directory.resolve("world")
        val player=Navigator(1,10000,Power.ALLIANCE,2488,1000,960,900)
        FileWorldStateStore(world).use {it.saveWorld(mapOf("pilot" to player),moved)}
        FileWorldStateStore(world).use {
            assertEquals(moved,it.loadStocks());assertEquals(mapOf("pilot" to player),it.loadNavigation())
        }
    }
    @Test fun malformedOrIncompletePositionFailsWithoutRewritingTheSource() {
        val path=directory.resolve("stocks")
        val stock=stocks().let {it.copy(outfits=it.outfits+(1 to it.outfits.getValue(1).copy(position=OutfitPosition(2488))))}
        FilePlanetStockStore(path).use {it.save(stock)}
        val text=Files.readString(path)
        for(position in listOf("-\t0","5000\t0","-1\t0","2488\t-","2488\t65536","2488\t9999")) {
            val bad=text.replace("2488\t0\n",position+"\n")
            assertNotEquals(text,bad);Files.writeString(path,bad)
            FilePlanetStockStore(path).use {assertFails {it.load()}}
            assertEquals(bad,Files.readString(path))
        }
    }
}
