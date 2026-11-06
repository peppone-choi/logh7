package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.OutfitPartyMessages
import java.nio.ByteBuffer
import kotlin.test.*

/** Ground (mode 2), battle (mode 3) and fleet unit (0x0330) views act for the actual country. Author: Peppone Choi. */
class GroundCountryViewTest {
    // 42: empire office family displayed as the alliance (native 3). 43: alliance family displayed as the empire (native 2).
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val base=GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private val world=WorldBootstrap(galaxy,catalog)
    private val style=ShipStyleId.fromLegacy(Power.EMPIRE)
    private fun ground(id:Int,country:CountryId,troop:Int,kind:Int,commander:Int,style:ShipStyleId=this.style):Pair<OutfitStock,OutfitWarehouse> {
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(troop,UnitStack(kind),base=1049)))
        return OutfitStock(id,country,OutfitKind.GROUND,1049,id,units=UnitInventory(troops=troops.quantities()),activeGround=troops,
            commanderId=commander,shipStyle=style) to OutfitWarehouse(id,country,OutfitKind.GROUND,1049,shipStyle=style)
    }
    /** Ground outfits on base 1049: 1 = country 42, 2 = country 43 (display 2), 3 = original empire 2. */
    private val stocks=listOf(ground(1,CountryId(42),11,2,10001),ground(2,CountryId(43),21,10,10002,ShipStyleId.fromLegacy(Power.ALLIANCE)),
        ground(3,CountryId(2),31,2,10003)).let {list->
        PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(CountryId(42)),0,0,0,0)),
            list.associate {it.first.id to it.first},list.associate {it.second.id to it.second},nextShipId=40)
    }
    private fun battle(country:Int,s:PlanetStocks=stocks,members:Map<Int,Int> = emptyMap(),
        memberCountries:Map<Int,CountryId?> = s.outfits.values.associate {it.commanderId to it.country}):Pair<List<Pair<Int,Int>>,List<Int>> {
        val packets=world.outfitBattle(OutfitPartyMessages.Query(0,1049,3),country,s,2487,
            characterCountryById={memberCountries[it]}) {id->
            members[id]?.let {power->org.logh7.protocol.CharacterMessages.Generate(1,id.toLong(),power,0,0,"지휘관","",20,1,1,0,
                List(8){50},0,0,0,20,1,0,"Ship",0)}}
        val b=ByteBuffer.wrap(packets[1]);b.position(6)
        val rows=List(b.get().toInt()) {
            val id=b.int;b.short;b.get();b.int;b.int;b.int;b.get();b.int;b.get();b.get();b.short;b.short;b.int;val commander=b.int;b.float
            id to commander
        }
        val header=ByteBuffer.wrap(packets[0]);header.position(25)
        val commanders=List(header.get().toInt()) {header.int.also {header.position(header.position()+3+8)}}.take(1)
        return rows to commanders
    }

    @Test fun theBattleShowsEveryRegisteredCountrysTroopsButOnlyTheViewersCommanders() {
        // Public strength: all three countries' troops, whatever their display code.
        for(viewer in listOf(42,43,2))assertEquals(listOf(11,21,31),battle(viewer).first.map {it.first},"$viewer")
        // Private command: only the viewer's actual country, never another country sharing its display code.
        assertEquals(listOf(11 to 10001,21 to 0,31 to 0),battle(42).first)
        assertEquals(listOf(11 to 0,21 to 10002,31 to 0),battle(43).first)
        assertEquals(listOf(11 to 0,21 to 0,31 to 10003),battle(2).first)
        // The commander roster names a member whose record carries the viewer country's display code.
        assertEquals(listOf(10002),battle(43,members=mapOf(10002 to 2)).second)
        assertTrue(battle(43,members=mapOf(10002 to 3)).second.isEmpty())
    }

    @Test fun groundLocationsOpenOnlyForTheOutfitsActualCountry() {
        val query=OutfitPartyMessages.Query(1,0,2)
        val own=world.outfitGround(query,42,stocks)
        assertEquals(3,ByteBuffer.wrap(own[0]).get(15).toInt(),"header carries 42's display code")
        assertEquals(1,ByteBuffer.wrap(own[1]).get(6).toInt())
        // Country 43 shares the empire display code with country 2; neither reads the other's outfit.
        assertFailsWith<IllegalArgumentException> {world.outfitGround(OutfitPartyMessages.Query(2,0,2),2,stocks)}
        assertFailsWith<IllegalArgumentException> {world.outfitGround(OutfitPartyMessages.Query(3,0,2),43,stocks)}
        assertFailsWith<IllegalArgumentException> {world.outfitGround(query,3,stocks)}
        assertEquals(1,ByteBuffer.wrap(world.outfitGround(OutfitPartyMessages.Query(3,0,2),2,stocks)[1]).get(6).toInt())
    }

    @Test fun fleetUnitsOpenOnlyForTheActualCountry() {
        val fleet=ActiveShips.initialize(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(CountryId(43)),0,0,0,0)),
            mapOf(1 to OutfitStock(1,CountryId(43),OutfitKind.FLEET,1049,10,units=UnitInventory(mapOf(UnitStack(0) to 10)),shipStyle=style)),
            mapOf(10 to OutfitWarehouse(10,CountryId(43),OutfitKind.FLEET,1049,1,shipStyle=style))))
        val id=fleet.outfits.getValue(1).activeShips!!.values.first().id
        assertEquals(1,ByteBuffer.wrap(world.outfitUnit(id,43,fleet)).get(6).toInt())
        assertFailsWith<IllegalArgumentException> {world.outfitUnit(id,2,fleet)}
        assertFailsWith<IllegalArgumentException> {world.outfitUnit(id,42,fleet)}
        assertFailsWith<IllegalArgumentException> {WorldBootstrap(galaxy,CountryCatalogLoader.load()).outfitUnit(id,43,fleet)}
    }

    @Test fun aCommanderWhoChangedToAnotherCountryWithTheSameDisplayCodeIsNotOffered() {
        val foreign=battle(43,members=mapOf(10002 to 2),memberCountries=mapOf(10002 to CountryId(2)))
        assertTrue(foreign.first.all {it.second==0});assertTrue(foreign.second.isEmpty())
        val missing=battle(42,members=mapOf(10001 to 3),memberCountries=emptyMap())
        assertTrue(missing.first.all {it.second==0});assertTrue(missing.second.isEmpty())
    }

    @Test fun unregisteredCountriesAreNeitherShownAsTheEmpireNorAllowedToRead() {
        // A record of a country missing from the catalogue (removed definition) is left out of the public roster.
        val removed=WorldBootstrap(galaxy,CountryCatalogLoader.load())
        val rows=ByteBuffer.wrap(removed.outfitBattle(OutfitPartyMessages.Query(0,1049,3),2,stocks,2487)[1]).get(6).toInt()
        assertEquals(1,rows,"only the original empire's troop remains")
        // A viewer whose country is not in the catalogue cannot get a display code or read any view.
        assertFails {world.outfitGround(OutfitPartyMessages.Query(1,0,2),99,stocks)}
        assertFails {world.outfitBattle(OutfitPartyMessages.Query(0,1049,3),99,stocks,2487)}
    }
}
