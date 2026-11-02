package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalSupportTest {
    @Test fun supplyUsesCurrentOrbitalPositionsForCommanderSourceAndTargetAfterIndependentAnchorTimes() {
        val at=now+1000;val timed=session.copy(gameMillis=at*1000)
        val moving=Galaxy(galaxy.systems,galaxy.bases.map {it.copy(grid=2488,radius=20f,cycle=1000)},galaxy.starts)
        val base=moving.bases.single();val posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,base.id)
        fun anchored(at:Long,offset:Float):TacticalMotion {
            val point=TacticalBasePosition.at(base,at).let {TacticalPoint(it.x,it.y+offset)}
            return TacticalMotion(2488,at,TacticalPose(point,0f),0f,0f,listOf(point))
        }
        val s=state();s.players=s.players.mapValues {(_,v)->v.copy(posture=posture,motion=anchored(at-750,.5f))}
        val own=s.stocks.outfits.getValue(1)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(activeShips=ActiveShipUnits(own.activeShips!!.values.map {
            it.copy(posture=posture,tactical=NpcTacticalState(anchored(at-if(it.id==1)250 else 500,if(it.id==1)0f else .8f),at,at))}))))
        val n=Navigation(moving,s.nav,NavigationPolicy())
        val p=PlanetProduction(moving,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,at)}
        assertNotNull(TacticalSupportOperations.accept(request(TacticalSupportAction.SUPPLY,2),timed,moving,n,p,p.snapshot(1)!!,s.commit,24).player)
        val out=mutableListOf<TacticalSupportResult>()
        TacticalCombat.settle(session.copy(gameMillis=(at+120)*1000),moving,n,p,s.commit,onSupport=out::add)
        assertFalse(out.single().cancelled);assertEquals(10000,out.single().targetSupplies)
        assertEquals(0,OutfitSupplies.distribution(s.stocks.outfits.getValue(1)).getValue(1))
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
            hull=FlagshipHull(0,now,hp=40),motion=ai(0.5f).motion)
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
    private fun request(action:TacticalSupportAction,target:Int)=NavigationRequest("pilot",10000,Power.EMPIRE,"current",1,
        shipKind=0,tacticalSupport=TacticalSupport(action,1,target))
    private fun accept(s:State,nav:Navigation,p:PlanetProduction,r:NavigationRequest,at:Long=now)=
        TacticalSupportOperations.accept(r,session.copy(gameMillis=at*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit,24)
    private fun settle(s:State,nav:Navigation,p:PlanetProduction,at:Long,out:MutableList<TacticalSupportResult>)=
        TacticalCombat.settle(session.copy(gameMillis=at*1000),galaxy,nav,p,s.commit,onSupport=out::add)

    @Test fun supplyIsDelayedAtomicConservedAndNotRepeatedAfterReload() {
        val s=state();var nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val accepted=accept(s,nav,p,request(TacticalSupportAction.SUPPLY,1010000))
        assertNotNull(accepted.player);assertEquals(now+120,accepted.supportReadyAt)
        assertEquals(400,s.players.getValue("pilot").supplies)
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
        assertEquals(NavigationRejection.BUSY,accept(s,nav,p,request(TacticalSupportAction.SUPPLY,2)).rejection)
        val out=mutableListOf<TacticalSupportResult>()
        settle(s,nav,p,now+119,out);assertTrue(out.isEmpty())
        nav=Navigation(galaxy,s.nav,NavigationPolicy())
        settle(s,nav,p,now+120,out)
        assertEquals(10000,s.players.getValue("pilot").supplies)
        assertEquals(400,s.stocks.outfits.getValue(1).supplies)
        assertEquals(mapOf(1 to 400,2 to 0),OutfitSupplies.distribution(s.stocks.outfits.getValue(1)))
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.first().support)
        assertEquals(1,out.size);assertEquals(10000,out.single().targetSupplies)
        assertEquals(mapOf(10000 to "current"),out.single().recipients)
        settle(s,nav,p,now+240,out);assertEquals(1,out.size)
    }

    @Test fun supplyBetweenUnitsOfTheSameFleetDoesNotChangeItsTotal() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertNotNull(accept(s,nav,p,request(TacticalSupportAction.SUPPLY,2)).player)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
        assertEquals(mapOf(1 to 0,2 to 10000),OutfitSupplies.distribution(s.stocks.outfits.getValue(1)))
        assertEquals(250,s.stocks.outfits.getValue(1).activeShips!!.values.last().boats)
        assertEquals(30,s.stocks.outfits.getValue(1).activeShips!!.values.last().damaged)
    }

    @Test fun explicitStopCancelsPendingSupportWithoutSpendingAndForeignStopIsRejected() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val command=request(TacticalSupportAction.SUPPLY,2)
        accept(s,nav,p,command)
        val stop=command.copy(tacticalSupport=null,tacticalStop=TacticalStop(listOf(1)))
        assertEquals(NavigationRejection.CARD,TacticalSupportOperations.stop(stop.copy(tacticalStop=TacticalStop(listOf(99))),
            session,galaxy,nav,p,p.snapshot(1)!!,s.commit).rejection)
        assertEquals(NavigationRejection.RANGE,TacticalSupportOperations.stop(stop,session,galaxy,nav,p,p.snapshot(1)!!,s.commit).rejection)
        assertNotNull(TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+8)*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit).player)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+240,out)
        assertTrue(out.isEmpty());assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.first().support)
    }

    @Test fun supplyAcrossFriendlyFleetsConservesCombinedCargoAndLeavesEnemyStockAlone() {
        val s=state();val source=s.stocks.outfits.getValue(1)
        val receiver=source.activeShips!!.values.last()
        val remaining=ActiveShipUnits(source.activeShips.values.filter {it.id!=receiver.id})
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to source.copy(activeShips=remaining,
            units=UnitInventory(remaining.quantities())))+(3 to OutfitStock(3,2,OutfitKind.FLEET,1000,30,
            units=UnitInventory(mapOf(receiver.stack to 1)),activeShips=ActiveShipUnits(listOf(receiver)),position=OutfitPosition(2488))),
            warehouses=s.stocks.warehouses+(30 to OutfitWarehouse(30,2,OutfitKind.FLEET,1000,3)))
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val enemy=s.stocks.outfits.getValue(2)
        assertNotNull(accept(s,nav,p,request(TacticalSupportAction.SUPPLY,2)).player)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        assertEquals(0,s.stocks.outfits.getValue(1).supplies)
        assertEquals(10000,s.stocks.outfits.getValue(3).supplies)
        assertEquals(10000,OutfitSupplies.distribution(s.stocks.outfits.getValue(3)).getValue(2))
        assertEquals(enemy,s.stocks.outfits.getValue(2))
        assertEquals(10000,s.stocks.outfits.values.sumOf {it.supplies})
        assertEquals(10000,out.single().targetSupplies)
    }

    @Test fun partialRepairKeepsLostBoatsLostAndRejectsLessThanOneShipsCost() {
        val s=state(true)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits.mapValues {(id,o)->if(id==1)
            OutfitSupplies.withBalances(o,mapOf(1 to 280,2 to 0)) else o})
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertNotNull(accept(s,nav,p,request(TacticalSupportAction.REPAIR,2)).player)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        val unit=s.stocks.outfits.getValue(1).activeShips!!.values.last()
        assertEquals(250,unit.boats);assertEquals(28,unit.damaged)
        assertEquals(0,s.stocks.outfits.getValue(1).supplies)
        assertEquals(78,out.single().damaged)
        assertEquals(NavigationRejection.SUPPLY,accept(s,nav,p,request(TacticalSupportAction.REPAIR,2),now+320).rejection)
    }

    @Test fun repairCostsManualSuppliesAndNeverRecreatesDestroyedBoats() {
        for(target in listOf(2,1010000)) {
            val s=state(true);val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            assertNotNull(accept(s,nav,p,request(TacticalSupportAction.REPAIR,target)).player)
            val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
            val cost=UnitSpecifications.ships.single {it.kind==if(target==2)32 else 0}.number("修理消費物資(１隻):修理消費物資(１隻)")
            assertEquals(10000-cost*(if(target==2)30 else 1),s.stocks.outfits.getValue(1).supplies)
            assertEquals(if(target==2)50 else 0,out.single().damaged)
            if(target==2) {
                val unit=s.stocks.outfits.getValue(1).activeShips!!.values.last()
                assertEquals(250,unit.boats);assertEquals(0,unit.damaged)
            } else {assertEquals(100,s.players.getValue("pilot").hull!!.hp);assertEquals(400,s.players.getValue("pilot").supplies)}
        }
    }

    @Test fun failedSavePublishesNoCargoOrResultAndRetryExecutesOnce() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks;s.fail=true
        assertFailsWith<java.io.IOException> {accept(s,nav,p,request(TacticalSupportAction.SUPPLY,1010000))}
        assertEquals(before,p.snapshot(1));s.fail=false
        accept(s,nav,p,request(TacticalSupportAction.SUPPLY,1010000))
        val accepted=s.stocks;val players=s.players;val out=mutableListOf<TacticalSupportResult>();s.fail=true
        assertFailsWith<java.io.IOException> {settle(s,nav,p,now+120,out)}
        assertEquals(accepted,p.snapshot(1));assertEquals(players,nav.snapshot(1));assertTrue(out.isEmpty())
        s.fail=false;settle(s,nav,p,now+120,out);settle(s,nav,p,now+120,out)
        assertEquals(1,out.size);assertEquals(400,s.stocks.outfits.getValue(1).supplies)
    }

    @Test fun invalidAuthorityHostilityHullAndRangeCannotTransferAndMovementCancelsPending() {
        for(change in listOf<(State)->Unit>(
            {s->s.stocks=s.stocks.copy(outfits=s.stocks.outfits.mapValues {(id,o)->if(id==1)o.copy(commanderId=10002) else o})},
            {s->s.players=s.players.mapValues {(_,v)->v.copy(motion=ai(100f).motion)}})) {
            val s=state();change(s);val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            assertEquals(NavigationRejection.TARGET,accept(s,nav,p,request(TacticalSupportAction.SUPPLY,1010000)).rejection)
        }
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertEquals(NavigationRejection.TARGET,accept(s,nav,p,request(TacticalSupportAction.REPAIR,2)).rejection)
        assertEquals(NavigationRejection.TARGET,accept(s,nav,p,request(TacticalSupportAction.SUPPLY,99)).rejection)
        val far=state().also {it.players=it.players.mapValues {(_,v)->v.copy(motion=ai(100f).motion)}}
        val farNav=Navigation(galaxy,far.nav,NavigationPolicy());val farStock=production(far)
        assertEquals(NavigationRejection.TARGET,accept(far,farNav,farStock,request(TacticalSupportAction.SUPPLY,2)).rejection)
        assertEquals(NavigationRejection.SESSION,accept(s,nav,p,request(TacticalSupportAction.SUPPLY,2).copy(connection="stale")).rejection)
        assertNotNull(accept(s,nav,p,request(TacticalSupportAction.SUPPLY,2)).player)
        val current=p.snapshot(1)!!
        p.replace(current,current.copy(outfits=current.outfits.mapValues {(id,o)->if(id==1)o.copy(commanderId=10002) else o}))
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        assertTrue(out.single().cancelled);assertEquals(10000,out.single().character)
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.first().support)
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
    }

    @Test fun nativeSupportRadiusExcludesBoundaryAndCancelsWhenTargetLeaves() {
        for(y in listOf(1f,1.01f)) {
            val s=state().also {it.players=it.players.mapValues {(_,v)->v.copy(motion=ai(y).motion)}}
            val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            assertEquals(NavigationRejection.TARGET,accept(s,nav,p,request(TacticalSupportAction.SUPPLY,1010000)).rejection)
        }
        val s=state().also {it.players=it.players.mapValues {(_,v)->v.copy(motion=ai(0.999f).motion)}}
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertNotNull(accept(s,nav,p,request(TacticalSupportAction.SUPPLY,1010000)).player)
        val current=nav.snapshot(1).mapValues {(_,v)->v.copy(motion=ai(1f).motion)}
        nav.saveReplacement(nav.snapshot(1),current,1)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        assertTrue(out.single().cancelled)
        assertEquals(10000,s.stocks.outfits.getValue(1).supplies)
        assertEquals(400,s.players.getValue("pilot").supplies)
    }
    @Test fun acceptedSupportCompletesOutsideCommandRangeButNewCommandsNeedRegrowth() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val request=request(TacticalSupportAction.SUPPLY,2)
        assertNotNull(accept(s,nav,p,request).player)
        assertEquals(0f,s.players.getValue("pilot").commandRange!!.radius(now))
        val previous=nav.snapshot(1)
        nav.saveReplacement(previous,previous.mapValues {(_,v)->v.copy(motion=ai(100f).motion)},1)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        assertFalse(out.single().cancelled)
        assertEquals(mapOf(1 to 0,2 to 10000),OutfitSupplies.distribution(s.stocks.outfits.getValue(1)))
    }
    @Test fun combatGeneratedDamageCanBeRepairedWithoutRestoringDestroyedBoatsOrLostCargo() {
        val s=state(repair=true)
        val own=s.stocks.outfits.getValue(1)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(supplies=13000,activeShips=ActiveShipUnits(
            own.activeShips!!.values.map {if(it.id==2)it.copy(damaged=0,supplies=3000) else it}))))
        val victim=checkNotNull(TacticalCombat.target(2,2488,Power.ALLIANCE,s.stocks,galaxy,s.players))
        val hit=TacticalCombat.strike(session,galaxy,s.players.toMutableMap(),s.stocks,99,2488,1,now,2,TacticalWeapon.BEAM,
            TacticalWeaponMount(0,63,68,0),100,TacticalPose(TacticalPoint(0f,2f),0f),victim)
        s.stocks=hit.first
        val damaged=s.stocks.outfits.getValue(1).activeShips!!.values.last()
        assertTrue(damaged.damaged>0);assertEquals(248,damaged.boats);assertEquals(2976,damaged.supplies)
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertNotNull(accept(s,nav,p,request(TacticalSupportAction.REPAIR,2)).player)
        val out=mutableListOf<TacticalSupportResult>();settle(s,nav,p,now+120,out)
        val repaired=s.stocks.outfits.getValue(1).activeShips!!.values.last()
        assertEquals(0,repaired.damaged);assertEquals(damaged.boats,repaired.boats);assertEquals(damaged.supplies,repaired.supplies)
        assertEquals(52,out.single().damaged)
        assertEquals(9860,s.stocks.outfits.getValue(1).activeShips!!.values.first().supplies)
    }
}
