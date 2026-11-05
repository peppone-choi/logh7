package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OfficeCountryTest {
    private val empire=GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)
    private val alliance=empire.copy(id=1177,grid=2015,name=Capital.ALLIANCE.baseName)
    private val home=empire.copy(id=1050,name="Home")
    private val foreign=empire.copy(id=1051,name="Other")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(empire,alliance,home,foreign),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val other=CountryId(42)
    private val policy=OrganizationalOfficePolicy(galaxy)
    private fun stocks()=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,0,0,0),1177 to PlanetStock(1177,3,0,0,0,0),
        1050 to PlanetStock(1050,2,0,0,0,0,governorId=10000),
        1051 to PlanetStock(1051,Allegiance.Country(other),0,0,0,0,garrisonCommanderId=10001)),
        outfits=mapOf(42 to OutfitStock(42,other,OutfitKind.FLEET,1049,42,commanderId=10001,
            shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE))),
        warehouses=mapOf(42 to OutfitWarehouse(42,other,OutfitKind.FLEET,1049,42,
            shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE))))

    @Test fun `foreign bases and outfits do not break a domestic office lookup or become its holdings`() {
        val stocks=stocks()
        assertTrue(BaseOfficePolicy(galaxy).holders(stocks.bases.getValue(1051)).isEmpty())
        assertEquals(listOf(OfficeHolding(54,1050,10000)),policy.held(stocks,10000))
        assertTrue(policy.held(stocks,10001).isEmpty())
        assertFalse(policy.active(stocks,OrganizationOffices.all.getValue(55),1051))
        assertFalse(policy.active(stocks,OrganizationOffices.all.getValue(39),42))
        assertFailsWith<IllegalArgumentException> {policy.set(stocks,OfficeHolding(55,1051,10002))}
    }

    @Test fun `captured capitals deactivate stored national and logistics offices for every foreign owner`() {
        for(owner in listOf(Allegiance.Country(CountryId(1)),Allegiance.Country(CountryId(4)),
            Allegiance.Country(other),Allegiance.Neutral,Allegiance.Pirates,Allegiance.Unspecified)) {
            val before=stocks().copy(nationalOffices=NationalOffice.entries.associateWith {10000},
                logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_FIRST))
            val stocks=before.copy(bases=before.bases+(1049 to before.bases.getValue(1049).copy(owner=owner))+
                (1177 to before.bases.getValue(1177).copy(owner=owner)))
            for(office in NationalOffice.entries)assertFalse(office.active(galaxy,stocks))
            for(country in listOf(2,3))assertTrue(NationalOfficePolicy(galaxy).cards(stocks,10000,country).isEmpty())
            assertNull(LogisticsOfficePolicy(galaxy).seat(LogisticsOffice.EMPIRE_FIRST,stocks))
            assertTrue(policy.held(stocks,10000).none {it.card in NationalOffice.entries.map {office->office.card}})
            assertTrue(BaseOfficePolicy(galaxy).holders(stocks.bases.getValue(1049)).isEmpty())
        }
        val invalid=stocks().bases.getValue(1049).copy(owner=Allegiance.Country(other),capitalDefenseCommanderId=10000)
        assertFailsWith<IllegalArgumentException> {BaseOfficePolicy(galaxy).validate(invalid)}
    }

    @Test fun `a matching ship style cannot lend a foreign fleet domestic appointment authority`() {
        val initial=stocks()
        for(style in Power.entries) {
            val fleet=initial.outfits.getValue(42).copy(shipStyle=ShipStyleId.fromLegacy(style))
            val stocks=initial.copy(outfits=mapOf(42 to fleet),warehouses=mapOf(42 to
                initial.warehouses.getValue(42).copy(shipStyle=fleet.shipStyle)))
            for(role in FleetOffice.entries) {
                assertFalse(role.authorized(galaxy,stocks,fleet,10001,4))
                assertFalse(role.friendly(stocks,fleet))
                assertTrue(role.available(galaxy,stocks,10001,4).isEmpty())
            }
        }
    }

    @Test fun `domestic fleets keep their offices when only their ship style differs`() {
        val fleet=stocks().outfits.getValue(42).copy(country=Power.EMPIRE.country,
            shipStyle=ShipStyleId.fromLegacy(Power.ALLIANCE),commanderId=10000)
        val initial=stocks()
        val stocks=initial.copy(outfits=mapOf(42 to fleet),warehouses=mapOf(42 to
            initial.warehouses.getValue(42).copy(country=fleet.country,shipStyle=fleet.shipStyle)))
        assertEquals(listOf(OfficeHolding(39,42,10000)),policy.holders(stocks,39))
        assertTrue(policy.holders(stocks,175).isEmpty())
        assertTrue(FleetOffice.EMPIRE_AIDE.authorized(galaxy,stocks,fleet,10000,4))
        assertTrue(FleetOffice.EMPIRE_AIDE.friendly(stocks,fleet))
    }

    @Test fun `domestic appointments ignore foreign holdings and foreign seats reject without charging CP`() {
        val stocks=stocks()
        val session=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
        val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val request=GarrisonAppointmentRequest("actor","c",10000,Power.EMPIRE,1,1050,10001,55,7,10,2)
        val result=GarrisonAppointment.evaluate(galaxy,request,session,player,stocks)
        assertNull(result.rejection);assertTrue(result.changed);assertEquals(840,result.player!!.mcp)
        assertEquals(10001,result.stocks!!.bases.getValue(1050).garrisonCommanderId)
        assertEquals(stocks.bases.getValue(1051),result.stocks.bases.getValue(1051))
        assertEquals(stocks.outfits,result.stocks.outfits)
        for(foreign in listOf(request.copy(base=1051),request.copy(base=1049,targetCard=39,outfit=42))) {
            val denied=GarrisonAppointment.evaluate(galaxy,foreign,session,player,stocks)
            assertEquals(AppointmentRejection.BASE,denied.rejection);assertFalse(denied.changed)
            assertEquals(player,denied.player);assertEquals(stocks,denied.stocks)
        }
        val legacy=FleetOfficeAppointment.evaluate(galaxy,request.copy(base=1049,targetCard=39,outfit=42),player,stocks)
        assertEquals(AppointmentRejection.TARGET,legacy.rejection);assertEquals(player,legacy.player);assertEquals(stocks,legacy.stocks)
    }
}
