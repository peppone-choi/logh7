package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.math.hypot

class TacticalOfflineAiTest {
    private val baseSession=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"one")}.exportState()
    private val now=baseSession.gameMillis/1000
    private val offline=baseSession.copy(participants=baseSession.participants.mapValues {(_,p)->p.copy(online=false,connection=null)})
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Battle",2)),
        listOf(GalaxyBase(1000,2488,80,1,"Fortress",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2488})
    private fun motion(x:Float=0f,y:Float=0f)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,y),0f),0f,0f,listOf(TacticalPoint(x,y)))
    private fun players()=mutableMapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,cpPaused=true,
        hull=FlagshipHull(0,now,clockScale=1),motion=motion(),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM)))
    private fun stocks(mission:Int=0):PlanetStocks {
        fun outfit(id:Int,power:Int,unit:Int,kind:Int,commander:Int,y:Float)=OutfitStock(id,power,OutfitKind.FLEET,1000,id*10,
            commander,UnitInventory(mapOf(UnitStack(kind) to 1)),supplies=100,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(unit,UnitStack(kind),
                tactical=NpcTacticalState(motion(0f,y),now,now)))),position=OutfitPosition(2488))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)),
            outfits=mapOf(1 to outfit(1,2,1,32,10000,.5f),2 to outfit(2,3,99,119,0,30f)),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100,tacticalCommands=TacticalCommandState(aiSwitches=mapOf(10000 to 16),
                missions=mapOf(10000 to TacticalMissionState(10000,mission,if(mission==1)0 else 1,if(mission==1)1000 else 99,now,grid=2488))))
    }
    @Test fun enabledOfflineAiIssuesRealOwnedMovementAndAttackWithoutSpendingCpAndHonorsCadence() {
        val players=players();val stock=stocks();val events=mutableListOf<TacticalCommandResult>()
        val next=TacticalOfflineAi.advance(offline,galaxy,players,stock,1,events)
        val p=players.getValue("pilot")
        assertNotNull(p.attack);assertEquals(TacticalPoint(0f,28f),p.motion!!.waypoints.single())
        assertTrue(p.energy!!.allocated<=Flagships.find(0)!!.number("出力:出力"))
        assertEquals(100,p.pcp);assertEquals(100,p.mcp);assertEquals(10000,p.supplies)
        val own=next.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(10000,own.motionCommander);assertNotNull(own.attack)
        assertEquals(stock.outfits.getValue(2),next.outfits.getValue(2))
        assertTrue(events.single().stateOnly);assertEquals(setOf(1010000,1),events.single().motions.keys)
        val saved=players.toMap()
        assertEquals(next,TacticalOfflineAi.advance(offline.copy(gameMillis=(now+1)*1000),galaxy,players,next,1,events))
        assertEquals(saved,players);assertEquals(1,events.size)
    }
    @Test fun offlineAiIgnoresADeceasedCommanderInAnOlderTacticalSave() {
        val players=players()
        val before=players.toMap()
        val plain=stocks()
        val stock=plain.copy(strategic=plain.strategic.copy(people=mapOf(10000 to StrategicPerson(deceasedAt=1))))
        val events=mutableListOf<TacticalCommandResult>()
        assertFalse(TacticalOfflineAi.eligible(offline,"pilot",players.getValue("pilot"),stock))
        assertEquals(stock,TacticalOfflineAi.advance(offline,galaxy,players,stock,1,events))
        assertEquals(before,players)
        assertTrue(events.isEmpty())
    }

    @Test fun offlinePatrolAndAttackMissionsRequireDirectOrSharedContact() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases.map {it.copy(radius=25f)},galaxy.starts)
        for(mission in listOf(0,3))for((observer,scout) in listOf(21f to 22f,16f to 22f,22f to 16f)) {
            val players=players()
            players["pilot"]=players.getValue("pilot").copy(motion=motion(0f,observer))
            val plain=stocks(mission)
            val enemy=plain.outfits.getValue(2)
            val ship=enemy.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(0f,19f),now,now))
            val own=plain.outfits.getValue(1)
            val scoutShip=own.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(0f,scout),now,now))
            val stock=plain.copy(outfits=mapOf(1 to own.copy(activeShips=ActiveShipUnits(listOf(scoutShip))),
                2 to enemy.copy(activeShips=ActiveShipUnits(listOf(ship)))))
            TacticalOfflineAi.advance(offline,world,players,stock,1,mutableListOf())
            val pilot=players.getValue("pilot")
            if(observer>20f && scout>20f) {
                assertNull(pilot.attack)
                assertEquals(0f,pilot.motion!!.velocity)
            } else {
                assertNotNull(pilot.attack)
                assertTrue(pilot.motion!!.velocity>0f)
            }
        }
    }

    @Test fun interceptionWaitsForItsTargetInsteadOfAttackingAnUnrelatedVisibleEnemy() {
        val players=players()
        val plain=stocks(3)
        val command=plain.tacticalCommands
        val missing=command.missions.getValue(10000).copy(target=98)
        val stock=plain.copy(tacticalCommands=command.copy(missions=mapOf(10000 to missing)))
        val next=TacticalOfflineAi.advance(offline,galaxy,players,stock,1,mutableListOf())
        assertNull(players.getValue("pilot").attack)
        assertEquals(0f,players.getValue("pilot").motion!!.velocity)
        assertNull(next.outfits.getValue(1).activeShips!!.values.single().attack)
    }

    @Test fun unassignedPlanetGarrisonDefendsItsPostUntilRelieved() {
        val base=galaxy.bases.single().copy(kind=0,radius=15f)
        val world=Galaxy(galaxy.systems,listOf(base),galaxy.starts)
        val plain=stocks()
        for (appointed in listOf(true,false)) {
            val players=players()
            val stock=plain.copy(bases=mapOf(base.id to plain.bases.getValue(base.id)
                .copy(garrisonCommanderId=if(appointed)10000 else 0)),
                tacticalCommands=plain.tacticalCommands.copy(missions=emptyMap()))
            TacticalOfflineAi.advance(offline,world,players,stock,1,mutableListOf())
            val end=players.getValue("pilot").motion!!.waypoints.single()
            val point=TacticalCommandOperations.basePoint(base,now,stock)
            if(appointed) assertTrue(hypot(end.x-point.x,end.y-point.y)<=3.001f)
            else assertEquals(TacticalPoint(0f,28f),end)
        }
    }

    @Test fun onlyTheActualFortressCommanderDefaultsToDefendingTheMobileFortress() {
        val base=galaxy.bases.single().copy(mobile=true)
        val world=Galaxy(galaxy.systems,listOf(base),galaxy.starts)
        val plain=stocks()
        val point=TacticalPoint(15f,0f)
        for (appointed in listOf(true,false)) {
            val players=players()
            val stock=plain.copy(bases=mapOf(base.id to plain.bases.getValue(base.id).copy(garrisonCommanderId=10000)),
                tacticalCommands=plain.tacticalCommands.copy(missions=emptyMap(),
                    fortresses=mapOf(base.id to TacticalFortressMotion(base.grid,motion(point.x,point.y)))),
                strategic=StrategicState(offices=if(appointed)listOf(OfficeHolding(51,base.id,10000)) else emptyList()))
            TacticalOfflineAi.advance(offline,world,players,stock,1,mutableListOf())
            val end=players.getValue("pilot").motion!!.waypoints.single()
            assertEquals(if(appointed)TacticalPoint(12f,0f) else TacticalPoint(0f,28f),end)
        }
    }

    @Test fun missionsFromAnotherGridDoNotOverrideLocalOfflinePatrol() {
        for (kind in 3..5) {
            val players=players()
            val pilot=players.getValue("pilot")
            val plain=stocks(kind)
            val command=plain.tacticalCommands
            val stale=command.missions.getValue(10000).copy(grid=2487,target=98)
            val stock=plain.copy(tacticalCommands=command.copy(missions=mapOf(10000 to stale)))
            assertEquals(listOf(99),TacticalOfflineAi.automaticTargets(offline,"pilot",pilot,stock,listOf(99)))
            val next=TacticalOfflineAi.advance(offline,galaxy,players,stock,1,mutableListOf())
            val acting=players.getValue("pilot")
            assertNotNull(acting.attack)
            assertEquals(TacticalPoint(0f,28f),acting.motion!!.waypoints.single())
            assertNotNull(next.outfits.getValue(1).activeShips!!.values.single().attack)
        }
    }

    @Test fun anExistingFleetAttackHonorsTheOfflineMissionBeforeTheNextAiDecision() {
        for(mission in listOf(3,4,5)) assertExistingFleetAttackIsSuppressed(mission)
    }

    @Test fun commanderConfusionSuppressesExistingOfflineFireBeforeTheNextAiDecision() {
        assertExistingFleetAttackIsSuppressed(3,confused=true)
    }

    private fun assertExistingFleetAttackIsSuppressed(mission:Int,confused:Boolean=false) {
        val plain=stocks(mission)
        val own=plain.outfits.getValue(1)
        val enemy=plain.outfits.getValue(2)
        val shooting=own.activeShips!!.values.single().copy(
            attack=TacticalUnitAttack(10000,TacticalAttackOrder(2,now,now,now)))
        val nearby=enemy.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(0f,3f),now,now))
        val command=plain.tacticalCommands
        val assigned=command.missions.getValue(10000).let {if(mission==3 && !confused)it.copy(target=98) else it}
        val pilot=players().getValue("pilot")
        val morale=if(confused)mapOf(pilot.unitId to 20) else emptyMap()
        val stock=plain.copy(outfits=mapOf(1 to own.copy(activeShips=ActiveShipUnits(listOf(shooting))),
            2 to enemy.copy(activeShips=ActiveShipUnits(listOf(nearby)))),
            tacticalCommands=command.copy(missions=mapOf(10000 to assigned),morale=morale,
                aiNextAt=mapOf(10000 to now+5)))
        assertTrue(TacticalOfflineAi.automaticTargets(offline,"pilot",pilot,stock,listOf(99))
            .none {it==99})
        val impacts=mutableListOf<TacticalImpact>()
        TacticalFleetAttack.advance(offline,galaxy,players(),stock,1,impacts)
        assertTrue(impacts.isEmpty())
        TacticalFleetAttack.advance(baseSession,galaxy,players(),stock,1,impacts)
        assertTrue(impacts.any {it.hit.target==99},"the enemy is a valid target while the commander is online")
    }

    @Test fun occupationAnchorsTroopCarriersAtTheEnemyBaseAndThenLandsTheirTroops() {
        // The fortress belongs to the alliance; the pilot's mission 2 (占領) targets it with a light infantry unit aboard ship 1.
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),carrier=1),ActiveGroundUnit(6,UnitStack(2),carrier=1)))
        val plain=stocks(2)
        val outfit=plain.outfits.getValue(1)
        val start=plain.copy(bases=mapOf(1000 to PlanetStock(1000,3,0,100,0,0)),
            outfits=plain.outfits+(1 to outfit.copy(units=UnitInventory(outfit.units.ships,troops.quantities()),activeGround=troops)),
            tacticalCommands=plain.tacticalCommands.copy(missions=mapOf(10000 to TacticalMissionState(10000,2,1,1000,now,grid=2488))))
        val anchored=TacticalOfflineAi.advance(offline,galaxy,players(),start,1,mutableListOf())
        val ship=anchored.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(TacticalShipMode.ANCHORED,ship.posture!!.order!!.mode);assertEquals(1000,ship.posture!!.order!!.base)
        assertEquals(now+10,ship.posture!!.order!!.readyAt,"the player's 10-second stance wait")
        assertTrue(anchored.outfits.getValue(1).activeGround!!.values.all {it.order==null},"no landing before the carrier is anchored")
        // Once the stance order has executed, the next decision lands the admissible troops.
        val docked=anchored.copy(outfits=anchored.outfits+(1 to anchored.outfits.getValue(1).let {o->o.copy(activeShips=ActiveShipUnits(
            o.activeShips!!.values.map {it.copy(posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,1000))}))}),
            tacticalCommands=anchored.tacticalCommands.copy(aiNextAt=emptyMap()))
        val landed=TacticalOfflineAi.advance(offline.copy(gameMillis=(now+10)*1000),galaxy,players(),docked,1,mutableListOf())
        val orders=landed.outfits.getValue(1).activeGround!!.values.associate {it.id to it.order}
        assertEquals(TacticalGroundAction.DEPLOY,orders.getValue(5)!!.action);assertEquals(1000,orders.getValue(5)!!.base)
        assertNull(orders.getValue(6),"armored troops stay aboard at a fortress (W p.51)")
    }
    @Test fun occupationRespectsRangeConfusionAndExistingOrdersBeforeAnchoringOrLanding() {
        for(anchored in listOf(false,true))for(block in listOf("range","confusion","order","shot","ground")) {
            val plain=stocks(2)
            val outfit=plain.outfits.getValue(1)
            val troop=ActiveGroundUnit(5,UnitStack(0),carrier=1)
            val troops=ActiveGroundUnits(if(block=="ground")listOf(
                troop.copy(order=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2488,1,1000,now,now+25)),troop.copy(id=6)) else listOf(troop))
            val ship=outfit.activeShips!!.values.single().copy(
                posture=if(anchored)TacticalPosture(2488,TacticalShipMode.ANCHORED,1000) else null,
                shot=if(block=="shot")TacticalUnitShot(10000,TacticalWeapon.BEAM,99,now,now+5) else null)
            val owned=outfit.copy(units=UnitInventory(outfit.units.ships,troops.quantities()),
                activeShips=ActiveShipUnits(listOf(ship)),activeGround=troops)
            val start=plain.copy(bases=mapOf(1000 to PlanetStock(1000,3,0,100,0,0)),outfits=plain.outfits+(1 to owned),
                tacticalCommands=plain.tacticalCommands.copy(
                    missions=mapOf(10000 to TacticalMissionState(10000,2,1,1000,now,grid=2488)),
                    morale=if(block=="confusion")mapOf(1 to 20) else emptyMap(),
                    orders=if(block=="order")listOf(TacticalQueuedCommand(10000,2488,now,now+50,TacticalCommand(0x407,units=listOf(1)))) else emptyList()))
            val players=players()
            if(block=="range")players["pilot"]=players.getValue("pilot").copy(commandRange=TacticalCommandRange(now,0f))
            val range=players.getValue("pilot").commandRange
            val next=TacticalOfflineAi.advance(offline,galaxy,players,start,1,mutableListOf())
            assertEquals(owned,next.outfits.getValue(1),"$block, anchored=$anchored: keep the ship, shot and troops unchanged")
            assertEquals(range,players.getValue("pilot").commandRange,"a blocked unit must not consume command range")
        }
    }
    @Test fun onlineOrDisabledCharactersAreNotDrivenAndPendingPersonalShotsArePreserved() {
        for((session,stock) in listOf(baseSession to stocks(),offline to stocks().copy(tacticalCommands=TacticalCommandState()))) {
            val p=players();val before=p.toMap()
            assertEquals(stock,TacticalOfflineAi.advance(session,galaxy,p,stock,1,mutableListOf()))
            assertEquals(before,p)
        }
        val p=players();val shot=TacticalPendingFire(1,2488,0,99,now,now+5)
        p["pilot"]=p.getValue("pilot").copy(fire=TacticalFire(1,pending=shot))
        val before=p.toMap();TacticalOfflineAi.advance(offline,galaxy,p,stocks(),1,mutableListOf())
        assertEquals(before,p)
    }
    @Test fun reconnaissanceRoutesFlagshipAndFleetAroundTheStar() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases,galaxy.starts)
        val observers=players()
        observers["pilot"]=observers.getValue("pilot").copy(motion=motion(0f,-21f))
        val initial=stocks(4)
        val own=initial.outfits.getValue(1)
        val ship=own.activeShips!!.values.single().let {u->
            u.copy(tactical=u.tactical!!.copy(motion=motion(1f,-21f)))
        }
        val stock=initial.copy(outfits=initial.outfits+(1 to own.copy(activeShips=ActiveShipUnits(listOf(ship)))))
        val after=TacticalOfflineAi.advance(offline,world,observers,stock,1,mutableListOf())
        val flagship=observers.getValue("pilot").motion!!
        val escort=after.outfits.getValue(1).activeShips!!.values.single().tactical!!.motion
        for(route in listOf(flagship,escort)) {
            assertTrue(route.velocity>0f)
            assertTrue(route.waypoints.size>1)
            assertFalse(TacticalTerrain.pathBlocked(world,route.grid,route.origin.position,route.waypoints))
            assertTrue(route.waypoints.last().y>29f)
        }
    }

    @Test fun reconnaissanceApproachesWithinMeritRangeAndWithdrawalStartsWarpPreparation() {
        val scouts=players();TacticalOfflineAi.advance(offline,galaxy,scouts,stocks(4),1,mutableListOf())
        assertNull(scouts.getValue("pilot").attack)
        assertEquals(TacticalPoint(0f,29.8f),scouts.getValue("pilot").motion!!.waypoints.single())
        val p=players();p["pilot"]=p.getValue("pilot").copy(motion=motion(0f,260f))
        TacticalOfflineAi.advance(offline,galaxy,p,stocks(5),1,mutableListOf())
        val withdrawn=p.getValue("pilot")
        assertNotNull(withdrawn.retreat);assertEquals(50,withdrawn.energy!!.values[1]);assertNull(withdrawn.attack)
        assertEquals(now+155,withdrawn.retreat!!.completesAt)
        val defenders=players();val s=stocks(1)
        TacticalOfflineAi.advance(offline,galaxy,defenders,s,1,mutableListOf())
        val end=defenders.getValue("pilot").motion!!.waypoints.single()
        val base=TacticalCommandOperations.basePoint(galaxy.bases.single(),now,s)
        assertTrue(hypot(end.x-base.x,end.y-base.y)<=3.001f)
    }
}
