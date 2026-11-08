package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcTacticsTest {
    @Test fun advisorCanChooseOnlyAnEnemyInTheCurrentWeaponArc() {
        for (choice in listOf(null, 1010001, 1010002)) {
            val state = state()
            val pilot = state.players.getValue("pilot")
            fun enemy(id: Int, point: TacticalPoint) = pilot.copy(characterId = id,
                motion = TacticalMotion(2488, now, TacticalPose(point, 0f), 0f, 0f, listOf(point)))
            val players = (state.players + ("second" to enemy(10001, TacticalPoint(-181f, -94.5f))) +
                ("out-of-range" to enemy(10002, TacticalPoint(-190f, -95f)))).toMutableMap()
            val outfit = state.stocks.outfits.getValue(1)
            val point = TacticalPoint(-178f, -95f)
            val facing = (-Math.PI / 2).toFloat()
            val motion = TacticalMotion(2488, now, TacticalPose(point, facing), 0f, facing, listOf(point))
            val unit = outfit.activeShips!!.values.single().copy(tactical = NpcTacticalState(motion, now + 1, now))
            val stocks = state.stocks.copy(outfits = mapOf(1 to outfit.copy(activeShips = ActiveShipUnits(listOf(unit)))))
            val impacts = mutableListOf<TacticalImpact>()
            val observed = mutableListOf<NpcFireDecision>()
            val policy = NpcTacticalPolicy(chooseFireTarget = { observed += it; choice })
            NpcTactics.advance(session, galaxy, players, stocks, policy, 24, impacts, mutableListOf())
            val beam = observed.single { it.weapon == TacticalWeapon.BEAM }
            assertEquals(listOf(1010000, 1010001), beam.targets.map { it.id })
            assertEquals(if (choice == 1010001) choice else 1010000, impacts.first().hit.target)
        }
    }

    @Test fun twoNpcUnitsSpendTheirOwnPersistedCargoInsteadOfDrainingAnotherUnit() {
        val state=state()
        val original=state.stocks.outfits.getValue(1)
        val point=TacticalPoint(-178f,-95f)
        val heading=(-Math.PI/2).toFloat()
        val ai=NpcTacticalState(TacticalMotion(2488,now,TacticalPose(point,heading),0f,heading,listOf(point)),now,now)
        val stack=UnitStack(32)
        val outfit=OutfitSupplies.withBalances(original.copy(units=UnitInventory(mapOf(stack to 2)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,tactical=ai),ActiveShipUnit(2,stack,tactical=ai)))),
            mapOf(1 to 5000,2 to 5000))
        state.stocks=state.stocks.copy(outfits=mapOf(1 to outfit),nextShipId=3)
        val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
        val hits=settle(now,nav,stock,state,mutableListOf())
        assertEquals(6,hits.size)
        assertEquals(mapOf(1 to 3,2 to 3),hits.groupingBy {it.source}.eachCount())
        val saved=state.stocks.outfits.getValue(1)
        assertEquals(6400,saved.supplies)
        assertEquals(mapOf(1 to 3200,2 to 3200),OutfitSupplies.distribution(saved))
        assertEquals(listOf(3200,3200),saved.activeShips!!.values.map {it.supplies})
        assertTrue(settle(now,nav,stock,state,mutableListOf()).isEmpty())
        assertEquals(saved,state.stocks.outfits.getValue(1))
    }

    @Test fun autonomousUnitsFightEachOtherAndEmptyAmmoStillAllowsBeamOnly() {
        val state=state();val empire=state.stocks.outfits.getValue(1);val allianceKind=ProductionUnits.definitions.first {it.ship && it.power==3 && it.name=="戦艦Ⅰ"}.kind
        fun ai(x:Float,direction:Float)=NpcTacticalState(TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,-95f),direction),
            0f,direction,listOf(TacticalPoint(x,-95f))),now,now)
        val stack=UnitStack(allianceKind)
        state.stocks=state.stocks.copy(outfits=mapOf(1 to empire.copy(activeShips=ActiveShipUnits(listOf(
            empire.activeShips!!.values.single().copy(tactical=ai(180f,0f))))),2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,
                units=UnitInventory(mapOf(stack to 1)),supplies=10000,activeShips=ActiveShipUnits(listOf(ActiveShipUnit(2,stack,300,ai(182f,0f)))),position=OutfitPosition(2488))),
            warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),nextShipId=3)
        val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state);val moves=mutableListOf<TacticalMovement>()
        val hits=settle(now,nav,stock,state,moves)
        assertEquals(6,hits.size);assertEquals(setOf(1,2),hits.map {it.source}.toSet());assertTrue(hits.all {it.hit.target in 1..2})
        assertTrue(state.stocks.outfits.values.all {it.activeShips!!.values.single().boats<300})
        val empty=state();val unit=empty.stocks.outfits.getValue(1).activeShips!!.values.single()
        empty.stocks=empty.stocks.copy(outfits=empty.stocks.outfits.mapValues {(_,o)->o.copy(supplies=0,
            activeShips=ActiveShipUnits(listOf(unit.copy(tactical=ai(-178f,0f)))) )})
        val emptyNav=Navigation(galaxy,empty.nav,NavigationPolicy());val emptyStock=production(empty)
        assertEquals(listOf(TacticalWeapon.BEAM),settle(now,emptyNav,emptyStock,empty,moves).map {it.weapon})
        assertEquals(0,empty.stocks.outfits.getValue(1).supplies)
    }
    @Test fun strategicFireKeepsTheWholeReturnTripAndThenReloadsAtTheRealDepot() {
        for(jumps in listOf(1,3)) {
            val state=state();val grid=2487+jumps
            val point=TacticalPoint(-178f,-95f);val heading=(-Math.PI/2).toFloat()
            val ai=NpcTacticalState(TacticalMotion(grid,now,TacticalPose(point,heading),0f,heading,listOf(point)),now,now)
            state.players=state.players.mapValues {(_,p)->p.copy(grid=grid,motion=p.motion!!.copy(grid=grid))}
            val original=state.stocks.outfits.getValue(1)
            var stocks=state.stocks.copy(bases=state.stocks.bases.mapValues {(_,b)->b.copy(supplies=100000)},
                outfits=mapOf(1 to OutfitSupplies.withBalances(original.copy(position=OutfitPosition(grid),
                    activeShips=ActiveShipUnits(original.activeShips!!.values.map {it.copy(tactical=ai)})),mapOf(1 to 2700))))
            val players=state.players.toMutableMap();val hits=mutableListOf<TacticalImpact>()
            val moves=mutableListOf<TacticalMovement>()
            fun sessionAt(at:Long)=session.copy(gameMillis=at*1000)
            stocks=NpcTactics.advance(sessionAt(now),galaxy,players,stocks,NpcTacticalPolicy(strategic=true),24,hits,moves)
            assertEquals(TacticalWeapon.entries,hits.map {it.weapon})
            assertEquals(900,stocks.outfits.getValue(1).supplies)
            for(pulse in 1..3) {
                hits.clear()
                stocks=NpcTactics.advance(sessionAt(now+480*pulse),galaxy,players,stocks,NpcTacticalPolicy(strategic=true),24,hits,moves)
                assertEquals(if(jumps==1 && pulse<3)listOf(TacticalWeapon.BEAM,TacticalWeapon.GUN) else
                    listOf(TacticalWeapon.BEAM),hits.map {it.weapon})
                assertTrue(stocks.outfits.getValue(1).supplies>=jumps*300)
            }
            assertEquals(jumps*300,stocks.outfits.getValue(1).supplies)
            var at=now+1440
            val travel=mutableListOf<NpcStrategicMovement>()
            repeat(jumps) {
                stocks=NpcStrategy.advance(sessionAt(at),galaxy,players,stocks,travel)
                assertNotNull(stocks.outfits.getValue(1).strategy!!.destination)
                at+=3600
                stocks=NpcStrategy.advance(sessionAt(at),galaxy,players,stocks,travel)
                at+=21600
            }
            assertEquals(OutfitPosition(2487,1000),stocks.outfits.getValue(1).position)
            assertEquals(3000,stocks.outfits.getValue(1).supplies)
            assertEquals(97000,stocks.bases.getValue(1000).supplies)
            assertEquals(jumps,travel.size)
        }
    }
    @Test fun strandedStrategicFleetUsesNormalRetreatThenDocksWithoutReplayingItsOldJump() {
        val state=state();val original=state.stocks.outfits.getValue(1)
        val before=state.stocks.copy(bases=state.stocks.bases.mapValues {(_,b)->b.copy(supplies=100000)},
            outfits=mapOf(1 to OutfitSupplies.withBalances(original.copy(
                strategy=NpcStrategicState(now+21600,2487,now+3600)),mapOf(1 to 0))))
        val players=state.players.toMutableMap();val hits=mutableListOf<TacticalImpact>();val moves=mutableListOf<TacticalMovement>()
        val preparing=NpcTactics.advance(session,galaxy,players,before,NpcTacticalPolicy(strategic=true),24,hits,moves)
        val retreat=assertNotNull(preparing.outfits.getValue(1).activeShips!!.values.single().tactical!!.retreat)
        assertEquals(now+3720,retreat.completesAt);assertEquals(2487,retreat.destination)
        assertEquals(before.bases,preparing.bases);assertEquals(0,preparing.outfits.getValue(1).supplies)
        val escaped=NpcTactics.advance(session.copy(gameMillis=retreat.completesAt*1000),galaxy,players,
            preparing,NpcTacticalPolicy(strategic=true),24,hits,moves)
        assertEquals(OutfitPosition(2487),escaped.outfits.getValue(1).position)
        assertNull(escaped.outfits.getValue(1).strategy)
        assertEquals(0,escaped.outfits.getValue(1).supplies)
        val docked=NpcStrategy.advance(session.copy(gameMillis=retreat.completesAt*1000),galaxy,players,escaped,mutableListOf())
        assertEquals(OutfitPosition(2487,1000),docked.outfits.getValue(1).position)
        assertEquals(3000,docked.outfits.getValue(1).supplies)
        assertEquals(97000,docked.bases.getValue(1000).supplies)
    }
    @Test fun aReservedJumpCannotInterruptAnActiveTacticalWithdrawal() {
        val state=state(75);val original=state.stocks.outfits.getValue(1)
        val stocks=state.stocks.copy(outfits=mapOf(1 to OutfitSupplies.withBalances(original.copy(
            strategy=NpcStrategicState(now+21600,2487,now+3600)),mapOf(1 to 300))))
        val players=state.players.toMutableMap();val moves=mutableListOf<TacticalMovement>()
        val preparing=NpcTactics.advance(session,galaxy,players,stocks,NpcTacticalPolicy(strategic=true),24,mutableListOf(),moves)
        val retreat=assertNotNull(preparing.outfits.getValue(1).activeShips!!.values.single().tactical!!.retreat)
        assertTrue(retreat.completesAt>now+3600)
        val travel=mutableListOf<NpcStrategicMovement>()
        val beforeExit=NpcStrategy.advance(session.copy(gameMillis=(now+3600)*1000),galaxy,players,preparing,travel)
        assertEquals(OutfitPosition(2488),beforeExit.outfits.getValue(1).position)
        assertEquals(300,beforeExit.outfits.getValue(1).supplies)
        assertEquals(preparing.bases,beforeExit.bases)
        assertEquals(retreat,beforeExit.outfits.getValue(1).activeShips!!.values.single().tactical!!.retreat)
        assertNull(beforeExit.outfits.getValue(1).strategy);assertTrue(travel.isEmpty())
    }
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.ALLIANCE,false,"current")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state(boats:Int=300):State {
        val p=TacticalPoint(-180f,-95f)
        val player=Navigator(1,10000,Power.ALLIANCE,2488,1000,1000,900,cpPaused=true,hull=FlagshipHull(89,now),
            motion=TacticalMotion(2488,now,TacticalPose(p,0f),0f,0f,listOf(p)))
        val stack=UnitStack(32)
        return State(mapOf("pilot" to player),PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),supplies=10000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,boats))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2))
    }
    private fun production(state:State)=PlanetProduction(galaxy,state.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
    private fun settle(at:Long,nav:Navigation,production:PlanetProduction,state:State,moves:MutableList<TacticalMovement>)=
        TacticalCombat.settle(session.copy(gameMillis=at*1000),galaxy,nav,production,state.commit,NpcTacticalPolicy(),24,moves::add)

    @Test fun detectsApproachesFacesAndFiresUsingSharedDamageThenReopensWithoutReplay() {
        val state=state();var nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state);val moves=mutableListOf<TacticalMovement>()
        assertTrue(settle(now,nav,stock,state,moves).isEmpty())
        val first=state.stocks.outfits.getValue(1).activeShips!!.values.single().tactical!!
        assertEquals(1010000,first.target);assertEquals(TacticalPoint(-178f,-95f),first.motion.waypoints.single())
        assertEquals(1,moves.size);assertEquals(mapOf(10000 to "current"),moves.single().recipients)
        assertTrue(first.motion.pose(now+50).position.x<first.motion.origin.position.x)
        nav=Navigation(galaxy,state.nav,NavigationPolicy())
        val hits=settle(now+480,nav,stock,state,moves)
        assertEquals(TacticalWeapon.entries,hits.map {it.weapon});assertTrue(hits.all {it.source==1 && it.hit.target==1010000})
        assertEquals(8200,state.stocks.outfits.getValue(1).supplies)
        assertTrue(state.players.getValue("pilot").hull!!.levels().any {it<FlagshipHull.capacity(89)})
        assertTrue(settle(now+480,nav,stock,state,moves).isEmpty())
        nav=Navigation(galaxy,state.nav,NavigationPolicy())
        assertTrue(settle(now+600,nav,stock,state,moves).isEmpty())
        assertEquals(3,settle(now+960,nav,stock,state,moves).size)
        assertEquals(6,state.stocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.sequence)
        assertEquals(6400,state.stocks.outfits.getValue(1).supplies)
    }
    @Test fun disabledSurvivorsCannotFireAndOnlyHealthyBoatsConsumeAmmunition() {
        for(disabled in listOf(150,300)) {
            val state=state();val outfit=state.stocks.outfits.getValue(1)
            state.stocks=state.stocks.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(
                outfit.activeShips!!.values.map {it.copy(damaged=disabled)}))))
            val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
            val moves=mutableListOf<TacticalMovement>()
            settle(now,nav,stock,state,moves)
            val hits=settle(now+480,nav,stock,state,moves)
            assertEquals(if(disabled==300)0 else 3,hits.size)
            assertEquals(10000-(300-disabled)*6,state.stocks.outfits.getValue(1).supplies)
        }
    }
    @Test fun damagedOutfitMovesOutsideRadarPreparesAndExitsToLegalAdjacentGrid() {
        val state=state(75);val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state);val moves=mutableListOf<TacticalMovement>()
        settle(now,nav,stock,state,moves)
        val withdrawal=state.stocks.outfits.getValue(1).activeShips!!.values.single().tactical!!.retreat!!
        assertEquals(2487,withdrawal.destination);assertEquals(now+3720,withdrawal.completesAt)
        assertTrue(settle(now+3719,nav,stock,state,moves).isEmpty());assertEquals(2488,state.stocks.outfits.getValue(1).position!!.grid)
        assertTrue(settle(now+3720,nav,stock,state,moves).isEmpty())
        assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
        assertNull(state.stocks.outfits.getValue(1).activeShips!!.values.single().tactical)
        assertEquals(2487,moves.last().destination);assertEquals(10000,moves.last().recipients.keys.single())
    }
    @Test fun whollyConfusedFleetWithdrawsButOneConfusedUnitDoesNotWithdrawItsHealthyPeers() {
        for(allConfused in listOf(false,true)) {
            val state=state();val outfit=state.stocks.outfits.getValue(1)
            val unit=outfit.activeShips!!.values.single()
            state.stocks=state.stocks.copy(nextShipId=3,outfits=mapOf(1 to outfit.copy(
                units=UnitInventory(mapOf(unit.stack to 2)),
                activeShips=ActiveShipUnits(listOf(unit,unit.copy(id=2))))),
                tacticalCommands=TacticalCommandState(morale=if(allConfused)mapOf(1 to 20,2 to 0) else mapOf(1 to 0)))
            val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
            val moves=mutableListOf<TacticalMovement>()
            assertTrue(settle(now,nav,stock,state,moves).isEmpty())
            val ships=state.stocks.outfits.getValue(1).activeShips!!.values
            assertEquals(allConfused,ships.all {it.tactical?.retreat!=null})
            if(allConfused) {
                assertEquals(300,ships[0].boats)
                val target=checkNotNull(ships[0].tactical?.retreat).destination
                assertTrue(settle(now+3719,nav,stock,state,moves).isEmpty())
                assertEquals(OutfitPosition(2488),state.stocks.outfits.getValue(1).position)
                assertTrue(settle(now+3720,nav,stock,state,moves).isEmpty())
                val departed=state.stocks.outfits.getValue(1)
                assertEquals(OutfitPosition(target),departed.position)
                assertTrue(departed.activeShips!!.values.all {it.tactical==null && it.boats==300})
                assertEquals(setOf(1,2),moves.filter {it.destination==target}.map {it.unit}.toSet())
                assertEquals(mapOf(1 to 20,2 to 0),state.stocks.tacticalCommands.morale)
            }
        }
    }
    @Test fun failedMovementOrShotSavePublishesNeitherMovementNorDamageAndRetryIsSingle() {
        val state=state();val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state);val moves=mutableListOf<TacticalMovement>()
        val initial=state.stocks;state.fail=true
        assertFailsWith<java.io.IOException>{settle(now,nav,stock,state,moves)}
        assertEquals(initial,state.stocks);assertTrue(moves.isEmpty())
        state.fail=false;settle(now,nav,stock,state,moves);moves.clear()
        val before=state.stocks;val hull=state.players.getValue("pilot").hull
        state.fail=true
        assertFailsWith<java.io.IOException>{settle(now+480,nav,stock,state,moves)}
        assertEquals(before,stock.snapshot(1));assertEquals(hull,nav.snapshot(1).getValue("pilot").hull);assertTrue(moves.isEmpty())
        state.fail=false;assertEquals(3,settle(now+480,nav,stock,state,moves).size)
        assertTrue(settle(now+480,nav,stock,state,moves).isEmpty())
    }
    @Test fun friendlyUnitsAndCommandedOutfitsAreNotAutonomousAndTakingCommandStopsSavedPath() {
        val state=state();val player=state.players.getValue("pilot")
        state.players=mapOf("pilot" to player.copy(country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE),hull=FlagshipHull(0,now)))
        var nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state);val moves=mutableListOf<TacticalMovement>()
        assertTrue(settle(now,nav,stock,state,moves).isEmpty());assertTrue(moves.isEmpty())
        state.players=mapOf("pilot" to player);nav=Navigation(galaxy,state.nav,NavigationPolicy())
        settle(now,nav,stock,state,moves)
        val before=stock.snapshot(1)!!
        stock.replace(before,before.copy(outfits=before.outfits.mapValues {(_,o)->o.copy(commanderId=10002)}))
        settle(now+240,nav,stock,state,moves)
        val stopped=state.stocks.outfits.getValue(1).activeShips!!.values.single().tactical!!
        assertEquals(0f,stopped.motion.velocity);assertEquals(0,stopped.target)
        assertEquals(10000,state.stocks.outfits.getValue(1).supplies)
        assertTrue(settle(now+480,nav,stock,state,moves).isEmpty())
    }
}
