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

    @Test fun enemyDeploymentBlocksSoloShipEvenWhenPlanetIsFriendly() {
        val inventory=stocks(count=1)
        val enemy=inventory.copy(outfits=inventory.outfits.mapValues { it.value.copy(power=3) },
            warehouses=inventory.warehouses.mapValues { it.value.copy(power=3) })
        assertEquals(NavigationRejection.GRID,
            Navigation(galaxy,Store(),NavigationPolicy()).apply(request,session,enemy).rejection)
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
