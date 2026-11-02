package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 실제 평가 포인트 지출·기함 변경·합의 교환·오프라인·기한·실패 무변경. 작성: Peppone Choi. */
class FlagshipMarketActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"Other",0,30f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("actor",Power.EMPIRE,false,"c");it.join("receiver",Power.EMPIRE,false,"r")}.exportState()
    private val now=session.gameMillis/1000
    private val yard=galaxy.facilities.getValue(1049).single {it.kind==23}.spots.single {it.kind==21}.id
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
        spotId=yard,hull=FlagshipHull(0,now,hp=72),supplies=35)
    private val receiver=player.copy(characterId=10001,hull=FlagshipHull(2,now,hp=89),supplies=27)
    private val players=mapOf("actor" to player,"receiver" to receiver)
    private val identities=listOf(StrategicIdentity(10000,2,5,0,List(8){50}),StrategicIdentity(10001,2,5,0,List(8){50}))
    private fun stocks()=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1050 to PlanetStock(1050,2,0,100,200,300)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=1000))))
    private fun request(mode:Int=0)=StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,96,0,5,value=0,
        target=if(mode==1)10001 else 0,argument=mode,identities=identities,navigators=players)
    private fun act(r:StrategyActionRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player)=StrategyActions.evaluate(galaxy,r,session,p,s)
    @Test fun purchaseChargesEvaluationAndPcpTogetherAndKeepsCargoWithoutChangingAnotherPersonsShip() {
        val original=stocks();val result=act(s=original)
        assertNull(result.rejection);assertEquals(920,result.player?.pcp);assertEquals(1000,result.player?.mcp)
        assertEquals(950L,result.stocks?.strategic?.people?.get(10000)?.evaluation)
        assertEquals(3,result.player?.hull?.kind);assertEquals(100,result.player?.hull?.hp)
        assertEquals(player.supplies,result.player?.supplies);assertTrue(result.navigators.isEmpty())
        val replay=act(s=assertNotNull(result.stocks),p=assertNotNull(result.player))
        assertEquals(StrategyActionRejection.MAXIMUM,replay.rejection);assertEquals(result.player,replay.player)
    }
    @Test fun capitalYardRankCurrencyAndCpAreEnforcedWithoutPartialDebit() {
        val original=stocks()
        assertEquals(StrategyActionRejection.LOCATION,act(p=player.copy(spotId=0)).rejection)
        assertEquals(StrategyActionRejection.LOCATION,act(p=player.copy(baseId=1050,spotId=0)).rejection)
        assertEquals(StrategyActionRejection.CARD,act(request().copy(card=59)).rejection)
        assertEquals(StrategyActionRejection.RANK,act(request().copy(rank=11,value=1)).rejection)
        val empty=original.copy(strategic=StrategicState())
        val points=act(s=empty);assertEquals(StrategyActionRejection.POINTS,points.rejection);assertEquals(empty,points.stocks)
        val poor=player.copy(pcp=79,mcp=159)
        val cp=act(s=original,p=poor);assertEquals(StrategyActionRejection.CP,cp.rejection);assertEquals(original,cp.stocks);assertEquals(poor,cp.player)
        val substitute=act(p=player.copy(pcp=79,mcp=160));assertNull(substitute.rejection)
        assertEquals(79,substitute.player?.pcp);assertEquals(0,substitute.player?.mcp)
    }
    @Test fun donorCannotSwapAnOfflineRecipientsHullUntilThatRecipientAcceptsAndBothChangesAreReturnedTogether() {
        val offlineSession=session.copy(participants=session.participants+("receiver" to session.participants.getValue("receiver").copy(online=false,connection=null)))
        val offered=StrategyActions.evaluate(galaxy,request(1),offlineSession,player,stocks())
        assertNull(offered.rejection);assertEquals(player,offered.player);assertTrue(offered.navigators.isEmpty())
        val stored=assertNotNull(offered.stocks);assertEquals(1,stored.flagshipMarket.offers.size)
        assertEquals(StrategyActionRejection.BUSY,act(s=stored).rejection)
        val accept=request(2).copy(account="receiver",connection="r",characterId=10001,target=10000)
        val poor=receiver.copy(pcp=79,mcp=159)
        val rejected=act(accept,stored,poor)
        assertEquals(StrategyActionRejection.CP,rejected.rejection);assertEquals(stored,rejected.stocks)
        assertEquals(poor,rejected.player);assertTrue(rejected.navigators.isEmpty())
        val accepted=act(accept,stored,receiver);assertNull(accepted.rejection)
        assertEquals(player.hull?.copy(at=now),accepted.player?.hull);assertEquals(receiver.supplies,accepted.player?.supplies)
        assertEquals(receiver.hull?.copy(at=now),accepted.navigators.getValue("actor").hull)
        assertEquals(player.supplies,accepted.navigators.getValue("actor").supplies)
        assertEquals(920,accepted.player?.pcp);assertEquals(1000,accepted.navigators.getValue("actor").pcp)
        assertTrue(assertNotNull(accepted.stocks).flagshipMarket.offers.isEmpty())
        assertEquals(StrategyActionRejection.AUTHORITY,act(accept,assertNotNull(accepted.stocks),receiver).rejection)
    }
    @Test fun staleMovedForeignAndExpiredOffersNeverMoveOrDuplicateHullsAndEitherOwnerCanCancel() {
        val foreign=players+("receiver" to receiver.copy(power=Power.ALLIANCE,hull=FlagshipHull(89,now)))
        assertEquals(StrategyActionRejection.LOCATION,act(request(1).copy(navigators=foreign)).rejection)
        val offered=assertNotNull(act(request(1)).stocks)
        val accept=request(2).copy(account="receiver",connection="r",characterId=10001,target=10000)
        val moved=players+("actor" to player.copy(spotId=0))
        assertEquals(StrategyActionRejection.LOCATION,act(accept.copy(navigators=moved),offered,receiver).rejection)
        val changed=players+("actor" to player.copy(hull=FlagshipHull(3,now)))
        val stale=act(accept.copy(navigators=changed),offered,receiver)
        assertEquals(StrategyActionRejection.TARGET,stale.rejection);assertEquals(offered,stale.stocks);assertTrue(stale.navigators.isEmpty())
        val future=session.copy(gameMillis=session.gameMillis+86400_000)
        val expired=StrategyActions.evaluate(galaxy,accept,future,receiver,offered)
        assertEquals(StrategyActionRejection.AUTHORITY,expired.rejection)
        assertTrue(StrategyActions.settle(offered,now+86400).flagshipMarket.offers.isEmpty())
        val cancel=request(3).copy(account="receiver",connection="r",characterId=10001)
        val cancelled=act(cancel,offered,receiver.copy(spotId=0));assertNull(cancelled.rejection)
        assertTrue(assertNotNull(cancelled.stocks).flagshipMarket.offers.isEmpty());assertEquals(receiver.copy(spotId=0),cancelled.player)
    }
    @Test fun alliancePurchaseUsesItsOwnYardAndFlagshipKinds() {
        val galaxyA=Galaxy(listOf(GalaxySystem(3,2015,4,"Alliance",3)),listOf(
            GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2015,Power.ALLIANCE to 2015))
        val sessionA=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.ALLIANCE,false,"c")}.exportState()
        val spot=galaxyA.facilities.getValue(1177).single {it.kind==23}.spots.single {it.kind==21}.id
        val playerA=player.copy(power=Power.ALLIANCE,grid=2015,baseId=1177,spotId=spot,hull=FlagshipHull(89,now))
        val stocksA=PlanetStocks(1,0,mapOf(1177 to PlanetStock(1177,3,0,100,200,300)),
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=1000))))
        val result=StrategyActions.evaluate(galaxyA,request().copy(power=Power.ALLIANCE),sessionA,playerA,stocksA)
        assertNull(result.rejection);assertEquals(93,result.player?.hull?.kind)
        assertEquals(950L,result.stocks?.strategic?.people?.get(10000)?.evaluation)
    }
}
