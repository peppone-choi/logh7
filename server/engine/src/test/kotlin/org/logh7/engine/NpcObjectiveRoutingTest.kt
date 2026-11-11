package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcObjectiveRoutingTest {
    private val session=SessionSimulation(SessionRules.load()).exportState()
    private val empire=Power.EMPIRE.country
    private val alliance=Power.ALLIANCE.country
    private val ship=ActiveShipUnit(1,UnitStack(32))
    private val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),carrier=1)))
    private fun map(origin:Int,target:Int,terrain:List<String>?=null,stage:Int?=null,sargasso:Set<Int> = emptySet()):Galaxy {
        val grids=listOfNotNull(origin,target,stage)
        return Galaxy(grids.mapIndexed {i,grid->GalaxySystem(i+3,grid,4,"System$i",if(i==1)3 else 2)},
            grids.mapIndexed {i,grid->GalaxyBase(1000+i,grid,80,0,"Base$i",0,20f,86400,0,0f,2f)},
            mapOf(Power.EMPIRE to origin,Power.ALLIANCE to target),terrain=terrain,sargassoCells=sargasso)
    }
    private fun stock(galaxy:Galaxy,supplies:Int):PlanetStocks {
        val fleet=OutfitStock(1,2,OutfitKind.FLEET,1000,10,
            units=UnitInventory(mapOf(ship.stack to 1),ground.quantities()),activeShips=ActiveShipUnits(listOf(ship)),
            activeGround=ground,position=OutfitPosition(galaxy.starts.getValue(Power.EMPIRE),1000))
        val bases=galaxy.bases.associate {base->
            val owner=if(base.id==1001)alliance else empire
            base.id to PlanetStock(base.id,Allegiance.Country(owner),0,when(base.id){1000->supplies;1002->100000;else->0},0,0)
        }
        return PlanetStocks(1,0,bases,outfits=mapOf(1 to fleet),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=3,
            social=SocialState(goals=GoalState(national=mapOf(2 to NationalGoal(1001,10000,0)))))
    }
    private fun advance(at:Long,galaxy:Galaxy,stocks:PlanetStocks,events:MutableList<NpcStrategicMovement>)=
        NpcStrategy.advance(session.copy(gameMillis=at*1000),galaxy,mutableMapOf(),stocks,events)
    private fun fleet(stocks:PlanetStocks)=stocks.outfits.getValue(1)

    @Test fun reservesARealDetourAndReplansBeforeDebitWhenTheFirstHopCloses() {
        val terrain=List(50){y->CharArray(100){x->if(x==88 && y in 23..25)'~' else '.'}.concatToString()}
        val world=map(2487,2489,terrain)
        val original=stock(world,100000)
        val moves=mutableListOf<NpcStrategicMovement>()
        val planned=advance(0,world,original,moves)
        assertEquals(2387,fleet(planned).strategy!!.destination)
        val closed=map(2487,2489,terrain,sargasso=setOf(2387))
        val changed=advance(3600,closed,planned,moves)
        assertEquals(2587,fleet(changed).strategy!!.destination)
        assertEquals(7200L,fleet(changed).strategy!!.arrivesAt)
        assertEquals(original.bases,changed.bases)
        assertTrue(moves.isEmpty())
        val departed=advance(7200,closed,changed,moves)
        assertEquals(2587,moves.single().destination)
        assertEquals(2700,fleet(departed).supplies)
        assertEquals(97000,departed.bases.getValue(1000).supplies)
    }

    @Test fun fullManualCorridorCanCarryInfantryFromIserlohnToHeinessenAndFundTheReturn() {
        val world=map(1253,2015,Galaxy.manualTerrain())
        val original=stock(world,100000)
        val moves=mutableListOf<NpcStrategicMovement>()
        var inventory=original
        var at=0L
        repeat(38) {
            inventory=advance(at,world,inventory,moves)
            val queued=assertNotNull(fleet(inventory).strategy?.arrivesAt)
            inventory=advance(queued,world,inventory,moves)
            at=assertNotNull(fleet(inventory).strategy).nextDecisionAt
        }
        assertEquals(38,moves.size)
        assertEquals(1253,moves.first().source)
        assertEquals(2015,moves.last().destination)
        assertTrue(moves.all {world.terrain(it.destination)==GridTerrain.NONE && world.kind(it.destination)!=GridKind.IMPASSABLE})
        assertEquals(ground,fleet(inventory).activeGround)
        assertEquals(38*ship.boats,fleet(inventory).supplies)
        assertEquals(100000-76*ship.boats,inventory.bases.getValue(1000).supplies)
        assertEquals(OutfitPosition(2015),fleet(inventory).position)
    }

    @Test fun insufficientDirectRangeUsesAForwardDepotWithActualFuel() {
        val world=map(2487,2475,stage=2484)
        val initial=stock(world,1500)
        val moves=mutableListOf<NpcStrategicMovement>()
        var inventory=initial
        var at=0L
        repeat(3) {
            inventory=advance(at,world,inventory,moves)
            inventory=advance(assertNotNull(fleet(inventory).strategy?.arrivesAt),world,inventory,moves)
            at=fleet(inventory).strategy!!.nextDecisionAt
        }
        assertEquals(OutfitPosition(2484,1002),fleet(inventory).position)
        assertEquals(0,inventory.bases.getValue(1000).supplies)
        val prepared=advance(at,world,inventory,moves)
        val away=advance(assertNotNull(fleet(prepared).strategy?.arrivesAt),world,prepared,moves)
        assertTrue(fleet(away).supplies>=17*ship.boats)
        assertEquals(ground,fleet(away).activeGround)
    }

    @Test fun emptyCarrierReturnsFromForwardDepotAndLoadsNewInfantryBeforeRetryingTheObjective() {
        val world=map(2487,2484,stage=2485)
        val initial=stock(world,100000)
        val empty=fleet(initial).copy(position=OutfitPosition(2485,1002),
            activeGround=ActiveGroundUnits(emptyList()),units=UnitInventory(ships=mapOf(ship.stack to 1)))
        var inventory=initial.copy(outfits=mapOf(1 to empty))
        val moves=mutableListOf<NpcStrategicMovement>()
        var at=0L
        repeat(2) {
            inventory=advance(at,world,inventory,moves)
            inventory=advance(assertNotNull(fleet(inventory).strategy?.arrivesAt),world,inventory,moves)
            at=fleet(inventory).strategy!!.nextDecisionAt
        }
        assertEquals(listOf(2386,2487),moves.map {it.destination})
        assertEquals(OutfitPosition(2487,1000),fleet(inventory).position)
        val waiting=advance(at,world,inventory,moves)
        assertNull(fleet(waiting).strategy?.destination)
        assertTrue(fleet(waiting).activeGround!!.values.isEmpty())

        val home=waiting.bases.getValue(1000).copy(units=UnitInventory(troops=mapOf(UnitStack(0) to 1)))
        val resupplied=waiting.copy(bases=waiting.bases+(1000 to home))
        val rearmed=advance(assertNotNull(fleet(waiting).strategy).nextDecisionAt,world,resupplied,moves)
        val replacement=assertNotNull(fleet(rearmed).activeGround).values.single()
        assertEquals(3,replacement.id)
        assertEquals(ship.id,replacement.carrier)
        assertTrue(rearmed.bases.getValue(1000).units.troops.isEmpty())
        assertNotNull(fleet(rearmed).strategy?.destination)
    }
}
