package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OfficeDismissalTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1048,2487,80,0,"Ordinary",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun session(power:Power)=SessionSimulation(SessionRules.load()).also {it.join("actor",power,false,"c")}.exportState()
    private fun stock()=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.id==1177)3 else 2,0,100,200,300)})
    private fun player(power:Power)=Navigator(1,10000,power,if(power==Power.EMPIRE)2487 else 2015,1000,1000,1000)
    @Test fun allTenFleetRolesCheckStoredAppointerAndRemoveOnlyTheTarget() {
        for(role in FleetOffice.entries) {
            val power=if(role.power==2)Power.EMPIRE else Power.ALLIANCE
            val authority=NationalOffice.of(role.authority)
            val rank=if(authority!=null)2 else if(role.power==2)5 else 6
            val base=if(role.power==2)1049 else 1177
            var fleet=OutfitStock(200,role.power,OutfitKind.FLEET,base,300,commanderId=if(authority==null)10000 else 0)
            fleet=role.assign(fleet,10001)
            if(role.offset==3) fleet=role.assign(fleet,10002)
            val stocks=stock().copy(outfits=mapOf(200 to fleet),warehouses=mapOf(300 to OutfitWarehouse(300,role.power,OutfitKind.FLEET,base,200)),
                nationalOffices=if(authority==null)emptyMap() else mapOf(authority to 10000))
            val q=OfficeDismissalRequest("actor","c",10000,power,1,10001,role.card,rank,role.power)
            val p=player(power);val result=OfficeDismissal.evaluate(galaxy,q,session(power),p,stocks)
            assertNull(result.rejection,role.name);assertTrue(result.changed);assertEquals(840,result.player!!.mcp)
            assertEquals(role.remove(fleet,10001),result.stocks!!.outfits.getValue(200))
            assertEquals(if(role.offset==3)listOf(10002) else emptyList(),role.holders(result.stocks!!.outfits.getValue(200)))
            val replay=OfficeDismissal.evaluate(galaxy,q,session(power),result.player,result.stocks!!)
            assertEquals(DismissalRejection.AUTHORITY,replay.rejection);assertEquals(result.player,replay.player)
            val unauthorized=stocks.copy(nationalOffices=emptyMap(),outfits=mapOf(200 to fleet.copy(commanderId=if(authority==null)10003 else fleet.commanderId)))
            val denied=OfficeDismissal.evaluate(galaxy,q,session(power),p,unauthorized)
            assertEquals(DismissalRejection.AUTHORITY,denied.rejection);assertEquals(p,denied.player);assertEquals(unauthorized,denied.stocks)
        }
    }
    @Test fun capitalAndOrdinaryGarrisonDismissalsPreserveOtherOffices() {
        for(card in listOf(55,56,57,192,193)) {
            val rule=CapitalAppointmentPolicy.rule(card);val power=if(rule?.power==3)Power.ALLIANCE else Power.EMPIRE
            val base=if(card==55)1048 else if(power==Power.EMPIRE)1049 else 1177
            val initial=stock();val b=initial.bases.getValue(base).copy(governorId=10000)
            val stocks=initial.copy(bases=initial.bases+(base to (rule?.assign(b,10001) ?: b.copy(garrisonCommanderId=10001))),
                nationalOffices=if(rule==null)emptyMap() else mapOf(rule.authority to 10000))
            val q=OfficeDismissalRequest("actor","c",10000,power,1,10001,card,if(card==192)1 else 2,if(power==Power.EMPIRE)2 else 3)
            val result=OfficeDismissal.evaluate(galaxy,q,session(power),player(power),stocks)
            assertNull(result.rejection,"card $card");assertEquals(840,result.player!!.mcp)
            assertEquals(b,result.stocks!!.bases.getValue(base));assertEquals(stocks.nationalOffices,result.stocks!!.nationalOffices)
        }
    }
    @Test fun fullStaffStillOffersDismissalAndOccupiedEnemyBasesDoNot() {
        val fleet=OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000,
            staff=FleetStaff(officers=(10001..10006).toList()))
        val s=stock().copy(outfits=mapOf(200 to fleet),warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
        assertTrue(FleetOffice.EMPIRE_STAFF.available(galaxy,s,10000,5).isEmpty())
        val policy=OfficeDismissalPolicy(galaxy)
        assertEquals((10001..10006).toList(),policy.available(s,10000,2,5).map {it.target})
        val captured=s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(power=3)))
        assertTrue(policy.available(captured,10000,2,5).isEmpty())
    }
    @Test fun invalidIdentityAmbiguityBusyAndInsufficientCpNeverClearOrCharge() {
        val b=stock();val stocks=b.copy(bases=b.bases+(1048 to b.bases.getValue(1048).copy(governorId=10000,garrisonCommanderId=10001)))
        val q=OfficeDismissalRequest("actor","c",10000,Power.EMPIRE,1,10001,55,2,2);val p=player(Power.EMPIRE)
        fun act(r:OfficeDismissalRequest=q,nav:Navigator=p,s:PlanetStocks=stocks)=OfficeDismissal.evaluate(galaxy,r,session(Power.EMPIRE),nav,s)
        for((r,reason) in listOf(q.copy(connection="old") to DismissalRejection.SESSION,q.copy(generation=2) to DismissalRejection.SESSION,
            q.copy(characterId=10002) to DismissalRejection.IDENTITY,q.copy(target=10000) to DismissalRejection.TARGET,
            q.copy(targetPower=3) to DismissalRejection.TARGET,q.copy(card=0) to DismissalRejection.CARD)) {
            val result=act(r);assertEquals(reason,result.rejection);assertEquals(p,result.player);assertEquals(stocks,result.stocks)
        }
        assertEquals(DismissalRejection.BUSY,act(nav=p.copy(cpPaused=true)).rejection)
        assertEquals(DismissalRejection.CP,act(nav=p.copy(mcp=159,pcp=319)).rejection)
        val fallback=act(nav=p.copy(mcp=159,pcp=320));assertNull(fallback.rejection)
        assertEquals(0,fallback.player!!.pcp);assertEquals(159,fallback.player!!.mcp)
        val seed=galaxy.bases.single {it.id==1048}.copy(id=1047,name="Second")
        val g=Galaxy(galaxy.systems,galaxy.bases+seed,galaxy.starts)
        val dup=stocks.copy(bases=stocks.bases+(1047 to stocks.bases.getValue(1048).copy(baseId=1047)))
        val denied=OfficeDismissal.evaluate(g,q,session(Power.EMPIRE),p,dup)
        assertEquals(DismissalRejection.AMBIGUOUS,denied.rejection);assertEquals(dup,denied.stocks);assertEquals(p,denied.player)
        assertTrue(OfficeDismissalPolicy(g).available(dup,10000,2,2).isEmpty())
    }
}
