package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OutfitCountryStoreTest {
    @TempDir lateinit var directory:Path
    private val base=PlanetStock(1000,2,0,100,200,300)
    private val fleet=OutfitStock(1,2,OutfitKind.FLEET,1000,1)
    private val depot=OutfitWarehouse(1,2,OutfitKind.FLEET,1000,1)

    @Test fun legacyOwnerRowsKeepTheirFormatAndNewCountryStyleGuardAndWarehouseRoundTripTogether() {
        val path=directory.resolve("stocks")
        val old=PlanetStocks(1,0,mapOf(1000 to base),mapOf(1 to fleet),mapOf(1 to depot))
        FilePlanetStockStore(path).use {store->
            store.save(old)
            assertFalse(Files.readString(path).startsWith("LOGH7-STOCK-41"))
            assertEquals(old,store.load())
        }
        val option=GuardShips.options.first {it.power==2}
        val stack=UnitStack(option.baseKind)
        val guard=OutfitStock(2,CountryId(42),OutfitKind.PATROL,1000,2,units=UnitInventory(ships=mapOf(stack to 1)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(101,stack))),guard=mapOf(101 to option.index),
            position=OutfitPosition(2487,1000),strategy=NpcStrategicState(100),shipStyle=ShipStyleId("empire"))
        val guardedDepot=OutfitWarehouse(2,CountryId(42),OutfitKind.PATROL,1000,shipStyle=ShipStyleId("empire"))
        val otherStyle=fleet.copy(id=3,warehouseId=3,shipStyle=ShipStyleId("alliance"))
        val extraFleets=listOf(1,4).associate {id->(10+id) to fleet.copy(id=10+id,warehouseId=10+id,country=CountryId(id))}
        val extraDepots=listOf(1,4).associate {id->(10+id) to depot.copy(id=10+id,outfitId=10+id,country=CountryId(id))}
        val expected=old.copy(outfits=old.outfits+mapOf(2 to guard,3 to otherStyle)+extraFleets,
            warehouses=old.warehouses+mapOf(2 to guardedDepot,3 to depot.copy(id=3,outfitId=3,shipStyle=ShipStyleId("alliance")))+extraDepots,
            nextShipId=102,nationalTreasuries=mapOf(CountryId(42) to 777L))
        FilePlanetStockStore(path).use {it.save(expected)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-41\t"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        val world=directory.resolve("world")
        FileWorldStateStore(world).use {it.saveWorld(emptyMap(),expected)}
        FileWorldStateStore(world).use {assertEquals(expected,it.loadStocks())}
    }

    @Test fun invalidCountryStyleGuardAndLegacyDisguiseAreRejectedWithoutReplacingTheInput() {
        val path=directory.resolve("stocks")
        val stock=PlanetStocks(1,0,mapOf(1000 to base),mapOf(1 to fleet.copy(country=CountryId(42))),
            mapOf(1 to depot.copy(country=CountryId(42))))
        FilePlanetStockStore(path).use {it.save(stock)}
        val valid=Files.readString(path)
        val invalid=listOf("0","-","-1","042","+42","2147483648").map {id->
            valid.replace("O\t1\t42\t","O\t1\t$id\t")
        }+listOf("","Empire","bad style").map {style->valid.replace("\tempire\n","\t$style\n")}+
            valid.replace("LOGH7-STOCK-41","LOGH7-STOCK-40")+
            valid.replace("W\t1\t42\t","W\t1\t2\t")+
            valid.lineSequence().joinToString("\n") {line->if(line.startsWith("O\t"))
                line.split('\t').toMutableList().also {it[17]="bad_guard"}.joinToString("\t") else line}
        for(text in invalid) {
            assertNotEquals(valid,text)
            Files.writeString(path,text)
            assertFails {FilePlanetStockStore(path).use {it.load()}}
            assertEquals(text,Files.readString(path))
        }
    }
}
