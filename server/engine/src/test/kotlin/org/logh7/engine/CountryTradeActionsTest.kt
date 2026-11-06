package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Route ownership and tariffs follow actual countries, including shared display codes. Author: Peppone Choi. */
class CountryTradeActionsTest {
    private val a=CountryId(42);private val b=CountryId(43);private val c=CountryId(44)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2,shipStyleOverride="empire"),
            seed.countries.getValue(2).copy(id=44,key="c",name="C")))}
    private val galaxy=Galaxy((0..4).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..4).map {GalaxyBase(1000+it,2487+it,80,0,"B$it",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2489))
    private val pilot=Navigator(1,10000,a,2487,1000,1000,1000,baseId=1000,mode=4,shipStyle=ShipStyleId("alliance"))
    private val session=SessionSimulation(SessionRules.load()).also {it.join("a",a,false,"a")}.exportState()
    private val now=session.gameMillis/1000
    private fun stocks()=PlanetStocks(1,0,(0..4).associate {i->(1000+i) to PlanetStock(1000+i,
        Allegiance.Country(listOf(a,a,b,c,b)[i]),0,0,0,0,PlanetEconomy(200_000,100_000,60,60))},
        strategic=StrategicState(capitals=mapOf(a to 1000,b to 1002,c to 1003),offices=listOf(
            OfficeHolding(8,1000,10000,a),OfficeHolding(136,1002,10001,b))))
    private fun act(command:Int=TradeActions.ROUTE,card:Int=0,target:Int=1001,value:Int=0,s:PlanetStocks=stocks(),
        p:Navigator=pilot,state:SessionState=session):SocialResult {
        val request=StrategyActionRequest("a","a",p.characterId,p.country,1,command,card,1,target=target,value=value)
        return SocialActions.evaluate(galaxy,SocialRequest(request),state,p,s,countries=catalog)
    }

    @Test fun directoryAndExecutionUseCurrentCountryInsteadOfDisplayOrShipStyle() {
        assertEquals(listOf(1001),TradeActions.routeTargets(galaxy,stocks(),pilot,catalog))
        assertEquals(SocialRejection.TARGET,act(target=1004).rejection,"same display, different owner")
        val result=act();assertNull(result.rejection);assertEquals(840,result.player?.pcp);assertEquals(1000,result.player?.mcp)
        assertEquals(a,result.stocks!!.social.trade.routes.getValue(10000).country)
        val captured=stocks().copy(bases=stocks().bases+(1000 to stocks().bases.getValue(1000).copy(owner=Allegiance.Country(b))))
        assertTrue(TradeActions.routeTargets(galaxy,captured,pilot,catalog).isEmpty())
        assertEquals(SocialRejection.LOCATION,act(s=captured).rejection)
        assertTrue(TradeActions.routeTargets(galaxy,stocks(),pilot.copy(country=null),catalog).isEmpty())
        assertTrue(TradeActions.routeTargets(galaxy,stocks(),pilot.copy(generation=2),catalog).isEmpty())
    }

    @Test fun incomeCannotFollowBothEndpointsToANewOwnerAndStopsAtAnEnemyCamp() {
        val opened=act().stocks!!
        assertEquals(opened,TradeActions.settle(galaxy,opened,now-Justice.DAY,catalog),"a backward clock cannot unpay a route")
        val paid=TradeActions.settle(galaxy,opened,now+2*Justice.DAY,catalog)
        assertEquals(200,paid.bases.getValue(1000).treasury);assertEquals(200,paid.bases.getValue(1001).treasury)
        val both=paid.copy(bases=paid.bases.mapValues {(id,base)->if(id in setOf(1000,1001))base.copy(owner=Allegiance.Country(b)) else base})
        val stopped=TradeActions.settle(galaxy,both,now+3*Justice.DAY,catalog)
        assertTrue(stopped.social.trade.routes.isEmpty());assertEquals(both.bases,stopped.bases)
        val rebel=paid.copy(bases=paid.bases+(1001 to paid.bases.getValue(1001).copy(camp=1)))
        val noIncome=TradeActions.settle(galaxy,rebel,now+3*Justice.DAY,catalog)
        assertTrue(noIncome.social.trade.routes.isEmpty());assertEquals(rebel.bases,noIncome.bases)
        val ended=TradeActions.settle(galaxy,opened,now+30*Justice.DAY,catalog)
        assertEquals(700,ended.bases.getValue(1000).treasury);assertEquals(700,ended.bases.getValue(1001).treasury)
        assertTrue(ended.social.trade.routes.isEmpty())
    }

    @Test fun unboundLegacyRoutesCannotBeClaimedByAnAddedCountry() {
        val s=stocks().copy(social=SocialState(trade=TradeState(routes=mapOf(10000 to TradeRoute(10000,1000,1001,now+7*Justice.DAY,now)))))
        val rejected=TradeActions.settle(galaxy,s,now+Justice.DAY,catalog)
        assertTrue(rejected.social.trade.routes.isEmpty());assertEquals(s.bases,rejected.bases)
        val legacy=s.copy(bases=s.bases.mapValues {(id,base)->if(id in 1000..1001)base.copy(owner=Allegiance.Country(CountryId(2))) else base})
        val bound=TradeActions.settle(galaxy,legacy,now,catalog)
        assertEquals(CountryId(2),bound.social.trade.routes.getValue(10000).country)
        assertEquals(legacy.bases,bound.bases,"binding does not pay a partial day")
    }

    @Test fun eachTreasuryMinisterSetsOnlyTheirOwnCountriesTariffAndUsesItsOfficeSystem() {
        val set=act(TradeActions.TARIFF,8,value=20)
        assertNull(set.rejection);assertEquals(680,set.player?.mcp)
        assertEquals(mapOf(42 to 20),set.stocks!!.social.trade.tariffs);assertEquals(10,set.stocks!!.social.trade.tariff(43))
        val foreign=stocks().copy(strategic=stocks().strategic.copy(offices=listOf(OfficeHolding(8,1003,10000,c))))
        assertEquals(SocialRejection.CARD,act(TradeActions.TARIFF,8,value=20,s=foreign).rejection)
        val q=pilot.copy(characterId=10001,country=b,baseId=1002,grid=2489,shipStyle=ShipStyleId("empire"))
        val bs=session.copy(participants=session.participants.mapValues {(_,member)->member.copy(country=b)})
        val second=act(TradeActions.TARIFF,136,value=50,s=set.stocks!!,p=q,state=bs)
        assertNull(second.rejection);assertEquals(mapOf(42 to 20,43 to 50),second.stocks!!.social.trade.tariffs)
        assertEquals(SocialRejection.CARD,act(TradeActions.TARIFF,8,value=20,p=q,state=bs).rejection)
    }

    @Test fun invalidRequestsNeverConsumeCPOrChangeTradeState() {
        val s=stocks()
        val eliminated=session.copy(eliminated=mapOf(a to CountryDefeat(EndReason.CAPITAL_CAPTURED,1,b)))
        val cases=listOf(act(state=eliminated) to SocialRejection.SESSION,
            act(p=pilot.copy(country=null)) to SocialRejection.IDENTITY,
            act(state=session.copy(participants=session.participants.mapValues {(_,member)->member.copy(country=b)})) to SocialRejection.IDENTITY,
            act(TradeActions.TARIFF,8,value=51) to SocialRejection.TARGET,
            act(p=pilot.copy(pcp=159,mcp=319)) to SocialRejection.CP,
            act(TradeActions.TARIFF,8,value=10,p=pilot.copy(mcp=319,pcp=639)) to SocialRejection.CP)
        for((result,reason) in cases) {assertEquals(reason,result.rejection);assertFalse(result.changed);assertEquals(s,result.stocks)}
        val opened=act().stocks!!
        assertEquals(SocialRejection.BUSY,act(s=opened).rejection)
        val substitute=act(p=pilot.copy(pcp=159))
        assertNull(substitute.rejection);assertEquals(159,substitute.player?.pcp);assertEquals(680,substitute.player?.mcp)
    }
}
