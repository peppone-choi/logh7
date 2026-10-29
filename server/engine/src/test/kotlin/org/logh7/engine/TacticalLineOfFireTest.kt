package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalLineOfFireTest {
    private val base=GalaxyBase(1000,2487,80,1,"Base",0,20f,1000,0,90f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private fun state(power:Int):PlanetStocks {
        val kind=if(power==2)32 else 119
        val point=TacticalPoint(0f,2f)
        val motion=TacticalMotion(2487,100,TacticalPose(point,0f),0f,0f,listOf(point))
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(2,UnitStack(kind),tactical=NpcTacticalState(motion,100,100))))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),
            mapOf(1 to OutfitStock(1,power,OutfitKind.PATROL,1000,10,units=UnitInventory(ships.quantities()),activeShips=ships,position=OutfitPosition(2487))),
            mapOf(10 to OutfitWarehouse(10,power,OutfitKind.PATROL,1000)),nextShipId=3)
    }
    @Test fun friendlyHullBlocksAConventionalShotAndEnemyHullDoesNot() {
        assertTrue(TacticalLineOfFire.blocked(1,2487,Power.EMPIRE,TacticalPoint(0f,0f),TacticalPoint(0f,4f),100,emptyList(),state(2),galaxy))
        assertFalse(TacticalLineOfFire.blocked(1,2487,Power.EMPIRE,TacticalPoint(0f,0f),TacticalPoint(0f,4f),100,emptyList(),state(3),galaxy))
        assertFalse(TacticalLineOfFire.blocked(2,2487,Power.EMPIRE,TacticalPoint(0f,0f),TacticalPoint(0f,4f),100,emptyList(),state(2),galaxy))
    }
    @Test fun mobileFortressUsesItsSavedPositionForBlocking() {
        val before=state(3);val point=TacticalPoint(0f,3f)
        val motion=TacticalMotion(2487,100,TacticalPose(point,0f),0f,0f,listOf(point))
        val moved=before.copy(tacticalCommands=TacticalCommandState(fortresses=mapOf(1000 to TacticalFortressMotion(2487,motion))))
        assertFalse(TacticalLineOfFire.blocked(1,2487,Power.EMPIRE,TacticalPoint(0f,0f),TacticalPoint(0f,5f),100,emptyList(),before,galaxy))
        assertTrue(TacticalLineOfFire.blocked(1,2487,Power.EMPIRE,TacticalPoint(0f,0f),TacticalPoint(0f,5f),100,emptyList(),moved,galaxy))
    }
}
