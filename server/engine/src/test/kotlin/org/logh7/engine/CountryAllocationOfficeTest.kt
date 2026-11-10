package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryAllocationOfficeTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1050,2487,80,0,"New capital",0,20f,86400,0,0f,2f),
        GalaxyBase(1051,2487,80,0,"Other HQ",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val policy=LogisticsOfficePolicy(galaxy,catalog)
    private fun stock(country:CountryId,style:ShipStyleId,card:Int):PlanetStocks {
        val ship=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==style.nativePower}.kind)
        return PlanetStocks(1,0,mapOf(
            1050 to PlanetStock(1050,Allegiance.Country(country),0,1000,200,300,units=UnitInventory(mapOf(ship to 3))),
            1051 to PlanetStock(1051,2,0,1000,200,300)),
            outfits=mapOf(1 to OutfitStock(1,country,OutfitKind.FLEET,1050,101,shipStyle=style)),
            warehouses=mapOf(101 to OutfitWarehouse(101,country,OutfitKind.FLEET,1050,1,shipStyle=style)),
            strategic=StrategicState(offices=listOf(OfficeHolding(card,1050,10000,country)),capitals=mapOf(country to 1050)),
            deploymentInitialized=true)
    }
    private fun session(country:CountryId)=SessionSimulation(SessionRules.load()).also {it.join("pilot",country,false,"c")}.exportState()
    private fun player(country:CountryId)=Navigator(1,10000,country,2487,1000,1000,1000,baseId=1050,mode=4,shipStyle=ShipStyleId("empire"))
    private fun request(country:CountryId)=LogisticsRequest("pilot","c",10000,country,1,1,LogisticsMode.ALLOCATION,LogisticsPhase.PREPARE)
    private fun production(stock:PlanetStocks)=PlanetProduction(galaxy,null,SessionRules.load().startSeconds,PlanetProductionPolicy(),restored=stock)

    @Test fun bothOfficeSystemsAllocateTheActualCountrysModelsIndependentlyOfDisplayAndShipStyle() {
        for((id,card) in listOf(42 to 24,43 to 150))for(style in listOf(ShipStyleId("empire"),ShipStyleId("alliance"))) {
            val country=CountryId(id);val initial=stock(country,style,card);val production=production(initial)
            val logistics=OutfitLogistics({0},galaxy,countries=catalog)
            assertEquals(listOf(card to (105000+if(card==24)38 else 37)),policy.cards(initial,10000,id))
            val prepared=logistics.apply(request(country),session(country),player(country),production)
            assertNull(prepared.rejection,"$id/$style")
            val ship=initial.bases.getValue(1050).units.ships.keys.single()
            val committed=logistics.apply(request(country).copy(phase=LogisticsPhase.COMMIT,reservation=prepared.reservation,
                transfer=UnitTransfer(mapOf(ship to 2)),supplies=100),session(country),player(country),production)
            assertNull(committed.rejection)
            val after=assertNotNull(committed.stocks)
            assertEquals(1,after.bases.getValue(1050).units.ships[ship]);assertEquals(2,after.warehouses.getValue(101).units.ships[ship])
            assertEquals(900,after.bases.getValue(1050).supplies);assertEquals(100,after.warehouses.getValue(101).supplies)
            assertEquals(country,after.warehouses.getValue(101).country);assertEquals(style,after.warehouses.getValue(101).shipStyle)
            assertFalse(logistics.blocksSupply(session(country),after,setOf(1)))
        }
    }

    @Test fun officeTypesAndEveryHeldCardAreCheckedWithoutBorrowingAnotherNationsRecords() {
        val country=CountryId(42);val initial=stock(country,ShipStyleId("empire"),24)
        assertTrue(policy.permits(initial,10000,country,OutfitKind.FLEET,24))
        assertFalse(policy.permits(initial,10000,country,OutfitKind.PATROL))
        val both=initial.copy(strategic=initial.strategic.copy(offices=initial.strategic.offices+OfficeHolding(25,1050,10000,country)))
        assertEquals(setOf(24,25),policy.cards(both,10000,42).map {it.first}.toSet())
        assertTrue(policy.permits(both,10000,country,OutfitKind.GROUND,25))
        assertFalse(policy.permits(both,10000,country,OutfitKind.GROUND,24))
        val wrong=initial.copy(strategic=initial.strategic.copy(offices=listOf(OfficeHolding(24,1050,10000,CountryId(2)))),
            logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_FIRST))
        assertFalse(policy.permits(wrong,10000,country,OutfitKind.FLEET))
        assertTrue(policy.cards(wrong,10000,42).isEmpty())
    }

    @Test fun losingTheCapitalOrTheAppointmentRevokesAnAlreadyPreparedAllocation() {
        val country=CountryId(42);val initial=stock(country,ShipStyleId("alliance"),24)
        val changes=listOf(initial.copy(strategic=initial.strategic.copy(offices=emptyList())),
            initial.copy(bases=initial.bases+(1050 to initial.bases.getValue(1050).copy(owner=Allegiance.Country(CountryId(43))))),
            initial.copy(strategic=initial.strategic.copy(capitals=mapOf(country to 1051))))
        for(changed in changes) {
            val production=production(initial);val logistics=OutfitLogistics({0},galaxy,countries=catalog)
            val prepared=logistics.apply(request(country),session(country),player(country),production)
            assertNull(prepared.rejection)
            val current=production.snapshot(1)!!;production.replace(current,changed)
            val baseline=production.settle(1,session(country).gameMillis/1000)
            val commit=request(country).copy(phase=LogisticsPhase.COMMIT,reservation=prepared.reservation,supplies=100)
            assertEquals(LogisticsRejection.ACCESS,logistics.apply(commit,session(country),player(country),production).rejection)
            assertEquals(baseline,production.snapshot(1))
            assertEquals(LogisticsRejection.PREPARATION,logistics.apply(commit.copy(phase=LogisticsPhase.CANCEL),session(country),player(country),production).rejection)
            assertFalse(logistics.blocksSupply(session(country),changed,setOf(1)))
        }
    }
}
