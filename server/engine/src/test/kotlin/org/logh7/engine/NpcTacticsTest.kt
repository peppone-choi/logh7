package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcTacticsTest {
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
        state.players=mapOf("pilot" to player.copy(power=Power.EMPIRE,hull=FlagshipHull(0,now)))
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
