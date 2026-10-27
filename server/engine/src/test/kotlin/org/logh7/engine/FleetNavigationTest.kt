package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class FleetNavigationTest {
    private val home=GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)
    private val enemy=home.copy(id=1001,grid=2488,name="Enemy")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Enemy",2)),
        listOf(home,enemy),Power.entries.associateWith { 2487 })
    private val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
    private val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,unitId=1010000,captainCard=195)
    private class State(initial: PlanetStocks, player: Navigator) {
        var stocks=initial
        var players=mapOf("pilot" to player)
        var fail=false
        val nav=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) {this@State.players=players.toMap()}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) {this@State.stocks=stocks}
        }
        val commit=WorldStateCommitter { navigation, inventory ->
            if(fail)throw java.io.IOException("atomic save failed")
            players=navigation.toMap();stocks=checkNotNull(inventory)
        }
    }
    private fun state():State {
        val quay=galaxy.facilities.getValue(1000).single {it.kind==4}.spots.single {it.kind==6}
        return State(PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,100,100),
            1001 to PlanetStock(1001,2,0,100,100,100)),
            mapOf(1 to OutfitStock(1,3,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(UnitStack(89) to 10),mapOf(UnitStack(4) to 3,UnitStack(7) to 40)))),
            mapOf(10 to OutfitWarehouse(10,3,OutfitKind.FLEET,1000,1,
                units=UnitInventory(mapOf(UnitStack(89) to 200)))),deploymentInitialized=true),
            Navigator(1,10000,Power.ALLIANCE,2487,1000,1000,1000,baseId=1000,mode=4,spotId=quay.id))
    }
    private fun apply(state:State, vararg requests:NavigationRequest, blocked:Boolean=false,
        committer:WorldStateCommitter?=state.commit):List<NavigationResult> {
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        return requests.map { FleetNavigation.apply(it,session,galaxy,navigation,production,
            production.settle(1,session.gameMillis/1000),committer) {blocked} }
    }
    @Test fun departureAndFleetWarpMoveEveryDeployedUnitWithoutMovingTheDepotAndSurviveReload() {
        val state=state()
        val results=apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2488))
        assertTrue(results.all {it.rejection==null})
        assertEquals(OutfitPosition(2488),state.stocks.outfits.getValue(1).position)
        assertEquals(1000,state.stocks.warehouses.getValue(10).baseId)
        assertEquals(200,state.stocks.warehouses.getValue(10).units.ships.getValue(UnitStack(89)))
        val player=state.players.getValue("pilot")
        assertEquals(2488,player.grid);assertEquals(960,player.mcp);assertEquals(900,player.cruise)
        val forces=GridForces.collect(galaxy,1,state.players.values,state.stocks)
        assertEquals(14L,forces.getValue(2488).single {it.camp.power==Power.ALLIANCE}.units)
        assertEquals(0L,forces.getValue(2487).single().units)
        assertEquals(player,apply(state,request).single().player)
    }
    @Test fun wholeFleetCapacityIsCheckedBeforeAnyCostOrPositionChange() {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        state.stocks=ActiveShips.initialize(state.stocks.copy(outfits=state.stocks.outfits+
            (2 to OutfitStock(2,3,OutfitKind.PATROL,1001,20,
                units=UnitInventory(mapOf(UnitStack(89) to 287)))),
            warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1001))))
        val before=state.players.toMap();val inventory=state.stocks
        assertEquals(NavigationRejection.GRID,apply(state,request.copy(destination=2488)).single().rejection)
        assertEquals(before,state.players);assertEquals(inventory,state.stocks)
    }
    @Test fun atomicFailurePublishesNeitherNewFlagshipNorFleetPosition()=runBlocking {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit)
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            val ready=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request,ready));ready.await()
            val before=engine.snapshot()
            state.fail=true
            val moved=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request.copy(destination=2488),moved))
            assertFailsWith<java.io.IOException> {moved.await()}
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
        } finally {engine.drainAndClose()}
    }
    @Test fun reservationOrMissingAtomicWriterCannotSplitDepartureState() {
        for (locked in listOf(false,true)) {
            val state=state();val before=state.players.toMap()
            val result=apply(state,request.copy(place=PlaceMove.SwitchMode(5)),blocked=locked,
                committer=if(locked)state.commit else null).single()
            assertEquals(if(locked)NavigationRejection.BUSY else NavigationRejection.DISABLED,result.rejection)
            assertEquals(before,state.players);assertNull(state.stocks.outfits.getValue(1).position)
        }
    }
    @Test fun delayedDockArrivalCommitsFleetAndFlagshipAtTheSameBase() {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(place=PlaceMove.Dock(1000,4)))
        assertEquals(840,state.players.getValue("pilot").mcp)
        assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
        val late=session.copy(gameMillis=session.gameMillis+8*3600_000L)
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        val result=FleetNavigation.apply(request,late,galaxy,navigation,production,
            production.settle(1,late.gameMillis/1000),state.commit) {false}
        assertTrue(result.completedDock);assertEquals(1000,result.player!!.baseId)
        assertEquals(OutfitPosition(2487,1000),state.stocks.outfits.getValue(1).position)
        assertEquals(result.player,state.players["pilot"])
    }
    private fun addAide(state: State, cruise: Int = 1000) {
        val fleet = state.stocks.outfits.getValue(1)
        state.stocks = state.stocks.copy(outfits = state.stocks.outfits + (1 to fleet.copy(aideId = 10001)))
        state.players = state.players + ("aide" to state.players.getValue("pilot").copy(characterId = 10001,
            pcp = 800, mcp = 600, cruise = cruise))
    }
    @Test fun offlineAideFollowsDepartureWarpAndReloadWithoutPayingTheCommandersCp() {
        val state = state(); addAide(state)
        val cp = state.players.getValue("aide")
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2488))
        val aide = state.players.getValue("aide")
        assertEquals(2488,aide.grid); assertEquals(0,aide.baseId); assertEquals(5,aide.mode)
        assertEquals(0,aide.spotId); assertEquals(900,aide.cruise); assertEquals(2,aide.moves)
        assertEquals(cp.pcp,aide.pcp); assertEquals(cp.mcp,aide.mcp)
        assertEquals(15L,GridForces.collect(galaxy,1,state.players.values,state.stocks)
            .getValue(2488).single {it.camp.power==Power.ALLIANCE}.units)
        apply(state,request)
        assertEquals(aide,state.players.getValue("aide"))
    }
    @Test fun memberInPublicRoomOrDifferentGridOrAmbiguousOutfitIsNotTeleported() {
        for (scenario in 0..2) {
            val state = state(); addAide(state)
            val aide = state.players.getValue("aide")
            if(scenario==0) {
                val publicSpot=galaxy.facilities.getValue(1000).flatMap {it.spots}.first {it.access==SpotAccess.PUBLIC}
                state.players=state.players+("aide" to aide.copy(spotId=publicSpot.id))
            } else if(scenario==1) state.players=state.players+("aide" to aide.copy(grid=2488,baseId=0,mode=5,spotId=0))
            else state.stocks=state.stocks.copy(outfits=state.stocks.outfits+
                (2 to OutfitStock(2,3,OutfitKind.PATROL,1000,20,commanderId=10001)),
                warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1000)))
            val before=state.players.getValue("aide")
            apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
            assertEquals(before,state.players.getValue("aide"))
        }
    }
    @Test fun memberFuelAndDestinationCapacityAreCheckedForTheWholeGroup() {
        for(lowFuel in listOf(false,true)) {
            val state=state();addAide(state,if(lowFuel)50 else 1000)
            apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
            if(!lowFuel) state.stocks=state.stocks.copy(outfits=state.stocks.outfits+
                (2 to OutfitStock(2,3,OutfitKind.PATROL,1001,20,units=UnitInventory(mapOf(UnitStack(89) to 286)))),
                warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1001)))
            val before=state.players.toMap()
            assertEquals(if(lowFuel)NavigationRejection.CRUISE else NavigationRejection.GRID,
                apply(state,request.copy(destination=2488)).single().rejection)
            assertEquals(before,state.players)
        }
    }
    @Test fun dockCompletionMovesOfflineMemberAndAtomicFailureMovesNobody() {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        val before=state.players.toMap();state.fail=true
        assertFailsWith<java.io.IOException> {apply(state,request.copy(destination=2488))}
        assertEquals(before,state.players)
        state.fail=false
        apply(state,request.copy(place=PlaceMove.Dock(1000,4)))
        val late=session.copy(gameMillis=session.gameMillis+8*3600_000L)
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        FleetNavigation.apply(request,late,galaxy,navigation,production,
            production.settle(1,late.gameMillis/1000),state.commit) {false}
        assertEquals(1000,state.players.getValue("aide").baseId)
        assertEquals(4,state.players.getValue("aide").mode)
        assertEquals(600,state.players.getValue("aide").mcp)
    }
}
