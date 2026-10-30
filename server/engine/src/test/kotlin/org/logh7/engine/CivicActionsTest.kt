package org.logh7.engine

import kotlinx.coroutines.*
import java.io.IOException
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 치안/행진/징발/경비의 실제 효과·예약·취소·이탈·저장 경계. 작성: Peppone Choi. 실행은 리드 담당. */
class CivicActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2200,4,"Foreign",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2200,80,0,"Foreign",0,30f,86400,0,0f,2f),
        GalaxyBase(1051,2200,80,1,"Fort",0,40f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2200))
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("actor",Power.EMPIRE,false,"c");it.join("other",Power.EMPIRE,false,"d")}.exportState()
    private val now=session.gameMillis/1000
    private val ship=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 &&
        UnitSpecifications.ships.single {s->s.kind==it.kind}.category==3}.kind,1)
    private val troop=UnitStack(UnitSpecifications.crews.first {it.power==2 && it.type!=3}.kind)
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,cpRecoveryAt=now)
    private val guarded=galaxy.facilities.getValue(1049).flatMap {it.spots}.first {it.access==SpotAccess.GUARDED}.id
    private fun stocks():PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,ship,supplies=100)))
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(2,troop,base=1049),ActiveGroundUnit(3,troop,base=1049)))
        return PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,0,2000,200,300,PlanetEconomy(1000,600,70,80))},
            mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,10000,UnitInventory(ships.quantities(),ground.quantities()),
                supplies=100,activeShips=ships,activeGround=ground)),mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),
            deploymentInitialized=true,nextShipId=4)
    }
    private fun request(command:Int=52,value:Int=0)=StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,command,59,5,
        outfit=if(command==55)0 else 200,value=value,requestToken=17)
    private fun act(r:StrategyActionRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player)=StrategyActions.evaluate(galaxy,r,session,p,s)
    private fun settle(s:PlanetStocks,at:Long=now+CivicActions.DAY,p:Navigator=player)=CivicActions.settle(galaxy,s,at,listOf(p))
    private fun stationed(s:PlanetStocks,base:Int):PlanetStocks {
        val o=s.outfits.getValue(200)
        return s.copy(outfits=mapOf(200 to o.copy(position=OutfitPosition(galaxy.bases.single {it.id==base}.grid,base),
            activeGround=ActiveGroundUnits(o.activeGround!!.values.map {it.copy(base=base)}))))
    }
    @Test fun policingWaitsTwentyFourHoursAndPaysOnlyOnceWithoutSuppressionApprovalDamage() {
        val original=stocks();val result=act(s=original);assertNull(result.rejection);assertEquals(840,result.player?.mcp)
        val pending=assertNotNull(result.stocks);assertEquals(CivicPhase.WAITING,result.civic?.phase)
        assertEquals(original.bases,pending.bases);assertEquals(now+86400,result.readyAt)
        assertEquals(pending,settle(pending,now+86399))
        val done=settle(pending);assertEquals(85,done.bases.getValue(1049).economy?.peace)
        assertEquals(70,done.bases.getValue(1049).economy?.approval);assertEquals(5,done.civic.orders.getValue(10000).effect)
        assertEquals(CivicPhase.COMPLETED,done.civic.orders.getValue(10000).phase)
        assertEquals(done,settle(done,now+2*86400));assertEquals(original.warehouses,done.warehouses)
        val replay=act(s=done,p=assertNotNull(result.player));assertFalse(replay.changed);assertEquals(done,replay.stocks)
        assertEquals(840,replay.player?.mcp)
        val query=act(request(value=2),done,assertNotNull(result.player));assertFalse(query.changed);assertEquals(CivicPhase.COMPLETED,query.civic?.phase)
        val newConnection=act(request().copy(connection="new",requestToken=17),done,assertNotNull(result.player))
        assertEquals(StrategyActionRejection.SESSION,newConnection.rejection)
    }
    @Test fun paradeRaisesOnlyPlanetApprovalAndFullValuesAreFreeRejections() {
        val accepted=act(request(54));val done=settle(assertNotNull(accepted.stocks))
        assertEquals(75,done.bases.getValue(1049).economy?.approval);assertEquals(80,done.bases.getValue(1049).economy?.peace)
        val fort=stationed(stocks(),1051)
        assertEquals(StrategyActionRejection.LOCATION,act(request(54),fort,player.copy(grid=2200,baseId=1051)).rejection)
        for(command in listOf(52,54)) {
            val s=stocks();val b=s.bases.getValue(1049);val full=s.copy(bases=s.bases+(1049 to b.copy(economy=b.economy!!.copy(peace=100,approval=100))))
            val rejected=act(request(command),full);assertEquals(StrategyActionRejection.MAXIMUM,rejected.rejection)
            assertEquals(full,rejected.stocks);assertEquals(player,rejected.player)
        }
    }
    @Test fun requisitionMovesExistingSuppliesFromCapturedForeignBaseToActualOutfitCapacity() {
        assertEquals(StrategyActionRejection.LOCATION,act(request(56)).rejection)
        val captured=stationed(stocks(),1050);val p=player.copy(grid=2200,baseId=1050)
        val accepted=act(request(56),captured,p);assertNull(accepted.rejection)
        val done=settle(assertNotNull(accepted.stocks),p=p)
        assertEquals(1000,done.bases.getValue(1050).supplies);assertEquals(1100,done.outfits.getValue(200).supplies)
        assertEquals(1100,done.outfits.getValue(200).activeShips!!.values.sumOf {it.supplies ?: 0})
        assertEquals(captured.warehouses,done.warehouses);assertEquals(captured.nextShipId,done.nextShipId)
        assertEquals(captured.outfits.getValue(200).activeGround,done.outfits.getValue(200).activeGround)
        assertEquals(1000,done.civic.orders.getValue(10000).effect)
        val pending=assertNotNull(accepted.stocks)
        val empty=pending.copy(bases=pending.bases+(1050 to pending.bases.getValue(1050).copy(supplies=0)))
        val noEffect=settle(empty,p=p);assertEquals(CivicPhase.EMPTY,noEffect.civic.orders.getValue(10000).phase)
        assertEquals(100,noEffect.outfits.getValue(200).supplies)
        val almostFull=pending.outfits.getValue(200).let {o->o.copy(supplies=OutfitSupplies.capacity(o)-7)}
        val bounded=settle(pending.copy(outfits=mapOf(200 to almostFull)),p=p)
        assertEquals(7,bounded.civic.orders.getValue(10000).effect);assertEquals(1993,bounded.bases.getValue(1050).supplies)
        assertEquals(OutfitSupplies.capacity(almostFull),bounded.outfits.getValue(200).supplies)
    }
    @Test fun specialGuardIsImmediateExclusiveAndExpiresOrEndsWhenLeavingTheGuardedSpot() {
        val p=player.copy(mode=1,spotId=guarded);val accepted=act(request(55),p=p);assertNull(accepted.rejection)
        val active=assertNotNull(accepted.stocks);assertEquals(CivicPhase.GUARDING,accepted.civic?.phase)
        assertEquals(2,CivicActions.guardStrength(active,1049,guarded,now))
        assertEquals(2,CivicActions.guardStrength(active,1049,guarded,now+86399))
        assertEquals(1,CivicActions.guardStrength(active,1049,guarded,now+86400))
        assertEquals(CivicPhase.COMPLETED,settle(active,p=p).civic.orders.getValue(10000).phase)
        val moved=settle(active,now+1,p.copy(spotId=0));assertEquals(CivicPhase.INVALIDATED,moved.civic.orders.getValue(10000).phase)
        assertEquals(1,CivicActions.guardStrength(moved,1049,guarded,now+1))
        val other=request(55).copy(account="other",connection="d",characterId=10001)
        assertEquals(StrategyActionRejection.BUSY,act(other,active,p.copy(characterId=10001)).rejection)
        assertEquals(StrategyActionRejection.LOCATION,act(request(55)).rejection)
        assertEquals(StrategyActionRejection.LOCATION,act(request(55),p=p.copy(spotOwner=10000)).rejection)
        val publicSpot=galaxy.facilities.getValue(1049).flatMap {it.spots}.first {it.access==SpotAccess.PUBLIC}.id
        assertEquals(StrategyActionRejection.LOCATION,act(request(55),p=p.copy(spotId=publicSpot)).rejection)
    }
    @Test fun cancellationIsFreeAndLostAuthorityBaseTroopsOrLocationNeverMatureLater() {
        val accepted=act();val pending=assertNotNull(accepted.stocks);val paid=assertNotNull(accepted.player)
        val cancelled=act(request(value=1),pending,paid.copy(baseId=0,mode=5));assertNull(cancelled.rejection)
        assertEquals(840,cancelled.player?.mcp);assertEquals(CivicPhase.CANCELLED,cancelled.civic?.phase)
        assertEquals(pending.bases,settle(assertNotNull(cancelled.stocks)).bases)
        val old=stocks().outfits.getValue(200)
        val variants=listOf(pending.copy(outfits=mapOf(200 to old.copy(commanderId=10001))),
            pending.copy(outfits=mapOf(200 to old.copy(activeGround=ActiveGroundUnits(old.activeGround!!.values.map {it.copy(base=1050)})))) ,
            pending.copy(bases=pending.bases+(1049 to pending.bases.getValue(1049).copy(power=3))),
            pending.copy(outfits=emptyMap(),warehouses=emptyMap()),
            pending.copy(strategic=pending.strategic.copy(people=mapOf(10000 to StrategicPerson(rank=1)))))
        for(s in variants) {
            val ended=settle(s,now+1);assertEquals(CivicPhase.INVALIDATED,ended.civic.orders.getValue(10000).phase)
            assertEquals(s.bases,settle(ended).bases)
        }
        assertEquals(CivicPhase.INVALIDATED,settle(pending,now+1,player.copy(baseId=1050,grid=2200)).civic.orders.getValue(10000).phase)
        assertEquals(pending,CivicActions.settle(galaxy,pending,now+86400,null))
        assertEquals(StrategyActionRejection.BUSY,act(request(53),pending).rejection)
        assertEquals(StrategyActionRejection.BUSY,act(request(54),pending).rejection)
    }
    @Test fun wrongSessionOfficeRankCpAndMissingLocalGroundPreserveEveryBalance() {
        val original=stocks()
        for(q in listOf(request().copy(connection="old"),request().copy(card=24),request().copy(card=195),
            request().copy(rank=1),request().copy(requestToken=65536))) {
            val rejected=act(q);assertNotNull(rejected.rejection);assertEquals(original,rejected.stocks);assertEquals(player,rejected.player)
        }
        val poor=player.copy(pcp=319,mcp=159);val rejected=act(p=poor)
        assertEquals(StrategyActionRejection.CP,rejected.rejection);assertEquals(original,rejected.stocks);assertEquals(poor,rejected.player)
        val substitute=act(p=player.copy(pcp=320,mcp=159));assertNull(substitute.rejection)
        assertEquals(0,substitute.player?.pcp);assertEquals(159,substitute.player?.mcp)
        assertNull(act(request().copy(card=39)).rejection)
        assertEquals(StrategyActionRejection.CARD,act(request().copy(card=44)).rejection)
        assertEquals(StrategyActionRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        val remote=stationed(original,1050).let {s->s.copy(outfits=s.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2487,1049))})}
        assertEquals(StrategyActionRejection.TROOPS,act(s=remote).rejection)
    }
    @Test fun worldActorPublishesNoOrderOrCpWhenTheAcceptanceCommitFails()=runBlocking {
        var savedPlayers=mapOf("actor" to player);var savedStocks=stocks();var fail=false
        val nav=object:NavigationStore {override fun load()=savedPlayers;override fun save(value:Map<String,Navigator>){savedPlayers=value}}
        val inventory=object:PlanetStockStore {override fun load()=savedStocks;override fun save(value:PlanetStocks){savedStocks=value}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=nav,stockStore=inventory,
            worldStateCommitter=WorldStateCommitter {players,s->if(fail)throw IOException("injected civic acceptance failure")
                savedPlayers=players;savedStocks=checkNotNull(s)},npcPolicy=NpcTacticalPolicy(enabled=false))
        val events=mutableListOf<CivicChange>();val subscription=engine.observeCivic {events+=it}
        try {
            val join=CompletableDeferred<Admission>();engine.submit(WorldCommand.Join("actor",Power.EMPIRE,false,join,"c",1))
            assertEquals(Admission.ACCEPTED,withTimeout(5000){join.await()})
            val snapshot=CompletableDeferred<PlanetStocks>();engine.submit(WorldCommand.Stock(1,snapshot));withTimeout(5000){snapshot.await()}
            val before=engine.snapshot();val persisted=savedStocks;val players=savedPlayers;fail=true
            val reply=CompletableDeferred<StrategyActionResult>();engine.submit(WorldCommand.StrategyAction(request(),reply))
            assertFailsWith<IOException>{withTimeout(5000){reply.await()}}
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health());assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks);assertEquals(persisted,savedStocks);assertEquals(players,savedPlayers)
            assertTrue(events.isEmpty())
        } finally {subscription.close();withTimeout(5000){engine.drainAndClose()};scope.cancel()}
    }
    @Test fun allianceUsesItsOwnMilitaryCardsAndGroundRosterAndRebelOwnershipIsDenied() {
        val aSession=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.ALLIANCE,false,"c")}.exportState()
        val aShip=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==3 &&
            UnitSpecifications.ships.single {s->s.kind==it.kind}.category==3}.kind)
        val aGround=UnitStack(UnitSpecifications.crews.first {it.power==3 && it.type!=3}.kind)
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,aShip)))
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(2,aGround,base=1049)))
        val s=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,3,0,2000,200,300,PlanetEconomy(1000,600,70,80))),
            mapOf(200 to OutfitStock(200,3,OutfitKind.FLEET,1049,300,10000,UnitInventory(ships.quantities(),ground.quantities()),
                activeShips=ships,activeGround=ground)),mapOf(300 to OutfitWarehouse(300,3,OutfitKind.FLEET,1049,200)),nextShipId=3)
        val p=player.copy(power=Power.ALLIANCE);val q=request().copy(power=Power.ALLIANCE,card=195,rank=6)
        val accepted=StrategyActions.evaluate(galaxy,q,aSession,p,s);assertNull(accepted.rejection)
        assertEquals(840,accepted.player?.mcp)
        assertEquals(85,CivicActions.settle(galaxy,assertNotNull(accepted.stocks),now+86400,listOf(p)).bases.getValue(1049).economy?.peace)
        assertEquals(StrategyActionRejection.CARD,StrategyActions.evaluate(galaxy,q.copy(card=59),aSession,p,s).rejection)
        val rebel=s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(camp=1)))
        assertEquals(StrategyActionRejection.LOCATION,StrategyActions.evaluate(galaxy,q,aSession,p,rebel).rejection)
    }
    @Test fun guardedSpotDepartureAndInvalidationShareOneCommitAndARejectedSaveMovesNeither() {
        val p=player.copy(mode=1,spotId=guarded);val accepted=act(request(55),p=p)
        var savedPlayers=mapOf("actor" to assertNotNull(accepted.player));var savedStocks=assertNotNull(accepted.stocks)
        val navStore=object:NavigationStore {override fun load()=savedPlayers;override fun save(value:Map<String,Navigator>){savedPlayers=value}}
        val stockStore=object:PlanetStockStore {override fun load()=savedStocks;override fun save(value:PlanetStocks){savedStocks=value}}
        val navigation=Navigation(galaxy,navStore,NavigationPolicy())
        val production=PlanetProduction(galaxy,stockStore,SessionRules.load().startSeconds,PlanetProductionPolicy(),1,
            {generation->navigation.snapshot(generation).values})
        val current=production.settle(1,now);val before=navigation.snapshot(1)
        val publicSpot=galaxy.facilities.getValue(1049).flatMap {it.spots}.first {it.access==SpotAccess.PUBLIC}.id
        val move=NavigationRequest("actor",10000,Power.EMPIRE,"c",1,place=PlaceMove.Spot(publicSpot,0),rank=5)
        assertFailsWith<IOException>{FleetNavigation.apply(move,session,galaxy,navigation,production,current,
            WorldStateCommitter {_,_->throw IOException("injected civic departure failure")}) {false}}
        assertEquals(before,navigation.snapshot(1));assertEquals(current,production.snapshot(1))
        assertEquals(CivicPhase.GUARDING,savedStocks.civic.orders.getValue(10000).phase)
        val result=FleetNavigation.apply(move,session,galaxy,navigation,production,current,
            WorldStateCommitter {players,inventory->savedPlayers=players;savedStocks=checkNotNull(inventory)}) {false}
        assertNull(result.rejection);assertEquals(publicSpot,savedPlayers.getValue("actor").spotId)
        assertEquals(CivicPhase.INVALIDATED,savedStocks.civic.orders.getValue(10000).phase)
        assertEquals(savedStocks,production.snapshot(1));assertEquals(savedPlayers,navigation.snapshot(1))
    }
    @Test fun failedCompletionSaveDoesNotConsumeSuppliesOrPublishAReceiptAndLegacySuppressionRemainsDistinct() {
        val captured=stationed(stocks(),1050);val p=player.copy(grid=2200,baseId=1050)
        val accepted=act(request(56),captured,p);var stored=assertNotNull(accepted.stocks);var fail=false
        val store=object:PlanetStockStore {override fun load()=stored;override fun save(value:PlanetStocks){
            if(fail)throw IOException("injected civic completion failure");stored=value}}
        val production=PlanetProduction(galaxy,store,SessionRules.load().startSeconds,PlanetProductionPolicy(),1,{listOf(p)})
        val before=production.settle(1,now);fail=true
        assertFailsWith<IOException>{production.settle(1,now+86400)}
        assertEquals(before,production.snapshot(1));assertEquals(before,stored)
        fail=false;val complete=production.settle(1,now+86400)
        assertEquals(1000,complete.civic.orders.getValue(10000).effect);assertEquals(1100,complete.outfits.getValue(200).supplies)
        assertEquals(complete,production.settle(1,now+86400))
        val suppression=act(request(53));val old=assertNotNull(suppression.stocks)
        assertTrue(old.civic.orders.isEmpty());assertEquals(1,old.strategic.suppressions.size)
        assertEquals(StrategyActionRejection.BUSY,act(s=old).rejection)
        val settled=StrategyActions.settle(old,now+86400,galaxy)
        assertEquals(85,settled.bases.getValue(1049).economy?.peace);assertEquals(68,settled.bases.getValue(1049).economy?.approval)
    }
}
