package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OfficeResignationTest {
    private val empire=GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)
    private val alliance=empire.copy(id=1177,grid=2015,name="ハイネセン")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(empire,alliance,empire.copy(id=1048,name="Other"),alliance.copy(id=1176,name="Other"),
            empire.copy(id=1001,kind=1,name="Fort"),alliance.copy(id=1201,kind=1,name="Fort")),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val cards=listOf(2,17,39,44,47,50,52,54,55,56,57,134,161,175,180,183,186,188,190,191,192,193)
    private fun power(card:Int)=if(card in setOf(134,161,175,180,183,186,188,190,191,192,193))3 else 2
    private fun request(card:Int)=OfficeResignationRequest("pilot","c",10000,if(power(card)==2)Power.EMPIRE else Power.ALLIANCE,1,card)
    private fun stock(card:Int,holder:Int=10000):PlanetStocks {
        val p=power(card);val ordinary=if(p==2)1048 else 1176
        var s=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,if(it.grid==2487)2 else 3,0,100,200,300)},deploymentInitialized=true)
        val national=NationalOffice.of(card)
        if(national!=null) return s.copy(nationalOffices=if(holder==0)emptyMap() else mapOf(national to holder))
        val outfitKind=when(card) {39,175->OutfitKind.FLEET;44,180->OutfitKind.TRANSPORT;47,183->OutfitKind.PATROL;50,186->OutfitKind.GROUND;else->null}
        if(outfitKind!=null) return s.copy(outfits=mapOf(1 to OutfitStock(1,p,outfitKind,ordinary,2,holder)),
            warehouses=mapOf(2 to OutfitWarehouse(2,p,outfitKind.warehouseKind,ordinary,if(outfitKind in setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT))1 else 0)))
        val base=when(card) {52->1001;188->1201;56,57->1049;192,193->1177;else->ordinary}
        val b=s.bases.getValue(base)
        val next=when(card) {54,190->b.copy(governorId=holder);52,55,188,191->b.copy(garrisonCommanderId=holder);else->CapitalAppointmentPolicy.rule(card)!!.assign(b,holder)}
        return s.copy(bases=s.bases+(base to next))
    }
    private fun act(q:OfficeResignationRequest,s:PlanetStocks=stock(q.card),p:Navigator=Navigator(1,10000,q.power,if(q.power==Power.EMPIRE)2487 else 2015,1000,1000,1000)):OfficeResignationResult {
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",p.power,false,"c")}.exportState()
        return OfficeResignation.evaluate(galaxy,q,session,p,s)
    }
    @Test fun allSupportedOfficeKindsClearExactlyTheirStoredSeatAndPayOnce() {
        for(card in cards) {
            val q=request(card);val result=act(q)
            assertNull(result.rejection,"card $card");assertTrue(result.changed)
            assertEquals(stock(card,0),result.stocks,"card $card")
            assertEquals(920,result.player!!.mcp);assertEquals(1000,result.player!!.pcp)
            val replay=act(q,result.stocks!!,result.player!!)
            assertEquals(ResignationRejection.AUTHORITY,replay.rejection)
            assertFalse(replay.changed);assertEquals(result.player,replay.player);assertEquals(result.stocks,replay.stocks)
        }
    }
    @Test fun forgedIdentityStaleConnectionAndUnsupportedOrMissingAuthorityNeverPay() {
        val q=request(57);val s=stock(57)
        assertEquals(ResignationRejection.SESSION,act(q.copy(connection="old")).rejection)
        assertEquals(ResignationRejection.SESSION,act(q.copy(generation=2)).rejection)
        assertEquals(ResignationRejection.IDENTITY,act(q.copy(characterId=10001)).rejection)
        for(card in listOf(0,59,195,261)) assertEquals(ResignationRejection.CARD,act(q.copy(card=card),s).rejection)
        for(card in listOf(56,193,17)) {
            val result=act(q.copy(card=card),s)
            assertEquals(ResignationRejection.AUTHORITY,result.rejection);assertEquals(s,result.stocks)
            assertEquals(1000,result.player!!.mcp)
        }
        assertEquals(ResignationRejection.BUSY,act(q,p=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true)).rejection)
    }
    @Test fun duplicateSeatsAreRejectedInsteadOfRemovingAnArbitraryGovernorship() {
        val q=request(54);val s=stock(54)
        val duplicate=s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(governorId=10000)))
        // A capital's legacy governor is not an active ordinary governorship.
        assertNull(act(q,duplicate).rejection)
        val other=galaxy.bases.single {it.id==1048}.copy(id=1047,name="Second")
        val g=Galaxy(galaxy.systems,galaxy.bases+other,galaxy.starts)
        val duplicated=s.copy(bases=s.bases+(1047 to PlanetStock(1047,2,0,100,200,300,governorId=10000)))
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()
        val result=OfficeResignation.evaluate(g,q,session,Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000),duplicated)
        assertEquals(ResignationRejection.AMBIGUOUS,result.rejection);assertEquals(duplicated,result.stocks);assertEquals(1000,result.player!!.mcp)
    }
    @Test fun fullPcpSubstitutionAndInsufficientMixedBalancePreserveTheOtherPool() {
        val q=request(57)
        val p=Navigator(1,10000,Power.EMPIRE,2487,160,79,1000)
        val paid=act(q,p=p);assertNull(paid.rejection);assertEquals(0,paid.player!!.pcp);assertEquals(79,paid.player!!.mcp)
        val denied=act(q,p=p.copy(pcp=159));assertEquals(ResignationRejection.CP,denied.rejection);assertEquals(p.copy(pcp=159),denied.player)
    }
}
