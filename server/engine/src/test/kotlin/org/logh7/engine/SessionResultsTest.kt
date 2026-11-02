package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class SessionResultsTest {
    private val captured=SessionEnd(EndReason.CAPITAL_CAPTURED,setOf(Power.ALLIANCE),123)
    private val known=VictoryConditions(true,true)
    private fun assess(reason:SessionEnd=captured,empirePopulation:Long=90,alliancePopulation:Long=10,
        empireShips:Long=100,allianceShips:Long=10,total:Long=100,conditions:VictoryConditions=known)=
        SessionResults.assess(reason,SessionStatistics(population=total),mapOf(
            Power.EMPIRE to SessionStatistics(population=empirePopulation,shipUnits=empireShips),
            Power.ALLIANCE to SessionStatistics(population=alliancePopulation,shipUnits=allianceShips)),conditions)
    @Test fun capturedCapitalAwardsDecisiveOnlyWhenAllFourRequirementsAreKnownAndTrue() {
        assertEquals(SessionGrade.DECISIVE,assess().powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.DEFEAT,assess().powers.getValue(Power.ALLIANCE).grade)
        for(result in listOf(assess(empirePopulation=89,alliancePopulation=11),assess(empireShips=99),
            assess(conditions=VictoryConditions(false,true)),assess(conditions=VictoryConditions(true,false))))
            assertEquals(SessionGrade.LIMITED,result.powers.getValue(Power.EMPIRE).grade)
    }
    @Test fun missingPoliticalFactsDoNotSilentlyGrantDecisiveOrInventAWinningGrade() {
        assertEquals(SessionGrade.UNRESOLVED,assess(conditions=VictoryConditions()).powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.LIMITED,assess(empireShips=99,conditions=VictoryConditions()).powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.UNRESOLVED,assess(reason=captured.copy(affected=Power.entries.toSet())).powers.getValue(Power.EMPIRE).grade)
    }
    @Test fun allianceCapitalVictoryDoesNotRequireAnImperialLeader() {
        val result=assess(captured.copy(affected=setOf(Power.EMPIRE)),10,90,10,100,conditions=VictoryConditions(true,null))
        assertEquals(SessionGrade.DECISIVE,result.powers.getValue(Power.ALLIANCE).grade)
    }
    @Test fun nonCapitalEndUsesPopulationAndTiesGoToAllianceRegardlessOfReducedSide() {
        for(reason in listOf(EndReason.SYSTEMS_REDUCED,EndReason.TIME_LIMIT)) {
            val end=SessionEnd(reason,setOf(Power.EMPIRE),123)
            assertEquals(SessionGrade.LOCAL,assess(end,60,40).powers.getValue(Power.EMPIRE).grade)
            val tied=assess(end,50,50)
            assertEquals(SessionGrade.LOCAL,tied.powers.getValue(Power.ALLIANCE).grade)
            assertEquals(SessionGrade.DEFEAT,tied.powers.getValue(Power.EMPIRE).grade)
        }
    }
    @Test fun neutralPopulationCountsTowardNinetyPercentAndLargeRatiosDoNotOverflow() {
        assertEquals(SessionGrade.LIMITED,assess(total=101).powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.DECISIVE,assess(empirePopulation=Long.MAX_VALUE,alliancePopulation=0,
            total=Long.MAX_VALUE,empireShips=Long.MAX_VALUE,allianceShips=Long.MAX_VALUE/10).powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.LIMITED,assess(empireShips=Long.MAX_VALUE,allianceShips=Long.MAX_VALUE/10+1).powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.LIMITED,assess(empireShips=0,allianceShips=0).powers.getValue(Power.EMPIRE).grade)
        assertEquals(SessionGrade.DECISIVE,assess(empireShips=1,allianceShips=0).powers.getValue(Power.EMPIRE).grade)
    }
    private val galaxy=Galaxy((0..3).map {GalaxySystem(3+it,100+it,4,"S$it",if(it<2)2+it else 0)},
        (0..3).map {GalaxyBase(it+1,100+it,80,if(it==1)1 else 0,"B$it",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 100,Power.ALLIANCE to 101))
    @Test fun worldReportUsesCurrentOwnersAndEconomyIncludesOnlyLivingIdsAndDoesNotCountOutfitAggregatesTwice() {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(0),boats=1),ActiveShipUnit(2,UnitStack(0),boats=0)))
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(0),base=1,strength=1)))
        val bases=galaxy.bases.associate {base->base.id to PlanetStock(base.id,
            when(base.id){1->2;2->3;3->0;else->2},if(base.id==4)1 else 0,0,0,0,
            PlanetEconomy(base.id*10,0,50,50),if(base.id==1)UnitInventory(mapOf(UnitStack(0) to 2),mapOf(UnitStack(0) to 3),mapOf("S0" to 100)) else UnitInventory(),
            spareShips=if(base.id==1)SpareShipBoats(mapOf(UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2}.kind) to 1)) else SpareShipBoats())}
        val outfit=OutfitStock(1,2,OutfitKind.PATROL,1,10,units=UnitInventory(ships.quantities(),ground.quantities()),activeShips=ships,activeGround=ground)
        val stock=PlanetStocks(1,0,bases,mapOf(1 to outfit),mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1,
            units=UnitInventory(mapOf(UnitStack(0) to 4),mapOf(UnitStack(0) to 2)))),deploymentInitialized=true,nextShipId=4)
        val session=SessionSimulation(SessionRules.load()).snapshot().copy(ended=captured)
        val player=Navigator(1,10000,Power.EMPIRE,100,1000,1000,1000,hull=FlagshipHull(0,0))
        val snapshot=WorldSnapshot(1,0,session,navigators=mapOf("offline" to player,"legacy" to player.copy(characterId=10001,hull=null),"stale" to player.copy(generation=2)),planetStocks=stock)
        val report=SessionResults.fromWorld(galaxy,snapshot,known)
        assertEquals(SessionStatistics(1,0,10,10,6),report.powers.getValue(Power.EMPIRE).statistics)
        assertEquals(SessionStatistics(0,1,20),report.powers.getValue(Power.ALLIANCE).statistics)
        assertEquals(SessionStatistics(3,1,100,10,6),report.total)
        val capturedBase=stock.copy(bases=bases+(2 to bases.getValue(2).copy(power=2)))
        assertEquals(30,SessionResults.fromWorld(galaxy,snapshot.copy(planetStocks=capturedBase),known).powers.getValue(Power.EMPIRE).statistics.population)
        assertFailsWith<IllegalArgumentException>{SessionResults.fromWorld(galaxy,snapshot.copy(planetStocks=stock.copy(generation=2)))}
        assertFailsWith<IllegalStateException>{SessionResults.fromWorld(galaxy,snapshot.copy(session=session.copy(ended=null)))}
    }
}
