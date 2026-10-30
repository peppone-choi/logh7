package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalGroundOperationsTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.EMPIRE,false,"current")}.exportState()
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),
        listOf(GalaxyBase(1000,2487,80,0,"Here",0,0f,86400,0,0f,2f),GalaxyBase(1001,2487,80,0,"Enemy",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private fun motion()=TacticalMotion(2487,now,TacticalPose(TacticalPoint(0f,0f),0f),0f,0f,listOf(TacticalPoint(0f,0f)))
    private class State(var players:Map<String,Navigator>,var stocks:PlanetStocks) {
        var fail=false;var saves=0
        val nav=object:NavigationStore {override fun load()=players;override fun save(players:Map<String,Navigator>){this@State.players=players}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(stocks:PlanetStocks){this@State.stocks=stocks}}
        val commit=WorldStateCommitter {p,s->if(fail)throw java.io.IOException("disk");saves++;players=p.toMap();stocks=checkNotNull(s)}
    }
    private fun state(defenders:Int=0):State {
        val ships=ActiveShipUnits(listOf(1,4).map {ActiveShipUnit(it,UnitStack(32),
            tactical=NpcTacticalState(motion(),now,now),posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1000))})
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),carrier=1),ActiveGroundUnit(3,UnitStack(1),carrier=1),
            ActiveGroundUnit(5,UnitStack(0),carrier=4))+(0 until defenders).map {ActiveGroundUnit(100+it,UnitStack(0),base=1000)})
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1000,10,10000,UnitInventory(ships.quantities(),troops.quantities()),
            activeShips=ships,activeGround=troops,position=OutfitPosition(2487))
        return State(mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,900,cpPaused=true,
            motion=motion(),hull=FlagshipHull(0,now))),PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0),1001 to PlanetStock(1001,3,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),nextShipId=1000))
    }
    private fun production(s:State)=PlanetProduction(galaxy,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
    private fun request(action:TacticalGroundAction=TacticalGroundAction.DEPLOY,ids:List<Int> = listOf(1))=
        NavigationRequest("pilot",10000,Power.EMPIRE,"current",1,shipKind=0,tacticalGround=TacticalGroundCommand(action,ids))
    private fun accept(s:State,n:Navigation,p:PlanetProduction,r:NavigationRequest=request(),time:Long=now)=
        TacticalGroundOperations.accept(r,session.copy(gameMillis=time*1000),galaxy,n,p,p.snapshot(1)!!,s.commit,24)
    private fun settle(s:State,n:Navigation,p:PlanetProduction,time:Long,events:MutableList<TacticalGroundResult>)=
        TacticalCombat.settle(session.copy(gameMillis=time*1000),galaxy,n,p,s.commit,onGround=events::add)
    @Test fun deployAndWithdrawWaitPersistAndKeepTheSameTroopIdentitiesWithoutDuplication() {
        val s=state();var n=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        val accepted=accept(s,n,p);assertNotNull(accepted.player);assertEquals(now+600,accepted.groundReadyAt)
        assertEquals(NavigationRejection.BUSY,accept(s,n,p).rejection)
        val events=mutableListOf<TacticalGroundResult>();settle(s,n,p,now+599,events);assertTrue(events.isEmpty())
        n=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        settle(s,n,p,now+600,events)
        assertEquals(listOf(2,3),events.single().troops);assertFalse(events.single().cancelled)
        assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}.all {it.base==1000 && it.origin==1 && it.carrier==0 && it.order==null})
        assertEquals(2000,TacticalBaseDefense.strength(s.stocks,1000))
        settle(s,n,p,now+700,events);assertEquals(1,events.size)
        // Withdrawal starts from the current clock and keeps the saved return carrier.
        assertNotNull(accept(s,n,p,request(TacticalGroundAction.WITHDRAW),now+700).player)
        settle(s,n,p,now+1300,events);assertEquals(2,events.size)
        assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.filter {it.id in 2..3}.all {it.carrier==1 && it.base==0 && it.origin==0})
        assertEquals(0,TacticalBaseDefense.strength(s.stocks,1000));assertEquals(1000,s.stocks.nextShipId)
    }
    @Test fun capacityIncludesOtherAcceptedLandingsAndMixedInvalidSelectionDoesNotPartiallySave() {
        val s=state(28);val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks
        assertEquals(NavigationRejection.UNIT,accept(s,n,p,request(ids=listOf(1,999))).rejection);assertEquals(before,s.stocks)
        assertNotNull(accept(s,n,p).player)
        assertEquals(NavigationRejection.GRID,accept(s,n,p,request(ids=listOf(4))).rejection)
        assertNull(s.stocks.outfits.getValue(1).activeGround!!.values.single {it.id==5}.order)
    }
    @Test fun forgedIdentityConnectionAndNonzeroSkillCannotStartLanding() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);val before=s.stocks
        assertEquals(NavigationRejection.IDENTITY,accept(s,n,p,request().copy(characterId=10001)).rejection)
        assertEquals(NavigationRejection.SESSION,accept(s,n,p,request().copy(connection="old")).rejection)
        assertEquals(NavigationRejection.ACCESS,accept(s,n,p,request().copy(tacticalGround=TacticalGroundCommand(TacticalGroundAction.DEPLOY,listOf(1),1))).rejection)
        assertEquals(before,s.stocks);assertEquals(0,s.saves)
    }
    @Test fun stopClearsTheSameReservationAtomicallyAndNoTroopsMoveLater() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);assertNotNull(accept(s,n,p).player)
        val stopped=TacticalSupportOperations.stop(request().copy(tacticalGround=null,tacticalStop=TacticalStop(listOf(1))),
            session,galaxy,n,p,p.snapshot(1)!!,s.commit)
        assertNotNull(stopped.player)
        assertTrue(s.stocks.outfits.getValue(1).activeGround!!.values.all {it.order==null})
        val events=mutableListOf<TacticalGroundResult>();settle(s,n,p,now+600,events);assertTrue(events.isEmpty())
        assertEquals(1,s.stocks.outfits.getValue(1).activeGround!!.values.single {it.id==2}.carrier)
    }
    @Test fun storageFailurePublishesNeitherAcceptanceNorCompletionAndRetryExecutesOnce() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);val before=s.stocks
        s.fail=true;assertFailsWith<java.io.IOException>{accept(s,n,p)};assertEquals(before,p.snapshot(1))
        s.fail=false;assertNotNull(accept(s,n,p).player);val pending=p.snapshot(1)
        val events=mutableListOf<TacticalGroundResult>();s.fail=true
        assertFailsWith<java.io.IOException>{settle(s,n,p,now+600,events)};assertEquals(pending,p.snapshot(1));assertTrue(events.isEmpty())
        s.fail=false;settle(s,n,p,now+600,events);assertEquals(1,events.size)
    }
    @Test fun commanderLossCancelsWithoutInventingALandedOrEmbarkedPosition() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);assertNotNull(accept(s,n,p).player)
        val changed=s.stocks.copy(outfits=s.stocks.outfits.mapValues {(_,o)->o.copy(commanderId=10001)})
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(session,galaxy,s.players,changed,events)
        assertTrue(events.single().cancelled);assertEquals(1,result.outfits.getValue(1).activeGround!!.values.single {it.id==2}.carrier)
        assertTrue(result.outfits.getValue(1).activeGround!!.values.all {it.order==null})
    }
    @Test fun stationedShipWithoutMotionCannotUseFormationCoordinatesToEvadeTheCommandRange() {
        val s=state();val old=s.stocks.outfits.getValue(1)
        s.stocks=s.stocks.copy(outfits=mapOf(1 to old.copy(activeShips=ActiveShipUnits(old.activeShips!!.values.map {
            if(it.id==1)it.copy(tactical=null,posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1000)) else it
        }))))
        val far=Galaxy(galaxy.systems,galaxy.bases.map {if(it.id==1000)it.copy(radius=100f,angle=90f) else it},galaxy.starts)
        val n=Navigation(far,s.nav,NavigationPolicy());val p=production(s);val before=s.stocks
        val result=TacticalGroundOperations.accept(request(),session,far,n,p,p.snapshot(1)!!,s.commit,24)
        assertEquals(NavigationRejection.RANGE,result.rejection);assertEquals(before,s.stocks)
    }
    @Test fun missingBattlefieldBaseCancelsAPersistedLandingWithoutMovingTroops() {
        val s=state();val n=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s);assertNotNull(accept(s,n,p).player)
        val changed=Galaxy(galaxy.systems,galaxy.bases.filterNot {it.id==1000},galaxy.starts)
        val events=mutableListOf<TacticalGroundResult>()
        val result=TacticalGroundOperations.advance(session.copy(gameMillis=(now+600)*1000),changed,s.players,s.stocks,events)
        assertTrue(events.single().cancelled);assertEquals(1,result.outfits.getValue(1).activeGround!!.values.single {it.id==2}.carrier)
    }
}
