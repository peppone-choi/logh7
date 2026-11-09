package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalFleetMotionTest {
    @Test fun deceasedCommanderCannotMoveAFlagshipOrItsFleet() {
        val s=state()
        s.stocks=BattleDeath.apply(s.stocks,listOf(10000),now) {_,_->true}
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val players=s.players;val stocks=s.stocks
        assertEquals(NavigationRejection.IDENTITY,move(s,nav,p,request(listOf(1,1010000))).rejection)
        assertEquals(NavigationRejection.IDENTITY,nav.apply(request(listOf(1010000)),session,p.snapshot(1)!!).rejection)
        assertEquals(players,s.players);assertEquals(stocks,s.stocks)
    }
    @Test fun detentionRejectsNewMovementAndStopsSavedFlagshipAndFleetMotion() {
        val s=state()
        val mixed=request(listOf(1,1010000))
        val accepted=move(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),mixed)
        assertEquals(setOf(1,1010000),accepted.tacticalMotions.keys)
        val detention=Detention(10000,Power.EMPIRE.country,now,now+Justice.DAY)
        s.stocks=s.stocks.copy(social=s.stocks.social.copy(justice=JusticeState(detentions=mapOf(10000 to detention))))
        val nav=Navigation(galaxy,s.nav,NavigationPolicy())
        val p=production(s)
        assertEquals(NavigationRejection.BUSY,move(s,nav,p,mixed).rejection)
        assertEquals(NavigationRejection.BUSY,nav.apply(request(listOf(1010000)),session,p.snapshot(1)!!).rejection)
        val events=mutableListOf<TacticalMovement>()
        val tick=session.copy(gameMillis=(now+10)*1000)
        TacticalCombat.settle(tick,galaxy,nav,p,s.commit,onMovement=events::add)
        assertEquals(setOf(1,1010000),events.map {it.unit}.toSet())
        for(event in events) {
            assertEquals(-1,event.waypoint)
            assertEquals(0f,event.motion!!.velocity)
            assertEquals(accepted.tacticalMotions.getValue(event.unit).pose(now+10),event.motion.origin)
        }
        TacticalCombat.settle(tick,galaxy,nav,p,s.commit,onMovement=events::add)
        assertEquals(2,events.size)
    }
    @Test fun removalOfDelegatedAuthorityStopsOnlyTheFormerOfficersMovement() {
        val s = state()
        val outfit = s.stocks.outfits.getValue(1)
        s.stocks = s.stocks.copy(
            outfits = s.stocks.outfits + (1 to outfit.copy(commanderId = 10001, aideId = 10000)),
            tacticalCommands = s.stocks.tacticalCommands.copy(authority = mapOf(1 to 10000, 2 to 10000)),
        )
        val accepted = move(s, Navigation(galaxy, s.nav, NavigationPolicy()), production(s))
        assertEquals(setOf(1, 2), accepted.tacticalMotions.keys)
        val before = accepted.tacticalMotions.getValue(1)
        val office = OfficeHolding(FleetOffice.EMPIRE_AIDE.card, 1, 10000, Power.EMPIRE.country)
        s.stocks = OrganizationalOfficePolicy(galaxy).set(s.stocks, office, remove = true)
        val changed = s.stocks.outfits.getValue(1)
        val units = changed.activeShips!!.values
        val successor = units.single { it.id == 2 }.copy(motionCommander = 10001)
        s.stocks = s.stocks.copy(outfits = s.stocks.outfits +
            (1 to changed.copy(activeShips = ActiveShipUnits(units.map { if (it.id == 2) successor else it }))))
        val nav = Navigation(galaxy, s.nav, NavigationPolicy())
        val production = production(s)
        val events = mutableListOf<TacticalMovement>()
        val time = now + 7
        val tick = session.copy(gameMillis = time * 1000)

        TacticalCombat.settle(tick, galaxy, nav, production, s.commit, onMovement = events::add)
        val stop = events.single()
        assertEquals(1, stop.unit)
        assertEquals(10000, stop.character)
        assertEquals(-1, stop.waypoint)
        val stopped = s.stocks.outfits.getValue(1).activeShips!!.values.single { it.id == 1 }
        assertEquals(0, stopped.motionCommander)
        assertEquals(before.pose(time), stopped.tactical!!.motion.pose(time + 100))
        assertEquals(successor, s.stocks.outfits.getValue(1).activeShips!!.values.single { it.id == 2 })

        TacticalCombat.settle(tick, galaxy, nav, production, s.commit, onMovement = events::add)
        assertEquals(1, events.size)
    }

    @Test fun waypointProgressLeavesUnauthorizedRetreatForItsOwnSettlementAndAdvancesAutonomousMovement() {
        val s = state()
        val accepted = move(s, Navigation(galaxy, s.nav, NavigationPolicy()), production(s))
        val outfit = s.stocks.outfits.getValue(1)
        val units = outfit.activeShips!!.values
        val retreating = units.single { it.id == 1 }.let {
            it.copy(tactical = it.tactical!!.copy(retreat = TacticalRetreat(2487, now, 5, 150)))
        }
        val autonomous = units.single { it.id == 2 }.copy(motionCommander = 0)
        val before = s.stocks.copy(outfits = s.stocks.outfits + (1 to outfit.copy(
            commanderId = 10001, activeShips = ActiveShipUnits(listOf(retreating, autonomous)),
        )))
        val tick = session.copy(gameMillis = (now + 60) * 1000)
        val events = mutableListOf<TacticalMovement>()
        val players = s.players.toMutableMap()

        val progressed = TacticalMotionProgress.advance(tick, galaxy, players, before, events)
        assertEquals(retreating, progressed.outfits.getValue(1).activeShips!!.values.single { it.id == 1 })
        val completed = events.single()
        assertEquals(2, completed.unit)
        assertEquals(0, completed.character)
        assertEquals(accepted.tacticalMotions.getValue(2).pose(now + 60), assertNotNull(completed.motion).origin)
        val settled = TacticalFleetRetreat.advance(tick, galaxy, players, progressed, mutableListOf())
        assertNull(settled.outfits.getValue(1).activeShips!!.values.single { it.id == 1 }.tactical!!.retreat)
    }

    @Test fun aCommandersDifferentStyleFlagshipMovesWithItsOwnCountrysFleet() {
        val s=state()
        s.players=s.players.mapValues {(_,p)->p.copy(shipStyle=ShipStyleId("alliance"),hull=FlagshipHull(89,now,hp=40))}
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val r=request(listOf(1,2,1010000)).copy(shipKind=89)
        val accepted=move(s,nav,p,r)
        assertNull(accepted.rejection)
        assertEquals(CountryId(2),accepted.player?.country);assertEquals(ShipStyleId("alliance"),accepted.player?.shipStyle)
        assertEquals(setOf(1,2,1010000),accepted.tacticalMotions.keys)
    }
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
            assertTrue(events.all {(it.reverseTarget!=null)==(kind==TacticalManeuver.REVERSE)})
            assertEquals(accepted.player.commandRange,s.players.getValue("pilot").commandRange)
            assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
            TacticalCombat.settle(session.copy(gameMillis=(now+wait+1)*1000),galaxy,nav,p,s.commit,onMovement=events::add)
            assertEquals(3,events.size)
        }
    }
    @Test fun deceasedCommandersSavedRotationStopsAtTheOriginalPose() = assertSavedRotationStopsAtTheOriginalPose { s ->
        s.stocks=BattleDeath.apply(s.stocks,listOf(10000),now) {_,_->true}
    }
    @Test fun detainedCommandersSavedRotationStopsAtTheOriginalPose() = assertSavedRotationStopsAtTheOriginalPose { s ->
        val detention=Detention(10000,Power.EMPIRE.country,now,now+Justice.DAY)
        s.stocks=s.stocks.copy(social=s.stocks.social.copy(justice=JusticeState(detentions=mapOf(10000 to detention))))
    }
    @Test fun woundedCommandersSavedRotationStopsAtTheOriginalPose() = assertSavedRotationStopsAtTheOriginalPose { s ->
        val pilot=s.players.getValue("pilot")
        s.players=s.players+("pilot" to pilot.copy(hull=pilot.hull!!.copy(woundedUntil=now+30)))
    }
    private fun assertSavedRotationStopsAtTheOriginalPose(invalidate:(State)->Unit) {
        for(kind in TacticalManeuver.entries) {
            val s=state()
            val request=request().copy(tacticalMove=TacticalMove(listOf(1,2,1010000),0f,1f,
                listOf(TacticalPoint(0f,0f)),maneuver=kind))
            val accepted=move(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),request)
            assertEquals(3,accepted.tacticalMotions.size)
            invalidate(s)
            val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            val events=mutableListOf<TacticalMovement>()
            TacticalCombat.settle(session.copy(gameMillis=(now+20)*1000),galaxy,nav,p,s.commit,onMovement=events::add)
            assertEquals(3,events.size)
            for(event in events) {
                val stopped=assertNotNull(event.motion)
                assertNull(stopped.rotation);assertNull(event.reverseTarget)
                assertEquals(accepted.tacticalMotions.getValue(event.unit).origin,stopped.pose(now+20))
            }
            assertNull(s.players.getValue("pilot").motion!!.rotation)
            assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.tactical!!.motion.rotation==null})
        }
    }
    @Test fun commanderChangingCampCannotMoveOrRotateShipsInTheOtherCamp() {
        val s=state()
        val command=request().copy(tacticalMove=TacticalMove(listOf(1,2,1010000),0f,1f,
            listOf(TacticalPoint(0f,0f)),maneuver=TacticalManeuver.REVERSE))
        val accepted=move(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),command)
        assertEquals(3,accepted.tacticalMotions.size)
        val plot=Plot(10000,Power.EMPIRE.country,now,revolt=5)
        s.stocks=s.stocks.copy(social=s.stocks.social.copy(coup=CoupState(mapOf(10000 to plot))))
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val stock=production(s)
        assertEquals(NavigationRejection.CARD,move(s,nav,stock,request(listOf(1))).rejection)
        assertEquals(NavigationRejection.CARD,move(s,nav,stock,command).rejection)

        val deadline=accepted.tacticalMotions.getValue(1).startsAt
        val events=mutableListOf<TacticalMovement>()
        TacticalCombat.settle(session.copy(gameMillis=deadline*1000),galaxy,nav,stock,s.commit,onMovement=events::add)
        for(id in listOf(1,2)) {
            val stopped=events.single {it.unit==id}
            assertNull(stopped.reverseTarget)
            assertEquals(accepted.tacticalMotions.getValue(id).origin,stopped.motion!!.pose(deadline))
            assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.single {it.id==id}.tactical!!.motion.rotation)
        }
        assertNotNull(events.single {it.unit==1010000}.reverseTarget)
    }

    @Test fun reloadedRotationCancelsOnlyTheShipWhoseCommanderChanged() {
        for (kind in TacticalManeuver.entries) {
            val s=state()
            val r=request().copy(tacticalMove=TacticalMove(listOf(1,2),0f,1f,
                listOf(TacticalPoint(0f,0f)),maneuver=kind))
            val accepted=move(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s),r)
            assertEquals(2,accepted.tacticalMotions.size)
            s.stocks=s.stocks.copy(tacticalCommands=s.stocks.tacticalCommands.copy(authority=mapOf(1 to 10001)))
            val nav=Navigation(galaxy,s.nav,NavigationPolicy())
            val p=production(s)
            val events=mutableListOf<TacticalMovement>()
            val deadline=accepted.tacticalMotions.getValue(1).startsAt
            TacticalCombat.settle(session.copy(gameMillis=deadline*1000),galaxy,nav,p,s.commit,onMovement=events::add)
            val cancelled=events.single {it.unit==1}
            assertNull(cancelled.reverseTarget)
            assertEquals(accepted.tacticalMotions.getValue(1).origin,cancelled.motion!!.pose(deadline))
            val completed=events.single {it.unit==2}
            assertEquals(accepted.tacticalMotions.getValue(2).pose(deadline),completed.motion!!.pose(deadline))
            assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.tactical!!.motion.rotation==null})
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
    @Test fun stoppingTheAnchorAndReplacingAnotherShipKeepsTheRemainingTurn() {
        val s=state()
        var nav=Navigation(galaxy,s.nav,NavigationPolicy())
        var p=production(s)
        fun at(offset:Long)=session.copy(gameMillis=(now+offset)*1000)
        fun maneuver(ids:List<Int>,kind:TacticalManeuver)=request(ids).copy(tacticalMove=
            TacticalMove(ids,0f,1f,listOf(TacticalPoint(0f,0f)),maneuver=kind))

        val flagship=1010000
        val original=TacticalFleetMotion.move(maneuver(listOf(flagship,1,2),TacticalManeuver.TURN),
            session,galaxy,nav,p,p.snapshot(1)!!,s.commit,24).tacticalMotions
        val stop=request().copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(flagship)))
        val stopped=TacticalSupportOperations.stop(stop,at(24),galaxy,nav,p,p.snapshot(1)!!,s.commit)
        assertEquals(original.getValue(flagship).origin,stopped.tacticalMotions.getValue(flagship).origin)

        val replaced=TacticalFleetMotion.move(maneuver(listOf(1),TacticalManeuver.REVERSE),
            at(48),galaxy,nav,p,p.snapshot(1)!!,s.commit,24).tacticalMotions.getValue(1)
        assertEquals(original.getValue(1).origin,replaced.origin)

        nav=Navigation(galaxy,s.nav,NavigationPolicy())
        p=production(s)
        val events=mutableListOf<TacticalMovement>()
        fun settle(offset:Long)=TacticalCombat.settle(at(offset),galaxy,nav,p,s.commit,onMovement=events::add)
        settle(119)
        assertTrue(events.isEmpty())
        settle(120)
        val turn=events.single()
        assertEquals(2,turn.unit)
        assertEquals(original.getValue(2).pose(now+120),turn.motion!!.origin)
        assertNull(turn.reverseTarget)
        settle(287)
        assertEquals(1,events.size)
        settle(288)
        val reverse=events.last()
        assertEquals(1,reverse.unit)
        assertNotNull(reverse.reverseTarget)
        assertEquals(replaced.pose(now+288),reverse.motion!!.origin)
        settle(300)
        assertEquals(listOf(2,1),events.map {it.unit})
        val stationary=s.players.getValue("pilot").motion!!
        assertNull(stationary.rotation)
        assertEquals(original.getValue(flagship).origin,stationary.origin)
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
        assertFailsWith<java.io.IOException>{tick(12)} // Translation plus both native turns.
        assertTrue(events.isEmpty());assertEquals(before,s.stocks);assertEquals(players,s.players)
        s.fail=false;nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        tick(8);assertTrue(events.isEmpty())
        tick(12);assertEquals(2,events.size);assertTrue(events.all {it.waypoint==-1 && it.motion!!.velocity==0f})
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

    @Test fun newMovementPreservesOnlyTheSlowdownAlreadyAppliedByAnAttackWing() {
        for(active in listOf(false,true)) {
            val s=state()
            val attack=TacticalAirWing(10001,2488,99,1,1,now+100,if(active).25f else null)
            val approaching=attack.copy(source=98,slowVelocity=null)
            val escort=attack.copy(source=97,mode=0,slowVelocity=null)
            val wings=listOf(attack,approaching,escort).associateBy {it.source}
            s.stocks=s.stocks.copy(tacticalCommands=s.stocks.tacticalCommands.copy(air=wings))
            val accepted=move(s,Navigation(galaxy,s.nav,NavigationPolicy()),production(s))
            assertNotNull(accepted.player)
            assertEquals(if(active).5f else 1f,accepted.tacticalMotions.getValue(1).velocity)
            assertEquals(1f,accepted.tacticalMotions.getValue(2).velocity)
            val updated=s.stocks.tacticalCommands.air
            assertEquals(if(active)1f else null,updated.getValue(99).slowVelocity)
            assertEquals(approaching,updated.getValue(98))
            assertEquals(escort,updated.getValue(97))
        }
    }

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
