package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryOfficeHoldingsTest {
    private val country=CountryId(42)
    private val other=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"New home",2),
        GalaxySystem(5,2489,4,"Other home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2488,80,0,"New capital",0,20f,86400,0,0f,2f),
        GalaxyBase(1051,2489,80,0,"Other HQ",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_country","New country","new_empire",3,0,"empire",shipStyleOverride="alliance"),
            CountryDefinition(43,"other_country","Other country","empire",3,0,"empire")))}
    private val policy=OrganizationalOfficePolicy(galaxy,catalog)
    private val role=policy.role(24,country)!!
    private val authority=policy.role(role.authority!!,country)!!
    private val ranks=authority.ranks.flatMap {actor->role.ranks.map {actor to it}}.first {role.eligible(it.first,it.second)}
    private fun session(account:String="chief",id:CountryId?=country)=SessionSimulation(SessionRules.load())
        .also {it.join(account,id,false,"c")}.exportState()
    private fun player(character:Int=10000,id:CountryId?=country)=Navigator(1,character,id,if(id==other)2489 else 2488,1000,1000,1000,
        baseId=if(id==other)1051 else 1050,mode=4,shipStyle=ShipStyleId("alliance"))
    private fun stock()=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,1000,200,300),
        1050 to PlanetStock(1050,Allegiance.Country(country),0,1000,200,300,
            units=UnitInventory(mapOf(UnitStack(139) to 2),mapOf(UnitStack(7) to 1000))),
        1051 to PlanetStock(1051,Allegiance.Country(other),0,1000,200,300)),
        strategic=StrategicState(offices=listOf(OfficeHolding(authority.card,1050,10000,country)),
            capitals=mapOf(country to 1050,other to 1051)),deploymentInitialized=true)
    private fun appointment()=GarrisonAppointmentRequest("chief","c",10000,country,1,0,10001,24,ranks.first,ranks.second,country)

    @Test fun anAppointedAdditionalCountryOfficerCanFormAndDissolveItsOwnFleet() {
        val before=stock()
        assertEquals(2,role.power);assertEquals(country,role.country);assertEquals(3,catalog.countries.getValue(42).nativePower)
        val appointed=GarrisonAppointment.evaluate(galaxy,appointment(),session(),player(),before,catalog)
        assertNull(appointed.rejection);assertEquals(840,appointed.player!!.mcp)
        val inventory=appointed.stocks!!
        assertEquals(listOf(OfficeHolding(24,1050,10001,country)),policy.holders(inventory,24,country))
        assertTrue(policy.holders(inventory,24).isEmpty());assertTrue(policy.holders(inventory,24,other).isEmpty())
        val officer=player(10001);val state=session("officer")
        val identity=StrategyActionRequest("officer","c",10001,country,1,25,24,ranks.second)
        val request=OutfitFormationRequest(identity,FormationPhase.BEGIN,1050,OutfitKind.FLEET)
        val service=OutfitFormationService(catalog) {0}
        val begun=service.evaluate(galaxy,request,state,officer,inventory)
        assertNull(begun.rejection);assertEquals(1000,begun.player!!.mcp)
        val selected=request.copy(phase=FormationPhase.SET_UNIT,target=begun.lease,unit=OutfitFormationPolicy.code(UnitStack(139),false),amount=1)
        assertNull(service.evaluate(galaxy,selected,state,officer,inventory).rejection)
        val commit=request.copy(phase=FormationPhase.COMMIT,target=begun.lease)
        val created=service.evaluate(galaxy,commit,state,officer,inventory)
        assertNull(created.rejection);assertEquals(680,created.player!!.mcp);service.complete(commit)
        val next=created.stocks!!;val fleet=next.outfits.getValue(created.outfit)
        assertEquals(country,fleet.country);assertEquals(ShipStyleId("alliance"),fleet.shipStyle)
        assertEquals(1,fleet.units.ships[UnitStack(139)]);assertEquals(0,fleet.commanderId)
        val dissolved=service.evaluate(galaxy,request.copy(identity=identity.copy(command=26),phase=FormationPhase.DELETE,target=fleet.id),
            state,created.player,next)
        assertNull(dissolved.rejection);assertEquals(520,dissolved.player!!.mcp)
        assertTrue(dissolved.stocks!!.outfits.isEmpty())
        assertEquals(before.bases.getValue(1050).units,dissolved.stocks.bases.getValue(1050).units)
        assertEquals(inventory.strategic,dissolved.stocks.strategic)
    }

    @Test fun nationsSharingAnOfficeSystemKeepIndependentRightsAndLoseAccessOnConquest() {
        val before=stock();val q=appointment()
        val foreign=q.copy(country=other,targetCountry=other)
        assertEquals(AppointmentRejection.AUTHORITY,GarrisonAppointment.evaluate(galaxy,foreign,session(id=other),player(id=other),before,catalog).rejection)
        assertEquals(AppointmentRejection.TARGET,GarrisonAppointment.evaluate(galaxy,q.copy(targetCountry=other),session(),player(),before,catalog).rejection)
        val borrowed=before.copy(strategic=before.strategic.copy(offices=listOf(OfficeHolding(authority.card,1049,10000))))
        assertEquals(AppointmentRejection.AUTHORITY,GarrisonAppointment.evaluate(galaxy,q,session(),player(),borrowed,catalog).rejection)
        val both=before.copy(strategic=before.strategic.copy(offices=before.strategic.offices+
            OfficeHolding(authority.card,1051,10002,other)))
        assertEquals(listOf(10000),policy.holders(both,authority.card,country).map {it.character})
        assertEquals(listOf(10002),policy.holders(both,authority.card,other).map {it.character})
        val conquered=both.copy(bases=both.bases+(1050 to both.bases.getValue(1050).copy(owner=Allegiance.Country(other))))
        assertTrue(policy.holders(conquered,authority.card,country).isEmpty())
        assertEquals(listOf(10002),policy.holders(conquered,authority.card,other).map {it.character})
        assertTrue(conquered.strategic.offices.any {it.country==country && it.character==10000})
        val missing=before.copy(strategic=before.strategic.copy(capitals=emptyMap()))
        assertNull(policy.capital(missing,country));assertTrue(policy.holders(missing,authority.card,country).isEmpty())
    }

    @Test fun dismissalAndResignationAffectOnlyTheNamedCountryAndChargeOnce() {
        val before=GarrisonAppointment.evaluate(galaxy,appointment(),session(),player(),stock(),catalog).stocks!!
        val request=OfficeDismissalRequest("chief","c",10000,country,1,10001,24,ranks.first,country)
        val dismissed=OfficeDismissal.evaluate(galaxy,request,session(),player(),before,catalog)
        assertNull(dismissed.rejection);assertEquals(840,dismissed.player!!.mcp)
        assertTrue(policy.holders(dismissed.stocks!!,24,country).isEmpty())
        val resigned=OfficeResignation.evaluate(galaxy,OfficeResignationRequest("officer","c",10001,country,1,24),
            session("officer"),player(10001),before,catalog)
        assertNull(resigned.rejection);assertEquals(920,resigned.player!!.mcp)
        assertEquals(dismissed.stocks,resigned.stocks)
        assertEquals(AppointmentRejection.IDENTITY,GarrisonAppointment.evaluate(galaxy,appointment().copy(country=null),
            session(id=null),player(id=null),stock(),catalog).rejection)
    }

    @Test fun anotherCountrysRulerDoesNotVacateTheOriginalThroneOrGrantItsVictoryCondition() {
        val before=stock()
        val emperor=before.copy(strategic=before.strategic.copy(offices=before.strategic.offices+OfficeHolding(1,1050,10001,country)))
        assertFalse(SuccessionActions.vacated(emperor,10001).social.succession.vacant)
        assertFalse(SessionResults.imperialLeaderAtEnemyCapital(emperor,listOf(player(10001).copy(grid=Capital.ALLIANCE.grid)))!!)
        val chairman=before.copy(strategic=before.strategic.copy(offices=before.strategic.offices+OfficeHolding(134,1050,10002,country)))
        assertTrue(SuccessionActions.notices(before,chairman).isEmpty())
    }
}
