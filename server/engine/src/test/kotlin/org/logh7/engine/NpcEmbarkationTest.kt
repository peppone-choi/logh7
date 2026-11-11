package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcEmbarkationTest {
    private val home=GalaxyBase(1049,417,80,0,"\u30aa\u30fc\u30c7\u30a3\u30f3",0,20f,86400,0,0f,2f)
    private val goal=GalaxyBase(1050,418,80,1,"Front",0,0f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,417,4,"\u30f4\u30a1\u30eb\u30cf\u30e9",2),GalaxySystem(4,418,4,"Front",3)),
        listOf(home,goal),mapOf(Power.EMPIRE to home.grid,Power.ALLIANCE to goal.grid))
    private val session=SessionSimulation(SessionRules.load()).exportState()
    private val infantry=UnitStack(0)
    private val crew=UnitStack(3)
    private val tank=UnitStack(2)
    private val day=86400L
    private fun time(at:Long)=session.copy(gameMillis=at*1000)
    private fun stocks():PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        val ship=OutfitStock(1,2,OutfitKind.FLEET,home.id,10,
            units=UnitInventory(ships.quantities(),mapOf(crew to 5)),activeShips=ships)
        val before=ProductionUnits.definitions.single {it.key=="T0"}.hours-24
        val base=PlanetStock(home.id,2,0,100000,100000,100000).copy(economy=PlanetEconomy(100000,80000,90,90),
            units=UnitInventory(work=mapOf("T0" to before)))
        return PlanetStocks(1,0,mapOf(home.id to base,goal.id to PlanetStock(goal.id,3,0,0,0,0)),
            outfits=mapOf(1 to ship),warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,home.id,1)),
            deploymentInitialized=true,nextShipId=2,
            social=SocialState(goals=GoalState(national=mapOf(2 to NationalGoal(goal.id,10000,0)))))
    }
    private fun troops(stocks:PlanetStocks)=stocks.outfits.getValue(1).activeGround?.values.orEmpty()

    @Test fun newlyProducedTroopsBoardOnceAndTravelOnTheSameShipToTheObjective() {
        val initial=stocks()
        assertTrue(initial.bases.getValue(home.id).units.troops.isEmpty())
        val produced=PlanetProduction(galaxy,null,0,PlanetProductionPolicy(fleetUpkeepPerShip=0),restored=initial).settle(1,day)
        val originalCount=assertNotNull(produced.bases.getValue(home.id).units.troops[infantry])
        assertTrue(originalCount>=2)
        val events=mutableListOf<NpcStrategicMovement>()
        val loaded=NpcStrategy.advance(time(day),galaxy,mutableMapOf(),produced,events)
        assertEquals(listOf(2,3),troops(loaded).map {it.id})
        assertTrue(troops(loaded).all {it.carrier==1 && it.stack==infantry})
        assertEquals(originalCount-2,loaded.bases.getValue(home.id).units.troops[infantry] ?: 0)
        assertEquals(produced.warehouses,loaded.warehouses)
        assertEquals(produced.outfits.getValue(1).activeShips,loaded.outfits.getValue(1).activeShips)
        assertEquals(goal.grid,loaded.outfits.getValue(1).strategy!!.destination)
        assertEquals(loaded,NpcStrategy.advance(time(day),galaxy,mutableMapOf(),loaded,events))
        val arrived=NpcStrategy.advance(time(day+3600),galaxy,mutableMapOf(),loaded,events)
        assertEquals(goal.grid,events.single().destination)
        assertEquals(OutfitPosition(goal.grid),arrived.outfits.getValue(1).position)
        assertEquals(troops(loaded),troops(arrived))
        assertEquals(loaded.nextShipId,arrived.nextShipId)
    }

    @Test fun reservationsAndMilitaryOwnershipKeepStocksIntactAndWarehouseTakesPriority() {
        val initial=stocks()
        val source=initial.bases.getValue(home.id).copy(units=UnitInventory(troops=mapOf(infantry to 4,tank to 3)))
        val before=initial.copy(bases=initial.bases+(home.id to source))
        val locked=NpcStrategy.advance(time(day),galaxy,mutableMapOf(),before,mutableListOf(),warehouseBlocked={_,_->true})
        assertTrue(troops(locked).isEmpty())
        assertEquals(before.bases,locked.bases)
        val outfit=before.outfits.getValue(1)
        assertEquals(before,NpcOccupation.loadAtHome(outfit,goal,before) {false})
        val veteran=infantry.copy(level=1)
        val reserve=before.warehouses.getValue(10).copy(units=UnitInventory(troops=mapOf(veteran to 1,tank to 1,crew to 1)))
        val supplied=before.copy(warehouses=mapOf(10 to reserve))
        val loaded=NpcOccupation.loadAtHome(outfit,goal,supplied) {false}
        assertEquals(listOf(ActiveGroundUnit(2,veteran,carrier=1)),troops(loaded))
        assertEquals(mapOf(tank to 1,crew to 1),loaded.warehouses.getValue(10).units.troops)
        assertEquals(before.bases,loaded.bases)
        assertEquals(loaded,NpcOccupation.loadAtHome(loaded.outfits.getValue(1),goal,loaded) {true})
    }
}
