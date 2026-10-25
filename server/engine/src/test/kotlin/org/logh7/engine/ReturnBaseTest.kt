package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class ReturnBaseTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Empire", 2), GalaxySystem(4, 2015, 4, "Alliance", 3)),
        listOf(GalaxyBase(1048, 2487, 80, 0, "Soest", 0, 20f, 86400, 0, 0f, 2f),
            GalaxyBase(1050, 2487, 30, 0, "Gas", 0, 40f, 86400, 0, 0f, 2f),
            GalaxyBase(2015, 2015, 60, 0, "Alliance", 0, 20f, 86400, 0, 0f, 2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private val request = NavigationRequest("pilot", 10000, Power.EMPIRE, "live", 1)
    private val session = SessionState(1, 0, 0, 0,
        mapOf("pilot" to Participant(Power.EMPIRE, false, true, "live")), emptyMap(), emptyMap(), null)
    private class Store : NavigationStore {
        var data = emptyMap<String, Navigator>()
        var fail = false
        override fun load() = data
        override fun save(players: Map<String, Navigator>) {
            if (fail) throw java.io.IOException("disk failure")
            data = players
        }
    }
    @Test fun selectionPersistsWithoutMovingOrSpendingAndCanBeCleared() {
        val store = Store(); var nav = Navigation(galaxy, store, NavigationPolicy())
        val before = nav.apply(request, session).player!!
        val selected = nav.apply(request.copy(returnBase = 1048), session).player!!
        assertEquals(before.copy(returnBase = 1048), selected)
        nav = Navigation(galaxy, store, NavigationPolicy())
        assertEquals(selected, nav.apply(request, session).player)
        assertEquals(before, nav.apply(request.copy(returnBase = 0), session).player)
    }
    @Test fun invalidWorldOwnershipIdentityAndSaveFailureCannotChangeSelection() {
        val store = Store(); val nav = Navigation(galaxy, store, NavigationPolicy())
        nav.apply(request.copy(returnBase = 1048), session)
        val before = store.data
        for (target in listOf(65535, 1050, 2015)) assertNotNull(nav.apply(request.copy(returnBase = target), session).rejection)
        assertEquals(NavigationRejection.IDENTITY, nav.apply(request.copy(characterId = 10001, returnBase = 1048), session).rejection)
        assertEquals(NavigationRejection.SESSION, nav.apply(request.copy(connection = "old", returnBase = 1048), session).rejection)
        val changed = PlanetStocks(1, 0, mapOf(1048 to PlanetStock(1048, 3, 0, 0, 0, 0)))
        assertEquals(NavigationRejection.ACCESS, nav.apply(request.copy(returnBase = 1048), session, changed).rejection)
        assertEquals(NavigationRejection.BASE, nav.apply(request.copy(returnBase = 1048), session,
            PlanetStocks(1, 0, emptyMap())).rejection)
        assertEquals(before, store.data)
        store.fail = true
        assertFailsWith<java.io.IOException> { nav.apply(request.copy(returnBase = 0), session) }
        assertEquals(before, nav.snapshot(1))
    }
}
