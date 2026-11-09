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

    @Test fun seedCountryControlsReturnAndDockingWithoutAStockSnapshot() {
        val g=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,countryStarts=galaxy.countryStarts,
            initialOwners=mapOf(1049 to Allegiance.Country(CountryId(42))))
        val nav=Navigation(g,Store(mapOf("pilot" to pilot)),NavigationPolicy(),configuredCountries=catalog)
        val marked=nav.apply(request().copy(returnBase=1049),session())
        assertNull(marked.rejection);assertEquals(1049,marked.player?.returnBase)
        val accepted=nav.apply(request().copy(place=PlaceMove.Dock(1049,4),unitId=pilot.unitId,captainCard=59),session())
        assertNull(accepted.rejection);assertEquals(1049,accepted.player?.dockTarget)
        val at=assertNotNull(accepted.player?.strategy).jobs.single().completesAtGameMillis
        val docked=nav.apply(request(),session().copy(gameMillis=at))
        assertNull(docked.rejection);assertEquals(1049,docked.player?.baseId)
        assertEquals(CountryId(42),docked.player?.country);assertEquals(ShipStyleId("alliance"),docked.player?.shipStyle)
        val foreign=Navigation(g,Store(mapOf("pilot" to pilot.copy(country=CountryId(43)))),NavigationPolicy(),configuredCountries=catalog)
        assertEquals(NavigationRejection.ACCESS,foreign.apply(request(CountryId(43)).copy(returnBase=1049),session(CountryId(43))).rejection)
    }

    @Test fun initialOriginSkipsASeedPlanetMissingFromTheCurrentWorld() {
        val country=CountryId(42)
        val alternative=galaxy.bases.single().copy(id=1050,name="Alternative")
        val g=Galaxy(galaxy.systems,galaxy.bases+alternative,galaxy.starts,countryStarts=galaxy.countryStarts,
            initialOwners=mapOf(1049 to Allegiance.Country(country),1050 to Allegiance.Country(country)))
        val live=stocks.copy(bases=mapOf(1050 to PlanetStock(1050,Allegiance.Country(country),0,100,200,300)))
        fun assigned(world:PlanetStocks?)=assertNotNull(
            Navigation(g,Store(emptyMap()),NavigationPolicy(),configuredCountries=catalog).apply(request(),session(),world).player)
        assertEquals(1049,assigned(null).originBase)
        assertEquals(1050,assigned(live).originBase)
        assertEquals(0,assigned(live.copy(bases=emptyMap())).originBase)
    }

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
    @Test fun warpDiscountRequiresTheOperationCountryAndItsRegularCamp() {
        val state=session().let {it.copy(gameMillis=it.gameMillis+1000)};val now=state.gameMillis/1000
        val plan=StrategicOperation(1,10001,26,CountryId(42),2490,StrategicForce.SOLO,StrategicPurpose.SWEEP,1,now,
            StrategicPlanState.ISSUED,now,characters=setOf(pilot.characterId),issuer=10002)
        fun warp(country:CountryId,revolt:Long=0):Navigator {
            val plot=Plot(pilot.characterId,CountryId(42),now,revolt=revolt)
            val s=stocks.copy(strategic=StrategicState(operations=listOf(plan.copy(country=country))),
                social=stocks.social.copy(coup=CoupState(plots=mapOf(pilot.characterId to plot))))
            val result=navigation(Store(mapOf("pilot" to pilot.copy(grid=2486)))).apply(request().copy(destination=2489,
                unitId=pilot.unitId,captainCard=59),state,s)
            assertNull(result.rejection)
            return assertNotNull(result.player)
        }
        assertEquals(960,warp(CountryId(42)).mcp)
        assertEquals(880,warp(CountryId(43)).mcp)
        assertEquals(880,warp(CountryId(42),revolt=now).mcp)
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
    @Test fun aBaseMissingFromWorldStocksCannotAcceptOrCompleteDocking() {
        val g=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,countryStarts=galaxy.countryStarts,
            initialOwners=mapOf(1049 to Allegiance.Country(CountryId(42))))
        val missing=stocks.copy(bases=emptyMap())
        val dock=request().copy(unitId=pilot.unitId,captainCard=59,place=PlaceMove.Dock(1049,4))
        val initial=session()
        for(offline in listOf(false,true)) {
            val store=Store(mapOf("pilot" to pilot))
            val nav=Navigation(g,store,NavigationPolicy(),configuredCountries=catalog)
            assertEquals(NavigationRejection.BASE,nav.apply(dock,initial,missing).rejection)
            assertNull(store.saved.getValue("pilot").dockTarget)
            assertEquals(pilot.mcp,store.saved.getValue("pilot").mcp)
            val reserved=assertNotNull(nav.apply(dock,initial,stocks).player)
            val due=initial.copy(gameMillis=reserved.strategy!!.jobs.single().completesAtGameMillis)
            val settled=if(offline)nav.apply(NavigationRequest("pilot",pilot.characterId,pilot.country,"",1,offlineSettle=true),
                due.copy(participants=due.participants.mapValues {(_,member)->member.copy(online=false,connection=null)}),missing)
                else nav.apply(request(),due,missing)
            val saved=assertNotNull(settled.player)
            assertTrue(settled.completedDock) // Ends the reservation and clears the waiting HUD.
            assertNull(saved.dockTarget)
            assertEquals(0,saved.baseId)
            assertEquals(pilot.moves,saved.moves)
            assertEquals(reserved.mcpUsed,saved.mcpUsed)
            if(offline)assertEquals(reserved.mcp,saved.mcp)
        }
    }

    @Test fun missingWorldBaseBlocksFacilityEntryButDoesNotTrapAnExistingOccupant() {
        val g=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,countryStarts=galaxy.countryStarts,
            initialOwners=mapOf(1049 to Allegiance.Country(CountryId(42))))
        val quay=galaxy.facilitiesAt(1049,stocks,catalog).single {it.kind==4}.spots.single {it.kind==6}
        val inside=pilot.copy(baseId=1049,mode=4,spotId=quay.id)
        val store=Store(mapOf("pilot" to inside))
        val nav=Navigation(g,store,NavigationPolicy(),configuredCountries=catalog)
        val missing=stocks.copy(bases=emptyMap())
        val command=request().copy(unitId=pilot.unitId,place=PlaceMove.Spot(quay.id,0))
        val launch=command.copy(captainCard=59,place=PlaceMove.SwitchMode(5))
        assertEquals(NavigationRejection.EMBARKED,nav.apply(launch,session(),missing).rejection)
        assertEquals(NavigationRejection.BASE,nav.apply(command,session(),missing).rejection)
        assertEquals(inside.pcp,store.saved.getValue("pilot").pcp)
        val exited=assertNotNull(nav.apply(command.copy(place=PlaceMove.Spot(0,pilot.characterId)),session(),missing).player)
        assertEquals(0,exited.spotId)
        assertEquals(1049,exited.baseId)
        assertEquals(NavigationRejection.BASE,nav.apply(command,session(),missing).rejection)
        val departed=assertNotNull(nav.apply(launch,session(),missing).player)
        assertEquals(0,departed.baseId)
        assertEquals(5,departed.mode)
    }

    @Test fun rebelDockingAndQuayAccessFollowTheCurrentCamp() {
        val country=assertNotNull(pilot.country)
        val rebel=CoupState(mapOf(pilot.characterId to Plot(pilot.characterId,country,0,revolt=5,bases=setOf(1049))))
        val friendly=stocks.copy(bases=stocks.bases.mapValues {(_,base)->base.copy(camp=1)},social=stocks.social.copy(coup=rebel))
        val retaken=friendly.copy(bases=stocks.bases)
        val initial=session()
        val dock=request().copy(unitId=pilot.unitId,captainCard=59,place=PlaceMove.Dock(1049,4))
        val quay=galaxy.facilitiesAt(1049,friendly,catalog).single {it.kind==4}.spots.single {it.kind==6}
        for(world in listOf(friendly,retaken)) {
            val nav=navigation(Store(mapOf("pilot" to pilot)))
            val reserved=assertNotNull(nav.apply(dock,initial,friendly).player)
            val due=initial.copy(gameMillis=reserved.strategy!!.jobs.single().completesAtGameMillis)
            val settled=assertNotNull(nav.apply(request().copy(tactical=false),due,world).player)
            assertNull(settled.dockTarget)
            assertEquals(if(world==friendly)1049 else 0,settled.baseId)
        }
        val docked=pilot.copy(baseId=1049,mode=4)
        for(move in listOf(PlaceMove.Spot(quay.id,0),PlaceMove.SwitchMode(5))) {
            val action=dock.copy(place=move)
            assertNotNull(navigation(Store(mapOf("pilot" to docked))).apply(action,initial,friendly).player)
            assertEquals(NavigationRejection.ACCESS,
                navigation(Store(mapOf("pilot" to docked))).apply(action,initial,retaken).rejection)
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
