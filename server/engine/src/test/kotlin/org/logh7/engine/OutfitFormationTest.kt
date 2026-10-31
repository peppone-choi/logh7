package org.logh7.engine

import kotlinx.coroutines.*
import java.io.IOException
import org.junit.jupiter.api.Test
import kotlin.test.*

/** 결성/해산의 자산 보존·권한·예약·원자 저장. 작성: Peppone Choi. 실행은 리드 담당. */
class OutfitFormationTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"Other",0,30f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also {
        it.join("actor",Power.EMPIRE,false,"c");it.join("other",Power.EMPIRE,false,"d")}.exportState()
    private val ship=UnitStack(UnitSpecifications.ships.first {s->s.category==3 &&
        ProductionUnits.definitions.any {it.ship && it.kind==s.kind && it.power==2}}.kind,2)
    private val crew=UnitStack(UnitSpecifications.crews.first {it.power==2 && it.type==3}.kind)
    private val troop=UnitStack(UnitSpecifications.crews.first {it.power==2 && it.type!=3}.kind,1)
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
        cpRecoveryAt=SessionRules.load().startSeconds)
    private val code=OutfitFormationPolicy.code(ship,false)
    private fun stocks(card:Int=24)=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,2000,200,300,units=UnitInventory(mapOf(ship to 2),mapOf(crew to 1000,troop to 2),
            mapOf("S${ship.kind}" to 0)),spareShips=SpareShipBoats(mapOf(ship to 137))),
        1050 to PlanetStock(1050,2,0,100,200,300)),deploymentInitialized=true,
        strategic=StrategicState(offices=listOf(OfficeHolding(card,1049,10000))))
    private fun request(phase:FormationPhase=FormationPhase.BEGIN,card:Int=24,kind:OutfitKind=OutfitKind.FLEET,
        command:Int=25)=OutfitFormationRequest(StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,command,card,
            OrganizationOffices.of(card)?.ranks?.first() ?: 5,navigators=mapOf("actor" to player)),phase,1049,kind)
    private fun create(s:PlanetStocks=stocks(),p:Navigator=player,selected:Map<Int,Int> = mapOf(code to 3),
        supplies:Int=0,r:OutfitFormationRequest=request())=OutfitFormationPolicy.create(galaxy,r,p,s,selected,supplies)
    @Test fun formationPreservesPartialBoatsGradesWorkAndCrewAndNeverSelfAppoints() {
        val before=stocks();val selected=mapOf(code to 3,OutfitFormationPolicy.code(troop,true) to 2)
        val result=create(before,selected=selected,supplies=100)
        assertNull(result.rejection);assertTrue(result.changed);assertEquals(680,result.player?.mcp)
        val stored=assertNotNull(result.stocks);val outfit=stored.outfits.getValue(result.outfit)
        assertEquals(0,outfit.commanderId);assertTrue(outfit.members().isEmpty())
        assertEquals(listOf(300,300,137),outfit.activeShips!!.values.map {it.boats})
        assertTrue(outfit.activeShips.values.all {it.stack==ship})
        assertEquals(ReplacementBoats.crewRequired(outfit).toInt(),result.crew)
        assertEquals(result.crew,outfit.units.troops[crew]);assertEquals(1000-result.crew,stored.bases.getValue(1049).units.troops[crew])
        assertEquals(2,outfit.activeGround!!.values.size);assertTrue(outfit.activeGround.values.all {it.base==1049 && it.carrier==0})
        assertEquals(5,stored.nextShipId-1);assertEquals(1900,stored.bases.getValue(1049).supplies)
        assertEquals(100,outfit.supplies);assertEquals(100,outfit.activeShips.values.sumOf {it.supplies ?: 0})
        assertEquals(before.bases.getValue(1049).units.work,stored.bases.getValue(1049).units.work)
        assertTrue(stored.bases.getValue(1049).units.ships.isEmpty());assertTrue(stored.bases.getValue(1049).spareShips.values.isEmpty())
        assertEquals(OutfitSequence(2,2),stored.outfitSequence)
        assertTrue(stored.warehouses.getValue(outfit.warehouseId).units.ships.isEmpty())
    }
    @Test fun crewCpCapacityAndChangedInventoryFailuresNeverPartiallyDebit() {
        val before=stocks();val noCrew=before.copy(bases=before.bases+(1049 to before.bases.getValue(1049).copy(
            units=UnitInventory(mapOf(ship to 2)))))
        assertEquals(StrategyActionRejection.CREW,create(noCrew).rejection)
        val poor=player.copy(pcp=639,mcp=319);val denied=create(before,poor)
        assertEquals(StrategyActionRejection.CP,denied.rejection);assertEquals(before,denied.stocks);assertEquals(poor,denied.player)
        val fallback=create(before,player.copy(pcp=640,mcp=319));assertNull(fallback.rejection)
        assertEquals(0,fallback.player?.pcp);assertEquals(319,fallback.player?.mcp)
        assertEquals(StrategyActionRejection.RESOURCE,create(selected=mapOf(code to 4)).rejection)
        assertEquals(StrategyActionRejection.TROOPS,create(selected=emptyMap()).rejection)
        assertEquals(StrategyActionRejection.RESOURCE,create(supplies=2001).rejection)
        val huge=before.copy(bases=before.bases+(1049 to before.bases.getValue(1049).copy(
            units=UnitInventory(mapOf(ship to 61),mapOf(crew to 1000)))))
        val service=OutfitFormationService {0};val begin=service.evaluate(galaxy,request(),session,player,huge)
        val set=request(FormationPhase.SET_UNIT).copy(target=begin.lease,unit=code,amount=61)
        assertNull(service.evaluate(galaxy,set,session,player,huge).rejection)
        val limit=service.evaluate(galaxy,set.copy(phase=FormationPhase.COMMIT,unit=0,amount=0),session,player,huge)
        assertEquals(StrategyActionRejection.MAXIMUM,limit.rejection);assertEquals(huge,limit.stocks)
    }
    @Test fun crewGradesAreConsumedInOrderAndForeignCapturedStockSurvivesFormation() {
        val s=stocks();val b=s.bases.getValue(1049);val veteran=crew.copy(level=3)
        val foreign=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==3}.kind,1)
        val mixed=s.copy(bases=s.bases+(1049 to b.copy(units=UnitInventory(b.units.ships+(foreign to 1),
            mapOf(crew to 1,veteran to 1000),b.units.work),spareShips=SpareShipBoats(b.spareShips.values+(foreign to 23)))))
        val result=create(mixed);assertNull(result.rejection);assertTrue(result.crew>1)
        val next=assertNotNull(result.stocks);val troops=next.outfits.getValue(result.outfit).units.troops
        assertEquals(1,troops[crew]);assertEquals(result.crew-1,troops[veteran])
        assertEquals(1001-result.crew,next.bases.getValue(1049).units.troops[veteran])
        assertEquals(1,next.bases.getValue(1049).units.ships[foreign]);assertEquals(23,next.bases.getValue(1049).spareShips.values[foreign])
        assertTrue(OutfitFormationPolicy.available(mixed,1049,2,OutfitKind.FLEET).keys.none {
            val (troop,stack)=OutfitFormationPolicy.stack(it);!troop && stack==foreign})
    }
    @Test fun formationRespectsTheActualGridUnitLimit() {
        val s=stocks();val mass=OutfitStock(50,2,OutfitKind.GROUND,1049,60,units=UnitInventory(troops=mapOf(troop to 299)))
        val full=s.copy(outfits=mapOf(50 to mass),warehouses=mapOf(60 to OutfitWarehouse(60,2,OutfitKind.PATROL,1049)))
        val result=create(full,selected=mapOf(code to 1))
        assertEquals(StrategyActionRejection.MAXIMUM,result.rejection);assertEquals(full,result.stocks);assertEquals(player,result.player)
    }
    @Test fun rolesRequireAnActualHeldCardAndMatchingFactionKindRankAndFriendlyLocation() {
        val s=stocks();val q=request()
        assertNull(OutfitFormationPolicy.authority(galaxy,q,player,s))
        assertEquals(StrategyActionRejection.AUTHORITY,OutfitFormationPolicy.authority(galaxy,q,player,s.copy(strategic=StrategicState())))
        assertEquals(StrategyActionRejection.CARD,OutfitFormationPolicy.authority(galaxy,q.copy(kind=OutfitKind.TRANSPORT),player,s))
        assertEquals(StrategyActionRejection.AUTHORITY,OutfitFormationPolicy.authority(galaxy,q.copy(identity=q.identity.copy(rank=20)),player,s))
        assertEquals(StrategyActionRejection.LOCATION,OutfitFormationPolicy.authority(galaxy,q,player.copy(baseId=1050),s))
        assertEquals(StrategyActionRejection.BUSY,OutfitFormationPolicy.authority(galaxy,q,player.copy(cpPaused=true),s))
        assertEquals(setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT,OutfitKind.PATROL),OutfitFormationPolicy.kinds(164))
        assertTrue(OutfitFormationPolicy.kinds(150).isEmpty());assertTrue(OutfitFormationPolicy.kinds(147).isEmpty())
        assertEquals(setOf(OutfitKind.GROUND),OutfitFormationPolicy.kinds(152))
    }
    @Test fun dissolutionReturnsOnlySurvivorsAndBothDepotsClearsOfficesAndNeverReusesTheDeletedId() {
        val formed=create(selected=mapOf(code to 3));val first=assertNotNull(formed.stocks)
        val outfit=first.outfits.getValue(formed.outfit)
        val damagedByLoss=outfit.copy(commanderId=10001,position=OutfitPosition(2487,1050),
            activeShips=ActiveShipUnits(outfit.activeShips!!.values.mapIndexed {index,u->u.copy(boats=if(index==0)0 else u.boats)}))
        val warehouse=first.warehouses.getValue(outfit.warehouseId).copy(units=UnitInventory(mapOf(ship to 1)),
            spareShips=SpareShipBoats(mapOf(ship to 19)),supplies=11,food=12,mineral=13)
        val before=first.copy(outfits=mapOf(outfit.id to damagedByLoss),warehouses=mapOf(warehouse.id to warehouse),
            strategic=first.strategic.copy(offices=first.strategic.offices+OfficeHolding(43,outfit.id,10002)),
            tacticalCommands=TacticalCommandState(morale=mapOf(1 to 50,99 to 75),authority=mapOf(1 to 10001),
                formations=mapOf(1 to 2),formationLeaders=mapOf(99 to 1)))
        val p=assertNotNull(formed.player).copy(baseId=1050)
        val q=request(FormationPhase.DELETE,command=26).copy(base=1050,target=outfit.id)
        val result=OutfitFormationPolicy.dissolve(galaxy,q,p,before);assertNull(result.rejection)
        val next=assertNotNull(result.stocks);assertTrue(next.outfits.isEmpty());assertTrue(next.warehouses.isEmpty())
        assertEquals(520,result.player?.mcp);assertEquals(setOf(10001,10002),result.affected)
        assertTrue(next.strategic.offices.none {it.seat==outfit.id});assertEquals(formed.player?.grid,result.player?.grid)
        assertEquals(mapOf(99 to 75),next.tacticalCommands.morale);assertTrue(next.tacticalCommands.authority.isEmpty())
        assertTrue(next.tacticalCommands.formations.isEmpty());assertTrue(next.tacticalCommands.formationLeaders.isEmpty())
        assertEquals(1,next.bases.getValue(1050).units.ships[ship]);assertEquals(137,next.bases.getValue(1050).spareShips.values[ship])
        assertEquals(1,next.bases.getValue(1049).units.ships[ship]);assertEquals(19,next.bases.getValue(1049).spareShips.values[ship])
        assertEquals(2011,next.bases.getValue(1049).supplies);assertEquals(212,next.bases.getValue(1049).food)
        assertEquals(313,next.bases.getValue(1049).mineral);assertEquals(crew, next.bases.getValue(1050).units.troops.keys.single())
        val second=create(next,selected=mapOf(code to 1));assertNull(second.rejection)
        assertTrue(second.outfit>outfit.id);assertTrue(assertNotNull(second.stocks).warehouses.keys.single()>warehouse.id)
        assertTrue(second.stocks.nextShipId>first.nextShipId)
    }
    @Test fun allianceEquipmentOfficeIsDistinctFromAllocatorAndOperationsStaff() {
        val a=Galaxy(listOf(GalaxySystem(3,2015,4,"Home",3)),listOf(
            GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2015,Power.ALLIANCE to 2015))
        val s=PlanetStocks(1,0,mapOf(1177 to PlanetStock(1177,3,0,100,200,300)),
            strategic=StrategicState(offices=listOf(OfficeHolding(164,1177,10000))))
        val p=player.copy(power=Power.ALLIANCE,grid=2015,baseId=1177)
        val q=request(card=164).let {it.copy(identity=it.identity.copy(power=Power.ALLIANCE),base=1177)}
        for(kind in setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT,OutfitKind.PATROL))
            assertNull(OutfitFormationPolicy.authority(a,q.copy(kind=kind),p,s))
        assertEquals(StrategyActionRejection.CARD,OutfitFormationPolicy.authority(a,q.copy(kind=OutfitKind.GROUND),p,s))
        for(card in listOf(147,150))assertEquals(StrategyActionRejection.CARD,
            OutfitFormationPolicy.authority(a,q.copy(identity=q.identity.copy(card=card)),p,s))
    }
    @Test fun sharedWarehouseAndOtherPatrolRemainIntactAndGroundDissolutionCannotTeleportOrRepair() {
        val s=stocks(25);val existing=OutfitStock(7,2,OutfitKind.PATROL,1049,9,number=1)
        val warehouse=OutfitWarehouse(9,2,OutfitKind.PATROL,1049,units=UnitInventory(mapOf(ship to 1)),supplies=99)
        val before=s.copy(outfits=mapOf(7 to existing),warehouses=mapOf(9 to warehouse))
        val q=request(card=25,kind=OutfitKind.PATROL)
        val formed=create(before,selected=mapOf(code to 1),r=q);assertNull(formed.rejection)
        val current=assertNotNull(formed.stocks);val outfit=current.outfits.getValue(formed.outfit)
        assertEquals(9,outfit.warehouseId);assertEquals(warehouse,current.warehouses.getValue(9))
        fun dissolve(s:PlanetStocks)=OutfitFormationPolicy.dissolve(galaxy,q.copy(phase=FormationPhase.DELETE,target=outfit.id,
            identity=q.identity.copy(command=26)),assertNotNull(formed.player),s)
        val finished=dissolve(current);assertNull(finished.rejection)
        assertEquals(mapOf(7 to existing),finished.stocks?.outfits);assertEquals(before.warehouses,finished.stocks?.warehouses)
        val units=outfit.activeShips!!.values.map {it.copy(damaged=1)}
        val damaged=current.copy(outfits=current.outfits+(outfit.id to outfit.copy(activeShips=ActiveShipUnits(units))))
        assertEquals(StrategyActionRejection.BUSY,dissolve(damaged).rejection)
        val order=TacticalQueuedCommand(10000,2487,0,10,TacticalCommand(0x419,listOf(units.single().id)))
        val pending=current.copy(tacticalCommands=TacticalCommandState(orders=listOf(order)))
        val blocked=dissolve(pending);assertEquals(StrategyActionRejection.BUSY,blocked.rejection)
        assertEquals(pending,blocked.stocks);assertEquals(formed.player,blocked.player)
        val g=request(card=25,kind=OutfitKind.GROUND)
        val ground=create(s,selected=mapOf(OutfitFormationPolicy.code(troop,true) to 1),r=g);assertNull(ground.rejection)
        val gs=assertNotNull(ground.stocks);val go=gs.outfits.getValue(ground.outfit)
        for(u in listOf(go.activeGround!!.values.single().copy(base=1050),go.activeGround.values.single().copy(strength=999),
            go.activeGround.values.single().copy(base=0))) {
            val bad=gs.copy(outfits=mapOf(go.id to go.copy(activeGround=ActiveGroundUnits(listOf(u)))))
            val denied=OutfitFormationPolicy.dissolve(galaxy,g.copy(phase=FormationPhase.DELETE,target=go.id),player,bad)
            assertEquals(StrategyActionRejection.BUSY,denied.rejection);assertEquals(bad,denied.stocks)
        }
    }
    @Test fun leasesExpireOnTimeoutAndReconnectAndSelectionReplacesRatherThanDuplicates() {
        var now=0L;val service=OutfitFormationService {now};val s=stocks();val q=request()
        fun act(r:OutfitFormationRequest,p:Navigator=player,state:SessionState=session,stock:PlanetStocks=s)=
            service.evaluate(galaxy,r,state,p,stock)
        val begin=act(q);assertTrue(begin.lease>0);assertTrue(service.blocksBase(session,1049))
        assertEquals(begin.lease,act(q).lease)
        val set=q.copy(phase=FormationPhase.SET_UNIT,target=begin.lease,unit=code,amount=1)
        assertEquals(1,act(set).items.single {it.code==code}.selected)
        assertEquals(1,act(set).items.single {it.code==code}.selected)
        val foreign=set.copy(identity=set.identity.copy(account="other",connection="d",characterId=10001))
        assertEquals(StrategyActionRejection.AUTHORITY,act(foreign,player.copy(characterId=10001)).rejection)
        val wrong=set.copy(identity=set.identity.copy(connection="old"))
        assertEquals(StrategyActionRejection.SESSION,act(wrong).rejection)
        val lost=s.copy(bases=s.bases+(1049 to s.bases.getValue(1049).copy(units=UnitInventory(troops=mapOf(crew to 1000)),spareShips=SpareShipBoats())))
        assertEquals(StrategyActionRejection.RESOURCE,act(set.copy(phase=FormationPhase.COMMIT,unit=0,amount=0),stock=lost).rejection)
        val cancel=act(set.copy(phase=FormationPhase.CANCEL),player.copy(baseId=1050));assertNull(cancel.rejection)
        assertEquals(s,cancel.stocks);assertEquals(1000,cancel.player?.mcp);assertFalse(service.blocksBase(session,1049))
        val second=act(q);now=120000
        assertEquals(StrategyActionRejection.TARGET,act(set.copy(target=second.lease)).rejection)
        val third=act(q);val offline=session.copy(participants=session.participants+("actor" to session.participants.getValue("actor").copy(online=false,connection=null)))
        assertFalse(service.blocksBase(offline,1049));assertEquals(StrategyActionRejection.TARGET,act(set.copy(target=third.lease)).rejection)
        val fourth=act(q);val selected=set.copy(target=fourth.lease);act(selected)
        val commit=selected.copy(phase=FormationPhase.COMMIT,unit=0,amount=0);val done=act(commit);assertTrue(done.changed)
        service.complete(commit)
        val replay=act(commit,assertNotNull(done.player),stock=assertNotNull(done.stocks))
        assertEquals(StrategyActionRejection.TARGET,replay.rejection);assertEquals(done.stocks,replay.stocks)
    }
    @Test fun worldActorDoesNotPublishCpUnitsOrIdsOnCommitFailure() = runBlocking {
        var savedPlayers=mapOf("actor" to player)
        var savedStocks=stocks().copy(outfits=mapOf(100 to OutfitStock(100,2,OutfitKind.FLEET,1049,200,10000)),
            warehouses=mapOf(200 to OutfitWarehouse(200,2,OutfitKind.FLEET,1049,100)),
            logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_FIRST))
        var fail=false
        val navigation=object:NavigationStore {override fun load()=savedPlayers;override fun save(value:Map<String,Navigator>){savedPlayers=value}}
        val inventory=object:PlanetStockStore {override fun load()=savedStocks;override fun save(value:PlanetStocks){savedStocks=value}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=inventory,
            worldStateCommitter=WorldStateCommitter {nav,stock->if(fail)throw IOException("injected formation commit failure")
                savedPlayers=nav;savedStocks=checkNotNull(stock)},npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val join=CompletableDeferred<Admission>();engine.submit(WorldCommand.Join("actor",Power.EMPIRE,false,join,"c",1))
            assertEquals(Admission.ACCEPTED,withTimeout(5000){join.await()})
            suspend fun action(r:OutfitFormationRequest):OutfitFormationResult {val reply=CompletableDeferred<OutfitFormationResult>()
                engine.submit(WorldCommand.FormOutfit(r,reply));return withTimeout(5000){reply.await()}}
            val prepare=LogisticsRequest("actor","c",10000,Power.EMPIRE,1,100,LogisticsMode.REORGANIZATION,LogisticsPhase.PREPARE)
            val lock=CompletableDeferred<LogisticsResult>();engine.submit(WorldCommand.Logistics(prepare,lock))
            val held=withTimeout(5000){lock.await()};assertNull(held.rejection)
            assertEquals(StrategyActionRejection.BUSY,action(request()).rejection)
            val cancel=CompletableDeferred<LogisticsResult>()
            engine.submit(WorldCommand.Logistics(prepare.copy(phase=LogisticsPhase.CANCEL,reservation=held.reservation),cancel))
            assertNull(withTimeout(5000){cancel.await()}.rejection)
            val begin=action(request());assertNull(begin.rejection)
            val allocation=CompletableDeferred<LogisticsResult>()
            engine.submit(WorldCommand.Logistics(prepare.copy(mode=LogisticsMode.ALLOCATION),allocation))
            assertEquals(LogisticsRejection.BUSY,withTimeout(5000){allocation.await()}.rejection)
            val assignment=CompletableDeferred<StockAssignmentResult>()
            engine.submit(WorldCommand.AssignStock(StockAssignmentRequest("actor","c",10000,Power.EMPIRE,1,1049,100,0,
                emptyList(),emptyList(),0),assignment))
            assertEquals(LogisticsRejection.BUSY,withTimeout(5000){assignment.await()}.rejection)
            val selected=request(FormationPhase.SET_UNIT).copy(target=begin.lease,unit=code,amount=1);action(selected)
            val before=engine.snapshot();val persisted=savedStocks;val nav=savedPlayers;fail=true
            assertFailsWith<IOException>{action(selected.copy(phase=FormationPhase.COMMIT,unit=0,amount=0))}
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health());assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks);assertEquals(persisted,savedStocks);assertEquals(nav,savedPlayers)
        } finally {withTimeout(5000){engine.drainAndClose()};scope.cancel()}
    }
}
