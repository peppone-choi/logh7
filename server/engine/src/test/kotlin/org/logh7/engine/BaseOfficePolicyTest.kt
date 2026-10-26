package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class BaseOfficePolicyTest {
    private val empire=GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)
    private val alliance=empire.copy(id=1200,grid=2015,name="ハイネセン")
    private val ordinary=empire.copy(id=1048,name="Home")
    private val fortress=empire.copy(id=1001,kind=1,name="Fortress")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(empire,alliance,ordinary,fortress),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val policy=BaseOfficePolicy(galaxy)
    @Test fun legacyCapitalAssignmentsAreRetainedButNeverPromotedIntoDifferentOffices() {
        val old=PlanetStock(1049,2,0,0,0,0,governorId=10000,garrisonCommanderId=10001)
        assertEquals(emptyMap(),policy.holders(old))
        assertEquals(10000,old.governorId);assertEquals(10001,old.garrisonCommanderId)
        assertEquals(mapOf(56 to 10002,57 to 10003),policy.holders(old.copy(
            capitalDefenseCommanderId=10002,guardInspectorId=10003)))
        assertEquals(mapOf(192 to 10004,193 to 10005),policy.holders(PlanetStock(1200,3,0,0,0,0,
            governorId=10000,garrisonCommanderId=10001,capitalAdministratorId=10004,capitalDefenseCommanderId=10005)))
        assertEquals(mapOf(54 to 10000,55 to 10001),policy.holders(old.copy(baseId=1048)))
        assertEquals(mapOf(52 to 10001),policy.holders(old.copy(baseId=1001)))
    }
    @Test fun capitalIdentityRequiresBothOriginalNameAndGridAndWrongOfficesCannotBeImported() {
        assertFalse(policy.ordinaryPlanet(1049));assertFalse(policy.ordinaryPlanet(1200))
        assertTrue(policy.ordinaryPlanet(1048));assertFalse(policy.ordinaryPlanet(1001))
        assertNull(Capital.of(empire.copy(grid=2015)))
        assertNull(Capital.of(empire.copy(name="Home")))
        val base=PlanetStock(1048,2,0,0,0,0)
        assertFails { policy.validate(base.copy(capitalDefenseCommanderId=10000)) }
        assertFails { policy.validate(base.copy(baseId=1049,power=3,capitalDefenseCommanderId=10000)) }
        assertFails { base.copy(capitalAdministratorId=10000) }
        assertFails { base.copy(guardInspectorId=-1) }
    }
    @Test fun aLegacyCapitalGovernorCannotSpendCpOnOrdinaryPlanetCommands() {
        val session=SessionSimulation(SessionRules.load()).also { it.join("pilot",Power.EMPIRE,false,"c") }.exportState()
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4)
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0,governorId=10000)))
        val tax=PlanetTax.evaluate(galaxy,TaxRequest("pilot","c",10000,Power.EMPIRE,1,1049,35,54),session,player,stocks)
        assertEquals(TaxRejection.ACCESS,tax.rejection);assertEquals(player,tax.player);assertEquals(stocks,tax.stocks)
        val appointment=GarrisonAppointment.evaluate(galaxy,GarrisonAppointmentRequest("pilot","c",10000,Power.EMPIRE,
            1,1049,10001,55,1,10,2),session,player,stocks)
        assertEquals(AppointmentRejection.BASE,appointment.rejection)
        assertEquals(player,appointment.player);assertEquals(stocks,appointment.stocks)
    }
}
