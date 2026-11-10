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

    @Test fun theCountryChoosesNewModelsWhileAnAlreadyPaidOrderKeepsItsOldKind() {
        val catalog=countriesWithSwappedShipStyles()
        val kinds=ProductionActions.kinds(CountryId(2),true,catalog)
        assertTrue(kinds.all {it.power==3})
        fun order(s:PlanetStocks,kind:Int)=ProductionActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(
            "a","a",10000,Power.EMPIRE,1,ProductionActions.SHIP,66,1,value=kind)),session,a,s,catalog=catalog)
        val pending=stocks.copy(social=stocks.social.copy(production=ProductionState(orders=mapOf(1049 to ShipOrder(32,now)))))
        assertEquals(SocialRejection.BUSY,order(pending,119).rejection)
        val finished=ProductionActions.settle(pending,now)
        assertEquals(1,finished.bases.getValue(1049).units.ships[UnitStack(32)])
        val denied=order(finished,32)
        assertEquals(SocialRejection.TARGET,denied.rejection);assertEquals(a,denied.player);assertEquals(finished,denied.stocks)
        val accepted=order(finished,119)
        assertNull(accepted.rejection);assertEquals(920,accepted.player?.mcp)
        assertEquals(119,accepted.stocks?.social?.production?.orders?.get(1049)?.kind)
        assertEquals(1,accepted.stocks?.bases?.get(1049)?.units?.ships?.get(UnitStack(32)))
        assertEquals(2,accepted.player?.country?.value)
    }

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
    @Test fun aPausedShipyardHoldsItsPaidOrderUntilItResumes() {
        val ship = ProductionActions.kinds(2, true).first()
        val ordered = act(ProductionActions.SHIP, stocks, ship.kind)
        assertNull(ordered.rejection)
        assertEquals(920, ordered.player?.mcp)
        val pending = assertNotNull(ordered.stocks)
        val order = pending.social.production.orders.getValue(1049)

        val pause = act(ProductionActions.PAUSE, pending)
        assertNull(pause.rejection)
        val paused = assertNotNull(pause.stocks)
        assertSame(paused, ProductionActions.settle(paused, order.readyAt))

        val resume = act(ProductionActions.RESUME, paused)
        assertNull(resume.rejection)
        val resumed = assertNotNull(resume.stocks)
        val delivered = ProductionActions.settle(resumed, order.readyAt + 1)
        val warehouse = pending.bases.getValue(1049)
        assertEquals(warehouse.copy(units = UnitInventory(ships = mapOf(UnitStack(ship.kind) to 1))),
            delivered.bases.getValue(1049))
        assertTrue(delivered.social.production.orders.isEmpty())
        assertSame(delivered, ProductionActions.settle(delivered, order.readyAt + 2))
    }

    @Test fun rebellionHoldsThePaidShipOrderUntilRegularControlReturns() {
        val ship = ProductionActions.kinds(2, true).first()
        val accepted = act(ProductionActions.SHIP, stocks, ship.kind)
        assertEquals(920, accepted.player?.mcp)
        val pending = assertNotNull(accepted.stocks)
        val order = pending.social.production.orders.getValue(1049)
        val paidWarehouse = pending.bases.getValue(1049)
        val rebel = pending.copy(bases = mapOf(1049 to paidWarehouse.copy(camp = 1)))

        assertEquals(SocialRejection.LOCATION, act(ProductionActions.SHIP, rebel, ship.kind).rejection)
        assertSame(rebel, ProductionActions.settle(rebel, order.readyAt))
        assertSame(rebel, ProductionActions.settle(rebel, order.readyAt + 1))
        val restored = rebel.copy(bases = pending.bases)
        val delivered = ProductionActions.settle(restored, order.readyAt + 2)
        assertEquals(paidWarehouse.copy(units = UnitInventory(ships = mapOf(UnitStack(ship.kind) to 1))),
            delivered.bases.getValue(1049))
        assertTrue(delivered.social.production.orders.isEmpty())
        assertSame(delivered, ProductionActions.settle(delivered, order.readyAt + 3))
    }

    @Test fun aFullStackKeepsTheOrderWaitingAndAFullTroopStackRefusesRecruiting() {
        val ship=ProductionActions.kinds(2,true).first();val troop=ProductionActions.kinds(2,false).first()
        val base=stocks.bases.getValue(1049)
        val full=stocks.copy(bases=stocks.bases+(1049 to base.copy(units=UnitInventory(mapOf(UnitStack(ship.kind) to 65535),
            mapOf(UnitStack(troop.kind) to 65535)))),social=stocks.social.copy(production=ProductionState(orders=mapOf(1049 to ShipOrder(ship.kind,now)))))
        assertSame(full,ProductionActions.settle(full,now+1),"no out-of-range stack; the order waits")
        assertEquals(SocialRejection.MAXIMUM,act(ProductionActions.RECRUIT,full,troop.kind).rejection)
    }
    @Test fun shipDeliveryWaitsForSpaceAcrossGradesAndSpareBoats() {
        val key=UnitStack(32)
        val veteran=UnitStack(32,1)
        val base=stocks.bases.getValue(1049).copy(units=UnitInventory(ships=mapOf(key to 216,veteran to 1)),
            spareShips=SpareShipBoats(mapOf(veteran to 136)))
        val pending=stocks.copy(bases=mapOf(1049 to base),
            social=stocks.social.copy(production=ProductionState(orders=mapOf(1049 to ShipOrder(key.kind,now)))))
        assertSame(pending,ProductionActions.settle(pending,now))

        val spare=SpareShipBoats(mapOf(veteran to 135))
        val room=pending.copy(bases=mapOf(1049 to base.copy(spareShips=spare)))
        val delivered=ProductionActions.settle(room,now)
        val stock=delivered.bases.getValue(1049)
        assertEquals(mapOf(key to 217,veteran to 1),stock.units.ships)
        assertEquals(spare,stock.spareShips)
        assertTrue(delivered.social.production.orders.isEmpty())
        assertSame(delivered,ProductionActions.settle(delivered,now))
    }
    @Test fun quotesShareTheCommittedCostsAndNeverSpendOrOrder() {
        val policy=UnitProductionPolicy(peoplePerUnit=888,foodPerTroop=110,suppliesPerTroop=21,mineralPerShipHour=12)
        val player=a.copy(mcp=0)
        fun run(command:Int,phase:Int,kind:Int=0,site:Int=0,s:PlanetStocks=stocks)=ProductionActions.evaluate(galaxy,
            SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,command,66,1,value=kind,base=site),phase),
            session,player,s,policy)
        val ship=ProductionActions.kinds(2,true).first()
        val view=run(35,ProductionActions.QUOTE_PHASE,ship.kind)
        val quote=assertNotNull(view.productionQuote)
        assertEquals(ship.hours,quote.hours)
        assertEquals(ship.hours*12L,quote.mineral)
        assertEquals(0L,quote.progress?.automaticPausedUntil)
        val blast=Bomb(10001,1049,10490001,now)
        val sabotage=stocks.copy(social=stocks.social.copy(justice=JusticeState(bombs=mapOf(blast.character to blast))))
        val outage=run(35,ProductionActions.QUOTE_PHASE,ship.kind,s=sabotage)
        assertNull(outage.rejection)
        assertEquals(now+7*Justice.DAY,outage.productionQuote?.progress?.automaticPausedUntil)
        assertFalse(view.changed);assertSame(stocks,view.stocks);assertEquals(player,view.player)
        assertEquals(SocialRejection.QUOTE_CHANGED,run(35,0,ship.kind,1000).rejection)
        val order=run(35,0,ship.kind,quote.base)
        assertEquals(840,order.player?.pcp)
        val stock=assertNotNull(order.stocks).bases.getValue(1049)
        val pending=run(35,ProductionActions.QUOTE_PHASE,ship.kind,s=checkNotNull(order.stocks))
        assertEquals(SocialRejection.BUSY,pending.rejection)
        assertEquals(ShipOrder(ship.kind,now+ship.hours*3600L),pending.productionQuote?.progress?.order)
        val settled=ProductionActions.settle(checkNotNull(order.stocks),now+ship.hours*3600L)
        val received=assertNotNull(run(35,ProductionActions.QUOTE_PHASE,ship.kind,s=settled).productionQuote?.progress)
        assertNull(received.order);assertEquals(1,received.stock)
        assertEquals(100_000L-quote.mineral,stock.mineral.toLong())
        assertEquals(100_000L-quote.supplies,stock.supplies.toLong())
        val build=run(31,ProductionActions.QUOTE_PHASE)
        assertEquals(SocialRejection.TARGET,build.rejection)
        assertEquals(100_000_000L,build.productionQuote?.treasury)
        assertEquals(720,build.productionQuote?.hours)
        val yard=stocks.copy(social=stocks.social.copy(production=ProductionState(
            built=mapOf(1049 to now+120L),destroyed=setOf(1049))))
        val construction=run(31,ProductionActions.QUOTE_PHASE,s=yard)
        assertEquals(ProductionActions.Yard.BUILDING,construction.productionQuote?.progress?.yard)
        assertEquals(now+120L,construction.productionQuote?.progress?.buildingUntil)
        val troop=ProductionActions.kinds(2,false).first()
        val recruit=assertNotNull(run(36,ProductionActions.QUOTE_PHASE,troop.kind).productionQuote)
        assertEquals(listOf(160,888,110),listOf(recruit.mcp,recruit.adults,recruit.food))
        val done=run(36,0,troop.kind,recruit.base)
        assertEquals(680,done.player?.pcp)
        assertEquals(299_112,done.stocks?.bases?.get(1049)?.economy?.adults)
    }
    @Test fun orderListNamesAreKorean() {
        assertEquals("전함Ⅰ",ProductionActions.koreanName("戦艦Ⅰ"));assertEquals("경장육전병",ProductionActions.koreanName("軽装陸戦兵"))
        assertTrue(ProductionActions.kinds(2,true).all {ProductionActions.koreanName(it.name).none {c->c in '぀'..'ヿ'}})
    }
}
