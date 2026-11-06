package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Actual foreign targets and conserved Fezzan subsidies. Author: Peppone Choi. */
class CountryFezzanActionsTest {
    private val a=CountryId(42);private val b=CountryId(43);private val c=CountryId(44)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2,shipStyleOverride="empire"),
            seed.countries.getValue(2).copy(id=44,key="c",name="C")))}
    private val galaxy=Galaxy((0..4).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..4).map {GalaxyBase(1000+it,2487+it,80,0,if(it==4)TradeActions.FEZZAN_NAME else "B$it",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
    private val pilot=Navigator(1,10000,a,2487,1000,1000,1000,baseId=1000,mode=4,shipStyle=ShipStyleId("alliance"))
    private val now=(1L..1000).first {at->(0..2).all {TradeActions.roll(10000,it,at)<75}}
    private val session=SessionSimulation(SessionRules.load()).also {it.join("a",a,false,"a")}.exportState().copy(gameMillis=now*1000)
    private fun stocks()=PlanetStocks(1,0,mapOf(
        1000 to PlanetStock(1000,Allegiance.Country(a),0,0,0,0),
        1001 to PlanetStock(1001,Allegiance.Country(b),0,0,0,0,PlanetEconomy(1000,600,70,80),treasury=7_770_000),
        1002 to PlanetStock(1002,Allegiance.Country(c),0,0,0,0,PlanetEconomy(1000,600,50,60),treasury=990_000),
        1003 to PlanetStock(1003,Allegiance.Country(a),0,0,0,0),1004 to PlanetStock(1004,Allegiance.Neutral,0,0,0,0)),
        outfits=mapOf(7 to OutfitStock(7,a,OutfitKind.TRANSPORT,1000,11,commanderId=10000,supplies=1000,
            position=OutfitPosition(2487,1000),shipStyle=ShipStyleId("alliance"))),
        warehouses=mapOf(11 to OutfitWarehouse(11,a,OutfitKind.TRANSPORT,1000,7,shipStyle=ShipStyleId("alliance"))),
        nationalTreasuries=mapOf(a to 200_000_000L,b to 10L,c to 20L),
        strategic=StrategicState(capitals=mapOf(a to 1000,b to 1001,c to 1002),offices=listOf(
            OfficeHolding(5,1000,10000,a),OfficeHolding(44,7,10000,a),OfficeHolding(132,1001,10001,b))))
    private fun act(command:Int=TradeActions.CONVOY,target:Int=43,value:Int=0,s:PlanetStocks=stocks(),state:SessionState=session):SocialResult {
        val request=StrategyActionRequest("a","a",10000,a,1,command,if(command==TradeActions.CONVOY)44 else 5,1,
            target=target,value=value,amount=1000,identities=listOf(StrategicIdentity(10000,a,1,0,List(8){100})))
        return SocialActions.evaluate(galaxy,SocialRequest(request),state,pilot,s,countries=catalog)
    }
    private fun ships(s:PlanetStocks=stocks())=s.copy(outfits=s.outfits+(7 to s.outfits.getValue(7).copy(position=OutfitPosition(2491),
        units=UnitInventory(mapOf(UnitStack(139) to 1)),activeShips=ActiveShipUnits(listOf(ActiveShipUnit(70,UnitStack(139),300))))),nextShipId=71)

    @Test fun convoysPayTheChosenCountrysTariffAndAmbiguousDefaultsNeverCharge() {
        val before=stocks().let {it.copy(social=SocialState(trade=TradeState(tariffs=mapOf(43 to 20,44 to 50))))}
        assertEquals(listOf(b,c),TradeActions.foreignTargets(galaxy,before,a,catalog))
        val result=act(s=before);assertNull(result.rejection);assertEquals(680,result.player?.mcp)
        val paid=result.stocks!!
        assertEquals(200_001_200L,paid.nationalTreasuries[a]);assertEquals(310L,paid.nationalTreasuries[b]);assertEquals(20L,paid.nationalTreasuries[c])
        assertEquals(0,paid.outfits.getValue(7).supplies)
        val other=act(target=44,s=before);assertEquals(750,other.found);assertEquals(770L,other.stocks!!.nationalTreasuries[c])
        val ambiguous=act(target=0,s=before);assertEquals(SocialRejection.TARGET,ambiguous.rejection);assertFalse(ambiguous.changed)
        assertEquals(before,ambiguous.stocks);assertEquals(pilot,ambiguous.player)
    }

    @Test fun foreignListsAndCommandsExcludeEliminatedAndLostCapitalsAndRejectBorrowedOutfits() {
        for(target in listOf(42,99))assertEquals(SocialRejection.TARGET,act(target=target).rejection)
        val defeated=session.copy(eliminated=mapOf(b to CountryDefeat(EndReason.CAPITAL_CAPTURED,1,c)))
        assertEquals(SocialRejection.TARGET,act(state=defeated).rejection)
        val lost=stocks().copy(bases=stocks().bases+(1001 to stocks().bases.getValue(1001).copy(owner=Allegiance.Country(c))))
        assertEquals(listOf(c),TradeActions.foreignTargets(galaxy,lost,a,catalog));assertEquals(SocialRejection.TARGET,act(s=lost).rejection)
        assertNull(act(target=0,s=lost).rejection,"single remaining counterparty keeps the legacy default")
        val borrowed=stocks().copy(outfits=stocks().outfits.mapValues {(_,o)->o.copy(country=c)},
            warehouses=stocks().warehouses.mapValues {(_,w)->w.copy(country=c)})
        assertEquals(SocialRejection.CARD,act(s=borrowed).rejection)
    }

    @Test fun intelligenceUsesTheSelectedCurrentCapitalAndOnlyItsLeakIsFree() {
        val paid=act(TradeActions.DIPLOMACY,value=TradeActions.INTELLIGENCE)
        assertNull(paid.rejection);assertEquals(1,paid.outcome);assertEquals(777,paid.found);assertEquals(70*256+80,paid.report)
        assertEquals(100_000_000L,paid.stocks!!.nationalTreasuries[a])
        val leaking=stocks().copy(social=SocialState(trade=TradeState(violations=mapOf(43 to now))))
        val free=act(TradeActions.DIPLOMACY,value=TradeActions.INTELLIGENCE,s=leaking)
        assertEquals(1,free.outcome);assertEquals(200_000_000L,free.stocks!!.nationalTreasuries[a]);assertEquals(680,free.player?.mcp)
        val unrelated=act(TradeActions.DIPLOMACY,target=44,value=TradeActions.INTELLIGENCE,s=leaking)
        assertEquals(99,unrelated.found);assertEquals(100_000_000L,unrelated.stocks!!.nationalTreasuries[a])
        val moved=leaking.copy(bases=leaking.bases+(1003 to leaking.bases.getValue(1003).copy(owner=Allegiance.Country(b),treasury=1_230_000)),
            strategic=leaking.strategic.copy(capitals=leaking.strategic.capitals+(b to 1003)))
        assertEquals(123,act(TradeActions.DIPLOMACY,value=TradeActions.INTELLIGENCE,s=moved).found)
        assertEquals(SocialRejection.TARGET,act(TradeActions.DIPLOMACY,target=0,value=TradeActions.INTELLIGENCE).rejection)
    }

    @Test fun commerceAndPassageBelongToTheActualCountryWithoutRequiringAForeignTarget() {
        val commerce=act(TradeActions.DIPLOMACY,target=0,value=TradeActions.COMMERCE)
        assertEquals(1,commerce.outcome);assertEquals(mapOf(42 to now+30*Justice.DAY),commerce.stocks!!.social.trade.pacts)
        val passage=act(TradeActions.DIPLOMACY,target=0,value=TradeActions.PASSAGE)
        assertEquals(1,passage.outcome);assertEquals(mapOf(42 to now+30*Justice.DAY),passage.stocks!!.social.trade.passages)
        val exempt=ships(passage.stocks!!)
        assertSame(exempt,TradeActions.neutrality(galaxy,exempt,now,catalog))
    }

    @Test fun aViolationSplitsOneSubsidyAcrossSurvivorsAndNeverPaysTwice() {
        val start=ships().let {it.copy(nationalTreasuries=emptyMap(),social=SocialState(trade=TradeState(pacts=mapOf(42 to now+1000))))}
        val first=TradeActions.neutrality(galaxy,start,now,catalog)
        assertEquals(mapOf(b to 15_000_000_000L,c to 15_000_000_000L),first.nationalTreasuries)
        assertEquals(now,first.social.trade.violations[42]);assertEquals(1,first.social.trade.subsidies[42]);assertTrue(first.social.trade.pacts.isEmpty())
        assertSame(first,TradeActions.neutrality(galaxy,first,now,catalog))
        val second=TradeActions.neutrality(galaxy,first,now+30*Justice.DAY,catalog,setOf(b))
        assertEquals(15_000_000_000L,second.nationalTreasuries[b]);assertEquals(45_000_000_000L,second.nationalTreasuries[c])
        val originDefeated=TradeActions.neutrality(galaxy,second,now+60*Justice.DAY,catalog,setOf(a,b))
        assertEquals(75_000_000_000L,originDefeated.nationalTreasuries[c],"Fezzan still pays its recorded installments")
        assertFalse(first.social.trade.leaking(42,now+90*Justice.DAY))
    }

    @Test fun integerRemaindersAndMissedTicksKeepExactlyTwelvePaymentsAndTheSameDistribution() {
        val extra=(45..49).map {id->catalog.countries.getValue(44).copy(id=id,key="c$id")}
        val definitions=CountryCatalog(catalog.calendars.values.toList(),catalog.families.values.toList(),catalog.countries.values.toList()+extra)
        val added=(0..4).map {i->GalaxyBase(1005+i,2492+i,80,0,"X$i",0,20f,86400,0,0f,2f)}
        val g=Galaxy(galaxy.systems+added.mapIndexed {i,x->GalaxySystem(8+i,x.grid,4,"X$i",2)},galaxy.bases+added,galaxy.starts)
        val s=ships().let {it.copy(nationalTreasuries=emptyMap(),bases=it.bases+added.mapIndexed {i,x->x.id to PlanetStock(x.id,Allegiance.Country(CountryId(45+i)),0,0,0,0)},
            strategic=it.strategic.copy(capitals=it.strategic.capitals+added.mapIndexed {i,x->CountryId(45+i) to x.id}))}
        val first=TradeActions.neutrality(g,s,now,definitions)
        assertEquals(TradeActions.SUBSIDY,first.nationalTreasuries.values.sum())
        assertEquals(4_285_714_286L,first.nationalTreasuries[b]);assertEquals(4_285_714_285L,first.nationalTreasuries[CountryId(49)])
        val batched=TradeActions.neutrality(g,first,now+1000*Justice.DAY,definitions)
        var stepped=first
        for(month in 1L..11)stepped=TradeActions.neutrality(g,stepped,now+month*30*Justice.DAY,definitions)
        assertEquals(stepped,batched);assertEquals(TradeActions.SUBSIDY*12,batched.nationalTreasuries.values.sum())
        assertEquals(12,batched.social.trade.subsidies[42]);assertSame(batched,TradeActions.neutrality(g,batched,now+2000*Justice.DAY,definitions))
    }

    @Test fun missingRecipientsAndReversedTimeCannotCreateDeferredOrDuplicateMoney() {
        val start=ships().let {it.copy(nationalTreasuries=emptyMap())}
        val nobody=TradeActions.neutrality(galaxy,start,now,catalog,setOf(b,c))
        assertTrue(nobody.nationalTreasuries.isEmpty());assertEquals(1,nobody.social.trade.subsidies[42])
        assertSame(nobody,TradeActions.neutrality(galaxy,nobody,now,catalog),"restoring a recipient cannot replay a completed installment")
        assertSame(nobody,TradeActions.neutrality(galaxy,nobody,now-1,catalog))
        val route=start.copy(social=SocialState(trade=TradeState(routes=mapOf(10000 to TradeRoute(10000,1000,1003,now+7*Justice.DAY,now,a)))))
        val stopped=TradeActions.settle(galaxy,route,now+Justice.DAY,catalog,setOf(a,b,c))
        assertTrue(stopped.social.trade.routes.isEmpty());assertEquals(route.bases,stopped.bases)
    }
}
