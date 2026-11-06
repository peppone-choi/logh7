package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryNavigationRoutingTest {
    private val catalog=CountryCatalogLoader.load().let {c->CountryCatalog(c.calendars.values.toList(),c.families.values.toList(),
        c.countries.values.toList()+(42..43).map {c.countries.getValue(2).copy(id=it,key="country_$it")})}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Legacy",2),
        GalaxySystem(5,2488,4,"Empty",0)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        Power.entries.associateWith {2490},countryStarts=mapOf(CountryId(42) to 2487))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(CountryId(42)),0,100,200,300)),
        deploymentInitialized=true)
    private val pilot=Navigator(1,10000,CountryId(42),2487,1000,1000,1000,shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(89,0))
    private fun session(country:CountryId?=CountryId(42))=SessionSimulation(SessionRules.load()).also {it.join("pilot",country,false,"c")}.exportState()
    private fun request(country:CountryId?=CountryId(42))=NavigationRequest("pilot",10000,country,"c",1,shipKind=89,shipStyle=ShipStyleId("alliance"))
    private class Store(initial:Map<String,Navigator>):NavigationStore {
        var saved=initial;var saves=0
        override fun load()=saved
        override fun save(players:Map<String,Navigator>) {saved=players;saves++}
    }
    private fun navigation(store:NavigationStore?=null)=Navigation(galaxy,store,NavigationPolicy(),configuredCountries=catalog)

    @Test fun theCountryStartAndStoredStyleAreIndependentOfTheNativeDisplayFaction() {
        val nav=navigation()
        val initialized=assertNotNull(nav.apply(request(),session(),stocks).player)
        assertEquals(2487,initialized.grid);assertEquals(CountryId(42),initialized.country)
        assertEquals(2490,galaxy.starts.getValue(Power.EMPIRE))
        assertEquals(ShipStyleId("alliance"),initialized.shipStyle);assertEquals(89,initialized.hull?.kind)
        val missing=Navigation(Galaxy(galaxy.systems,galaxy.bases,galaxy.starts),null,NavigationPolicy(),configuredCountries=catalog)
        assertEquals(NavigationRejection.BASE,missing.apply(request(),session(),stocks).rejection)
        assertTrue(missing.snapshot(1).isEmpty())
    }
    @Test fun anotherCountryWithTheSameNativeAppearanceCannotUseTheSavedIdentity() {
        val store=Store(mapOf("pilot" to pilot));val nav=navigation(store)
        for(id in listOf(CountryId(2),CountryId(43),null))
            assertEquals(NavigationRejection.IDENTITY,nav.apply(request(id),session(),stocks).rejection)
        assertEquals(0,store.saves);assertEquals(pilot,store.saved.getValue("pilot"))
    }
    @Test fun aForeignOperationDoesNotKeepTheWarpDiscountAfterAMembershipChange() {
        val state=session();val now=state.gameMillis/1000
        val plan=StrategicOperation(1,10001,26,CountryId(42),2490,StrategicForce.SOLO,StrategicPurpose.SWEEP,1,now,
            StrategicPlanState.ISSUED,now,characters=setOf(pilot.characterId),issuer=10002)
        fun warp(country:CountryId):Navigator {
            val s=stocks.copy(strategic=StrategicState(operations=listOf(plan.copy(country=country))))
            val result=navigation(Store(mapOf("pilot" to pilot))).apply(request().copy(destination=2489,
                unitId=pilot.unitId,captainCard=59),state,s)
            assertNull(result.rejection)
            return assertNotNull(result.player)
        }
        assertEquals(960,warp(CountryId(42)).mcp)
        assertEquals(920,warp(CountryId(43)).mcp)
    }
    @Test fun aCountryCaptainCanWarpAndSettleFuelWithoutChangingCountryOrShipStyle() {
        val store=Store(mapOf("pilot" to pilot));val nav=navigation(store);val initial=session()
        val warp=assertNotNull(nav.apply(request().copy(destination=2488,unitId=pilot.unitId,captainCard=59),initial,stocks).player)
        val arrivalAt=warp.strategy?.jobs?.singleOrNull()?.completesAtGameMillis ?: initial.gameMillis
        val arrived=assertNotNull(nav.apply(request(),initial.copy(gameMillis=arrivalAt),stocks).player)
        assertEquals(2488,arrived.grid);assertEquals(CountryId(42),arrived.country)
        assertTrue(arrived.cruise<1000);assertEquals(89,arrived.hull?.kind)
        val fuel=assertNotNull(nav.apply(request().copy(supplyFuel=true,unitId=pilot.unitId,captainCard=59),
            initial.copy(gameMillis=arrivalAt),stocks).player)
        val fueled=assertNotNull(nav.apply(request(),initial.copy(gameMillis=assertNotNull(fuel.fuelReadyAt)*1000),stocks).player)
        assertEquals(1000,fueled.cruise);assertEquals(CountryId(42),fueled.country)
        assertEquals(ShipStyleId("alliance"),fueled.shipStyle);assertEquals(fueled,store.saved.getValue("pilot"))
    }
    @Test fun departureAndQuayAccessRequireActualOwnershipRatherThanMatchingModels() {
        val quay=galaxy.facilitiesAt(1049,stocks,catalog).single {it.kind==4}.spots.single {it.kind==6}
        for(id in listOf(CountryId(42),CountryId(2),CountryId(43))) {
            val docked=pilot.copy(country=id,baseId=1049,mode=4)
            for(move in listOf(PlaceMove.SwitchMode(5),PlaceMove.Spot(quay.id,0))) {
                val store=Store(mapOf("pilot" to docked));val nav=navigation(store)
                val result=nav.apply(request(id).copy(unitId=docked.unitId,captainCard=59,place=move),session(id),stocks)
                if(id==CountryId(42))assertNotNull(result.player) else {
                    assertEquals(NavigationRejection.ACCESS,result.rejection)
                    val unchanged=store.saved.getValue("pilot")
                    assertEquals(id,unchanged.country);assertEquals(1049,unchanged.baseId);assertEquals(0,unchanged.spotId)
                    assertEquals(1000,unchanged.pcp);assertEquals(1000,unchanged.mcp);assertEquals(docked.moves,unchanged.moves)
                }
            }
        }
    }
    @Test fun aReservedDockRechecksActualOwnershipEvenWhenTheStockSnapshotBecomesUnavailable() {
        for(available in listOf(true,false)) {
            val store=Store(mapOf("pilot" to pilot));val nav=navigation(store);val initial=session()
            val reserved=assertNotNull(nav.apply(request().copy(unitId=pilot.unitId,captainCard=59,place=PlaceMove.Dock(1049,4)),initial,stocks).player)
            assertEquals(1049,reserved.dockTarget)
            val due=reserved.strategy!!.jobs.single().completesAtGameMillis
            val changed=stocks.copy(bases=stocks.bases.mapValues {(_,base)->base.copy(owner=Allegiance.Country(CountryId(43)))})
            val paused=assertNotNull(nav.apply(request(),initial.copy(gameMillis=due),if(available)changed else null).player)
            assertEquals(0,paused.baseId)
            // Check ownership independently of the tactical-contact pause when ordinary commands resume.
            val settled=assertNotNull(nav.apply(request().copy(tactical=false),initial.copy(gameMillis=due),if(available)changed else null).player)
            assertEquals(CountryId(42),settled.country);assertEquals(0,settled.baseId);assertEquals(5,settled.mode)
            assertNull(settled.dockTarget);assertEquals(pilot.moves,settled.moves)
        }
    }
    @Test fun returnPlanetAndCaptainCardCannotBorrowAnotherCountryOrTheModelPalette() {
        val docked=pilot.copy(baseId=1049,mode=4)
        val own=navigation(Store(mapOf("pilot" to docked))).apply(request().copy(returnBase=1049),session(),stocks)
        assertEquals(1049,assertNotNull(own.player).returnBase)
        val foreign=stocks.copy(bases=stocks.bases.mapValues {(_,base)->base.copy(owner=Allegiance.Country(CountryId(2)))})
        assertEquals(NavigationRejection.ACCESS,navigation(Store(mapOf("pilot" to docked))).apply(request().copy(returnBase=1049),session(),foreign).rejection)
        assertEquals(NavigationRejection.CARD,navigation(Store(mapOf("pilot" to pilot))).apply(
            request().copy(destination=2488,unitId=pilot.unitId,captainCard=195),session(),stocks).rejection)
    }
}
