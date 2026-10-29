package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 국가 직무의 상위 임면권·개별 해제·비용 보존 검사. 작성: 최병호. */
class NationalOfficeDismissalTest {
    private val galaxy=Galaxy(
        listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val roles=listOf(NationalOffice.IMPERIAL_STAFF_DIRECTOR,
        NationalOffice.ALLIANCE_FLEET_COMMANDER,NationalOffice.ALLIANCE_FLEET_CHIEF)
    private fun power(role:NationalOffice)=if(role.power==2)Power.EMPIRE else Power.ALLIANCE
    private fun request(role:NationalOffice)=OfficeDismissalRequest("actor","c",10000,power(role),1,
        10001,role.card,checkNotNull(role.appointer).ranks.max(),role.power)
    private fun player(role:NationalOffice)=Navigator(1,10000,power(role),role.capital.grid,1000,1000,1000)
    private fun stock(role:NationalOffice):PlanetStocks {
        val bases=galaxy.bases.associate {base -> base.id to PlanetStock(base.id,
            if(base.id==1049)2 else 3,0,100,200,300)}
        val base=if(role.power==2)1049 else 1177
        return PlanetStocks(1,0,bases,
            outfits=mapOf(200 to OutfitStock(200,role.power,OutfitKind.FLEET,base,300,
                commanderId=10001,aideId=10002)),
            warehouses=mapOf(300 to OutfitWarehouse(300,role.power,OutfitKind.FLEET,base,200)),
            nationalOffices=mapOf(checkNotNull(role.appointer) to 10000,role to 10001))
    }
    private fun act(q:OfficeDismissalRequest,stocks:PlanetStocks,player:Navigator):OfficeDismissalResult {
        val session=SessionSimulation(SessionRules.load()).also {it.join("actor",q.power,false,"c")}.exportState()
        return OfficeDismissal.evaluate(galaxy,q,session,player,stocks)
    }

    @Test fun threeStoredNationalChainsClearOnlyTheSelectedOfficeAndChargeOnce() {
        for(role in roles) {
            val q=request(role);val stocks=stock(role);val p=player(role)
            val policy=OfficeDismissalPolicy(galaxy)
            assertEquals(listOf(role.card),policy.available(stocks,10000,role.power,q.actorRank)
                .filter {NationalOffice.of(it.card)!=null}.map {it.card})
            val result=act(q,stocks,p)
            assertNull(result.rejection,role.name);assertTrue(result.changed)
            assertEquals(p.copy(mcp=840),result.player)
            assertEquals(stocks.copy(nationalOffices=stocks.nationalOffices-role),result.stocks)
            // Other offices held by the same target remain; dismissal is not a rank change.
            val after=assertNotNull(result.stocks)
            assertEquals(10001,after.outfits.getValue(200).commanderId)
            assertTrue(policy.available(after,10000,role.power,q.actorRank).none {it.card==role.card})
            val replay=act(q,after,result.player!!)
            assertEquals(DismissalRejection.AUTHORITY,replay.rejection)
            assertFalse(replay.changed);assertEquals(result.player,replay.player);assertEquals(result.stocks,replay.stocks)
        }
    }

    @Test fun rankAloneWrongHolderWrongFactionAndInactiveCapitalNeverGrantAuthority() {
        for(role in roles) {
            val q=request(role);val s=stock(role);val p=player(role)
            val base=if(role.power==2)1049 else 1177
            val capital=s.bases.getValue(base)
            val unauthorized=listOf(
                s.copy(nationalOffices=mapOf(role to 10001)),
                s.copy(nationalOffices=s.nationalOffices+(checkNotNull(role.appointer) to 10002)),
                s.copy(bases=s.bases+(base to capital.copy(power=if(role.power==2)3 else 2))),
                s.copy(bases=s.bases+(base to capital.copy(camp=1))))
            for(stocks in unauthorized) {
                assertTrue(OfficeDismissalPolicy(galaxy).available(stocks,10000,role.power,q.actorRank).isEmpty())
                val denied=act(q,stocks,p)
                assertEquals(DismissalRejection.AUTHORITY,denied.rejection,role.name)
                assertEquals(stocks,denied.stocks);assertEquals(p,denied.player);assertFalse(denied.changed)
            }
            val wrongRank=act(q.copy(actorRank=20),s,p)
            assertEquals(DismissalRejection.AUTHORITY,wrongRank.rejection)
            assertEquals(s,wrongRank.stocks);assertEquals(p,wrongRank.player)
            assertTrue(OfficeDismissalPolicy(galaxy).available(s,10000,if(role.power==2)3 else 2,q.actorRank).isEmpty())
        }
    }

    @Test fun equalMarshalRanksDoNotPreventDismissalByStoredSuperiorOffice() {
        val role=NationalOffice.IMPERIAL_STAFF_DIRECTOR
        val result=act(request(role).copy(actorRank=2),stock(role),player(role))
        assertNull(result.rejection);assertTrue(result.changed)
    }

    @Test fun capitalDefenseHeadCannotDismissTheAllianceFleetCommander() {
        val role=NationalOffice.ALLIANCE_FLEET_COMMANDER
        val s=stock(role).copy(nationalOffices=mapOf(NationalOffice.DEFENSE_HEAD to 10000,role to 10001))
        val p=player(role)
        val result=act(request(role).copy(actorRank=2),s,p)
        assertEquals(DismissalRejection.AUTHORITY,result.rejection)
        assertEquals(s,result.stocks);assertEquals(p,result.player);assertFalse(result.changed)
        assertTrue(OfficeDismissalPolicy(galaxy).available(s,10000,3,2).none {it.card==169})
        assertEquals(NationalOffice.DEFENSE_HEAD,CapitalAppointmentPolicy.rule(193)?.authority)
        assertEquals(135,NationalOffice.ALLIANCE_FLEET_COMMANDER.appointer?.card)
    }

    @Test fun unsupportedTopOfficesSelfDismissalAndStaleTargetCannotBeCharged() {
        for(role in roles) {
            val q=request(role);val s=stock(role);val p=player(role)
            for((r,reason) in listOf(
                q.copy(card=if(role.power==2)2 else 132) to DismissalRejection.CARD,
                q.copy(target=10000) to DismissalRejection.TARGET,
                q.copy(targetPower=if(role.power==2)3 else 2) to DismissalRejection.TARGET,
                q.copy(target=10003) to DismissalRejection.AUTHORITY)) {
                val denied=act(r,s,p)
                assertEquals(reason,denied.rejection);assertEquals(s,denied.stocks);assertEquals(p,denied.player)
            }
        }
    }

    @Test fun fullPcpSubstitutionAndInsufficientCpBusyOrStaleSessionPreserveState() {
        val role=NationalOffice.ALLIANCE_FLEET_COMMANDER
        val q=request(role);val s=stock(role);val p=player(role).copy(mcp=159,pcp=320)
        val paid=act(q,s,p)
        assertNull(paid.rejection);assertEquals(p.copy(pcp=0),paid.player)
        assertEquals(s.copy(nationalOffices=s.nationalOffices-role),paid.stocks)
        for((nav,reason) in listOf(p.copy(pcp=319) to DismissalRejection.CP,
            p.copy(cpPaused=true) to DismissalRejection.BUSY)) {
            val denied=act(q,s,nav)
            assertEquals(reason,denied.rejection);assertEquals(s,denied.stocks);assertEquals(nav,denied.player)
        }
        val stale=act(q.copy(connection="old"),s,p)
        assertEquals(DismissalRejection.SESSION,stale.rejection);assertEquals(s,stale.stocks);assertEquals(p,stale.player)
    }
}
