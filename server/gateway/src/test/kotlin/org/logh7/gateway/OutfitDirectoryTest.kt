package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import java.nio.ByteBuffer
import kotlin.test.*

class OutfitDirectoryTest {
    @Test fun partyPublishesCarriedPlusFreeCapacityAndNeverChangesTheStoredCargo() {
        val original=stocks.outfits.getValue(10).copy(units=UnitInventory(mapOf(UnitStack(32) to 2)),supplies=100000,activeShips=null)
        val state=ActiveShips.initialize(stocks.copy(outfits=stocks.outfits+(10 to original)))
        val packet=WorldBootstrap(galaxy).outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(10,1049,0),2,state)
        val input=ByteBuffer.wrap(packet);input.position(packet.size-17)
        assertEquals(100000,input.int);assertEquals(356000,input.int)
        assertEquals(100000,state.outfits.getValue(10).supplies)
        val overloaded=state.copy(outfits=state.outfits+(10 to state.outfits.getValue(10).copy(supplies=456001)))
        assertFails { WorldBootstrap(galaxy).outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(10,1049,0),2,overloaded) }
        assertEquals(456001,overloaded.outfits.getValue(10).supplies)
    }
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2563,4,"Other",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f),
            GalaxyBase(1050,2563,80,0,"Other",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2563))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0),1050 to PlanetStock(1050,2,0,0,0,0)),
        mapOf(20 to OutfitStock(20,2,OutfitKind.PATROL,1049,101,number=2),
            10 to OutfitStock(10,2,OutfitKind.PATROL,1049,101,number=1),
            30 to OutfitStock(30,2,OutfitKind.PATROL,1050,102,number=3)),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1049),102 to OutfitWarehouse(102,2,OutfitKind.PATROL,1050)),
        deploymentInitialized=true)
    private fun definitions(grid: Int): List<Int> {
        val packets=WorldBootstrap(galaxy).initialize(grid,stocks=stocks)
        assertEquals(listOf(0x032b,0x031f,0x0321),packets.map { ByteBuffer.wrap(it).getShort(4).toInt() })
        val input=ByteBuffer.wrap(packets.first()); input.position(6)
        val ids=List(input.get().toInt() and 255) {
            val id=input.int
            assertEquals(2,input.get().toInt()); assertEquals(2,input.get().toInt())
            input.position(input.position()+18); id
        }
        assertFalse(input.hasRemaining())
        return ids
    }
    @Test fun initializationLoadsOnlyCurrentGridDefinitionsInDeterministicOrder() {
        assertEquals(listOf(10,20),definitions(2487))
        assertEquals(listOf(30),definitions(2563))
        assertEquals(emptyList(),definitions(0))
    }
    @Test fun legacyInitializationWithoutStockStorageKeepsExistingResponseShape() {
        assertEquals(listOf(0x031f,0x0321),WorldBootstrap(galaxy).initialize(2487)
            .map { ByteBuffer.wrap(it).getShort(4).toInt() })
    }
}
