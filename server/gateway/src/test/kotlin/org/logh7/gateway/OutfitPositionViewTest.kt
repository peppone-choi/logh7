package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.OutfitMessages
import org.logh7.protocol.OutfitPartyMessages
import java.nio.ByteBuffer
import kotlin.test.*

class OutfitPositionViewTest {
    @Test fun initializationBatchesMoreThanOneHundredDeploymentsWithoutDroppingIds() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),
            (1..201).associateWith {OutfitStock(it,2,OutfitKind.PATROL,1000,10,position=OutfitPosition(2488))},
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1000)))
        val packets=WorldBootstrap(galaxy).initialize(2488,stocks=stocks).filter {
            ByteBuffer.wrap(it).getShort(4).toInt()==0x032b
        }
        assertEquals(listOf(100,100,1),packets.map {it[6].toInt() and 255})
        val ids=packets.flatMap {packet ->val bytes=ByteBuffer.wrap(packet);bytes.position(7)
            List(packet[6].toInt() and 255) {bytes.int.also {bytes.position(bytes.position()+20)}}}
        assertEquals((1..201).toList(),ids)
    }
    @Test fun directoryAndGridForceFollowTheDeploymentInsteadOfItsWarehouse() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val stocks=ActiveShips.initialize(PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(UnitStack(0) to 10)),
                position=OutfitPosition(2488))),mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1))))
        val world=WorldBootstrap(galaxy)
        assertEquals(0,ByteBuffer.wrap(world.gridOutfits(OutfitMessages.GridQuery(2487,2,0),stocks)).getShort(6).toInt())
        assertEquals(1,ByteBuffer.wrap(world.gridOutfits(OutfitMessages.GridQuery(2488,2,0),stocks)).getShort(6).toInt())
        val initialized=ByteBuffer.wrap(world.initialize(2488,stocks=stocks).first())
        assertEquals(0x032b,initialized.getShort(4).toInt())
        assertEquals(1,initialized.get(6).toInt());assertEquals(1,initialized.getInt(7))
        assertEquals(0,world.initialize(2487,stocks=stocks).first()[6].toInt())
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val forces=GridForces.collect(galaxy,1,listOf(player),stocks)
        assertEquals(1L,forces.getValue(2487).single().units)
        assertEquals(10L,forces.getValue(2488).single().units)
        val unit=ByteBuffer.wrap(world.outfitUnit(1,2,stocks))
        // Header, count, native unit ID/kind then the actual grid/outfit/base.
        assertEquals(0x0331,unit.getShort(4).toInt())
        assertEquals(5,unit.get(13).toInt());assertEquals(2488,unit.getInt(14))
        assertEquals(1,unit.getInt(18));assertEquals(0,unit.getInt(27))
        val party=ByteBuffer.wrap(world.outfitParty(OutfitPartyMessages.Query(1,0,0),2,stocks))
        assertEquals(1,party.getInt(6));assertEquals(0,party.getInt(10))
    }
}
