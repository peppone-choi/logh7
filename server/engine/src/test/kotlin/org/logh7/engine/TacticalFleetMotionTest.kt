package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalFleetMotionTest {
    @Test fun rotationsKeepFirstSelectedAnchorWaitAtomicallyAndResumeWithoutDuplicateCompletion() {
        for(kind in TacticalManeuver.entries) {
            val s=state();var nav=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
            val command=TacticalMove(listOf(2,1,1010000),0f,(Math.PI/2).toFloat(),listOf(TacticalPoint(999f,999f)),maneuver=kind)
            val request=request().copy(tacticalMove=command)
            val accepted=TacticalFleetMotion.move(request,session,galaxy,nav,p,p.snapshot(1)!!,s.commit,24)
            assertNotNull(accepted.player)
            val wait=if(kind==TacticalManeuver.TURN)120L else 240L
            for((id,motion) in accepted.tacticalMotions) {
                assertEquals(TacticalRotation(kind,2,wait,command.units),motion.rotation)
                assertEquals(motion.origin,motion.pose(now+wait-1))
                assertEquals(0,motion.completedWaypoints(now+wait-1))
                assertEquals(1,motion.completedWaypoints(now+wait))
                if(kind==TacticalManeuver.REVERSE) {
                    assertEquals(motion.origin.position,motion.pose(now+wait).position)
                    assertEquals(Math.PI.toFloat(),kotlin.math.abs(motion.direction),0.00001f)
                } else {
                    assertEquals((Math.PI/2).toFloat(),motion.direction)
                    assertEquals(0.8f,motion.waypoints.single().y,0.00001f)
                    assertEquals(when(id){2->0f;1->-0.8f;else->-0.3f},motion.waypoints.single().x,0.00001f)
                }
            }
            val before=s.stocks;val players=s.players;val events=mutableListOf<TacticalMovement>()
            s.fail=true
            assertFailsWith<java.io.IOException> {TacticalCombat.settle(session.copy(gameMillis=(now+wait)*1000),galaxy,nav,p,s.commit,onMovement=events::add)}
            assertEquals(before,s.stocks);assertEquals(players,s.players);assertTrue(events.isEmpty())
            s.fail=false;nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
            TacticalCombat.settle(session.copy(gameMillis=(now+wait)*1000),galaxy,nav,p,s.commit,onMovement=events::add)
            assertEquals(setOf(1,2,1010000),events.map {it.unit}.toSet())
            assertTrue(events.all {it.waypoint==-1 && it.motion!!.rotation==null})
            assertTrue(events.all {it.turned==(kind==TacticalManeuver.REVERSE)})
            assertEquals(accepted.player.commandRange,s.players.getValue("pilot").commandRange)
            assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
            TacticalCombat.settle(session.copy(gameMillis=(now+wait+1)*1000),galaxy,nav,p,s.commit,onMovement=events::add)
            assertEquals(3,events.size)
        }
    }
    @Test fun pendingRotationsCanBeStoppedAndOutOfRangeCommandsDoNotCommit() {
        for(kind in TacticalManeuver.entries) {
            val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            val r=request().copy(tacticalMove=TacticalMove(listOf(1,2,1010000),0f,1f,listOf(TacticalPoint(0f,0f)),maneuver=kind))
            assertNotNull(TacticalFleetMotion.move(r,session,galaxy,nav,p,p.snapshot(1)!!,s.commit,24).player)
            val stop=r.copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(1,2,1010000)))
            val stopped=TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+16)*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit)
            assertNotNull(stopped.player);assertTrue(stopped.tacticalMotions.values.all {it.rotation==null})
            val events=mutableListOf<TacticalMovement>()
            TacticalCombat.settle(session.copy(gameMillis=(now+300)*1000),galaxy,nav,p,s.commit,onMovement=events::add)
            assertTrue(events.isEmpty());assertEquals(0f,s.players.getValue("pilot").motion!!.direction)
            val denied=state();denied.players=denied.players.mapValues {(_,v)->v.copy(commandRange=TacticalCommandRange(now))}
            val deniedNav=Navigation(galaxy,denied.nav,NavigationPolicy());val deniedProduction=production(denied)
            val before=denied.stocks
            assertEquals(NavigationRejection.RANGE,TacticalFleetMotion.move(r,session,galaxy,deniedNav,deniedProduction,
                deniedProduction.snapshot(1)!!,denied.commit,24).rejection)
            assertEquals(before,denied.stocks)
        }
    }
    @Test fun waypointProgressAndCompletionCommitBeforeNotificationAndSurviveReloadWithoutRepeating() {
        val s=state();var nav=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        val command=TacticalMove(listOf(1,1010000),1f,1.2f,listOf(TacticalPoint(0f,2f),TacticalPoint(3f,2f)))
        assertNotNull(move(s,nav,p,request().copy(tacticalMove=command)).player)
        val range=s.players.getValue("pilot").commandRange
        val events=mutableListOf<TacticalMovement>()
        fun tick(offset:Long)=TacticalCombat.settle(session.copy(gameMillis=(now+offset)*1000),galaxy,nav,p,s.commit,
            onMovement=events::add)
        tick(6);assertTrue(events.isEmpty())
        tick(7);assertEquals(setOf(1,1010000),events.map {it.unit}.toSet())
        assertTrue(events.all {it.waypoint==1 && it.recipients==mapOf(10000 to "current")})
        assertEquals(1,s.players.getValue("pilot").motion!!.reportedWaypoints)
        assertEquals(1,s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.motion.reportedWaypoints)
        events.clear();val before=s.stocks;val players=s.players;s.fail=true
        assertFailsWith<java.io.IOException>{tick(10)}
        assertTrue(events.isEmpty());assertEquals(before,s.stocks);assertEquals(players,s.players)
        s.fail=false;nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        tick(8);assertTrue(events.isEmpty())
        tick(10);assertEquals(2,events.size);assertTrue(events.all {it.waypoint==-1 && it.motion!!.velocity==0f})
        assertEquals(TacticalPose(TacticalPoint(3f,2f),1.2f),events.single {it.unit==1}.motion!!.pose(now+100))
        assertEquals(range,s.players.getValue("pilot").commandRange)
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
        events.clear();tick(30);assertTrue(events.isEmpty())
    }
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.EMPIRE,false,"current")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private fun ai(y:Float)=NpcTacticalState(TacticalMotion(2488,now,TacticalPose(TacticalPoint(0f,y),0f),0f,0f,
        listOf(TacticalPoint(0f,y))),now,now)
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk"); players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state(repair:Boolean=false):State {
        val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith(if(repair)"工作艦" else "輸送艦")}.kind
        val stack=UnitStack(kind);val target=UnitStack(32);val enemy=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==3}.kind)
        val player=Navigator(1,10000,Power.EMPIRE,2488,1000,1000,900,cpPaused=true,supplies=400,
            hull=FlagshipHull(0,now,hp=40),energy=TacticalEnergy(listOf(100,0,0,0,0,0,0,0,0,0,0)),motion=ai(0.5f).motion)
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,commanderId=10000,
            units=UnitInventory(mapOf(stack to 1,target to 1)),supplies=10000,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,tactical=ai(0f),supplies=10000),
                ActiveShipUnit(2,target,250,ai(0.8f),supplies=0,damaged=30))),position=OutfitPosition(2488))
        return State(mapOf("pilot" to player),PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to outfit,2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,
                units=UnitInventory(mapOf(enemy to 1)),activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,enemy))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100))
    }
    private fun production(s:State)=PlanetProduction(galaxy,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
    private fun request(ids:List<Int> = listOf(1,2))=NavigationRequest("pilot",10000,Power.EMPIRE,"current",1,
        shipKind=0,captainCard=59,tacticalMove=TacticalMove(ids,1f,0f,listOf(TacticalPoint(0f,10f))))
    private fun move(s:State,nav:Navigation,p:PlanetProduction,r:NavigationRequest=request())=
        TacticalFleetMotion.move(r,session,galaxy,nav,p,p.snapshot(1)!!,s.commit)

    @Test fun fleetMoveKeepsFormationAndSurvivesNpcTicksAndStopFreezesCurrentPose() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val result=move(s,nav,p)
        assertNotNull(result.player);assertEquals(setOf(1,2),result.tacticalMotions.keys)
        val motions=s.stocks.outfits.getValue(1).activeShips!!.values.associate {it.id to it.tactical!!.motion}
        assertEquals(10f,motions.getValue(1).waypoints.last().y)
        assertEquals(10.8f,motions.getValue(2).waypoints.last().y,0.0001f)
        assertEquals(setOf(10000),s.stocks.outfits.getValue(1).activeShips!!.values.map {it.motionCommander}.toSet())
        TacticalCombat.settle(session.copy(gameMillis=(now+10)*1000),galaxy,nav,p,s.commit,npcPolicy=NpcTacticalPolicy())
        assertEquals(motions,s.stocks.outfits.getValue(1).activeShips!!.values.associate {it.id to it.tactical!!.motion})
        val stop=request().copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(1,2)))
        assertEquals(NavigationRejection.RANGE,TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+10)*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit).rejection)
        val stopped=TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+320)*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit)
        assertNotNull(stopped.player)
        for((id,motion) in stopped.tacticalMotions) {
            assertEquals(0f,motion.velocity);assertEquals(motions.getValue(id).pose(now+320),motion.pose(now+400))
        }
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
        assertEquals(250,s.stocks.outfits.getValue(1).activeShips!!.values.last().boats)
    }
    @Test fun parallelMovementKeepsHeadingAtHalfSpeedAndUsesFirstSelectedFormationAnchor() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val command=TacticalMove(listOf(2,1,1010000),1f,2f,listOf(TacticalPoint(10f,0f)),parallel=true)
        val result=move(s,nav,p,request().copy(tacticalMove=command))
        assertNotNull(result.player);assertEquals(3,result.tacticalMotions.size)
        assertEquals(TacticalPoint(10f,0f),result.tacticalMotions.getValue(2).waypoints.last())
        assertEquals(TacticalPoint(10f,-0.8f),result.tacticalMotions.getValue(1).waypoints.last())
        for(motion in result.tacticalMotions.values) {
            assertTrue(motion.parallel);assertEquals(0.5f,motion.velocity)
            assertEquals(0f,motion.pose(now+9).direction);assertEquals(0f,motion.pose(now+100).direction)
            val point=motion.pose(now+9).position
            assertEquals(2f,kotlin.math.hypot(point.x-motion.origin.position.x,point.y-motion.origin.position.y),0.0001f)
        }
        assertEquals(0f,result.player!!.commandRange!!.radius(now))
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
    }
    @Test fun mixedFlagshipAndFleetMoveIsOneCommitAndForeignUnitDeniesEverything() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks;val beforePlayers=s.players
        assertEquals(NavigationRejection.CARD,move(s,nav,p,request(listOf(1,99,1010000))).rejection)
        assertEquals(before,s.stocks);assertEquals(beforePlayers,s.players)
        assertNotNull(move(s,nav,p,request(listOf(1,1010000))).player)
        assertNotNull(s.players.getValue("pilot").motion)
        assertEquals(10000,s.stocks.outfits.getValue(1).activeShips!!.values.first().motionCommander)
        val stop=request().copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(1,1010000)))
        assertNotNull(TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+8)*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit).player)
        assertEquals(0f,s.players.getValue("pilot").motion!!.velocity)
    }
    @Test fun failedSaveStaleSessionAndOutsideCommandRangeCannotPublishMovement() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks;val players=s.players;s.fail=true
        assertFailsWith<java.io.IOException> {move(s,nav,p)}
        assertEquals(before,s.stocks);assertEquals(players,s.players);s.fail=false
        assertEquals(NavigationRejection.SESSION,move(s,nav,p,request().copy(connection="old")).rejection)
        val far=players.mapValues {(_,v)->v.copy(motion=ai(100f).motion)}
        nav.saveReplacement(nav.snapshot(1),far,1)
        assertEquals(NavigationRejection.RANGE,move(s,nav,p).rejection)
        assertEquals(before,s.stocks)
    }
    @Test fun newCommanderCancelsPreviousCommandedPathBeforeTakingOver() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        move(s,nav,p)
        val current=p.snapshot(1)!!
        p.replace(current,current.copy(outfits=current.outfits+(1 to current.outfits.getValue(1).copy(commanderId=10002))))
        TacticalCombat.settle(session.copy(gameMillis=(now+10)*1000),galaxy,nav,p,s.commit,npcPolicy=NpcTacticalPolicy())
        val units=s.stocks.outfits.getValue(1).activeShips!!.values
        assertTrue(units.all {it.motionCommander==0 && it.tactical!!.motion.velocity==0f})
    }
    @Test fun newTacticalEntryStartsAtZeroAndPersonalMovementDoesNotConsumeFleetRange() {
        val s=state();s.players=s.players.mapValues {(_,p)->p.copy(cpPaused=false,motion=null)}
        var nav=Navigation(galaxy,s.nav,NavigationPolicy())
        val identity=request().copy(tacticalMove=null,commandAbility=50)
        val entered=nav.apply(identity,session,s.stocks).player!!
        assertEquals(0f,entered.commandRange!!.radius(now))
        nav=Navigation(galaxy,s.nav,NavigationPolicy())
        assertEquals(5f,nav.snapshot(1).getValue("pilot").commandRange!!.radius(now+80))
        val personal=identity.copy(tacticalMove=TacticalMove(listOf(entered.unitId),1f,0f,listOf(TacticalPoint(0f,10f))))
        assertNotNull(nav.apply(personal,session,s.stocks).player)
        assertEquals(entered.commandRange,nav.snapshot(1).getValue("pilot").commandRange)
    }
}
