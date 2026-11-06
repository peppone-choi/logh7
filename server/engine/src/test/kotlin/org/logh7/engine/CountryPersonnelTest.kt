package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryPersonnelTest {
    private val country=CountryId(42)
    private val other=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1003).map {
        GalaxyBase(it,2487,80,0,if(it==1000)Capital.EMPIRE.baseName else "Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {it.join("a",country,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val actor=Navigator(1,10000,country,2487,1000,1000,1000,shipStyle=ShipStyleId("empire"))
    private val identities=listOf(StrategicIdentity(10000,country,2,0,List(8){50}),
        StrategicIdentity(10001,country,9,0,List(8){50}),StrategicIdentity(10002,country,9,0,List(8){50}))
    private fun catalog(ranks:String="empire",offices:String="empire",peerage:String="empire",native:Int=2)=
        CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),old.families.values.toList()+
            old.families.getValue("empire").copy(key="custom",ranks=ranks,offices=offices,peerage=peerage),
            old.countries.values.toList()+listOf(42,43).map {id->old.countries.getValue(2).copy(
                id=id,key="country_$id",family="custom",nativePower=native,shipStyleOverride="alliance")})}
    private fun stock(card:Int=19)=PlanetStocks(1,0,(1000..1003).associate {id->id to PlanetStock(id,
        Allegiance.Country(if(id==1002)other else country),0,100,200,300)},strategic=StrategicState(
        capitals=mapOf(country to 1000,other to 1002),people=mapOf(10001 to StrategicPerson(merit=30),10002 to StrategicPerson(merit=10)),
        offices=listOf(OfficeHolding(card,1000,10000,country))))
    private fun request(command:Int,card:Int=19)=StrategyActionRequest("a","c",10000,country,1,command,card,2,
        target=10001,identities=identities)
    private fun act(r:StrategyActionRequest,s:PlanetStocks=stock(r.card),definitions:CountryCatalog=catalog())=
        StrategyActions.evaluate(galaxy,r,session,actor,s,definitions)

    @Test fun rankAndOfficeSystemsAreIndependentOfDisplayAndTheRecipientsShipStyle() {
        for(ranks in listOf("empire","alliance"))for(offices in listOf("empire","alliance")) {
            val card=if(offices=="empire")19 else 160
            val definitions=catalog(ranks,offices,native=if(offices=="empire")3 else 2)
            val target=actor.copy(characterId=10001,shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(93,now))
            val s=stock(card).let {it.copy(strategic=it.strategic.copy(offices=it.strategic.offices+
                OfficeHolding(if(offices=="empire")4 else 134,1000,10001,country)))}
            val result=act(request(0,card).copy(navigators=mapOf("target" to target)),s,definitions)
            assertNull(result.rejection);assertEquals(840,result.player?.mcp)
            val after=assertNotNull(result.stocks);assertEquals(8,after.strategic.people.getValue(10001).rank)
            assertEquals(0L,after.strategic.people.getValue(10001).merit)
            assertTrue(after.strategic.offices.none {it.character==10001})
            val upgraded=assertNotNull(result.navigators["target"])
            assertEquals(country,upgraded.country);assertEquals(ShipStyleId("alliance"),upgraded.shipStyle)
            assertEquals(Flagships.forType(3,2).kind,upgraded.hull?.kind)
            val forged=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(card,1002,10000,other))))
            assertEquals(StrategyActionRejection.AUTHORITY,act(request(0,card),forged,definitions).rejection)
        }
    }

    @Test fun foreignRanksAndMeritDoNotAffectTheActualCountrysPromotionQuota() {
        val q=request(0,17).copy(identities=identities.map {if(it.id!=10000)it.copy(rank=8) else it})
        val foreign=(20000..20079).map {StrategicIdentity(it,other,7,0,List(8){255})}
        val s=stock(17).let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+foreign.associate {
            it.id to StrategicPerson(merit=1000000)}))}
        assertNull(act(q.copy(identities=q.identities+foreign),s).rejection)
        assertEquals(StrategyActionRejection.MAXIMUM,act(q.copy(identities=q.identities+foreign.map {it.copy(country=country)}),s).rejection)
        for(id in listOf(other,null)) {
            val denied=act(q.copy(identities=q.identities.map {if(it.id==10001)it.copy(country=id) else it}),s)
            assertEquals(StrategyActionRejection.IDENTITY,denied.rejection);assertEquals(s,denied.stocks);assertEquals(actor,denied.player)
        }
    }

    @Test fun transitionsUseConfiguredRanksWhileAuthorityUsesConfiguredOffices() {
        val ids=identities.map {if(it.id!=10000)it.copy(rank=4) else it}
        val empire=act(request(0,135).copy(identities=ids),definitions=catalog("empire","alliance"))
        assertNull(empire.rejection);assertEquals(3,empire.stocks?.strategic?.people?.get(10001)?.rank)
        val alliance=act(request(0,1).copy(identities=ids),definitions=catalog("alliance","empire"))
        assertNull(alliance.rejection);assertEquals(2,alliance.stocks?.strategic?.people?.get(10001)?.rank,"alliance ranks skip senior admiral")
        val retired=stock().let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+
            (10001 to StrategicPerson(rank=1,militaryRank=9,merit=30))))}
        val kept=act(request(0),retired)
        assertNull(kept.rejection);assertEquals(1,kept.stocks?.strategic?.people?.get(10001)?.rank)
        assertEquals(8,kept.stocks?.strategic?.people?.get(10001)?.militaryRank)
        assertEquals(StrategyActionRejection.RANK,act(request(0),retired,catalog("alliance")).rejection)
    }

    @Test fun promotionDemotionAndDecorationsKeepTheExistingCostsAndMeritRules() {
        val selected=act(request(1).copy(target=10002))
        assertNull(selected.rejection);assertEquals(360,selected.player?.mcp);assertEquals(8,selected.stocks?.strategic?.people?.get(10002)?.rank)
        val demoted=act(request(2).copy(target=10002))
        assertNull(demoted.rejection);assertEquals(680,demoted.player?.mcp)
        assertEquals(10,demoted.stocks?.strategic?.people?.get(10002)?.rank);assertEquals(100L,demoted.stocks?.strategic?.people?.get(10002)?.merit)
        val ids=identities.map {if(it.id==10001)it.copy(rank=6) else it}
        val s=stock(135).let {it.copy(strategic=it.strategic.copy(people=it.strategic.people+(10001 to StrategicPerson(merit=100))))}
        val q=request(4,135).copy(identities=ids)
        val awarded=act(q,s,catalog("empire","alliance"))
        assertNull(awarded.rejection);assertEquals(setOf(0),awarded.stocks?.strategic?.people?.get(10001)?.decorations)
        assertEquals(StrategyActionRejection.MAXIMUM,act(q,assertNotNull(awarded.stocks),catalog("empire","alliance")).rejection)
    }

    @Test fun peerageIsConfiguredAndFiefsExcludeForeignBasesAndActualCapitals() {
        val ids=identities.map {if(it.id==10001)it.copy(rank=6) else it}
        val q=request(3,1).copy(identities=ids,value=Peerage.BARON.ordinal)
        assertEquals(StrategyActionRejection.AUTHORITY,act(q,definitions=catalog(peerage="none")).rejection)
        val grant=act(q,definitions=catalog("alliance","empire"))
        assertNull(grant.rejection)
        val s=assertNotNull(grant.stocks)
        val fief=request(8,1).copy(identities=ids,base=1001)
        val endowed=act(fief,s)
        assertNull(endowed.rejection);assertEquals(360,endowed.player?.mcp)
        assertEquals(10001,endowed.stocks?.strategic?.fiefs?.get(1001))
        assertEquals(StrategyActionRejection.LOCATION,act(fief.copy(base=1000),s).rejection)
        assertEquals(StrategyActionRejection.LOCATION,act(fief.copy(base=1002),s).rejection)
        val revoke=act(fief.copy(command=9),assertNotNull(endowed.stocks))
        assertNull(revoke.rejection);assertTrue(assertNotNull(revoke.stocks).strategic.fiefs.isEmpty())
        val moved=s.copy(strategic=s.strategic.copy(capitals=s.strategic.capitals+(country to 1003),
            offices=s.strategic.offices.map {it.copy(seat=1003)}))
        assertNull(act(fief.copy(base=1000),moved).rejection,"a former original capital can become a fief")
        assertEquals(StrategyActionRejection.LOCATION,act(fief.copy(base=1003),moved).rejection)
    }
}
