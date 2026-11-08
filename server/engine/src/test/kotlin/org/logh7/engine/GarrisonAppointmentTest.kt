package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class GarrisonAppointmentTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also { it.join("pilot",Power.EMPIRE,false,"c") }.exportState()
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,governorId=10000)))
    private val request=GarrisonAppointmentRequest("pilot","c",10000,Power.EMPIRE,1,1049,10001,55,7,10,2)
    private fun act(r:GarrisonAppointmentRequest=request,p:Navigator=player,s:PlanetStocks=stocks)=GarrisonAppointment.evaluate(galaxy,r,session,p,s)

    @Test fun fourthOfficeOnlyChangesFutureRecoveryAndResignationRestoresIt() = runBlocking {
        assertEquals(listOf(0,0,0,0,5,10,15,20,20), (0..8).map(OfficeCpRecovery::penalty))
        val rules=SessionRules.load().copy(speed=1,cpIntervalSeconds=10)
        val extra=(1050..1052).map {galaxy.bases.single().copy(id=it,name="Post $it")}
        val world=Galaxy(galaxy.systems,galaxy.bases+extra,galaxy.starts)
        val abilities=List(8){100}
        val target=Navigator(1,10001,Power.EMPIRE,2487,200,200,1000,cpRecoveryAt=rules.startSeconds)
        var savedPlayers=mapOf("pilot" to player,"target" to target)
        var savedStocks=stocks.copy(deploymentInitialized=true,bases=stocks.bases+extra.associate {
            it.id to PlanetStock(it.id,2,0,100,200,300,governorId=target.characterId)
        })
        val engine=WorldEngine(this,rules,galaxy=world,ticking=false,
            navigationStore=object:NavigationStore {
                override fun load()=savedPlayers
                override fun save(players:Map<String,Navigator>) { savedPlayers=players }
            },stockStore=object:PlanetStockStore {
                override fun load()=savedStocks
                override fun save(stocks:PlanetStocks) { savedStocks=stocks }
            },worldStateCommitter=WorldStateCommitter { players, stocks ->
                savedPlayers=players; savedStocks=checkNotNull(stocks)
            },npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            suspend fun join(account:String, connection:String) {
                val result=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join(account,Power.EMPIRE,false,result,connection,
                    profile=StrategicPerson(abilities=abilities)))
                assertEquals(Admission.ACCEPTED,result.await())
            }
            suspend fun refresh(connection:String):Navigator {
                val result=CompletableDeferred<NavigationResult>()
                engine.submit(WorldCommand.Navigate(NavigationRequest("target",10001,Power.EMPIRE,connection,1,
                    politics=100,administration=100),result))
                return assertNotNull(result.await().player)
            }
            join("pilot","c")
            join("target","t")
            engine.submit(WorldCommand.AdvanceTo(20_000))
            val appointment=CompletableDeferred<GarrisonAppointmentResult>()
            engine.submit(WorldCommand.AppointGarrison(request,appointment))
            assertTrue(appointment.await().changed)
            assertEquals(400,savedPlayers.getValue("target").pcp)
            assertEquals(400,savedPlayers.getValue("target").mcp)

            engine.submit(WorldCommand.AdvanceTo(30_000))
            assertEquals(497,refresh("t").pcp)
            engine.submit(WorldCommand.AdvanceTo(40_000))
            val resignation=CompletableDeferred<OfficeResignationResult>()
            engine.submit(WorldCommand.ResignOffice(OfficeResignationRequest("target","t",10001,Power.EMPIRE,1,55,
                politics=100,administration=100),resignation))
            val resigned=assertNotNull(resignation.await().player)
            assertEquals(594,resigned.pcp)
            assertEquals(514,resigned.mcp)
            assertEquals(3,OrganizationalOfficePolicy(world).held(savedStocks,10001).size)
            engine.submit(WorldCommand.Disconnect("target","t"))
            join("target","t2")
            engine.submit(WorldCommand.AdvanceTo(50_000))
            val recovered=refresh("t2")
            assertEquals(694,recovered.pcp)
            assertEquals(614,recovered.mcp)
        } finally {engine.drainAndClose()}
    }
    @Test fun deceasedActorOrCandidateCannotChangeOfficesOrSpendCp() {
        for((character,rejection) in listOf(10000 to AppointmentRejection.IDENTITY,10001 to AppointmentRejection.TARGET)) {
            val state=stocks.copy(strategic=StrategicState(people=mapOf(character to StrategicPerson(deceasedAt=1))))
            val result=act(s=state)
            assertEquals(rejection,result.rejection)
            assertEquals(player,result.player)
            assertEquals(state,result.stocks)
        }
    }
    @Test fun actualForeignAndUnattachedMembershipCannotBeUsedForAppointment() {
        for(country in listOf(CountryId(1),CountryId(4),CountryId(42),null)) {
            val denied=act(request.copy(targetCountry=country))
            assertEquals(AppointmentRejection.TARGET,denied.rejection)
            assertEquals(stocks,denied.stocks);assertEquals(player,denied.player)
        }
        assertEquals(AppointmentRejection.IDENTITY,act(p=player.copy(country=CountryId(42))).rejection)
    }
    @Test fun storedGovernorAppointsWithMcpOrWholePcpSubstitutionAndRetryDoesNotPayAgain() {
        val result=act();assertNull(result.rejection);assertTrue(result.changed)
        assertEquals(840,result.player!!.mcp);assertEquals(1000,result.player.pcp)
        assertEquals(10001,result.stocks!!.bases.getValue(1049).garrisonCommanderId)
        assertEquals(10000,result.stocks.bases.getValue(1049).governorId)
        val retry=act(p=result.player,s=result.stocks)
        assertNull(retry.rejection);assertFalse(retry.changed);assertEquals(result.player,retry.player)
        assertEquals(0,act(p=player.copy(mcp=0,pcp=320)).player!!.pcp)
        assertEquals(AppointmentRejection.CP,act(p=player.copy(mcp=159,pcp=319)).rejection)
    }
    @Test fun authorityRankFactionIdentityAndSessionAreServerConditions() {
        assertEquals(AppointmentRejection.AUTHORITY,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(governorId=0)))).rejection)
        assertEquals(AppointmentRejection.AUTHORITY,act(request.copy(targetCard=59)).rejection)
        assertEquals(AppointmentRejection.IDENTITY,act(request.copy(country=CountryId(3)),player.copy(country=Power.ALLIANCE.country,shipStyle=ShipStyleId.fromLegacy(Power.ALLIANCE))).rejection)
        for(rank in listOf(-1,0,1,7,11,20)) assertEquals(AppointmentRejection.RANK,act(request.copy(targetRank=rank)).rejection)
        assertEquals(AppointmentRejection.AUTHORITY,act(request.copy(actorRank=10)).rejection)
        assertNull(act(request.copy(actorRank=1)).rejection)
        assertEquals(AppointmentRejection.TARGET,act(request.copy(target=10000)).rejection)
        assertEquals(AppointmentRejection.TARGET,act(request.copy(targetCountry=CountryId(3))).rejection)
        assertEquals(AppointmentRejection.SESSION,act(request.copy(connection="stale")).rejection)
        assertEquals(AppointmentRejection.SESSION,act(request.copy(generation=2)).rejection)
        assertEquals(AppointmentRejection.IDENTITY,act(request.copy(characterId=999)).rejection)
        assertEquals(AppointmentRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        assertEquals(AppointmentRejection.BASE,act(request.copy(base=1050)).rejection)
        assertEquals(AppointmentRejection.BASE,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(owner=Allegiance.fromLegacy(3))))).rejection)
    }
    @Test fun occupiedPostsAndCardCapacityDoNotChargeOrReplaceTheHolder() {
        assertEquals(AppointmentRejection.OCCUPIED,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(garrisonCommanderId=10002)))).rejection)
        val many=stocks.copy(bases=stocks.bases+(1..14).associateWith { PlanetStock(it,2,0,0,0,0,governorId=10001) })
        val extended=Galaxy(galaxy.systems,galaxy.bases+(1..14).map { galaxy.bases.single().copy(id=it) },galaxy.starts)
        val rejected=GarrisonAppointment.evaluate(extended,request,session,player,many)
        assertEquals(AppointmentRejection.CAPACITY,rejected.rejection)
        assertEquals(player,rejected.player);assertEquals(many,rejected.stocks)
    }
    @Test fun aPersonAlreadyHoldingTheSameCardKindElsewhereIsNotAppointedAgain() {
        val extended=Galaxy(galaxy.systems,galaxy.bases+galaxy.bases.single().copy(id=1050),galaxy.starts)
        val held=stocks.copy(bases=stocks.bases+(1050 to PlanetStock(1050,2,0,0,0,0,garrisonCommanderId=10001)))
        val rejected=GarrisonAppointment.evaluate(extended,request,session,player,held)
        assertEquals(AppointmentRejection.TARGET,rejected.rejection);assertEquals(player,rejected.player);assertEquals(held,rejected.stocks)
        assertNull(GarrisonAppointment.evaluate(extended,request.copy(target=10002),session,player,held).rejection)
    }
}
