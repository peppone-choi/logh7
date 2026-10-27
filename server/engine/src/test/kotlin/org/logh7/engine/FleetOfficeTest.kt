package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FleetOfficeTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2015,4,"Other",3)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1200,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    @Test fun everyFleetRoleUsesItsActualAppointerAndSurvivesReplayAndIndividualResignation() {
        for(role in FleetOffice.entries) {
            val power=if(role.power==2)Power.EMPIRE else Power.ALLIANCE
            val base=if(role.power==2)1049 else 1200;val grid=if(role.power==2)2487 else 2015
            val authority=NationalOffice.of(role.authority)
            val rank=if(authority!=null)2 else if(role.power==2)5 else 6
            val player=Navigator(1,10000,power,grid,1000,1000,1000,baseId=base,mode=4)
            val stock=PlanetStocks(1,0,mapOf(base to PlanetStock(base,role.power,0,100,200,300)),
                outfits=mapOf(200 to OutfitStock(200,role.power,OutfitKind.FLEET,base,300,
                    commanderId=if(authority==null)10000 else 0)),
                warehouses=mapOf(300 to OutfitWarehouse(300,role.power,OutfitKind.FLEET,base,200)),
                nationalOffices=if(authority==null)emptyMap() else mapOf(authority to 10000))
            val session=SessionSimulation(SessionRules.load()).also {it.join("actor",power,false,"c")}.exportState()
            val request=GarrisonAppointmentRequest("actor","c",10000,power,1,0,10001,role.card,rank,role.lowest,role.power,outfit=200)
            val result=GarrisonAppointment.evaluate(galaxy,request,session,player,stock)
            assertNull(result.rejection,role.name);assertEquals(840,result.player!!.mcp)
            assertEquals(listOf(10001),role.holders(result.stocks!!.outfits.getValue(200)))
            val retry=GarrisonAppointment.evaluate(galaxy,request,session,result.player,result.stocks)
            assertNull(retry.rejection);assertFalse(retry.changed);assertEquals(result.player,retry.player)
            val unauthorized=stock.copy(nationalOffices=emptyMap(),outfits=stock.outfits.mapValues {(_,o)->o.copy(commanderId=0)})
            val denied=GarrisonAppointment.evaluate(galaxy,request,session,player,unauthorized)
            assertEquals(AppointmentRejection.AUTHORITY,denied.rejection);assertEquals(player,denied.player)
            val targetSession=SessionSimulation(SessionRules.load()).also {it.join("target",power,false,"d")}.exportState()
            val resigned=OfficeResignation.evaluate(galaxy,OfficeResignationRequest("target","d",10001,power,1,role.card),
                targetSession,player.copy(characterId=10001),result.stocks)
            assertNull(resigned.rejection,role.name);assertEquals(920,resigned.player!!.mcp)
            assertEquals(stock.outfits,resigned.stocks!!.outfits)
        }
    }
    @Test fun sixStaffSeatsDoNotReplaceEachOtherAndTheSeventhPaysNothing() {
        val role=FleetOffice.EMPIRE_STAFF
        var stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000,aideId=10001)),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
        val session=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        fun request(id:Int)=GarrisonAppointmentRequest("actor","c",10000,Power.EMPIRE,1,0,id,42,5,11,2,outfit=200)
        for(id in 10002..10007) {
            val result=GarrisonAppointment.evaluate(galaxy,request(id),session,player,stock)
            assertNull(result.rejection);stock=result.stocks!!
        }
        assertEquals((10002..10007).toList(),stock.outfits.getValue(200).staff.officers)
        val denied=GarrisonAppointment.evaluate(galaxy,request(10008),session,player,stock)
        assertEquals(AppointmentRejection.OCCUPIED,denied.rejection);assertEquals(stock,denied.stocks);assertEquals(player,denied.player)
        assertTrue(role.available(galaxy,stock,10000,5).isEmpty())
        assertEquals(10000,stock.outfits.getValue(200).commanderId);assertEquals(10001,stock.outfits.getValue(200).aideId)
        assertFails {stock.outfits.getValue(200).copy(staff=FleetStaff(officers=listOf(10000)))}
        val people=(10004..10009).toMutableList();val staff=FleetStaff(10002,10003,people);people.clear()
        assertEquals(8,staff.members.size)
        assertFails {(staff.officers as MutableList<Int>).clear()}
        assertEquals(10,stock.outfits.getValue(200).copy(staff=staff).members().size)
    }
}
