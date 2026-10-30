package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 실제 계획·할당·기간·소유권·보상·철회 저장 상태. 작성: 최병호. 실행은 통합 담당. */
class StrategicOperationsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2500,4,"Enemy",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(2050,2500,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2500))
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("actor",Power.EMPIRE,false,"c");it.join("issuer",Power.EMPIRE,false,"i")}.exportState()
    private val now=session.gameMillis/1000
    private val player=Navigator(1,10000,Power.EMPIRE,2487,2000,2000,1000)
    private val issuer=player.copy(characterId=10001)
    private fun stocks():PlanetStocks {
        val stack=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2}.kind)
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,stack)))
        val initial=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),2050 to PlanetStock(2050,3,0,100,200,300)),
            mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,10002,UnitInventory(ships=ships.quantities()),activeShips=ships)),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),nextShipId=2)
        val policy=OrganizationalOfficePolicy(galaxy)
        return policy.set(policy.set(initial,OfficeHolding(24,1049,10000)),OfficeHolding(28,1049,10001))
    }
    private fun request(command:Int)=if(command==24)StrategyActionRequest("issuer","i",10001,Power.EMPIRE,1,24,28,2,target=2500,base=200)
        else StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,command,24,4,target=2500,amount=if(command==22)1 else 0)
    private fun act(command:Int,stocks:PlanetStocks,s:SessionState=session,r:StrategyActionRequest=request(command))=
        StrategyActions.evaluate(galaxy,r,s,if(command==24)issuer else player,stocks)
    @Test fun occupationPlanAssignsActualFleetAndSettlesExactlyOnceAfterThirtyDays() {
        val planned=act(22,stocks());assertNull(planned.rejection);assertEquals(720,planned.player?.mcp)
        val stored=assertNotNull(planned.stocks);assertEquals(StrategicPlanState.PLANNED,stored.strategic.operations.single().state)
        val issued=act(24,stored);assertNull(issued.rejection);assertEquals(1999,issued.player?.mcp)
        val assigned=assertNotNull(issued.stocks);assertEquals(setOf(200),assigned.strategic.operations.single().outfits)
        val reached=assigned.copy(outfits=assigned.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2500))})
        val started=StrategicOperations.settle(galaxy,reached,now+1)
        assertEquals(StrategicPlanState.STARTED,started.strategic.operations.single().state)
        val captured=started.copy(bases=started.bases+(2050 to started.bases.getValue(2050).copy(power=2)))
        assertTrue(StrategicOperations.settle(galaxy,captured,now+OperationPlanRules.DURATION_GAME_SECONDS-1).strategic.operations.single().live)
        val ended=StrategicOperations.settle(galaxy,captured,now+OperationPlanRules.DURATION_GAME_SECONDS)
        assertEquals(OperationOutcome.FULL_SUCCESS,ended.strategic.operations.single().outcome)
        for(id in listOf(10000,10001,10002))assertEquals(100L,ended.strategic.people.getValue(id).merit)
        assertEquals(ended,StrategicOperations.settle(galaxy,ended,now+OperationPlanRules.DURATION_GAME_SECONDS+1))
    }
    @Test fun plannerAndIssuerAreDistinctStoredOfficesAndActivationIsEnforced() {
        val delayed=act(22,stocks(),r=request(22).copy(argument=1));assertNull(delayed.rejection)
        val stored=assertNotNull(delayed.stocks)
        assertEquals(StrategyActionRejection.BUSY,act(24,stored).rejection)
        assertEquals(StrategyActionRejection.AUTHORITY,act(24,stored,r=request(24).copy(card=24)).rejection)
        val without=stored.copy(strategic=stored.strategic.copy(offices=emptyList()))
        assertEquals(StrategyActionRejection.AUTHORITY,act(24,without).rejection)
        val due=session.copy(gameMillis=session.gameMillis+86400_000L)
        assertNull(act(24,stored,due).rejection)
    }
    @Test fun duplicateTargetsAndUnitOvercommitAreRejectedWithoutPaymentAndWithdrawalReleasesThePlan() {
        val stored=assertNotNull(act(22,stocks()).stocks)
        val duplicate=act(22,stored);assertEquals(StrategyActionRejection.MAXIMUM,duplicate.rejection);assertEquals(player,duplicate.player)
        val excess=act(22,stocks(),r=request(22).copy(amount=2))
        assertEquals(StrategyActionRejection.MAXIMUM,excess.rejection);assertEquals(player,excess.player)
        val withdrawn=act(23,stored);assertNull(withdrawn.rejection)
        assertEquals(StrategicPlanState.WITHDRAWN,withdrawn.stocks?.strategic?.operations?.single()?.state)
        assertNull(act(22,assertNotNull(withdrawn.stocks)).rejection)
    }
    @Test fun defenseTargetLostToTheOtherFactionWithdrawsImmediately() {
        val q=request(22).copy(target=2487,value=StrategicPurpose.DEFEND.ordinal)
        val stored=assertNotNull(act(22,stocks(),r=q).stocks)
        val lost=stored.copy(bases=stored.bases+(1049 to stored.bases.getValue(1049).copy(power=3)))
        val settled=StrategicOperations.settle(galaxy,lost,now+1)
        assertEquals(StrategicPlanState.WITHDRAWN,settled.strategic.operations.single().state)
        assertTrue(settled.strategic.people.isEmpty())
    }
}
