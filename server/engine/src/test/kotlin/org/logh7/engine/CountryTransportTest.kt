package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryTransportTest {
    private val country=CountryId(42)
    private val other=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1003).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("officer",country,false,"o");it.join("captain",country,false,"c")}.exportState()
    private val officer=Navigator(1,10000,country,2487,1000,1000,1000,baseId=1000,mode=4,shipStyle=ShipStyleId("empire"))
    private val captain=officer.copy(characterId=10001,baseId=1001)
    private val hull=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==3 && it.name.startsWith("輸送艦")}.kind)
    private val cargo=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2}.kind,1)
    private val boats=UnitSpecifications.ships.single {it.kind==hull.kind}.number
    private fun catalog(family:String="empire")=CountryCatalogLoader.load().let {old->CountryCatalog(
        old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id",family=family,shipStyleOverride="alliance")})}
    private fun stock(family:String="empire"):PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,hull)))
        return PlanetStocks(1,0,(1000..1003).associate {id->id to PlanetStock(id,
            Allegiance.Country(if(id==1003)other else country),0,if(id==1001)5000 else 100,200,300,
            units=if(id==1001)UnitInventory(ships=mapOf(cargo to 4)) else UnitInventory())},
            mapOf(200 to OutfitStock(200,country,OutfitKind.TRANSPORT,1001,300,10001,
                UnitInventory(ships=ships.quantities()),activeShips=ships,shipStyle=ShipStyleId("alliance"))),
            mapOf(300 to OutfitWarehouse(300,country,OutfitKind.TRANSPORT,1001,200,shipStyle=ShipStyleId("alliance"))),nextShipId=2,
            strategic=StrategicState(capitals=mapOf(country to 1000,other to 1003),offices=listOf(
                OfficeHolding(if(family=="empire")25 else 150,1000,10000,country))))
    }
    private fun plan(family:String="empire")=StrategyActionRequest("officer","o",10000,country,1,28,
        if(family=="empire")25 else 150,5,base=1001,target=1002,amount=1000,value=cargo.kind,argument=2)
    private fun load(family:String="empire")=StrategyActionRequest("captain","c",10001,country,1,65,
        if(family=="empire")44 else 180,5,outfit=200,target=1)
    private fun act(r:StrategyActionRequest,s:PlanetStocks,definitions:CountryCatalog=catalog(),p:Navigator?=null)=
        StrategyActions.evaluate(galaxy,r,session,p ?: if(r.account=="officer")officer else captain.copy(
            baseId=s.outfits.getValue(200).positionIn(galaxy).baseId),s,definitions)

    @Test fun configuredLogisticsOfficesPlanLoadAndDeliverWithoutChangingOwnershipOrModels() {
        for(family in listOf("empire","alliance")) {
            val definitions=catalog(family);val before=stock(family)
            val planned=act(plan(family),before,definitions)
            assertNull(planned.rejection);assertEquals(920,planned.player?.mcp)
            val pending=assertNotNull(planned.stocks);assertEquals(country,pending.strategic.transports.single().country)
            assertEquals(2,pending.bases.getValue(1001).units.ships[cargo])
            val loaded=act(load(family),pending,definitions)
            assertNull(loaded.rejection);assertEquals(840,loaded.player?.mcp)
            val aboard=assertNotNull(loaded.stocks)
            assertEquals(200,aboard.strategic.transports.single().carrier)
            val moved=aboard.copy(outfits=aboard.outfits+(200 to aboard.outfits.getValue(200).copy(position=OutfitPosition(2487,1002))))
            val done=act(load(family),moved,definitions,assertNotNull(loaded.player).copy(baseId=1002))
            assertNull(done.rejection);assertEquals(680,done.player?.mcp)
            val after=assertNotNull(done.stocks)
            assertTrue(after.strategic.transports.isEmpty());assertEquals(1100,after.bases.getValue(1002).supplies)
            assertEquals(2,after.bases.getValue(1002).units.ships[cargo]);assertEquals(country,after.outfits.getValue(200).country)
            assertEquals(ShipStyleId("alliance"),after.outfits.getValue(200).shipStyle)
            assertEquals(hull,after.outfits.getValue(200).activeShips?.values?.single()?.stack)
            assertEquals(before.warehouses,after.warehouses)
        }
    }

    @Test fun sameDisplayCannotBorrowAnotherCountrysOfficeBasePackageOrCarrier() {
        val before=stock()
        val foreignOffice=before.copy(strategic=before.strategic.copy(offices=listOf(OfficeHolding(25,1003,10000,other))))
        assertEquals(StrategyActionRejection.CARD,act(plan(),foreignOffice).rejection)
        assertEquals(StrategyActionRejection.TARGET,act(plan().copy(target=1003),before).rejection)
        val pending=assertNotNull(act(plan(),before).stocks)
        val foreignPackage=pending.copy(strategic=pending.strategic.copy(transports=pending.strategic.transports.map {it.copy(country=other)}))
        for(r in listOf(load(),plan().copy(command=29,target=1))) {
            val denied=act(r,foreignPackage)
            assertEquals(StrategyActionRejection.TARGET,denied.rejection);assertEquals(foreignPackage,denied.stocks)
            assertEquals(1000,denied.player?.mcp)
        }
        val foreignCarrier=pending.copy(outfits=pending.outfits.mapValues {(_,o)->o.copy(country=other)},
            warehouses=pending.warehouses.mapValues {(_,w)->w.copy(country=other)})
        assertEquals(StrategyActionRejection.AUTHORITY,act(load(),foreignCarrier).rejection)
        assertEquals(StrategyActionRejection.CARD,act(load().copy(card=180),pending).rejection)
        val occupied=pending.copy(bases=pending.bases+(1001 to pending.bases.getValue(1001).copy(owner=Allegiance.Country(other))))
        assertEquals(StrategyActionRejection.LOCATION,act(plan().copy(command=29,target=1),occupied).rejection)
    }

    @Test fun cancellationAndReroutingAfterForeignOccupationReturnCargoOnlyToTheOwner() {
        val before=stock();val pending=assertNotNull(act(plan(),before).stocks)
        val cancelled=act(plan().copy(command=29,target=1),pending)
        assertNull(cancelled.rejection);assertEquals(before.bases,cancelled.stocks?.bases)
        assertTrue(assertNotNull(cancelled.stocks).strategic.transports.isEmpty())
        val aboard=assertNotNull(act(load(),pending).stocks)
        assertEquals(StrategyActionRejection.LOCATION,act(load(),aboard).rejection)
        val captured=aboard.copy(bases=aboard.bases+(1002 to aboard.bases.getValue(1002).copy(owner=Allegiance.Country(other))))
        val returned=act(load(),captured)
        assertNull(returned.rejection)
        val after=assertNotNull(returned.stocks)
        assertEquals(before.bases.getValue(1001),after.bases.getValue(1001))
        assertEquals(100,after.bases.getValue(1002).supplies);assertTrue(after.strategic.transports.isEmpty())
    }

    @Test fun damagedActualCountryCarrierDeliversOnlyItsSurvivingShare() {
        val pending=assertNotNull(act(plan(),stock()).stocks)
        val aboard=assertNotNull(act(load(),pending).stocks)
        val damaged=aboard.copy(outfits=aboard.outfits+(200 to aboard.outfits.getValue(200).copy(
            position=OutfitPosition(2487,1002),activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,hull,boats=boats/2))))))
        val delivered=act(load(),damaged)
        assertNull(delivered.rejection)
        val after=assertNotNull(delivered.stocks)
        assertEquals(100+1000*(boats/2)/boats,after.bases.getValue(1002).supplies)
        assertEquals(1,after.bases.getValue(1002).units.ships[cargo]);assertEquals(country,after.outfits.getValue(200).country)
    }
}
