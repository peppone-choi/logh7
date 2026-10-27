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
        assertEquals(AppointmentRejection.TARGET,act(request.copy(targetPower=3)).rejection)
        assertEquals(AppointmentRejection.BASE,act(request.copy(base=1)).rejection)
        assertEquals(AppointmentRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        val occupied=stocks.copy(outfits=mapOf(200 to stocks.outfits.getValue(200).copy(aideId=10002)))
        val rejected=act(s=occupied)
        assertEquals(AppointmentRejection.OCCUPIED,rejected.rejection);assertEquals(occupied,rejected.stocks);assertEquals(player,rejected.player)
        assertEquals(AppointmentRejection.BASE,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(power=3)))).rejection)
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
        val allied=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(power=3)),
            outfits=mapOf(200 to stocks.outfits.getValue(200).copy(power=3)),
            warehouses=mapOf(300 to stocks.warehouses.getValue(300).copy(power=3)))
        val state=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        val r=request.copy(power=Power.ALLIANCE,targetCard=179,actorRank=6,targetPower=3)
        val result=GarrisonAppointment.evaluate(galaxy,r,state,player.copy(power=Power.ALLIANCE),allied)
        assertNull(result.rejection);assertEquals(10001,result.stocks!!.outfits.getValue(200).aideId)
        assertEquals(listOf(200),FleetAideAppointmentPolicy().available(allied,10000,3,6).map {it.id})
        assertEquals(AppointmentRejection.AUTHORITY,GarrisonAppointment.evaluate(galaxy,r.copy(targetCard=43),state,
            player.copy(power=Power.ALLIANCE),allied).rejection)
    }
}
