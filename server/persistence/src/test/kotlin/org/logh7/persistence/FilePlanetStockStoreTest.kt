package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class FilePlanetStockStoreTest {
    @TempDir lateinit var directory: Path
    @Test fun groundOutfitAtSeaRequiresTheRollbackMarkerAndPreservesCarrierIdentity() {
        val path = directory.resolve("ground-at-sea")
        val depot = PlanetStock(1049, 2, 0, 100, 100, 100)
        val ship = ActiveShipUnit(1, UnitStack(84))
        val infantry = ActiveGroundUnit(2, UnitStack(0), carrier = ship.id)
        val outfit = OutfitStock(1, 2, OutfitKind.GROUND, depot.baseId, 10, commanderId = 10000,
            units = UnitInventory(mapOf(ship.stack to 1), mapOf(infantry.stack to 1)),
            activeShips = ActiveShipUnits(listOf(ship)), activeGround = ActiveGroundUnits(listOf(infantry)))
        val warehouse = OutfitWarehouse(10, 2, OutfitKind.PATROL, depot.baseId)
        val docked = PlanetStocks(1, 0, mapOf(depot.baseId to depot), mapOf(1 to outfit), mapOf(10 to warehouse),
            deploymentInitialized = true, nextShipId = 3)
        val atSea = docked.copy(outfits = mapOf(1 to outfit.copy(position = OutfitPosition(2488))))
        val marker = "LOGH7-STOCK-57\t"

        FilePlanetStockStore(path).use { store ->
            store.save(docked)
            assertFalse(Files.readString(path).startsWith(marker))
            assertEquals(docked, store.load())
            store.save(atSea)
            val saved = Files.readString(path)
            assertTrue(saved.startsWith(marker))
            assertEquals(atSea, store.load())
            Files.writeString(path, saved.removePrefix(marker))
            assertFailsWith<IllegalArgumentException> { store.load() }
        }
    }

    @Test fun generatedCharacterDeletionReceiptsRoundTripWithoutChangingOtherStockSections() {
        val path = directory.resolve("deletion")
        val stock = PlanetStock(1049, 2, 0, 100, 100, 100)
        val report = HomeIntelligenceReport(CountryId(2), stock.baseId, 100, 200, 75, 90, garrisonStrength=1200)
        val ordinary = PlanetStocks(7, 3, mapOf(stock.baseId to stock),
            strategic=StrategicState(people=mapOf(10003 to StrategicPerson(deceasedAt=150))),
            social=SocialState(justice=JusticeState(homeReports=mapOf((report.country to report.base) to report))))
        val marked = ordinary.copy(deletedCharacters=mapOf("other" to setOf(10004), "pilot" to setOf(10002, 10001)))
        FilePlanetStockStore(path).use { it.save(ordinary) }
        val legacy = Files.readString(path)
        assertTrue(legacy.startsWith("LOGH7-STOCK-54\t"))
        FilePlanetStockStore(path).use { assertEquals(ordinary, it.load()) }
        FilePlanetStockStore(path).use { it.save(marked) }
        val receipt = "D\t3\tother:10004\tpilot:10001\tpilot:10002\n"
        val serialized = Files.readString(path)
        assertEquals("LOGH7-STOCK-55\t" + legacy + receipt, serialized)
        FilePlanetStockStore(path).use { assertEquals(marked, it.load()) }
        for (invalid in listOf(serialized.removeSuffix(receipt), serialized.replace("D\t3", "D\t2"),
            serialized.replace("pilot:10002", "pilot:10001"), serialized.replace("pilot:10002", "pilot:1"),
            serialized.replace("LOGH7-STOCK-54", "LOGH7-STOCK-55"))) {
            Files.writeString(path, invalid)
            FilePlanetStockStore(path).use { assertFailsWith<IllegalArgumentException> { it.load() } }
        }
    }

    @Test fun independentOwnershipIsVersionedAndCanonical() {
        val path=directory.resolve("independent")
        val stock=PlanetStock(2000,Allegiance.Independent(10000),0,100,100,100)
        val expected=PlanetStocks(1,0,mapOf(stock.baseId to stock))
        FilePlanetStockStore(path).use {it.save(expected)}
        val serialized=Files.readString(path)
        assertTrue(serialized.startsWith("LOGH7-STOCK-48\t"))
        assertTrue("\tI:10000\t" in serialized)
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        for(invalid in listOf(serialized.replace("LOGH7-STOCK-48","LOGH7-STOCK-47"),
            serialized.replace("I:10000","I:010000"),serialized.replace("I:10000","I:0"))) {
            Files.writeString(path,invalid)
            FilePlanetStockStore(path).use {assertFailsWith<IllegalArgumentException> {it.load()}}
        }
    }
    @Test fun weaponReloadSurvivesIdleAttackAndPendingShotAndRejectsInvalidDeadlines() {
        val path=directory.resolve("reload")
        val point=TacticalPoint(0f,0f)
        val state=NpcTacticalState(TacticalMotion(2488,100,TacticalPose(point,0f),0f,0f,listOf(point)),100,100)
        val stack=UnitStack(32);val reload=TacticalReload(340,460,580)
        val unit=ActiveShipUnit(1,stack,tactical=state,supplies=8000,reload=reload)
        for(ship in listOf(unit.copy(tactical=null),
            unit.copy(motionCommander=10000,attack=TacticalUnitAttack(10000,TacticalAttackOrder(1,100,220,340))),
            unit.copy(shot=TacticalUnitShot(10000,TacticalWeapon.GUN,99,100,220)))) {
            val expected=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
                outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
                    units=UnitInventory(mapOf(stack to 1)),supplies=8000,activeShips=ActiveShipUnits(listOf(ship)),position=OutfitPosition(2488))),
                warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
            FilePlanetStockStore(path).use {it.save(expected)}
            val text=Files.readString(path);assertTrue(text.startsWith("LOGH7-STOCK-22"))
            FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
            for(corrupt in listOf("-1~460~580","340~4294967296~580","340~580","-")) {
                Files.writeString(path,text.replace("340~460~580",corrupt))
                FilePlanetStockStore(path).use {assertFails {it.load()}}
            }
            Files.writeString(path,text.replace("LOGH7-STOCK-22","LOGH7-STOCK-21"))
            FilePlanetStockStore(path).use {assertFails {it.load()}}
        }
    }
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
