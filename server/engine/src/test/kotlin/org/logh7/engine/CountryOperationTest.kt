package org.logh7.engine

import org.junit.jupiter.api.Test
import org.logh7.engine.strategy.*
import kotlin.test.*

class CountryOperationTest {
    private val country=CountryId(42)
    private val other=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2),GalaxySystem(4,2490,4,"There",2)),listOf(
        GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f),
        GalaxyBase(1001,2490,80,0,"Target",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("a",country,false,"a");it.join("i",country,false,"i")}.exportState()
    private val now=session.gameMillis/1000
    private val planner=Navigator(1,10000,country,2487,2000,2000,1000,shipStyle=ShipStyleId("empire"))
    private val issuer=planner.copy(characterId=10001)
    private fun catalog(family:String)=CountryCatalogLoader.load().let {old->CountryCatalog(
        old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id",family=family,shipStyleOverride="alliance")})}
    private fun cards(family:String,solo:Boolean=false)=if(family=="empire")
        (if(solo)26 to 30 else 24 to 28) else (if(solo)149 to 171 else 147 to 169)
    private fun stock(family:String="empire",solo:Boolean=false):PlanetStocks {
        val (plan,issue)=cards(family,solo)
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(ProductionUnits.definitions.first {it.ship && it.power==3}.kind))))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,Allegiance.Country(country),0,100,200,300),
            1001 to PlanetStock(1001,Allegiance.Country(other),0,100,200,300)),
            mapOf(200 to OutfitStock(200,country,OutfitKind.FLEET,1000,300,10002,UnitInventory(ships=ships.quantities()),
                activeShips=ships,shipStyle=ShipStyleId("alliance"))),
            mapOf(300 to OutfitWarehouse(300,country,OutfitKind.FLEET,1000,200,shipStyle=ShipStyleId("alliance"))),
            nextShipId=2,deploymentInitialized=true,strategic=StrategicState(capitals=mapOf(country to 1000,other to 1001),
                offices=listOf(OfficeHolding(plan,1000,10000,country),OfficeHolding(issue,1000,10001,country))))
    }
    private fun request(command:Int,family:String="empire",solo:Boolean=false)=StrategyActionRequest(
        if(command==24)"i" else "a",if(command==24)"i" else "a",if(command==24)10001 else 10000,country,1,command,
        cards(family,solo).let {if(command==24)it.second else it.first},4,target=2490,
        base=if(command==24)200 else 0,amount=if(command==22)1 else 0)
    private fun act(r:StrategyActionRequest,s:PlanetStocks,family:String="empire")=
        StrategyActions.evaluate(galaxy,r,session,if(r.command==24)issuer else planner,s,catalog(family))

    @Test fun sameDisplayAndOfficeNumberDoNotSharePlanLimitsOrdersOrRewards() {
        for(family in listOf("empire","alliance")) {
            val s=stock(family);val foreign=StrategicOperation(50,10000,cards(family).first,other,2490,
                StrategicForce.FLEET,StrategicPurpose.OCCUPY,999,now)
            val planned=act(request(22,family),s.copy(strategic=s.strategic.copy(operations=listOf(foreign))),family)
            assertNull(planned.rejection);assertEquals(720,planned.player?.mcp)
            val pending=assertNotNull(planned.stocks)
            assertEquals(country,pending.strategic.operations.single {it.id==51}.country)
            assertEquals(StrategyActionRejection.MAXIMUM,act(request(22,family),pending,family).rejection)
            val assigned=act(request(24,family),pending,family)
            assertNull(assigned.rejection);assertEquals(1999,assigned.player?.mcp)
            val moved=assertNotNull(assigned.stocks).let {it.copy(outfits=it.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2490))})}
            val started=StrategicOperations.settle(galaxy,moved,now+1)
            assertEquals(StrategicPlanState.STARTED,started.strategic.operations.single {it.id==51}.state)
            val captured=started.copy(bases=started.bases+(1001 to started.bases.getValue(1001).copy(owner=Allegiance.Country(country))))
            val done=StrategicOperations.settle(galaxy,captured,now+OperationPlanRules.DURATION_GAME_SECONDS)
            assertEquals(OperationOutcome.FULL_SUCCESS,done.strategic.operations.single {it.id==51}.outcome)
            assertEquals(foreign,done.strategic.operations.single {it.id==50})
            for(id in listOf(10000,10001,10002))assertEquals(100L,done.strategic.people.getValue(id).merit)
            assertEquals(ShipStyleId("alliance"),done.outfits.getValue(200).shipStyle)
            assertEquals(done,StrategicOperations.settle(galaxy,done,now+OperationPlanRules.DURATION_GAME_SECONDS+1))
            val withdrawn=act(request(23,family),pending,family)
            assertNull(withdrawn.rejection)
            assertEquals(StrategicPlanState.WITHDRAWN,withdrawn.stocks?.strategic?.operations?.single {it.id==51}?.state)
            assertEquals(foreign,withdrawn.stocks?.strategic?.operations?.single {it.id==50})
        }
    }

    @Test fun foreignSeatsArmiesAndInvalidTargetsCannotAuthorizeAPlanOrAnOrder() {
        val s=stock();val q=request(22)
        val borrowed=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(24,1001,10000,other))))
        assertEquals(StrategyActionRejection.AUTHORITY,act(q,borrowed).rejection)
        assertEquals(StrategyActionRejection.LOCATION,act(q.copy(target=2487),s).rejection)
        val neutral=s.copy(bases=s.bases+(1001 to s.bases.getValue(1001).copy(owner=Allegiance.Neutral)))
        assertEquals(StrategyActionRejection.LOCATION,act(q,neutral).rejection)
        val planned=assertNotNull(act(q,s).stocks)
        val foreign=planned.copy(outfits=planned.outfits.mapValues {(_,o)->o.copy(country=other)},
            warehouses=planned.warehouses.mapValues {(_,w)->w.copy(country=other)})
        val denied=act(request(24),foreign)
        assertEquals(StrategyActionRejection.AUTHORITY,denied.rejection);assertEquals(issuer,denied.player);assertEquals(foreign,denied.stocks)
        assertEquals(StrategyActionRejection.MAXIMUM,act(q,foreign.copy(strategic=foreign.strategic.copy(operations=emptyList()))).rejection)
        val lost=s.copy(strategic=s.strategic.copy(operations=listOf(StrategicOperation(1,10000,24,country,2487,
            StrategicForce.FLEET,StrategicPurpose.DEFEND,1,now))),bases=s.bases.mapValues {(_,b)->b.copy(owner=Allegiance.Country(other))})
        assertEquals(StrategicPlanState.WITHDRAWN,StrategicOperations.settle(galaxy,lost,now+1).strategic.operations.single().state)
    }

    @Test fun aForeignCarrierAtTheObjectiveDoesNotStartTheOldCountrysOperation() {
        val planned=assertNotNull(act(request(22),stock()).stocks)
        val assigned=assertNotNull(act(request(24),planned).stocks)
        val foreign=assigned.copy(outfits=assigned.outfits.mapValues {(_,o)->o.copy(country=other,position=OutfitPosition(2490))},
            warehouses=assigned.warehouses.mapValues {(_,w)->w.copy(country=other)})
        val untouched=StrategicOperations.settle(galaxy,foreign,now+1)
        assertEquals(StrategicPlanState.ISSUED,untouched.strategic.operations.single().state)
        assertTrue(untouched.strategic.people.isEmpty())
    }

    @Test fun soloArrivalUsesActualMembershipAndGeneration() {
        val participant=planner.copy(characterId=10003,grid=2490,hull=FlagshipHull(0,now))
        val op=StrategicOperation(1,10000,26,country,2490,StrategicForce.SOLO,StrategicPurpose.OCCUPY,1,now,
            StrategicPlanState.ISSUED,now,characters=setOf(10003),issuer=10001)
        val s=stock(solo=true).let {it.copy(strategic=it.strategic.copy(operations=listOf(op)))}
        assertEquals(op,StrategicOperations.settle(galaxy,s,now+OperationPlanRules.DURATION_GAME_SECONDS).strategic.operations.single(),
            "startup without restored navigation must defer rather than discard the arrival reward")
        assertEquals(StrategicPlanState.STARTED,StrategicOperations.settle(galaxy,s,now+1,listOf(participant)).strategic.operations.single().state)
        for(p in listOf(participant.copy(country=other),participant.copy(generation=2)))
            assertEquals(StrategicPlanState.ISSUED,StrategicOperations.settle(galaxy,s,now+1,listOf(p)).strategic.operations.single().state)
    }

    @Test fun ordinaryProductionTicksSettleSoloDefenseWithoutACombatEvent() {
        val participant=Navigator(1,10003,Power.EMPIRE,2487,1000,1000,1000,hull=FlagshipHull(0,now))
        val op=StrategicOperation(1,10000,26,CountryId(2),2487,StrategicForce.SOLO,StrategicPurpose.DEFEND,1,now,
            StrategicPlanState.ISSUED,now,characters=setOf(10003),defended=setOf(1000),issuer=10001)
        val s=PlanetStocks(1,0,galaxy.bases.associate {b->b.id to PlanetStock(b.id,2,0,100,200,300,
            PlanetEconomy(1000,600,70,80))},deploymentInitialized=true,strategic=StrategicState(operations=listOf(op)))
        val production=PlanetProduction(galaxy,null,now,PlanetProductionPolicy(),restored=s,civicPlayers={listOf(participant)})
        val started=production.settle(1,now+1)
        assertEquals(StrategicPlanState.STARTED,started.strategic.operations.single().state)
        val ended=production.settle(1,now+OperationPlanRules.DURATION_GAME_SECONDS)
        assertEquals(OperationOutcome.FULL_SUCCESS,ended.strategic.operations.single().outcome)
        assertEquals(100L,ended.strategic.people.getValue(10003).merit)
    }
}
