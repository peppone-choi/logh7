package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.OutfitPartyMessages
import java.nio.ByteBuffer
import kotlin.test.*

class CountryAllocationViewTest {
    private val country=CountryId(42)
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_country","New country","new_empire",3,0,"empire"),
            CountryDefinition(43,"other_country","Other country","new_empire",3,0,"empire")))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1050,2487,80,0,"New capital",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val world=WorldBootstrap(galaxy,catalog)
    private val stocks=PlanetStocks(1,0,mapOf(1050 to PlanetStock(1050,Allegiance.Country(country),0,1000,200,300)),
        outfits=mapOf(1 to OutfitStock(1,country,OutfitKind.FLEET,1050,101,shipStyle=ShipStyleId("empire"))),
        warehouses=mapOf(101 to OutfitWarehouse(101,country,OutfitKind.FLEET,1050,1,shipStyle=ShipStyleId("empire"))),
        strategic=StrategicState(offices=listOf(OfficeHolding(24,1050,10000,country)),capitals=mapOf(country to 1050)))

    @Test fun editorAndWarehouseUseActualCountryWhileTheNativePacketUsesItsConfiguredDisplay() {
        val query=OutfitPartyMessages.Query(1,1050,1)
        val packet=world.outfitParty(query,42,stocks,10000)
        assertEquals(0x032f,ByteBuffer.wrap(packet).getShort(4).toInt())
        assertEquals(3,packet[15].toInt())
        val deployed=stocks.copy(outfits=stocks.outfits.mapValues {it.value.copy(position=OutfitPosition(2486))})
        assertEquals(1,world.outfitParty(query,42,deployed,10000)[14].toInt())
        assertFailsWith<IllegalArgumentException> {world.outfitParty(query.copy(mode=0),42,deployed,10000)}
        assertEquals(0x0327,ByteBuffer.wrap(world.outfitWarehouse(1050,1,10000,42,stocks)).getShort(4).toInt())
        for(id in listOf(2,3,43)) {
            assertFailsWith<IllegalArgumentException> {world.outfitParty(query,id,stocks,10000)}
            assertFailsWith<IllegalArgumentException> {world.outfitWarehouse(1050,1,10000,id,stocks)}
        }
        val legacy=stocks.copy(strategic=stocks.strategic.copy(offices=emptyList()),
            logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_FIRST))
        assertFailsWith<IllegalArgumentException> {world.outfitParty(query,42,legacy,10000)}
        assertFailsWith<IllegalArgumentException> {world.outfitWarehouse(1050,1,10000,42,legacy)}
    }

    @Test fun explicitCapitalCreatesTheCorrectHeadquartersWithoutUsingTheHistoricNameOrDisplayCode() {
        val facilities=galaxy.facilitiesAt(1050,stocks,catalog)
        assertTrue(facilities.any {it.kind==37});assertFalse(facilities.any {it.kind==36})
        val role=OrganizationalOfficePolicy(galaxy,catalog).role(24,country)!!
        val target=OrganizationalOfficePolicy(galaxy,catalog).target(role,1050,stocks)
        assertEquals(105038,target);assertTrue(facilities.any {it.id==target})
        assertTrue(facilities.flatMap {it.spots}.all {it.image==Places.image(it.kind,3)})
        val ordinary=stocks.copy(strategic=stocks.strategic.copy(capitals=emptyMap()))
        assertFalse(galaxy.facilitiesAt(1050,ordinary,catalog).any {it.kind==37})
        val lost=stocks.copy(bases=stocks.bases.mapValues {it.value.copy(owner=Allegiance.Country(CountryId(43)))})
        assertFalse(galaxy.facilitiesAt(1050,lost,catalog).any {it.kind==37})
    }
}
