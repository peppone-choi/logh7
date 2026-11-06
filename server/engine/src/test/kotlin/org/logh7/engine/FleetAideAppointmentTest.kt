package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FleetAideAppointmentTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000)),
        warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
    private val request=GarrisonAppointmentRequest("pilot","c",10000,Power.EMPIRE,1,0,10001,43,5,12,2,outfit=200)
    private fun act(r:GarrisonAppointmentRequest=request,p:Navigator=player,s:PlanetStocks=stocks)=
        GarrisonAppointment.evaluate(galaxy,r,session,p,s)
    @Test fun actualCommanderAppointsLieutenantWithoutChangingCommandAndReplayDoesNotPay() {
        val result=act();assertNull(result.rejection);assertTrue(result.changed)
        assertEquals(840,result.player!!.mcp)
        assertEquals(stocks.outfits.getValue(200).copy(aideId=10001),result.stocks!!.outfits[200])
        val retry=act(p=result.player,s=result.stocks)
        assertFalse(retry.changed);assertEquals(result.player,retry.player)
        assertEquals(0,act(p=player.copy(mcp=0,pcp=320)).player!!.pcp)
        assertEquals(AppointmentRejection.CP,act(p=player.copy(mcp=159,pcp=319)).rejection)
    }
    @Test fun identityAuthorityRanksOccupiedAndHostileSeatsRejectWithoutMutation() {
        assertEquals(AppointmentRejection.SESSION,act(request.copy(connection="stale")).rejection)
        assertEquals(AppointmentRejection.AUTHORITY,act(s=stocks.copy(outfits=mapOf(200 to stocks.outfits.getValue(200).copy(commanderId=10002)))).rejection)
        assertEquals(AppointmentRejection.AUTHORITY,act(request.copy(targetCard=179)).rejection)
        for(rank in listOf(0,1,5,13,20)) assertEquals(AppointmentRejection.RANK,act(request.copy(targetRank=rank)).rejection)
        for(rank in 6..12) assertNull(act(request.copy(targetRank=rank)).rejection)
        assertEquals(AppointmentRejection.TARGET,act(request.copy(targetCountry=CountryId(3))).rejection)
        assertEquals(AppointmentRejection.BASE,act(request.copy(base=1)).rejection)
        assertEquals(AppointmentRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        val occupied=stocks.copy(outfits=mapOf(200 to stocks.outfits.getValue(200).copy(aideId=10002)))
        val rejected=act(s=occupied)
        assertEquals(AppointmentRejection.OCCUPIED,rejected.rejection);assertEquals(occupied,rejected.stocks);assertEquals(player,rejected.player)
        assertEquals(AppointmentRejection.BASE,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(owner=Allegiance.fromLegacy(3))))).rejection)
        assertFails {stocks.outfits.getValue(200).copy(aideId=10000)}
    }
    @Test fun aideCardsCountTowardCapacityAndResigningDoesNotRemoveTheCommander() {
        val many=stocks.copy(outfits=stocks.outfits+(1..14).associate {i ->
            (200+i) to OutfitStock(200+i,2,OutfitKind.FLEET,1049,300+i,commanderId=10002,aideId=10001)
        },warehouses=stocks.warehouses+(1..14).associate {i ->
            (300+i) to OutfitWarehouse(300+i,2,OutfitKind.FLEET,1049,200+i)
        })
        assertEquals(AppointmentRejection.CAPACITY,act(s=many).rejection)
        val appointed=act().stocks!!
        val targetSession=SessionSimulation(SessionRules.load()).also {it.join("aide",Power.EMPIRE,false,"d")}.exportState()
        val target=player.copy(characterId=10001)
        val resigned=OfficeResignation.evaluate(galaxy,OfficeResignationRequest("aide","d",10001,Power.EMPIRE,1,43),targetSession,target,appointed)
        assertNull(resigned.rejection);assertEquals(920,resigned.player!!.mcp)
        assertEquals(stocks.outfits,resigned.stocks!!.outfits)
    }
    @Test fun allianceUsesItsOwnCardAndStoredFleetAuthority() {
        val allied=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(owner=Allegiance.fromLegacy(3))),
            outfits=mapOf(200 to stocks.outfits.getValue(200).copy(country=CountryId(3),shipStyle=ShipStyleId.fromLegacy(3))),
            warehouses=mapOf(300 to stocks.warehouses.getValue(300).copy(country=CountryId(3),shipStyle=ShipStyleId.fromLegacy(3))))
        val state=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        val r=request.copy(country=CountryId(3),targetCard=179,actorRank=6,targetCountry=CountryId(3))
        val result=GarrisonAppointment.evaluate(galaxy,r,state,player.copy(country=Power.ALLIANCE.country,shipStyle=ShipStyleId.fromLegacy(Power.ALLIANCE)),allied)
        assertNull(result.rejection);assertEquals(10001,result.stocks!!.outfits.getValue(200).aideId)
        assertEquals(listOf(200),FleetAideAppointmentPolicy().available(allied,10000,3,6).map {it.id})
        assertEquals(AppointmentRejection.AUTHORITY,GarrisonAppointment.evaluate(galaxy,r.copy(targetCard=43),state,
            player.copy(country=Power.ALLIANCE.country,shipStyle=ShipStyleId.fromLegacy(Power.ALLIANCE)),allied).rejection)
    }

    // Added countries: 42 uses the empire office family but displays as the alliance (native 3);
    // 43 uses the alliance family but displays as the empire (native 2).
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val home=GalaxyBase(1050,2487,80,0,"Home",0,20f,86400,0,0f,2f)
    private val countryGalaxy=Galaxy(galaxy.systems,galaxy.bases+home,galaxy.starts)
    /** Fleet 200 at 1049 belongs to [country] (commander 10000); fleet 201 at 1050 belongs to [other] (commander 10002). */
    private val style=ShipStyleId.fromLegacy(Power.EMPIRE)
    private fun countryStocks(country:CountryId,other:CountryId=CountryId(2))=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,Allegiance.Country(country),0,100,200,300),
        1050 to PlanetStock(1050,Allegiance.Country(other),0,100,200,300)),
        outfits=mapOf(200 to OutfitStock(200,country,OutfitKind.FLEET,1049,300,commanderId=10000,shipStyle=style),
            201 to OutfitStock(201,other,OutfitKind.FLEET,1050,301,commanderId=10002,shipStyle=style)),
        warehouses=mapOf(300 to OutfitWarehouse(300,country,OutfitKind.FLEET,1049,200,shipStyle=style),
            301 to OutfitWarehouse(301,other,OutfitKind.FLEET,1050,201,shipStyle=style)))
    private fun countrySession(country:CountryId)=SessionSimulation(SessionRules.load()).also {it.join("pilot",country,false,"c")}.exportState()
    private fun countryPlayer(country:CountryId,id:Int=10000)=Navigator(1,id,country,2487,1000,1000,1000,shipStyle=style)
    private fun countryRequest(country:CountryId,card:Int,actorRank:Int,targetRank:Int=12,outfit:Int=200,actor:Int=10000)=
        GarrisonAppointmentRequest("pilot","c",actor,country,1,0,10001,card,actorRank,targetRank,country,outfit=outfit)
    private fun countryAct(country:CountryId,r:GarrisonAppointmentRequest,s:PlanetStocks=countryStocks(country),
        actor:Int=10000,countries:CountryCatalog=catalog)=
        GarrisonAppointment.evaluate(countryGalaxy,r,countrySession(country),countryPlayer(country,actor),s,countries)

    @Test fun addedCountriesAppointAidesByTheirOwnOfficeFamilyAndStoreTheSameAideField() {
        for((id,card,rank) in listOf(Triple(42,43,5),Triple(43,179,6))) {
            val country=CountryId(id);val before=countryStocks(country)
            val result=countryAct(country,countryRequest(country,card,rank),before)
            assertNull(result.rejection,"$id");assertTrue(result.changed);assertEquals(840,result.player!!.mcp)
            // Same OutfitStock.aideId field as the original two countries, so the saved format is unchanged.
            assertEquals(before.outfits.getValue(200).copy(aideId=10001),result.stocks!!.outfits.getValue(200))
            assertEquals(before.outfits.getValue(201),result.stocks.outfits.getValue(201))
            // The other family's card, not the display code's card, is no authority.
            assertEquals(AppointmentRejection.AUTHORITY,countryAct(country,countryRequest(country,if(card==43)179 else 43,rank),before).rejection)
        }
        // Commander ranks come from each family: empire 2..5, alliance 2/4/5/6 (no 上級大将).
        assertEquals(AppointmentRejection.AUTHORITY,countryAct(CountryId(42),countryRequest(CountryId(42),43,6)).rejection)
        assertEquals(AppointmentRejection.AUTHORITY,countryAct(CountryId(43),countryRequest(CountryId(43),179,3)).rejection)
        assertEquals(AppointmentRejection.RANK,countryAct(CountryId(43),countryRequest(CountryId(43),179,2,targetRank=3)).rejection)
    }

    @Test fun aSharedDisplayCodeLendsNoFleetBaseOrCommandAcrossCountries() {
        // 43 displays as the empire: neither 43 nor the original empire may act on the other's fleet.
        val mixed=countryStocks(CountryId(43),CountryId(2))
        val into2=countryAct(CountryId(43),countryRequest(CountryId(43),179,6,outfit=201),mixed)
        assertEquals(AppointmentRejection.BASE,into2.rejection);assertEquals(mixed,into2.stocks);assertFalse(into2.changed)
        val from2=GarrisonAppointment.evaluate(countryGalaxy,countryRequest(CountryId(2),43,5,outfit=200,actor=10002),
            countrySession(CountryId(2)),countryPlayer(CountryId(2),10002),mixed,catalog)
        assertEquals(AppointmentRejection.BASE,from2.rejection);assertEquals(mixed,from2.stocks)
        // 42 displays as the alliance and uses empire cards; the original alliance's fleet stays foreign.
        val other=countryStocks(CountryId(42),CountryId(3))
        assertEquals(AppointmentRejection.BASE,countryAct(CountryId(42),countryRequest(CountryId(42),43,5,outfit=201),other).rejection)
        // A target registered in another country is never appointed, whatever its display code.
        assertEquals(AppointmentRejection.TARGET,countryAct(CountryId(42),
            countryRequest(CountryId(42),43,5).copy(targetCountry=CountryId(3))).rejection)
    }

    @Test fun theStoredCountryRankNotTheReportedProfileRankDecidesAuthorityAndEligibility() {
        fun ranked(s:PlanetStocks,vararg people:Pair<Int,Int>)=s.copy(strategic=s.strategic.copy(
            people=people.associate {(id,rank)->id to StrategicPerson(rank=rank)}))
        // Original empire: a commander demoted to 少将 (6) on the server ladder loses the aide card,
        // and one promoted to 中将 (5) gains it, whatever rank the client reports.
        assertEquals(AppointmentRejection.AUTHORITY,act(s=ranked(stocks,10000 to 6)).rejection)
        assertNull(act(request.copy(actorRank=6),s=ranked(stocks,10000 to 5)).rejection)
        // Target eligibility uses the target's stored rank (aide: 2..12).
        assertEquals(AppointmentRejection.RANK,act(s=ranked(stocks,10001 to 13)).rejection)
        assertNull(act(request.copy(targetRank=20),s=ranked(stocks,10001 to 12)).rejection)
        val unchanged=ranked(stocks,10001 to 13);val denied=act(s=unchanged)
        assertEquals(unchanged,denied.stocks);assertEquals(player,denied.player)
        // Added alliance-family country 43: the alliance ladder applies the same way.
        val c43=CountryId(43)
        assertEquals(AppointmentRejection.AUTHORITY,countryAct(c43,countryRequest(c43,179,6),ranked(countryStocks(c43),10000 to 3)).rejection)
        assertNull(countryAct(c43,countryRequest(c43,179,3),ranked(countryStocks(c43),10000 to 6)).rejection)
        assertEquals(AppointmentRejection.RANK,countryAct(c43,countryRequest(c43,179,6),ranked(countryStocks(c43),10001 to 3)).rejection)
    }

    @Test fun lostBasesAndRemovedCountriesRejectWithoutMutationWhileTheOriginalCountriesStillAppoint() {
        val country=CountryId(42);val before=countryStocks(country)
        val lost=before.copy(bases=before.bases+(1049 to before.bases.getValue(1049).copy(owner=Allegiance.Country(CountryId(2)))))
        val defeated=countryAct(country,countryRequest(country,43,5),lost)
        assertEquals(AppointmentRejection.BASE,defeated.rejection);assertEquals(lost,defeated.stocks);assertFalse(defeated.changed)
        val removed=CountryCatalogLoader.load()
        val unknown=countryAct(country,countryRequest(country,43,5),before,countries=removed)
        assertEquals(AppointmentRejection.AUTHORITY,unknown.rejection);assertEquals(before,unknown.stocks)
        // Original countries keep their result with the extended catalogue loaded.
        assertNull(GarrisonAppointment.evaluate(galaxy,request,session,player,stocks,catalog).rejection)
    }
}
