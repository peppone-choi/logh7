package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class FleetStaffStockStoreTest {
    @TempDir lateinit var directory:Path
    private val full=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000,aideId=10001,
            staff=FleetStaff(10002,10003,(10004..10009).toList()))),
        warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
    @Test fun twelveKeepsAllTenPeopleAndElevenKeepsItsCommanderAndAideWithoutInventingStaff() {
        val path=directory.resolve("stock")
        FilePlanetStockStore(path).use {it.save(full)}
        val text=Files.readString(path)
        FilePlanetStockStore(path).use {assertEquals(full,it.load())}
        val old=text.lineSequence().filter {it.isNotEmpty()}.joinToString("\n",postfix="\n") {
            if(it.startsWith("P\t"))it.substringBeforeLast('\t')
            else if(it.startsWith("O\t"))it.split('\t').dropLast(1).joinToString("\t")
            else if(it.startsWith("W\t"))it.substringBeforeLast('\t') else it.replace("STOCK-14","STOCK-11")
        }
        Files.writeString(path,old)
        FilePlanetStockStore(path).use {assertEquals(full.copy(outfits=mapOf(200 to full.outfits.getValue(200).copy(staff=FleetStaff()))),it.load())}
        assertEquals(old,Files.readString(path))
        val root=directory.resolve("world")
        repeat(3) {i->FileWorldStateStore(root).use {if(i>0)assertEquals(full,it.loadStocks());it.saveWorld(emptyMap(),full)}}
    }
    @Test fun malformedNegativeDuplicateAndOverCapacityStaffAreRejectedWithoutRewriting() {
        val path=directory.resolve("stock")
        FilePlanetStockStore(path).use {it.save(full)}
        val text=Files.readString(path);val valid="10002/10003/10004,10005,10006,10007,10008,10009"
        for(value in listOf("-1/10003/-","10002/10002/-","10002/10003/10004,10004","10002/10003/10000",
            "10002/10003/10001","10002/10003/1,2,3,4,5,6,7","10002/10003","10002/10003/0")) {
            val bad=text.replace(valid,value);assertNotEquals(text,bad);Files.writeString(path,bad)
            FilePlanetStockStore(path).use {assertFails {it.load()}};assertEquals(bad,Files.readString(path))
        }
    }
}
