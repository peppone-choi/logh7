package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryDefinitionsLiveTest {
    @Test fun existingConsumersReadTheNewCompleteDefinitionAndIgnoreAnOlderPoll() {
        val normal=CountryCatalogLoader.load()
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2)),listOf(
            GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val policy=PlanetProductionPolicy()
        val production=UnitProduction(galaxy,policy.units,policy.economy,policy.maximumStock)
        val initial=(CountryCatalogLoader.snapshot()?.revision ?: 0)+1
        val calendar=CountryCalendar("fixture","시험력",-310)
        val changed=CountryCatalog(normal.calendars.values.toList()+calendar,normal.families.values.toList(),normal.countries.values.map {
            if(it.id==2)it.copy(name="시험 제국",calendarOverride=calendar.key,shipStyleOverride="alliance") else it})
        fun version(revision:Long,catalog:CountryCatalog)=CountryDefinitionSnapshot(revision,catalog,emptyMap(),emptyMap())
        try {
            CountryCatalogLoader.publish(version(initial,changed))
            assertEquals("시험 제국",CountryCatalogLoader.current().countries.getValue(2).name)
            assertEquals(485,CountryCatalogLoader.current().year(2,795))
            assertTrue(production.forCountry(1049,CountryId(2)).all {it.unit.power==3})
            assertTrue(ProductionActions.kinds(CountryId(2),true).all {it.power==3})
            assertEquals(ShipStyleId("alliance"),GuardShips.styleFor(PlanetStocks(1,0,emptyMap()),10000,CountryId(2)))
            CountryCatalogLoader.publish(version(initial-1,normal))
            assertSame(changed,CountryCatalogLoader.current())
            assertEquals("imperial",normal.calendar(2)?.key,"immutable readers keep their captured snapshot")
        } finally {CountryCatalogLoader.publish(version(initial+1,normal))}
    }
}
