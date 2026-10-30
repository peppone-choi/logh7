package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalFleetAttackTest {
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

    private fun attack(s:State,nav:Navigation,p:PlanetProduction,ids:List<Int> = listOf(2),mode:Int=2,at:Long=now)=
        TacticalFleetAttack.accept(request(ids).copy(tacticalMove=null,tacticalAttack=TacticalAttack(ids,mode)),
            session.copy(gameMillis=at*1000),galaxy,nav,p,p.snapshot(1)!!,s.commit,24)

    @Test fun commandedShipFiresPersistsAndStopsWithoutNpcPolicyOrRangeRechecks() {
        val s=state();var nav=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
        assertNotNull(attack(s,nav,p).player)
        assertEquals(0f,s.players.getValue("pilot").commandRange!!.radius(now))
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+119)*1000),galaxy,nav,p,s.commit).isEmpty())
        val first=TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,p,s.commit)
        assertTrue(first.isNotEmpty());assertTrue(first.all {it.source==2 && it.hit.target==99})
        val boats=s.stocks.outfits.getValue(2).activeShips!!.values.single().boats
        assertTrue(boats<300)
        nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+240)*1000),galaxy,nav,p,s.commit).isEmpty())
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+360)*1000),galaxy,nav,p,s.commit).isNotEmpty())
        assertTrue(s.stocks.outfits.getValue(2).activeShips!!.values.single().boats<boats)
        assertNotNull(attack(s,nav,p,mode=0,at=now+400).player)
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().attack)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+500)*1000),galaxy,nav,p,s.commit).isEmpty())
    }
    @Test fun salvoWaitsForAllFittedWeaponsWhileContinuousFiresEachReadyWeaponAfterReopen() {
        for(mode in listOf(1,2)) {
            val s=state();val outfit=s.stocks.outfits.getValue(1)
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to outfit.copy(supplies=15000,activeShips=ActiveShipUnits(
                outfit.activeShips!!.values.map {if(it.id==2)it.copy(supplies=5000,reload=TacticalReload(now+240,now+360,now+480)) else it}))))
            var nav=Navigation(galaxy,s.nav,NavigationPolicy());var p=production(s)
            assertNotNull(attack(s,nav,p,mode=mode).player)
            for(elapsed in listOf(120L,240L,360L,480L)) {
                nav=Navigation(galaxy,s.nav,NavigationPolicy());p=production(s)
                val hits=TacticalCombat.settle(session.copy(gameMillis=(now+elapsed)*1000),galaxy,nav,p,s.commit)
                val expected=if(mode==1) {if(elapsed==480L)TacticalWeapon.entries else emptyList()} else when(elapsed) {
                    240L->listOf(TacticalWeapon.BEAM);360L->listOf(TacticalWeapon.GUN)
                    480L->listOf(TacticalWeapon.BEAM,TacticalWeapon.MISSILE);else->emptyList()
                }
                assertEquals(expected,hits.map {it.weapon})
                val saved=s.stocks
                assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+elapsed)*1000),galaxy,nav,p,s.commit).isEmpty())
                assertEquals(saved,s.stocks)
            }
            val reload=s.stocks.outfits.getValue(1).activeShips!!.values.last().reload!!
            assertEquals(now+720,reload.beam);assertEquals(now+960,reload.missile)
            assertEquals(now+if(mode==1)840 else 720,reload.gun)
        }
    }
    @Test fun mixedCommandIsAtomicAndForeignOrUnchargedUnitsRejectEverything() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        val before=s.stocks;val players=s.players
        assertEquals(NavigationRejection.CARD,attack(s,nav,p,listOf(2,99,1010000)).rejection)
        assertEquals(before,s.stocks);assertEquals(players,s.players)
        s.fail=true;assertFailsWith<java.io.IOException>{attack(s,nav,p,listOf(2,1010000))}
        assertEquals(before,s.stocks);assertEquals(players,s.players);s.fail=false
        assertNotNull(attack(s,nav,p,listOf(2,1010000)).player)
        assertNotNull(s.players.getValue("pilot").attack)
        assertEquals(NavigationRejection.RANGE,attack(s,nav,p,mode=0).rejection)
    }
    @Test fun revokedCommanderCancelsOrderAndCannotContinueInflictingDamage() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        attack(s,nav,p)
        val current=p.snapshot(1)!!
        p.replace(current,current.copy(outfits=current.outfits+(1 to current.outfits.getValue(1).copy(commanderId=10002))))
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,p,s.commit).isEmpty())
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().attack)
        assertEquals(300,s.stocks.outfits.getValue(2).activeShips!!.values.single().boats)
    }
    @Test fun receivedAttackContinuesOutsideCrcAndAFailedFireCommitCannotPublishOrDuplicateDamage() {
        val s=state();val nav=Navigation(galaxy,s.nav,NavigationPolicy());val p=production(s)
        attack(s,nav,p)
        val players=nav.snapshot(1)
        nav.saveReplacement(players,players.mapValues {(_,v)->v.copy(motion=ai(100f).motion)},1)
        val before=s.stocks;val beforePlayers=s.players;s.fail=true
        assertFailsWith<java.io.IOException> {
            TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,p,s.commit)
        }
        assertEquals(before,p.snapshot(1));assertEquals(beforePlayers,nav.snapshot(1));s.fail=false
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,p,s.commit).isNotEmpty())
        val saved=s.stocks
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,p,s.commit).isEmpty())
        assertEquals(saved,s.stocks)
    }
}
