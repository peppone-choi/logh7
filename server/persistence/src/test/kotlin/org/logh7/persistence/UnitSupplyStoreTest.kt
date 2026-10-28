package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class UnitSupplyStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun stockEighteenReopensPendingSupportAndDamageAndRejectsOlderSchema() {
        val stack=UnitStack(32)
        val order=TacticalSupportOrder(TacticalSupportAction.REPAIR,10000,1010000,2488,100,220)
        val expected=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1,2,3)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 2)),supplies=1000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,supplies=1000,support=order),
                    ActiveShipUnit(2,stack,250,damaged=30))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=3)
        val path=directory.resolve("support")
        FilePlanetStockStore(path).use {it.save(expected)}
        val text=Files.readString(path)
        assertTrue(text.startsWith("LOGH7-STOCK-18\t"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        Files.writeString(path,text.replace("LOGH7-STOCK-18","LOGH7-STOCK-17"))
        assertFailsWith<IllegalArgumentException> {FilePlanetStockStore(path).use {it.load()}}
        val encoded=TacticalSupportCodec.encode(order)
        assertEquals(order,TacticalSupportCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {TacticalSupportCodec.decode(encoded+"=")}
        assertFailsWith<IllegalArgumentException> {ActiveShipUnit(1,stack,20,damaged=21)}
    }

    @Test fun unitCargoReopensTogetherWithNpcStateAndLegacyStockDoesNotInventAllocations() {
        val stack=UnitStack(32)
        val point=TacticalPoint(180f,-95f)
        val ai=NpcTacticalState(TacticalMotion(2488,100,TacticalPose(point,0f),0f,0f,listOf(point)),124,220,7,1010000)
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 2)),supplies=10000,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,tactical=ai),ActiveShipUnit(2,stack))),position=OutfitPosition(2488))
        val allocated=OutfitSupplies.change(OutfitSupplies.withBalances(outfit,mapOf(1 to 2000,2 to 8000)),2,-1500)
        val expected=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1,2,3)),outfits=mapOf(1 to allocated),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=3)
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use {it.save(expected)}
        val text=Files.readString(path)
        assertTrue(text.startsWith("LOGH7-STOCK-17\t"))
        FilePlanetStockStore(path).use {
            val actual=checkNotNull(it.load()); assertEquals(expected,actual)
            assertEquals(mapOf(1 to 2000,2 to 6500),OutfitSupplies.distribution(actual.outfits.getValue(1)))
        }
        Files.writeString(path,text.replace("LOGH7-STOCK-17","LOGH7-STOCK-16"))
        assertFailsWith<IllegalArgumentException> { FilePlanetStockStore(path).use {it.load()} }
        val legacy=expected.copy(outfits=mapOf(1 to outfit))
        FilePlanetStockStore(path).use {it.save(legacy)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-16\t"))
        FilePlanetStockStore(path).use {
            val restored=it.load()!!.outfits.getValue(1)
            assertTrue(restored.activeShips!!.values.all {unit->unit.supplies==null})
            assertEquals(mapOf(1 to 10000,2 to 0),OutfitSupplies.distribution(restored))
        }
        assertFailsWith<IllegalArgumentException> {ActiveShipUnitCodec.decode("1.32.0.300.-.2",tactics=true)}
        assertFailsWith<IllegalArgumentException> {ActiveShipUnitCodec.decode("1.32.0.300.-.-1",tactics=true,cargo=true)}
        assertFailsWith<IllegalArgumentException> {ActiveShipUnitCodec.decode("1.32.0.300.-.9999999",tactics=true,cargo=true)}
    }
}
