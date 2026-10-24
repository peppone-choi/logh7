package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationRecoveryTest {
    private val rules = SessionRules.load()
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "connection", 1, politics = 100, administration = 20)
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire"), GalaxySystem(4, 2015, 4, "Alliance")),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private val session = SessionSimulation(rules).also { it.join("pilot", Power.EMPIRE, false, "connection") }.exportState()
    private fun at(seconds: Long, online: Boolean = true) = session.copy(gameMillis = (rules.startSeconds + seconds) * 1000,
        participants = session.participants.mapValues { (_, p) -> p.copy(online = online, connection = if (online) "connection" else null) })
    private class Store(player: Navigator) : NavigationStore {
        var players = mapOf("pilot" to player)
        var fail = false
        override fun load() = players
        override fun save(players: Map<String, Navigator>) {
            if (fail) throw java.io.IOException("disk failure")
            this.players = players
        }
    }
    private fun store() = Store(Navigator(1, 10000, Power.EMPIRE, 2487, 100, 200, 1000, cpRecoveryAt = rules.startSeconds))
    @Test fun intervalRemaindersAbilityRatesOfflineTimeAndReopeningCannotDuplicateRecovery() {
        val store = store()
        val nav = Navigation(galaxy, store, NavigationPolicy())
        assertEquals(100, nav.apply(request, at(7199)).player!!.pcp)
        val recovered = nav.apply(request, at(7200)).player!!
        assertEquals(200, recovered.pcp); assertEquals(260, recovered.mcp)
        assertEquals(recovered, nav.apply(request, at(7200)).player)
        assertEquals(NavigationRejection.SESSION, nav.apply(request, at(21601, online = false)).rejection)
        val reopened = Navigation(galaxy, store, NavigationPolicy())
        val offline = reopened.apply(request, at(21601)).player!!
        assertEquals(400, offline.pcp); assertEquals(380, offline.mcp)
        assertEquals(rules.startSeconds + 21600, offline.cpRecoveryAt)
        assertEquals(offline, reopened.apply(request, at(28799)).player)
        assertEquals(500, reopened.apply(request, at(28800)).player!!.pcp)
        assertEquals(1000, reopened.apply(request, at(720000)).player!!.pcp)
        assertEquals(1000, reopened.apply(request, at(720000)).player!!.mcp)
    }
    @Test fun tacticalTimeIsExcludedAndTheIntervalRestartsOnReturnToStrategy() {
        val nav = Navigation(galaxy, store(), NavigationPolicy())
        assertTrue(nav.apply(request.copy(tactical = true), at(7100)).player!!.cpPaused)
        assertEquals(100, nav.apply(request, at(50000)).player!!.pcp)
        val resumed = nav.apply(request.copy(tactical = false), at(60000)).player!!
        assertFalse(resumed.cpPaused); assertEquals(100, resumed.pcp)
        assertEquals(100, nav.apply(request, at(67199)).player!!.pcp)
        assertEquals(200, nav.apply(request, at(67200)).player!!.pcp)
    }
    @Test fun legacyOrRewoundClocksDoNotInventHistoricalRecoveryAndFailedWritesDoNotPublishIt() {
        val store = Store(store().players.getValue("pilot").copy(cpRecoveryAt = null))
        val nav = Navigation(galaxy, store, NavigationPolicy())
        assertEquals(100, nav.apply(request, at(50000)).player!!.pcp)
        assertEquals(100, nav.apply(request, at(0)).player!!.pcp)
        store.fail = true
        assertFailsWith<java.io.IOException> { nav.apply(request, at(7200)) }
        assertEquals(100, nav.snapshot(1).getValue("pilot").pcp)
        store.fail = false
        assertEquals(200, nav.apply(request, at(7200)).player!!.pcp)
    }
    @Test fun recoveryIsAppliedBeforeChargingACommand() {
        val store = Store(store().players.getValue("pilot").copy(pcp = 0, mcp = 0))
        val nav = Navigation(galaxy, store, NavigationPolicy())
        val warp = request.copy(destination = 2488, unitId = 1010000, captainCard = 59)
        assertEquals(NavigationRejection.CP, nav.apply(warp, at(0)).rejection)
        val arrived = nav.apply(warp, at(7200)).player!!
        assertEquals(2488, arrived.grid); assertEquals(20, arrived.mcp); assertEquals(100, arrived.pcp)
    }
    @Test fun anUnrelatedBattleDoesNotBlockStrategyMovementButThePlayersOwnTacticalPhaseDoes() {
        val nav = Navigation(galaxy, store(), NavigationPolicy())
        val elsewhere = session.copy(battles = mapOf("another-grid" to BattleState(session.gameMillis, 0)))
        val warp = request.copy(destination = 2488, unitId = 1010000, captainCard = 59)
        assertNotNull(nav.apply(warp, elsewhere).player)
        nav.apply(request.copy(tactical = true), elsewhere)
        assertEquals(NavigationRejection.TACTICAL, nav.apply(warp.copy(destination = 2489), elsewhere).rejection)
    }
}
