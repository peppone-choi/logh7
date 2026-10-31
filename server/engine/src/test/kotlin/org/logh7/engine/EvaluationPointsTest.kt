package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Evaluation-point sources: sunk ships, flagship, occupation shares. Author: Peppone Choi. */
class EvaluationPointsTest {
    private fun stocks(people:Map<Int,StrategicPerson> = emptyMap())=PlanetStocks(1,0,emptyMap(),
        strategic=StrategicState(people=people))

    @Test fun sunkShipsRoundUpPerTenAndNothingForNoLoss() {
        assertEquals(0,EvaluationPoints.sunk(0));assertEquals(0,EvaluationPoints.sunk(-3))
        assertEquals(1,EvaluationPoints.sunk(1));assertEquals(1,EvaluationPoints.sunk(10))
        assertEquals(2,EvaluationPoints.sunk(11));assertEquals(30,EvaluationPoints.sunk(300))
    }
    @Test fun awardAddsToTheStrategicRecordAndSaturates() {
        val first=EvaluationPoints.award(stocks(),10000,7)
        assertEquals(7,first.strategic.people.getValue(10000).evaluation)
        val kept=EvaluationPoints.award(stocks(mapOf(10000 to StrategicPerson(rank=2,merit=5,evaluation=3))),10000,4)
        assertEquals(StrategicPerson(rank=2,merit=5,evaluation=7),kept.strategic.people.getValue(10000))
        val full=stocks(mapOf(10000 to StrategicPerson(evaluation=0xffffffffL-1)))
        assertEquals(0xffffffffL,EvaluationPoints.award(full,10000,50).strategic.people.getValue(10000).evaluation)
        val none=stocks()
        assertSame(none,EvaluationPoints.award(none,0,10));assertSame(none,EvaluationPoints.award(none,10000,0))
    }
    @Test fun occupationSharesSumExactlyAndIgnoreMissingCommanders() {
        assertEquals(mapOf(10000 to 34L,10001 to 33L,10002 to 33L),EvaluationPoints.shares(100,listOf(10002,0,10000,10001,10000)))
        assertEquals(mapOf(10000 to 300L),EvaluationPoints.shares(300,listOf(10000)))
        assertTrue(EvaluationPoints.shares(100,listOf(0)).isEmpty())
    }
    @Test fun shooterIsTheFlagshipOwnerOrTheUnitsTacticalCommander() {
        val kind=UnitSpecifications.ships.first().kind
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(5,UnitStack(kind)),ActiveShipUnit(6,UnitStack(kind))))
        val outfit=OutfitStock(200,2,OutfitKind.PATROL,1049,300,10002,UnitInventory(ships=ships.quantities()),activeShips=ships)
        val base=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),outfits=mapOf(200 to outfit),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.PATROL,1049)),nextShipId=7)
        val reassigned=base.copy(tacticalCommands=base.tacticalCommands.copy(authority=mapOf(6 to 10003)))
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        assertEquals(10000,EvaluationPoints.shooter(player.unitId,mapOf("a" to player),reassigned))
        assertEquals(10002,EvaluationPoints.shooter(5,emptyMap(),reassigned))
        assertEquals(10003,EvaluationPoints.shooter(6,emptyMap(),reassigned))
        assertEquals(0,EvaluationPoints.shooter(99,emptyMap(),reassigned))
    }
    @Test fun aStrikeThatSinksShipsPaysTheShootingFlagshipOwnerInTheSameStocks() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        val session=SessionSimulation(SessionRules.load()).also {it.join("a",Power.EMPIRE,false,"c")}.exportState()
        val spec=UnitSpecifications.ships.single {it.kind==119}
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(5,UnitStack(119))))
        val enemy=OutfitStock(200,3,OutfitKind.PATROL,1049,300,0,UnitInventory(ships=ships.quantities()),activeShips=ships)
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,3,0,100,200,300)),outfits=mapOf(200 to enemy),
            warehouses=mapOf(300 to OutfitWarehouse(300,3,OutfitKind.PATROL,1049)),nextShipId=6)
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val players=mutableMapOf("a" to player)
        val mount=TacticalArmaments.mount(UnitSpecifications.ships.single {it.kind==32},TacticalWeapon.GUN)
        val (after,_)=TacticalCombat.strike(session,galaxy,players,stocks,player.unitId,2487,1,0,5,TacticalWeapon.GUN,
            mount,100,TacticalPose(TacticalPoint(0f,0f),0f),TacticalCombat.Target(regular=enemy to ships.values.single(),spec=spec))
        val lost=300-after.outfits.getValue(200).activeShips!!.values.single().boats
        assertTrue(lost>0)
        assertEquals(EvaluationPoints.sunk(lost),after.strategic.people.getValue(10000).evaluation)
    }
}
