package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class EliminatedOfficeCommandsTest {
    private val empire=CountryId(2)
    private val alliance=CountryId(3)
    private val third=CountryId(42)
    private val capital=1049
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2)),
        listOf(GalaxyBase(capital,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val actor=Navigator(1,10000,empire,2487,1000,1000,1000)
    private val vacant=PlanetStocks(1,0,mapOf(capital to PlanetStock(capital,2,0,100,200,300)),
        nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to 10000))
    private val staffed=vacant.copy(bases=vacant.bases.mapValues {(_,base)->base.copy(guardInspectorId=10001)})

    private fun sessions():Pair<SessionState,SessionState> {
        val sim=SessionSimulation(SessionRules.load())
        assertEquals(Admission.ACCEPTED,sim.join("actor",empire,false,"c"))
        sim.updateCountryTerritory(mapOf(empire to 4,alliance to 4,third to 4),emptyMap())
        val active=sim.exportState()
        sim.updateCountryTerritory(mapOf(empire to 3,alliance to 4,third to 4),emptyMap(),setOf(empire))
        val defeated=sim.exportState()
        assertNull(defeated.ended)
        assertEquals(EndReason.SYSTEMS_REDUCED,defeated.eliminated.getValue(empire).reason)
        assertEquals(active.participants,defeated.participants)
        return active to defeated
    }

    @Test fun eliminatedCountryCannotAppointToItsRemainingCapitalOffice() {
        val (active,defeated)=sessions()
        val request=GarrisonAppointmentRequest("actor","c",10000,empire,1,capital,10001,57,2,3,empire)
        val allowed=GarrisonAppointment.evaluate(galaxy,request,active,actor,vacant)
        assertNull(allowed.rejection)
        assertEquals(840,allowed.player?.mcp)
        assertEquals(10001,allowed.stocks?.bases?.get(capital)?.guardInspectorId)
        assertEquals(GarrisonAppointmentResult(actor,vacant,AppointmentRejection.SESSION),
            GarrisonAppointment.evaluate(galaxy,request,defeated,actor,vacant))
    }

    @Test fun eliminatedCountryCannotDismissAHolderFromItsRemainingCapitalOffice() {
        val (active,defeated)=sessions()
        val request=OfficeDismissalRequest("actor","c",10000,empire,1,10001,57,2,empire)
        val allowed=OfficeDismissal.evaluate(galaxy,request,active,actor,staffed)
        assertNull(allowed.rejection)
        assertEquals(840,allowed.player?.mcp)
        assertEquals(vacant,allowed.stocks)
        assertEquals(OfficeDismissalResult(actor,staffed,DismissalRejection.SESSION),
            OfficeDismissal.evaluate(galaxy,request,defeated,actor,staffed))
    }

    @Test fun eliminatedCountryCannotResignItsRemainingCapitalOffice() {
        val (active,defeated)=sessions()
        val request=OfficeResignationRequest("actor","c",10000,empire,1,17)
        val allowed=OfficeResignation.evaluate(galaxy,request,active,actor,staffed)
        assertNull(allowed.rejection)
        assertEquals(920,allowed.player?.mcp)
        assertEquals(staffed.copy(nationalOffices=emptyMap()),allowed.stocks)
        assertEquals(OfficeResignationResult(actor,staffed,ResignationRejection.SESSION),
            OfficeResignation.evaluate(galaxy,request,defeated,actor,staffed))
    }
}
