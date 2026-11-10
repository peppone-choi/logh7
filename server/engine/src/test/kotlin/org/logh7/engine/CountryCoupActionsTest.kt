package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Actual ownership, configured offices and capital occupation must agree. Author: Peppone Choi. */
class CountryCoupActionsTest {
    private val a=CountryId(42);private val b=CountryId(43);private val c=CountryId(44)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2,shipStyleOverride="empire"),
            seed.countries.getValue(2).copy(id=44,key="c",name="C")))}
    private val galaxy=Galaxy((0..3).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..3).map {GalaxyBase(1000+it,2487+it,80,0,"B$it",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
    private val policy=OrganizationalOfficePolicy(galaxy,catalog)
    private fun player(id:Int,country:CountryId,base:Int)=Navigator(1,id,country,2487+base-1000,1000,1000,1000,
        baseId=base,mode=4,spotId=base*10000+1,shipStyle=ShipStyleId(if(country==a)"alliance" else "empire"))
    private val players=mapOf("a" to player(10000,a,1000),"joiner" to player(10001,a,1000),
        "b" to player(10002,b,1001),"c" to player(10003,c,1002))
    private val session=SessionSimulation(SessionRules.load()).also {sim->
        players.forEach {(account,p)->sim.join(account,p.country,false,account)}}.exportState().copy(gameMillis=1000_000)
    private fun stocks()=PlanetStocks(1,0,(0..3).associate {i->(1000+i) to PlanetStock(1000+i,
        Allegiance.Country(listOf(a,b,c,a)[i]),0,0,0,0)},strategic=StrategicState(
        people=players.values.associate {it.characterId to StrategicPerson(rank=5)},capitals=mapOf(a to 1000,b to 1001,c to 1002),
        offices=listOf(OfficeHolding(58,1000,10000,a),OfficeHolding(194,1001,10002,b),OfficeHolding(58,1002,10003,c),
            OfficeHolding(1,1000,10008,a),OfficeHolding(1,1002,10009,c),OfficeHolding(10,1002,10000,c))))
    private fun act(command:Int,s:PlanetStocks,account:String="a",target:Int=0,card:Int=0,
        accounts:Map<String,Navigator> = players,state:SessionState=session):SocialResult {
        val p=accounts.getValue(account)
        val i=StrategyActionRequest(account,account,p.characterId,p.country,1,command,card,5,target=target,
            identities=players.values.map {StrategicIdentity(it.characterId,it.country,5,0,List(8){50})})
        return SocialActions.evaluate(galaxy,SocialRequest(i),state,p,s,accounts.values,accounts,catalog)
    }
    private fun plotted(country:CountryId=a,leader:Int=10000,members:List<Int> = emptyList())=stocks().let {
        it.copy(social=it.social.copy(coup=CoupState(mapOf(leader to Plot(leader,country,1,members=members)))))}

    @Test fun intentAndInspectionUseTheActualCountryAndItsOfficeFamily() {
        val started=act(CoupActions.INTENT,stocks())
        assertNull(started.rejection);assertEquals(360,started.player?.pcp)
        assertEquals(a,started.stocks!!.social.coup.plots.getValue(10000).country)
        assertNull(act(CoupActions.INTENT,stocks(),"b").rejection,"alliance offices despite empire display/model")
        val onlyForeign=stocks().copy(strategic=stocks().strategic.copy(offices=stocks().strategic.offices.filter {it.country!=a}))
        assertEquals(SocialRejection.CARD,act(CoupActions.INTENT,onlyForeign).rejection)
        val foreign=plotted(c,10003)
        val inspected=act(CoupActions.INSPECTION,foreign,card=58)
        assertNull(inspected.rejection);assertEquals(3,inspected.outcome);assertEquals(0,inspected.found)
        assertEquals(840,inspected.player?.mcp)
        assertEquals(SocialRejection.TARGET,act(CoupActions.CONSPIRACY,plotted(),target=10002).rejection)
    }

    @Test fun revoltChangesOnlyOwnedBasesOfficesAndEligibleCurrentMembers() {
        val joined=plotted(members=listOf(10001))
        val deserter=players+ ("joiner" to players.getValue("joiner").copy(country=c,baseId=1002))
        val result=act(CoupActions.REVOLT,joined,accounts=deserter)
        assertNull(result.rejection)
        val next=result.stocks!!
        assertEquals(1,next.bases.getValue(1000).camp)
        assertEquals(0,next.bases.getValue(1002).camp)
        assertTrue(OfficeHolding(68,1000,10000,a) in next.strategic.offices)
        assertTrue(OfficeHolding(10,1002,10000,c) in next.strategic.offices,"foreign appointment retained")
        assertTrue(next.strategic.offices.none {it.card==69},"deserter is not a deputy")
        val defected=session.copy(participants=session.participants+("joiner" to Participant(c,false,connection="joiner")))
        assertEquals(SocialRejection.STALE,act(CoupActions.JOIN,joined,account="joiner",accounts=deserter,state=defected).rejection)
        assertEquals(SocialRejection.LOCATION,act(CoupActions.REVOLT,joined,accounts=players+(
            "a" to players.getValue("a").copy(baseId=1002))).rejection)
        val lost=session.copy(eliminated=mapOf(a to CountryDefeat(EndReason.CAPITAL_CAPTURED,1,c)))
        assertEquals(SocialRejection.SESSION,act(CoupActions.INTENT,stocks(),state=lost).rejection)
    }

    @Test fun offlineMemberWithCurrentSavedCountryStillReceivesTheDeputyOffice() {
        val result=act(CoupActions.REVOLT,plotted(members=listOf(10001)),accounts=players-"joiner")
        assertNull(result.rejection)
        assertTrue(OfficeHolding(69,1000,10001,a) in result.stocks!!.strategic.offices)
    }

    @Test fun currentIndependentMembershipDoesNotFallBackToAnOlderSavedCountry() {
        val independent=players+("joiner" to players.getValue("joiner").copy(country=null))
        val result=act(CoupActions.REVOLT,plotted(members=listOf(10001)),accounts=independent)
        assertNull(result.rejection)
        assertTrue(result.stocks!!.social.coup.plots.getValue(10000).members.isEmpty())
        assertTrue(result.stocks!!.strategic.offices.none {it.card==69})
    }

    @Test fun persuasionTouchesOnlyOutfitsOfTheActualCountry() {
        val s=plotted().let {it.copy(outfits=mapOf(
            7 to OutfitStock(7,a,OutfitKind.PATROL,1000,1000,10000,shipStyle=ShipStyleId("alliance")),
            8 to OutfitStock(8,c,OutfitKind.PATROL,1002,1002,10000,shipStyle=ShipStyleId("empire"))),warehouses=mapOf(
            1000 to OutfitWarehouse(1000,a,OutfitKind.PATROL,1000,shipStyle=ShipStyleId("alliance")),
            1002 to OutfitWarehouse(1002,c,OutfitKind.PATROL,1002,shipStyle=ShipStyleId("empire"))),
            strategic=it.strategic.copy(offices=it.strategic.offices+listOf(OfficeHolding(47,7,10000,a),OfficeHolding(47,8,10000,c))))}
        val result=act(CoupActions.PERSUASION,s)
        assertNull(result.rejection);assertEquals(360,result.player?.pcp)
        assertEquals(mapOf(7 to 10),result.stocks!!.social.coup.loyalty)
    }

    @Test fun baseOfficersAreClearedWhenARegularBaseJoinsTheRevolt() {
        val s=plotted().let {it.copy(bases=it.bases+(1000 to it.bases.getValue(1000).copy(
            governorId=10008,garrisonCommanderId=10009)))}
        val result=act(CoupActions.REVOLT,s)
        assertNull(result.rejection)
        val base=result.stocks!!.bases.getValue(1000)
        assertEquals(1,base.camp);assertEquals(0,base.governorId);assertEquals(0,base.garrisonCommanderId)
    }

    @Test fun allianceRuleFamilyUsesItsRebelAndHeadOfficesWithAnEmpireDisplay() {
        val revolted=act(CoupActions.REVOLT,plotted(b,10002),"b").stocks!!
        assertTrue(OfficeHolding(197,1001,10002,b) in revolted.strategic.offices)
        assertEquals(197,ProposalActions.rebelLeaderCard(policy,revolted,10002,b))
        assertNull(ProposalActions.rebelLeaderCard(policy,revolted,10002,a))
        val started=CoupActions.settle(galaxy,revolted,1000,catalog)
        val won=CoupActions.settle(galaxy,started,1000+7*86400,catalog)
        assertTrue(OfficeHolding(132,1001,10002,b) in won.strategic.offices)
        assertFalse(won.social.coup.revolting(b))
        assertTrue(OfficeHolding(1,1002,10009,c) in won.strategic.offices)
    }

    @Test fun onlyACompletedChairmanChangeRetiresTheFormerIncumbentsConfidenceVotes() {
        val plotted=plotted(b,10002)
        val funds=FundState(confidence=mapOf(a.value to (20L to 30L),b.value to (0L to 100L)),
            support=mapOf(10002 to 80L))
        val before=plotted.copy(
            strategic=plotted.strategic.copy(offices=plotted.strategic.offices+OfficeHolding(132,1001,10010,b)),
            social=plotted.social.copy(funds=funds))
        val revolted=assertNotNull(act(CoupActions.REVOLT,before,"b").stocks)
        val started=CoupActions.settle(galaxy,revolted,1000,catalog)
        val due=1000+7*86400L
        assertEquals(funds,CoupActions.settle(galaxy,started,due-1,catalog).social.funds)
        val dead=started.copy(strategic=started.strategic.copy(people=started.strategic.people+
            (10002 to StrategicPerson(deceasedAt=due-1))))
        val failed=CoupActions.settle(galaxy,dead,due,catalog)
        assertEquals(funds,failed.social.funds)
        assertEquals(listOf(10010),policy.holders(failed,132,b).map {it.character})

        val won=CoupActions.settle(galaxy,started,due,catalog)
        assertEquals(funds.copy(confidence=funds.confidence-b.value),won.social.funds)
        assertEquals(listOf(10002),policy.holders(won,132,b).map {it.character})
        assertFalse(SuccessionActions.notices(started,won,due,catalog).any {
            it.kind==SuccessionNotice.Kind.CHAIRMAN_DISMISSED && it.country==b
        })
        val monthly=PoliticianLadder.monthly(galaxy,won,emptyList(),1,catalog)
        assertEquals(listOf(10002),policy.holders(monthly,132,b).map {it.character})
    }

    @Test fun storedCapitalRelocationResetsContinuousOccupationAndOnlyThatCountrysHeadChanges() {
        val revolted=act(CoupActions.REVOLT,plotted()).stocks!!
        val started=CoupActions.settle(galaxy,revolted,1000,catalog)
        assertEquals(1000,started.social.coup.plots.getValue(10000).capitalBase)
        val relocated=started.copy(bases=started.bases+(1003 to started.bases.getValue(1003).copy(camp=1)),
            strategic=started.strategic.copy(capitals=started.strategic.capitals+(a to 1003)))
        val reset=CoupActions.settle(galaxy,relocated,1000+6*86400,catalog)
        assertEquals(1000+6*86400,reset.social.coup.plots.getValue(10000).capitalSince)
        assertEquals(1003,reset.social.coup.plots.getValue(10000).capitalBase)
        val still=CoupActions.settle(galaxy,reset,1000+7*86400,catalog)
        assertTrue(still.social.coup.revolting(a),"old capital time cannot crown the leader")
        val won=CoupActions.settle(galaxy,still,1000+13*86400,catalog)
        assertFalse(won.social.coup.revolting(a))
        assertTrue(OfficeHolding(1,1003,10000,a) in won.strategic.offices)
        assertTrue(OfficeHolding(1,1002,10009,c) in won.strategic.offices)
        val missing=started.copy(strategic=started.strategic.copy(capitals=started.strategic.capitals-a))
        assertEquals(0,CoupActions.settle(galaxy,missing,1001,catalog).social.coup.plots.getValue(10000).capitalSince)
    }

    @Test fun suppressionAndTacticalMembershipCannotBorrowAnotherCountrysRebellion() {
        val revolted=act(CoupActions.REVOLT,plotted()).stocks!!
        assertTrue(CoupActions.rebel(revolted,10000,a))
        assertFalse(CoupActions.rebel(revolted,10000,c))
        assertFalse(CoupActions.rebel(revolted,10000,null))
        val foreignOutfit=OutfitStock(7,c,OutfitKind.PATROL,1002,1,commanderId=10000,shipStyle=ShipStyleId("empire"))
        val loyal=revolted.copy(social=revolted.social.copy(coup=revolted.social.coup.copy(loyalty=mapOf(7 to 100))))
        assertEquals(0,CoupActions.outfitCamp(loyal,foreignOutfit))
        val captured=loyal.copy(bases=loyal.bases+(1000 to loyal.bases.getValue(1000).copy(owner=Allegiance.Country(c))))
        val ended=CoupActions.settle(galaxy,captured,1001,catalog)
        assertTrue(ended.social.coup.plots.isEmpty())
        assertEquals(1,ended.bases.getValue(1000).camp,"captured foreign rebel base must stay untouched")
    }
}
