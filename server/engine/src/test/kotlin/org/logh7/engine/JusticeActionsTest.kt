package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-25 逮捕·処断: 허가 리스트, 1회 체포권, 체포 판정·구금, 처단과 자동 처리. 작성: Peppone Choi. */
class JusticeActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {sim->listOf("a","b","c").forEach {sim.join(it,Power.EMPIRE,false,it)}}.exportState()
    private val spot=10490003
    // a: 内務尚書 7 (minister), b: an arrester, c: a colonel (rank 8) to be arrested.
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=spot)
    private val b=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=spot)
    private val c=Navigator(1,10002,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=spot)
    private val accounts=mapOf("a" to a,"b" to b,"c" to c)
    private val ids=listOf(StrategicIdentity(10000,2,1,0,List(8){50}),StrategicIdentity(10001,2,9,0,List(8){90}),
        StrategicIdentity(10002,2,8,0,List(8){10}))
    private fun stocks():PlanetStocks {
        val initial=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),strategic=StrategicState(people=mapOf(
            10000 to StrategicPerson(rank=1),10001 to StrategicPerson(rank=9),10002 to StrategicPerson(rank=8,merit=100))))
        return policy.set(policy.set(initial,OfficeHolding(7,1049,10000)),OfficeHolding(24,1049,10002))
    }
    private fun act(who:Navigator,account:String,command:Int,card:Int,target:Int,value:Int=0,base:Int=0,s:PlanetStocks):SocialResult =
        SocialActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(account,account,who.characterId,Power.EMPIRE,1,command,card,9,
            target=target,value=value,base=base,identities=ids)),session,who,s,accounts.values,accounts)

    @Test fun offlineAndOnlineForeignTargetsAreNotDomesticArrestPermitTargets() {
        val s=stocks()
        val request=StrategyActionRequest("a","a",10000,Power.EMPIRE,1,Justice.PERMIT,7,1,target=10002,value=3,identities=ids)
        for(country in listOf(CountryId(1),CountryId(4),CountryId(42),null)) {
            val foreignIds=ids.map {if(it.id==10002)it.copy(country=country) else it}
            for(online in listOf(false,true)) {
                val people=if(online)accounts+("c" to c.copy(country=country)) else accounts-"c"
                val result=SocialActions.evaluate(galaxy,SocialRequest(request.copy(identities=foreignIds)),session,a,s,people.values,people)
                assertEquals(SocialRejection.TARGET,result.rejection)
                assertEquals(a,result.player);assertEquals(s,result.stocks)
            }
        }
    }

    @Test fun detentionCancelsEveryPersonalNavigationJobWithoutRefundOrLaterEscape() {
        for(kind in 0..2) {
            var players=accounts+("c" to c.copy(baseId=0,mode=5,spotId=0,cruise=500))
            val store=object:NavigationStore {
                override fun load()=players
                override fun save(value:Map<String,Navigator>) {players=value.toMap()}
            }
            val order=NavigationRequest("c",10002,Power.EMPIRE,"c",1,unitId=1010002,captainCard=59)
            val request=when(kind) {0->order.copy(supplyFuel=true);1->order.copy(place=PlaceMove.Dock(1049,4));else->order.copy(destination=2489)}
            val pending=assertNotNull(Navigation(galaxy,store,NavigationPolicy()).apply(request,session,stocks()).player)
            val detainedPlayers=players.toMutableMap()
            val detained=assertNotNull(JusticeActions.detain(galaxy,stocks(),detainedPlayers,10002,CountryId(2),session.gameMillis/1000))
            val prisoner=detainedPlayers.getValue("c")
            assertEquals(10490019,prisoner.spotId);assertEquals(pending.mcp,prisoner.mcp)
            assertNull(prisoner.strategy);assertNull(prisoner.fuelReadyAt);assertNull(prisoner.dockTarget);assertNull(prisoner.warpTarget)
            players=detainedPlayers
            val late=session.copy(gameMillis=session.gameMillis+100*86400_000L)
            val after=assertNotNull(Navigation(galaxy,store,NavigationPolicy()).apply(order,late,detained).player)
            assertEquals(1049,after.baseId);assertEquals(10490019,after.spotId,"the cancelled job cannot move the prisoner later")
        }
    }

    @Test fun aMinisterListsGrantsAndTheHolderArrestsOnce() {
        val listed=act(a,"a",Justice.PERMIT,7,10002,value=3,s=stocks())
        assertNull(listed.rejection);assertEquals(200,listed.player?.mcp,"MCP 800")
        val granted=act(a,"a",Justice.EXECUTION,7,10001,value=0,base=10002,s=assertNotNull(listed.stocks))
        assertNull(granted.rejection);assertEquals(10002,assertNotNull(granted.stocks).social.justice.rights.getValue(10001).target)
        val arrest=act(b,"b",Justice.ARREST,0,10002,s=assertNotNull(granted.stocks))
        assertNull(arrest.rejection);assertEquals(840,arrest.player?.mcp,"MCP 160")
        val threshold=Justice.arrestThreshold(90,10)
        val dice=JusticeActions.roll(10001,10002,session.gameMillis/1000)
        val expected=when {dice<threshold-10 -> JusticeActions.Outcome.ARRESTED;dice<threshold+10 -> JusticeActions.Outcome.EVADED
            else -> JusticeActions.Outcome.FAILED}
        assertEquals(expected.ordinal,arrest.outcome)
        val s=assertNotNull(arrest.stocks)
        if(expected==JusticeActions.Outcome.ARRESTED) {
            assertTrue(s.social.justice.detained(10002));assertTrue(policy.held(s,10002).isEmpty(),"offices lost")
            assertEquals(10490019,arrest.navigators.getValue("c").spotId,"holding room")
        }
        if(expected!=JusticeActions.Outcome.EVADED)assertTrue(10001 !in s.social.justice.rights,"one shot")
    }
    @Test fun anUnlistedPersonOrAnOutOfScopeRankIsRefused() {
        assertEquals(SocialRejection.STALE,act(b,"b",Justice.ARREST,0,10002,s=stocks()).rejection,"not on the list")
        assertFalse(Justice.arrests(7,1),"内務尚書 does not cover politicians");assertTrue(Justice.arrests(10,1))
        assertFalse(Justice.arrests(32,2),"憲兵総監 does not cover marshals");assertFalse(Justice.judges(32,8))
        assertEquals(SocialRejection.CARD,act(b,"b",Justice.PERMIT,7,10002,value=1,s=stocks()).rejection,"card not held")
    }
    @Test fun judgmentReleasesDemotesOrHoldsSixtyDays() {
        val players=accounts.toMutableMap()
        val detained=assertNotNull(JusticeActions.detain(galaxy,stocks(),players,10002,CountryId(2),0))
        val lenient=act(a,"a",Justice.JUDGMENT,7,10002,value=Justice.LENIENT,s=detained)
        assertNull(lenient.rejection);val s=assertNotNull(lenient.stocks)
        assertFalse(s.social.justice.detained(10002));assertEquals(9,s.strategic.people.getValue(10002).rank);assertEquals(50,s.strategic.people.getValue(10002).merit)
        val severe=assertNotNull(act(a,"a",Justice.JUDGMENT,7,10002,value=Justice.SEVERE,s=detained).stocks)
        val d=severe.social.justice.detentions.getValue(10002);assertTrue(d.severe);assertEquals(session.gameMillis/1000+60*86400,d.releaseAt)
        assertEquals(SocialRejection.BUSY,act(c,"c",Justice.PERMIT,0,10001,value=1,s=detained).rejection,"a detainee cannot act")
    }
    @Test fun fourteenDaysWithoutAVerdictMeanLenience() {
        val players=accounts.toMutableMap()
        val detained=assertNotNull(JusticeActions.detain(galaxy,stocks(),players,10002,CountryId(2),0))
        val later=session.copy(gameMillis=(14*86400L+1)*1000)
        val settled=JusticeActions.settle(galaxy,later,players,detained)
        assertFalse(settled.social.justice.detained(10002));assertEquals(9,settled.strategic.people.getValue(10002).rank)
        assertEquals(0,players.getValue("c").spotId,"released to the square")
    }
    @Test fun aSearchFindsSomeoneAtTheSameBaseAndARaidWoundsByTheRoll() {
        val search=act(a,"a",Justice.SEARCH,7,10002,s=stocks())
        assertNull(search.rejection);assertEquals(840,search.player?.mcp)
        val found=JusticeActions.roll(10000,10002,session.gameMillis/1000)<Justice.arrestThreshold(50,10)
        assertEquals(if(found)1 else 3,search.outcome);assertEquals(if(found)spot else 0,search.found)
        assertEquals(SocialRejection.CARD,act(a,"a",Justice.RAID,7,10002,s=stocks()).rejection,"内務尚書 cannot raid")
    }
    @Test fun aWatchReportsAgainForFreeUntilItIsNoticed() {
        val s=policy.set(stocks(),OfficeHolding(32,1049,10000))
        val placed=act(a,"a",Justice.WATCH,32,10002,s=s)
        assertNull(placed.rejection);assertEquals(840,placed.player?.mcp);assertEquals(spot,placed.found)
        val again=act(a,"a",Justice.WATCH,32,10002,s=assertNotNull(placed.stocks))
        assertNull(again.rejection);assertEquals(1000,again.player?.mcp,"free while watching");assertEquals(spot,again.found)
        // Daily rolls until noticed; detection 30% at equal intelligence (50 vs the stored default).
        var state=assertNotNull(placed.stocks);var day=1L
        while(state.social.justice.watches.isNotEmpty() && day<60) {
            state=JusticeActions.settle(galaxy,session.copy(gameMillis=(session.gameMillis/1000+day*86400)*1000),accounts.toMutableMap(),state);day++}
        assertTrue(state.social.justice.watches.isEmpty(),"noticed within 60 days")
    }
    @Test fun activeSpecialGuardLetsTheRaidTargetEvadeAtThatSpotOnly() {
        val start=session.gameMillis/1000
        val now=(start..start+1000).first {JusticeActions.roll(a.characterId,c.characterId,it) in 40..59}
        val at=session.copy(gameMillis=now*1000)
        val target=c.copy(country=CountryId(3),shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(93,now))
        val players=accounts+("c" to target)
        val inventory=policy.set(stocks(),OfficeHolding(32,1049,a.characterId))
        val order=CivicOrder(b.characterId,55,59,CountryId(2),9,1049,0,spot,now-1,now+1,CivicPhase.GUARDING)
        val request=SocialRequest(StrategyActionRequest("a","a",a.characterId,Power.EMPIRE,1,
            Justice.RAID,32,9,target=c.characterId,identities=ids))
        fun raid(guard:CivicOrder):SocialResult {
            val guarded=inventory.copy(civic=CivicState(mapOf(guard.character to guard)))
            return JusticeActions.evaluate(galaxy,request,at,a,guarded,players).also {
                assertNull(it.rejection)
                assertEquals(840,it.player?.mcp)
            }
        }
        val defended=raid(order)
        assertEquals(JusticeActions.Outcome.EVADED.ordinal,defended.outcome)
        assertTrue(defended.navigators.isEmpty())
        for(inactive in listOf(order.copy(readyAt=now),order.copy(spot=spot+1))) {
            val hit=raid(inactive)
            assertEquals(JusticeActions.Outcome.ARRESTED.ordinal,hit.outcome)
            assertEquals(now+Justice.WOUND_SECONDS,hit.navigators.getValue("c").hull?.woundedUntil)
        }
    }
    @Test fun aDefectorSwitchesSideLosesOfficesAndWaitsInTheOtherCapital() {
        val twoCapitals=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Away",3)),listOf(
            GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
        val r=SocialRequest(StrategyActionRequest("c","c",10002,Power.EMPIRE,1,Justice.DEFECT,0,8,value=0,identities=ids))
        val owned=stocks().let {it.copy(bases=it.bases+(1177 to PlanetStock(1177,3,0,0,0,0)))}
        val result=SocialActions.evaluate(twoCapitals,r,session,c,owned,accounts.values,accounts)
        assertNull(result.rejection);assertEquals(CountryId(3),result.defected)
        val p=assertNotNull(result.player);assertEquals(Power.ALLIANCE,p.power);assertEquals(680,p.pcp,"PCP 320");assertNull(p.hull)
        assertEquals(11770019,p.spotId,"the alliance capital holding room")
        val s=assertNotNull(result.stocks);assertEquals(CountryId(3),s.social.justice.detentions.getValue(10002).country)
        assertTrue(OrganizationalOfficePolicy(twoCapitals).held(s,10002).isEmpty())
        val sim=SessionSimulation(rules).also {it.join("c",Power.EMPIRE,false,"c")}
        sim.defect("c",Power.ALLIANCE);assertEquals(Power.ALLIANCE,sim.exportState().participants.getValue("c").power)
        val original=CountryCatalogLoader.load()
        val swapped=CountryCatalog(original.calendars.values.toList(),original.families.values.map {
            it.copy(shipStyle=if(it.shipStyle=="empire")"alliance" else "empire")},original.countries.values.toList())
        val configured=JusticeActions.evaluate(twoCapitals,r,session,c,owned,accounts,catalog=swapped)
        assertNull(configured.rejection)
        assertEquals(CountryId(3),configured.player?.country)
        assertEquals(ShipStyleId("empire"),configured.player?.shipStyle,"destination country model family")
        assertNull(configured.player?.hull)
    }
}
