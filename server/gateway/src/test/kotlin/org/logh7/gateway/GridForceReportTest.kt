package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import java.nio.ByteBuffer
import kotlin.test.*

class GridForceReportTest {
    private val viewer = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000)
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2563,4,"Other",3)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f),
            GalaxyBase(1050,2563,80,0,"Other",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2563))
    private val session = SessionSnapshot(1, 0, 0, 0, emptyMap(), 2, 1, null)
    private fun counts(snapshot: WorldSnapshot, player: Navigator = viewer): Map<Int, Int> {
        val input = ByteBuffer.wrap(checkNotNull(GridForceReport.message(snapshot, galaxy, player, 1)))
        input.position(18)
        return List(input.get().toInt() and 255) {
            val grid = input.short.toInt() and 65535
            assertEquals(1, input.get().toInt()); assertEquals(2, input.get().toInt()); assertEquals(0, input.get().toInt())
            grid to (input.short.toInt() and 65535)
        }.toMap().also { assertFalse(input.hasRemaining()) }
    }
    @Test fun storedFlagshipsFollowMovementAndGenerationWithoutLeakingEnemies() {
        val offline = viewer.copy(characterId=10001, grid=2488)
        val state = WorldSnapshot(0,0,session, navigators=mapOf("self" to viewer, "offline" to offline,
            "enemy" to viewer.copy(characterId=10002, power=Power.ALLIANCE),
            "old" to viewer.copy(characterId=10003, generation=2),
            "far" to viewer.copy(characterId=10004, grid=2495)))
        assertEquals(mapOf(2487 to 1,2488 to 1), counts(state))
        assertEquals(mapOf(2487 to 2), counts(state.copy(navigators=state.navigators+("offline" to offline.copy(grid=2487)))))
        assertNull(GridForceReport.message(state.copy(session=session.copy(generation=2)), galaxy, viewer, 1))
    }
    @Test fun countsDeployedShipBoatsButNotGroundTroopsCrewOrWarehouses() {
        val stock = PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.PATROL,1049,10,
                units=UnitInventory(mapOf(UnitStack(32) to 10),mapOf(UnitStack(0) to 2,UnitStack(3) to 40)))),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1049,
                units=UnitInventory(mapOf(UnitStack(0) to 1000)))))
        assertEquals(mapOf(2487 to 3001), counts(WorldSnapshot(0,0,session,navigators=mapOf("self" to viewer),planetStocks=stock)))
    }
    @Test fun partialLossChangesMapShipsWithoutChangingGridEntryUnitSlots() {
        val stack=UnitStack(32)
        val roster=ActiveShipUnits(listOf(ActiveShipUnit(1,stack),ActiveShipUnit(2,stack,123,damaged=23),ActiveShipUnit(3,stack,0)))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1049,10,units=UnitInventory(mapOf(stack to 3)),activeShips=roster)
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1)),nextShipId=4)
        fun state(value:PlanetStocks)=WorldSnapshot(0,0,session,navigators=mapOf("self" to viewer),planetStocks=value)
        assertEquals(mapOf(2487 to 424),counts(state(stocks)))
        assertEquals(3,GridForces.collect(galaxy,1,listOf(viewer),stocks).getValue(2487).single().units)
        val lost=stocks.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(roster.values.map {
            if(it.id==2)it.copy(boats=100) else it
        }))))
        assertEquals(mapOf(2487 to 401),counts(state(lost)))
        assertEquals(3,GridForces.collect(galaxy,1,listOf(viewer),lost).getValue(2487).single().units)
    }
    @Test fun windowEdgesDoNotWrapAcrossGalaxyRows() {
        val edge=viewer.copy(grid=2499)
        val state=WorldSnapshot(0,0,session,navigators=mapOf("self" to edge,
            "wrapped" to viewer.copy(characterId=10001,grid=2500),
            "corner" to viewer.copy(characterId=10002,grid=1792)))
        assertEquals(mapOf(1792 to 1,2499 to 1),counts(state,edge))
    }
}
