package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationForcesTest {
    private val base = GalaxyBase(1000,2488,80,0,"Target",0,20f,86400,0,0f,2f)
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Start",2),GalaxySystem(4,2488,4,"Target",3)),
        listOf(base),Power.entries.associateWith { 2487 })
    private val session = SessionSimulation(SessionRules.load()).also {
        it.join("pilot",Power.EMPIRE,false,"connection")
    }.exportState()
    private val request = NavigationRequest("pilot",10000,Power.EMPIRE,"connection",session.generation,
        destination=2488,unitId=1010000,captainCard=59)
    private class Store : NavigationStore {
        var saved = emptyMap<String, Navigator>()
        override fun load() = saved
        override fun save(players: Map<String, Navigator>) { saved = players.toMap() }
    }
    private fun stocks(power: Int = 2, count: Int = 0, camp: Int = 0, crew: Int = 0,
        warehouse: Int = 0): PlanetStocks = PlanetStocks(session.generation,0,
        mapOf(1000 to PlanetStock(1000,power,camp,0,0,0)),
        if (count + crew == 0) emptyMap() else mapOf(1 to OutfitStock(1,2,OutfitKind.PATROL,1000,10,
            units=UnitInventory(if(count==0) emptyMap() else mapOf(UnitStack(0) to count),
                if(crew==0) emptyMap() else mapOf(UnitStack(3) to crew)))),
        mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1000,
            units=UnitInventory(if(warehouse==0) emptyMap() else mapOf(UnitStack(0) to warehouse)))))

    @Test fun deployedCapacityIncludesOfflineFlagshipsAndRejectsWithoutChargingAfterRestart() {
        val store = Store()
        var navigation = Navigation(galaxy,store,NavigationPolicy())
        val before = navigation.apply(request.copy(destination=null),session).player!!
        store.saved = store.saved + ("offline" to before.copy(characterId=10001,grid=2488))
        navigation = Navigation(galaxy,store,NavigationPolicy())
        val rejected = navigation.apply(request,session,stocks(count=299))
        assertEquals(NavigationRejection.GRID,rejected.rejection)
        assertEquals(before,store.saved["pilot"])
        navigation = Navigation(galaxy,store,NavigationPolicy())
        assertEquals(before,navigation.apply(request.copy(destination=null),session).player)
        val moved = navigation.apply(request,session,stocks(count=298,crew=1000,warehouse=1000)).player!!
        assertEquals(2488,moved.grid); assertEquals(before.mcp-40,moved.mcp)
        assertEquals(before.cruise-100,moved.cruise); assertEquals(before.moves+1,moved.moves)
    }

    @Test fun currentPlanetOwnerOverridesSeedAndNeutralOwnerDoesNotBecomeEnemy() {
        for (owner in listOf(0,2,3)) {
            val navigation = Navigation(galaxy,Store(),NavigationPolicy())
            val result = navigation.apply(request,session,stocks(power=owner))
            if(owner==3) assertEquals(NavigationRejection.GRID,result.rejection)
            else assertEquals(2488,result.player!!.grid)
        }
        assertEquals(NavigationRejection.GRID,
            Navigation(galaxy,Store(),NavigationPolicy()).apply(request,session).rejection)
        assertEquals(NavigationRejection.GRID,
            Navigation(galaxy,Store(),NavigationPolicy()).apply(request,session,stocks(camp=1)).rejection)
    }

    @Test fun onlyDeployedAlliedShipsGrantSoloWarpIntoHostileSystems() {
        val hostile=stocks(power=3)
        val fleet=stocks(power=3,count=1)
        val dockedAlly=Navigator(1,10001,Power.EMPIRE,2488,1000,1000,1000,mode=4,baseId=1000)
        val departedAlly=dockedAlly.copy(mode=5,baseId=0)
        val dockedFleet=fleet.copy(outfits=fleet.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2488,1000))})
        val departedFleet=fleet.copy(outfits=fleet.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2488))})

        fun attempt(inventory:PlanetStocks,ally:Navigator?,allowed:Boolean) {
            val store=Store()
            val setup=Navigation(galaxy,store,NavigationPolicy())
            val before=assertNotNull(setup.apply(request.copy(destination=null),session).player)
            if(ally!=null)store.saved=store.saved+("ally" to ally)
            val result=Navigation(galaxy,store,NavigationPolicy()).apply(request,session,inventory)
            if(allowed) {
                val arrived=assertNotNull(result.player)
                assertEquals(2488,arrived.grid)
                assertTrue(arrived.cpPaused)
            } else {
                assertEquals(NavigationRejection.GRID,result.rejection)
                assertEquals(before,store.saved.getValue("pilot"))
            }
        }
        attempt(hostile,dockedAlly,allowed=false)
        attempt(hostile,departedAlly,allowed=true)
        attempt(dockedFleet,null,allowed=false)
        attempt(departedFleet,null,allowed=true)
    }

    @Test fun enemyDeploymentBlocksSoloShipEvenWhenPlanetIsFriendly() {
        val inventory=stocks(count=1)
        val enemy=inventory.copy(outfits=inventory.outfits.mapValues { it.value.copy(country=CountryId(3),shipStyle=ShipStyleId.fromLegacy(3)) },
            warehouses=inventory.warehouses.mapValues { it.value.copy(country=CountryId(3),shipStyle=ShipStyleId.fromLegacy(3)) })
        assertEquals(NavigationRejection.GRID,
            Navigation(galaxy,Store(),NavigationPolicy()).apply(request,session,enemy).rejection)
    }

    @Test fun anOppositeCampOfficeDoesNotTurnALoneFlagshipIntoAFleet() {
        val target = 2587
        val now = session.gameMillis / 1000
        val rebel = Navigator(session.generation, 10001, Power.EMPIRE, target, 1000, 1000, 1000)
        val seed = stocks()
        val outfit = OutfitStock(1, 2, OutfitKind.FLEET, base.id, 10,
            commanderId = 10002, aideId = rebel.characterId, position = OutfitPosition(target))
        val plot = Plot(rebel.characterId, Power.EMPIRE.country, now, revolt = now + 1)
        val rebellion = CoupState(plots = mapOf(rebel.characterId to plot))
        val inventory = seed.copy(outfits = mapOf(outfit.id to outfit),
            warehouses = mapOf(10 to OutfitWarehouse(10, 2, OutfitKind.FLEET, base.id, outfitId = outfit.id)),
            social = seed.social.copy(coup = rebellion))
        val command = request.copy(destination = target)
        val store = Store()
        val pilot = assertNotNull(Navigation(galaxy, store, NavigationPolicy())
            .apply(command.copy(destination = null), session).player)
        store.saved = store.saved + ("rebel" to rebel)
        val navigation = Navigation(galaxy, store, NavigationPolicy())

        val sameCamp = rebellion.copy(
            plots = mapOf(rebel.characterId to plot.copy(members = listOf(outfit.commanderId))),
            loyalty = mapOf(outfit.id to CoupActions.LOYAL),
        )
        val blocked = navigation.apply(command, session, inventory.copy(social = seed.social.copy(coup = sameCamp)))
        assertEquals(NavigationRejection.GRID, blocked.rejection)
        assertEquals(pilot, store.saved.getValue("pilot"))

        val allowed = navigation.apply(command, session, inventory)
        assertNull(allowed.rejection)
        val arrived = assertNotNull(allowed.player)
        assertEquals(target, arrived.grid)
        assertEquals(pilot.mcp - 40, arrived.mcp)
        assertEquals(pilot.cruise - 100, arrived.cruise)
        assertTrue(arrived.cpPaused)
    }

    @Test fun soloWarpIgnoresEliminatedMobileForcesAtReservationAndArrival() {
        val target=2588
        val third=CountryId(42)
        val before=stocks(count=1)
        val inventory=before.copy(outfits=before.outfits.mapValues {(_,outfit)->
            outfit.copy(country=third,position=OutfitPosition(target))
        },warehouses=before.warehouses.mapValues {(_,warehouse)->warehouse.copy(country=third)})
        val ongoing=session.copy(countries=setOf(Power.EMPIRE.country,Power.ALLIANCE.country,third))
        val defeated=ongoing.copy(eliminated=mapOf(third to CountryDefeat(
            EndReason.CAPITAL_CAPTURED,session.gameMillis/1000,Power.ALLIANCE.country)))
        val command=request.copy(destination=target)
        val store=Store()
        val navigation=Navigation(galaxy,store,NavigationPolicy())
        val pilot=assertNotNull(navigation.apply(request.copy(destination=null),ongoing).player)
        assertEquals(NavigationRejection.GRID,navigation.apply(command,ongoing,inventory).rejection)
        assertEquals(pilot,store.saved["pilot"])

        val reserved=assertNotNull(navigation.apply(command,defeated,inventory).player)
        assertEquals(target,reserved.warpTarget)
        assertEquals(pilot.grid,reserved.grid)
        assertEquals(pilot.cruise,reserved.cruise)
        val due=defeated.copy(gameMillis=assertNotNull(reserved.warpReadyAt)*1000)
        val completed=navigation.apply(request.copy(destination=null),due,inventory)
        assertTrue(completed.completedWarp)
        assertNull(completed.warpCancellation)
        val arrived=assertNotNull(completed.player)
        assertEquals(target,arrived.grid)
        assertEquals(pilot.moves+1,arrived.moves)
        assertEquals(pilot.cruise-142,arrived.cruise)
        assertFalse(arrived.cpPaused)
        assertNull(arrived.warpTarget)

        val held=stocks().let {it.copy(bases=it.bases.mapValues {(_,base)->base.copy(owner=Allegiance.Country(third))})}
        assertEquals(NavigationRejection.GRID,
            Navigation(galaxy,Store(),NavigationPolicy()).apply(request,defeated,held).rejection)
    }

    @Test fun scheduledWarpAndTheFollowingWorldTickIgnoreEliminatedMobileForces() = runBlocking {
        val rules=SessionRules.load()
        val target=2588
        val third=CountryId(42)
        val before=stocks(count=1)
        val ghost=before.outfits.getValue(1).copy(country=third,position=OutfitPosition(target))
        val fleet=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(0))))
        var inventory=before.copy(outfits=mapOf(1 to ghost.copy(activeShips=fleet)),
            warehouses=before.warehouses.mapValues {(_,warehouse)->warehouse.copy(country=third)},
            strategic=before.strategic.copy(people=mapOf(10000 to StrategicPerson(abilities=List(8){50}))),
            deploymentInitialized=true,nextShipId=2)
        val ongoing=session.copy(countries=setOf(Power.EMPIRE.country,Power.ALLIANCE.country,third))
        val defeated=ongoing.copy(eliminated=mapOf(third to CountryDefeat(
            EndReason.CAPITAL_CAPTURED,session.gameMillis/1000,Power.ALLIANCE.country)))
        val store=Store()
        val nav=Navigation(galaxy,store,NavigationPolicy())
        val reserved=assertNotNull(nav.apply(request.copy(destination=target),defeated,inventory).player)
        val stale=reserved.copy(characterId=10001,grid=target,cpPaused=true,warpTarget=null,strategy=null)
        store.saved=store.saved+("observer" to stale)
        val offline=defeated.copy(participants=defeated.participants.mapValues {(_,p)->p.copy(online=false,connection=null)})
        val durable=object:SessionDurability {
            private var saved=DurableWorldState(offline,0)
            override fun load()=saved
            override fun commit(action:SessionAction,transition:SessionTransition) {saved=transition.state}
            override fun close() {}
        }
        val normal=CountryCatalogLoader.current()
        val definition=normal.countries.getValue(2).copy(id=third.value,key="test_country",name="Test Country")
        val catalog=CountryCatalog(normal.calendars.values.toList(),normal.families.values.toList(),
            normal.countries.values.toList()+definition)
        val revision=(CountryCatalogLoader.snapshot()?.revision ?: 0)+1
        CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision,catalog,emptyMap(),emptyMap()))
        val engine=try { WorldEngine(this,rules,ticking=false,durability=durable,galaxy=galaxy,navigationStore=store,
            stockStore=object:PlanetStockStore {
                override fun load()=inventory
                override fun save(stocks:PlanetStocks) {inventory=stocks}
            },worldStateCommitter=WorldStateCommitter {players,stocks->store.save(players);inventory=assertNotNull(stocks)}) }
        catch(failure:Throwable) {
            CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision+1,normal,emptyMap(),emptyMap()))
            throw failure
        }
        suspend fun tick(elapsed:Long):WorldSnapshot {
            engine.submit(WorldCommand.AdvanceTo(elapsed))
            val result=CompletableDeferred<WorldSnapshot>()
            engine.submit(WorldCommand.Quiesce({engine.snapshot()},result))
            return result.await()
        }
        try {
            val due=assertNotNull(reserved.warpReadyAt)*1000-offline.gameMillis
            val elapsed=(due+rules.speed-1)/rules.speed
            val arrival=tick(elapsed)
            val pilot=arrival.navigators.getValue("pilot")
            assertEquals(target,pilot.grid)
            assertNull(pilot.warpTarget)
            assertFalse(pilot.cpPaused)
            assertFalse(arrival.navigators.getValue("observer").cpPaused)
            val following=tick(elapsed+1_000)
            assertEquals(pilot,following.navigators.getValue("pilot"))
            assertEquals(ghost.position,following.planetStocks!!.outfits.getValue(1).position)
        } finally {
            try {engine.drainAndClose()}
            finally {CountryCatalogLoader.publish(CountryDefinitionSnapshot(revision+1,normal,emptyMap(),emptyMap()))}
        }
    }

    @Test fun worldActorPassesCurrentDeploymentAndPublishesTheSameStockForMapReports() = runBlocking {
        val navigationStore=Store()
        val stockStore=object : PlanetStockStore {
            var current=stocks(count=300).copy(deploymentInitialized=true)
            override fun load()=current
            override fun save(stocks: PlanetStocks) { current=stocks }
        }
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=navigationStore,stockStore=stockStore)
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,joined,"connection",1))
            assertEquals(Admission.ACCEPTED,joined.await())
            val initial=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request.copy(destination=null),initial))
            val before=initial.await().player!!
            val result=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request,result))
            assertEquals(NavigationRejection.GRID,result.await().rejection)
            assertEquals(before,engine.snapshot().navigators["pilot"])
            assertEquals(300L,GridForces.collect(galaxy,session.generation,
                engine.snapshot().navigators.values,engine.snapshot().planetStocks).getValue(2488).single().units)
        } finally { engine.drainAndClose() }
    }
}
