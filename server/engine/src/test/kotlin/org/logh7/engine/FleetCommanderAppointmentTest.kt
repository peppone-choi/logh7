package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FleetCommanderAppointmentTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also { it.join("pilot",Power.EMPIRE,false,"c") }.exportState()
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,number=1)),
        warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),
        nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to 10000))
    private val request=GarrisonAppointmentRequest("pilot","c",10000,Power.EMPIRE,1,0,10001,39,2,5,2,outfit=200)
    private fun act(r:GarrisonAppointmentRequest=request,p:Navigator=player,s:PlanetStocks=stocks)=
        GarrisonAppointment.evaluate(galaxy,r,session,p,s)
    @Test fun ministerAppointsAnActualFleetWithoutReplacingItsInventoryAndRetryPaysNothing() {
        val result=act()
        assertNull(result.rejection);assertTrue(result.changed)
        assertEquals(840,result.player!!.mcp)
        assertEquals(stocks.outfits.getValue(200).copy(commanderId=10001),result.stocks!!.outfits[200])
        assertEquals(stocks.bases,result.stocks.bases);assertEquals(stocks.warehouses,result.stocks.warehouses)
        val retry=act(p=result.player,s=result.stocks)
        assertNull(retry.rejection);assertFalse(retry.changed);assertEquals(result.player,retry.player)
        assertEquals(0,act(p=player.copy(mcp=0,pcp=320)).player!!.pcp)
        assertEquals(AppointmentRejection.CP,act(p=player.copy(mcp=159,pcp=319)).rejection)
        assertEquals(listOf(200),FleetCommanderAppointmentPolicy(galaxy).available(stocks,10000,2,2).map {it.id})
        assertTrue(FleetCommanderAppointmentPolicy(galaxy).available(result.stocks,10000,2,2).isEmpty())
    }
    @Test fun requestsCannotForgeOfficeFactionRankFleetOrSession() {
        assertEquals(AppointmentRejection.AUTHORITY,act(s=stocks.copy(nationalOffices=emptyMap())).rejection)
        assertEquals(AppointmentRejection.AUTHORITY,act(request.copy(actorRank=3)).rejection)
        for(rank in listOf(-1,0,1,2,6,20)) assertEquals(AppointmentRejection.RANK,act(request.copy(targetRank=rank)).rejection)
        for(rank in 3..5) assertNull(act(request.copy(targetRank=rank)).rejection)
        assertEquals(AppointmentRejection.TARGET,act(request.copy(targetPower=3)).rejection)
        assertEquals(AppointmentRejection.TARGET,act(request.copy(target=10000)).rejection)
        assertEquals(AppointmentRejection.BASE,act(request.copy(outfit=999)).rejection)
        assertEquals(AppointmentRejection.BASE,act(request.copy(base=1177)).rejection)
        assertEquals(AppointmentRejection.BASE,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(power=3)))).rejection)
        assertEquals(AppointmentRejection.SESSION,act(request.copy(connection="old")).rejection)
        assertEquals(AppointmentRejection.IDENTITY,act(request.copy(characterId=999)).rejection)
        assertEquals(AppointmentRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        assertEquals(AppointmentRejection.TARGET,act(request.copy(targetCard=55)).rejection)
    }
    @Test fun occupiedFleetAndFullOfficeCardsPreserveStateButMultipleOfficesAreAllowed() {
        val occupied=stocks.copy(outfits=mapOf(200 to stocks.outfits.getValue(200).copy(commanderId=10002)))
        val rejected=act(s=occupied)
        assertEquals(AppointmentRejection.OCCUPIED,rejected.rejection)
        assertEquals(player,rejected.player);assertEquals(occupied,rejected.stocks)
        val held=stocks.copy(nationalOffices=stocks.nationalOffices+(NationalOffice.IMPERIAL_SUPREME_COMMANDER to 10001))
        assertNull(act(s=held).rejection)
        val many=stocks.copy(outfits=stocks.outfits+(1..14).associate { i ->
            (200+i) to OutfitStock(200+i,2,OutfitKind.PATROL,1049,301,commanderId=10001)
        },warehouses=stocks.warehouses+(301 to OutfitWarehouse(301,2,OutfitKind.PATROL,1049)))
        assertEquals(AppointmentRejection.CAPACITY,act(s=many).rejection)
    }
}
