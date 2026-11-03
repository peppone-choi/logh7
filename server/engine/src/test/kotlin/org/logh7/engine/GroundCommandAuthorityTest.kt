package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class GroundCommandAuthorityTest {
    private val base=GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private val troop=ActiveGroundUnit(1,UnitStack(0),base=1000)
    private val outfit=OutfitStock(1,2,OutfitKind.GROUND,1000,1,
        units=UnitInventory(troops=mapOf(UnitStack(0) to 1)),activeGround=ActiveGroundUnits(listOf(troop)))
    private val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0,garrisonCommanderId=10000)),
        outfits=mapOf(1 to outfit),warehouses=mapOf(1 to OutfitWarehouse(1,2,OutfitKind.GROUND,1000)),nextShipId=2)
    @Test fun localUnassignedDefendersFollowTheirExistingGarrisonButAnOutfitCommanderKeepsCommand() {
        assertEquals(10000,GroundCommandAuthority.commander(outfit,troop,stocks,galaxy))
        assertEquals(10001,GroundCommandAuthority.commander(outfit.copy(commanderId=10001),troop,stocks,galaxy))
        assertEquals(0,GroundCommandAuthority.commander(outfit,troop.copy(origin=9),stocks,galaxy))
        assertEquals(0,GroundCommandAuthority.commander(outfit,troop,stocks.copy(bases=mapOf(1000 to stocks.bases.getValue(1000).copy(power=3))),galaxy))
        assertEquals(0,GroundCommandAuthority.commander(outfit,troop,stocks.copy(bases=mapOf(1000 to stocks.bases.getValue(1000).copy(camp=1,garrisonCommanderId=0))),galaxy))
        assertEquals(0,GroundCommandAuthority.commander(outfit,troop,stocks.copy(bases=mapOf(1000 to stocks.bases.getValue(1000).copy(garrisonCommanderId=0))),galaxy))
    }
}
