package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 조직표 전 행의 임명·해임·사임·무권한 거부를 운영 평가기에 연결한다. 작성: 최병호. */
class OrganizationOfficesTest {
    private val seeds=listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"PlanetE",0,30f,86400,0,0f,2f),
        GalaxyBase(1001,2487,0,1,"FortE",0,40f,86400,0,0f,2f),
        GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1178,2015,80,0,"PlanetA",0,30f,86400,0,0f,2f),
        GalaxyBase(1200,2015,0,1,"FortA",0,40f,86400,0,0f,2f))
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),seeds,
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val ordinary=OrganizationOffices.all.values.filter {it.camp==0}
    private fun power(p:Int)=if(p==2)Power.EMPIRE else Power.ALLIANCE
    private fun seat(role:OrganizationalOffice):Int=when(role.scope) {
        OfficeScope.OUTFIT->role.power*100+role.institution-11
        OfficeScope.FORTRESS->if(role.power==2)1001 else 1200
        OfficeScope.PLANET->if(role.power==2)1050 else 1178
        else->if(role.power==2)1049 else 1177
    }
    private fun stock():PlanetStocks {
        val outfits=buildMap {
            for(p in 2..3)for((i,kind) in listOf(OutfitKind.FLEET,OutfitKind.TRANSPORT,OutfitKind.PATROL,OutfitKind.GROUND).withIndex()) {
                val id=p*100+i;put(id,OutfitStock(id,p,kind,if(p==2)1049 else 1177,p*100+minOf(i,2)+1000))
            }
        }
        val warehouses=outfits.values.associate {o->o.warehouseId to OutfitWarehouse(o.warehouseId,o.power,o.kind.warehouseKind,
            o.baseId,if(o.kind in setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT))o.id else 0)}
        return PlanetStocks(1,0,seeds.associate {it.id to PlanetStock(it.id,if(it.grid==2487)2 else 3,0,100,200,300)},
            outfits,warehouses,deploymentInitialized=true)
    }
    private fun session(p:Int)=SessionSimulation(SessionRules.load()).also {it.join("actor",power(p),false,"c")}.exportState()
    private fun player(p:Int)=Navigator(1,10000,power(p),if(p==2)2487 else 2015,1000,1000,1000)
    private fun request(role:OrganizationalOffice,actorRank:Int,targetRank:Int)=GarrisonAppointmentRequest("actor","c",10000,
        power(role.power),1,if(role.scope==OfficeScope.OUTFIT)stock().outfits.getValue(seat(role)).baseId else seat(role),
        10001,role.card,actorRank,targetRank,role.power,outfit=if(role.scope==OfficeScope.OUTFIT)seat(role) else 0)

    @Test fun all121ManualRowsHaveTheirOwnExactAppointerAndRankRange() {
        assertEquals(121,ordinary.size);assertEquals(121,ordinary.map {it.manual.source.record}.distinct().size)
        for(role in ordinary) {
            val source=role.manual
            if(source.appointedBy.isEmpty())assertNull(role.authority,source.post)
            else assertEquals(source.appointedBy.substringAfterLast('・'),OrganizationOffices.all.getValue(role.authority!!).manual.post)
            assertTrue(role.ranks.isNotEmpty(),source.post)
        }
        assertEquals(135,OrganizationOffices.all.getValue(169).authority)
        assertEquals(161,OrganizationOffices.all.getValue(193).authority)
        assertEquals(165,OrganizationOffices.all.getValue(187).authority)
        assertEquals(167,OrganizationOffices.all.getValue(173).authority)
        assertNull(OrganizationOffices.all.getValue(148).authority)
    }
    @Test fun everyDocumentedAppointmentEdgeChangesTheActualOfficeAndAllowsOnlyItsStoredSuperiorToDismiss() {
        for(role in ordinary.filter {it.authority!=null}) {
            val authority=OrganizationOffices.all.getValue(role.authority!!)
            val actorRank=if(authority.canonical==2)0 else authority.ranks.min()
            val targetRank=role.ranks.filter {role.eligible(actorRank,it)}.maxOrNull()
            if(targetRank==null) {
                // A fixed same-rank military edge cannot override the manual's seniority rule.
                val s=policy.set(stock(),OfficeHolding(authority.card,if(authority.scope==OfficeScope.NATIONAL)seat(authority) else seat(role),10000))
                assertEquals(AppointmentRejection.RANK,GarrisonAppointment.evaluate(galaxy,request(role,actorRank,role.ranks.min()),
                    session(role.power),player(role.power),s).rejection,role.manual.post)
                continue
            }
            val authoritySeat=if(authority.scope==OfficeScope.NATIONAL)seat(authority) else seat(role)
            val initial=policy.set(stock(),OfficeHolding(authority.card,authoritySeat,10000))
            val q=request(role,actorRank,targetRank)
            val appointed=GarrisonAppointment.evaluate(galaxy,q,session(role.power),player(role.power),initial)
            assertNull(appointed.rejection,role.manual.post);assertTrue(appointed.changed,role.manual.post)
            val stored=assertNotNull(appointed.stocks)
            assertTrue(policy.holders(stored,role.card).any {it.character==10001 && it.seat==seat(role)},role.manual.post)
            assertEquals(840,appointed.player?.mcp)
            val replay=GarrisonAppointment.evaluate(galaxy,q,session(role.power),appointed.player,stored)
            assertFalse(replay.changed);assertEquals(appointed.player,replay.player)
            val dismissal=OfficeDismissal.evaluate(galaxy,OfficeDismissalRequest("actor","c",10000,power(role.power),1,
                10001,role.card,actorRank,role.power),session(role.power),appointed.player,stored)
            assertNull(dismissal.rejection,role.manual.post);assertTrue(dismissal.changed)
            assertFalse(policy.holders(assertNotNull(dismissal.stocks),role.card).any {it.character==10001})
            val outsider=stored.let {s->policy.set(s,OfficeHolding(authority.card,authoritySeat,10000),remove=true)}
            val denied=GarrisonAppointment.evaluate(galaxy,q.copy(target=10002,actorRank=0),session(role.power),player(role.power),outsider)
            assertEquals(AppointmentRejection.AUTHORITY,denied.rejection,role.manual.post)
            val deniedDismissal=OfficeDismissal.evaluate(galaxy,OfficeDismissalRequest("actor","c",10000,power(role.power),1,
                10001,role.card,0,role.power),session(role.power),player(role.power),outsider)
            assertEquals(DismissalRejection.AUTHORITY,deniedDismissal.rejection,role.manual.post)
            val foreign=GarrisonAppointment.evaluate(galaxy,q.copy(targetPower=if(role.power==2)3 else 2),
                session(role.power),player(role.power),initial)
            assertEquals(AppointmentRejection.TARGET,foreign.rejection,role.manual.post)
        }
    }
    @Test fun everyOfficeCanBeResignedWithoutRemovingOtherHoldersAndEmptyAppointmentEdgesStayUnavailable() {
        for(role in ordinary) {
            val initial=policy.set(stock(),OfficeHolding(role.card,seat(role),10000))
            val result=OfficeResignation.evaluate(galaxy,OfficeResignationRequest("actor","c",10000,power(role.power),1,
                role.card),session(role.power),player(role.power),initial)
            assertNull(result.rejection,role.manual.post);assertEquals(920,result.player?.mcp)
            assertTrue(policy.holders(assertNotNull(result.stocks),role.card).isEmpty(),role.manual.post)
            if(role.authority==null) {
                val request=request(role,0,role.ranks.min())
                val denied=GarrisonAppointment.evaluate(galaxy,request,session(role.power),player(role.power),stock())
                assertEquals(AppointmentRejection.AUTHORITY,denied.rejection,role.manual.post)
            }
        }
    }
    @Test fun rebelSlotsHaveSeparateCampAndSeatOwnershipAndNeverBecomeRegularOfficeAuthority() {
        val alias=OrganizationOffices.all.getValue(231)
        assertEquals(169,alias.canonical);assertEquals(200,alias.authority);assertEquals(1,alias.camp)
        val s=stock().copy(strategic=StrategicState(offices=listOf(OfficeHolding(200,1177,10000))))
        assertTrue(policy.holders(s,200).isEmpty())
        assertFalse(policy.authorized(s,OrganizationOffices.all.getValue(169),1177,10000,1))
        assertFalse(policy.active(s,alias,1177))
    }
    @Test fun simultaneousLogisticsCardsNeverOverwriteOrDismissEachOther() {
        val first=OfficeHolding(24,1049,10000);val second=OfficeHolding(25,1049,10000)
        val both=policy.set(policy.set(stock(),first),second)
        assertEquals(setOf(24,25),policy.held(both,10000).map {it.card}.toSet())
        val withoutSecond=policy.set(both,second,remove=true)
        assertEquals(listOf(24),policy.held(withoutSecond,10000).map {it.card})
        val withoutFirst=policy.set(both,first,remove=true)
        assertEquals(listOf(25),policy.held(withoutFirst,10000).map {it.card})
    }
}
