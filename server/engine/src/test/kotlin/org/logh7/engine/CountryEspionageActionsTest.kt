package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryEspionageActionsTest {
    private val a=CountryId(42);private val b=CountryId(43)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2)))}
    private val galaxy=Galaxy((0..2).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..2).map {GalaxyBase(1000+it,2487+it,80,0,"B$it",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
    private val player=Navigator(1,10000,a,2487,1000,1000,1000,baseId=1000,mode=4,spotId=10000001,shipStyle=ShipStyleId("alliance"))
    private val session=SessionSimulation(SessionRules.load()).also {it.join("spy",a,false,"c")}.exportState()
    private fun stocks()=PlanetStocks(1,0,(0..2).associate {i->(1000+i) to PlanetStock(1000+i,
        Allegiance.Country(if(i==0)a else b),0,0,0,0,PlanetEconomy(1000,500,60,60))},strategic=StrategicState(
        people=mapOf(10000 to StrategicPerson(rank=5,abilities=List(8){100})),capitals=mapOf(a to 1000,b to 1001),
        offices=listOf(OfficeHolding(58,1000,10000,a))))
    private fun act(command:Int,now:Long,s:PlanetStocks=stocks(),p:Navigator=player,base:Int=0,value:Int=0,
        state:SessionState=session,card:Int=58):SocialResult {
        val request=StrategyActionRequest("spy","c",10000,p.country,1,command,card,5,base=base,value=value)
        return SocialActions.evaluate(galaxy,SocialRequest(request),state.copy(gameMillis=now*1000),p,s,
            listOf(p),mapOf("spy" to p),catalog)
    }
    private fun time(target:Int,success:Boolean)=(1L..1000).first {
        (EspionageActions.roll(10000,target,it)<EspionageActions.threshold(100,1))==success}

    @Test fun successfulIntelligenceStoresAHomeSnapshotUntilTheExactExpiry() {
        val spot=galaxy.facilitiesAt(1001,stocks(),catalog).first().spots.last().id
        val target=stocks().bases.getValue(1001).copy(treasury=3_000_000_000)
        val defenders=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(0),base=1001,strength=725)))
        val invaders=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),base=1001,strength=500)))
        val outfits=listOf(b to defenders,a to invaders).mapIndexed {index,(country,troops)->
            val id=index+1
            id to OutfitStock(id,country,OutfitKind.GROUND,1001,100+id,
                units=UnitInventory(troops=troops.quantities()),activeGround=troops,shipStyle=ShipStyleId("empire"))
        }.toMap()
        val warehouses=outfits.values.associate {it.warehouseId to
            OutfitWarehouse(it.warehouseId,it.country,OutfitKind.PATROL,1001,shipStyle=it.shipStyle)}
        val original=stocks().let {it.copy(bases=it.bases+(1001 to target),outfits=outfits,warehouses=warehouses,nextShipId=3)}
        val entered=act(EspionageActions.INFILTRATE,time(spot,true),original,player.copy(baseId=1001,grid=2488,spotId=10010001),base=spot)
        val infiltrated=assertNotNull(entered.stocks)
        val agent=assertNotNull(entered.player)
        val observed=time(spot,true)
        val succeeded=act(EspionageActions.INTELLIGENCE,observed,infiltrated,agent)
        assertEquals(1,succeeded.outcome)
        assertEquals(Int.MAX_VALUE,succeeded.found)
        val saved=assertNotNull(succeeded.stocks)
        val failed=act(EspionageActions.INTELLIGENCE,time(spot,false),saved,agent)
        assertEquals(3,failed.outcome)
        assertEquals(saved,failed.stocks)

        val members=mapOf("ally" to Participant(a,false,connection="ally-connection"),
            "other" to Participant(b,false,connection="other-connection"))
        val ally=player.copy(characterId=10001)
        val other=player.copy(characterId=10002,country=b)
        fun at(time:Long)=session.copy(gameMillis=time*1000,participants=members)
        val expires=observed+Justice.DAY
        val viewerState=at(expires-1)
        val report=HomeIntelligenceReport(a,1001,observed,3_000_000_000,60,60,garrisonStrength=725)
        val changedTarget=saved.copy(bases=saved.bases+(1001 to target.copy(treasury=5)),outfits=emptyMap(),warehouses=emptyMap())
        assertEquals(report,EspionageActions.homeReport(changedTarget,viewerState,"ally","ally-connection",ally,1001))
        assertNull(EspionageActions.homeReport(saved,viewerState,"other","other-connection",other,1001))
        assertNull(EspionageActions.homeReport(saved,viewerState,"other","other-connection",ally,1001))
        assertNull(EspionageActions.homeReport(saved,viewerState,"ally","old-connection",ally,1001))
        assertNull(EspionageActions.homeReport(saved,at(expires),"ally","ally-connection",ally,1001))
        assertTrue(EspionageActions.settle(galaxy,at(expires),mutableMapOf(),saved,catalog).social.justice.homeReports.isEmpty())
    }

    @Test fun aFullReportStorePreservesActiveReportsAndReusesExpiredCapacity() {
        val spot=galaxy.facilitiesAt(1001,stocks(),catalog).first().spots.last().id
        val observed=(Justice.DAY..Justice.DAY+1000).first {EspionageActions.roll(10000,spot,it)<EspionageActions.threshold(100,1)}
        val p=player.copy(baseId=1001,grid=2488,spotId=spot)
        val original=stocks()
        val reports=(2000 until 2000+JusticeState.MAX_HOME_REPORTS).associate {base->
            (b to base) to HomeIntelligenceReport(b,base,observed-Justice.DAY+1,100,60,60)
        }
        val justice=JusticeState(infiltrations=mapOf(10000 to Infiltration(10000,1001,spot,observed+10,a,b)),homeReports=reports)
        fun withJustice(state:JusticeState)=original.copy(social=original.social.copy(justice=state))
        val full=withJustice(justice)
        val rejected=act(EspionageActions.INTELLIGENCE,observed,full,p)
        assertEquals(SocialRejection.MAXIMUM,rejected.rejection)
        assertEquals(p,rejected.player)
        assertEquals(full,rejected.stocks)

        val expired=b to 2000
        val freed=withJustice(justice.copy(homeReports=reports+(expired to reports.getValue(expired).copy(observedAt=observed-Justice.DAY))))
        val succeeded=act(EspionageActions.INTELLIGENCE,observed,freed,p)
        assertEquals(1,succeeded.outcome)
        assertEquals(840,succeeded.player?.mcp)
        val saved=assertNotNull(succeeded.stocks).social.justice.homeReports
        assertEquals(0L,saved.getValue(a to 1001).garrisonStrength)
        assertEquals(reports-expired,saved-(a to 1001))
    }

    @Test fun intrudeReturnAndCaptureUseActualCountryDespiteIdenticalDisplay() {
        val entered=act(EspionageActions.INTRUDE,time(1001,true),base=1001,value=2)
        assertNull(entered.rejection);assertEquals(680,entered.player?.mcp);assertEquals(1001,entered.player?.baseId)
        val disguise=entered.stocks!!.social.justice.disguises.getValue(10000)
        assertEquals(b,disguise.country);assertEquals(a,disguise.originCountry)
        val caught=act(EspionageActions.INTRUDE,time(1001,false),base=1001,value=2)
        assertEquals(b,caught.stocks!!.social.justice.detentions.getValue(10000).country)
        assertEquals(10010019,caught.player?.spotId);assertEquals(a,caught.player?.country)
        assertEquals(SocialRejection.TARGET,act(EspionageActions.RETURN,1,p=entered.player!!,base=1002).rejection)
        val returned=act(EspionageActions.RETURN,1,p=entered.player!!,s=entered.stocks!!,base=1000)
        assertNull(returned.rejection);assertEquals(1000,returned.player?.baseId)
        assertTrue(returned.stocks!!.social.justice.disguises.isEmpty())
    }

    @Test fun infiltrationAndIntelligenceCannotBeReusedAfterTheAgentOrTargetChangesCountry() {
        val spot=galaxy.facilitiesAt(1001,stocks(),catalog).first().spots.last().id
        val p=player.copy(baseId=1001,grid=2488,spotId=10010001)
        val entered=act(EspionageActions.INFILTRATE,time(spot,true),p=p,base=spot)
        assertEquals(a,entered.stocks!!.social.justice.infiltrations.getValue(10000).country)
        assertEquals(b,entered.stocks!!.social.justice.infiltrations.getValue(10000).targetCountry)
        val captured=entered.stocks!!.let {it.copy(bases=it.bases+(1001 to it.bases.getValue(1001).copy(owner=Allegiance.Country(a))))}
        assertEquals(SocialRejection.STALE,act(EspionageActions.INTELLIGENCE,1,captured,entered.player!!).rejection)
        val foreign=entered.stocks!!.let {it.copy(strategic=it.strategic.copy(offices=listOf(OfficeHolding(194,1001,10000,b))))}
        val other=entered.player!!.copy(country=b)
        assertEquals(SocialRejection.IDENTITY,act(EspionageActions.INTELLIGENCE,1,foreign,other).rejection)
        val switched=session.copy(participants=mapOf("spy" to Participant(b,false,connection="c")))
        assertEquals(SocialRejection.STALE,act(EspionageActions.INTELLIGENCE,1,foreign,other,state=switched,card=194).rejection)
    }

    @Test fun exposureUsesThisWorldsGalaxyAndClearsAStaleForgedIdentity() {
        val due=(1L..1000).first {EspionageActions.roll(10000,2,it)<EspionageActions.exposure(100)}
        val disguised=stocks().let {it.copy(social=it.social.copy(justice=JusticeState(disguises=mapOf(
            10000 to Disguise(10000,2,b,0,due,a)))))}
        val atEnemy=player.copy(baseId=1002,grid=2489,spotId=10020001)
        val live=mutableMapOf("spy" to atEnemy)
        val result=EspionageActions.settle(galaxy,session.copy(gameMillis=due*1000),live,disguised,catalog)
        assertEquals(10010019,live.getValue("spy").spotId)
        assertEquals(b,result.social.justice.detentions.getValue(10000).country)
        val independent=mutableMapOf("spy" to atEnemy.copy(country=null))
        assertNull(EspionageActions.disguise(disguised,independent.getValue("spy")))
        val stale=EspionageActions.settle(galaxy,session.copy(gameMillis=due*1000),independent,disguised,catalog)
        assertTrue(stale.social.justice.disguises.isEmpty());assertTrue(stale.social.justice.detentions.isEmpty())
        assertEquals(atEnemy.copy(country=null),independent.getValue("spy"))
    }
}
