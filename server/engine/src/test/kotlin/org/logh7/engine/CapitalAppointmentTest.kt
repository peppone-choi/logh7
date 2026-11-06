package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CapitalAppointmentTest {
    private val empire=GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)
    private val alliance=empire.copy(id=1177,grid=2015,model=61,name="ハイネセン")
    private val ordinary=empire.copy(id=1048,name="Other")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(empire,alliance,ordinary),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val policy=CapitalAppointmentPolicy(galaxy)
    private fun stock(rule:CapitalAppointmentPolicy.Rule)=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,0,0,0),1177 to PlanetStock(1177,3,0,0,0,0),1048 to PlanetStock(1048,2,0,0,0,0)),
        nationalOffices=mapOf(rule.authority to 10000))
    private fun request(rule:CapitalAppointmentPolicy.Rule):GarrisonAppointmentRequest {
        val actor=when(rule.card){56->2;57->2;192->1;else->5}
        return GarrisonAppointmentRequest("pilot","c",10000,if(rule.power==2)Power.EMPIRE else Power.ALLIANCE,
            1,if(rule.power==2)1049 else 1177,10001,rule.card,actor,rule.lowest,rule.power)
    }
    private fun act(rule:CapitalAppointmentPolicy.Rule,q:GarrisonAppointmentRequest=request(rule),s:PlanetStocks=stock(rule),
        p:Navigator=Navigator(1,10000,q.country,if(q.country==CountryId(2))2487 else 2015,1000,1000,1000)):GarrisonAppointmentResult {
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",q.country,false,"c")}.exportState()
        return GarrisonAppointment.evaluate(galaxy,q,session,p,s)
    }
    @Test fun allFourCapitalOfficesRequireTheirStoredAuthorityAndPayOnce() {
        for(rule in CapitalAppointmentPolicy.rules) {
            val q=request(rule);val original=stock(rule)
            assertEquals(listOf(rule.card),policy.vacancies(original,10000,rule.power,q.actorRank).map {it.rule.card})
            val result=act(rule)
            assertNull(result.rejection);assertTrue(result.changed);assertEquals(840,result.player!!.mcp)
            assertEquals(10001,rule.holder(result.stocks!!.bases.getValue(q.base)))
            assertEquals(original.nationalOffices,result.stocks.nationalOffices)
            assertEquals(0,result.stocks.bases.getValue(q.base).governorId)
            val retry=act(rule,s=result.stocks,p=result.player)
            assertNull(retry.rejection);assertFalse(retry.changed);assertEquals(result.player,retry.player)
            assertTrue(policy.vacancies(result.stocks,10000,rule.power,q.actorRank).isEmpty())
            assertEquals(AppointmentRejection.AUTHORITY,act(rule,s=original.copy(nationalOffices=emptyMap())).rejection)
            assertEquals(AppointmentRejection.BASE,act(rule,q.copy(base=1048)).rejection)
            assertEquals(AppointmentRejection.TARGET,act(rule,q.copy(targetCountry=CountryId(if(rule.power==2)3 else 2))).rejection)
            assertEquals(AppointmentRejection.RANK,act(rule,q.copy(targetRank=20)).rejection)
            assertEquals(AppointmentRejection.AUTHORITY,act(rule,q.copy(actorRank=20)).rejection)
            assertEquals(AppointmentRejection.CP,act(rule,p=result.player.copy(mcp=159,pcp=319)).rejection)
            val taken=rule.assign(original.bases.getValue(q.base),10002)
            assertEquals(AppointmentRejection.OCCUPIED,act(rule,s=original.copy(bases=original.bases+(q.base to taken))).rejection)
        }
    }
    @Test fun baseZeroAppointsAtEachCountrysMovedCapitalWithoutCreatingInvalidLegacyFields() {
        for(rule in CapitalAppointmentPolicy.rules) {
            val country=CountryId(rule.power)
            val moved=ordinary.copy(id=1500,grid=2488,name="New Capital")
            val expanded=Galaxy(galaxy.systems+GalaxySystem(5,2488,4,"New",rule.power),galaxy.bases+moved,galaxy.starts)
            val before=stock(rule).let {s->s.copy(bases=s.bases+(moved.id to PlanetStock(moved.id,rule.power,0,0,0,0)),
                strategic=s.strategic.copy(capitals=mapOf(country to moved.id)))}
            val query=request(rule).copy(base=0)
            val player=Navigator(1,10000,country,2488,1000,1000,1000)
            val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",country,false,"c")}.exportState()
            val result=GarrisonAppointment.evaluate(expanded,query,session,player,before)
            assertNull(result.rejection);assertTrue(result.changed);assertEquals(840,result.player!!.mcp)
            val saved=checkNotNull(result.stocks);val policy=OrganizationalOfficePolicy(expanded)
            assertEquals(listOf(OfficeHolding(rule.card,moved.id,10001,country)),policy.holders(saved,rule.card,country))
            assertEquals(0,rule.holder(saved.bases.getValue(moved.id)))
            assertTrue(saved.bases.values.all {BaseOfficePolicy(expanded).holders(it).isEmpty()})
            val retry=GarrisonAppointment.evaluate(expanded,query,session,result.player,saved)
            assertNull(retry.rejection);assertFalse(retry.changed);assertEquals(840,retry.player!!.mcp)
            val denied=GarrisonAppointment.evaluate(expanded,query,session,player.copy(mcp=159,pcp=319),before)
            assertEquals(AppointmentRejection.CP,denied.rejection);assertEquals(before,denied.stocks)
        }
    }

    @Test fun civilHierarchyExceptionCannotBeUsedForMilitaryPeers() {
        assertTrue(CapitalAppointmentPolicy.rule(192)!!.eligible(1,1))
        assertFalse(CapitalAppointmentPolicy.rule(193)!!.eligible(5,5))
        assertFalse(CapitalAppointmentPolicy.rule(193)!!.eligible(2,3))
        assertFalse(CapitalAppointmentPolicy.rule(193)!!.eligible(3,8))
        assertTrue(CapitalAppointmentPolicy.rule(193)!!.eligible(2,4))
        assertFalse(CapitalAppointmentPolicy.rule(56)!!.eligible(2,2))
    }
    @Test fun nationalCardsUseTheOriginalInstitutionAndDeactivateAfterCapitalOccupation() {
        for(office in NationalOffice.entries) {
            val rule=CapitalAppointmentPolicy.rules.singleOrNull {it.authority==office}
            val s=stock(CapitalAppointmentPolicy.rules.first()).copy(nationalOffices=mapOf(office to 10000))
            val cards=NationalOfficePolicy(galaxy).cards(s,10000,office.power)
            assertEquals(listOf(office.card to ((if(office.power==2)1049 else 1177)*100+office.facility+1)),cards)
            val id=if(office.power==2)1049 else 1177
            val changed=s.copy(bases=s.bases+(id to s.bases.getValue(id).copy(owner=Allegiance.fromLegacy(0))))
            assertTrue(NationalOfficePolicy(galaxy).cards(changed,10000,office.power).isEmpty())
            assertFalse(office.active(galaxy,changed))
            if(rule!=null) {
                assertFalse(policy.authorized(rule,changed,10000,office.power))
                assertEquals(AppointmentRejection.BASE,act(rule,s=changed).rejection)
            }
        }
    }
}
