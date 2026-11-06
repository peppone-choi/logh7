package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Actual capital ownership, shared display codes and independent hull styles. Author: Peppone Choi. */
class CountryFlagshipMarketTest {
    private val a=CountryId(42);private val b=CountryId(43)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2,shipStyleOverride="empire")))}
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"New capital",0,30f,86400,0,0f,2f),
        GalaxyBase(1051,2487,80,0,"Other",0,40f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {it.join("actor",a,false,"c");it.join("other",a,false,"d")}.exportState()
    private val now=session.gameMillis/1000
    private fun stocks()=PlanetStocks(1,0,(1049..1051).associateWith {id->PlanetStock(id,Allegiance.Country(if(id==1051)b else a),0,100,200,300)},
        strategic=StrategicState(capitals=mapOf(a to 1050,b to 1051),people=mapOf(10000 to StrategicPerson(rank=7,evaluation=50000),
            10001 to StrategicPerson(rank=7))),deploymentInitialized=true,nextShipId=100)
    private fun yard(s:PlanetStocks,base:Int=1050)=galaxy.facilitiesAt(base,s,catalog).single {it.kind==23}.spots.single {it.kind==21}.id
    private val player get()=Navigator(1,10000,a,2487,1000,1000,1000,baseId=1050,mode=4,spotId=yard(stocks()),
        hull=FlagshipHull(89,now),shipStyle=ShipStyleId("alliance"),supplies=35)
    private val other get()=player.copy(characterId=10001,hull=FlagshipHull(2,now,hp=89),shipStyle=ShipStyleId("empire"),supplies=27)
    private fun request(argument:Int=0,value:Int=0,target:Int=0)=StrategyActionRequest("actor","c",10000,a,1,96,0,7,
        value=value,target=target,argument=argument,navigators=mapOf("actor" to player,"other" to other))
    private fun act(r:StrategyActionRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player,state:SessionState=session,
        countries:CountryCatalog=catalog)=StrategyActions.evaluate(galaxy,r,state,p,s,countries)

    @Test fun aNewCountryBuysItsHeldHullStyleAtItsCurrentCapitalInsteadOfTheNativeCapital() {
        val s=stocks();assertTrue(FlagshipMarketCatalog.atYard(galaxy,s,player,catalog))
        assertFalse(FlagshipMarketCatalog.atYard(galaxy,s,player.copy(baseId=1049),catalog))
        val bought=act();assertNull(bought.rejection)
        assertEquals(a,bought.player?.country);assertEquals(ShipStyleId("alliance"),bought.player?.shipStyle)
        assertEquals(93,bought.player?.hull?.kind);assertEquals(920,bought.player?.pcp);assertEquals(1000,bought.player?.mcp)
        assertEquals(49950L,bought.stocks?.strategic?.people?.get(10000)?.evaluation)
        assertEquals(s.nationalTreasuries,bought.stocks?.nationalTreasuries);assertEquals(35,bought.player?.supplies)
    }

    @Test fun anExplicitLegacyCapitalAlsoOverridesTheOriginalNamedCapital() {
        val country=CountryId(2);val s=stocks().let {it.copy(bases=it.bases.mapValues {(_,base)->base.copy(owner=Allegiance.Country(country))},
            strategic=it.strategic.copy(capitals=mapOf(country to 1050)))}
        val p=player.copy(country=country)
        assertTrue(FlagshipMarketCatalog.atYard(galaxy,s,p,catalog))
        assertFalse(FlagshipMarketCatalog.atYard(galaxy,s,p.copy(baseId=1049,spotId=10494622),catalog))
    }

    @Test fun movedLostRebelMissingAndStaleCapitalsCannotBorrowAnotherCountrysYard() {
        val s=stocks()
        val invalid=listOf(s.copy(strategic=s.strategic.copy(capitals=mapOf(a to 1049,b to 1051))),
            s.copy(strategic=s.strategic.copy(capitals=mapOf(b to 1051))),
            s.copy(bases=s.bases+(1050 to s.bases.getValue(1050).copy(owner=Allegiance.Country(b)))),
            s.copy(bases=s.bases+(1050 to s.bases.getValue(1050).copy(camp=1))))
        for(source in invalid) {
            val result=act(s=source);assertEquals(StrategyActionRejection.LOCATION,result.rejection)
            assertEquals(source,result.stocks);assertEquals(player,result.player)
        }
        assertFalse(FlagshipMarketCatalog.atYard(galaxy,s,player.copy(baseId=1051,spotId=yard(s,1051)),catalog))
        assertFalse(FlagshipMarketCatalog.atYard(galaxy,s,player.copy(generation=2),catalog))
        assertFalse(FlagshipMarketCatalog.atYard(galaxy,s,player.copy(country=null),catalog))
        assertEquals(StrategyActionRejection.IDENTITY,act(countries=CountryCatalogLoader.load()).rejection)
        val eliminated=session.copy(eliminated=mapOf(a to CountryDefeat(EndReason.CAPITAL_CAPTURED,1,b)))
        assertEquals(StrategyActionRejection.SESSION,act(state=eliminated).rejection)
    }

    @Test fun onlyTheSameActualCountryCanConsentToAnExchangeAndKeepBothHullStyles() {
        val foreign=other.copy(country=b,baseId=1051,spotId=yard(stocks(),1051))
        assertEquals(StrategyActionRejection.LOCATION,act(request(1,target=10001).copy(navigators=mapOf("other" to foreign))).rejection)
        val offered=act(request(1,target=10001));assertNull(offered.rejection)
        val accept=request(2,target=10000).copy(account="other",connection="d",characterId=10001)
        val changed=act(accept,offered.stocks!!,other);assertNull(changed.rejection)
        assertEquals(a,changed.player?.country);assertEquals(ShipStyleId("alliance"),changed.player?.shipStyle)
        assertEquals(player.hull,changed.player?.hull);assertEquals(27,changed.player?.supplies);assertEquals(920,changed.player?.pcp)
        assertEquals(a,changed.navigators.getValue("actor").country)
        assertEquals(ShipStyleId("empire"),changed.navigators.getValue("actor").shipStyle)
        assertEquals(other.hull,changed.navigators.getValue("actor").hull)
        assertTrue(changed.stocks!!.flagshipMarket.offers.isEmpty())
    }

    @Test fun guardFormationUsesTheInjectedCountryStyleAndOnlyItsOwnWarehouse() {
        val s=stocks().copy(warehouses=mapOf(5 to OutfitWarehouse(5,b,OutfitKind.PATROL,1050,shipStyle=ShipStyleId("alliance"))))
        val bought=act(request(4,value=13),s);assertNull(bought.rejection)
        val after=bought.stocks!!;val guard=assertNotNull(GuardUnitActions.guardOf(after,10000))
        assertEquals(a,guard.country);assertEquals(ShipStyleId("alliance"),guard.shipStyle)
        assertEquals(6,guard.warehouseId);assertEquals(s.warehouses[5],after.warehouses[5])
        assertEquals(a,after.warehouses.getValue(6).country);assertEquals(ShipStyleId("alliance"),after.warehouses.getValue(6).shipStyle)
        assertEquals(30000L,after.strategic.people.getValue(10000).evaluation);assertEquals(920,bought.player?.pcp)
        val flipped=CountryCatalog(catalog.calendars.values.toList(),catalog.families.values.toList(),catalog.countries.values.map {
            if(it.id==42)it.copy(shipStyleOverride="empire") else it})
        val second=act(request(4,value=14),after,bought.player!!,countries=flipped);assertNull(second.rejection)
        assertEquals(ShipStyleId("alliance"),GuardUnitActions.guardOf(second.stocks!!,10000)?.shipStyle)
        val disband=act(request(5),second.stocks!!,second.player!!.copy(baseId=1049,spotId=0));assertNull(disband.rejection)
        assertNull(GuardUnitActions.guardOf(disband.stocks!!,10000))
        assertEquals(760,disband.player?.pcp)
    }

    @Test fun invalidChoiceAndInsufficientCpNeverSpendEvaluationOrCreateGuards() {
        val s=stocks();val poor=player.copy(pcp=79,mcp=159)
        for(r in listOf(request(),request(4,value=13))) {
            val result=act(r,s,poor);assertEquals(StrategyActionRejection.CP,result.rejection)
            assertEquals(s,result.stocks);assertEquals(poor,result.player)
        }
        assertEquals(StrategyActionRejection.COMMAND,act(request(4,value=0)).rejection)
    }
}
