package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Custody and domestic authority belong to a country, not a displayed faction. Author: Peppone Choi. */
class CountryJusticeActionsTest {
    private val a=CountryId(42);private val b=CountryId(43);private val c=CountryId(44)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2,shipStyleOverride="empire"),
            seed.countries.getValue(2).copy(id=44,key="c",name="C")))}
    private val galaxy=Galaxy((0..3).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..3).map {GalaxyBase(1000+it,2487+it,80,0,"B$it",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
    private fun player(id:Int,country:CountryId,base:Int)=Navigator(1,id,country,2487+base-1000,1000,1000,1000,
        baseId=base,mode=4,spotId=base*10000+3,shipStyle=ShipStyleId(if(country==a)"alliance" else "empire"))
    private val players=mapOf("a" to player(10000,a,1000),"target" to player(10001,a,1000),
        "b" to player(10002,b,1001),"holder" to player(10003,a,1000))
    private val now=(1L..1000).first {JusticeActions.roll(10003,10001,it)<40}
    private val session=SessionSimulation(SessionRules.load()).also {sim->
        players.forEach {(account,p)->sim.join(account,p.country,false,account)}}.exportState().copy(gameMillis=now*1000)
    private fun stocks()=PlanetStocks(1,0,(0..3).associate {i->(1000+i) to PlanetStock(1000+i,
        Allegiance.Country(listOf(a,b,c,a)[i]),0,0,0,0)},strategic=StrategicState(
        people=players.values.associate {it.characterId to StrategicPerson(rank=if(it.characterId==10001)8 else 1)},
        capitals=mapOf(a to 1000,b to 1001,c to 1002),offices=listOf(
            OfficeHolding(7,1000,10000,a),OfficeHolding(10,1000,10000,a),OfficeHolding(137,1001,10002,b),
            OfficeHolding(54,1003,10001,a))))
    private fun act(command:Int,s:PlanetStocks=stocks(),account:String="a",card:Int=7,target:Int=10001,value:Int=0,base:Int=0,
        accounts:Map<String,Navigator> = players,state:SessionState=session):SocialResult {
        val p=accounts.getValue(account)
        val ids=players.values.map {StrategicIdentity(it.characterId,it.country,8,0,List(8){50})}
        val request=StrategyActionRequest(account,account,p.characterId,p.country,1,command,card,1,
            target=target,value=value,base=base,identities=ids)
        return SocialActions.evaluate(galaxy,SocialRequest(request),state,p,s,accounts.values,accounts,catalog)
    }
    private fun listed()=stocks().let {it.copy(social=it.social.copy(justice=JusticeState(
        permits=mapOf(10001 to ArrestPermit(10001,10000,a,3,now+86400)))))}

    @Test fun permitsAndGrantsRequireActualDomesticMembershipAndOwnOffices() {
        val permit=act(Justice.PERMIT,value=3)
        assertNull(permit.rejection);assertEquals(200,permit.player?.mcp)
        assertEquals(a,permit.stocks!!.social.justice.permits.getValue(10001).country)
        val foreign=players+("target" to players.getValue("target").copy(country=c))
        assertEquals(SocialRejection.TARGET,act(Justice.PERMIT,value=3,accounts=foreign).rejection)
        val independent=players+("target" to players.getValue("target").copy(country=null))
        assertEquals(SocialRejection.TARGET,act(Justice.PERMIT,value=3,accounts=independent).rejection,"no saved-country fallback")
        val grant=act(Justice.EXECUTION,listed(),target=10003,base=10001)
        assertNull(grant.rejection);assertEquals(a,grant.stocks!!.social.justice.rights.getValue(10003).country)
        assertEquals(SocialRejection.TARGET,act(Justice.EXECUTION,listed(),target=10003,base=10001,accounts=foreign).rejection)
        val wrong=stocks().copy(strategic=stocks().strategic.copy(offices=listOf(OfficeHolding(7,1002,10000,c))))
        assertEquals(SocialRejection.CARD,act(Justice.PERMIT,wrong,value=3).rejection)
    }

    @Test fun arrestRightsAreBoundToTheirIssuingCountryAndCustodyUsesItsCapital() {
        val base=listed()
        for(origin in listOf(c,null)) {
            val s=base.copy(social=base.social.copy(justice=base.social.justice.copy(
                rights=mapOf(10003 to ArrestRight(10003,10001,10000,now+86400,origin)))))
            assertEquals(SocialRejection.CARD,act(Justice.ARREST,s,"holder",0).rejection)
        }
        val s=base.copy(social=base.social.copy(justice=base.social.justice.copy(
            rights=mapOf(10003 to ArrestRight(10003,10001,10000,now+86400,a)))))
        val result=act(Justice.ARREST,s,"holder",0)
        assertNull(result.rejection);assertEquals(840,result.player?.mcp)
        val detention=result.stocks!!.social.justice.detentions.getValue(10001)
        assertEquals(a,detention.country);assertEquals(1000,detention.base)
        assertEquals(10000019,result.navigators.getValue("target").spotId)
        assertTrue(result.stocks!!.strategic.offices.none {it.character==10001})
        val stolen=players+("target" to players.getValue("target").copy(country=c))
        assertEquals(SocialRejection.TARGET,act(Justice.ARREST,s,"holder",0,accounts=stolen).rejection)
    }

    @Test fun aForeignPrisonerIsJudgedByTheCustodianAndReleasedAtTheOriginalHoldingBase() {
        val live=players.toMutableMap()
        val detained=JusticeActions.detain(galaxy,stocks(),live,10001,b,now,catalog)!!
        assertEquals(10010019,live.getValue("target").spotId)
        val politician=detained.copy(strategic=detained.strategic.copy(people=detained.strategic.people+(10001 to StrategicPerson(rank=1))))
        assertEquals(SocialRejection.STALE,act(Justice.JUDGMENT,politician,card=10,accounts=live,value=Justice.ACQUITTAL).rejection)
        val moved=politician.copy(bases=politician.bases+(1003 to politician.bases.getValue(1003).copy(owner=Allegiance.Country(b))),
            strategic=politician.strategic.copy(capitals=politician.strategic.capitals+(b to 1003),
                offices=politician.strategic.offices.map {if(it.country==b)it.copy(seat=1003) else it}))
        val released=act(Justice.JUDGMENT,moved,"b",137,value=Justice.ACQUITTAL,accounts=live)
        assertNull(released.rejection)
        assertEquals(1001,released.navigators.getValue("target").baseId)
        assertEquals(0,released.navigators.getValue("target").spotId)
        assertEquals(a,released.navigators.getValue("target").country)
    }

    @Test fun explicitDefectionUsesDestinationCountryStyleAndCustodyWhileAmbiguousDefaultIsRejected() {
        val result=act(Justice.DEFECT,card=0,target=0,value=b.value)
        assertNull(result.rejection);assertEquals(b,result.defected);assertEquals(680,result.player?.pcp)
        assertEquals(b,result.player?.country);assertEquals(ShipStyleId("empire"),result.player?.shipStyle)
        assertEquals(10010019,result.player?.spotId)
        assertEquals(b,result.stocks!!.social.justice.detentions.getValue(10000).country)
        assertTrue(result.stocks!!.strategic.offices.none {it.character==10000})
        assertEquals(SocialRejection.TARGET,act(Justice.DEFECT,card=0,target=0).rejection)
        val eliminated=session.copy(eliminated=mapOf(b to CountryDefeat(EndReason.CAPITAL_CAPTURED,1,c)))
        assertEquals(SocialRejection.TARGET,act(Justice.DEFECT,card=0,target=0,value=b.value,state=eliminated).rejection)
        val lost=stocks().copy(bases=stocks().bases+(1001 to stocks().bases.getValue(1001).copy(owner=Allegiance.Country(c))))
        val accounts=players.toMutableMap();val before=accounts.toMap()
        assertNull(JusticeActions.detain(galaxy,lost,accounts,10001,b,now,catalog));assertEquals(before,accounts)
    }
}
