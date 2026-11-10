package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalFleetShootTest {
    @Test fun firingAfterOrbitalDriftUsesProjectedCommanderShooterAndTargetPoses() {
        val at=now+1000;val timed=session.copy(gameMillis=at*1000)
        val moving=Galaxy(galaxy.systems,galaxy.bases.map {it.copy(grid=2488,radius=20f,cycle=1000)},galaxy.starts)
        val base=moving.bases.single();val posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,base.id)
        fun anchored(at:Long,offset:Float):TacticalMotion {
            val point=TacticalBasePosition.at(base,at).let {TacticalPoint(it.x,it.y+offset)}
            return TacticalMotion(2488,at,TacticalPose(point,0f),0f,0f,listOf(point))
        }
        val s=state();s.players=s.players.mapValues {(_,v)->v.copy(posture=posture,motion=anchored(at-750,.5f))}
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits.mapValues {(_,o)->o.copy(activeShips=ActiveShipUnits(o.activeShips!!.values.map {
            it.copy(posture=posture,tactical=NpcTacticalState(anchored(at-if(it.id==99)250 else 500,if(it.id==99)2f else .8f),at,at))}))})
        val n=Navigation(moving,s.nav,NavigationPolicy())
        val p=PlanetProduction(moving,s.inventory,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,at)}
        val command=request(listOf(2)).copy(tacticalMove=null,tacticalShoot=TacticalShoot(listOf(2),TacticalWeapon.BEAM.wire,1,99))
        assertNotNull(TacticalFleetShoot.accept(command,timed,moving,n,p,p.snapshot(1)!!,s.commit,24).player)
        val hits=TacticalCombat.settle(session.copy(gameMillis=(at+120)*1000),moving,n,p,s.commit)
        assertEquals(1,hits.size);assertEquals(2,hits.single().source);assertEquals(99,hits.single().hit.target)
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

    @Test fun fleetTrainingIncreasesPowerOfHealthyBoats() {
        val unit=state().stocks.outfits.getValue(1).activeShips!!.values.last()
        val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
        val mount=TacticalArmaments.mount(spec,TacticalWeapon.BEAM).copy(power=10*spec.number)
        val healthy=unit.boats-unit.damaged
        for((level,powerPerBoat) in listOf(10,12,14,16).withIndex()) {
            val trained=unit.copy(stack=unit.stack.copy(level=level))
            val expected=healthy*powerPerBoat
            assertEquals(mount.copy(power=expected),TacticalArmaments.trainedMount(trained,mount,spec.number))
            assertEquals(TacticalFleetShoot.cost(unit,mount,TacticalWeapon.GUN),
                TacticalFleetShoot.cost(trained,mount,TacticalWeapon.GUN))
        }
    }

    @Test fun deceasedCommanderCannotFireManualOrAutomaticFleetOrdersFromAnOlderSave() {
        assertSavedFleetOrdersCancelled(deceased=true)
    }

    @Test fun eliminatedCountryCannotFireManualOrAutomaticFleetOrdersFromAnOlderSave() {
        assertSavedFleetOrdersCancelled(deceased=false)
    }

    @Test fun detainedCommanderCannotFireManualOrAutomaticFleetOrdersFromAnOlderSave() {
        assertSavedFleetOrdersCancelled(deceased=false,detained=true)
    }

    @Test fun fleetFireRejectsDefeatedTargetsAndContinuesAgainstSurvivingEnemies() {
        for (automatic in listOf(false, true)) {
            val s = state()
            val defeated = s.stocks.outfits.getValue(2)
            val third = CountryId(42)
            val survivor = defeated.copy(id = 3, country = third, warehouseId = 30,
                activeShips = ActiveShipUnits(listOf(defeated.activeShips!!.values.single().copy(id = 100, tactical = ai(2.5f)))))
            val warehouse = s.stocks.warehouses.getValue(20).copy(id = 30, country = third, outfitId = 3)
            s.stocks = s.stocks.copy(outfits = s.stocks.outfits + (3 to survivor),
                warehouses = s.stocks.warehouses + (30 to warehouse), nextShipId = 101)
            val nav = Navigation(galaxy, s.nav, NavigationPolicy())
            val stock = production(s)
            val liveSession = session.copy(countries = session.countries + Power.ALLIANCE.country + third)
            val afterDefeat = liveSession.copy(eliminated = mapOf(Power.ALLIANCE.country to
                CountryDefeat(EndReason.CAPITAL_CAPTURED, now, Power.EMPIRE.country)))
            val ids = listOf(2)
            val manual = request(ids).copy(tacticalMove = null, tacticalShoot = TacticalShoot(ids, TacticalWeapon.BEAM.wire, 1, 99))
            val auto = request(ids).copy(tacticalMove = null, tacticalAttack = TacticalAttack(ids, 2))
            val initial = stock.snapshot(1)!!
            val initialPlayers = nav.snapshot(1)
            assertEquals(NavigationRejection.TARGET,
                TacticalFleetShoot.accept(manual, afterDefeat, galaxy, nav, stock, initial, s.commit, 24).rejection)
            assertEquals(initial, stock.snapshot(1))
            assertEquals(initialPlayers, nav.snapshot(1))

            val accepted = if (automatic) TacticalFleetAttack.accept(auto, liveSession, galaxy, nav, stock, initial, s.commit, 24)
                else TacticalFleetShoot.accept(manual, liveSession, galaxy, nav, stock, initial, s.commit, 24)
            assertNotNull(accepted.player)
            val ownBefore = s.stocks.outfits.getValue(1).activeShips!!.values.last()
            val targetBefore = s.stocks.outfits.getValue(2)
            val tick = afterDefeat.copy(gameMillis = (now + 120) * 1000)
            val impacts = TacticalCombat.settle(tick, galaxy, nav, stock, s.commit)
            assertTrue(s.players.getValue("pilot").cpPaused)
            assertEquals(targetBefore, s.stocks.outfits.getValue(2))
            if (automatic) {
                assertTrue(impacts.isNotEmpty())
                assertTrue(impacts.all { it.source == 2 && it.hit.target == 100 })
            } else {
                assertTrue(impacts.isEmpty())
                assertEquals(ownBefore.copy(shot = null), s.stocks.outfits.getValue(1).activeShips!!.values.last())
            }
        }
    }

    private fun assertSavedFleetOrdersCancelled(deceased:Boolean,detained:Boolean=false) {
        for(automatic in listOf(false,true)) {
            val s=state()
            var nav=Navigation(galaxy,s.nav,NavigationPolicy())
            var stock=production(s)
            val command=request(listOf(2)).copy(tacticalMove=null,tacticalAttack=TacticalAttack(listOf(2),2))
            fun attack()=TacticalFleetAttack.accept(command,session,galaxy,nav,stock,stock.snapshot(1)!!,s.commit,24)
            fun unit()=s.stocks.outfits.getValue(1).activeShips!!.values.single {it.id==2}
            assertNotNull((if(automatic)attack() else shoot(s,nav,stock)).player)
            val saved=unit()
            if(automatic)assertNotNull(saved.attack) else assertNotNull(saved.shot)
            val target=s.stocks.outfits.getValue(2)
            if(deceased)s.stocks=s.stocks.copy(strategic=s.stocks.strategic.copy(people=mapOf(10000 to StrategicPerson(deceasedAt=1))))
            if(detained) {
                val detention=Detention(10000,Power.EMPIRE.country,now,now+Justice.DAY)
                s.stocks=s.stocks.copy(social=s.stocks.social.copy(justice=JusticeState(detentions=mapOf(10000 to detention))))
            }
            nav=Navigation(galaxy,s.nav,NavigationPolicy())
            stock=production(s)
            if(deceased || detained) {
                val rejection=if(deceased)NavigationRejection.IDENTITY else NavigationRejection.BUSY
                assertEquals(rejection,shoot(s,nav,stock).rejection)
                assertEquals(rejection,attack().rejection)
            }
            val tick=session.copy(gameMillis=(now+120)*1000,eliminated=if(deceased || detained)emptyMap() else
                mapOf(Power.EMPIRE.country to CountryDefeat(EndReason.CAPITAL_CAPTURED,now,Power.ALLIANCE.country)))
            assertTrue(TacticalCombat.settle(tick,galaxy,nav,stock,s.commit).isEmpty())
            assertEquals(saved.copy(attack=null,shot=null),unit())
            assertEquals(target,s.stocks.outfits.getValue(2))
        }
    }

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
    @Test fun aManualShotCannotTargetStarCoverAndCancelsIfTheEnemyEntersItDuringTheDelay() {
        val world=Galaxy(galaxy.systems.map {if(it.grid==2488)it.copy(star=0) else it},galaxy.bases,galaxy.starts)
        val s=state()
        s.players=s.players.mapValues {(_,p)->p.copy(motion=ai(-21f).motion)}
        val own=s.stocks.outfits.getValue(1)
        val shooter=own.activeShips!!.values.last().copy(tactical=ai(-21f),supplies=5000)
        s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to own.copy(supplies=15000,
            activeShips=ActiveShipUnits(listOf(own.activeShips.values.first().copy(tactical=ai(-22f)),shooter)))))
        fun targetAt(y:Float) {
            val enemy=s.stocks.outfits.getValue(2)
            val unit=enemy.activeShips!!.values.single().copy(tactical=ai(y))
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to enemy.copy(activeShips=ActiveShipUnits(listOf(unit)))))
        }
        val ids=listOf(2)
        val command=request(ids).copy(tacticalMove=null,tacticalShoot=TacticalShoot(ids,TacticalWeapon.MISSILE.wire,1,99))
        fun accept():NavigationResult {
            val nav=Navigation(world,s.nav,NavigationPolicy())
            val p=production(s)
            return TacticalFleetShoot.accept(command,session,world,nav,p,p.snapshot(1)!!,s.commit,24)
        }
        targetAt(-19f)
        assertEquals(NavigationRejection.RANGE,accept().rejection)
        assertNull(s.stocks.outfits.getValue(1).activeShips!!.values.last().shot)
        targetAt(-20f)
        assertNotNull(accept().player)
        targetAt(-19f)
        val nav=Navigation(world,s.nav,NavigationPolicy())
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),world,nav,production(s),s.commit).isEmpty())
        val saved=s.stocks.outfits.getValue(1).activeShips!!.values.last()
        assertNull(saved.shot)
        assertNull(saved.reload)
        assertEquals(5000,saved.supplies)
    }

    @Test fun alliedContactAllowsFireOnlyWhileTheScoutAndLineOfFireRemainValid() {
        val world=Galaxy(galaxy.systems.map {if(it.grid==2488)it.copy(star=0) else it},galaxy.bases,galaxy.starts)
        for(scoutWithdraws in listOf(false,true)) {
            val s=state()
            s.players=s.players.mapValues {(_,p)->p.copy(motion=ai(-21f).motion)}
            val own=s.stocks.outfits.getValue(1)
            val enemy=s.stocks.outfits.getValue(2)
            val scout=own.activeShips!!.values.first().copy(tactical=ai(-18f))
            val shooter=own.activeShips.values.last().copy(tactical=ai(-21f),supplies=5000)
            fun targetAt(y:Float)=enemy.copy(activeShips=ActiveShipUnits(listOf(
                enemy.activeShips!!.values.single().copy(tactical=ai(y)))))
            s.stocks=s.stocks.copy(outfits=mapOf(1 to own.copy(supplies=15000,
                activeShips=ActiveShipUnits(listOf(scout,shooter))),2 to targetAt(19f)))
            val ids=listOf(2)
            val command=request(ids).copy(tacticalMove=null,tacticalShoot=TacticalShoot(ids,TacticalWeapon.MISSILE.wire,1,99))
            fun accept():NavigationResult {
                val nav=Navigation(world,s.nav,NavigationPolicy())
                val stock=production(s)
                return TacticalFleetShoot.accept(command,session,world,nav,stock,stock.snapshot(1)!!,s.commit,24)
            }
            assertTrue(TacticalTerrain.fireBlocked(world,2488,TacticalPoint(0f,-21f),TacticalPoint(0f,19f)))
            assertEquals(NavigationRejection.RANGE,accept().rejection)
            s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(2 to targetAt(-19f)))
            assertNotNull(accept().player)

            if(scoutWithdraws) {
                val fleet=s.stocks.outfits.getValue(1)
                s.stocks=s.stocks.copy(outfits=s.stocks.outfits+(1 to fleet.copy(activeShips=ActiveShipUnits(
                    fleet.activeShips!!.values.map {if(it.id==scout.id)it.copy(tactical=ai(-22f)) else it}))))
            }
            val nav=Navigation(world,s.nav,NavigationPolicy())
            val impacts=TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),world,nav,production(s),s.commit)
            assertEquals(if(scoutWithdraws)emptyList() else listOf(99),impacts.map {it.hit.target})
        }
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
    @Test fun confusionRejectsMixedSelectionBeforeReservationAndCancelsAValidShotIfItOccursLater() {
        val s = state()
        s.players = s.players.mapValues { (_, player) ->
            val position = TacticalPoint(.5f, .5f)
            player.copy(charge = TacticalCharge(now, 24000),
                motion = player.motion!!.copy(origin = TacticalPose(position, 0f), waypoints = listOf(position)),
                attack = TacticalAttackOrder(2, now, now + 240, now + 240))
        }
        s.stocks = s.stocks.copy(tacticalCommands = s.stocks.tacticalCommands.copy(morale = mapOf(2 to 20)))
        val nav = Navigation(galaxy, s.nav, NavigationPolicy())
        val stock = production(s)
        val players = s.players
        val before = s.stocks
        assertEquals(NavigationRejection.BUSY, shoot(s, nav, stock, ids = listOf(1010000, 2)).rejection)
        assertEquals(players, s.players)
        assertEquals(before, s.stocks)

        fun morale(value: Int) {
            val current = stock.snapshot(1)!!
            stock.replace(current, current.copy(tacticalCommands = current.tacticalCommands.copy(morale = mapOf(2 to value))))
        }
        morale(21)
        assertNotNull(shoot(s, nav, stock).player)
        val reserved = s.stocks.outfits.getValue(1).activeShips!!.values.last()
        val target = s.stocks.outfits.getValue(2)
        assertNotNull(reserved.shot)
        morale(20)
        assertTrue(advance(s, nav, stock, now + 120).isEmpty())
        assertEquals(reserved.copy(shot = null), s.stocks.outfits.getValue(1).activeShips!!.values.last())
        assertEquals(target, s.stocks.outfits.getValue(2))
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
        // Keep the personal ray clear of the selected friendly ship at (0,.8).
        val s=state();s.players=s.players.mapValues {(_,v)->v.copy(charge=TacticalCharge(now,24000),
            motion=v.motion!!.copy(origin=TacticalPose(TacticalPoint(.5f,.5f),0f),waypoints=listOf(TacticalPoint(.5f,.5f))))}
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
