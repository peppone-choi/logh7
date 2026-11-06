package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

/** Appointment/dismissal directories follow the profile's actual country and rank system. Author: Peppone Choi. */
class AppointmentDirectoryScopeTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f),GalaxyBase(1050,2487,80,0,"Other",0,20f,86400,0,0f,2f),
        GalaxyBase(1051,2487,80,0,"Third",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    // 42: empire office family, displayed as the alliance (native 3). 43: alliance family, displayed as the empire (native 2).
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val style=ShipStyleId.fromLegacy(Power.EMPIRE)
    private fun fleet(id:Int,country:CountryId,base:Int,commander:Int,aide:Int=0)=
        OutfitStock(id,country,OutfitKind.FLEET,base,id+100,commanderId=commander,aideId=aide,shipStyle=style)
    private fun warehouse(id:Int,country:CountryId,base:Int)=OutfitWarehouse(id+100,country,OutfitKind.FLEET,base,id,shipStyle=style)
    /** 42 fleet 200 (commander 10000) at 1049, original empire fleet 201 (commander 10002) at 1050, 43 fleet 202 (commander 10003) at 1051. */
    private fun stocks(aide:Int=0,people:Map<Int,StrategicPerson> = emptyMap()):PlanetStocks {
        val owners=listOf(Triple(200,CountryId(42),1049),Triple(201,CountryId(2),1050),Triple(202,CountryId(43),1051))
        return PlanetStocks(1,0,owners.associate {(_,c,b)->b to PlanetStock(b,Allegiance.Country(c),0,100,200,300)},
            outfits=mapOf(200 to fleet(200,CountryId(42),1049,10000,aide),201 to fleet(201,CountryId(2),1050,10002),
                202 to fleet(202,CountryId(43),1051,10003)),
            warehouses=owners.associate {(o,c,b)->(o+100) to warehouse(o,c,b)},
            strategic=StrategicState(people=people))
    }
    private fun scope(country:CountryId?)=AppointmentDirectoryScope(galaxy,country,catalog)

    @Test fun theProfileCountryNotTheDisplayCodeSelectsTheAideSeats() {
        val s=stocks()
        assertEquals(listOf(200),scope(CountryId(42)).available(s,10000,5)[43])
        assertNull(scope(CountryId(42)).available(s,10000,5)[179])
        // The same commander seen through the original empire or alliance code has no aide seat of 42.
        for(legacy in listOf(CountryId(2),CountryId(3)))assertTrue(scope(legacy).available(s,10000,5).values.none {200 in it})
        assertEquals(listOf(202),scope(CountryId(43)).available(s,10003,6)[179])
        assertNull(scope(CountryId(43)).available(s,10003,6)[43])
        // The original empire commander still sees only its own fleet (unchanged behaviour).
        assertEquals(listOf(201),scope(CountryId(2)).available(s,10002,5)[43])
        assertEquals(mapOf(43 to 1),scope(CountryId(42)).vacancies(s,mapOf(43 to listOf(200))))
        assertEquals(59,scope(CountryId(42)).captainCard());assertEquals(195,scope(CountryId(43)).captainCard())
        assertEquals(59,scope(CountryId(2)).captainCard());assertEquals(195,scope(CountryId(3)).captainCard())
        assertEquals(43,scope(CountryId(42)).role(43)?.card);assertNull(scope(CountryId(42)).role(179))
    }

    @Test fun dismissalsListOnlyTheActorCountrysAide() {
        val s=stocks(aide=10001)
        assertEquals(listOf(OfficeDismissalPolicy.Seat(43,10001,1049,200,CountryId(42))),
            scope(CountryId(42)).dismissals(s,10000,5).filter {it.card==43})
        assertTrue(scope(CountryId(2)).dismissals(s,10002,5).none {it.target==10001})
        assertTrue(scope(CountryId(3)).dismissals(s,10000,5).isEmpty())
        // A full seat leaves no vacancy in the appointment list.
        assertNull(scope(CountryId(42)).available(s,10000,5)[43])
    }

    @Test fun theCountryRankSystemDecidesAuthority() {
        // The server ladder records 少将 (6), below the empire-family commander ranks 2..5, so the aide card is gone.
        val demoted=stocks(people=mapOf(10000 to StrategicPerson(rank=6)))
        assertEquals(6,scope(CountryId(42)).rank(demoted,10000,5))
        assertNull(scope(CountryId(42)).available(demoted,10000,5)[43])
        // Without a server ladder entry the profile rank is used, as before.
        assertEquals(5,scope(CountryId(42)).rank(stocks(),10000,5))
    }

    @Test fun missingRemovedOrLostCountriesGetEmptyDirectories() {
        val s=stocks()
        for(country in listOf(null,CountryId(99))) {
            val none=scope(country)
            assertNull(none.country);assertTrue(none.available(s,10000,5).isEmpty());assertTrue(none.dismissals(s,10000,5).isEmpty())
            assertEquals(5,none.rank(s,10000,5))
        }
        val removed=AppointmentDirectoryScope(galaxy,CountryId(42),CountryCatalogLoader.load())
        assertNull(removed.country);assertTrue(removed.available(s,10000,5).isEmpty())
        val lost=s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(owner=Allegiance.Country(CountryId(2)))))
        assertTrue(scope(CountryId(42)).available(lost,10000,5).isEmpty())
    }
}
