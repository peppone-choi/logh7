package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryLogisticsRoutingTest {
    private val country=CountryId(42)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private fun session(id:CountryId?=country)=SessionSimulation(SessionRules.load()).also {it.join("pilot",id,false,"c")}.exportState()
    private fun player(id:CountryId?=country)=Navigator(1,10000,id,2487,1000,1000,1000,baseId=1049,mode=4,shipStyle=ShipStyleId("empire"))
    private fun stock(style:ShipStyleId=ShipStyleId("empire")):PlanetStocks {
        val kinds=ProductionUnits.definitions.filter {it.ship && it.power==style.nativePower}.map {it.kind}.toSet()
        val type=UnitSpecifications.ships.single {it.kind==32}.type
        val ship=UnitStack(UnitSpecifications.ships.first {it.kind in kinds && it.type==type}.kind)
        val crew=UnitStack(UnitSpecifications.crews.first {it.power==style.nativePower && it.type==3}.kind)
        val required=UnitSpecifications.ships.single {it.kind==ship.kind}.number("必要乗組員ユニット数:必要乗組員ユニット数")
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(if(style.nativePower==2)0 else 4,2),base=1049)))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(country),0,10000,200,300)),
            outfits=mapOf(1 to OutfitStock(1,country,OutfitKind.TRANSPORT,1049,101,10000,
                UnitInventory(mapOf(ship to 1),ground.quantities()+mapOf(crew to required)),supplies=100,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,ship))),activeGround=ground,shipStyle=style),
                2 to OutfitStock(2,country,OutfitKind.FLEET,1049,102,10001,
                    UnitInventory(mapOf(ship to 1),mapOf(crew to required)),
                    activeShips=ActiveShipUnits(listOf(ActiveShipUnit(2,ship))),activeGround=ActiveGroundUnits(emptyList()),shipStyle=style)),
            warehouses=mapOf(101 to OutfitWarehouse(101,country,OutfitKind.TRANSPORT,1049,1,
                UnitInventory(mapOf(ship to 2),mapOf(crew to required*4)),supplies=1000,shipStyle=style),
                102 to OutfitWarehouse(102,country,OutfitKind.FLEET,1049,2,shipStyle=style)),
            logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_SECOND),deploymentInitialized=true,nextShipId=4)
    }
    private fun production(s:PlanetStocks)=PlanetProduction(galaxy,null,SessionRules.load().startSeconds,PlanetProductionPolicy(),restored=s)
    private fun request(id:CountryId?=country)=LogisticsRequest("pilot","c",10000,id,1,1,LogisticsMode.REORGANIZATION,LogisticsPhase.PREPARE)

    @Test fun additionalCountryReorganizationMovesItsOwnModelsAndCrewWithoutUsingTheDisplayFaction() {
        for(style in listOf(ShipStyleId("empire"),ShipStyleId("alliance"))) {
            val initial=stock(style);val production=production(initial);val logistics=OutfitLogistics({0})
            val prepared=logistics.apply(request(),session(),player(),production)
            assertNull(prepared.rejection)
            val ship=initial.outfits.getValue(1).units.ships.keys.single()
            val crew=initial.warehouses.getValue(101).units.troops.keys.single()
            val needed=UnitSpecifications.ships.single {it.kind==ship.kind}.number("必要乗組員ユニット数:必要乗組員ユニット数")
            val query=ReorganizationRequest("pilot","c",10000,country,1,1049,1,1,1,
                listOf(ReorganizationShip(ship.kind,1)),listOf(ReorganizationTroop(1000+crew.kind,crew.level,needed)))
            val transfer=Reorganization.transfer(query,initial)
            val result=logistics.apply(request().copy(phase=LogisticsPhase.COMMIT,reservation=prepared.reservation,transfer=transfer),
                session(),player(),production)
            assertNull(result.rejection)
            val after=assertNotNull(result.stocks)
            assertEquals(country,after.outfits.getValue(1).country);assertEquals(style,after.outfits.getValue(1).shipStyle)
            assertEquals(listOf(1,4),after.outfits.getValue(1).activeShips!!.values.map {it.id})
            assertEquals(2,after.outfits.getValue(1).units.ships[ship]);assertEquals(1,after.warehouses.getValue(101).units.ships[ship])
            assertEquals(after,production.snapshot(1));assertFalse(logistics.blocksMovement(session(),1))
        }
    }

    @Test fun supplySupplementAndGroundTransferUseActualCountryAndRetainModelAndUnitIdentities() {
        for(style in listOf(ShipStyleId("empire"),ShipStyleId("alliance"))) {
            val initial=stock(style);val pilot=player().copy(shipStyle=style)
            val supply=CompletenessSupply.evaluate(CompletenessSupplyRequest("pilot","c",10000,country,1,1,2),session(),pilot,initial)
            assertNull(supply.rejection);assertEquals(100,supply.transferred);assertEquals(840,supply.player!!.mcp)
            assertEquals(100,supply.stocks!!.outfits.values.sumOf {it.supplies});assertEquals(initial.warehouses,supply.stocks.warehouses)
            val unit=initial.outfits.getValue(1).activeShips!!.values.single()
            val damaged=initial.copy(outfits=initial.outfits+(1 to initial.outfits.getValue(1).copy(
                activeShips=ActiveShipUnits(listOf(unit.copy(boats=unit.boats-10))))))
            val supplemented=Supplement.evaluate(SupplementRequest("pilot","c",10000,country,1,1049,1,1,1,
                mapOf(unit.stack.kind to listOf(unit.id))),session(),pilot,damaged)
            assertNull(supplemented.rejection);assertEquals(840,supplemented.player!!.mcp)
            assertEquals(unit,supplemented.stocks!!.outfits.getValue(1).activeShips!!.values.single())
            val loaded=GroundTransfer.evaluate(GroundTransferRequest("pilot","c",10000,country,1,1,GroundTransferAction.LOAD),session(),pilot,initial)
            assertNull(loaded.rejection);assertEquals(920,loaded.player!!.mcp)
            val ground=loaded.stocks!!.outfits.getValue(1).activeGround!!.values.single()
            assertEquals(3,ground.id);assertEquals(unit.id,ground.carrier)
            for(s in listOf(assertNotNull(supply.stocks),assertNotNull(supplemented.stocks),assertNotNull(loaded.stocks))) {
                assertTrue(s.outfits.values.all {it.country==country && it.shipStyle==style})
                assertTrue(s.warehouses.values.all {it.country==country && it.shipStyle==style})
            }
        }
    }

    @Test fun anotherCountryWithTheSameModelsAndUnaffiliatedMembershipCannotBorrowAuthority() {
        val initial=stock();val production=production(initial);val baseline=production.settle(1,session().gameMillis/1000);val logistics=OutfitLogistics({0})
        for(id in listOf(CountryId(2),CountryId(43),null)) {
            val state=session(id);val pilot=player(id)
            assertEquals(LogisticsRejection.ACCESS,logistics.apply(request(id),state,pilot,production).rejection)
            assertEquals(LogisticsRejection.ACCESS,CompletenessSupply.evaluate(CompletenessSupplyRequest("pilot","c",10000,id,1,1,2),state,pilot,initial).rejection)
            assertEquals(LogisticsRejection.ACCESS,Supplement.evaluate(SupplementRequest("pilot","c",10000,id,1,1049,1,1,1,emptyMap()),state,pilot,initial).rejection)
            assertEquals(LogisticsRejection.ACCESS,GroundTransfer.evaluate(GroundTransferRequest("pilot","c",10000,id,1,1,GroundTransferAction.LOAD),state,pilot,initial).rejection)
            assertEquals(baseline,production.snapshot(1));assertFalse(logistics.blocksMovement(state,1))
        }
        assertEquals(LogisticsRejection.ACCESS,logistics.apply(request().copy(mode=LogisticsMode.ALLOCATION),session(),player(),production).rejection)
        assertEquals(baseline,production.snapshot(1))
    }

    @Test fun pendingReservationsExpireOnCountryChangeAndCannotBeReusedByAnotherCharacter() {
        val production=production(stock());val logistics=OutfitLogistics({0});val prepared=logistics.apply(request(),session(),player(),production)
        val reservation=assertNotNull(prepared.reservation)
        assertTrue(logistics.blocksMovement(session(),1))
        val commit=request().copy(phase=LogisticsPhase.COMMIT,reservation=reservation)
        assertEquals(LogisticsRejection.PREPARATION,logistics.apply(commit.copy(characterId=10001),session(),player().copy(characterId=10001),production).rejection)
        for(supplyCheck in listOf(false,true)) {
            val changed=session(CountryId(43))
            if(supplyCheck)assertFalse(logistics.blocksSupply(changed,stock(),setOf(1)))
            else assertFalse(logistics.blocksMovement(changed,1))
            assertEquals(LogisticsRejection.PREPARATION,logistics.apply(commit,session(),player(),production).rejection)
            assertNull(logistics.apply(request(),session(),player(),production).rejection)
        }
    }
}
