package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CampCountryTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),
        listOf(GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f),
            GalaxyBase(1001,2015,60,0,"Other",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private fun stocks(country:Int)=PlanetStocks(1,0,
        mapOf(1000 to PlanetStock(1000,Allegiance.Country(CountryId(country)),0,0,0,0),
            1001 to PlanetStock(1001,1,0,0,0,0)))

    @Test fun countriesAndIndependentCharactersNeverAliasSpecialFactions() {
        val camps=listOf(Camp(Power.EMPIRE,false),Camp(Power.EMPIRE,true),Camp(Power.ALLIANCE,false),
            Camp(CountryId(1)),Camp(CountryId(4)),Camp(CountryId(42)),Camp.PIRATES,Camp.FEZZAN,
            Camp.of(pilot.copy(country=null)),Camp.of(pilot.copy(characterId=10001,country=null)))
        assertEquals(camps.size,camps.toSet().size)
        assertEquals(Camp(CountryId(2)),Camp(Power.EMPIRE,false))
        assertEquals(CountryId(1),Camp(CountryId(1)).country)
        assertNull(Camp.FEZZAN.country);assertNull(Camp.PIRATES.country)
        assertFailsWith<IllegalArgumentException> {Camp.Owner.Independent(0)}
    }

    @Test fun actualCountriesGroupShipsBasesAndAshoreTroopsWithoutUsingTheirStyle() {
        val base=stocks(42)
        val units=UnitInventory(mapOf(UnitStack(32) to 1),mapOf(UnitStack(0) to 1))
        val outfit=OutfitStock(1,CountryId(42),OutfitKind.FLEET,1000,1,10000,units,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1001))),
            shipStyle=ShipStyleId("empire"))
        val inventory=base.copy(outfits=mapOf(1 to outfit),
            warehouses=mapOf(1 to OutfitWarehouse(1,CountryId(42),OutfitKind.FLEET,1000,1,shipStyle=ShipStyleId("empire"))),
            nextShipId=3)
        val players=listOf(pilot.copy(country=CountryId(42)),
            pilot.copy(characterId=10001,country=CountryId(1)),pilot.copy(characterId=10002,country=CountryId(4)),
            pilot.copy(characterId=10003,country=CountryId(42),shipStyle=ShipStyleId("alliance")),
            pilot.copy(characterId=10004,country=null),pilot.copy(characterId=10005,country=null))
        val forces=GridForces.collect(galaxy,1,players,inventory)
        val home=forces.getValue(2487)
        assertEquals(5,home.size)
        val own=home.single {it.camp==Camp(CountryId(42))}
        assertEquals(3L,own.units);assertTrue(own.base && own.nonSolo && own.ship)
        assertEquals(setOf(1,4,42),home.mapNotNull {it.camp.country?.value}.toSet())
        assertEquals(2,home.count {it.camp.owner is Camp.Owner.Independent})
        val ashore=forces.getValue(2015).single()
        assertEquals(Camp(CountryId(42)),ashore.camp);assertEquals(1L,ashore.units)
        assertTrue(ashore.nonSolo);assertFalse(ashore.ship || ashore.base)
        assertEquals(2,GridForces.collect(galaxy,2,players,null).values.sumOf {it.size},
            "old-generation navigators are excluded; only the two original national bases remain")
    }

    @Test fun campAndUnitLimitsCompareRealCountries() {
        fun force(country:Int,count:Long)=GridForces.Force(Camp(CountryId(country)),count,nonSolo=true,ship=true)
        val forces=mapOf(2488 to listOf(force(2,299),force(42,299)))
        val grid=GridForces.state(galaxy,2488,forces)
        assertEquals(GridEntryVerdict.CAMP_LIMIT,GridEntryRules.evaluate(grid,GridEntry(Camp(CountryId(43)),1,false)))
        assertEquals(GridEntryVerdict.UNIT_LIMIT,GridEntryRules.evaluate(grid,GridEntry(Camp(CountryId(42)),2,false)))
        assertEquals(GridEntryVerdict.ALLOWED,GridEntryRules.evaluate(grid,GridEntry(Camp(CountryId(42)),1,false)))
        assertTrue(TacticalContact.contested(forces.getValue(2488)))
    }

    @Test fun contactAndBattleEndDistinguishSameStyleCountriesAndUnaffiliatedIndividuals() {
        val first=pilot.copy(country=CountryId(42),grid=2488,mode=5,cpPaused=true)
        val other=pilot.copy(characterId=10001,grid=2488,mode=5,cpPaused=true)
        val inventory=stocks(42)
        val players=mutableMapOf("first" to first,"other" to other)
        assertTrue(TacticalContact.active(galaxy,first,players.values,inventory))
        TacticalContact.endUncontested(galaxy,players,inventory,10)
        assertTrue(players.getValue("first").cpPaused)
        players.remove("other")
        TacticalContact.endUncontested(galaxy,players,inventory,20)
        assertFalse(players.getValue("first").cpPaused)
        val independent=first.copy(country=null)
        assertTrue(TacticalContact.active(galaxy,independent,listOf(independent,other.copy(country=null)),inventory))
        assertFalse(TacticalContact.active(galaxy,independent,listOf(independent),inventory))
    }

    @Test fun aCountryNumberedOneIsNotNeutralFezzan() {
        val map=Galaxy(listOf(GalaxySystem(3,2300,4,"Fezzan",2)),
            listOf(GalaxyBase(1000,2300,60,0,TradeActions.FEZZAN_NAME,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2300})
        val initial=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,1,0,0,0,0)))
        val violated=initial.copy(social=initial.social.copy(trade=initial.social.trade.copy(violations=mapOf(2 to 0L))))
        assertEquals(1000,TradeActions.fezzan(map,violated))
        assertEquals(Camp.FEZZAN,GridForces.collect(map,1,emptyList(),violated).getValue(2300).single().camp)
        val national=violated.copy(bases=mapOf(1000 to violated.bases.getValue(1000).copy(owner=Allegiance.Country(CountryId(1)))))
        assertNull(TradeActions.fezzan(map,national))
        assertEquals(Camp(CountryId(1)),GridForces.collect(map,1,emptyList(),national).getValue(2300).single().camp)
    }

    @Test fun npcTravelUsesItsRealCountryDepotAndContactsAnotherEmpireStyleCountry() {
        val initial=stocks(42)
        val fleet=OutfitStock(1,CountryId(42),OutfitKind.FLEET,1000,1,
            units=UnitInventory(mapOf(UnitStack(32) to 1)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32)))),
            position=OutfitPosition(2487,1000),shipStyle=ShipStyleId("empire"))
        val inventory=initial.copy(bases=initial.bases+(1000 to initial.bases.getValue(1000).copy(supplies=100000)),
            outfits=mapOf(1 to fleet),warehouses=mapOf(1 to OutfitWarehouse(1,CountryId(42),OutfitKind.FLEET,1000,1,
                shipStyle=ShipStyleId("empire"))),deploymentInitialized=true,nextShipId=2)
        val players=mutableMapOf("pilot" to pilot.copy(grid=2488))
        val events=mutableListOf<NpcStrategicMovement>()
        val session=SessionSimulation(SessionRules.load()).exportState()
        val reserved=NpcStrategy.advance(session.copy(gameMillis=0),galaxy,players,inventory,events)
        assertEquals(2488,reserved.outfits.getValue(1).strategy?.destination)
        val arrived=NpcStrategy.advance(session.copy(gameMillis=3600000),galaxy,players,reserved,events)
        assertEquals(OutfitPosition(2488),arrived.outfits.getValue(1).position)
        assertEquals(CountryId(42),arrived.outfits.getValue(1).country)
        assertEquals(97000,arrived.bases.getValue(1000).supplies)
        assertEquals(2700,arrived.outfits.getValue(1).supplies)
        assertTrue(players.getValue("pilot").cpPaused)
        assertEquals(1,events.size)
        val foreign=inventory.copy(bases=inventory.bases+(1000 to inventory.bases.getValue(1000).copy(owner=Allegiance.Country(CountryId(2)))))
        val deniedEvents=mutableListOf<NpcStrategicMovement>()
        assertEquals(foreign,NpcStrategy.advance(session.copy(gameMillis=0),galaxy,players,foreign,deniedEvents))
        assertTrue(deniedEvents.isEmpty())
    }
}
