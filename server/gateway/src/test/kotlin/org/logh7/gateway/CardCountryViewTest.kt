package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.OutfitPartyMessages
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.CardCharacterMessages
import kotlin.test.*

/** Card holders (034e) and the display queries (032e modes 4/5/6) use actual countries. Author: Peppone Choi. */
class CardCountryViewTest {
    // 42: empire office family displayed as the alliance (native 3). 43: alliance family displayed as the empire (native 2).
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        (1048..1050).map {GalaxyBase(it,2487,80,0,"Home",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val world=WorldBootstrap(galaxy,catalog)
    private val person=CharacterMessages.Generate(4,10000,2,0,0,"Governor","Pilot",18,1,1,
        1000001,List(8) {50},0,0,0,20,3,0,"Ship",1)
    /** Governors (card 54): 10002 of the original empire at 1048 (legacy field), 10000 of country 42 at 1050 (stored seat). */
    private val stocks=PlanetStocks(1,0,mapOf(
        1048 to PlanetStock(1048,2,0,0,0,0,governorId=10002),
        1049 to PlanetStock(1049,3,0,0,0,0),
        1050 to PlanetStock(1050,Allegiance.Country(CountryId(42)),0,0,0,0)),
        strategic=StrategicState(offices=listOf(OfficeHolding(54,1050,10000,CountryId(42)))))
    // Office-card records carry the card family code: both governors use empire cards (2).
    private val display=mapOf(10000 to 2,10002 to 2)
    private val countries=mapOf(10000 to CountryId(42),10002 to CountryId(2))
    private fun containsId(response:ByteArray,id:Int)=(9..response.size-4).any {ByteBuffer.wrap(response).getInt(it)==id}

    @Test fun thePublicHolderLookupListsEveryCountryUsingTheCardWithItsOwnSeat() {
        val response=world.cardCharacters(54,stocks,{countries[it]},{id->person.copy(id=id.toLong(),power=display.getValue(id))})
        assertEquals(2,response[8].toInt(),"the empire's and country 42's governors, each with its own base")
        assertEquals(10000,ByteBuffer.wrap(response).getInt(9),"records stay sorted by person")
        assertTrue(containsId(response,10002))
        assertContentEquals(CardCharacterMessages.response(54,listOf(
            BootstrapMessages.character(person,0,0,offices=listOf(BootstrapMessages.OfficeCard(54,1050))),
            BootstrapMessages.character(person.copy(id=10002),0,0,offices=listOf(BootstrapMessages.OfficeCard(54,1048))))),response)
        // The alliance-family card of country 43 has no holder here; 42 never appears under it.
        assertEquals(0,world.cardCharacters(190,stocks,{countries[it]},{error("no holder")})[8].toInt())
    }

    @Test fun aHolderMustBelongToTheHoldingCountryNotMerelyShareItsDisplayCode() {
        // Actual country from the current profile: a mismatch is rejected even when the display code agrees.
        assertFails {world.cardCharacters(54,stocks,{
            if(it==10000)CountryId(43) else countries[it]},{id->person.copy(id=id.toLong(),power=display.getValue(id))})}
        // Unknown membership cannot publish a holding for a new country; the old overload accepts original countries only.
        assertFails {world.cardCharacters(54,stocks,{null},{id->person.copy(id=id.toLong(),power=display.getValue(id))})}
        assertFails {world.cardCharacters(54,stocks,characterById={id->person.copy(id=id.toLong(),power=display.getValue(id))})}
        assertFails {world.cardCharacters(54,stocks,characterById={id->person.copy(id=id.toLong(),power=3)})}
    }

    @Test fun removedCountriesAreLeftOutAndTheOriginalTwoKeepTheirResult() {
        val original=WorldBootstrap(galaxy,CountryCatalogLoader.load())
        val response=original.cardCharacters(54,stocks,{countries[it]},{id->person.copy(id=id.toLong(),power=2)})
        assertEquals(1,response[8].toInt());assertEquals(10002,ByteBuffer.wrap(response).getInt(9))
        // Same bytes as the original-countries lookup before this change (legacy governor field only).
        val legacy=stocks.copy(strategic=StrategicState(),bases=stocks.bases-1050)
        assertContentEquals(original.cardCharacters(54,legacy,characterById={id->person.copy(id=id.toLong(),power=2)}),response)
    }

    @Test fun publicCapitalOfficeHoldersKeepTheMovedSeatForBothOriginalCountries() {
        for((country,card) in listOf(2 to 56,2 to 57,3 to 192,3 to 193)) {
            val owner=CountryId(country)
            val before=stocks.copy(outfits=emptyMap(),strategic=StrategicState(capitals=mapOf(owner to 1050)),
                bases=stocks.bases.mapValues {(_,b)->b.copy(owner=Allegiance.Country(owner),governorId=0)})
            val policy=OrganizationalOfficePolicy(galaxy,catalog)
            val saved=policy.set(before,OfficeHolding(card,1050,10000,owner))
            val record=person.copy(power=country)
            val response=world.cardCharacters(card,saved,{owner},{record})
            assertContentEquals(CardCharacterMessages.response(card,listOf(BootstrapMessages.character(record,0,0,
                offices=listOf(BootstrapMessages.OfficeCard(card,1050))))),response)
        }
    }

    @Test fun displayQueriesEchoTheActualCountrysDisplayCodeAndRefuseUnknownCountries() {
        val colors=OutfitPartyMessages.Query(42,1,4);val metadata=OutfitPartyMessages.Query(42,1,5)
        for((country,code) in listOf(42 to 3,43 to 2,2 to 2,3 to 3)) {
            assertEquals(code,world.countryColors(colors,country)[15].toInt(),"$country colors")
            assertEquals(code,world.countryMetadata(metadata,country)[15].toInt(),"$country metadata")
        }
        for(unknown in listOf(99))for(call in listOf<()->Unit>({world.countryColors(colors,unknown)},{world.countryMetadata(metadata,unknown)},
            {world.facilityGuide(OutfitPartyMessages.Query(104801,16,6),unknown,stocks)}))assertFails {call()}
        assertEquals(3,world.facilityGuide(OutfitPartyMessages.Query(104801,16,6),42,stocks)[15].toInt())
    }
}
