package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Capital offices (56/57/192/193) of the original countries after their capital moves. Author: Peppone Choi. */
class MovedCapitalOfficeTest {
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire")))}
    private val odin=GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)
    private val heinessen=odin.copy(id=1177,grid=2015,name=Capital.ALLIANCE.baseName)
    /** New capitals with other names and grids. */
    private val empireNew=odin.copy(id=1050,grid=2490,name="Neue")
    private val allianceNew=odin.copy(id=1180,grid=2020,name="Newland")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3),
        GalaxySystem(5,2490,4,"East",2),GalaxySystem(6,2020,4,"West",3)),
        listOf(odin,heinessen,empireNew,allianceNew),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val policy=OrganizationalOfficePolicy(galaxy,catalog)
    private fun stocks(capitals:Map<CountryId,Int> = mapOf(CountryId(2) to 1050,CountryId(3) to 1180))=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,0,0,0),1177 to PlanetStock(1177,3,0,0,0,0),
        1050 to PlanetStock(1050,2,0,0,0,0),1180 to PlanetStock(1180,3,0,0,0,0)),
        strategic=StrategicState(capitals=capitals))
    private val cases=listOf(Triple(CountryId(2),56,1050),Triple(CountryId(2),57,1050),
        Triple(CountryId(3),192,1180),Triple(CountryId(3),193,1180))

    @Test fun capitalOfficesAppointedAtAMovedCapitalAreReadBackAtThatCapital() {
        for((country,card,seat) in cases) {
            val stored=policy.set(stocks(),OfficeHolding(card,seat,10000,country))
            assertEquals(listOf(OfficeHolding(card,seat,10000,country)),policy.holders(stored,card,country),"$card")
            assertEquals(listOf(OfficeHolding(card,seat,10000,country)),policy.held(stored,10000),"$card held")
            // The ordinary base lookups stay valid: no capital field lands on a base that is not an original capital.
            for(b in stored.bases.values)BaseOfficePolicy(galaxy).holders(b)
            val removed=policy.set(stored,OfficeHolding(card,seat,10000,country),remove=true)
            assertTrue(policy.holders(removed,card,country).isEmpty(),"$card removed")
        }
    }

    @Test fun theFormerCapitalsLeftoverOfficesNoLongerCount() {
        // Odin/Heinessen still carry their legacy fields after the capital moved away.
        val leftover=stocks().let {s->s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(capitalDefenseCommanderId=10001,guardInspectorId=10002))+
            (1177 to s.bases.getValue(1177).copy(capitalAdministratorId=10003,capitalDefenseCommanderId=10004)))}
        for((country,card,_) in cases)assertTrue(policy.holders(leftover,card,country).isEmpty(),"$card leftover")
        // While the original capital is still the capital, its legacy fields are read as before.
        val original=leftover.copy(strategic=StrategicState())
        assertEquals(listOf(OfficeHolding(56,1049,10001)),policy.holders(original,56,CountryId(2)))
        assertEquals(listOf(OfficeHolding(192,1177,10003,CountryId(3))),policy.holders(original,192,CountryId(3)))
    }

    @Test fun lostRebelOrUnregisteredCapitalsHoldNoCapitalOffice() {
        val stored=policy.set(stocks(),OfficeHolding(56,1050,10000,CountryId(2)))
        val lost=stored.copy(bases=stored.bases+(1050 to stored.bases.getValue(1050).copy(owner=Allegiance.Country(CountryId(3)))))
        assertTrue(policy.holders(lost,56,CountryId(2)).isEmpty(),"lost capital")
        val rebel=stored.copy(bases=stored.bases+(1050 to stored.bases.getValue(1050).copy(camp=1)))
        assertTrue(policy.holders(rebel,56,CountryId(2)).isEmpty(),"rebel-held capital")
        assertTrue(policy.holders(stored,56,CountryId(99)).isEmpty(),"unregistered country")
    }

    @Test fun anAddedCountrysStoredCapitalOfficesAreUnchanged() {
        val country=CountryId(42)
        val s=PlanetStocks(1,0,mapOf(1050 to PlanetStock(1050,Allegiance.Country(country),0,0,0,0)),
            strategic=StrategicState(capitals=mapOf(country to 1050)))
        val stored=policy.set(s,OfficeHolding(56,1050,10000,country))
        assertEquals(listOf(OfficeHolding(56,1050,10000,country)),stored.strategic.offices)
        assertEquals(0,stored.bases.getValue(1050).capitalDefenseCommanderId)
        assertEquals(listOf(OfficeHolding(56,1050,10000,country)),policy.holders(stored,56,country))
    }
}
