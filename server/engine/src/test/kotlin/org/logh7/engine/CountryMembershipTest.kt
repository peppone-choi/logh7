package org.logh7.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryMembershipTest {
    private val rules = SessionRules.load()

    @Test fun membershipUsesIdentityWithoutProjectingAnotherCountryToAnExistingPower() {
        val catalog = CountryCatalogLoader.load()
        val otherEmpire = catalog.countries.getValue(2).copy(id = 42, key = "other_empire")
        val expanded = CountryCatalog(catalog.calendars.values.toList(), catalog.families.values.toList(),
            catalog.countries.values.toList() + otherEmpire)
        assertEquals(expanded.countries.getValue(2).family, expanded.countries.getValue(42).family)
        assertEquals(expanded.countries.getValue(2).nativePower, expanded.countries.getValue(42).nativePower)
        val session = SessionSimulation(rules)
        assertEquals(Admission.ACCEPTED, session.join("pilot", CountryId(42), false, "old"))
        assertEquals(Admission.IDENTITY_CHANGED, session.join("pilot", Power.EMPIRE, false, "new"))
        assertFailsWith<IllegalStateException> { session.exportState().participants.getValue("pilot").power }
        session.disconnect("pilot", "old")
        val restored = SessionSimulation.restore(rules, session.exportState())
        assertEquals(Admission.ACCEPTED, restored.join("pilot", CountryId(42), false, "new"))
        restored.exclude("pilot")
        assertEquals(Admission.FACTION_CHANGED, restored.join("pilot", Power.EMPIRE, false))
        assertEquals(Admission.ORIGINAL_RETURN_FORBIDDEN, restored.join("pilot", CountryId(42), true))
        assertEquals(Admission.ACCEPTED, restored.join("pilot", CountryId(42), false))
    }

    @Test fun unaffiliatedExclusionIsDistinctFromMissingExclusion() {
        val session = SessionSimulation(rules)
        session.join("free", null, false)
        session.exclude("free")
        val state = session.exportState()
        assertTrue("free" in state.excluded)
        assertNull(state.excluded.getValue("free"))
        val restored = SessionSimulation.restore(rules, state)
        assertEquals(Admission.ORIGINAL_RETURN_FORBIDDEN, restored.join("free", null, true))
        assertEquals(Admission.FACTION_CHANGED, restored.join("free", CountryId(2), false))
        assertEquals(Admission.ACCEPTED, restored.join("free", null, false))
        assertFailsWith<IllegalArgumentException> {
            SessionSimulation.restore(rules, restored.exportState().copy(participants =
                mapOf("free" to Participant(CountryId(2), false))))
        }
        restored.join("traveler", null, false)
        restored.defect("traveler", CountryId(42))
        assertEquals(CountryId(42), SessionSimulation.restore(rules, restored.exportState())
            .exportState().participants.getValue("traveler").country)
    }

    @Test fun countryZeroAndNegativeIdsAreInvalidAndLegacyCountriesKeepTheirIdentity() {
        listOf(0, -1).forEach { assertFailsWith<IllegalArgumentException> { CountryId(it) } }
        assertEquals(CountryId(2), Power.EMPIRE.country)
        assertEquals(CountryId(3), Power.ALLIANCE.country)
        Power.entries.forEach { assertEquals(it, it.country.legacyPower()) }
        assertFailsWith<IllegalStateException> { (null as CountryId?).legacyPower() }
    }

    @Test fun legacyRuntimeRejectsUnsupportedMembershipBeforeRecoveryCanWrite() = runBlocking {
        for (country in listOf(CountryId(42), null)) {
            val session = SessionSimulation(rules)
            session.join("pilot", country, false, "old-connection")
            var commits = 0
            var closed = false
            val store = object : SessionDurability {
                override fun load() = DurableWorldState(session.exportState())
                override fun commit(action: SessionAction, transition: SessionTransition) { commits++ }
                override fun close() { closed = true }
            }
            assertFailsWith<IllegalStateException> { WorldEngine(this, rules, ticking = false, durability = store) }
            assertEquals(0, commits)
            assertTrue(closed)
        }
    }
}
