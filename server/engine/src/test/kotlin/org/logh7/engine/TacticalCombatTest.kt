package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalCombatTest {
    @Test fun anAllianceCaptainCanFireAnImperialStyleFlagshipAtTheImperialEnemy() {
        val state=state()
        state.players=state.players.mapValues {(_,p)->p.copy(shipStyle=ShipStyleId("empire"),hull=FlagshipHull(0,now))}
        val nav=Navigation(galaxy,state.nav,NavigationPolicy())
        val p=production(state)
        val shot=request.copy(shipKind=0)
        val accepted=TacticalCombat.accept(shot,session,galaxy,nav,p.snapshot(1)!!,state.commit,24)
        assertNull(accepted.rejection)
        assertEquals(CountryId(3),accepted.player?.country);assertEquals(ShipStyleId("empire"),accepted.player?.shipStyle)
        assertNotNull(accepted.player?.fire)
    }
    @Test fun lastEnemyDefeatedOrDepartedClearsAutomaticOrderDurablyEvenWithoutAnotherShot() {
        val command=request.copy(tacticalShoot=null,tacticalAttack=TacticalAttack(listOf(1010000),2))
        val hostileBase=Galaxy(galaxy.systems,galaxy.bases.map {it.copy(grid=2488)},galaxy.starts)
        val state=state(1);val nav=Navigation(hostileBase,state.nav,NavigationPolicy());val stock=production(state)
        TacticalCombat.attack(command,session,hostileBase,nav,stock.snapshot(1)!!,state.commit,24)
        assertEquals(1,TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),hostileBase,nav,stock,state.commit).size)
        assertNull(state.players.getValue("pilot").attack)
        assertTrue(TacticalContact.active(hostileBase,state.players.getValue("pilot"),state.players.values,state.stocks))
        assertTrue(state.players.getValue("pilot").cpPaused)
        val departed=state();val moved=Navigation(galaxy,departed.nav,NavigationPolicy());val inventory=production(departed)
        TacticalCombat.attack(command,session,galaxy,moved,inventory.snapshot(1)!!,departed.commit,24)
        val before=inventory.snapshot(1)!!
        inventory.replace(before,before.copy(outfits=before.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2487))}))
        departed.fail=true
        assertFailsWith<java.io.IOException> {
            TacticalCombat.settle(session.copy(gameMillis=(now+1)*1000),galaxy,moved,inventory,departed.commit)
        }
        assertTrue(moved.snapshot(1).getValue("pilot").cpPaused)
        departed.fail=false
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+1)*1000),galaxy,moved,inventory,departed.commit).isEmpty())
        assertFalse(departed.players.getValue("pilot").cpPaused)
        assertEquals(now+1,departed.players.getValue("pilot").cpRecoveryAt)
        assertNull(departed.players.getValue("pilot").motion)
        assertNull(departed.players.getValue("pilot").charge)
        assertNull(departed.players.getValue("pilot").attack)
        assertNull(Navigation(galaxy,departed.nav,NavigationPolicy()).snapshot(1).getValue("pilot").attack)
    }
    @Test fun continuousFireChoosesHostileTargetsUsesAllReadyWeaponsAndDoesNotReplaySameTick() {
        val state=state();val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
        val command=request.copy(tacticalShoot=null,tacticalAttack=TacticalAttack(listOf(1010000),2))
        val accepted=TacticalCombat.attack(command,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24)
        assertEquals(now+120,accepted.player!!.attack!!.startsAt)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+119)*1000),galaxy,nav,stock,state.commit).isEmpty())
        val first=TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,stock,state.commit)
        assertEquals(listOf(TacticalWeapon.BEAM,TacticalWeapon.MISSILE),first.map {it.weapon})
        assertTrue(first.all {it.hit.target==1})
        assertEquals(9999,nav.snapshot(1).getValue("pilot").supplies)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,stock,state.commit).isEmpty())
        // A process restart reloads the cadence instead of replaying the first volley.
        val reopened=Navigation(galaxy,state.nav,NavigationPolicy())
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+239)*1000),galaxy,reopened,stock,state.commit).isEmpty())
        assertEquals(listOf(TacticalWeapon.MISSILE),TacticalCombat.settle(session.copy(gameMillis=(now+240)*1000),
            galaxy,reopened,stock,state.commit).map {it.weapon})
        assertEquals(listOf(TacticalWeapon.BEAM,TacticalWeapon.MISSILE),TacticalCombat.settle(session.copy(gameMillis=(now+360)*1000),
            galaxy,reopened,stock,state.commit).map {it.weapon})
        val stopped=TacticalCombat.attack(command.copy(tacticalAttack=TacticalAttack(listOf(1010000),0)),
            session.copy(gameMillis=(now+360)*1000),galaxy,reopened,stock.snapshot(1)!!,state.commit,24)
        assertNull(stopped.player!!.attack)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+600)*1000),galaxy,reopened,stock,state.commit).isEmpty())
    }
    @Test fun salvoWaitsForBothChargesAndFailureCannotPublishAnOrderOrLosses() {
        val state=state();val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
        val command=request.copy(tacticalShoot=null,tacticalAttack=TacticalAttack(listOf(1010000),1))
        state.fail=true
        assertFailsWith<java.io.IOException>{TacticalCombat.attack(command,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24)}
        assertNull(nav.snapshot(1).getValue("pilot").attack);state.fail=false
        TacticalCombat.attack(command,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,stock,state.commit).isEmpty())
        state.players=nav.snapshot(1).mapValues {(_,p)->p.copy(charge=TacticalCharge(now,24000,24000),
            energy=TacticalEnergy(List(11){if(it in 3..4)100 else 0}))}
        val charged=Navigation(galaxy,state.nav,NavigationPolicy())
        state.fail=true
        assertFailsWith<java.io.IOException>{TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,charged,stock,state.commit)}
        assertNull(charged.snapshot(1).getValue("pilot").fire);assertEquals(300,stock.snapshot(1)!!.outfits.getValue(1).activeShips!!.values.single().boats)
        state.fail=false
        val hits=TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,charged,stock,state.commit)
        assertTrue(hits.all {it.hit.damaged>it.hit.destroyed})
        assertEquals(TacticalWeapon.entries,hits.map {it.weapon});assertEquals(listOf(1L,2L,3L),hits.map {it.hit.sequence})
        assertEquals(9998,charged.snapshot(1).getValue("pilot").supplies)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+240)*1000),galaxy,charged,stock,state.commit).isEmpty())
        assertEquals(3,TacticalCombat.settle(session.copy(gameMillis=(now+360)*1000),galaxy,charged,stock,state.commit).size)
    }
    @Test fun automaticAttackCannotControlOtherUnitsOrAttackFriendlyOrAbsentTargets() {
        val state=state();val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
        val command=request.copy(tacticalShoot=null,tacticalAttack=TacticalAttack(listOf(1010000),2))
        for((invalid,reason) in listOf(command.copy(connection="obsolete") to NavigationRejection.SESSION,
            command.copy(captainCard=59) to NavigationRejection.CARD,
            command.copy(tacticalAttack=TacticalAttack(listOf(1),2)) to NavigationRejection.UNIT,
            command.copy(tacticalAttack=TacticalAttack(listOf(1010000),3)) to NavigationRejection.TARGET,
            command.copy(tacticalAttack=TacticalAttack(listOf(1010000),2,1)) to NavigationRejection.TARGET)) {
            assertEquals(reason,TacticalCombat.attack(invalid,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24).rejection)
        }
        TacticalCombat.attack(command,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24)
        state.players=nav.snapshot(1).mapValues {(_,p)->p.copy(country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE),hull=FlagshipHull(0,now))}
        val friendly=Navigation(galaxy,state.nav,NavigationPolicy())
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,friendly,stock,state.commit).isEmpty())
        assertNull(friendly.snapshot(1).getValue("pilot").fire)
    }
    private val rules = SessionRules.load()
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Battle",3)),
        listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val session = SessionSimulation(rules).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
    private val now = session.gameMillis / 1000
    private val request = NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,captainCard=195,shipKind=89,
        tacticalShoot=TacticalShoot(listOf(1010000),0,1,1))
    private class State(var players: Map<String,Navigator>, var stocks: PlanetStocks) {
        var fail = false
        val nav = object:NavigationStore {
            override fun load() = players
            override fun save(players:Map<String,Navigator>) { if(fail)throw java.io.IOException("disk");this@State.players=players }
        }
        val inventory = object:PlanetStockStore {
            override fun load() = stocks
            override fun save(stocks:PlanetStocks) {this@State.stocks=stocks}
        }
        val commit = WorldStateCommitter { players,stocks ->
            if(fail)throw java.io.IOException("disk")
            this.players=players.toMap();this.stocks=checkNotNull(stocks)
        }
    }
    private fun state(boats:Int=300): State {
        val point = TacticalPoint(180f,-97f)
        val player = Navigator(1,10000,Power.ALLIANCE,2488,1000,960,900,cpPaused=true,
            energy=TacticalEnergy(List(11){if(it==3)100 else 0}),charge=TacticalCharge(now,24000),
            motion=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point)))
        val stack = UnitStack(32)
        return State(mapOf("pilot" to player), PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,
                units=UnitInventory(mapOf(stack to 1)),activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,boats))),
                position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2))
    }
    private fun production(state:State) = PlanetProduction(galaxy,state.inventory,rules.startSeconds,PlanetProductionPolicy()).also {
        it.settle(1,now)
    }
    @Test fun flagshipBeamIsRejectedInStarCoverAndCancelledWithoutChargeLossWhenCoverReturns() {
        val world=Galaxy(galaxy.systems.map {if(it.grid==2488)it.copy(star=0) else it},galaxy.bases,galaxy.starts)
        val state=state()
        fun stationary(y:Float):TacticalMotion {
            val point=TacticalPoint(0f,y)
            return TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point))
        }
        state.players=state.players.mapValues {(_,p)->p.copy(motion=stationary(-21f))}
        val nav=Navigation(world,state.nav,NavigationPolicy())
        val production=production(state)
        fun targetAt(y:Float) {
            val old=production.snapshot(1)!!
            val outfit=old.outfits.getValue(1)
            val unit=outfit.activeShips!!.values.single().copy(tactical=NpcTacticalState(stationary(y),now,now))
            production.replace(old,old.copy(outfits=old.outfits+(1 to outfit.copy(activeShips=ActiveShipUnits(listOf(unit))))))
        }
        fun accept()=TacticalCombat.accept(request,session,world,nav,production.snapshot(1)!!,state.commit,24)
        targetAt(-19f)
        assertEquals(NavigationRejection.RANGE,accept().rejection)
        assertNull(state.players.getValue("pilot").fire)
        targetAt(-20f)
        assertNotNull(accept().player?.fire?.pending)
        targetAt(-19f)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),world,nav,production,state.commit).isEmpty())
        val saved=state.players.getValue("pilot")
        assertNull(saved.fire?.pending)
        assertNull(saved.fire?.last)
        assertEquals(24000,saved.charge!!.beam)
        assertEquals(300,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
    }

    @Test fun pendingBeamSurvivesReloadAndSettlesOnceWithChargeAndActualLossInOneCommit() {
        val state = state()
        var nav = Navigation(galaxy,state.nav,NavigationPolicy())
        var production = production(state)
        val accepted = TacticalCombat.accept(request,session,galaxy,nav,production.snapshot(1)!!,state.commit,24).player!!
        assertEquals(now+120,accepted.fire!!.pending!!.firesAt)
        assertEquals(24000,accepted.charge!!.beam)
        assertEquals(NavigationRejection.BUSY,TacticalCombat.accept(request,session,galaxy,nav,production.snapshot(1)!!,state.commit,24).rejection)
        nav = Navigation(galaxy,state.nav,NavigationPolicy());production = production(state)
        TacticalCombat.settle(session.copy(gameMillis=(now+119)*1000),galaxy,nav,production,state.commit)
        assertNotNull(nav.snapshot(1).getValue("pilot").fire!!.pending)
        val old = nav.snapshot(1);val inventory=production.snapshot(1)
        state.fail=true
        assertFailsWith<java.io.IOException> {TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,production,state.commit)}
        assertEquals(old,nav.snapshot(1));assertSame(inventory,production.snapshot(1));state.fail=false
        val impacts=TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,production,state.commit)
        val fired=nav.snapshot(1).getValue("pilot")
        assertEquals(0,fired.charge!!.beam);assertNull(fired.fire!!.pending)
        val losses = fired.fire.last!!.destroyed
        assertEquals(fired.fire.last,impacts.single().hit)
        assertEquals(mapOf(10000 to "c"),impacts.single().recipients)
        assertTrue(losses > 0)
        assertEquals(300-losses,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
        assertEquals(1000,fired.pcp);assertEquals(960,fired.mcp);assertEquals(900,fired.cruise)
        nav=Navigation(galaxy,state.nav,NavigationPolicy());production=production(state)
        val saved=state.stocks
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+500)*1000),galaxy,nav,production,state.commit).isEmpty())
        assertEquals(saved,state.stocks);assertEquals(fired,nav.snapshot(1).getValue("pilot"))
    }
    @Test fun forgedIdentityForeignUnitFriendlyOrMissingTargetUnsupportedWeaponsAndUnchargedBeamAreRejected() {
        val state=state();val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stocks=production(state).snapshot(1)!!
        fun reject(request:NavigationRequest,reason:NavigationRejection) {
            assertEquals(reason,TacticalCombat.accept(request,session,galaxy,nav,stocks,state.commit,24).rejection)
        }
        reject(request.copy(connection="stale"),NavigationRejection.SESSION)
        reject(request.copy(characterId=10001),NavigationRejection.IDENTITY)
        reject(request.copy(captainCard=59),NavigationRejection.CARD)
        reject(request.copy(tacticalShoot=request.tacticalShoot!!.copy(units=listOf(1))),NavigationRejection.UNIT)
        reject(request.copy(tacticalShoot=request.tacticalShoot!!.copy(arms=3)),NavigationRejection.WEAPON)
        reject(request.copy(tacticalShoot=request.tacticalShoot!!.copy(targetKind=2)),NavigationRejection.WEAPON)
        reject(request.copy(tacticalShoot=request.tacticalShoot!!.copy(target=2)),NavigationRejection.TARGET)
        reject(request.copy(shipKind=0),NavigationRejection.UNIT)
        state.players=state.players.mapValues {(_,p)->p.copy(charge=TacticalCharge(now))}
        assertEquals(NavigationRejection.ENERGY,TacticalCombat.accept(request,session,galaxy,
            Navigation(galaxy,state.nav,NavigationPolicy()),stocks,state.commit,24).rejection)
    }
    @Test fun destructionRetainsWreckIdentityWithoutKeepingEnemyContactAliveAndStopCancelsPendingShot() {
        val state=state(1);val nav=Navigation(galaxy,state.nav,NavigationPolicy());val production=production(state)
        TacticalCombat.accept(request,session,galaxy,nav,production.snapshot(1)!!,state.commit,24)
        TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,production,state.commit)
        assertEquals(0,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
        assertEquals(0,GridForces.units(state.stocks.outfits.getValue(1)))
        assertFalse(nav.snapshot(1).getValue("pilot").cpPaused)
        assertEquals(now+120,nav.snapshot(1).getValue("pilot").cpRecoveryAt)
        assertFalse(TacticalContact.active(galaxy,nav.snapshot(1).getValue("pilot"),nav.snapshot(1).values,state.stocks))
        val other=state();val stopped=Navigation(galaxy,other.nav,NavigationPolicy());val stock=production(other)
        TacticalCombat.accept(request,session,galaxy,stopped,stock.snapshot(1)!!,other.commit,24)
        val stop=stopped.apply(request.copy(tacticalShoot=null,tacticalStop=TacticalStop(listOf(1010000),emptyList())),session,stock.snapshot(1))
        assertNull(stop.player!!.fire!!.pending)
        TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,stopped,stock,other.commit)
        assertEquals(300,other.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
    }
    @Test fun failedAcceptanceAndTargetLeavingBeforeExecutionCannotSpendOrPublishDamage() {
        val state=state();val nav=Navigation(galaxy,state.nav,NavigationPolicy());val production=production(state)
        val before=nav.snapshot(1)
        state.fail=true
        assertFailsWith<java.io.IOException>{TacticalCombat.accept(request,session,galaxy,nav,production.snapshot(1)!!,state.commit,24)}
        assertEquals(before,nav.snapshot(1));state.fail=false
        TacticalCombat.accept(request,session,galaxy,nav,production.snapshot(1)!!,state.commit,24)
        val stocks=production.snapshot(1)!!
        production.replace(stocks,stocks.copy(outfits=stocks.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2487))}))
        TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,production,state.commit)
        val player=nav.snapshot(1).getValue("pilot")
        assertNull(player.fire!!.pending);assertNull(player.fire.last)
        assertNull(player.charge)
        assertFalse(player.cpPaused)
        assertEquals(now+120,player.cpRecoveryAt)
        assertEquals(300,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
        val point=TacticalPoint(180f,-93f)
        state.players=before.mapValues {(_,p)->p.copy(motion=TacticalMotion(2488,now,TacticalPose(point,0f),
            0f,0f,listOf(point)))}
        assertEquals(NavigationRejection.RANGE,TacticalCombat.accept(request,session,galaxy,
            Navigation(galaxy,state.nav,NavigationPolicy()),stocks,state.commit,24).rejection)
    }
    @Test fun acceptedFireContinuesOfflineAndAudienceIncludesBothPowersOnlyAtAuthenticatedCurrentConnections() {
        val state=state();val pilot=state.players.getValue("pilot")
        state.players+=mapOf("enemy" to pilot.copy(characterId=10001,country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE),motion=null,charge=null),
            "friend" to pilot.copy(characterId=10002,motion=null,charge=null),
            "stale" to pilot.copy(characterId=10003,motion=null,charge=null),
            "outside" to pilot.copy(characterId=10004,grid=2487,motion=null,charge=null),
            "no-connection" to pilot.copy(characterId=10005,motion=null,charge=null))
        val nav=Navigation(galaxy,state.nav,NavigationPolicy());val production=production(state)
        TacticalCombat.accept(request,session,galaxy,nav,production.snapshot(1)!!,state.commit,24)
        val firing=session.copy(gameMillis=(now+120)*1000,participants=session.participants+
            mapOf("pilot" to session.participants.getValue("pilot").copy(online=false,connection=null),
                "enemy" to Participant(Power.EMPIRE,false,connection="enemy-current"),
                "friend" to Participant(Power.ALLIANCE,false,connection="friend-current"),
                "stale" to Participant(Power.EMPIRE,false,connection="wrong-power"),
                "outside" to Participant(Power.ALLIANCE,false,connection="other-grid"),
                "no-connection" to Participant(Power.ALLIANCE,false)))
        val impact=TacticalCombat.settle(firing,galaxy,nav,production,state.commit).single()
        assertEquals(mapOf(10001 to "enemy-current",10002 to "friend-current"),impact.recipients)
        assertTrue(impact.hit.destroyed>0)
    }
    @Test fun gunAndMissilePersistTheirWeaponSpendSuppliesOnceAndDoNotConsumeOtherCharge() {
        for (weapon in listOf(TacticalWeapon.GUN,TacticalWeapon.MISSILE)) {
            val state=state()
            state.players=state.players.mapValues { (_,p)->p.copy(supplies=1,charge=TacticalCharge(now,24000,24000)) }
            var nav=Navigation(galaxy,state.nav,NavigationPolicy());var stocks=production(state)
            val shot=request.copy(tacticalShoot=request.tacticalShoot!!.copy(arms=weapon.wire))
            val accepted=TacticalCombat.accept(shot,session,galaxy,nav,stocks.snapshot(1)!!,state.commit,24).player!!
            assertEquals(weapon,accepted.fire!!.pending!!.weapon)
            assertEquals(1,accepted.supplies)
            nav=Navigation(galaxy,state.nav,NavigationPolicy());stocks=production(state)
            val due=session.copy(gameMillis=(now+120)*1000)
            state.fail=true
            assertFailsWith<java.io.IOException>{TacticalCombat.settle(due,galaxy,nav,stocks,state.commit)}
            assertEquals(1,nav.snapshot(1).getValue("pilot").supplies)
            assertEquals(300,state.stocks.outfits.getValue(1).activeShips!!.values.single().boats)
            state.fail=false
            val impact=TacticalCombat.settle(due,galaxy,nav,stocks,state.commit).single()
            assertEquals(weapon,impact.weapon)
            val fired=nav.snapshot(1).getValue("pilot")
            assertEquals(0,fired.supplies);assertEquals(24000,fired.charge!!.beam)
            assertEquals(if(weapon==TacticalWeapon.GUN)0L else 24000L,fired.charge.gun)
            assertTrue(impact.hit.destroyed>0)
            assertTrue(TacticalCombat.settle(due,galaxy,nav,stocks,state.commit).isEmpty())
            assertEquals(NavigationRejection.SUPPLY,TacticalCombat.accept(shot,due,galaxy,nav,stocks.snapshot(1)!!,state.commit,24).rejection)
        }
    }
    @Test fun missileNeedsNoBeamOrGunChargeButGunDoesAndCancelledMissileDoesNotSpend() {
        val state=state();state.players=state.players.mapValues {(_,p)->p.copy(charge=null)}
        val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stocks=production(state)
        val gun=request.copy(tacticalShoot=request.tacticalShoot!!.copy(arms=1))
        assertEquals(NavigationRejection.ENERGY,TacticalCombat.accept(gun,session,galaxy,nav,stocks.snapshot(1)!!,state.commit,24).rejection)
        val missile=request.copy(tacticalShoot=request.tacticalShoot!!.copy(arms=2))
        assertNotNull(TacticalCombat.accept(missile,session,galaxy,nav,stocks.snapshot(1)!!,state.commit,24).player)
        val stop=nav.apply(request.copy(tacticalShoot=null,tacticalStop=TacticalStop(listOf(1010000),emptyList())),session,stocks.snapshot(1))
        assertNull(stop.player!!.fire!!.pending)
        assertTrue(TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,stocks,state.commit).isEmpty())
        assertEquals(10000,nav.snapshot(1).getValue("pilot").supplies)
    }

    @Test fun personalShieldDamagePersistsAndFatalOverflowReturnsInDestroyerWithoutGhostFire() {
        val state=state()
        val victimPoint=TacticalPoint(180f,-95f)
        val victim=state.players.getValue("pilot").copy(characterId=10001,country=Power.EMPIRE.country,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE),returnBase=1000,
            energy=TacticalEnergy.ZERO,charge=null,hull=FlagshipHull(0,now),
            motion=TacticalMotion(2488,now,TacticalPose(victimPoint,Math.PI.toFloat()),0f,Math.PI.toFloat(),listOf(victimPoint)))
        state.players+= "victim" to victim
        val duel=session.copy(participants=session.participants+("victim" to Participant(Power.EMPIRE,false,connection="v")))
        var nav=Navigation(galaxy,state.nav,NavigationPolicy());var stock=production(state)
        val shot=request.copy(tacticalShoot=request.tacticalShoot!!.copy(target=victim.unitId))
        assertNotNull(TacticalCombat.accept(shot,duel,galaxy,nav,stock.snapshot(1)!!,state.commit,24).player)
        val first=TacticalCombat.settle(duel.copy(gameMillis=(now+120)*1000),galaxy,nav,stock,state.commit).single()
        assertEquals(victim.unitId,first.hit.target);assertEquals(0,first.hit.destroyed);assertEquals(0,first.hit.damaged)
        assertEquals(0,first.hit.shieldFace);assertTrue(first.hit.shieldDamage>0);assertNull(first.departed)
        val defended=nav.snapshot(1).getValue("victim")
        assertEquals(100,defended.hull!!.hp)
        assertTrue(defended.hull!!.levels()[0]<FlagshipHull.capacity(0))
        nav=Navigation(galaxy,state.nav,NavigationPolicy());assertEquals(defended,nav.snapshot(1).getValue("victim"))
        state.players=state.players.mapValues { (account,p)->if(account=="victim")p.copy(
            hull=p.hull!!.copy(hp=1,shields=List(6){0L}),
            fire=TacticalFire(1,TacticalPendingFire(1,2488,0,1010000,now+120,now+1000))) else
            p.copy(charge=TacticalCharge(now+120,24000)) }
        state.stocks=state.stocks.copy(tacticalCommands=state.stocks.tacticalCommands.copy(
            morale=mapOf(victim.unitId to 15, 1010000 to 70)))
        nav=Navigation(galaxy,state.nav,NavigationPolicy());stock=production(state)
        val nextSession=duel.copy(gameMillis=(now+120)*1000)
        assertNotNull(TacticalCombat.accept(shot,nextSession,galaxy,nav,stock.snapshot(1)!!,state.commit,24).player)
        val before=nav.snapshot(1);val stored=state.stocks
        state.fail=true
        assertFailsWith<java.io.IOException>{TacticalCombat.settle(duel.copy(gameMillis=(now+240)*1000),galaxy,nav,stock,state.commit)}
        assertEquals(before,nav.snapshot(1));assertEquals(stored,state.stocks);state.fail=false
        val fatal=TacticalCombat.settle(duel.copy(gameMillis=(now+240)*1000),galaxy,nav,stock,state.commit).single()
        assertEquals(1,fatal.hit.destroyed);assertEquals(1,fatal.hit.damaged)
        val returned=nav.snapshot(1).getValue("victim")
        assertEquals(returned,fatal.departed);assertEquals(2487,returned.grid);assertEquals(1000,returned.baseId)
        assertEquals(4,returned.mode);assertFalse(returned.cpPaused);assertNull(returned.fire);assertNull(returned.motion)
        assertEquals(3,returned.hull!!.kind);assertEquals(now+240+86400,returned.hull!!.woundedUntil)
        assertEquals(mapOf(1010000 to 70),state.stocks.tacticalCommands.morale)
        assertEquals(0,returned.supplies);assertEquals(setOf(10000,10001),fatal.recipients.keys)
        assertTrue(TacticalCombat.settle(duel.copy(gameMillis=(now+1200)*1000),galaxy,nav,stock,state.commit).isEmpty())
        val exit=NavigationRequest("victim",10001,Power.EMPIRE,"v",1,place=PlaceMove.SwitchMode(5),shipKind=3,unitId=1010001,captainCard=59)
        assertEquals(NavigationRejection.WOUNDED,nav.apply(exit,duel.copy(gameMillis=(now+300)*1000),stock.snapshot(1)).rejection)
        assertNotNull(nav.apply(exit,duel.copy(gameMillis=(now+240+86400)*1000),stock.snapshot(1)).player)
    }

    @Test fun sameGridDefeatPublishesOnlyTheVictimsCombatExit() = runBlocking {
        val state = state()
        val home = galaxy.bases.single().copy(grid = 2488)
        val world = Galaxy(galaxy.systems, listOf(home), galaxy.starts)
        val target = TacticalPoint(180f, -95f)
        val victim = Navigator(1, 10001, Power.EMPIRE, 2488, 1000, 1000, 900, cpPaused = true,
            hull = FlagshipHull(0, now, hp = 1, shields = List(6) { 0L }),
            motion = TacticalMotion(2488, now, TacticalPose(target, 0f), 0f, 0f, listOf(target)))
            .copy(returnBase = home.id)
        state.players = mapOf("victim" to victim, "pilot" to state.players.getValue("pilot").copy(
            hull = FlagshipHull(89, now),
            fire = TacticalFire(1, TacticalPendingFire(1, 2488, 89, victim.unitId, now, now + 120))))
        state.stocks = state.stocks.copy(outfits = emptyMap(), warehouses = emptyMap())
        val impacts = mutableListOf<TacticalImpact>()
        val ends = mutableListOf<TacticalEnd>()
        val engine = WorldEngine(this, rules, ticking = false, galaxy = world, navigationStore = state.nav,
            stockStore = state.inventory, worldStateCommitter = state.commit)
        engine.observeCombat(impacts::add)
        engine.observeTacticalEnds(ends::add)
        try {
            for ((account, player) in state.players) {
                val joined = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join(account, player.power, false, joined, account))
                assertEquals(Admission.ACCEPTED, joined.await())
            }
            engine.submit(WorldCommand.AdvanceTo(5000))
        } finally {
            engine.drainAndClose()
        }
        val returned = assertNotNull(impacts.single().departed)
        assertEquals(home.id, returned.baseId)
        assertEquals(victim.grid, returned.grid)
        assertEquals("victim", impacts.single().recipients[victim.characterId])
        assertTrue(ends.none { it.character == victim.characterId })
    }

    @Test fun aRebelFlagshipShootsARegularFlagshipOfItsOwnSide() {
        val state=state()
        // U-10: the pilot leads a revolt holding base 1000; the victim stays in the alliance's regular camp.
        state.stocks=state.stocks.copy(bases=state.stocks.bases+(1000 to PlanetStock(1000,3,1,100,100,100)),
            social=state.stocks.social.copy(coup=CoupState(mapOf(10000 to Plot(10000,CountryId(3),0,revolt=1,bases=setOf(1000))))))
        val victimPoint=TacticalPoint(180f,-95f)
        val victim=state.players.getValue("pilot").copy(characterId=10001,returnBase=1000,energy=TacticalEnergy.ZERO,charge=null,
            hull=FlagshipHull(89,now),motion=TacticalMotion(2488,now,TacticalPose(victimPoint,Math.PI.toFloat()),0f,Math.PI.toFloat(),listOf(victimPoint)))
        state.players+= "victim" to victim
        val duel=session.copy(participants=session.participants+("victim" to Participant(Power.ALLIANCE,false,connection="v")))
        val nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
        val shot=request.copy(tacticalShoot=request.tacticalShoot!!.copy(target=victim.unitId))
        assertNotNull(TacticalCombat.accept(shot,duel,galaxy,nav,stock.snapshot(1)!!,state.commit,24).player)
        val hit=TacticalCombat.settle(duel.copy(gameMillis=(now+120)*1000),galaxy,nav,stock,state.commit).single()
        assertEquals(victim.unitId,hit.hit.target);assertTrue(hit.hit.shieldDamage>0)
        assertTrue(nav.snapshot(1).getValue("victim").hull!!.levels()[0]<FlagshipHull.capacity(89))
    }
    @Test fun aFlagshipShootsAPirateUnitAndGridContactIncludesPirates() {
        val state=state()
        val point=TacticalPoint(0f,-2f)
        state.players=state.players.mapValues {(_,p)->p.copy(motion=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point)))}
        state.stocks=state.stocks.copy(social=state.stocks.social.copy(pirates=PirateState(mapOf(1 to PirateFleet(1,2488,now,
            listOf(PirateUnit(900003,300),PirateUnit(900004,300),PirateUnit(900005,300)))),2,now+86400)))
        assertTrue(GridForces.collect(galaxy,1,state.players.values,state.stocks).getValue(2488).any {it.camp==Camp.PIRATES})
        var nav=Navigation(galaxy,state.nav,NavigationPolicy());val stock=production(state)
        val shot=request.copy(tacticalShoot=request.tacticalShoot!!.copy(target=900004))
        assertEquals(NavigationRejection.RANGE,TacticalCombat.accept(shot,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24).rejection)
        val inside=TacticalPoint(0f,-0.2f)
        state.players=state.players.mapValues {(_,p)->p.copy(motion=TacticalMotion(2488,now,TacticalPose(inside,0f),0f,0f,listOf(inside)))}
        nav=Navigation(galaxy,state.nav,NavigationPolicy())
        val accepted=TacticalCombat.accept(shot,session,galaxy,nav,stock.snapshot(1)!!,state.commit,24)
        assertNotNull(accepted.player,"${accepted.rejection}")
        val hit=TacticalCombat.settle(session.copy(gameMillis=(now+120)*1000),galaxy,nav,stock,state.commit).single {it.hit.target==900004}
        assertEquals(1010000,hit.source)
        assertTrue(state.stocks.social.pirates.unit(900004)!!.second.boats<300)
    }
    @Test fun piratesCounterattackANearbyFlagshipEveryTwentySeconds() {
        val state=state()
        val point=TacticalPoint(0f,-8f)
        state.players=state.players.mapValues {(_,p)->p.copy(hull=FlagshipHull(89,now),
            motion=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point)))}
        state.stocks=state.stocks.copy(social=state.stocks.social.copy(pirates=PirateState(mapOf(1 to PirateFleet(1,2488,now,
            listOf(PirateUnit(900003,300)))),2,now+86400)))
        val players=state.players.toMutableMap();val impacts=mutableListOf<TacticalImpact>()
        val fired=PirateActions.counter(session,galaxy,players,state.stocks,now+100,impacts)
        assertEquals(1,impacts.size);assertEquals(1010000,impacts.single().hit.target)
        assertTrue(players.getValue("pilot").hull!!.levels().sum()<FlagshipHull(89,now).levels().sum())
        assertEquals(now+100,fired.social.pirates.fleets.getValue(1).firedAt)
        assertTrue(PirateActions.counter(session,galaxy,players,fired,now+110,mutableListOf()).let {it==fired},"waits 20 seconds")
    }
}
