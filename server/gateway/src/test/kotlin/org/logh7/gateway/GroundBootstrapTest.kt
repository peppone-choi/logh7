package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

class GroundBootstrapTest {
    @Test fun onBaseTroopsUseTheirOwnGridWithoutInventingAShipStateOrCarrier() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2),GalaxySystem(4,2488,4,"Away",2)),
            listOf(GalaxyBase(1049,2487,80,0,"Here",0,20f,86400,0,0f,2f),
                GalaxyBase(1050,2488,80,0,"Away",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val person=CharacterMessages.Generate(0,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)
        val viewer=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true)
        val stack=UnitStack(0)
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1049,10,10000,
            units=UnitInventory(troops=mapOf(stack to 3)),position=OutfitPosition(2488),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(1,stack,base=1049),
                ActiveGroundUnit(2,stack,base=1050),ActiveGroundUnit(3,stack))))
        val stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,0,0,0,0)},
            mapOf(1 to outfit),mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1)),nextShipId=4)
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),
            navigators=mapOf("pilot" to viewer),planetStocks=stocks)
        val bootstrap=TacticalBootstrap(snapshot,galaxy,viewer){person}
        assertEquals(listOf(TacticsMessages.GroundDefinition(1,1000,1,1049)),bootstrap.groundDefinitions)
        assertEquals(listOf(1010000),bootstrap.units.map {it.id})
        val messages=bootstrap.initialize().associateBy {ByteBuffer.wrap(it).getShort(4).toInt()}
        val definitions=ByteBuffer.wrap(messages.getValue(0x0325))
        assertEquals(2,definitions.getShort(6).toInt());assertEquals(1,definitions.getInt(50))
        assertEquals(1049,definitions.getInt(70));assertEquals(0,definitions.getInt(65))
        assertEquals(1000,ByteBuffer.wrap(messages.getValue(0x0345)).getShort(33).toInt())
        assertEquals(1000,bootstrap.baseInformation.single().stamina)
        val states=ByteBuffer.wrap(messages.getValue(0x033b))
        assertEquals(1,states.getShort(6).toInt());assertEquals(1010000,states.getInt(8))
        assertEquals(listOf(2),TacticalBootstrap(snapshot,galaxy,viewer.copy(grid=2488)){person}.groundDefinitions.map {it.id})
    }
}
