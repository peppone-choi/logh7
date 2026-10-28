package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class TacticalPostureBootstrapTest {
    @Test fun storedCurrentPostureIsProjectedForBothShipSourcesWithoutApplyingAPendingTarget() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val person=CharacterMessages.Generate(0,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)
        val pending=TacticalPostureOrder(10000,TacticalShipMode.STATIONED,1049,100,340)
        for(mode in TacticalShipMode.entries) {
            val posture=TacticalPosture(2487,mode,if(mode.requiresBase)1049 else 0,pending)
            val viewer=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,posture=posture)
            val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),posture=posture)))
            val outfit=OutfitStock(1,2,OutfitKind.PATROL,1049,10,10000,UnitInventory(ships=ships.quantities()),
                activeShips=ships,position=OutfitPosition(2487))
            val stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),mapOf(1 to outfit),
                mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1049)),nextShipId=2)
            val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,200,0,0,emptyMap(),1,1,null),
                navigators=mapOf("pilot" to viewer),planetStocks=stock)
            val bootstrap=TacticalBootstrap(snapshot,galaxy,viewer){if(it==10000)person else null}
            assertEquals(setOf(1,1010000),bootstrap.units.map {it.id}.toSet())
            assertTrue(bootstrap.units.all {it.mode==mode.wire && it.base==posture.base})
            // A stale grid-scoped record cannot anchor a ship to a base from its previous grid.
            val stale=posture.copy(grid=2488)
            val otherViewer=viewer.copy(posture=stale)
            val otherStock=stock.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(
                ships.values.map {it.copy(posture=stale)}))))
            val other=TacticalBootstrap(snapshot.copy(navigators=mapOf("pilot" to otherViewer),planetStocks=otherStock),
                galaxy,otherViewer){person}
            assertTrue(other.units.all {it.mode==6 && it.base==0})
        }
    }
}
