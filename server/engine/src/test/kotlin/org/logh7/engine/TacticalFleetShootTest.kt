package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalFleetShootTest {
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
                units=UnitInventory(mapOf(enemy to 1)),activeShips=ActiveShipUnits(listOf(ActiveShipUnit(99,enemy,tactical=ai(2f)))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100))
    }
    private fun production(s:State)=PlanetProduction(galaxy,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
    private fun request(ids:List<Int> = listOf(1,2))=NavigationRequest("pilot",10000,Power.EMPIRE,"current",1,
        shipKind=0,captainCard=59,tacticalMove=TacticalMove(ids,1f,0f,listOf(TacticalPoint(0f,10f))))
    private fun move(s:State,nav:Navigation,p:PlanetProduction,r:NavigationRequest=request())=
        TacticalFleetMotion.move(r,session,galaxy,nav,p,p.snapshot(1)!!,s.commit)

    private fun shoot(s:State,nav:Navigation,p:PlanetProduction,weapon:TacticalWeapon=TacticalWeapon.BEAM,
        ids:List<Int> = listOf(2),at:Long=now)=TacticalFleetShoot.accept(request(ids).copy(tacticalMove=null,
            tacticalShoot=TacticalShoot(ids,weapon.wire,1,99)),session.copy(gameMillis=at*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit,24)
    private fun advance(s:State,nav:Navigation,p:PlanetProduction,at:Long)=
        TacticalCombat.settle(session.copy(gameMillis=at*1000),galaxy,nav,p,s.commit)

    @Test fun oneShotResumesPendingAndFiresExactlyOnceEvenOutsideRegrownCrc() {
        val s=state();var nav=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        assertNotNull(shoot(s,nav,p).player)
        assertEquals(0f,s.players.getValue("pilot").commandRange!!.radius(now))
        assertTrue(advance(s,nav,p,now+119).isEmpty())
        nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        val before=nav.snapshot(1)
        nav.saveReplacement(before,before.mapValues {(_,v)->v.copy(motion=ai(100f).motion)},1)
        val hits=advance(s,nav,p,now+120)
        assertEquals(1,hits.size);assertEquals(2,hits.single().source);assertEquals(99,hits.single().hit.target)
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().shot)
        val saved=s.stocks
        assertTrue(advance(s,nav,p,now+120).isEmpty());assertTrue(advance(s,nav,p,now+500).isEmpty())
        assertEquals(saved,s.stocks)
    }
    @Test fun everyWeaponDebitsOnlyFiringUnitsHealthyBoatsAndRejectsInsufficientSupply() {
        for(weapon in TacticalWeapon.entries) {
            val s=state();val own=s.stocks.outfits.getValue(1)
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(supplies=15000,
                activeShips=ActiveShipUnits(own.activeShips!!.values.map {if(it.id==2)it.copy(supplies=5000) else it}))))
            val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            assertNotNull(shoot(s,nav,p,weapon).player)
            val mount=TacticalArmaments.mount(UnitSpecifications.ships.single {it.kind==32},weapon)
            val cost=TacticalFleetShoot.cost(own.activeShips!!.values.last(),mount,weapon)
            assertEquals(weapon,advance(s,nav,p,now+120).single().weapon)
            assertEquals(5000-cost,s.stocks.outfits.getValue(1).activeShips!!.values.last().supplies)
            assertEquals(10000,s.stocks.outfits.getValue(1).activeShips!!.values.first().supplies)
        }
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        assertEquals(NavigationRejection.SUPPLY,shoot(s,nav,p,TacticalWeapon.MISSILE).rejection)
    }
    @Test fun manualShotRejectsReloadingWithoutResettingCargoOrderOrOtherWeaponReadiness() {
        val s=state();var nav=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        assertNotNull(shoot(s,nav,p).player);assertEquals(1,advance(s,nav,p,now+120).size)
        val saved=s.stocks;val players=s.players
        assertEquals(TacticalReload(now+360),saved.outfits.getValue(1).activeShips!!.values.last().reload)
        nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        assertEquals(NavigationRejection.ENERGY,shoot(s,nav,p,at=now+121).rejection)
        assertEquals(saved,s.stocks);assertEquals(players,s.players)
        assertNotNull(shoot(s,nav,p,at=now+360).player)
        assertEquals(1,advance(s,nav,p,now+480).size)
        assertEquals(TacticalReload(now+720),s.stocks.outfits.getValue(1).activeShips!!.values.last().reload)
    }
    @Test fun mixedSelectionCannotPartiallyCommitAndFailedFireCannotPublishOrDuplicateDamage() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks;val players=s.players
        assertEquals(NavigationRejection.CARD,shoot(s,nav,p,ids=listOf(2,99,1010000)).rejection)
        assertEquals(before,s.stocks);assertEquals(players,s.players)
        s.fail=true;assertFailsWith<java.io.IOException>{shoot(s,nav,p)}
        assertEquals(before,s.stocks);assertEquals(players,s.players);s.fail=false
        assertNotNull(shoot(s,nav,p).player)
        val pending=s.stocks;s.fail=true
        assertFailsWith<java.io.IOException>{advance(s,nav,p,now+120)}
        assertEquals(pending,p.snapshot(1));s.fail=false
        assertEquals(1,advance(s,nav,p,now+120).size)
        assertTrue(advance(s,nav,p,now+120).isEmpty())
    }
    @Test fun revokedCommanderOrTargetLeavingRangeCancelsWithoutSpendingOrDamage() {
        for(revoke in listOf(true,false)) {
            val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
            shoot(s,nav,p)
            val current=p.snapshot(1)!!
            val o=current.outfits.getValue(if(revoke)1 else 2)
            val changed=if(revoke)o.copy(commanderId=10002) else o.copy(activeShips=ActiveShipUnits(
                o.activeShips!!.values.map {it.copy(tactical=ai(1000f))}))
            p.replace(current,current.copy(outfits=current.outfits+(o.id to changed)))
            assertTrue(advance(s,nav,p,now+120).isEmpty())
            assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().shot)
            assertEquals(300,s.stocks.outfits.getValue(2).activeShips!!.values.single().boats)
        }
    }
    @Test fun mixedPersonalAndFleetShotCommitsTogetherAndStopCancelsBothBeforeFiring() {
        val s=state();s.players=s.players.mapValues {(_,v)->v.copy(charge=TacticalCharge(now,24000))}
        val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.players;val inventory=s.stocks;s.fail=true
        assertFailsWith<java.io.IOException>{shoot(s,nav,p,ids=listOf(2,1010000))}
        assertEquals(before,s.players);assertEquals(inventory,s.stocks);s.fail=false
        assertNotNull(shoot(s,nav,p,ids=listOf(2,1010000)).player)
        assertNotNull(s.players.getValue("pilot").fire!!.pending)
        assertNotNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().shot)
        val stop=request().copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(2,1010000)))
        assertNotNull(TacticalSupportOperations.stop(stop,session.copy(gameMillis=(now+16)*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit).player)
        assertNull(s.players.getValue("pilot").fire!!.pending)
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().shot)
        assertTrue(advance(s,nav,p,now+120).isEmpty())
        assertEquals(300,s.stocks.outfits.getValue(2).activeShips!!.values.single().boats)
    }
}
