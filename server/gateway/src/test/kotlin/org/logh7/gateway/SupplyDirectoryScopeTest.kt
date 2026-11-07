package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class SupplyDirectoryScopeTest {
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        (1049..1051).map {GalaxyBase(it,2487,80,0,"Home",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private fun outfit(id:Int,country:Int,base:Int,commander:Int,transport:Boolean=false):OutfitStock {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(id,UnitStack(32),290)))
        return OutfitStock(id,CountryId(country),if(transport)OutfitKind.TRANSPORT else OutfitKind.FLEET,base,id,
            commanderId=commander,units=UnitInventory(ships=ships.quantities()),activeShips=ships,
            shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE))
    }
    private val outfits=listOf(
        outfit(1,42,1049,10000,true),outfit(2,42,1049,10001),outfit(3,43,1050,10000,true),
        outfit(4,43,1050,10001),outfit(5,2,1051,10001),outfit(6,42,1050,10002),
        outfit(7,43,1049,10003),outfit(8,2,1049,10004))
    private val stocks=PlanetStocks(1,0,(1049..1051).zip(listOf(42,43,2)).associate {(id,country)->
        id to PlanetStock(id,Allegiance.Country(CountryId(country)),0,100,200,300)},outfits=outfits.associateBy {it.id},
        warehouses=outfits.associate {it.id to OutfitWarehouse(it.id,it.country,it.kind,it.baseId,it.id,shipStyle=it.shipStyle)},nextShipId=9)
    private fun scope(country:Int?,countries:CountryCatalog=catalog)=SupplyDirectoryScope(galaxy,country?.let(::CountryId),countries)

    @Test fun supplementUsesActualCountryEvenWithOtherCountriesAndSharedCommandersInTheInventory() {
        assertEquals(listOf(2),scope(42).supplement(stocks,10001,32).map {it.id})
        assertEquals(listOf(4),scope(43).supplement(stocks,10001,32).map {it.id})
        assertEquals(listOf(5),scope(2).supplement(stocks,10001,32).map {it.id})
        assertEquals(10,scope(42).supplement(stocks,10001,32).single().destroyed)
        assertTrue(scope(42).supplement(stocks,10001,33).isEmpty())
        assertTrue(scope(42).supplement(stocks,10002,32).isEmpty(),"foreign owned base")
    }

    @Test fun supplyListsOnlyOtherOutfitsOfTheSameCountryAtTheCommandedTransportBase() {
        assertEquals(listOf(2),scope(42).supply(stocks,10000,2487).map {it.id})
        assertEquals(listOf(4),scope(43).supply(stocks,10000,2487).map {it.id})
        assertTrue(scope(42).supply(stocks,10000,2488).isEmpty())
        assertTrue(scope(42).supply(stocks,10001,2487).isEmpty(),"fleet commander is not the transport commander")
    }

    @Test fun lostOrRebelBasesAndMissingMembershipProduceEmptyListsWithoutChangingStocks() {
        for(owner in listOf(Allegiance.Country(CountryId(43)),Allegiance.Neutral,Allegiance.Unspecified)) {
            val lost=stocks.copy(bases=stocks.bases+(1049 to stocks.bases.getValue(1049).copy(owner=owner)))
            assertTrue(scope(42).supply(lost,10000,2487).isEmpty())
            assertTrue(scope(42).supplement(lost,10001,32).isEmpty())
        }
        val rebel=stocks.copy(bases=stocks.bases+(1049 to stocks.bases.getValue(1049).copy(camp=1)))
        assertTrue(scope(42).supply(rebel,10000,2487).isEmpty())
        assertTrue(scope(42).supplement(rebel,10001,32).isEmpty())
        for(missing in listOf(null,99)) {
            assertTrue(scope(missing).supply(stocks,10000,2487).isEmpty())
            assertTrue(scope(missing).supplement(stocks,10001,32).isEmpty())
        }
        assertTrue(scope(42,CountryCatalogLoader.load()).supply(stocks,10000,2487).isEmpty())
        assertTrue(scope(42,CountryCatalogLoader.load()).supplement(stocks,10001,32).isEmpty())
        assertEquals(100,stocks.bases.getValue(1049).supplies)
        assertEquals(290,stocks.outfits.getValue(2).activeShips!!.values.single().boats)
    }

    @Test fun departedSourceOrTargetIsExcludedEvenWhenItsHomeBaseMatches() {
        for (id in listOf(1, 2)) {
            val departed = stocks.outfits.getValue(id).copy(position = OutfitPosition(2487))
            val inventory = stocks.copy(outfits = stocks.outfits + (id to departed))
            assertTrue(scope(42).supply(inventory, 10000, 2487).isEmpty())
        }
    }

    @Test fun visitingOutfitsAreListedAtTheirCurrentFriendlyPort() {
        val port = OutfitPosition(2487, 1051)
        val visitors = listOf(1, 2).associateWith { stocks.outfits.getValue(it).copy(position = port) }
        val inventory = stocks.copy(outfits = stocks.outfits + visitors,
            bases = stocks.bases + (1051 to stocks.bases.getValue(1051).copy(owner = Allegiance.Country(CountryId(42)))))
        assertEquals(listOf(2), scope(42).supply(inventory, 10000, 2487).map { it.id })
    }
}
