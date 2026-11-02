package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class GroundUnitStoreTest {
    @TempDir lateinit var directory:Path
    private val ship=UnitStack(32)
    private val troop=UnitStack(0)
    private fun legacy()=PlanetStocks(1,7,mapOf(1000 to PlanetStock(1000,2,0,100,200,300)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,101,
            units=UnitInventory(mapOf(ship to 1),mapOf(troop to 2,UnitStack(3) to 4)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,ship))))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)

    @Test fun legacyMigrationAndReloadPreserveIdsBalancesAndUnknownAllocation() {
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use {it.save(legacy())}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-14\t"))
        val migrated=FilePlanetStockStore(path).use {store ->
            val old=store.load()!!;assertNull(old.outfits.getValue(1).activeGround)
            ActiveShips.initialize(old).also(store::save)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-24\t"))
        FilePlanetStockStore(path).use {store ->
            val loaded=store.load()!!;assertEquals(migrated,loaded)
            assertSame(loaded,ActiveShips.initialize(loaded))
            assertEquals(legacy().outfits.getValue(1).units,loaded.outfits.getValue(1).units)
            assertEquals(listOf(2,3),loaded.outfits.getValue(1).activeGround!!.values.map {it.id})
            assertTrue(loaded.outfits.getValue(1).activeGround!!.values.all {it.carrier==0 && it.base==0})
        }
    }
    @Test fun knownCarrierAndAshoreLocationRoundTripAndCorruptionCannotResetStock() {
        val path=directory.resolve("mixed")
        val initial=ActiveShips.initialize(legacy());val old=initial.outfits.getValue(1)
        val expected=initial.copy(outfits=mapOf(1 to old.copy(activeGround=ActiveGroundUnits(listOf(
            ActiveGroundUnit(2,troop,carrier=1),ActiveGroundUnit(3,troop,base=1000))))))
        FilePlanetStockStore(path).use {it.save(expected)}
        val text=Files.readString(path)
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        for(replacement in listOf("2~0~0~99~0", "2~0~0~1~1000", "1~0~0~0~0", "2~3~0~0~0",
            "2~4~0~0~0", "2~0~0~0~9999", "02~0~0~1~0", "2~0~0~1", "-", "0")) {
            val bad=text.replace("2~0~0~1~0,3~0~0~0~1000",replacement)
            assertNotEquals(text,bad)
            Files.writeString(path,bad)
            FilePlanetStockStore(path).use {store ->
                // '-' explicitly means not migrated; it is allowed, unlike an empty or corrupt roster.
                if(replacement=="-")assertNull(store.load()!!.outfits.getValue(1).activeGround)
                else assertFails {store.load()}
            }
            assertEquals(bad,Files.readString(path))
        }
    }
    @Test fun explicitEmptyRosterIsPreservedAndCodecRejectsDuplicateIds() {
        val path=directory.resolve("empty")
        val initial=legacy();val old=initial.outfits.getValue(1)
        val expected=initial.copy(outfits=mapOf(1 to old.copy(
            units=UnitInventory(mapOf(ship to 1)),activeGround=ActiveGroundUnits(emptyList()))))
        FilePlanetStockStore(path).use {it.save(expected)}
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        assertFailsWith<IllegalArgumentException> {ActiveGroundUnitCodec.decode("2~0~0~0~0,2~0~0~0~0")}
        assertFailsWith<IllegalArgumentException> {ActiveGroundUnitCodec.decode("")}
    }
    @Test fun groundOrdersAndReturnCarrierSurviveReopeningAndOldFormatRejectsNewFields() {
        val initial=ActiveShips.initialize(legacy());val outfit=initial.outfits.getValue(1)
        val order=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,1,1000,100,700)
        val path=directory.resolve("deployments")
        for(unit in listOf(ActiveGroundUnit(2,troop,carrier=1,order=order),
            ActiveGroundUnit(2,troop,base=1000,origin=1),
            ActiveGroundUnit(2,troop,base=1000,origin=1,order=order.copy(action=TacticalGroundAction.WITHDRAW)))) {
            val expected=initial.copy(outfits=mapOf(1 to outfit.copy(activeGround=ActiveGroundUnits(listOf(unit,ActiveGroundUnit(3,troop))))))
            FilePlanetStockStore(path).use {it.save(expected)}
            assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-25\t"))
            repeat(2){FilePlanetStockStore(path).use {store->assertEquals(expected,store.load());store.save(expected)}}
            val text=Files.readString(path);Files.writeString(path,text.replace("LOGH7-STOCK-25","LOGH7-STOCK-24"))
            FilePlanetStockStore(path).use {assertFails {it.load()}}
        }
    }
    @Test fun partialGroundStrengthAndBattleClockRoundTripAndOldVersionsCannotSilentlyHeal() {
        val initial=ActiveShips.initialize(legacy());val old=initial.outfits.getValue(1)
        val path=directory.resolve("ground-combat")
        val expected=initial.copy(outfits=mapOf(1 to old.copy(activeGround=ActiveGroundUnits(listOf(
            ActiveGroundUnit(2,troop,base=1000,strength=731),ActiveGroundUnit(3,troop,carrier=1))))),
            groundBattles=mapOf(1000 to 12345L))
        FilePlanetStockStore(path).use {it.save(expected)}
        val text=Files.readString(path);assertTrue(text.startsWith("LOGH7-STOCK-26\t"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        for(bad in listOf(text.replace("~731","~0"),text.replace("~731","~1001"),
            text.replace("G\t1000\t12345","G\t9999\t12345"),text.replace("~731","~0731"),
            text.replace("LOGH7-STOCK-26","LOGH7-STOCK-25"))) {
            Files.writeString(path,bad)
            FilePlanetStockStore(path).use {assertFails {it.load()}}
            assertEquals(bad,Files.readString(path))
        }
        assertFails {ActiveGroundUnitCodec.decode("2~0~0~0~1000~0~-~731",deployments=true)}
    }

    @Test fun groundClockCanBeSavedBeforeLegacyOutfitMigrationWithoutChangingItsRoster() {
        val expected=legacy().copy(groundBattles=mapOf(1000 to 12345L))
        val path=directory.resolve("clock-before-migration")
        FilePlanetStockStore(path).use {it.save(expected)}
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
    }

}
