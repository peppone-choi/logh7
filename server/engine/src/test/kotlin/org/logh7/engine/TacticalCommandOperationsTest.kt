package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalCommandOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {
        it.join("pilot",Power.EMPIRE,false,"one");it.join("ally",Power.EMPIRE,false,"two")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Battle",2)),
        listOf(GalaxyBase(1000,2488,80,1,"Fortress",0,0f,86400,0,0f,2f,mobile=true)),Power.entries.associateWith {2488})
    private fun motion(x:Float=0f,y:Float=0f)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,y),0f),0f,0f,listOf(TacticalPoint(x,y)))
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val stock=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state():State {
        val carrier=UnitStack(64);val repair=UnitStack(72);val enemy=UnitStack(119)
        fun unit(id:Int,stack:UnitStack,y:Float,supplies:Int)=ActiveShipUnit(id,stack,tactical=NpcTacticalState(motion(0f,y),now,now),supplies=supplies)
        val ships=ActiveShipUnits(listOf(unit(1,carrier,.4f,100),unit(2,repair,.8f,0)))
        val enemyShips=ActiveShipUnits(listOf(unit(99,enemy,1.5f,0)))
        val players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,cpPaused=true,
            hull=FlagshipHull(0,now),motion=motion(),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM)),
            "ally" to Navigator(1,10001,Power.EMPIRE,2488,100,100,1000,cpPaused=true,hull=FlagshipHull(0,now),
                motion=motion(100f),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM)))
        return State(players,PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1000,0,0,garrisonCommanderId=10000)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships.quantities()),supplies=100,
                activeShips=ships,position=OutfitPosition(2488)),
                2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,units=UnitInventory(enemyShips.quantities()),
                    activeShips=enemyShips,position=OutfitPosition(2488))),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100,strategic=StrategicState(offices=listOf(OfficeHolding(51,1000,10000)))))
    }
    private class Run(val state:State,val navigation:Navigation,val production:PlanetProduction)
    private fun run(s:State=state()):Run=Run(s,Navigation(galaxy,s.nav,NavigationPolicy(),clockScale=1),
        PlanetProduction(galaxy,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)})
    private fun request(c:TacticalCommand,account:String="pilot")=NavigationRequest(account,if(account=="pilot")10000 else 10001,
        Power.EMPIRE,if(account=="pilot")"one" else "two",1,captainCard=59,shipKind=0,rank=if(account=="pilot")10 else 20,
        tacticalCommand=c,tacticalRanks=mapOf(10000 to 10,10001 to 20))
    private fun accept(r:Run,c:TacticalCommand,account:String="pilot")=TacticalCommandOperations.accept(request(c,account),session,galaxy,
        r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1)
    private fun settle(r:Run,at:Long,events:MutableList<TacticalCommandResult> = mutableListOf(),impacts:MutableList<TacticalImpact> = mutableListOf())=
        TacticalCombat.settle(session.copy(gameMillis=at*1000),galaxy,r.navigation,r.production,r.state.commit,scale=1,onCommand=events::add).also {impacts+=it}

    @Test fun deceasedCommanderCannotIssueSpecialCommandsAndSavedEncouragementIsCancelled() {
        val s=state()
        s.stocks=s.stocks.copy(tacticalCommands=TacticalCommandState(morale=mapOf(1 to 15)))
        val command=TacticalCommand(0x409,listOf(1010000))
        assertNotNull(accept(run(s),command).player)
        s.stocks=BattleDeath.apply(s.stocks,listOf(10000),now) {_,_->true}
        val restored=run(s)
        val before=s.players.getValue("pilot")
        val orders=s.stocks.tacticalCommands.orders
        assertEquals(NavigationRejection.IDENTITY,accept(restored,command).rejection)
        assertEquals(before,s.players.getValue("pilot"))
        assertEquals(orders,s.stocks.tacticalCommands.orders)
        val events=mutableListOf<TacticalCommandResult>()
        settle(restored,now+5,events)
        assertTrue(events.single().cancelled)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        assertEquals(15,s.stocks.tacticalCommands.morale[1])
    }

    @Test fun eliminatedCommanderCannotExecuteSavedEncouragement() {
        val s=state()
        s.stocks=s.stocks.copy(tacticalCommands=TacticalCommandState(morale=mapOf(1 to 15)))
        assertNotNull(accept(run(s),TacticalCommand(0x409,listOf(1010000))).player)
        val order=s.stocks.tacticalCommands.orders.single()
        val defeated=session.copy(gameMillis=order.readyAt*1000,participants=emptyMap(),
            eliminated=mapOf(Power.EMPIRE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        val restored=run(s)
        val events=mutableListOf<TacticalCommandResult>()
        TacticalCombat.settle(defeated,galaxy,restored.navigation,restored.production,s.commit,
            scale=1,onCommand=events::add)

        assertTrue(events.single().cancelled)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        assertEquals(15,s.stocks.tacticalCommands.morale[1])
    }

    @Test fun tacticalEntryAssignsShipAndGroundCommandToOnlineSameOutfitMember() {
        val state = state()
        val old = state.players.getValue("ally")
        state.players = state.players + ("ally" to old.copy(cpPaused = false, motion = null, commandRange = null))
        val ground = ActiveGroundUnits(listOf(ActiveGroundUnit(3, UnitStack(0), base = 1000, origin = 1)))
        val outfit = state.stocks.outfits.getValue(1).let {
            it.copy(aideId = 10001, activeGround = ground, units = UnitInventory(it.units.ships, ground.quantities()))
        }
        val explicit = mapOf(2 to 10000)
        state.stocks = state.stocks.copy(outfits = state.stocks.outfits + (1 to outfit),
            tacticalCommands = TacticalCommandState(authority = explicit),
            strategic = state.stocks.strategic.copy(people = mapOf(10000 to StrategicPerson(rank = 1),
                10001 to StrategicPerson(rank = 10))))
        val offlineCommander = session.copy(participants = session.participants +
            ("pilot" to session.participants.getValue("pilot").copy(online = false, connection = null)))
        val world = run(state)
        val request = NavigationRequest("ally", 10001, Power.EMPIRE, "two", 1, captainCard = 59, shipKind = 0)
        assertTrue(assertNotNull(FleetNavigation.apply(request, offlineCommander, galaxy, world.navigation,
            world.production, world.production.snapshot(1)!!, state.commit) { false }.player).cpPaused)
        assertEquals(mapOf(1 to 10001, 3 to 10001), state.stocks.tacticalCommands.entryAuthority)
        assertEquals(explicit, state.stocks.tacticalCommands.authority)
        assertEquals(10001, TacticalAuthority.commander(outfit, outfit.activeShips!!.values.first(), state.stocks))
        assertEquals(10000, TacticalAuthority.commander(outfit, outfit.activeShips.values.last(), state.stocks))
        assertEquals(10001, GroundCommandAuthority.commander(outfit, ground.values.single(), state.stocks, galaxy))
        val explicitGround = state.stocks.copy(tacticalCommands = state.stocks.tacticalCommands.copy(
            authority = explicit + (3 to 10000)))
        assertEquals(10000, GroundCommandAuthority.commander(outfit, ground.values.single(), explicitGround, galaxy))

        val sameField = state.players
        assertSame(state.stocks, TacticalEntryAssignment.settle(session, galaxy, sameField, sameField,
            state.stocks) { error("Ranks must not be read again without an entry") })
        val left = sameField.getValue("ally").copy(cpPaused = false, charge = null, motion = null, commandRange = null)
        val departed = TacticalEntryAssignment.settle(session, galaxy, sameField, sameField + ("ally" to left),
            state.stocks) { emptyList() }
        assertTrue(departed.tacticalCommands.entryAuthority.isEmpty())
        assertEquals(explicit, departed.tacticalCommands.authority)
    }

    @Test fun tacticalEntryAssignsEveryUnitInAWorldWithMoreThanOneThousandUnits() {
        val state = state()
        val count = 17
        val size = 60
        val pilot = state.players.getValue("pilot")
        val outfits = (1..count).associateWith { fleet ->
            val ships = ActiveShipUnits((1..size).map { slot -> ActiveShipUnit((fleet - 1) * size + slot, UnitStack(64)) })
            OutfitStock(fleet, 2, OutfitKind.FLEET, 1000, fleet, pilot.characterId + fleet,
                UnitInventory(ships.quantities()), number = fleet, activeShips = ships, position = OutfitPosition(pilot.grid))
        }
        val warehouses = outfits.mapValues { (id, _) -> OutfitWarehouse(id, 2, OutfitKind.FLEET, 1000, id) }
        val stocks = state.stocks.copy(outfits = outfits, warehouses = warehouses, nextShipId = count * size + 1)
        val entering = outfits.map { (fleet, outfit) ->
            "pilot-$fleet" to pilot.copy(characterId = outfit.commanderId)
        }.toMap()
        val assigned = TacticalEntryAssignment.settle(session, galaxy, emptyMap(), entering, stocks) { emptyList() }
        val expected = outfits.values.flatMap { outfit ->
            outfit.activeShips!!.values.map { it.id to outfit.commanderId }
        }.toMap()
        assertEquals(expected, assigned.tacticalCommands.entryAuthority)
    }

    @Test fun commandReassignmentRequiresSameOutfitAtAcceptanceAndCompletion() {
        for (opcode in listOf(0x40b,0x420)) {
            val s=state()
            s.players=s.players+("pilot" to s.players.getValue("pilot").copy(motion=motion(50f)))
            val order=TacticalCommand(opcode,listOf(1),target=1010001)
            assertEquals(NavigationRejection.CARD,accept(run(s),order,"ally").rejection)
            assertTrue(s.stocks.tacticalCommands.orders.isEmpty())

            val outfit=s.stocks.outfits.getValue(1)
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(aideId=10001)),
                tacticalCommands=s.stocks.tacticalCommands.copy(entryAuthority=mapOf(1 to 10000)))
            val ready=assertNotNull(accept(run(s),order,"ally").commandReadyAt)
            val events=mutableListOf<TacticalCommandResult>()
            settle(run(s),ready,events)
            assertFalse(events.single().cancelled)
            assertEquals(10001,s.stocks.tacticalCommands.authority[1])
            assertNull(s.stocks.tacticalCommands.entryAuthority[1])

            val nextOrder=order.copy(units=listOf(2))
            val nextReady=assertNotNull(accept(run(s),nextOrder,"ally").commandReadyAt)
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit))
            events.clear()
            settle(run(s),nextReady,events)
            assertTrue(events.single().cancelled)
            assertNull(s.stocks.tacticalCommands.authority[2])
        }
    }

    @Test fun commandReassignmentWaitsForTheSelectedCarriersGroundOperation() {
        for ((opcode, action) in listOf(0x40b to TacticalGroundAction.DEPLOY, 0x420 to TacticalGroundAction.WITHDRAW)) {
            val state = state()
            val outfit = state.stocks.outfits.getValue(1)
            val ships = ActiveShipUnits(outfit.activeShips!!.values.map {
                if (it.id == 1) it.copy(stack = UnitStack(32),
                    posture = TacticalPosture(2488, TacticalShipMode.ANCHORED, 1000)) else it
            })
            val troop = if (action == TacticalGroundAction.DEPLOY) ActiveGroundUnit(3, UnitStack(0), carrier = 1)
                else ActiveGroundUnit(3, UnitStack(0), base = 1000, origin = 1)
            val troops = ActiveGroundUnits(listOf(troop))
            state.stocks = state.stocks.copy(outfits = state.stocks.outfits + (1 to outfit.copy(aideId = 10001,
                units = UnitInventory(ships.quantities(), troops.quantities()), activeShips = ships, activeGround = troops)))
            val initial = run(state)
            val transfer = TacticalCommand(opcode, listOf(1), target = 1010001)
            val ground = request(transfer).copy(tacticalCommand = null,
                tacticalGround = TacticalGroundCommand(action, listOf(1)))
            val groundReady = assertNotNull(TacticalGroundOperations.accept(ground, session, galaxy, initial.navigation,
                initial.production, assertNotNull(initial.production.snapshot(1)), state.commit, 1).groundReadyAt)
            state.players = state.players + ("pilot" to state.players.getValue("pilot").copy(motion = motion(50f)))
            val pending = run(state)
            val before = state.stocks
            assertEquals(NavigationRejection.BUSY, accept(pending, transfer, "ally").rejection)
            assertEquals(before, state.stocks)

            // A save from before the guard could contain both reservations.
            val oldTransfer = TacticalQueuedCommand(10001, 2488, now, now + 5, transfer)
            state.stocks = before.copy(tacticalCommands = before.tacticalCommands.copy(orders = listOf(oldTransfer)))
            val events = mutableListOf<TacticalCommandResult>()
            settle(run(state), oldTransfer.readyAt, events)
            assertTrue(events.single().cancelled)
            assertNull(state.stocks.tacticalCommands.authority[1])
            assertEquals(groundReady, state.stocks.outfits.getValue(1).activeGround!!.values.single().order?.readyAt)

            val restored = run(state)
            settle(restored, groundReady)
            val finished = state.stocks.outfits.getValue(1).activeGround!!.values.single()
            assertNull(finished.order)
            assertEquals(if (action == TacticalGroundAction.DEPLOY) 0 else 1, finished.carrier)
            val result = TacticalCommandOperations.accept(request(transfer, "ally"),
                session.copy(gameMillis = groundReady * 1000), galaxy, restored.navigation, restored.production,
                assertNotNull(restored.production.snapshot(1)), state.commit, 1)
            val transferReady = assertNotNull(result.commandReadyAt)
            events.clear()
            settle(restored, transferReady, events)
            assertFalse(events.single().cancelled)
            assertEquals(10001, state.stocks.tacticalCommands.authority[1])
        }
    }

    @Test fun explicitAntiAirWaitsAcrossReloadDispersesTheWingAndRestoresSpeedWithoutHittingItsCarrier() {
        val s=state();val enemy=s.stocks.outfits.getValue(2)
        val carrier=ProductionUnits.definitions.first {it.ship && it.power==3 && it.name.startsWith("戦闘艇母艦")}.kind
        val target=enemy.activeShips!!.values.single().copy(stack=UnitStack(carrier))
        val own=s.stocks.outfits.getValue(1)
        val slowed=own.activeShips!!.values.map {unit->if(unit.id!=1)unit else {
            val state=checkNotNull(unit.tactical)
            unit.copy(tactical=state.copy(motion=state.motion.copy(velocity=.25f,waypoints=listOf(TacticalPoint(0f,10f)),waitSeconds=0)))
        }}
        s.players=s.players+("enemy" to Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,
            hull=FlagshipHull(89,now),motion=motion(10f)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(activeShips=ActiveShipUnits(slowed)))+
            (2 to enemy.copy(commanderId=10002,activeShips=ActiveShipUnits(listOf(target)),units=UnitInventory(mapOf(UnitStack(carrier) to 1)))),
            tacticalCommands=TacticalCommandState(air=mapOf(99 to TacticalAirWing(10002,2488,99,1,1,now+100,.5f))))
        val r=run(s);val before=s.stocks.outfits.getValue(2).activeShips!!.values.single()
        assertNotNull(accept(r,TacticalCommand(0x406,listOf(1010000),kind=3,targetKind=0x81,target=99)).player)
        assertEquals(NavigationRejection.BUSY,TacticalCommandOperations.guard(request(TacticalCommand(0x409,listOf(1010000))),
            session,r.navigation,r.production.snapshot(1)!!)?.rejection)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+4,impacts=hits)
        assertTrue(99 in s.stocks.tacticalCommands.air)
        val restored=run(s);settle(restored,now+5,impacts=hits)
        assertTrue(s.stocks.tacticalCommands.air.isEmpty());assertTrue(hits.isEmpty())
        assertEquals(before,s.stocks.outfits.getValue(2).activeShips!!.values.single())
        assertEquals(.5f,s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.motion.velocity)
        assertEquals(10000,s.players.getValue("pilot").supplies);assertEquals(100,s.players.getValue("pilot").pcp)
        assertNull(s.players.getValue("pilot").fire)
        assertEquals(NavigationRejection.TARGET,accept(restored,TacticalCommand(0x406,listOf(1010000),kind=3,targetKind=0x81,target=99)).rejection)
    }
    @Test fun aaOnlyAssaultFlagshipAndRepairShipDisperseAircraftButCannotIssueOrdinaryMissiles() {
        for(personal in listOf(true,false)) {
            val s=state();val enemy=s.stocks.outfits.getValue(2)
            val carrier=ProductionUnits.find(3,"戦闘艇母艦Ⅰ").kind
            val target=enemy.activeShips!!.values.single().copy(stack=UnitStack(carrier))
            val source=if(personal)1010000 else 2
            val sourceKind=if(personal)4 else 72
            val spec=UnitSpecifications.ships.single {it.kind==sourceKind}
            assertTrue(spec.number("対空兵装破壊力:対空兵装破壊力")>0)
            assertEquals(0,TacticalArmaments.mount(spec,TacticalWeapon.MISSILE).power)
            s.players=s.players+("pilot" to s.players.getValue("pilot").copy(hull=FlagshipHull(4,now)))+
                ("enemy" to Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,
                    hull=FlagshipHull(89,now),motion=motion(10f)))
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(commanderId=10002,activeShips=ActiveShipUnits(listOf(target)),
                units=UnitInventory(mapOf(UnitStack(carrier) to 1)))),tacticalCommands=TacticalCommandState(
                air=mapOf(99 to TacticalAirWing(10002,2488,99,source,1,now+100))))
            val r=run(s)
            val missile=request(TacticalCommand(0x406,listOf(source),kind=3,targetKind=0x81,target=99))
                .copy(tacticalCommand=null,tacticalShoot=TacticalShoot(listOf(source),2,1,99),shipKind=4)
            val denied=if(personal)TacticalCombat.accept(missile,session,galaxy,r.navigation,r.production.snapshot(1)!!,s.commit,1)
                else TacticalFleetShoot.accept(missile,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1)
            assertEquals(NavigationRejection.WEAPON,denied.rejection)
            val command=missile.copy(tacticalShoot=null,tacticalCommand=TacticalCommand(0x406,listOf(source),kind=3,targetKind=0x81,target=99))
            assertNotNull(TacticalCommandOperations.accept(command,session,galaxy,r.navigation,r.production,
                r.production.snapshot(1)!!,s.commit,1).player)
            val targetBefore=s.stocks.outfits.getValue(2).activeShips!!.values.single()
            val suppliesBefore=s.stocks.outfits.getValue(1).supplies
            val restored=run(s);val hits=settle(restored,now+5)
            assertTrue(hits.isEmpty());assertTrue(s.stocks.tacticalCommands.air.isEmpty())
            assertEquals(targetBefore,s.stocks.outfits.getValue(2).activeShips!!.values.single())
            assertEquals(suppliesBefore,s.stocks.outfits.getValue(1).supplies)
            assertEquals(10000,s.players.getValue("pilot").supplies);assertNull(s.players.getValue("pilot").fire)
            assertTrue(s.stocks.outfits.values.flatMap {it.activeShips!!.values}.all {it.shot==null})
        }
    }
    @Test fun antiAirRechecksSharedContactBeforeDispersingACarrierWing() {
        for (keepContact in listOf(false,true)) {
            checkAntiAirSharedContact(keepContact)
        }
    }

    private fun checkAntiAirSharedContact(keepContact:Boolean) {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases.map {it.copy(radius=25f)},galaxy.starts)
        val s=state()
        s.players=s.players.mapValues {(_,p)->if(p.characterId==10000)
            p.copy(hull=FlagshipHull(4,now),motion=motion(0f,-21f)) else p}+
            ("enemy" to Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,
                hull=FlagshipHull(89,now),motion=motion(10f)))
        val friendly=s.stocks.outfits.getValue(1)
        val ships=friendly.activeShips!!.values.map {unit->
            unit.copy(tactical=unit.tactical!!.copy(motion=motion(1f,-21f)))
        }
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to friendly.copy(activeShips=ActiveShipUnits(ships))))
        val wing=TacticalAirWing(10002,2488,99,0,0,now+100)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to s.stocks.outfits.getValue(2).copy(commanderId=10002)),
            tacticalCommands=TacticalCommandState(air=mapOf(99 to wing)))
        val navigation=Navigation(world,s.nav,NavigationPolicy(),clockScale=1)
        val production=PlanetProduction(world,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        fun moveShip(outfitId:Int,unitId:Int,x:Float,y:Float) {
            val old=production.snapshot(1)!!
            val outfit=old.outfits.getValue(outfitId)
            val units=outfit.activeShips!!.values.map {unit->
                if(unit.id==unitId)unit.copy(tactical=unit.tactical!!.copy(motion=motion(x,y))) else unit
            }
            production.replace(old,old.copy(outfits=old.outfits+(outfitId to outfit.copy(activeShips=ActiveShipUnits(units)))))
        }
        val command=request(TacticalCommand(0x406,listOf(1010000),kind=3,targetKind=0x81,target=99)).copy(shipKind=4)
        fun accept()=TacticalCommandOperations.accept(command,session,world,navigation,production,production.snapshot(1)!!,s.commit,1)
        moveShip(2,99,0f,-19f)
        assertEquals(NavigationRejection.RANGE,accept().rejection)
        assertTrue(production.snapshot(1)!!.tacticalCommands.orders.isEmpty())
        moveShip(1,1,1f,-19f)
        assertNotNull(accept().player)
        if(!keepContact)moveShip(1,1,1f,-21f)
        val impacts=TacticalCombat.settle(session.copy(gameMillis=(now+5)*1000),world,navigation,production,s.commit,scale=1)
        assertTrue(impacts.isEmpty())
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        assertEquals(if(keepContact)null else wing,s.stocks.tacticalCommands.air[99])
    }

    @Test fun antiAirUsesAnExclusiveEightUnitRadiusEvenForAnAaOnlyFlagship() {
        fun fixture(distance:Float):Run {
            val s=state();s.players=s.players+("pilot" to s.players.getValue("pilot").copy(hull=FlagshipHull(4,now)))
            val enemy=s.stocks.outfits.getValue(2);val target=enemy.activeShips!!.values.single()
            val moved=target.copy(tactical=target.tactical!!.copy(motion=motion(0f,distance)))
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(activeShips=ActiveShipUnits(listOf(moved)))),
                tacticalCommands=TacticalCommandState(air=mapOf(99 to TacticalAirWing(10002,2488,99,1010000,1,now+100))))
            return run(s)
        }
        val command=request(TacticalCommand(0x406,listOf(1010000),kind=3,targetKind=0x81,target=99)).copy(shipKind=4)
        val boundary=fixture(8f)
        assertEquals(NavigationRejection.RANGE,TacticalCommandOperations.accept(command,session,galaxy,boundary.navigation,
            boundary.production,boundary.production.snapshot(1)!!,boundary.state.commit,1).rejection)
        assertTrue(boundary.state.stocks.tacticalCommands.orders.isEmpty())
        val inside=fixture(7.999f)
        assertNotNull(TacticalCommandOperations.accept(command,session,galaxy,inside.navigation,inside.production,
            inside.production.snapshot(1)!!,inside.state.commit,1).player)
    }
    @Test fun formationChangeCannotMoveAFollowerIntoTheStar() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases,galaxy.starts)
        val s=state()
        s.players=s.players.mapValues {(_,p)->p.copy(motion=motion(12.5f))}
        fun place(stocks:PlanetStocks,leaderX:Float):PlanetStocks {
            val fleet=stocks.outfits.getValue(1)
            val ships=fleet.activeShips!!.values.map {u->
                u.copy(tactical=u.tactical!!.copy(motion=motion(if(u.id==1)leaderX else 12.5f)))
            }
            return stocks.copy(outfits=stocks.outfits+(1 to fleet.copy(activeShips=ActiveShipUnits(ships))))
        }
        s.stocks=place(s.stocks,10.5f)
        val nav=Navigation(world,s.nav,NavigationPolicy(),clockScale=1)
        val production=PlanetProduction(world,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        fun accept()=TacticalCommandOperations.accept(request(TacticalCommand(0x40d,listOf(1,2),kind=0)),
            session,world,nav,production,production.snapshot(1)!!,s.commit,1)
        assertEquals(NavigationRejection.RANGE,accept().rejection)
        val rejected=production.snapshot(1)!!
        production.replace(rejected,place(rejected,12.5f))
        assertNotNull(accept().player)
        val queued=production.snapshot(1)!!
        production.replace(queued,place(queued,10.5f))
        TacticalCombat.settle(session.copy(gameMillis=(now+5)*1000),world,nav,production,s.commit,scale=1)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        assertTrue(s.stocks.tacticalCommands.formationLeaders.isEmpty())
        assertTrue(s.stocks.tacticalCommands.formations.isEmpty())
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.all {it.tactical!!.motion.velocity==0f})
    }

    @Test fun formationWaitsSavesAndMovesRealUnitsAfterReloadWithoutRepeatedOrders() {
        val r=run();assertNotNull(accept(r,TacticalCommand(0x40d,listOf(1,2),kind=6)).player)
        assertEquals(0f,r.state.stocks.outfits.getValue(1).activeShips!!.values[0].tactical!!.motion.origin.position.x)
        val events=mutableListOf<TacticalCommandResult>();settle(r,now+4,events);assertTrue(events.isEmpty())
        val restored=run(r.state);settle(restored,now+5,events)
        assertEquals(1,events.size);assertFalse(events.single().cancelled)
        assertEquals(6,r.state.stocks.tacticalCommands.formations[10000]);assertTrue(r.state.stocks.tacticalCommands.orders.isEmpty())
        assertEquals(2,events.single().motions.size)
        assertNotEquals(TacticalPoint(0f,.8f),r.state.stocks.outfits.getValue(1).activeShips!!.values[1].tactical!!.motion.waypoints.single())
        settle(restored,now+20,events);assertEquals(1,events.size)
    }

    @Test fun formationMovementKeepsAppliedAirSlowdownAndRestoresItsNewNormalSpeed() {
        for((mode,normalSpeed) in listOf(1 to .8f,1 to null,0 to null)) {
            val s=state()
            val enemy=Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,
                hull=FlagshipHull(89,now),motion=motion(1f,1f))
            s.players=s.players+("enemy" to enemy)
            val enemyFleet=s.stocks.outfits.getValue(2)
            val wing=TacticalAirWing(enemy.characterId,2488,99,2,mode,now+100,normalSpeed)
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemyFleet.copy(commanderId=enemy.characterId)),
                tacticalCommands=s.stocks.tacticalCommands.copy(air=mapOf(99 to wing)))
            val ready=assertNotNull(accept(run(s),TacticalCommand(0x40d,listOf(1,2),kind=6)).commandReadyAt)
            val events=mutableListOf<TacticalCommandResult>()
            settle(run(s),ready,events)
            val speed=if(normalSpeed==null).5f else .25f
            assertEquals(speed,events.single().motions.getValue(2).velocity)
            assertEquals(speed,s.stocks.outfits.getValue(1).activeShips!!.values[1].tactical!!.motion.velocity)
            assertEquals(normalSpeed?.let {.5f},s.stocks.tacticalCommands.air.getValue(99).slowVelocity)
            if(normalSpeed!=null) {
                s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemyFleet))
                settle(run(s),ready+1)
                assertTrue(s.stocks.tacticalCommands.air.isEmpty())
                assertEquals(.5f,s.stocks.outfits.getValue(1).activeShips!!.values[1].tactical!!.motion.velocity)
            }
        }
    }
    @Test fun cdAirButtonInterceptAtAHostileUnitBecomesTheAntiShipLaunch() {
        // CD air button sends kind 0 only; a hostile target means the anti-ship attack.
        val s=state();val r=run(s);assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=0,target=99)).player)
        assertEquals(1,s.stocks.tacticalCommands.orders.single().command.kind)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits)
        assertEquals(90,s.stocks.outfits.getValue(1).supplies);assertTrue(hits.single().airBattle)
    }
    @Test fun antiShipLaunchRejectsOrCancelsAnEliminatedTargetWhileOtherEnemiesRemain() {
        val enemy = Power.ALLIANCE.country
        val third = CountryId(42)
        val ongoing = session.copy(countries = setOf(Power.EMPIRE.country, enemy, third))
        val command = request(TacticalCommand(0x40e, listOf(1), kind = 0, target = 99))
        fun defeated(at: Long) = ongoing.copy(gameMillis = at * 1000,
            eliminated = mapOf(enemy to CountryDefeat(EndReason.SYSTEMS_REDUCED, at)))

        for (phase in listOf("admission", "launch", "deployed")) {
            val s = state()
            s.players = s.players + ("third" to s.players.getValue("ally").copy(
                characterId = 10002, country = third, motion = motion(200f),
            ))
            val targetOutfit = s.stocks.outfits.getValue(2)
            val target = targetOutfit.activeShips!!.values.single()
            val moving = target.copy(tactical = target.tactical!!.copy(
                motion = motion(0f, 1.5f).copy(velocity = .5f, waypoints = listOf(TacticalPoint(0f, 40f))),
            ))
            s.stocks = s.stocks.copy(outfits = s.stocks.outfits +
                (targetOutfit.id to targetOutfit.copy(activeShips = ActiveShipUnits(listOf(moving)))))
            val r = run(s)
            val admitted = TacticalCommandOperations.accept(command, if (phase == "admission") defeated(now) else ongoing,
                galaxy, r.navigation, r.production, r.production.snapshot(1)!!, s.commit, 1)
            if (phase == "admission") {
                assertEquals(NavigationRejection.TARGET, admitted.rejection)
                assertEquals(100, s.stocks.outfits.getValue(1).supplies)
                assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
                continue
            }
            assertNotNull(admitted.player, phase)
            val at = if (phase == "deployed") {
                val hits = TacticalCombat.settle(ongoing.copy(gameMillis = (now + 5) * 1000), galaxy,
                    r.navigation, r.production, s.commit, scale = 1)
                assertTrue(hits.any { it.airBattle })
                assertEquals(.25f, s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
                s.stocks.tacticalCommands.air.getValue(1).nextAt
            } else now + 5
            val before = s.stocks.outfits.getValue(2).activeShips!!.values.single()
            val restored = run(s)
            val events = mutableListOf<TacticalCommandResult>()
            val hits = TacticalCombat.settle(defeated(at), galaxy, restored.navigation, restored.production,
                s.commit, scale = 1, onCommand = events::add)
            assertTrue(hits.none { it.airBattle }, phase)
            assertTrue(s.stocks.tacticalCommands.air.isEmpty(), phase)
            assertTrue(s.stocks.tacticalCommands.orders.isEmpty(), phase)
            assertEquals(if (phase == "deployed") 90 else 100, s.stocks.outfits.getValue(1).supplies, phase)
            val after = s.stocks.outfits.getValue(2).activeShips!!.values.single()
            assertEquals(before.boats, after.boats, phase)
            assertEquals(before.damaged, after.damaged, phase)
            // Defeat stops movement independently of the wing; removing it must not restart the route.
            assertEquals(0f, after.tactical!!.motion.velocity, phase)
            if (phase == "launch") assertTrue(events.single().cancelled)
            else assertTrue(events.none { it.motions[99]?.velocity?.let { speed -> speed > 0f } == true })
        }
    }

    @Test fun antiShipLaunchRequiresContactThroughTheEndOfTheLaunchDelay() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases.map {it.copy(radius=25f)},galaxy.starts)
        for(keepContact in listOf(false,true)) {
            val s=state()
            s.players=s.players.mapValues {(_,p)->p.copy(motion=motion(0f,-21f))}
            fun place(outfit:OutfitStock,id:Int,y:Float)=outfit.copy(activeShips=ActiveShipUnits(
                outfit.activeShips!!.values.map {u->if(u.id==id)u.copy(tactical=u.tactical!!.copy(motion=motion(0f,y))) else u}))
            val friendly=place(place(s.stocks.outfits.getValue(1),1,-21f),2,-21f)
            val hostile=place(s.stocks.outfits.getValue(2),99,-19f)
            s.stocks=s.stocks.copy(outfits=mapOf(1 to friendly,2 to hostile))
            val nav=Navigation(world,s.nav,NavigationPolicy(),clockScale=1)
            val production=PlanetProduction(world,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
            val command=request(TacticalCommand(0x40e,listOf(1),kind=0,target=99))
            fun accept()=TacticalCommandOperations.accept(command,session,world,nav,production,production.snapshot(1)!!,s.commit,1)
            fun scoutAt(y:Float) {
                val old=production.snapshot(1)!!
                production.replace(old,old.copy(outfits=old.outfits+(1 to place(old.outfits.getValue(1),2,y))))
            }
            assertEquals(NavigationRejection.RANGE,accept().rejection)
            scoutAt(-18f)
            assertNotNull(accept().player)
            if(!keepContact)scoutAt(-21f)
            val hits=TacticalCombat.settle(session.copy(gameMillis=(now+5)*1000),world,nav,production,s.commit,scale=1)
            assertEquals(keepContact,1 in s.stocks.tacticalCommands.air)
            assertEquals(if(keepContact)90 else 100,s.stocks.outfits.getValue(1).supplies)
            assertEquals(keepContact,hits.any {it.airBattle})
        }
    }

    @Test fun deployedWingStopsAfterCommanderDeathOrCampSplitAndRestoresTargetSpeedOnReload() {
        for(death in listOf(true,false)) {
            val s=state();val foe=s.stocks.outfits.getValue(2)
            val target=foe.activeShips!!.values.single()
            val moving=target.copy(tactical=target.tactical!!.copy(
                motion=motion(0f,1.5f).copy(velocity=.5f,waypoints=listOf(TacticalPoint(0f,40f)))))
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to foe.copy(activeShips=ActiveShipUnits(listOf(moving)))))
            val r=run(s)
            assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
            settle(r,now+5)
            val wing=s.stocks.tacticalCommands.air.getValue(1)
            assertEquals(.25f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)

            if(death)s.stocks=BattleDeath.apply(s.stocks,listOf(10000),now+5) {_,_->true}
            else {
                val coup=CoupState(mapOf(10000 to Plot(10000,Power.EMPIRE.country,0,revolt=5)))
                val outfit=s.stocks.outfits.getValue(1)
                val roster=outfit.activeShips!!.values
                val carriers=ActiveShipUnits(roster.map {if(it.id==2)it.copy(stack=roster.first().stack) else it})
                val approaching=wing.copy(source=2,nextAt=wing.nextAt+5,slowVelocity=null)
                val air=s.stocks.tacticalCommands.air+(2 to approaching)
                s.stocks=s.stocks.copy(social=s.stocks.social.copy(coup=coup),
                    outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=carriers,units=UnitInventory(carriers.quantities()))),
                    tacticalCommands=s.stocks.tacticalCommands.copy(air=air))
            }
            val hits=mutableListOf<TacticalImpact>()
            settle(run(s),wing.nextAt,impacts=hits)
            assertTrue(hits.none {it.airBattle})
            assertTrue(s.stocks.tacticalCommands.air.isEmpty())
            assertEquals(.5f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
        }
    }

    @Test fun deployedWingStopsWhenItsCommanderCanNoLongerActInBattle() {
        for(reason in listOf("eliminated","wounded","docked")) {
            val s=state()
            val r=run(s)
            assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
            settle(r,now+5)
            val wing=s.stocks.tacticalCommands.air.getValue(1)
            val pilot=s.players.getValue("pilot")
            val due=session.copy(gameMillis=wing.nextAt*1000)
            val current=when(reason) {
                "eliminated" -> due.copy(eliminated=mapOf(Power.EMPIRE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now+5)))
                "wounded" -> {
                    s.players=s.players+("pilot" to pilot.copy(hull=pilot.hull!!.copy(woundedUntil=wing.nextAt+1)))
                    due
                }
                else -> {
                    s.players=s.players+("pilot" to pilot.copy(baseId=1000,mode=4,cpPaused=false,motion=null,commandRange=null))
                    due
                }
            }
            val restored=run(s)
            val hits=TacticalCombat.settle(current,galaxy,restored.navigation,restored.production,s.commit,scale=1)
            assertTrue(hits.none {it.airBattle},reason)
            assertTrue(s.stocks.tacticalCommands.air.isEmpty(),reason)
            assertEquals(90,s.stocks.outfits.getValue(1).supplies,reason)
        }
    }

    @Test fun wingStopsSlowingATargetThatBecomesFriendlyBeforeTheNextAttack() {
        val s=state()
        val foe=s.stocks.outfits.getValue(2)
        val target=foe.activeShips!!.values.single()
        val moving=target.copy(tactical=target.tactical!!.copy(
            motion=motion(0f,1.5f).copy(velocity=.5f,waypoints=listOf(TacticalPoint(0f,40f)))))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to foe.copy(activeShips=ActiveShipUnits(listOf(moving)))))
        val r=run(s)
        assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
        val events=mutableListOf<TacticalCommandResult>()
        settle(r,now+5,events)
        assertEquals(.25f,events.single {it.stateOnly}.motions.getValue(99).velocity)
        val next=s.stocks.tacticalCommands.air.getValue(1).nextAt
        assertEquals(.25f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
        s.players=s.players+("enemy" to Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,
            hull=FlagshipHull(89,now),motion=motion(100f)))
        val allied=s.stocks.outfits.getValue(2).copy(country=Power.EMPIRE.country)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to allied),
            warehouses=s.stocks.warehouses+(20 to s.stocks.warehouses.getValue(20).copy(country=Power.EMPIRE.country)))
        events.clear()
        val hits=settle(run(s),next-1,events)
        val restored=events.single {it.stateOnly}
        assertEquals(.5f,restored.motions.getValue(99).velocity)
        assertEquals(mapOf(10000 to "one",10001 to "two"),restored.recipients)
        assertTrue(hits.none {it.airBattle})
        assertTrue(s.stocks.tacticalCommands.air.isEmpty())
        assertEquals(.5f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
    }

    @Test fun removingAWingDoesNotRestartACompletedRouteAfterTheTargetLosesItsCommander() {
        val s=state()
        val fleet=s.stocks.outfits.getValue(2)
        val target=fleet.activeShips!!.values.single()
        val ended=motion(0f,1.5f).copy(velocity=.25f,waypoints=listOf(TacticalPoint(0f,2f)),waitSeconds=0)
        val moving=target.copy(tactical=target.tactical!!.copy(motion=ended),motionCommander=10002)
        val wing=TacticalAirWing(10000,2488,1,99,1,now+100,.5f)
        val coup=CoupState(mapOf(10000 to Plot(10000,Power.EMPIRE.country,0,revolt=5)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to fleet.copy(activeShips=ActiveShipUnits(listOf(moving)))),
            social=s.stocks.social.copy(coup=coup),tacticalCommands=s.stocks.tacticalCommands.copy(air=mapOf(1 to wing)))
        val run = run(s)
        val events = mutableListOf<TacticalCommandResult>()
        val movements = mutableListOf<TacticalMovement>()
        val tick = session.copy(gameMillis = (now + 5) * 1000)
        TacticalCombat.settle(tick, galaxy, run.navigation, run.production, s.commit, scale = 1,
            onMovement = movements::add, onCommand = events::add)
        val stopped=s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion
        assertEquals(0f,stopped.velocity)
        assertEquals(TacticalPoint(0f,2f),stopped.origin.position)
        assertTrue(s.stocks.tacticalCommands.air.isEmpty())
        val stop = movements.single { it.unit == 99 }
        assertEquals(-1, stop.waypoint)
        assertEquals(stopped, stop.motion)
        assertTrue(events.none { 99 in it.motions })
    }

    @Test fun changingFlagshipEnginePowerKeepsTheSlowdownAndCapsTheRestoredSpeed() {
        val s=state()
        val pilot=s.players.getValue("pilot")
        val motion=motion().copy(velocity=.5f,waypoints=listOf(TacticalPoint(0f,40f)),waitSeconds=0)
        val energy=TacticalEnergy(listOf(100)+List(10){0})
        s.players=s.players+("pilot" to pilot.copy(energy=energy,motion=motion))
        val wing=TacticalAirWing(10002,2488,99,pilot.unitId,1,now+100,1f)
        s.stocks=s.stocks.copy(tacticalCommands=s.stocks.tacticalCommands.copy(air=mapOf(99 to wing)))
        val r=run(s)
        val request=NavigationRequest("pilot",10000,Power.EMPIRE,"one",1,captainCard=59,shipKind=0,
            unitId=pilot.unitId,energy=TacticalEnergy(listOf(40)+List(10){0}))
        val reduced=r.navigation.apply(request,session,r.production.snapshot(1)!!)
        assertEquals(.2f,reduced.player?.motion?.velocity)
        val events=mutableListOf<TacticalCommandResult>()
        settle(run(s),now+1,events)
        assertEquals(.4f,s.players.getValue("pilot").motion?.velocity)
        assertEquals(.4f,events.single {it.stateOnly}.motions.getValue(pilot.unitId).velocity)
    }

    @Test fun carrierLaunchSpendsExactlyTenAndLandingRestoresTargetSpeed() {
        val s=state();val foe=s.stocks.outfits.getValue(2)
        val moving=foe.activeShips!!.values.single().let {it.copy(tactical=it.tactical!!.copy(motion=motion(0f,1.5f).copy(velocity=.5f,waitSeconds=60,waypoints=listOf(TacticalPoint(0f,40f)))))}
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to foe.copy(activeShips=ActiveShipUnits(listOf(moving)))))
        val r=run(s);assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
        val hits=mutableListOf<TacticalImpact>()
        val events=mutableListOf<TacticalCommandResult>()
        settle(r,now+5,events,hits)
        assertEquals(90,s.stocks.outfits.getValue(1).supplies);assertTrue(hits.single().airBattle)
        val slowed=s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion
        assertEquals(.25f,slowed.velocity)
        assertEquals(now+60,slowed.startsAt)
        assertEquals(slowed,events.single {it.stateOnly}.motions.getValue(99))
        // CRC regrowth is required for another squadron command.
        val later=session.copy(gameMillis=(now+20)*1000)
        assertNotNull(TacticalCommandOperations.accept(request(TacticalCommand(0x40e,listOf(1),kind=2)),later,galaxy,
            r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        events.clear()
        settle(r,now+25,events)
        assertTrue(s.stocks.tacticalCommands.air.isEmpty());assertEquals(90,s.stocks.outfits.getValue(1).supplies)
        val restored=s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion
        assertEquals(.5f,restored.velocity)
        assertEquals(now+60,restored.startsAt)
        assertEquals(moving.tactical!!.motion.origin,restored.pose(now+59))
        assertEquals(restored,events.single {it.stateOnly}.motions.getValue(99))
    }
    @Test fun airTacticsOfTheLaunchingCharacterShortensTheWingCycle() {
        fun nextStrike(airTactics:Int):Long {
            val s=state()
            if(airTactics>0)s.stocks=s.stocks.copy(strategic=s.stocks.strategic.copy(people=mapOf(10000 to StrategicPerson(rank=10,airTactics=airTactics))))
            val r=run(s);assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
            val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits);assertTrue(hits.single().airBattle)
            return checkNotNull(s.stocks.tacticalCommands.air[1]).nextAt-(now+5)
        }
        assertEquals(5,nextStrike(0));assertEquals(3,nextStrike(3))
    }
    @Test fun invalidAuthorityInsufficientCpAndDiskFailureNeverPublishSuccess() {
        val r=run();val c=TacticalCommand(0x40d,listOf(1,2),kind=1)
        assertEquals(NavigationRejection.CARD,accept(r,c,"ally").rejection)
        assertEquals(NavigationRejection.UNIT,accept(r,TacticalCommand(0x40d)).rejection)
        val before=r.state.stocks;val players=r.navigation.snapshot(1)
        r.state.fail=true;assertFailsWith<java.io.IOException> {accept(r,c)}
        assertEquals(before,r.state.stocks);assertEquals(players,r.navigation.snapshot(1));assertTrue(r.state.stocks.tacticalCommands.orders.isEmpty())
        r.state.fail=false
        val low=state();low.players=low.players.mapValues {(_,p)->p.copy(pcp=0)}
        low.stocks=low.stocks.copy(tacticalCommands=TacticalCommandState(morale=mapOf(1 to 50)))
        assertEquals(NavigationRejection.CP,accept(run(low),TacticalCommand(0x409,listOf(1010000))).rejection)
    }
    @Test fun fortressCommandsRequireTheActualFortressCommanderRatherThanItsGroundGarrison() {
        val cannon=TacticalCommand(0x419,base=1000,direction=0f)
        val garrison=state();garrison.stocks=garrison.stocks.copy(strategic=StrategicState())
        assertEquals(NavigationRejection.CARD,accept(run(garrison),cannon).rejection)
        val commander=state();commander.stocks=commander.stocks.copy(bases=commander.stocks.bases.mapValues {
            (_,b)->b.copy(garrisonCommanderId=0)})
        val r=run(commander)
        assertNotNull(accept(r,cannon).player)
        val lost=r.state.stocks.copy(strategic=r.state.stocks.strategic.copy(offices=emptyList()))
        val events=mutableListOf<TacticalCommandResult>();val impacts=mutableListOf<TacticalImpact>()
        val after=TacticalCommandOperations.advance(session.copy(gameMillis=(now+1)*1000),galaxy,
            r.state.players.toMutableMap(),lost,1,events,impacts)
        assertTrue(after.tacticalCommands.orders.isEmpty());assertTrue(impacts.isEmpty())
        assertTrue(events.single {it.order.command.opcode==0x419}.cancelled)
        assertEquals(lost.bases.getValue(1000).supplies,after.bases.getValue(1000).supplies)
    }
    @Test fun fortressCannonHitsBeforeTheStarButCannotHitThroughIt() {
        val s=state()
        s.players=s.players+mapOf(
            "pilot" to s.players.getValue("pilot").copy(motion=motion(2f,0f)),
            "ally" to s.players.getValue("ally").copy(motion=motion(0f,-1f)))
        s.stocks=s.stocks.copy(tacticalCommands=s.stocks.tacticalCommands.copy(
            fortresses=mapOf(1000 to TacticalFortressMotion(2488,motion(0f,-2f)))))
        val r=run(s)
        assertNotNull(accept(r,TacticalCommand(0x419,base=1000,direction=0f)).player)
        val impacts=mutableListOf<TacticalImpact>()
        settle(r,now+35,impacts=impacts)
        assertEquals(listOf(s.players.getValue("ally").unitId),impacts.filter {it.fortress}.map {it.hit.target})
    }
    @Test fun fortressRepairsAndSuppliesItsStationedShipsAcrossReloadWithoutLaunchingThem() {
        for(opcode in listOf(0x41b,0x41c))for(atBase in listOf(false,true)) {
            val s=state();val outfit=s.stocks.outfits.getValue(1)
            val roster=outfit.activeShips!!.values.map {u->if(u.id!=2)u else u.copy(damaged=1,
                posture=TacticalPosture(2488,TacticalShipMode.STATIONED,1000),tactical=null)}
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(roster),
                position=OutfitPosition(2488,if(atBase)1000 else 0))))
            val before=s.stocks;val r=run(s)
            assertNull(accept(r,TacticalCommand(opcode,listOf(2),base=1000)).rejection)
            val restored=run(s);val events=mutableListOf<TacticalCommandResult>();settle(restored,now+5,events)
            assertFalse(events.single().cancelled)
            val after=s.stocks.outfits.getValue(1);val unit=after.activeShips!!.values.single {it.id==2}
            assertEquals(TacticalShipMode.STATIONED,unit.posture!!.mode);assertEquals(1000,unit.posture.base)
            assertNull(unit.tactical);assertTrue(s.stocks.bases.getValue(1000).supplies<before.bases.getValue(1000).supplies)
            if(opcode==0x41b)assertEquals(0,unit.damaged) else {
                assertTrue(OutfitSupplies.distribution(after).getValue(2)>0)
                assertEquals(before.bases.getValue(1000).supplies+before.outfits.getValue(1).supplies,
                    s.stocks.bases.getValue(1000).supplies+after.supplies)
            }
        }
    }
    @Test fun fortressSupplyCountsCarriedTransportCargoAtAdmissionAndSettlement() {
        val s=state()
        val transport=s.stocks.outfits.getValue(1).copy(kind=OutfitKind.TRANSPORT)
        val warehouse=s.stocks.warehouses.getValue(10).copy(kind=OutfitKind.TRANSPORT)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to transport),warehouses=s.stocks.warehouses+(10 to warehouse))
        fun loadCargo(room:Int) {
            val outfit=s.stocks.outfits.getValue(1)
            val cargo=TransportPackage(1,outfit.country,1000,1001,OutfitSupplies.capacity(outfit)-outfit.supplies-room,
                carrier=1,carrierBoats=2)
            s.stocks=s.stocks.copy(strategic=s.stocks.strategic.copy(transports=listOf(cargo)))
        }
        val command=TacticalCommand(0x41c,listOf(1,2),base=1000)
        val before=s.stocks.bases.getValue(1000).supplies
        loadCargo(0)
        assertEquals(NavigationRejection.SUPPLY,accept(run(s),command).rejection)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())

        loadCargo(5)
        val ready=assertNotNull(accept(run(s),command).commandReadyAt)
        val cargo=s.stocks.strategic.transports
        val events=mutableListOf<TacticalCommandResult>()
        settle(run(s),ready,events)
        assertFalse(events.single().cancelled)
        assertEquals(before-5,s.stocks.bases.getValue(1000).supplies)
        assertEquals(transport.supplies+5,s.stocks.outfits.getValue(1).supplies)
        assertEquals(cargo,s.stocks.strategic.transports)

        loadCargo(5)
        val nextReady=assertNotNull(accept(run(s),command).commandReadyAt)
        loadCargo(0)
        events.clear()
        val loaded=s.stocks.strategic.transports
        settle(run(s),nextReady,events)
        assertTrue(events.single().cancelled)
        assertEquals(before-5,s.stocks.bases.getValue(1000).supplies)
        assertEquals(transport.supplies+5,s.stocks.outfits.getValue(1).supplies)
        assertEquals(loaded,s.stocks.strategic.transports)
    }

    @Test fun fortressCannotRepairAGuardShipFromANewOrSavedOrder() {
        val s=state()
        val option=checkNotNull(GuardShips.option(0))
        val ship=ActiveShipUnit(3,UnitStack(option.baseKind),damaged=1,
            posture=TacticalPosture(2488,TacticalShipMode.STATIONED,1000))
        val roster=ActiveShipUnits(listOf(ship))
        val guard=OutfitStock(3,2,OutfitKind.PATROL,1000,30,units=UnitInventory(roster.quantities()),
            activeShips=roster,position=OutfitPosition(2488,1000),guard=mapOf(ship.id to option.index))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(3 to guard),
            warehouses=s.stocks.warehouses+(30 to OutfitWarehouse(30,2,OutfitKind.PATROL,1000)))
        val repair=TacticalCommand(0x41b,listOf(ship.id),base=1000)
        assertEquals(NavigationRejection.TARGET,accept(run(s),repair).rejection)
        val before=s.stocks
        val pending=TacticalQueuedCommand(10000,2488,now,now+5,repair)
        s.stocks=before.copy(tacticalCommands=before.tacticalCommands.copy(orders=listOf(pending)))
        val events=mutableListOf<TacticalCommandResult>()
        settle(run(s),now+5,events)
        assertTrue(events.single().cancelled)
        assertEquals(guard,s.stocks.outfits.getValue(3))
        assertEquals(before.bases.getValue(1000).supplies,s.stocks.bases.getValue(1000).supplies)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
    }

    @Test fun fortressCannotRepairOrSupplyADeceasedFlagship() {
        for(opcode in listOf(0x41b,0x41c)) {
            val s=state()
            val ally=s.players.getValue("ally").copy(motion=motion(0f,.5f),supplies=400,hull=FlagshipHull(0,now,hp=40))
            s.players=s.players+("ally" to ally)
            val command=TacticalCommand(opcode,listOf(ally.unitId),base=1000)
            val living=s.stocks
            val dead=living.strategic.copy(people=mapOf(ally.characterId to StrategicPerson(deceasedAt=1)))
            s.stocks=living.copy(strategic=dead)
            assertEquals(NavigationRejection.TARGET,accept(run(s),command).rejection)
            s.stocks=living
            assertNotNull(accept(run(s),command).player)
            s.stocks=s.stocks.copy(strategic=dead)
            val events=mutableListOf<TacticalCommandResult>()
            settle(run(s),now+5,events)
            assertTrue(events.single().cancelled)
            val target=s.players.getValue("ally")
            assertEquals(ally.hull!!.hp,target.hull!!.hp)
            assertEquals(ally.supplies,target.supplies)
            assertEquals(living.bases.getValue(1000).supplies,s.stocks.bases.getValue(1000).supplies)
            assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        }
    }

    @Test fun fortressSupplyNeverWithdrawsCargoFromAnOverloadedSavedShip() {
        val s=state()
        val fleet=s.stocks.outfits.getValue(1)
        val source=fleet.activeShips!!.values.first().copy(boats=1)
        val cargo=OutfitSupplies.unitCapacity(source)+1
        val ships=ActiveShipUnits(listOf(source.copy(supplies=cargo)))
        val overloaded=fleet.copy(activeShips=ships,units=UnitInventory(ships.quantities()),supplies=cargo)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to overloaded))
        val before=s.stocks.bases.getValue(1000).supplies
        val r=run(s)
        assertNotNull(accept(r,TacticalCommand(0x41c,listOf(source.id),base=1000)).player)
        val events=mutableListOf<TacticalCommandResult>()
        settle(r,now+5,events)
        assertTrue(events.single().cancelled)
        assertEquals(before,s.stocks.bases.getValue(1000).supplies)
        assertEquals(overloaded,s.stocks.outfits.getValue(1))
    }

    @Test fun fortressSupplyRequiresAFlagshipEvenIfTheCharacterIsInTheBattlefield() {
        val s=state()
        val ally=s.players.getValue("ally").copy(motion=motion(0f,.5f),supplies=400)
        val noFlagship=ally.copy(hull=null)
        val command=TacticalCommand(0x41c,listOf(ally.unitId),base=1000)
        s.players=s.players+("ally" to noFlagship)
        assertEquals(NavigationRejection.TARGET,accept(run(s),command).rejection)
        s.players=s.players+("ally" to ally)
        assertNotNull(accept(run(s),command).player)
        s.players=s.players+("ally" to noFlagship)
        val before=s.stocks.bases.getValue(1000).supplies
        val events=mutableListOf<TacticalCommandResult>()
        settle(run(s),now+5,events)
        assertTrue(events.single().cancelled)
        assertEquals(400,s.players.getValue("ally").supplies)
        assertEquals(before,s.stocks.bases.getValue(1000).supplies)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
    }

    @Test fun fortressSupportDoesNotAcceptWrongBerthsEnemiesOrStationedCombatCommands() {
        for(opcode in listOf(0x41b,0x41c)) {
            val s=state();val outfit=s.stocks.outfits.getValue(1)
            val roster=outfit.activeShips!!.values.map {u->if(u.id!=2)u else u.copy(
                posture=TacticalPosture(2488,TacticalShipMode.STATIONED,1001),tactical=null)}
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(roster))))
            val r=run(s);val before=s.stocks
            assertEquals(NavigationRejection.BASE,accept(r,TacticalCommand(opcode,listOf(2),base=1000)).rejection)
            assertEquals(NavigationRejection.UNIT,accept(r,TacticalCommand(opcode,listOf(99),base=1000)).rejection)
            assertEquals(NavigationRejection.BASE,accept(r,TacticalCommand(0x40d,listOf(2),kind=0)).rejection)
            assertEquals(before,s.stocks)
        }
    }
    @Test fun fortressSupportRechecksTheBerthBeforeSpendingItsStock() {
        for(opcode in listOf(0x41b,0x41c)) {
            val s=state();val outfit=s.stocks.outfits.getValue(1)
            val roster=outfit.activeShips!!.values.map {u->if(u.id!=2)u else u.copy(damaged=1,
                posture=TacticalPosture(2488,TacticalShipMode.STATIONED,1000),tactical=null)}
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(roster))))
            val r=run(s);assertNull(accept(r,TacticalCommand(opcode,listOf(2),base=1000)).rejection)
            val changed=s.stocks.outfits.getValue(1).copy(activeShips=ActiveShipUnits(roster.map {u->
                if(u.id==2)u.copy(posture=u.posture!!.copy(base=1001)) else u}))
            val before=s.stocks.copy(outfits=s.stocks.outfits+(1 to changed))
            val events=mutableListOf<TacticalCommandResult>();val impacts=mutableListOf<TacticalImpact>()
            val after=TacticalCommandOperations.advance(session.copy(gameMillis=(now+5)*1000),galaxy,
                s.players.toMutableMap(),before,1,events,impacts)
            assertTrue(events.single().cancelled);assertTrue(after.tacticalCommands.orders.isEmpty())
            assertEquals(before.bases,after.bases);assertEquals(before.outfits,after.outfits);assertTrue(impacts.isEmpty())
        }
    }
    @Test fun fortressSupplyConservesStockAndStopCancelsPendingCannon() {
        val r=run();val supplies=r.state.stocks.bases.getValue(1000).supplies+r.state.stocks.outfits.getValue(1).supplies
        assertNotNull(accept(r,TacticalCommand(0x41c,listOf(2),base=1000)).player)
        settle(r,now+5)
        assertEquals(supplies,r.state.stocks.bases.getValue(1000).supplies+r.state.stocks.outfits.getValue(1).supplies)
        assertTrue(OutfitSupplies.distribution(r.state.stocks.outfits.getValue(1)).getValue(2)>0)
        val later=session.copy(gameMillis=(now+6)*1000)
        val cannon=request(TacticalCommand(0x419,base=1000,direction=0f))
        assertNotNull(TacticalCommandOperations.accept(cannon,later,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1).player)
        val stop=cannon.copy(tacticalCommand=TacticalCommand(0x41e,base=1000))
        val before=r.production.snapshot(1)!!;val players=r.navigation.snapshot(1)
        r.state.fail=true
        assertFailsWith<java.io.IOException> {TacticalCommandOperations.accept(stop,later,galaxy,r.navigation,r.production,before,r.state.commit,1)}
        assertEquals(before,r.production.snapshot(1));assertEquals(before,r.state.stocks)
        assertEquals(players,r.navigation.snapshot(1));assertEquals(players,r.state.players)
        r.state.fail=false
        val stopped=TacticalCommandOperations.accept(stop,later,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1)
        assertEquals(listOf(0x419,0x41e),stopped.commandNotifications.map {it.order.command.opcode})
        assertEquals(listOf(true,false),stopped.commandNotifications.map {it.cancelled})
        assertTrue(stopped.commandNotifications.all {it.order.character==10000 && it.order.command.base==1000})
        assertTrue(r.state.stocks.tacticalCommands.orders.isEmpty())
        assertTrue(run(r.state).production.snapshot(1)!!.tacticalCommands.orders.isEmpty())
        assertTrue(settle(r,now+60).isEmpty())
    }
    @Test fun supremeMissionAndSubordinateProposalRemainDistinctDurableEffects() {
        val r=run();val order=TacticalCommand(0x421,listOf(1010001),kind=1,target=1000)
        assertNotNull(accept(r,order).player);settle(r,now+5)
        assertEquals(1,r.state.stocks.tacticalCommands.missions[10001]?.mission)
        val proposal=TacticalCommand(0x421,listOf(1010001),kind=5)
        assertNotNull(accept(r,proposal,"ally").player);settle(r,now+10)
        assertEquals(5,r.state.stocks.tacticalCommands.proposals[10001]?.mission)
        assertEquals(1,r.state.stocks.tacticalCommands.missions[10001]?.mission)
        settle(r,now+5+TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS)
        assertEquals(1L,r.state.stocks.tacticalCommands.merits[10001])
        assertEquals(1L,r.state.stocks.strategic.people.getValue(10001).merit)
        val restored=run(r.state);settle(restored,now+5+TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS)
        assertEquals(1L,r.state.stocks.strategic.people.getValue(10001).merit)
    }
    @Test fun supremeMissionWithOnlyTheChiefsOwnFlagshipReachesEveryOwnSideParticipant() {
        // The CD selection only holds units the chief commands (pick flag 0x400).
        val r=run();val order=TacticalCommand(0x421,listOf(1010000),kind=5)
        assertNotNull(accept(r,order).player);settle(r,now+5)
        assertEquals(5,r.state.stocks.tacticalCommands.missions[10000]?.mission)
        assertEquals(5,r.state.stocks.tacticalCommands.missions[10001]?.mission)
    }
    @Test fun defaultDefenseCreditsOnlyFullIntervalsAndStopsWhenTheOfficeIsLost() {
        val s=state()
        val players=s.players.toMutableMap()
        val offline=session.copy(participants=session.participants.mapValues {(account,p)->
            if(account=="pilot")p.copy(online=false,connection=null) else p})
        val initial=s.stocks.copy(tacticalCommands=TacticalCommandState(aiSwitches=mapOf(10000 to 16)))
        val interval=TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
        fun settle(at:Long,stock:PlanetStocks)=TacticalCommandOperations.finishMissions(
            offline.copy(gameMillis=at*1000),galaxy,players,stock,1,mutableListOf(),mutableListOf(),emptySet())
        val withdrawal=TacticalMissionState(10000,5,0,0,now,grid=2487)
        val explicit=settle(now,initial.copy(tacticalCommands=initial.tacticalCommands.copy(missions=mapOf(10000 to withdrawal))))
        assertEquals(withdrawal.copy(completed=true),explicit.tacticalCommands.missions.getValue(10000))
        assertEquals(10L,explicit.strategic.people.getValue(10000).merit)
        val assigned=settle(now,initial)
        assertTrue(assigned.tacticalCommands.missions.getValue(10000).defaultDefense)
        assertEquals(assigned,settle(now+interval-1,assigned))
        val credited=settle(now+interval,assigned)
        assertEquals(1L,credited.strategic.people.getValue(10000).merit)
        assertEquals(credited,settle(now+interval,credited))
        val relieved=settle(now+2*interval,credited.copy(strategic=credited.strategic.copy(offices=emptyList())))
        assertNull(relieved.tacticalCommands.missions[10000])
        assertEquals(1L,relieved.strategic.people.getValue(10000).merit)
    }

    @Test fun defaultGarrisonDefenseCreditsOnlineAndAiDisabledCommandersWithoutDelegatingControl() {
        val s=state()
        val pilot=s.players.getValue("pilot")
        val offline=session.copy(participants=session.participants+("pilot" to
            session.participants.getValue("pilot").copy(online=false,connection=null)))
        val interval=TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
        val aiOn=TacticalCommandState(aiSwitches=mapOf(pilot.characterId to 16))
        for((phase,commands) in listOf(session to aiOn,session to TacticalCommandState(),offline to TacticalCommandState())) {
            val initial=s.stocks.copy(tacticalCommands=commands)
            val players=s.players.toMutableMap()
            fun settle(at:Long,inventory:PlanetStocks)=TacticalCommandOperations.finishMissions(
                phase.copy(gameMillis=at*1000),galaxy,players,inventory,1,mutableListOf(),mutableListOf(),emptySet())
            assertFalse(TacticalOfflineAi.eligible(phase,"pilot",pilot,initial))
            val assigned=settle(now,initial)
            val mission=assigned.tacticalCommands.missions.getValue(pilot.characterId)
            assertTrue(mission.defaultDefense)
            assertEquals(1000,mission.target)
            assertEquals(assigned,settle(now+interval-1,assigned))
            val credited=settle(now+interval,assigned)
            assertEquals(1L,credited.strategic.people.getValue(pilot.characterId).merit)
            assertEquals(credited,settle(now+interval,credited))
            assertEquals(s.players,players)
        }
    }

    @Test fun explicitSkirmishOverridesDefaultDefenseOnlyForItsCurrentBattle() {
        for(changeGrid in listOf(false,true)) {
            val r=run()
            val pilot=r.state.players.getValue("pilot")
            val order=TacticalCommand(0x421,listOf(pilot.unitId),kind=0)
            assertNotNull(accept(r,order).player)
            val assignedAt=now+5
            settle(r,assignedAt)
            val issued=r.state.stocks
            val skirmish=issued.tacticalCommands.missions.getValue(pilot.characterId)
            assertEquals(0,skirmish.mission)
            assertFalse(skirmish.defaultDefense)
            val interval=TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
            val events=mutableListOf<TacticalCommandResult>()
            val players=r.state.players.toMutableMap()
            fun finish(at:Long,stocks:PlanetStocks)=TacticalCommandOperations.finishMissions(
                session.copy(gameMillis=at*1000),galaxy,players,stocks,1,events,mutableListOf(),emptySet())

            val active=finish(assignedAt+interval,issued)
            assertEquals(skirmish,active.tacticalCommands.missions.getValue(pilot.characterId))
            assertTrue(active.tacticalCommands.merits.isEmpty())
            assertTrue(events.isEmpty())

            players["pilot"]=if(changeGrid)pilot.copy(grid=pilot.grid+1,motion=null) else pilot
            val departing=if(changeGrid)active else active.copy(outfits=active.outfits-2,warehouses=active.warehouses-20)
            val ended=finish(assignedAt+interval+1,departing)
            assertNull(ended.tacticalCommands.missions[pilot.characterId])
            assertTrue(ended.tacticalCommands.merits.isEmpty())
            assertTrue(events.isEmpty())

            players.putAll(r.state.players)
            val enteredAt=assignedAt+interval+2
            val resumed=finish(enteredAt,ended.copy(outfits=active.outfits,warehouses=active.warehouses))
            val defense=resumed.tacticalCommands.missions.getValue(pilot.characterId)
            assertTrue(defense.defaultDefense)
            assertEquals(pilot.grid,defense.grid)
            assertEquals(enteredAt,defense.assignedAt)
            assertTrue(resumed.tacticalCommands.merits.isEmpty())
            assertEquals(1L,finish(enteredAt+interval,resumed).tacticalCommands.merits.getValue(pilot.characterId))
        }
    }


    @Test fun sameGridDefeatEndsDefenseWithoutCreditAtTheCheckBoundary() {
        val s=state()
        val mission=TacticalMissionState(10000,1,0,1000,now,grid=2488)
        val stock=s.stocks.copy(tacticalCommands=TacticalCommandState(missions=mapOf(10000 to mission)))
        val boundary=session.copy(gameMillis=(now+TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS)*1000)
        val pilot=s.players.getValue("pilot")
        val returned=pilot.copy(baseId=1000,mode=4,cpPaused=false,motion=null,commandRange=null)
        val players=(s.players+("pilot" to returned)).toMutableMap()
        val events=mutableListOf<TacticalCommandResult>()
        val defending=TacticalCommandOperations.finishMissions(boundary,galaxy,players,stock,1,mutableListOf(),mutableListOf(),emptySet())
        assertEquals(1L,defending.tacticalCommands.merits[10000])
        val defeated=TacticalCommandOperations.finishMissions(boundary,galaxy,players,stock,1,events,mutableListOf(),setOf(10000))
        assertTrue(defeated.tacticalCommands.missions.getValue(10000).completed)
        assertTrue(defeated.tacticalCommands.merits.isEmpty())
        assertEquals(mapOf(10000 to (1 to 0L)),events.single().finishedMissions)
    }

    @Test fun anEliminatedCountryStopsEarningMissionMeritWhileSurvivingCountriesKeepFighting() {
        val s = state()
        val fallen = Power.EMPIRE.country
        val survivor = Power.ALLIANCE.country
        val third = CountryId(42)
        val pilot = s.players.getValue("pilot")
        val ally = s.players.getValue("ally").copy(country = survivor)
        val opponent = ally.copy(characterId = 10002, country = third)
        val players = mutableMapOf("pilot" to pilot, "ally" to ally, "opponent" to opponent)
        val secondBase = galaxy.bases.single().copy(id = 1001, name = "Ally base")
        val world = Galaxy(galaxy.systems, galaxy.bases + secondBase, galaxy.starts)
        val missions = mapOf(
            pilot.characterId to TacticalMissionState(pilot.characterId, 1, 0, 1000, now, grid = pilot.grid),
            ally.characterId to TacticalMissionState(ally.characterId, 1, 0, 1001, now, grid = ally.grid),
        )
        val initial = s.stocks.copy(bases = s.stocks.bases + (1001 to PlanetStock(1001, 3, 0, 100, 0, 0)),
            tacticalCommands = s.stocks.tacticalCommands.copy(missions = missions))
        val ongoing = session.copy(countries = setOf(fallen, survivor, third),
            participants = session.participants - "pilot" - "ally")
        val interval = TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
        fun settle(at: Long, phase: SessionState, stock: PlanetStocks, events: MutableList<TacticalCommandResult> = mutableListOf()) =
            TacticalCommandOperations.finishMissions(phase.copy(gameMillis = at * 1000), world, players,
                stock, 1, events, mutableListOf(), emptySet())

        val earned = settle(now + interval, ongoing, initial)
        assertEquals(1L, earned.strategic.people.getValue(pilot.characterId).merit)
        assertEquals(1L, earned.strategic.people.getValue(ally.characterId).merit)

        val defeated = ongoing.copy(eliminated = mapOf(fallen to CountryDefeat(EndReason.SYSTEMS_REDUCED, now + interval + 1)))
        val events = mutableListOf<TacticalCommandResult>()
        val next = settle(now + 2 * interval, defeated, earned, events)
        assertEquals(1L, next.strategic.people.getValue(pilot.characterId).merit)
        assertEquals(1L, next.tacticalCommands.creditedMerits.getValue(pilot.characterId))
        assertTrue(next.tacticalCommands.missions.getValue(pilot.characterId).completed)
        assertEquals(mapOf(pilot.characterId to (1 to 0L)), events.single { it.finishedMissions.isNotEmpty() }.finishedMissions)
        assertEquals(2L, next.strategic.people.getValue(ally.characterId).merit)
        assertFalse(next.tacticalCommands.missions.getValue(ally.characterId).completed)

        events.clear()
        val later = settle(now + 3 * interval, defeated, next, events)
        assertEquals(1L, later.strategic.people.getValue(pilot.characterId).merit)
        assertTrue(events.all { it.finishedMissions.isEmpty() })
    }

    @Test fun withdrawalRewardsAnExitButNotDefeatAndReturn() {
        val s=state()
        val mission=TacticalMissionState(10000,5,0,0,now,grid=2488)
        val stock=s.stocks.copy(tacticalCommands=TacticalCommandState(missions=mapOf(10000 to mission)))
        for (defeated in listOf(emptySet(),setOf(10000))) {
            val players=s.players.toMutableMap()
            val pilot=players.getValue("pilot")
            players["pilot"]=pilot.copy(grid=2487,cpPaused=false,energy=null,motion=null,charge=null,commandRange=null)
            val events=mutableListOf<TacticalCommandResult>()
            val settled=TacticalCommandOperations.finishMissions(session,galaxy,players,stock,1,events,mutableListOf(),defeated)
            val reward=if(defeated.isEmpty())10L else 0L
            assertTrue(settled.tacticalCommands.missions.getValue(10000).completed)
            assertEquals(reward,settled.tacticalCommands.merits[10000] ?: 0L)
            assertEquals(5 to reward,events.single().finishedMissions[10000])
        }
    }

    @Test fun missionInterceptionCreditsTheActualCommanderEvenWhenItsLastEnemyDies() {
        val s=state();val enemy=s.stocks.outfits.getValue(2);val target=enemy.activeShips!!.values.single()
        val maximum=UnitSpecifications.ships.single {it.kind==target.stack.kind}.number
        val dead=target.copy(boats=0,damaged=0)
        val stock=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(activeShips=ActiveShipUnits(listOf(dead)))),
            tacticalCommands=TacticalCommandState(missions=mapOf(10000 to TacticalMissionState(10000,3,1,99,now,grid=2488))))
        val impacts=mutableListOf(TacticalImpact(1,1010000,TacticalWeapon.BEAM,TacticalHit(1,2488,now,99,maximum),emptyMap()))
        val events=mutableListOf<TacticalCommandResult>();val players=s.players.toMutableMap()
        val next=TacticalCommandOperations.finishMissions(session,galaxy,players,stock,1,events,impacts,emptySet())
        assertEquals(30L,next.strategic.people.getValue(10000).merit)
        assertEquals(30L,next.tacticalCommands.creditedMerits[10000]);assertTrue(next.tacticalCommands.missions.getValue(10000).completed)
        assertTrue(events.single().stateOnly);assertEquals(mapOf(10000 to 30L),events.single().merits)
        assertEquals(mapOf(10000 to (3 to 30L)),events.single().finishedMissions)
        assertEquals(next,TacticalCommandOperations.finishMissions(session,galaxy,players,next,1,mutableListOf(),impacts,emptySet()))
        val another=impacts.map {it.copy(source=1010001)}.toMutableList()
        val earlierHit=impacts.single().let {it.copy(hit=it.hit.copy(destroyed=1))}
        another.add(0,earlierHit)
        val surviving=target.copy(id=98)
        val contested=stock.copy(outfits=stock.outfits+(2 to enemy.copy(
            units=UnitInventory(mapOf(target.stack to 2)),activeShips=ActiveShipUnits(listOf(dead,surviving)))))
        assertTrue(TacticalContact.contested(GridForces.collect(galaxy,session.generation,players.values,contested)
            .getValue(2488)))
        val otherEvents=mutableListOf<TacticalCommandResult>()
        val lostTarget=TacticalCommandOperations.finishMissions(session,galaxy,players,contested,1,otherEvents,another,emptySet())
        assertTrue(lostTarget.tacticalCommands.merits.isEmpty())
        assertTrue(lostTarget.tacticalCommands.missions.getValue(10000).completed)
        assertEquals(mapOf(10000 to (3 to 0L)),otherEvents.single().finishedMissions)
    }

    @Test fun reconnaissanceEndsWhenItsTargetSinksEvenIfAnotherEnemyRemains() {
        val s=state()
        val enemy=s.stocks.outfits.getValue(2)
        val target=enemy.activeShips!!.values.single()
        val stock=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(
            units=UnitInventory(mapOf(target.stack to 2)),
            activeShips=ActiveShipUnits(listOf(target.copy(boats=0),target.copy(id=98))))),
            tacticalCommands=TacticalCommandState(missions=mapOf(10000 to TacticalMissionState(10000,4,1,99,now,grid=2488))))
        val players=s.players.toMutableMap()
        assertTrue(TacticalContact.contested(GridForces.collect(galaxy,session.generation,players.values,stock).getValue(2488)))
        val events=mutableListOf<TacticalCommandResult>()
        val hits=mutableListOf(TacticalImpact(1,1010001,TacticalWeapon.BEAM,TacticalHit(1,2488,now,99,target.boats),emptyMap()))
        val settled=TacticalCommandOperations.finishMissions(session,galaxy,players,stock,1,events,hits,emptySet())
        assertTrue(settled.tacticalCommands.missions.getValue(10000).completed)
        assertTrue(settled.tacticalCommands.merits.isEmpty())
        assertEquals(mapOf(10000 to (4 to 0L)),events.single().finishedMissions)
    }
    @Test fun interceptionAndReconnaissanceEndWhenTheTargetsCountryFallsButTheBattleContinues() {
        for(kind in 3..4) {
            val s=state()
            val fallen=Power.ALLIANCE.country
            val third=CountryId(42)
            val enemy=s.stocks.outfits.getValue(2)
            val original=enemy.activeShips!!.values.single()
            val target=original.copy(tactical=original.tactical!!.copy(motion=motion(0f,10f)))
            val other=enemy.copy(id=3,country=third,warehouseId=30,activeShips=ActiveShipUnits(listOf(target.copy(id=98))))
            val stock=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(activeShips=ActiveShipUnits(listOf(target))))+
                (3 to other),warehouses=s.stocks.warehouses+
                (30 to s.stocks.warehouses.getValue(20).copy(id=30,country=third,outfitId=3)),
                tacticalCommands=TacticalCommandState(missions=mapOf(10000 to TacticalMissionState(10000,kind,1,target.id,now,grid=2488))))
            val interval=TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
            val ongoing=session.copy(countries=session.countries+setOf(fallen,third))
            val players=s.players.toMutableMap()
            players["pilot"]=players.getValue("pilot").copy(motion=motion(0f,10f))
            val events=mutableListOf<TacticalCommandResult>()
            fun settle(at:Long,phase:SessionState,current:PlanetStocks)=TacticalCommandOperations.finishMissions(
                phase.copy(gameMillis=at*1000),galaxy,players,current,1,events,mutableListOf(),emptySet())

            val before=settle(now+interval,ongoing,stock)
            assertFalse(before.tacticalCommands.missions.getValue(10000).completed)
            val merit=if(kind==4)1L else 0L
            assertEquals(merit,before.tacticalCommands.merits[10000] ?: 0L)
            val defeated=ongoing.copy(eliminated=mapOf(fallen to CountryDefeat(EndReason.SYSTEMS_REDUCED,now+interval+1)))
            assertTrue(TacticalContact.contested(GridForces.collect(galaxy,1,players.values,before,defeated.eliminated.keys)[2488].orEmpty()))
            events.clear()
            val after=settle(now+2*interval,defeated,before)
            assertTrue(after.tacticalCommands.missions.getValue(10000).completed)
            assertEquals(merit,after.tacticalCommands.merits[10000] ?: 0L)
            assertEquals(mapOf(10000 to (kind to 0L)),events.single().finishedMissions)
            events.clear()
            val resumed=settle(now+3*interval,defeated,after)
            val defense=resumed.tacticalCommands.missions.getValue(10000)
            assertTrue(defense.defaultDefense)
            assertEquals(now+3*interval,defense.assignedAt)
            assertEquals(merit,resumed.tacticalCommands.merits[10000] ?: 0L)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun reconnaissanceMeritRequiresCurrentSharedContactAndDoesNotBackfillHiddenChecks() {
        val s=state()
        val players=s.players.toMutableMap()
        players["pilot"]=players.getValue("pilot").copy(motion=motion(.5f))
        val enemy=s.stocks.outfits.getValue(2)
        val target=enemy.activeShips!!.values.single()
        val targetMotion=motion(.5f).copy(velocity=.1f,waypoints=listOf(TacticalPoint(.2f,0f)))
        val ships=ActiveShipUnits(listOf(target.copy(tactical=target.tactical!!.copy(motion=targetMotion))))
        val base=s.stocks.bases.getValue(1000).copy(owner=Allegiance.Country(Power.ALLIANCE.country),garrisonCommanderId=0)
        val stock=s.stocks.copy(bases=mapOf(base.baseId to base),outfits=s.stocks.outfits+(enemy.id to enemy.copy(activeShips=ships)),
            tacticalCommands=TacticalCommandState(missions=mapOf(10000 to TacticalMissionState(10000,4,1,target.id,now,grid=2488))))
        val interval=TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
        fun settle(at:Long,current:PlanetStocks)=TacticalCommandOperations.finishMissions(
            session.copy(gameMillis=at*1000),galaxy,players,current,1,mutableListOf(),mutableListOf(),emptySet())
        val hidden=settle(now+interval,stock)
        assertTrue(hidden.tacticalCommands.merits.isEmpty())
        assertEquals(now+interval,hidden.tacticalCommands.missions.getValue(10000).checkedAt)

        players["ally"]=players.getValue("ally").copy(motion=motion(.2f))
        val acquired=settle(now+interval,hidden)
        assertTrue(acquired.tacticalCommands.merits.isEmpty())
        val visible=settle(now+2*interval,acquired)
        assertEquals(1L,visible.tacticalCommands.merits[10000])
        assertEquals(now+2*interval,visible.tacticalCommands.missions.getValue(10000).checkedAt)
    }

    @Test fun boardingReturnDoesNotCountAsDestroyingTheInterceptionTarget() {
        val s=state()
        val target=s.players.getValue("ally")
        val mission=TacticalMissionState(10000,3,1,target.unitId,now,grid=2488)
        val stock=s.stocks.copy(tacticalCommands=TacticalCommandState(missions=mapOf(10000 to mission)))
        val returned=target.copy(grid=2487,cpPaused=false,motion=null,commandRange=null)
        val players=(s.players+("ally" to returned)).toMutableMap()
        val boarding=TacticalImpact(1,1010000,TacticalWeapon.GUN,
            TacticalHit(1,2488,now,target.unitId,0,1),emptyMap(),departed=returned,boarding=true)
        val afterBoarding=TacticalCommandOperations.finishMissions(
            session,galaxy,players,stock,1,mutableListOf(),mutableListOf(boarding),setOf(target.characterId))
        assertTrue(afterBoarding.tacticalCommands.merits.isEmpty())
        assertFalse(afterBoarding.tacticalCommands.missions.getValue(10000).completed)
        val sinking=boarding.copy(hit=boarding.hit.copy(destroyed=1),boarding=false)
        val afterSinking=TacticalCommandOperations.finishMissions(
            session,galaxy,players,stock,1,mutableListOf(),mutableListOf(sinking),setOf(target.characterId))
        assertEquals(30L,afterSinking.tacticalCommands.merits[10000])
        assertTrue(afterSinking.tacticalCommands.missions.getValue(10000).completed)
    }

    @Test fun pirateTargetsEarnReconnaissanceAndInterceptionMeritOnTheSameTerms() {
        val s=state()
        val target=900001
        val interval=TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS
        val at=session.copy(gameMillis=(now+interval)*1000)
        fun settle(kind:Int,grid:Int,boats:Int,impact:Boolean,observerY:Float=-9.601f):Long {
            val fleet=PirateFleet(1,grid,now,listOf(PirateUnit(target,boats)))
            val stock=s.stocks.copy(
                social=s.stocks.social.copy(pirates=PirateState(mapOf(1 to fleet),2)),
                tacticalCommands=TacticalCommandState(missions=mapOf(10000 to TacticalMissionState(10000,kind,1,target,now,grid=2488))))
            val hits=mutableListOf<TacticalImpact>()
            if(impact)hits+=TacticalImpact(1,1010000,TacticalWeapon.BEAM,TacticalHit(1,2488,now+interval,target,1),emptyMap(),pirateSunk=boats==0)
            val observers=s.players.toMutableMap()
            observers["pilot"]=observers.getValue("pilot").copy(motion=motion(0f,observerY))
            return TacticalCommandOperations.finishMissions(at,galaxy,observers,stock,1,mutableListOf(),hits,emptySet())
                .tacticalCommands.merits[10000] ?: 0L
        }
        assertEquals(1L,settle(4,2488,1,false))
        assertEquals(0L,settle(4,2488,1,false,observerY=-9.599f))
        assertEquals(0L,settle(4,2487,1,false))
        assertEquals(0L,settle(4,2488,0,false))
        assertEquals(0L,settle(3,2488,1,true))
        assertEquals(30L,settle(3,2488,0,true))
    }

    @Test fun interceptionAndReconnaissanceRequireAnAlliedContactWhenAssigningTheMission() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases.map {it.copy(radius=25f)},galaxy.starts)
        for(kind in 3..4) {
            val s=state()
            s.players=s.players.mapValues {(_,p)->p.copy(motion=motion(0f,-21f))}
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits.mapValues {(_,outfit)->
                val y=if(outfit.id==1)-21f else -19f
                outfit.copy(activeShips=ActiveShipUnits(outfit.activeShips!!.values.map {u->
                    u.copy(tactical=u.tactical!!.copy(motion=motion(0f,y)))
                }))
            })
            val nav=Navigation(world,s.nav,NavigationPolicy(),clockScale=1)
            val production=PlanetProduction(world,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
            val command=request(TacticalCommand(0x421,listOf(1010001),kind=kind,targetKind=1,target=99))
            fun accept()=TacticalCommandOperations.accept(command,session,world,nav,production,production.snapshot(1)!!,s.commit,1)
            assertEquals(NavigationRejection.RANGE,accept().rejection)
            val old=production.snapshot(1)!!
            val fleet=old.outfits.getValue(1)
            val ships=fleet.activeShips!!.values.map {u->
                if(u.id==1)u.copy(tactical=u.tactical!!.copy(motion=motion(1f,-18f))) else u
            }
            production.replace(old,old.copy(outfits=old.outfits+(1 to fleet.copy(activeShips=ActiveShipUnits(ships)))))
            assertNotNull(accept().player)
            TacticalCombat.settle(session.copy(gameMillis=(now+5)*1000),world,nav,production,s.commit,scale=1)
            val assigned=assertNotNull(s.stocks.tacticalCommands.missions[10001])
            assertEquals(kind,assigned.mission)
            assertEquals(99,assigned.target)
        }
    }

    @Test fun offlineMissionRequiresAiOnAndStrategicPersonnelDecidesTheSupremeCommander() {
        val s=state();val disconnected=session.copy(participants=session.participants.mapValues {(a,p)->
            if(a=="ally")p.copy(online=false,connection=null) else p})
        val command=request(TacticalCommand(0x421,listOf(1010001),kind=1,target=1000))
        val r=run(s)
        assertEquals(NavigationRejection.ACCESS,TacticalCommandOperations.accept(command,disconnected,galaxy,r.navigation,
            r.production,r.production.snapshot(1)!!,s.commit,1).rejection)
        val enabled=state();enabled.stocks=enabled.stocks.copy(tacticalCommands=TacticalCommandState(aiSwitches=mapOf(10001 to 16)))
        val e=run(enabled)
        assertNotNull(TacticalCommandOperations.accept(command,disconnected,galaxy,e.navigation,e.production,e.production.snapshot(1)!!,enabled.commit,1).player)
        val ranked=state();ranked.stocks=ranked.stocks.copy(strategic=StrategicState(people=mapOf(10001 to StrategicPerson(rank=5,merit=100))))
        assertEquals(NavigationRejection.CARD,accept(run(ranked),command.tacticalCommand!!).rejection)
    }

    @Test fun missionsRequireCurrentRosterMembershipAtSelectionAndBroadcast() {
        val ally = session.participants.getValue("ally")
        val absent = session.copy(participants = session.participants - "ally")
        val otherCountry = session.copy(participants = session.participants +
            ("ally" to ally.copy(country = Power.ALLIANCE.country)))
        val offline = session.copy(participants = session.participants +
            ("ally" to ally.copy(online = false, connection = null)))
        val toAlly = TacticalCommand(0x421, listOf(1010001), kind = 1, target = 1000)
        val broadcast = toAlly.copy(units = listOf(1010000))

        fun issue(run: Run, command: TacticalCommand, current: SessionState) = TacticalCommandOperations.accept(
            request(command), current, galaxy, run.navigation, run.production, checkNotNull(run.production.snapshot(1)),
            run.state.commit, 1)
        fun finish(run: Run, current: SessionState): List<TacticalCommandResult> {
            val events = mutableListOf<TacticalCommandResult>()
            TacticalCombat.settle(current.copy(gameMillis = (now + 5) * 1000), galaxy, run.navigation,
                run.production, run.state.commit, scale = 1, onCommand = events::add)
            return events
        }

        for (current in listOf(absent, otherCountry)) {
            val missing = state()
            missing.stocks = missing.stocks.copy(
                tacticalCommands = TacticalCommandState(aiSwitches = mapOf(10001 to 16)),
                strategic = missing.stocks.strategic.copy(people = mapOf(10001 to StrategicPerson(rank = 5)))
            )
            val run = run(missing)
            assertEquals(NavigationRejection.ACCESS, issue(run, toAlly, current).rejection)
            assertNotNull(issue(run, broadcast, current).player)
            assertFalse(finish(run, current).single().cancelled)
            assertEquals(setOf(10000), missing.stocks.tacticalCommands.missions.keys)

            val pending = run()
            assertNotNull(issue(pending, toAlly, session).player)
            assertTrue(finish(pending, current).single().cancelled)
            assertTrue(pending.state.stocks.tacticalCommands.missions.values.all { it.defaultDefense })
            assertNull(pending.state.stocks.tacticalCommands.missions[10001])
        }

        val aiEnabled = state()
        aiEnabled.stocks = aiEnabled.stocks.copy(tacticalCommands = TacticalCommandState(aiSwitches = mapOf(10001 to 16)))
        val valid = run(aiEnabled)
        assertNotNull(issue(valid, toAlly, offline).player)
        assertFalse(finish(valid, offline).single().cancelled)
        assertEquals(setOf(10000, 10001), aiEnabled.stocks.tacticalCommands.missions.keys)
    }
    @Test fun confusionBlocksRegularCommandsAndEncouragementActuallyRecoversMorale() {
        val s=state();s.stocks=s.stocks.copy(tacticalCommands=TacticalCommandState(morale=mapOf(1 to 15)))
        val r=run(s);val order=request(TacticalCommand(0x40d,listOf(1),kind=0))
        assertEquals(NavigationRejection.ACCESS,TacticalCommandOperations.guard(order,session,r.navigation,s.stocks)?.rejection)
        val events=mutableListOf<TacticalCommandResult>()
        assertNotNull(accept(r,TacticalCommand(0x409,listOf(1010000))).player);settle(r,now+5,events)
        assertEquals(35,s.stocks.tacticalCommands.morale[1]);assertFalse(s.stocks.tacticalCommands.confused(1))
        assertEquals(35,events.single().morale[1])
        assertEquals(90,s.players.getValue("pilot").pcp)
    }
    @Test fun garrisonEncouragementAlsoLiftsItsLandedGroundTroops() {
        val s=state();val o=s.stocks.outfits.getValue(1)
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(1),base=1000,origin=1)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to o.copy(activeGround=ground,units=UnitInventory(o.units.ships,ground.quantities()))),
            tacticalCommands=TacticalCommandState(morale=mapOf(3 to 50)))
        val r=run(s);assertNotNull(accept(r,TacticalCommand(0x41d,base=1000)).player);settle(r,now+5)
        assertEquals(70,s.stocks.tacticalCommands.morale[3])
    }
    @Test fun deceasedCommanderCannotRetreatAndAdmittedReservationsAreClearedOnReload() {
        assertRetreatCancelledForUnavailableCommander(detained=false)
    }

    @Test fun detainedCommanderCannotRetreatAndAdmittedReservationsAreClearedOnReload() {
        assertRetreatCancelledForUnavailableCommander(detained=true)
    }

    private fun assertRetreatCancelledForUnavailableCommander(detained:Boolean) {
        val s=state()
        val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=true)
        val pilot=s.players.getValue("pilot").copy(retreat=retreat,motion=motion(260f),
            energy=TacticalEnergy(List(11) {if(it==1)TacticalEnergy.WARP_MAX else 0}))
        s.players=s.players+("pilot" to pilot)
        val fleet=s.stocks.outfits.getValue(1)
        val ships=fleet.activeShips!!.values.map {u->if(u.id!=1)u else
            u.copy(motionCommander=10000,tactical=NpcTacticalState(motion(260f,.4f),now,now,retreat=retreat))}
        val saved=s.stocks.copy(outfits=s.stocks.outfits+(1 to fleet.copy(activeShips=ActiveShipUnits(ships))))
        s.stocks=if(detained) {
            val detention=Detention(pilot.characterId,checkNotNull(pilot.country),now,now+Justice.DAY)
            saved.copy(social=saved.social.copy(justice=JusticeState(detentions=mapOf(pilot.characterId to detention))))
        } else BattleDeath.apply(saved,listOf(10000),now) {_,_->true}
        val r=run(s)
        val request=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        val due=session.copy(gameMillis=(now+155)*1000)
        val rejection=if(detained)NavigationRejection.BUSY else NavigationRejection.IDENTITY
        assertEquals(rejection,TacticalFleetRetreat.accept(request,due,galaxy,r.navigation,
            r.production,r.production.snapshot(1)!!,s.commit,1).rejection)
        assertEquals(rejection,r.navigation.apply(request.copy(tacticalRetreat=listOf(pilot.unitId)),
            due,r.production.snapshot(1)!!).rejection)
        r.navigation.settleRetreats(due,r.production.snapshot(1))
        settle(r,now+155)
        assertEquals(pilot.grid,s.players.getValue("pilot").grid)
        assertNull(s.players.getValue("pilot").retreat)
        assertEquals(fleet.position,s.stocks.outfits.getValue(1).position)
        assertEquals(listOf(1,2),s.stocks.outfits.getValue(1).activeShips!!.values.map {it.id})
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.retreat)
    }
    @Test fun woundedCommanderCannotIssueRegularRetreatUntilRecovery() {
        val s=state()
        val pilot=s.players.getValue("pilot")
        val wounded=pilot.copy(motion=motion(260f),hull=pilot.hull!!.copy(woundedUntil=now+1))
        s.players=s.players+("pilot" to wounded)
        val outfit=s.stocks.outfits.getValue(1)
        val ships=ActiveShipUnits(outfit.activeShips!!.values.map {
            it.copy(tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now))
        })
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ships)))
        val r=run(s)
        val req=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        val before=r.production.snapshot(1)!!
        assertEquals(NavigationRejection.WOUNDED,TacticalFleetRetreat.accept(req,session,galaxy,
            r.navigation,r.production,before,s.commit,1).rejection)
        assertEquals(wounded,r.navigation.snapshot(1).getValue("pilot"))
        assertEquals(ships,s.stocks.outfits.getValue(1).activeShips)
        val recovered=session.copy(gameMillis=(now+1)*1000)
        assertNotNull(TacticalFleetRetreat.accept(req,recovered,galaxy,r.navigation,r.production,before,s.commit,1).player)
        assertNotNull(s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.retreat)
    }

    @Test fun aFleetRetreatAlreadyPreparingIsCancelledWhenTheBattleEnds() {
        val s = state()
        val pilot = s.players.getValue("pilot")
        s.players = s.players + ("pilot" to pilot.copy(motion = motion(260f)))
        val fleet = s.stocks.outfits.getValue(1)
        val ships = ActiveShipUnits(fleet.activeShips!!.values.map {
            it.copy(tactical = NpcTacticalState(motion(260f, it.id * .4f), now, now))
        })
        s.stocks = s.stocks.copy(outfits = s.stocks.outfits + (1 to fleet.copy(activeShips = ships)))
        val r = run(s)
        val command = request(TacticalCommand(0x40d, listOf(1))).copy(tacticalCommand = null, tacticalRetreat = listOf(1))
        assertNotNull(TacticalFleetRetreat.accept(command, session, galaxy, r.navigation, r.production,
            assertNotNull(r.production.snapshot(1)), s.commit, 1).player)
        settle(r, now + 5)
        val before = assertNotNull(r.production.snapshot(1))
        val preparing = before.outfits.getValue(1)
        assertTrue(assertNotNull(preparing.activeShips!!.values.first().tactical!!.retreat).membershipChecked)

        val enemy = before.outfits.getValue(2)
        val departed = enemy.copy(position = OutfitPosition(2490))
        r.production.replace(before, before.copy(outfits = before.outfits + (2 to departed)))
        val movements = mutableListOf<TacticalMovement>()
        TacticalCombat.settle(session.copy(gameMillis = (now + 155) * 1000), galaxy, r.navigation,
            r.production, s.commit, scale = 1, onMovement = movements::add)

        val after = s.stocks.outfits.getValue(1)
        assertEquals(preparing.position, after.position)
        assertEquals(preparing.supplies, after.supplies)
        assertEquals(preparing.activeShips.values.map { it.id }, after.activeShips!!.values.map { it.id })
        assertTrue(after.activeShips.values.all { it.tactical?.retreat == null })
        assertTrue(movements.none { it.destination != null })
        assertFalse(s.players.getValue("pilot").cpPaused)
    }

    @Test fun admittedFleetRetreatCannotWarpAfterTheOutfitDocksChangesGridOrLosesItsCountry() {
        for (position in listOf(OutfitPosition(2488,1000),OutfitPosition(2487),OutfitPosition(2488))) {
            val s=state()
            val fleet=s.stocks.outfits.getValue(1)
            val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=true)
            val ships=ActiveShipUnits(fleet.activeShips!!.values.map {
                it.copy(motionCommander=10000,
                    tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now,retreat=retreat))
            })
            val moved=fleet.copy(position=position,activeShips=ships)
            val before=s.stocks.copy(outfits=s.stocks.outfits+(1 to moved))
            val events=mutableListOf<TacticalMovement>()
            val eliminated=if(position==OutfitPosition(2488))mapOf(checkNotNull(fleet.country) to
                CountryDefeat(EndReason.SYSTEMS_REDUCED,now)) else emptyMap()
            val due=session.copy(gameMillis=(now+155)*1000,eliminated=eliminated)
            val after=TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),before,events)
            val result=after.outfits.getValue(1)
            assertEquals(position,result.position)
            assertEquals(listOf(1,2),result.activeShips!!.values.map {it.id})
            assertTrue(result.activeShips.values.all {it.tactical?.retreat==null})
            assertTrue(events.isEmpty())
        }
    }

    @Test fun admittedFleetRetreatCancelsWhenTheCommandersSessionCountryIsEliminated() {
        val s=state()
        val fleet=s.stocks.outfits.getValue(1)
        val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=true)
        val ships=ActiveShipUnits(fleet.activeShips!!.values.map {it.copy(motionCommander=10000,
            tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now,retreat=retreat))})
        val prepared=fleet.copy(position=OutfitPosition(2488),activeShips=ships)
        val before=s.stocks.copy(outfits=s.stocks.outfits+(1 to prepared))
        val due=session.copy(gameMillis=(now+155)*1000,
            participants=session.participants+("pilot" to session.participants.getValue("pilot").copy(country=Power.ALLIANCE.country)),
            eliminated=mapOf(Power.ALLIANCE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        val events=mutableListOf<TacticalMovement>()
        val after=TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),before,events)
        val expected=prepared.copy(activeShips=ActiveShipUnits(ships.values.map {
            it.copy(tactical=it.tactical!!.copy(retreat=null))
        }))
        assertEquals(before.copy(outfits=before.outfits+(1 to expected)),after)
        assertTrue(events.isEmpty())
    }

    @Test fun fleetRetreatCancelsOrdersAfterAuthorityChangesBeforeOrAfterAdmission() {
        for (admitted in listOf(false,true)) {
            val s=state()
            val pilot=s.players.getValue("pilot")
            s.players=s.players+("pilot" to pilot.copy(motion=motion(260f)))
            val fleet=s.stocks.outfits.getValue(1)
            val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=admitted)
            val ships=ActiveShipUnits(fleet.activeShips!!.values.map {
                it.copy(motionCommander=pilot.characterId,
                    tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now,retreat=retreat))
            })
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to fleet.copy(activeShips=ships)),
                tacticalCommands=s.stocks.tacticalCommands.copy(authority=mapOf(1 to 10001)))
            val r=run(s)
            settle(r,now+155)
            val remaining=s.stocks.outfits.getValue(1)
            assertEquals(fleet.position,remaining.position)
            assertEquals(listOf(1),remaining.activeShips!!.values.map {it.id})
            assertNull(remaining.activeShips.values.single().tactical!!.retreat)
            val moved=s.stocks.outfits.values.single {it.activeShips?.values?.any {unit->unit.id==2}==true}
            assertEquals(2489,moved.positionIn(galaxy).grid)
        }
    }

    @Test fun regularRetreatMovesOnlySelectedStableIdsAndLeavesLandedTroopsBehind() {
        val s=state();s.players=s.players.mapValues {(account,p)->if(account=="pilot")p.copy(motion=motion(260f)) else p}
        val o=s.stocks.outfits.getValue(1)
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(1),base=1000,origin=1)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to o.copy(activeGround=ground,
            units=UnitInventory(o.units.ships,ground.quantities()),activeShips=ActiveShipUnits(o.activeShips!!.values.map {
                it.copy(tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now))}))))
        val r=run(s);val req=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        assertNotNull(TacticalFleetRetreat.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+5)
        assertTrue(s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.retreat!!.membershipChecked)
        settle(r,now+154);assertEquals(2,s.stocks.outfits.getValue(1).activeShips!!.values.size)
        settle(r,now+155)
        val moved=s.stocks.outfits.values.single {it.activeShips?.values?.any {u->u.id==1}==true}
        assertNotEquals(2488,moved.positionIn(galaxy).grid)
        assertEquals(listOf(2),s.stocks.outfits.getValue(1).activeShips!!.values.map {it.id})
        assertEquals(1000,s.stocks.outfits.getValue(1).activeGround!!.values.single().base)
        assertEquals(10000,TacticalAuthority.commander(moved,moved.activeShips!!.values.single(),s.stocks))
        assertEquals(100,s.stocks.outfits.values.sumOf {it.supplies})
    }
    @Test fun regularRetreatCanReserveTheNearestExitThatBlocksASoloFlagship() {
        val s = state()
        val east = 2489
        val pilot = s.players.getValue("pilot").copy(motion = motion(260f),
            energy = TacticalEnergy(List(11) { if (it == 1) TacticalEnergy.WARP_MAX else 0 }))
        val enemy = Navigator(1, 10002, Power.ALLIANCE, pilot.grid, 100, 100, 1000, cpPaused = true,
            hull = FlagshipHull(89, now), motion = motion(100f))
        s.players = s.players + ("pilot" to pilot) + ("enemy" to enemy)
        val fleet = s.stocks.outfits.getValue(1)
        val regular = ActiveShipUnits(fleet.activeShips!!.values.map { unit ->
            if (unit.id == 1) unit.copy(tactical = NpcTacticalState(motion(260f), now, now)) else unit
        })
        val troops = ActiveGroundUnits(listOf(ActiveGroundUnit(3, UnitStack(1), carrier = 1)))
        val hostile = s.stocks.outfits.getValue(2).copy(position = OutfitPosition(east))
        s.stocks = s.stocks.copy(outfits = mapOf(
            fleet.id to fleet.copy(activeShips = regular, activeGround = troops,
                units = UnitInventory(regular.quantities(), troops.quantities())),
            hostile.id to hostile,
        ))
        val r = run(s)
        val inventory = assertNotNull(r.production.snapshot(1))
        val command = request(TacticalCommand(0x40d, listOf(1))).copy(tacticalCommand = null, tacticalRetreat = listOf(1))
        val solo = r.navigation.apply(command.copy(tacticalRetreat = listOf(pilot.unitId)), session, inventory, persist = false)
        assertNotEquals(east, assertNotNull(assertNotNull(solo.player).retreat).destination)

        val accepted = TacticalFleetRetreat.accept(command, session, galaxy, r.navigation, r.production, inventory, s.commit, 1)
        assertNull(accepted.rejection)
        val queued = s.stocks.outfits.getValue(1).activeShips!!.values.first().tactical!!.retreat
        assertEquals(east, assertNotNull(queued).destination)
        settle(r, queued.completesAt)
        val detachment = s.stocks.outfits.values.single { outfit -> outfit.activeShips?.values.orEmpty().any { it.id == 1 } }
        assertEquals(east, detachment.positionIn(galaxy).grid)
        assertEquals(troops, detachment.activeGround)
        assertEquals(pilot.grid, s.players.getValue("pilot").grid)
    }

    @Test fun fleetRetreatCountsEmbarkedTroopsAgainstDestinationCapacity() {
        val s=state()
        val fleet=s.stocks.outfits.getValue(1)
        val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=true)
        val ships=ActiveShipUnits(fleet.activeShips!!.values.map {
            if(it.id==1)it.copy(motionCommander=10000,tactical=NpcTacticalState(motion(260f),now,now,retreat=retreat)) else it
        })
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(1),carrier=1)))
        val before=s.stocks.copy(outfits=s.stocks.outfits+(1 to fleet.copy(activeShips=ships,activeGround=troops,
            units=UnitInventory(ships.quantities(),troops.quantities()))))
        val due=session.copy(gameMillis=(now+155)*1000)
        val pilot=s.players.getValue("pilot")
        fun occupants(count:Int)=s.players+(1..count).associate {"occupant$it" to
            pilot.copy(characterId=20000+it,grid=2489,motion=null)}
        val events=mutableListOf<TacticalMovement>()
        assertEquals(before,TacticalFleetRetreat.advance(due,galaxy,occupants(299).toMutableMap(),before,events))
        assertTrue(events.isEmpty())

        val moved=TacticalFleetRetreat.advance(due,galaxy,occupants(298).toMutableMap(),before,events)
        val detachment=moved.outfits.values.single {it.activeShips?.values?.any {unit->unit.id==1}==true}
        assertEquals(2489,detachment.positionIn(galaxy).grid)
        assertEquals(troops,detachment.activeGround)
        assertEquals(1,events.size)
    }

    @Test fun eliminatedFleetDoesNotConsumeARetreatDestinationCampSlot() {
        val s=state()
        val fleet=s.stocks.outfits.getValue(1)
        val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=true)
        val ships=ActiveShipUnits(fleet.activeShips!!.values.map {
            if(it.id==1)it.copy(motionCommander=10000,tactical=NpcTacticalState(motion(260f),now,now,retreat=retreat)) else it
        })
        val pirates=PirateFleet(1,2489,now,listOf(PirateUnit(900001,1)))
        val sourcePirates=PirateFleet(2,2488,now,listOf(PirateUnit(900002,1)))
        val blocker=s.stocks.outfits.getValue(2).copy(position=OutfitPosition(2489))
        val before=s.stocks.copy(outfits=s.stocks.outfits+(1 to fleet.copy(activeShips=ships))+(2 to blocker),
            social=s.stocks.social.copy(pirates=PirateState(mapOf(1 to pirates,2 to sourcePirates),3)))
        val due=session.copy(gameMillis=(now+155)*1000)
        val events=mutableListOf<TacticalMovement>()
        assertEquals(before,TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),before,events))
        assertTrue(events.isEmpty())

        val defeated=due.copy(eliminated=mapOf(Power.ALLIANCE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        val moved=TacticalFleetRetreat.advance(defeated,galaxy,s.players.toMutableMap(),before,events)
        val detachment=moved.outfits.values.single {it.activeShips?.values?.any {unit->unit.id==1}==true}
        assertEquals(2489,detachment.positionIn(galaxy).grid)
        assertEquals(1,events.size)
    }

    @Test fun rebelDetachmentRetainsItsCampAfterPartialRetreat() {
        val s=state()
        val fleet=s.stocks.outfits.getValue(1)
        val retreat=TacticalRetreat(2489,now,5,150,membershipChecked=true)
        val ships=ActiveShipUnits(fleet.activeShips!!.values.map {
            if(it.id==1)it.copy(motionCommander=10000,tactical=NpcTacticalState(motion(260f),now,now,retreat=retreat)) else it
        })
        val plot=Plot(10000,fleet.country,0,revolt=5)
        val coup=CoupState(mapOf(10000 to plot),loyalty=mapOf(1 to 100))
        val before=s.stocks.copy(outfits=s.stocks.outfits+(1 to fleet.copy(activeShips=ships)),social=s.stocks.social.copy(coup=coup))
        val events=mutableListOf<TacticalMovement>()
        val after=TacticalFleetRetreat.advance(session.copy(gameMillis=(now+155)*1000),galaxy,s.players.toMutableMap(),before,events)
        val moved=after.outfits.values.single {it.activeShips?.values?.any {unit->unit.id==1}==true}
        assertEquals(2489,moved.positionIn(galaxy).grid)
        assertTrue(TacticalAuthority.camp(moved,after).insurgent)
        assertEquals(1,events.size)
    }

    @Test fun partialRetreatKeepsTheIndependentFleetAndWarehouseModelsAndStableIdentities() {
        val s=state();s.players=s.players.mapValues {(account,p)->if(account=="pilot")p.copy(motion=motion(260f)) else p}
        val outfit=s.stocks.outfits.getValue(1)
        val ships=ActiveShipUnits(outfit.activeShips!!.values.map {it.copy(stack=UnitStack(if(it.id==1)147 else 151),
            tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now))})
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(5),base=1000,origin=1)))
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(shipStyle=ShipStyleId("alliance"),activeShips=ships,
            activeGround=ground,units=UnitInventory(ships.quantities(),ground.quantities()))),
            warehouses=s.stocks.warehouses+(10 to s.stocks.warehouses.getValue(10).copy(shipStyle=ShipStyleId("alliance"))),
            outfitSequence=OutfitSequence(50,60))
        val r=run(s);val req=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        assertNotNull(TacticalFleetRetreat.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+5);settle(r,now+155)
        val moved=s.stocks.outfits.getValue(50)
        assertNotEquals(2488,moved.positionIn(galaxy).grid)
        assertEquals(CountryId(2),moved.country);assertEquals(ShipStyleId("alliance"),moved.shipStyle)
        assertEquals(ShipStyleId("alliance"),s.stocks.outfits.getValue(1).shipStyle)
        assertEquals(listOf(1),moved.activeShips!!.values.map {it.id})
        assertEquals(147,moved.activeShips.values.single().stack.kind)
        assertEquals(60,moved.warehouseId)
        assertEquals(CountryId(2),s.stocks.warehouses.getValue(60).country)
        assertEquals(ShipStyleId("alliance"),s.stocks.warehouses.getValue(60).shipStyle)
        assertEquals(OutfitSequence(51,61),s.stocks.outfitSequence)
        assertEquals(ground,s.stocks.outfits.getValue(1).activeGround)
        assertEquals(10000,TacticalAuthority.commander(moved,moved.activeShips.values.single(),s.stocks))
    }
    @Test fun partialTransportRetreatPreservesSurvivingCargoAndRollsBackOnSaveFailure() {
        val s=state()
        s.players=s.players+("pilot" to s.players.getValue("pilot").copy(motion=motion(260f)))
        val outfit=s.stocks.outfits.getValue(1)
        val transport=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind)
        val ships=ActiveShipUnits(outfit.activeShips!!.values.map {
            it.copy(stack=transport,tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now))
        })
        val cargo=TransportPackage(10,outfit.country,1000,1001,101,
            UnitInventory(mapOf(UnitStack(32) to 5),mapOf(UnitStack(0) to 5)),1,600,399)
        val small=cargo.copy(id=11,supplies=2,units=UnitInventory())
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(kind=OutfitKind.TRANSPORT,
            activeShips=ships,units=UnitInventory(ships.quantities()))),
            warehouses=s.stocks.warehouses+(10 to s.stocks.warehouses.getValue(10).copy(kind=OutfitKind.TRANSPORT)),
            strategic=s.stocks.strategic.copy(transports=listOf(cargo,small),lastTransportId=20),outfitSequence=OutfitSequence(50,60))
        val r=run(s)
        val req=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        assertNotNull(TacticalFleetRetreat.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+5)
        val before=s.stocks
        val due=session.copy(gameMillis=(now+155)*1000)
        val full=before.copy(strategic=before.strategic.copy(lastTransportId=Int.MAX_VALUE))
        val movement=mutableListOf<TacticalMovement>()
        assertEquals(full,TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),full,movement))
        assertTrue(movement.isEmpty())
        val overloaded=cargo.copy(supplies=0,units=UnitInventory(troops=mapOf(UnitStack(0) to 33)))
        val noRoom=before.copy(strategic=before.strategic.copy(transports=listOf(overloaded,small)))
        assertEquals(noRoom,TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),noRoom,movement))
        assertTrue(movement.isEmpty())

        s.fail=true
        assertFailsWith<java.io.IOException> {settle(r,now+155)}
        assertSame(before,s.stocks)
        s.fail=false
        settle(r,now+155)
        val packages=s.stocks.strategic.transports.associateBy {it.id}
        val left=packages.getValue(10)
        val moved=packages.getValue(21)
        assertEquals(1,left.carrier);assertEquals(50,moved.carrier)
        assertEquals(34,left.supplies);assertEquals(33,moved.supplies)
        assertEquals(UnitInventory(mapOf(UnitStack(32) to 2),mapOf(UnitStack(0) to 2)),left.units)
        assertEquals(UnitInventory(mapOf(UnitStack(32) to 1),mapOf(UnitStack(0) to 1)),moved.units)
        assertEquals(1,packages.getValue(11).supplies)
        assertTrue(packages.values.all {it.carrierBoats==300 && it.survivingBoats==300})
        assertEquals(21,s.stocks.strategic.lastTransportId)
    }

    @Test fun retreatOfLastSurvivingTransportKeepsOutfitAndCargo() {
        val s=state()
        s.players=s.players+("pilot" to s.players.getValue("pilot").copy(motion=motion(260f)))
        val original=s.stocks.outfits.getValue(1)
        val transport=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind)
        val ships=ActiveShipUnits(original.activeShips!!.values.map {
            it.copy(stack=transport,tactical=NpcTacticalState(motion(260f,it.id*.4f),now,now))
        })
        val outfit=original.copy(kind=OutfitKind.TRANSPORT,activeShips=ships,units=UnitInventory(ships.quantities()))
        val cargo=TransportPackage(10,outfit.country,1000,1001,101,carrier=1,carrierBoats=600)
        val loaded=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit),
            warehouses=s.stocks.warehouses+(10 to s.stocks.warehouses.getValue(10).copy(kind=OutfitKind.TRANSPORT)),
            strategic=s.stocks.strategic.copy(transports=listOf(cargo),lastTransportId=10),outfitSequence=OutfitSequence(50,60))
        val damaged=TacticalLosses.apply(outfit,2,300)
        s.stocks=TransportActions.settleCargo(loaded.copy(outfits=loaded.outfits+(1 to damaged)),loaded)
        val r=run(s)
        val req=request(TacticalCommand(0x40d,listOf(1))).copy(tacticalCommand=null,tacticalRetreat=listOf(1))
        assertNotNull(TacticalFleetRetreat.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+5)
        val before=s.stocks
        val events=mutableListOf<TacticalMovement>()
        val due=session.copy(gameMillis=(now+155)*1000)
        val after=TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),before,events)
        val moved=after.outfits.getValue(1)
        assertEquals(before.outfits.keys,after.outfits.keys)
        assertEquals(before.warehouses,after.warehouses)
        assertEquals(before.outfitSequence,after.outfitSequence)
        assertEquals(outfit.commanderId,moved.commanderId)
        assertEquals(outfit.warehouseId,moved.warehouseId)
        assertEquals(listOf(cargo.copy(survivingBoats=300)),after.strategic.transports)
        assertNotEquals(2488,moved.positionIn(galaxy).grid)
        assertEquals(listOf(1 to 300,2 to 0),moved.activeShips!!.values.map {it.id to it.boats})
        assertTrue(moved.activeShips.values.all {it.tactical==null})
        assertEquals(listOf(1 to moved.positionIn(galaxy).grid),events.map {it.unit to it.destination})

        val crew=UnitSpecifications.crews.first {it.power==outfit.shipStyle.nativePower && GroundUnits.isGround(it.kind)}
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(3,UnitStack(crew.kind),base=1000,origin=1)))
        val source=before.outfits.getValue(1)
        val withGround=before.copy(outfits=before.outfits+(1 to source.copy(
            activeGround=ground,units=UnitInventory(source.units.ships,ground.quantities()))))
        val split=TacticalFleetRetreat.advance(due,galaxy,s.players.toMutableMap(),withGround,mutableListOf())
        assertEquals(ground,split.outfits.getValue(1).activeGround)
        assertEquals(2488,split.outfits.getValue(1).positionIn(galaxy).grid)
        assertEquals(moved.positionIn(galaxy).grid,split.outfits.getValue(50).positionIn(galaxy).grid)
    }

    @Test fun fortressCannonHitsFriendlyAndEnemyShipsAlongTheRay() {
        val s=state();val outfit=s.stocks.outfits.getValue(1)
        val ships=ActiveShipUnits(outfit.activeShips!!.values.map {if(it.id==2)it.copy(stack=UnitStack(32)) else it})
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ships,units=UnitInventory(ships.quantities()))))
        val r=run(s);val own=r.state.stocks.outfits.getValue(1).activeShips!!.values.first().boats
        val enemy=r.state.stocks.outfits.getValue(2).activeShips!!.values.single().boats
        assertNotNull(accept(r,TacticalCommand(0x419,base=1000,direction=0f)).player)
        val impacts=mutableListOf<TacticalImpact>();val events=mutableListOf<TacticalCommandResult>()
        settle(r,now+34,events,impacts);assertTrue(impacts.isEmpty());assertTrue(events.isEmpty())
        settle(r,now+35,events,impacts)
        assertTrue(r.state.stocks.outfits.getValue(1).activeShips!!.values.first().boats<own)
        assertTrue(r.state.stocks.outfits.getValue(2).activeShips!!.values.single().boats<enemy)
        assertTrue(impacts.any {it.hit.target==1});assertTrue(impacts.any {it.hit.target==99})
        assertTrue(impacts.all {it.fortress})
        val survivor=r.state.stocks.outfits.getValue(1).activeShips!!.values.single {it.id==2}
        assertTrue(survivor.boats in 1..299)
        assertFalse(2 in events.single().destroyedUnits,"Native 0436 must never turn a partial loss into a total kill")
        val dead=r.state.stocks.outfits.values.flatMap {it.activeShips!!.values}.filter {it.boats==0}.map {it.id}.toSet()
        assertEquals(dead,events.single().destroyedUnits.filter {it<1000000}.toSet())
    }
    @Test fun onlyAMobileFortressTakesTheMoveCommand() {
        // CD manual p.54: 【移動】 is exclusive to mobile fortresses (U-26).
        val fixed=Galaxy(galaxy.systems,listOf(galaxy.bases.single().copy(mobile=false)),galaxy.starts)
        val r=run()
        assertEquals(NavigationRejection.BASE,TacticalCommandOperations.accept(request(TacticalCommand(0x41f,base=1000,velocity=.5f,
            points=listOf(TacticalPoint(20f,0f)))),session,fixed,r.navigation,r.production,r.production.snapshot(1)!!,r.state.commit,1).rejection)
    }
    @Test fun fortressMovementCannotCrossTheStarWhenQueuedOrWhenStarting() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases.map {it.copy(radius=20f)},galaxy.starts)
        val s=state()
        val nav=Navigation(world,s.nav,NavigationPolicy(),clockScale=1)
        val production=PlanetProduction(world,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        fun accept(y:Float):NavigationResult {
            val cmd=request(TacticalCommand(0x41f,base=1000,points=listOf(TacticalPoint(1f,y))))
            return TacticalCommandOperations.accept(cmd,session,world,nav,production,production.snapshot(1)!!,s.commit,1)
        }
        assertEquals(NavigationRejection.RANGE,accept(-20f).rejection)
        assertTrue(production.snapshot(1)!!.tacticalCommands.orders.isEmpty())
        assertNotNull(accept(20f).player)
        val old=production.snapshot(1)!!
        val shifted=TacticalFortressMotion(2488,motion(0f,-20f))
        production.replace(old,old.copy(tacticalCommands=old.tacticalCommands.copy(fortresses=mapOf(1000 to shifted))))
        TacticalCombat.settle(session.copy(gameMillis=(now+5)*1000),world,nav,production,s.commit,scale=1)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        val fortress=s.stocks.tacticalCommands.fortresses.getValue(1000).motion
        assertEquals(TacticalPoint(0f,-20f),fortress.pose(now+100).position)
        assertEquals(0f,fortress.velocity)
    }

    @Test fun aLateFortressOrderChecksThePositionAtItsScheduledStart() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases,galaxy.starts)
        val s=state()
        val destination=TacticalPoint(0f,20f)
        val oldMotion=motion(0f,-20f).copy(velocity=1f,waypoints=listOf(TacticalPoint(25f,-20f)),waitSeconds=0)
        val pending=TacticalQueuedCommand(10000,2488,now,now+5,TacticalCommand(0x41f,base=1000,points=listOf(destination)))
        s.stocks=s.stocks.copy(tacticalCommands=s.stocks.tacticalCommands.copy(orders=listOf(pending),
            fortresses=mapOf(1000 to TacticalFortressMotion(2488,oldMotion))))
        assertTrue(TacticalTerrain.pathBlocked(world,2488,oldMotion.pose(now+5).position,listOf(destination)))
        assertFalse(TacticalTerrain.pathBlocked(world,2488,oldMotion.pose(now+30).position,listOf(destination)))
        val nav=Navigation(world,s.nav,NavigationPolicy(),clockScale=1)
        val production=PlanetProduction(world,s.stock,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        val events=mutableListOf<TacticalCommandResult>()
        TacticalCombat.settle(session.copy(gameMillis=(now+30)*1000),world,nav,production,s.commit,scale=1,onCommand=events::add)
        assertTrue(events.single {it.order==pending}.cancelled)
        assertEquals(oldMotion.waypoints.last(),TacticalBasePosition.at(world.bases.single(),now+300,s.stocks))
    }

    @Test fun aMobileFortressWithBrokenPropulsionCannotMove() {
        // CD manual p.46: orbital attack can destroy a mobile fortress's propulsion (FortressArmor).
        val s=state();s.stocks=s.stocks.copy(tacticalCommands=s.stocks.tacticalCommands.copy(brokenPropulsion=setOf(1000)))
        assertEquals(NavigationRejection.BASE,accept(run(s),TacticalCommand(0x41f,base=1000,velocity=.5f,points=listOf(TacticalPoint(20f,0f)))).rejection)
    }
    @Test fun destroyedPropulsionHaltsAFortressAlreadyUnderWay() {
        // PR #214 review: an orbital strike breaking propulsion must also stop the active motion.
        val r=run();assertNotNull(accept(r,TacticalCommand(0x41f,base=1000,velocity=.5f,points=listOf(TacticalPoint(20f,0f)))).player)
        settle(r,now+20)
        r.state.stocks=r.state.stocks.copy(tacticalCommands=r.state.stocks.tacticalCommands.copy(brokenPropulsion=setOf(1000)))
        settle(run(r.state),now+21)
        val halted=TacticalBasePosition.at(galaxy.bases.single(),now+21,r.state.stocks)
        assertEquals(0f,r.state.stocks.tacticalCommands.fortresses.getValue(1000).motion.velocity)
        assertEquals(halted,TacticalBasePosition.at(galaxy.bases.single(),now+60,r.state.stocks))
    }
    @Test fun baseStopAlsoCancelsTheActualMovingFortressWithoutSnappingToItsSeed() {
        val r=run();assertNotNull(accept(r,TacticalCommand(0x41f,base=1000,velocity=.5f,points=listOf(TacticalPoint(20f,0f)))).player)
        settle(r,now+20)
        val current=TacticalBasePosition.at(galaxy.bases.single(),now+20,r.state.stocks)
        assertEquals(TacticalPoint(1.5f,0f),current) // five-second wait, then fifteen seconds at the server's .1
        val req=request(TacticalCommand(0x41e,base=1000)).copy(tacticalCommand=null,tacticalStop=TacticalStop(emptyList(),listOf(1000)))
        assertNotNull(TacticalSupportOperations.stop(req,session.copy(gameMillis=(now+20)*1000),galaxy,r.navigation,r.production,
            r.production.snapshot(1)!!,r.state.commit).player)
        assertEquals(current,TacticalBasePosition.at(galaxy.bases.single(),now+60,r.state.stocks))
    }
    @Test fun arrivingFortressKeepsTheAnchoredShipOffsetWhenItsPathIsReplacedByAStop() {
        val s=state();val outfit=s.stocks.outfits.getValue(1)
        val anchored=outfit.activeShips!!.values.map {if(it.id==1)it.copy(posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,1000)) else it}
        val fortress=motion().copy(velocity=.5f,waypoints=listOf(TacticalPoint(20f,0f)),waitSeconds=0)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(anchored))),
            tacticalCommands=TacticalCommandState(fortresses=mapOf(1000 to TacticalFortressMotion(2488,fortress))))
        val r=run(s);settle(r,now+40)
        val ship=s.stocks.outfits.getValue(1).activeShips!!.values.single {it.id==1}
        assertEquals(TacticalPoint(20f,.4f),ship.tactical!!.motion.pose(now+40,ship.posture,galaxy,s.stocks).position)
        assertEquals(TacticalPoint(20f,.4f),ship.tactical!!.motion.pose(now+60,ship.posture,galaxy,s.stocks).position)
        assertEquals(0f,s.stocks.tacticalCommands.fortresses.getValue(1000).motion.velocity)
    }
    @Test fun detainedAndDeceasedHigherRankOfficersCannotBlockTheFreeCommander() {
        val state = state()
        val ally = state.players.getValue("ally")
        val prisoner = ally.copy(baseId = 1000, mode = 4, spotId = 10000019, cpPaused = false,
            motion = null, commandRange = null)
        val deceased = prisoner.copy(characterId = 10009, spotId = 0)
        state.players = state.players + mapOf("ally" to prisoner, "deceased" to deceased)
        val detention = Detention(ally.characterId, Power.EMPIRE.country, now, now + Justice.DAY, base = 1000)
        state.stocks = state.stocks.copy(
            strategic = state.stocks.strategic.copy(people = mapOf(
                ally.characterId to StrategicPerson(rank = 1),
                deceased.characterId to StrategicPerson(rank = 1, deceasedAt = 1))),
            social = state.stocks.social.copy(justice = JusticeState(detentions = mapOf(ally.characterId to detention))),
        )
        val run = run(state)
        val order = TacticalCommand(0x422, base = 1000)

        assertEquals(NavigationRejection.BUSY, accept(run, order, "ally").rejection)
        assertEquals(prisoner, state.players.getValue("ally"))
        assertTrue(state.stocks.tacticalCommands.orders.isEmpty())

        val accepted = accept(run, order)
        assertNull(accepted.rejection)
        assertEquals(90, state.players.getValue("pilot").pcp)
        settle(run, assertNotNull(accepted.commandReadyAt))
        assertEquals(Power.EMPIRE.country.value, state.stocks.tacticalCommands.emergencySupplies[1000])
        assertTrue(state.stocks.tacticalCommands.orders.isEmpty())
    }

    @Test fun theLastEnemyCountryDefeatClosesEmergencySupplyAndPreventsReopening() {
        val r = run()
        val command = TacticalCommand(0x422, base = 1000)
        assertNotNull(accept(r, command).player)
        settle(r, now + 5)
        val stocked = r.state.stocks
        assertEquals(Power.EMPIRE.country.value, stocked.tacticalCommands.emergencySupplies[1000])
        val enemy = Power.ALLIANCE.country
        val defeated = session.copy(gameMillis = (now + 6) * 1000,
            countries = setOf(Power.EMPIRE.country, enemy, CountryId(42)),
            eliminated = mapOf(enemy to CountryDefeat(EndReason.SYSTEMS_REDUCED, now + 6)))
        val after = TacticalCommandOperations.advance(defeated, galaxy, r.state.players.toMutableMap(),
            stocked, 1, mutableListOf(), mutableListOf())
        assertTrue(after.tacticalCommands.emergencySupplies.isEmpty())
        assertEquals(stocked.bases, after.bases)
        assertEquals(stocked.outfits, after.outfits)
        r.production.replace(stocked, after)
        assertEquals(NavigationRejection.TACTICAL, TacticalCommandOperations.accept(request(command), defeated,
            galaxy, r.navigation, r.production, after, r.state.commit, 1).rejection)
    }

    @Test fun emergencySupplyOpensTheSelectedBaseAndDoesNotInventOrTeleportCargo() {
        val r=run();val before=r.state.stocks.bases.getValue(1000).supplies
        assertNotNull(accept(r,TacticalCommand(0x422,base=1000)).player);settle(r,now+5)
        assertEquals(2,r.state.stocks.tacticalCommands.emergencySupplies[1000])
        assertEquals(before,r.state.stocks.bases.getValue(1000).supplies)
        assertEquals(100,r.state.stocks.outfits.getValue(1).supplies)
        assertEquals(90,r.state.players.getValue("pilot").pcp)
        assertEquals(NavigationRejection.BUSY,accept(r,TacticalCommand(0x422,base=1000)).rejection)
    }
    @Test fun boardingRequiresAssaultFlagshipAndInjuresTheCaptainWithoutReplacingHisHull() {
        val s=state();val source=s.players.getValue("pilot").copy(hull=FlagshipHull(4,now))
        val target=Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,hull=FlagshipHull(89,now),motion=motion(0f,.5f))
        s.players=s.players+("pilot" to source)+("enemy" to target)
        val person=StrategicPerson(rank=10,abilities=List(8){50})
        val experience=List(8){20}
        s.stocks=s.stocks.copy(strategic=s.stocks.strategic.copy(people=mapOf(target.characterId to person)),
            social=s.stocks.social.copy(academy=AcademyState(experience=mapOf(target.characterId to experience))))
        val r=run(s);val command=TacticalCommand(0x407,listOf(source.unitId),target=target.unitId)
        val req=request(command).copy(shipKind=4)
        assertNotNull(TacticalCommandOperations.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        val at=(now+5..now+1000).first {BattleDeath.dies(target.characterId,it)}
        val hits=mutableListOf<TacticalImpact>();settle(r,at,impacts=hits)
        assertTrue(hits.single().boarding);assertEquals(0,hits.single().hit.destroyed)
        assertEquals(89,s.players.getValue("enemy").hull!!.kind)
        assertTrue(s.players.getValue("enemy").hull!!.woundedUntil>at)
        assertEquals(target.supplies,s.players.getValue("enemy").supplies)
        assertFalse(BattleDeath.deceased(s.stocks,target.characterId))
        assertEquals(experience,s.stocks.social.academy.experience[target.characterId])
    }

    @Test fun boardingCancelsTheInjuredCommandersAdmittedFleetRetreat() {
        val s = state()
        val source = s.players.getValue("pilot").copy(hull = FlagshipHull(4, now))
        val target = Navigator(1, 10002, Power.ALLIANCE, 2488, 100, 100, 1000, cpPaused = true,
            hull = FlagshipHull(89, now), motion = motion(0f, .5f))
        s.players = s.players + mapOf("pilot" to source, "enemy" to target)
        val retreat = TacticalRetreat(2489, now, 5, 150, membershipChecked = true)
        val fleet = s.stocks.outfits.getValue(2)
        val ship = fleet.activeShips!!.values.single().copy(motionCommander = target.characterId, supplies = 100,
            tactical = NpcTacticalState(motion(260f), now, now, retreat = retreat))
        val deployed = fleet.copy(commanderId = target.characterId, activeShips = ActiveShipUnits(listOf(ship)), supplies = 100)
        s.stocks = s.stocks.copy(outfits = s.stocks.outfits + (2 to deployed))

        val r = run(s)
        val boarding = request(TacticalCommand(0x407, listOf(source.unitId), target = target.unitId)).copy(shipKind = 4)
        assertNotNull(TacticalCommandOperations.accept(boarding, session, galaxy, r.navigation, r.production,
            r.production.snapshot(1)!!, s.commit, 1).player)
        val hits = settle(r, now + 5)
        assertTrue(hits.single().boarding)
        assertTrue(s.players.getValue("enemy").hull!!.woundedUntil > retreat.completesAt)
        assertEquals(retreat, s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.retreat)

        val movements = mutableListOf<TacticalMovement>()
        TacticalCombat.settle(session.copy(gameMillis = retreat.completesAt * 1000), galaxy, r.navigation,
            r.production, s.commit, scale = 1, onMovement = movements::add)
        val result = s.stocks.outfits.getValue(2)
        val remaining = result.activeShips!!.values.single()
        assertEquals(deployed.position, result.position)
        assertEquals(deployed.supplies, result.supplies)
        assertEquals(ship.supplies, remaining.supplies)
        assertEquals(ship.tactical!!.motion.origin.position, remaining.tactical!!.motion.origin.position)
        assertNull(remaining.tactical.retreat)
        assertTrue(movements.none { it.unit == ship.id })
    }

    @Test fun boardingRequiresSharedContactAtAcceptanceAndCompletion() {
        val s=state()
        val source=s.players.getValue("pilot").copy(hull=FlagshipHull(4,now),motion=motion(.5f))
        val target=Navigator(1,10002,Power.ALLIANCE,2488,100,100,1000,cpPaused=true,hull=FlagshipHull(89,now),motion=motion(.2f))
        val ally=s.players.getValue("ally")
        s.players=s.players+mapOf("pilot" to source,"enemy" to target)
        val base=s.stocks.bases.getValue(1000).copy(owner=Allegiance.Country(Power.ALLIANCE.country),garrisonCommanderId=0)
        s.stocks=s.stocks.copy(bases=mapOf(base.baseId to base))
        val req=request(TacticalCommand(0x407,listOf(source.unitId),target=target.unitId)).copy(shipKind=4)
        fun board(r:Run)=TacticalCommandOperations.accept(req,session,galaxy,r.navigation,r.production,
            r.production.snapshot(1)!!,s.commit,1)

        assertEquals(NavigationRejection.RANGE,board(run(s)).rejection)
        assertTrue(s.stocks.tacticalCommands.orders.isEmpty())
        s.players=s.players+("ally" to ally.copy(motion=motion(.2f)))
        val readyAt=assertNotNull(board(run(s)).commandReadyAt)

        s.players=s.players+("ally" to ally)
        val events=mutableListOf<TacticalCommandResult>()
        val impacts=mutableListOf<TacticalImpact>()
        settle(run(s),readyAt,events,impacts)
        assertTrue(events.single().cancelled)
        assertTrue(impacts.isEmpty())
        assertEquals(target.hull,s.players.getValue("enemy").hull)
    }
}
