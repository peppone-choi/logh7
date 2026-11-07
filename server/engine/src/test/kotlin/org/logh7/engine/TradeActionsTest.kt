package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 무역: 항로 무역 일일 이익·만료, 선단 무역과 상대 관세, 관세율, 외교 정보 매입. 작성: Peppone Choi. */
class TradeActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2490,4,"Near",2),GalaxySystem(5,2015,4,"Haine",3),
        GalaxySystem(6,2300,4,"Fez",1)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),GalaxyBase(1050,2490,80,0,"Near",0,20f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f),GalaxyBase(1210,2300,80,0,"フェザーン",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val now=session.gameMillis/1000
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private fun stocks()=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,PlanetEconomy(500_000,300_000,60,60)),
        1050 to PlanetStock(1050,2,0,1,1,1,PlanetEconomy(200_000,100_000,60,60)),
        1177 to PlanetStock(1177,3,0,1,1,1,PlanetEconomy(500_000,300_000,70,80),treasury=777),1210 to PlanetStock(1210,1,0,1,1,1)),
        outfits=mapOf(7 to OutfitStock(7,2,OutfitKind.TRANSPORT,1049,11,commanderId=10000,supplies=1000,position=OutfitPosition(2487,1049))),
        warehouses=mapOf(11 to OutfitWarehouse(11,2,OutfitKind.TRANSPORT,1049,7)),nationalTreasuries=mapOf(CountryId(2) to 200_000_000L,CountryId(3) to 0L))
    private fun act(command:Int,card:Int,s:PlanetStocks,target:Int=0,value:Int=0,amount:Long=0)=SocialActions.evaluate(galaxy,SocialRequest(
        StrategyActionRequest("a","a",10000,Power.EMPIRE,1,command,card,1,target=target,value=value,amount=amount)),session,a,s,listOf(a),mapOf("a" to a))

    @Test fun aRoutePaysBothTreasuriesEachDayUntilItEnds() {
        assertEquals(SocialRejection.TARGET,act(TradeActions.ROUTE,0,stocks(),target=1177).rejection,"enemy base")
        val r=act(TradeActions.ROUTE,0,stocks(),target=1050)
        assertNull(r.rejection);assertEquals(840,r.player?.pcp);assertEquals(100,r.found)
        val s=assertNotNull(r.stocks)
        val day2=TradeActions.settle(galaxy,s,now+2*86400+5)
        assertEquals(200,day2.bases.getValue(1050).treasury);assertEquals(200,day2.bases.getValue(1049).treasury)
        val ended=TradeActions.settle(galaxy,day2,now+30*86400)
        assertEquals(700,ended.bases.getValue(1050).treasury,"seven days in all");assertTrue(ended.social.trade.routes.isEmpty())
    }
    @Test fun aConvoySellsSuppliesAndTheOtherSidesTariffTakesItsCut() {
        val s=policy.set(stocks(),OfficeHolding(44,7,10000))
        val tariff=assertNotNull(act(TradeActions.TARIFF,8,policy.set(s,OfficeHolding(8,1049,10000)),value=20).stocks)
        assertEquals(20,tariff.social.trade.tariff(2))
        val withAllianceTariff=s.copy(social=s.social.copy(trade=TradeState(tariffs=mapOf(3 to 20))))
        val sold=act(TradeActions.CONVOY,44,withAllianceTariff,amount=1000)
        assertNull(sold.rejection);assertEquals(1200,sold.found)
        val st=assertNotNull(sold.stocks)
        assertEquals(0,st.outfits.getValue(7).supplies);assertEquals(200_001_200L,st.nationalTreasuries[CountryId(2)]);assertEquals(300L,st.nationalTreasuries[CountryId(3)])
        assertEquals(SocialRejection.COOLDOWN,act(TradeActions.CONVOY,44,st.copy(outfits=st.outfits+(7 to st.outfits.getValue(7).copy(supplies=10))),amount=5).rejection)
    }
    @Test fun aConvoyQuoteIsReadOnlyAndTheSaleRejectsChangedProceedsBeforeCharging() {
        val s=policy.set(stocks(),OfficeHolding(44,7,10000))
        val trade=TradeState(tariffs=mapOf(3 to 20),pacts=mapOf(2 to now+100))
        val priced=s.copy(social=s.social.copy(trade=trade))
        val payer=a.copy(mcp=0,pcp=640)
        val identity=StrategyActionRequest("a","a",10000,Power.EMPIRE,1,TradeActions.CONVOY,44,1,target=3,amount=1000)
        val quote=TradeActions.evaluate(galaxy,SocialRequest(identity,phase=TradeActions.QUOTE_PHASE),session,payer,priced)
        assertEquals(TradeActions.ConvoyQuote(1440,20),quote.convoyQuote)
        assertFalse(quote.changed);assertSame(payer,quote.player);assertSame(priced,quote.stocks)

        val confirm=SocialRequest(identity.copy(argument=1440))
        val repriced=priced.copy(social=priced.social.copy(trade=trade.copy(tariffs=mapOf(3 to 30))))
        val stale=TradeActions.evaluate(galaxy,confirm,session,payer,repriced)
        assertEquals(SocialRejection.QUOTE_CHANGED,stale.rejection)
        assertFalse(stale.changed);assertSame(payer,stale.player);assertSame(repriced,stale.stocks)

        val sold=TradeActions.evaluate(galaxy,confirm,session,payer,priced)
        assertEquals(1440,sold.found);assertEquals(0,sold.player?.pcp);assertEquals(0,sold.player?.mcp)
        val after=assertNotNull(sold.stocks)
        assertEquals(0,after.outfits.getValue(7).supplies)
        assertEquals(200_001_440L,after.nationalTreasuries[CountryId(2)])
        assertEquals(360L,after.nationalTreasuries[CountryId(3)])
    }
    @Test fun buyingIntelligenceReportsTheEnemyCapitalOrSpendsNothingOnFailure() {
        val s=policy.set(stocks(),OfficeHolding(5,1049,10000))
        val r=act(TradeActions.DIPLOMACY,5,s,value=TradeActions.INTELLIGENCE)
        assertNull(r.rejection)
        if(r.outcome==1) {assertEquals(0,r.found,"777 marks in units of 10,000");assertEquals(70*256+80,r.report);assertEquals(100_000_000L,r.stocks?.nationalTreasuries?.get(CountryId(2)))}
        else assertEquals(200_000_000L,r.stocks?.nationalTreasuries?.get(CountryId(2)))
    }
    @Test fun shipsEnteringFezzanWithoutPassageViolateNeutralityAndPayTheEnemyTwelveTimes() {
        val inFezzan=stocks().let {it.copy(outfits=it.outfits+(7 to it.outfits.getValue(7).copy(position=OutfitPosition(2300),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(70,UnitStack(76),300))),units=UnitInventory(mapOf(UnitStack(76) to 1)))),nextShipId=71)}
        val passage=inFezzan.copy(social=inFezzan.social.copy(trade=TradeState(passages=mapOf(2 to now+100))))
        assertSame(passage,TradeActions.neutrality(galaxy,passage,now),"a corridor passage is not a violation")
        val first=TradeActions.neutrality(galaxy,inFezzan,now)
        assertEquals(now,first.social.trade.violations[2]);assertEquals(TradeActions.SUBSIDY,first.nationalTreasuries[CountryId(3)])
        assertTrue(first.social.trade.leaking(2,now+89*86400));assertFalse(first.social.trade.leaking(2,now+90*86400))
        val year=TradeActions.neutrality(galaxy,first,now+1000*86400)
        assertEquals(TradeActions.SUBSIDY*12,year.nationalTreasuries[CountryId(3)]);assertEquals(12,year.social.trade.subsidies[2])
        assertEquals(now,year.social.trade.violations[2],"only the first entry counts")
    }
    @Test fun aViolatorLosesFezzanMerchantsAndInvestment() {
        val pacted=stocks().let {it.copy(social=it.social.copy(trade=TradeState(pacts=mapOf(2 to now+100,3 to now+100))))}
        val inFezzan=pacted.copy(outfits=pacted.outfits+(7 to pacted.outfits.getValue(7).copy(position=OutfitPosition(2300),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(70,UnitStack(76),300))),units=UnitInventory(mapOf(UnitStack(76) to 1)))),nextShipId=71)
        val violated=TradeActions.neutrality(galaxy,inFezzan,now).social.trade
        assertEquals(mapOf(3 to now+100),violated.pacts,"the violator's commerce pact ends")
        val s=policy.set(policy.set(pacted.copy(social=pacted.social.copy(trade=violated)),OfficeHolding(44,7,10000)),OfficeHolding(5,1049,10000))
        assertEquals(SocialRejection.STALE,act(TradeActions.CONVOY,44,s,amount=10).rejection,"no Fezzan merchants")
        assertEquals(SocialRejection.STALE,act(TradeActions.DIPLOMACY,5,s,value=TradeActions.COMMERCE).rejection,"no new pact")
        assertNull(act(TradeActions.CONVOY,44,policy.set(pacted,OfficeHolding(44,7,10000)),amount=10).rejection,"before the violation")
    }
    @Test fun aViolatedFezzanIsContestedAndOneSidesLandedTroopsOccupyItEndingNeutrality() {
        val fezzan=galaxy.bases.single {it.id==1210}
        val ships=stocks().let {it.copy(outfits=it.outfits+(7 to it.outfits.getValue(7).copy(position=OutfitPosition(2300),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(70,UnitStack(76),300))),units=UnitInventory(mapOf(UnitStack(76) to 1)))),nextShipId=71)}
        assertTrue(GridForces.collect(galaxy,1,emptyList(),ships)[2300].orEmpty().none {it.camp==Camp.FEZZAN},"an untouched Fezzan is no party")
        val violated=TradeActions.neutrality(galaxy,ships,now)
        val forces=GridForces.collect(galaxy,1,emptyList(),violated)[2300].orEmpty()
        assertTrue(forces.any {it.camp==Camp.FEZZAN && it.base});assertTrue(TacticalContact.contested(forces))
        assertNull(BaseOccupationOperations.victor(violated,fezzan))
        val landed=violated.copy(outfits=violated.outfits+(7 to violated.outfits.getValue(7).copy(
            units=UnitInventory(mapOf(UnitStack(76) to 1),mapOf(UnitStack(0) to 1)),
            activeGround=ActiveGroundUnits(listOf(ActiveGroundUnit(71,UnitStack(0),base=1210))))),nextShipId=72)
        assertEquals(CountryId(2),BaseOccupationOperations.victor(landed,fezzan))
        val occupied=landed.copy(bases=landed.bases+(1210 to landed.bases.getValue(1210).copy(owner=Allegiance.fromLegacy(2))))
        assertNull(TradeActions.fezzan(galaxy,occupied),"occupation abandons neutrality")
        val later=TradeActions.neutrality(galaxy,occupied,now+40*86400)
        assertEquals(occupied.nationalTreasuries,later.nationalTreasuries,"no more subsidies after the occupation")
    }
    @Test fun diplomacyIsRefusedOnceFezzanIsOccupied() {
        val s=policy.set(stocks(),OfficeHolding(5,1049,10000)).let {it.copy(bases=it.bases+(1210 to it.bases.getValue(1210).copy(owner=Allegiance.fromLegacy(2))))}
        val r=act(TradeActions.DIPLOMACY,5,s,value=TradeActions.INTELLIGENCE)
        assertEquals(SocialRejection.TARGET,r.rejection);assertEquals(200_000_000L,r.stocks?.nationalTreasuries?.get(CountryId(2)))
    }
}
