package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalCommandOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {
        it.join("pilot",Power.EMPIRE,false,"one");it.join("ally",Power.EMPIRE,false,"two")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Battle",2)),
        listOf(GalaxyBase(1000,2488,80,1,"Fortress",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2488})
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
            (2 to enemy.copy(activeShips=ActiveShipUnits(listOf(target)),units=UnitInventory(mapOf(UnitStack(carrier) to 1)))),
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
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(activeShips=ActiveShipUnits(listOf(target)),
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
    @Test fun cdAirButtonInterceptAtAHostileUnitBecomesTheAntiShipLaunch() {
        // CD air button sends kind 0 only; a hostile target means the anti-ship attack.
        val s=state();val r=run(s);assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=0,target=99)).player)
        assertEquals(1,s.stocks.tacticalCommands.orders.single().command.kind)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits)
        assertEquals(90,s.stocks.outfits.getValue(1).supplies);assertTrue(hits.single().airBattle)
    }
    @Test fun carrierLaunchSpendsExactlyTenAndLandingRestoresTargetSpeed() {
        val s=state();val foe=s.stocks.outfits.getValue(2)
        val moving=foe.activeShips!!.values.single().let {it.copy(tactical=it.tactical!!.copy(motion=motion(0f,1.5f).copy(velocity=.5f,waypoints=listOf(TacticalPoint(0f,40f)))))}
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to foe.copy(activeShips=ActiveShipUnits(listOf(moving)))))
        val r=run(s);assertNotNull(accept(r,TacticalCommand(0x40e,listOf(1),kind=1,target=99)).player)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits)
        assertEquals(90,s.stocks.outfits.getValue(1).supplies);assertTrue(hits.single().airBattle)
        assertEquals(.25f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
        // CRC regrowth is required for another squadron command.
        val later=session.copy(gameMillis=(now+20)*1000)
        assertNotNull(TacticalCommandOperations.accept(request(TacticalCommand(0x40e,listOf(1),kind=2)),later,galaxy,
            r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        settle(r,now+25)
        assertTrue(s.stocks.tacticalCommands.air.isEmpty());assertEquals(90,s.stocks.outfits.getValue(1).supplies)
        assertEquals(.5f,s.stocks.outfits.getValue(2).activeShips!!.values.single().tactical!!.motion.velocity)
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
    @Test fun missionInterceptionCreditsTheActualCommanderEvenWhenItsLastEnemyDies() {
        val s=state();val enemy=s.stocks.outfits.getValue(2);val target=enemy.activeShips!!.values.single()
        val maximum=UnitSpecifications.ships.single {it.kind==target.stack.kind}.number
        val dead=target.copy(boats=0,damaged=0)
        val stock=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(activeShips=ActiveShipUnits(listOf(dead)))),
            tacticalCommands=TacticalCommandState(missions=mapOf(10000 to TacticalMissionState(10000,3,1,99,now,grid=2488))))
        val impacts=mutableListOf(TacticalImpact(1,1010000,TacticalWeapon.BEAM,TacticalHit(1,2488,now,99,maximum),emptyMap()))
        val events=mutableListOf<TacticalCommandResult>();val players=s.players.toMutableMap()
        val next=TacticalCommandOperations.finishMissions(session,galaxy,players,stock,1,events,impacts)
        assertEquals(30L,next.strategic.people.getValue(10000).merit)
        assertEquals(30L,next.tacticalCommands.creditedMerits[10000]);assertTrue(next.tacticalCommands.missions.getValue(10000).completed)
        assertTrue(events.single().stateOnly);assertEquals(mapOf(10000 to 30L),events.single().merits)
        assertEquals(next,TacticalCommandOperations.finishMissions(session,galaxy,players,next,1,mutableListOf(),impacts))
        val another=impacts.map {it.copy(source=1010001)}.toMutableList()
        assertTrue(TacticalCommandOperations.finishMissions(session,galaxy,players,stock,1,mutableListOf(),another)
            .tacticalCommands.merits.isEmpty())
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
    @Test fun baseStopAlsoCancelsTheActualMovingFortressWithoutSnappingToItsSeed() {
        val r=run();assertNotNull(accept(r,TacticalCommand(0x41f,base=1000,velocity=.5f,points=listOf(TacticalPoint(20f,0f)))).player)
        settle(r,now+20)
        val current=TacticalBasePosition.at(galaxy.bases.single(),now+20,r.state.stocks)
        assertEquals(TacticalPoint(7.5f,0f),current) // five-second wait, then fifteen seconds at .5
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
        val r=run(s);val command=TacticalCommand(0x407,listOf(source.unitId),target=target.unitId)
        val req=request(command).copy(shipKind=4)
        assertNotNull(TacticalCommandOperations.accept(req,session,galaxy,r.navigation,r.production,r.production.snapshot(1)!!,s.commit,1).player)
        val hits=mutableListOf<TacticalImpact>();settle(r,now+5,impacts=hits)
        assertTrue(hits.single().boarding);assertEquals(0,hits.single().hit.destroyed)
        assertEquals(89,s.players.getValue("enemy").hull!!.kind)
        assertTrue(s.players.getValue("enemy").hull!!.woundedUntil>now+5)
        assertEquals(target.supplies,s.players.getValue("enemy").supplies)
    }
}
