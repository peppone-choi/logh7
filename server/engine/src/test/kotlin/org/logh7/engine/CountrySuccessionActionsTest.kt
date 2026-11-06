package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Countries sharing display/office codes still have independent governments. Author: Peppone Choi. */
class CountrySuccessionActionsTest {
    private val a=CountryId(42);private val b=CountryId(43);private val c=CountryId(44);private val d=CountryId(45)
    private val catalog=CountryCatalogLoader.load().let {s->CountryCatalog(s.calendars.values.toList(),s.families.values.toList()+listOf(
        s.families.getValue(s.countries.getValue(2).family).copy(key="mixed_emperor",ranks="alliance"),
        s.families.getValue(s.countries.getValue(3).family).copy(key="mixed_chair",ranks="empire")),s.countries.values.toList()+listOf(
        s.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
        s.countries.getValue(2).copy(id=43,key="b",name="B",family="mixed_emperor"),
        s.countries.getValue(3).copy(id=44,key="c",name="C",family="mixed_chair",nativePower=2),
        s.countries.getValue(3).copy(id=45,key="d",name="D",nativePower=2)))}
    private val galaxy=Galaxy((0..4).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..4).map {GalaxyBase(1000+it,2487+it,80,0,"B$it",0,20f,86400,0,0f,2f)},mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
    private val policy=OrganizationalOfficePolicy(galaxy,catalog)
    private val ids=listOf(10000 to a,10001 to a,10002 to a,11000 to b,11001 to b,12000 to c,12001 to c,13000 to d,13001 to d)
        .map {(id,country)->StrategicIdentity(id,country,1,0,List(8){50})}
    private fun player(id:Int,country:CountryId,base:Int)=Navigator(1,id,country,2487+base-1000,1000,1000,1000,
        baseId=base,mode=4,spotId=base*10000+1,shipStyle=ShipStyleId("alliance"))
    private val players=mapOf("a" to player(10000,a,1000),"rival" to player(10001,a,1004),"b" to player(11000,b,1001))
    private val session=SessionSimulation(SessionRules.load()).also {sim->players.forEach {(key,p)->sim.join(key,p.country,false,key)}}
        .exportState().copy(gameMillis=1000_000)
    private fun stocks()=PlanetStocks(1,0,(0..4).associate {i->1000+i to PlanetStock(1000+i,Allegiance.Country(listOf(a,b,c,d,a)[i]),0,0,0,0)},
        strategic=StrategicState(people=ids.associate {it.id to StrategicPerson(rank=1,merit=20)},
            capitals=mapOf(a to 1000,b to 1001,c to 1002,d to 1003),
            offices=listOf(OfficeHolding(1,1000,10000,a),OfficeHolding(1,1001,11000,b),OfficeHolding(132,1002,12000,c),OfficeHolding(132,1003,13000,d))))
    private fun act(command:Int,s:PlanetStocks,account:String="a",target:Int=0,card:Int=0,p:Navigator=players.getValue(account),state:SessionState=session)=
        SocialActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(account,account,p.characterId,p.country,1,command,card,1,target=target,identities=ids)),
            state,p,s,players.values,players,catalog)

    @Test fun nominationAndAbdicationUseOnlyTheCurrentCountriesEmperorAndHeir() {
        val start=stocks()
        val chosen=act(SuccessionActions.DESIGNATE,start,target=10002,card=1)
        assertNull(chosen.rejection);assertEquals(840,chosen.player!!.mcp)
        assertEquals(10002,chosen.stocks!!.social.succession.of(a).heir)
        assertEquals(0,chosen.stocks!!.social.succession.of(b).heir)
        assertEquals(SocialRejection.TARGET,act(SuccessionActions.DESIGNATE,start,target=11001,card=1).rejection)
        val foreign=start.copy(strategic=start.strategic.copy(offices=start.strategic.offices.filter {it.country!=a}))
        assertEquals(SocialRejection.CARD,act(SuccessionActions.DESIGNATE,foreign,target=10002,card=1).rejection)
        val lost=session.copy(eliminated=mapOf(a to CountryDefeat(EndReason.CAPITAL_CAPTURED,1,c)))
        assertEquals(SocialRejection.SESSION,act(SuccessionActions.DESIGNATE,start,target=10002,card=1,state=lost).rejection)
        val r=OfficeResignation.evaluate(galaxy,OfficeResignationRequest("b","b",11000,b,1,1),session,players.getValue("b"),start,catalog)
        assertTrue(r.stocks!!.social.succession.of(b).vacant);assertFalse(r.stocks!!.social.succession.of(a).vacant)
    }
    @Test fun concurrentThronesCrownTheirOwnHeirsAndMissingOrLostCapitalsDoNotBorrowSeats() {
        val initial=stocks().let {it.copy(social=it.social.copy(succession=SuccessionState(heir=999,
            countries=mapOf(a to ThroneState(heir=10002),b to ThroneState(heir=11001)))))}
        val vacant=StrategyActions.clearOffices(StrategyActions.clearOffices(initial,10000),11000)
        val next=SuccessionActions.accede(galaxy,vacant,ids,10,catalog)
        assertEquals(listOf(10002),policy.holders(next,1,a).map {it.character})
        assertEquals(listOf(11001),policy.holders(next,1,b).map {it.character})
        assertEquals(999,next.social.succession.heir)
        assertEquals(listOf(12000),policy.holders(next,132,c).map {it.character})
        assertEquals(setOf(a,b),SuccessionActions.notices(vacant,next,10,catalog).map {it.country}.toSet())
        val noCapital=vacant.copy(strategic=vacant.strategic.copy(capitals=vacant.strategic.capitals-a))
        assertTrue(policy.holders(SuccessionActions.accede(galaxy,noCapital,ids,10,catalog),1,a).isEmpty())
        val captured=vacant.copy(bases=vacant.bases+(1000 to vacant.bases.getValue(1000).copy(owner=Allegiance.Country(c))))
        assertTrue(policy.holders(SuccessionActions.accede(galaxy,captured,ids,10,catalog),1,a).isEmpty())
        val moved=vacant.copy(strategic=vacant.strategic.copy(capitals=vacant.strategic.capitals+(a to 1004)))
        assertEquals(1004,policy.holders(SuccessionActions.accede(galaxy,moved,ids,10,catalog),1,a).single().seat)
    }
    @Test fun aClaimantRaisesOnlyItsOwnBasesAndCannotUseAnotherCountriesSuccessionRecord() {
        val initial=stocks().let {it.copy(strategic=it.strategic.copy(fiefs=mapOf(1002 to 10001)),social=it.social.copy(succession=SuccessionState(
            countries=mapOf(a to ThroneState(emperor=10000,rival=10001,at=999),b to ThroneState(emperor=11000,rival=11001,at=999)))))}
        val changed=act(SuccessionActions.CONTEST,initial,"rival")
        assertNull(changed.rejection);assertEquals(360,changed.player!!.pcp)
        val next=changed.stocks!!
        assertEquals(a,next.social.coup.plots.getValue(10001).country);assertEquals(1,next.bases.getValue(1004).camp)
        assertEquals(0,next.bases.getValue(1002).camp);assertEquals(11001,next.social.succession.of(b).rival)
        assertTrue(OfficeHolding(68,1004,10001,a) in next.strategic.offices)
        val deserter=players.getValue("rival").copy(country=b)
        val moved=session.copy(participants=session.participants+("rival" to session.participants.getValue("rival").copy(country=b)))
        assertEquals(SocialRejection.STALE,act(SuccessionActions.CONTEST,initial,"rival",p=deserter,state=moved).rejection)
    }
    @Test fun politicalPenaltiesFollowRankFamiliesWhileElectionsAndConfidenceFollowOfficeFamilies() {
        val initial=stocks().let {it.copy(social=it.social.copy(funds=FundState(confidence=mapOf(44 to (0L to 10L),45 to (10L to 0L),42 to (0L to 20L)),
            support=mapOf(12001 to 50L,13001 to 50L))))}
        val after=PoliticianLadder.monthly(galaxy,initial,ids,1,catalog)
        assertEquals(listOf(12001),policy.holders(after,132,c).map {it.character})
        assertEquals(listOf(13000),policy.holders(after,132,d).map {it.character})
        assertEquals(mapOf(42 to (0L to 20L)),after.social.funds.confidence)
        assertEquals(mapOf(13001 to 50L),after.social.funds.support)
        assertEquals(setOf(c),SuccessionActions.notices(initial,after,10,catalog).map {it.country}.toSet())
        val zero=initial.copy(strategic=initial.strategic.copy(people=initial.strategic.people.mapValues {(_,p)->p.copy(merit=0)}))
        val penalties=PoliticianLadder.monthly(galaxy,zero,ids,1,catalog).strategic.people
        assertEquals(10,penalties.getValue(11001).rank);assertEquals(10,penalties.getValue(13001).rank)
        assertEquals(399,penalties.getValue(10001).abilities!!.sum());assertEquals(399,penalties.getValue(12001).abilities!!.sum())
    }
    @Test fun rankZeroCannotBeRetainedThroughAnOldCountriesEmperorCard() {
        val start=stocks()
        val moved=ids.map {if(it.id==10000)it.copy(country=null) else it}
        val ranked=SuccessionActions.ranked(start,moved,catalog)
        assertEquals(1,ranked.strategic.people.getValue(10000).rank)
        assertEquals(0,ranked.strategic.people.getValue(11000).rank)
        val captured=start.copy(bases=start.bases+(1000 to start.bases.getValue(1000).copy(owner=Allegiance.Country(c))))
        assertEquals(1,SuccessionActions.ranked(captured,ids,catalog,galaxy).strategic.people.getValue(10000).rank)
    }
    @Test fun aWonSuccessionWarUpdatesOnlyTheWinningCountriesThroneRecord() {
        val initial=stocks().let {it.copy(bases=it.bases+(1000 to it.bases.getValue(1000).copy(camp=1)),social=it.social.copy(
            succession=SuccessionState(countries=mapOf(b to ThroneState(heir=11001))),coup=CoupState(mapOf(10001 to
                Plot(10001,a,1,revolt=1,bases=setOf(1000),capitalSince=10,capitalBase=1000)))))}
        val now=10+CoupActions.CAPITAL_DAYS*Justice.DAY
        val won=CoupActions.settle(galaxy,initial,now,catalog)
        assertEquals(ThroneState(emperor=10001,at=now),won.social.succession.of(a))
        assertEquals(initial.social.succession.of(b),won.social.succession.of(b))
        assertEquals(listOf(SuccessionNotice(1,10001,SuccessionNotice.Kind.ACCESSION,now,a)),SuccessionActions.notices(initial,won,now,catalog))
    }
}
