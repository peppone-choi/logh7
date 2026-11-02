package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-08 수동 생산: 조병창 건설·휴지·재가동·파기, 함정 주문 완료, 모병. 작성: Peppone Choi. */
class ProductionActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val now=session.gameMillis/1000
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100_000,100_000,100_000,PlanetEconomy(500_000,300_000,60,60),
        treasury=200_000_000)),strategic=StrategicState(fiefs=mapOf(1049 to 10000)))
    private fun act(command:Int,s:PlanetStocks,value:Int=0)=SocialActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(
        "a","a",10000,Power.EMPIRE,1,command,66,1,value=value)),session,a,s,listOf(a),mapOf("a" to a))

    @Test fun aLordBuildsPausesAndDestroysAShipyard() {
        // Every seeded base starts with a shipyard: destroy it first, then build a new one.
        assertTrue(ProductionActions.hasShipyard(galaxy,stocks,1049))
        assertEquals(SocialRejection.TARGET,act(ProductionActions.BUILD,stocks).rejection)
        val razed=assertNotNull(act(ProductionActions.DESTROY,stocks).stocks)
        assertFalse(ProductionActions.hasShipyard(galaxy,razed,1049));assertEquals(setOf(1049),razed.social.production.destroyed)
        val built=assertNotNull(act(ProductionActions.BUILD,razed).stocks)
        assertEquals(100_000_000,built.bases.getValue(1049).treasury)
        assertFalse(ProductionActions.shipyard(galaxy,built,1049,now));assertTrue(ProductionActions.shipyard(galaxy,built,1049,now+30*86400))
        val paused=assertNotNull(act(ProductionActions.PAUSE,built).stocks)
        assertFalse(ProductionActions.shipyard(galaxy,paused,1049,now+30*86400))
        assertTrue(ProductionActions.shipyard(galaxy,assertNotNull(act(ProductionActions.RESUME,paused).stocks),1049,now+30*86400))
        val again=assertNotNull(act(ProductionActions.DESTROY,paused).stocks).social.production
        assertTrue(again.built.isEmpty());assertEquals(setOf(1049),again.destroyed)
    }
    @Test fun aShipOrderArrivesAfterItsHoursAndRecruitingTurnsAdultsIntoTroops() {
        val withYard=stocks.copy(social=stocks.social.copy(production=ProductionState(built=mapOf(1049 to 0L))))
        val ship=ProductionActions.kinds(2,true).first()
        val ordered=act(ProductionActions.SHIP,withYard,ship.kind)
        assertNull(ordered.rejection,"${ordered.rejection}");assertEquals(920,ordered.player?.mcp)
        val s=assertNotNull(ordered.stocks)
        assertEquals(SocialRejection.BUSY,act(ProductionActions.SHIP,s,ship.kind).rejection)
        assertSame(s,ProductionActions.settle(s,now+ship.hours*3600L-1))
        val done=ProductionActions.settle(s,now+ship.hours*3600L)
        assertEquals(1,done.bases.getValue(1049).units.ships[UnitStack(ship.kind)]);assertTrue(done.social.production.orders.isEmpty())
        val troop=ProductionActions.kinds(2,false).first()
        val recruited=assertNotNull(act(ProductionActions.RECRUIT,stocks,troop.kind).stocks).bases.getValue(1049)
        assertEquals(1,recruited.units.troops[UnitStack(troop.kind)]);assertEquals(299_000,recruited.economy?.adults)
    }
    @Test fun aFullStackKeepsTheOrderWaitingAndAFullTroopStackRefusesRecruiting() {
        val ship=ProductionActions.kinds(2,true).first();val troop=ProductionActions.kinds(2,false).first()
        val base=stocks.bases.getValue(1049)
        val full=stocks.copy(bases=stocks.bases+(1049 to base.copy(units=UnitInventory(mapOf(UnitStack(ship.kind) to 65535),
            mapOf(UnitStack(troop.kind) to 65535)))),social=stocks.social.copy(production=ProductionState(orders=mapOf(1049 to ShipOrder(ship.kind,now)))))
        assertSame(full,ProductionActions.settle(full,now+1),"no out-of-range stack; the order waits")
        assertEquals(SocialRejection.MAXIMUM,act(ProductionActions.RECRUIT,full,troop.kind).rejection)
    }
    @Test fun orderListNamesAreKorean() {
        assertEquals("전함Ⅰ",ProductionActions.koreanName("戦艦Ⅰ"));assertEquals("경장육전병",ProductionActions.koreanName("軽装陸戦兵"))
        assertTrue(ProductionActions.kinds(2,true).all {ProductionActions.koreanName(it.name).none {c->c in '぀'..'ヿ'}})
    }
}
