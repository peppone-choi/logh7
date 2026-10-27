package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class FleetAideStockStoreTest {
    @TempDir lateinit var directory:Path
    private val stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000,aideId=10001)),
        warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
    @Test fun oldTenDoesNotInventAnAideAndElevenPreservesTheIndependentHolder() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use {it.save(stock)}
        val current=Files.readString(path)
        val old=current.lineSequence().filter {it.isNotEmpty()}.joinToString("\n",postfix="\n") {
            if(it.startsWith("O\t"))it.split('\t').dropLast(1).joinToString("\t") else it.replace("STOCK-11","STOCK-10")
        }
        Files.writeString(path,old)
        FilePlanetStockStore(path).use {assertEquals(stock.outfits.getValue(200).copy(aideId=0),it.load()!!.outfits[200])}
        assertEquals(old,Files.readString(path))
        Files.writeString(path,current)
        FilePlanetStockStore(path).use {assertEquals(stock,it.load())}
        for(bad in listOf(current.replace("\t10001\n","\t-1\n"),current.replace("\t10001\n","\t10000\n"))) {
            Files.writeString(path,bad);FilePlanetStockStore(path).use {assertFails {it.load()}}
            assertEquals(bad,Files.readString(path))
        }
        FileWorldStateStore(directory.resolve("world")).use {it.saveWorld(emptyMap(),stock)}
        FileWorldStateStore(directory.resolve("world")).use {assertEquals(stock,it.loadStocks())}
    }
}
