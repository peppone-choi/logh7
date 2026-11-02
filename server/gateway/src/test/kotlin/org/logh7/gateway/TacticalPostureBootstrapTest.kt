package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class TacticalPostureBootstrapTest {
    @Test fun anchoredReconnectProjectsBothSourcesAndDoesNotReplayAnObsoleteMotionOrigin() {
        val base=GalaxyBase(1049,2487,60,0,"Base",0,20f,1000,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Base",2)),listOf(base),Power.entries.associateWith {2487})
        val person=CharacterMessages.Generate(0,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)
        val posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1049)
        val origin=TacticalPose(TacticalPoint(2f,23f),.4f)
        val motion=TacticalMotion(2487,0,origin,0f,.4f,listOf(origin.position))
        val viewer=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,posture=posture,motion=motion)
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),posture=posture,tactical=NpcTacticalState(motion,0,0))))
        val outfit=OutfitStock(1,2,OutfitKind.PATROL,1049,10,10000,UnitInventory(ships=ships.quantities()),
            activeShips=ships,position=OutfitPosition(2487))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1049)),nextShipId=2)
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),
            navigators=mapOf("pilot" to viewer),planetStocks=stocks)
        val bootstrap=TacticalBootstrap(snapshot,galaxy,viewer){person}
        assertEquals(2,bootstrap.units.size)
        for(unit in bootstrap.units) {assertEquals(22f,unit.x,.00001f);assertEquals(3f,unit.y,.00001f)}
        assertTrue(bootstrap.resume().none {java.nio.ByteBuffer.wrap(it).getShort(4).toInt()==0x0400})
    }
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
            val basePacket=bootstrap.initialize().single {java.nio.ByteBuffer.wrap(it).getShort(4).toInt()==0x0345}
            assertEquals(1,basePacket[6].toInt());assertEquals(1049,java.nio.ByteBuffer.wrap(basePacket).getInt(7))
            assertEquals(setOf(1,1010000),bootstrap.units.map {it.id}.toSet())
            assertTrue(bootstrap.units.all {it.mode==mode.wire && it.base==posture.base})
            if(mode.requiresBase) {
                val berth=TacticalBasePosition.at(galaxy.bases.single(),snapshot.session!!.gameSeconds,stock)
                assertTrue(bootstrap.units.all {it.x==berth.x && it.y==berth.y})
            }
            val replay=bootstrap.resume().single {java.nio.ByteBuffer.wrap(it).getShort(4).toInt()==0x0411}
            assertEquals(140,java.nio.ByteBuffer.wrap(replay).getInt(10))
            val restored=TacticalGroundMessages.posture(replay.copyOfRange(4,replay.size))
            assertEquals(setOf(1010000,1),restored.units.toSet());assertEquals(4,restored.kind);assertEquals(1049,restored.base)
            if(mode==TacticalShipMode.STATIONED) {
                val leaving=posture.copy(order=pending.copy(mode=TacticalShipMode.ANCHORED))
                val leavingStock=stock.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(
                    ships.values.map {it.copy(posture=leaving)}))))
                val source=viewer.copy(posture=null)
                val replaySortie=TacticalBootstrap(snapshot.copy(navigators=mapOf("pilot" to source),planetStocks=leavingStock),
                    galaxy,source){person}.resume().single {java.nio.ByteBuffer.wrap(it).getShort(4).toInt()==0x0412}
                assertEquals(140,java.nio.ByteBuffer.wrap(replaySortie).getInt(10))
                assertEquals(TacticalGroundMessages.Command(TacticalGroundMessages.Action.SORTIE,10000,listOf(1)),
                    TacticalGroundMessages.command(replaySortie.copyOfRange(4,replaySortie.size)))
            }
            // A stale grid-scoped record cannot anchor a ship to a base from its previous grid.
            val stale=posture.copy(grid=2488)
            val otherViewer=viewer.copy(posture=stale)
            val otherStock=stock.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(
                ships.values.map {it.copy(posture=stale)}))))
            val other=TacticalBootstrap(snapshot.copy(navigators=mapOf("pilot" to otherViewer),planetStocks=otherStock),
                galaxy,otherViewer){person}
            assertTrue(other.units.all {it.mode==6 && it.base==0})
            assertTrue(other.resume().none {java.nio.ByteBuffer.wrap(it).getShort(4).toInt()==0x0411})
        }
    }
}
