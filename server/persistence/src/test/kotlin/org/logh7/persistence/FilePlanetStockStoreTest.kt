package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class FilePlanetStockStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun oldStocksRetainTheirBalancesAndDayWithoutInventingPastPopulationChanges() {
        val path = directory.resolve("old")
        Files.writeString(path, "LOGH7-STOCK-1\t1\t23\n1000\t2\t0\t123\t456\t789\n")
        FilePlanetStockStore(path).use {
            val old = it.load()!!
            assertEquals(PlanetStock(1000, 2, 0, 123, 456, 789), old.bases.getValue(1000))
            assertEquals(23, old.producedDays)
            it.save(old)
        }
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-14\t1\t23\t1\t0\t0\t0\t0\t1\t0"))
        FilePlanetStockStore(path).use { assertNull(it.load()!!.bases.getValue(1000).economy) }
    }
    @Test fun exactBalancesAndProductionWatermarkSurviveReopeningAndOtherWritersAreRejected() {
        val path = directory.resolve("stocks")
        val expected = PlanetStocks(7, 23, mapOf(1000 to PlanetStock(1000, 2, 0, 123000, 456, 789,
            PlanetEconomy(100000, 60000, 70, 80))))
        FilePlanetStockStore(path).use {
            assertNull(it.load()); assertFails { FilePlanetStockStore(path) }
            it.save(expected)
        }
        FilePlanetStockStore(path).use { assertEquals(expected, it.load()) }
    }
    @Test fun malformedOrTruncatedStocksNeverResetToInitialBalances() {
        for ((index, contents) in listOf("", "LOGH7-STOCK-4\t1\t0\n", "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t123\n",
            "LOGH7-STOCK-2\t1\t0\n1000\t2\t0\t1\t2\t3\t100\t101\t70\t80\n",
            "LOGH7-STOCK-2\t1\t0\n1000\t2\t0\t1\t2\t3\t-\t-\t70\t80\n",
            "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t-1\t0\t0\n",
            "LOGH7-STOCK-1\t1\t0\n1000\t2\t0\t1\t2\t3\n1000\t2\t0\t4\t5\t6\n").withIndex()) {
            val path = directory.resolve("bad-$index")
            Files.writeString(path, contents)
            FilePlanetStockStore(path).use { assertFails { it.load() } }
            assertEquals(contents, Files.readString(path))
        }
    }
    @Test fun regularAttackOrderAndCadenceSurviveReopeningWithoutChangingLegacyShips() {
        val path=directory.resolve("fleet-attack")
        val pose=TacticalPose(TacticalPoint(0f,0f),0f)
        val motion=TacticalMotion(2488,100,pose,0f,0f,listOf(pose.position))
        val stack=UnitStack(32)
        val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(motion,100,100),supplies=8000,
            motionCommander=10000,attack=TacticalUnitAttack(10000,TacticalAttackOrder(2,100,220,340)))
        val expected=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 1)),supplies=8000,activeShips=ActiveShipUnits(listOf(ship)),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
        FilePlanetStockStore(path).use {it.save(expected)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-20"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        val text=Files.readString(path)
        Files.writeString(path,text.replace("10000~2~100~220~340","-"))
        FilePlanetStockStore(path).use {assertFails {it.load()}}
        Files.writeString(path,text.replace("10000~2~100~220~340","10000~2~100~220~200"))
        FilePlanetStockStore(path).use {assertFails {it.load()}}
        Files.writeString(path,text.replace("LOGH7-STOCK-20","LOGH7-STOCK-19"))
        FilePlanetStockStore(path).use {assertFails {it.load()}}
    }
    @Test fun pendingRegularShotRetainsWeaponTargetAndIssueTimeAndRejectsCorruption() {
        val path=directory.resolve("fleet-shot")
        val pose=TacticalPose(TacticalPoint(0f,0f),0f)
        val motion=TacticalMotion(2488,100,pose,0f,0f,listOf(pose.position))
        val stack=UnitStack(32)
        val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(motion,100,100),supplies=8000,
            motionCommander=10000,shot=TacticalUnitShot(10000,TacticalWeapon.MISSILE,99,100,220))
        val expected=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(stack to 1)),supplies=8000,activeShips=ActiveShipUnits(listOf(ship)),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
        FilePlanetStockStore(path).use {it.save(expected)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-21"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        val text=Files.readString(path)
        Files.writeString(path,text.replace("10000~2~99~100~220","10000~2~99~100~90"))
        FilePlanetStockStore(path).use {assertFails {it.load()}}
        Files.writeString(path,text.replace("LOGH7-STOCK-21","LOGH7-STOCK-20"))
        FilePlanetStockStore(path).use {assertFails {it.load()}}
    }
}
