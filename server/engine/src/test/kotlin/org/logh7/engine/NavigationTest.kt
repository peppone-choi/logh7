package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationTest {
    @Test fun bothPersonalDepartureCommandsClearThePreviousStationedTacticalPosture() {
        val base=GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2)),listOf(base),Power.entries.associateWith {2487})
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"connection")}.exportState()
        for(command in listOf(PlaceMove.SwitchMode(5),PlaceMove.Dock(0,5))) {
            val store=Store()
            store.saved=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1000,mode=4,
                posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000)))
            val nav=Navigation(galaxy,store,NavigationPolicy())
            val departed=nav.apply(request.copy(unitId=1010000,captainCard=59,place=command),session).player!!
            assertNull(departed.posture);assertEquals(0,departed.baseId)
            assertEquals(departed,Navigation(galaxy,store,NavigationPolicy()).snapshot(1).getValue("pilot"))
        }
    }
    @Test fun nativeDepartureRequiresOwnCaptainAndFlagshipQuayAndPersistsBeforeSuccess() {
        val base = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2)), listOf(base),
            Power.entries.associateWith { 2487 })
        val store = Store()
        var navigation = Navigation(galaxy, store, NavigationPolicy())
        var session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
        fun act(move: PlaceMove) = navigation.apply(request.copy(unitId = 1010000, captainCard = 59, place = move), session)
        act(PlaceMove.Dock(1000, 4))
        session = session.copy(gameMillis = session.gameMillis + 8 * 3600_000L)
        navigation.apply(request, session)
        val port = galaxy.facilities.getValue(1000).single { it.kind == 4 }
        act(PlaceMove.Spot(port.spots.single { it.kind == 0 }.id, 0))
        assertEquals(NavigationRejection.EMBARKED, act(PlaceMove.SwitchMode(5)).rejection)
        val quay = act(PlaceMove.Spot(port.spots.single { it.kind == 6 }.id, 0)).player!!
        assertEquals(NavigationRejection.CARD, navigation.apply(request.copy(unitId = 1010000,
            captainCard = 195, place = PlaceMove.SwitchMode(5)), session).rejection)
        assertEquals(NavigationRejection.UNIT, navigation.apply(request.copy(unitId = 42,
            captainCard = 59, place = PlaceMove.SwitchMode(5)), session).rejection)
        assertEquals(NavigationRejection.BASE, act(PlaceMove.SwitchMode(4)).rejection)
        navigation = Navigation(galaxy, store, NavigationPolicy()) // Reconnect/restart at the quay.
        store.fail = true
        assertFailsWith<java.io.IOException> { act(PlaceMove.SwitchMode(5)) }
        assertEquals(quay, store.saved.getValue("pilot"))
        store.fail = false
        val departed = act(PlaceMove.SwitchMode(5)).player!!
        assertEquals(0, departed.baseId); assertEquals(0, departed.spotId); assertEquals(5, departed.mode)
        assertEquals(quay.pcp, departed.pcp); assertEquals(quay.mcp, departed.mcp)
        assertEquals(departed, store.saved.getValue("pilot"))
        assertEquals(NavigationRejection.BASE, act(PlaceMove.SwitchMode(5)).rejection)
        assertNotNull(navigation.apply(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), session).player)
    }
    @Test fun dockingAndRoomMovementRespectParentsAccessCpAndDurableState() {
        val base = GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
            listOf(base), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val store = Store()
        val navigation = Navigation(galaxy, store, NavigationPolicy())
        var session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
        fun act(move: PlaceMove) = navigation.apply(request.copy(unitId = 1010000, captainCard = 59, place = move), session)
        navigation.apply(request, session)
        assertEquals(NavigationRejection.BASE, act(PlaceMove.Dock(1234, 0)).rejection)
        val pending = act(PlaceMove.Dock(1000, 4)).player!!
        assertEquals(840, pending.mcp); assertEquals(0, pending.baseId)
        session = session.copy(gameMillis = session.gameMillis + 8 * 3600_000L)
        val docked = navigation.apply(request, session).player!!
        assertEquals(1000, docked.baseId)
        assertEquals(NavigationRejection.EMBARKED,
            navigation.apply(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), session).rejection)
        val office = galaxy.facilities.getValue(1000).single { it.kind == 0 }
        val lobby = office.spots.single { it.kind == 0 }; val meeting = office.spots.single { it.kind == 2 }
        val prison = office.spots.single { it.kind == 18 }
        assertEquals(990, act(PlaceMove.Spot(lobby.id, 0)).player!!.pcp)
        val arrived = act(PlaceMove.Spot(meeting.id, 0)).player!!
        assertEquals(985, arrived.pcp); assertEquals(arrived, store.saved.getValue("pilot"))
        val denied = act(PlaceMove.Spot(prison.id, 0))
        assertEquals(NavigationRejection.ACCESS, denied.rejection)
        assertEquals(arrived, denied.previous)
        assertEquals(arrived, store.saved.getValue("pilot"))
        assertEquals(NavigationRejection.SPOT, act(PlaceMove.Spot(1, 0)).rejection)
        assertEquals(NavigationRejection.EMBARKED, act(PlaceMove.Dock(0, 5)).rejection)
        assertNotNull(act(PlaceMove.Spot(0, 10000)).player)
        val quay = galaxy.facilities.getValue(1000).single { it.kind == 4 }.spots.single { it.kind == 6 }
        assertEquals(NavigationRejection.ACCESS, navigation.apply(request.copy(unitId = 42,
            place = PlaceMove.Spot(quay.id, 0)), session).rejection)
        assertNotNull(act(PlaceMove.Spot(quay.id, 0)).player)
        assertNotNull(act(PlaceMove.Spot(0, 10000)).player)
        val departed = act(PlaceMove.Dock(0, 5)).player!!
        assertEquals(0, departed.baseId); assertEquals(0, departed.spotId)
        assertEquals(departed, navigation.apply(request, session).player)
    }
    private fun galaxy() = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1)
    private class Store : NavigationStore {
        var saved = emptyMap<String, Navigator>()
        var fail = false
        override fun load() = saved
        override fun save(players: Map<String, Navigator>) {
            if (fail) throw java.io.IOException("disk failure")
            saved = players.toMap()
        }
    }
    @Test fun movesPersistAndReconnectCannotResetLocationOrCp() = runBlocking {
        val store = Store()
        val engine = WorldEngine(this, ticking = false, galaxy = galaxy(), navigationStore = store)
        val join = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("pilot", Power.EMPIRE, false, join, "connection", 1))
        assertEquals(Admission.ACCEPTED, join.await())
        suspend fun navigate(r: NavigationRequest): NavigationResult {
            val result = CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(r, result)); return result.await()
        }
        assertEquals(2487, navigate(request).player!!.grid)
        val moved = navigate(request.copy(destination = 2488, unitId = 1010000, captainCard = 59)).player!!
        assertEquals(2488, moved.grid); assertEquals(960, moved.mcp); assertEquals(900, moved.cruise)
        assertEquals(moved, store.saved["pilot"])
        assertEquals(moved, engine.snapshot().navigators["pilot"])
        assertEquals(moved, navigate(request).player)
        assertEquals(NavigationRejection.SESSION, navigate(request.copy(connection = "stale")).rejection)
        assertEquals(NavigationRejection.UNIT, navigate(request.copy(destination = 2489, unitId = 42, captainCard = 59)).rejection)
        engine.drainAndClose()
    }
    @Test fun failedSaveNeverPublishesMovedPosition() = runBlocking {
        val store = Store()
        val engine = WorldEngine(this, ticking = false, galaxy = galaxy(), navigationStore = store)
        val join = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("pilot", Power.EMPIRE, false, join, "connection", 1)); join.await()
        val initial = CompletableDeferred<NavigationResult>()
        engine.submit(WorldCommand.Navigate(request, initial)); initial.await()
        store.fail = true
        val result = CompletableDeferred<NavigationResult>()
        engine.submit(WorldCommand.Navigate(request.copy(destination = 2488, unitId = 1010000, captainCard = 59), result))
        assertFailsWith<java.io.IOException> { result.await() }
        assertEquals(2487, engine.snapshot().navigators.getValue("pilot").grid)
        assertEquals(EngineHealth.STORAGE_FAILED, engine.health())
        engine.drainAndClose()
    }
}
