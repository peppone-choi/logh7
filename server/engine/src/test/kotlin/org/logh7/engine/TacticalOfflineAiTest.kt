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
    @Test fun offlineAiPursuesTheRemainingEnemyAndIgnoresAnEliminatedMissionTarget() {
        val third=CountryId(4)
        val session=offline.copy(countries=offline.countries+third,
            eliminated=mapOf(Power.ALLIANCE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        for(mission in listOf(0,3)) {
            val initial=stocks(mission)
            val defeated=initial.outfits.getValue(2)
            val farther=defeated.activeShips!!.values.single().copy(id=100,
                tactical=NpcTacticalState(motion(0f,60f),now,now))
            val surviving=defeated.copy(id=3,country=third,warehouseId=30,activeShips=ActiveShipUnits(listOf(farther)))
            val inventory=initial.copy(outfits=initial.outfits+(3 to surviving),nextShipId=101,
                warehouses=initial.warehouses+(30 to initial.warehouses.getValue(20).copy(id=30,country=third,outfitId=3)))
            val pilots=players()
            val next=TacticalOfflineAi.advance(session,galaxy,pilots,inventory,1,mutableListOf())
            val pilot=pilots.getValue("pilot")
            val own=next.outfits.getValue(1).activeShips!!.values.single()
            if(mission==0) {
                assertEquals(TacticalPoint(0f,58f),pilot.motion!!.waypoints.single())
                assertNotNull(pilot.attack)
                assertNotNull(own.attack)
            } else {
                assertEquals(TacticalPoint(0f,0f),pilot.motion!!.waypoints.single())
                assertNull(pilot.attack)
                assertNull(own.attack)
            }
            assertEquals(defeated,next.outfits.getValue(2))
        }
    }
    @Test fun offlineAiOnlyHonorsAnchoredPostureInTheCurrentGrid() {
        val original = stocks()
        val fleet = original.outfits.getValue(1)
        val unit = fleet.activeShips!!.values.single()
        for (postureGrid in listOf(2487, 2488)) {
            val pilots = players()
            val pilot = pilots.getValue("pilot")
            pilots["pilot"] = pilot.copy(posture = TacticalPosture(2487, TacticalShipMode.ANCHORED, 1001))
            val anchored = unit.copy(posture = TacticalPosture(postureGrid, TacticalShipMode.ANCHORED,
                if (postureGrid == 2488) 1000 else 1001))
            val before = original.copy(outfits = original.outfits + (1 to fleet.copy(activeShips = ActiveShipUnits(listOf(anchored)))))
            val after = TacticalOfflineAi.advance(offline, galaxy, pilots, before, 1, mutableListOf())

            assertNotNull(pilots.getValue("pilot").attack)
            val nextUnit = after.outfits.getValue(1).activeShips!!.values.single()
            if (postureGrid == 2488) assertEquals(anchored, nextUnit)
            else assertNotNull(nextUnit.attack)
        }
        val pilots = players()
        val anchored = pilots.getValue("pilot").copy(posture = TacticalPosture(2488, TacticalShipMode.ANCHORED, 1000))
        pilots["pilot"] = anchored
        TacticalOfflineAi.advance(offline, galaxy, pilots, original, 1, mutableListOf())
        assertEquals(anchored, pilots.getValue("pilot"))
    }

    @Test fun detainedCommanderCannotDelegateOrChooseAutomaticTargets() {
        val players=players()
        val pilot=players.getValue("pilot")
        val plain=stocks()
        val detention=Detention(pilot.characterId,Power.EMPIRE.country,now,now+Justice.DAY)
        val detained=plain.copy(social=plain.social.copy(justice=JusticeState(detentions=mapOf(pilot.characterId to detention))))
        val events=mutableListOf<TacticalCommandResult>()
        assertEquals(detained,TacticalOfflineAi.advance(offline,galaxy,players,detained,1,events))
        assertEquals(pilot,players.getValue("pilot"))
        assertTrue(events.isEmpty())
        assertTrue(TacticalOfflineAi.automaticTargets(offline,"pilot",pilot,detained,listOf(99)).isEmpty())
    }

    @Test fun pendingManualRotationsSurviveOfflineAiAndShipsResumeAfterCompletion() {
        val pilots=players()
        val pilot=pilots.getValue("pilot")
        val flagshipTurn=motion().copy(direction=1f,rotation=TacticalRotation(TacticalManeuver.TURN,pilot.unitId,10))
        pilots["pilot"]=pilot.copy(motion=flagshipTurn)
        val plain=stocks()
        val outfit=plain.outfits.getValue(1)
        val ship=outfit.activeShips!!.values.single()
        val reverse=motion(0f,.5f).copy(direction=Math.PI.toFloat(),
            rotation=TacticalRotation(TacticalManeuver.REVERSE,ship.id,10))
        val rotating=ship.copy(motionCommander=pilot.characterId,tactical=ship.tactical!!.copy(motion=reverse))
        val other=ship.copy(id=2,tactical=ship.tactical.copy(motion=motion(0f,1f)))
        val units=ActiveShipUnits(listOf(rotating,other))
        val before=plain.copy(outfits=plain.outfits+(outfit.id to outfit.copy(activeShips=units,units=UnitInventory(units.quantities()))))
        val decisions=mutableListOf<TacticalCommandResult>()
        val movement=mutableListOf<TacticalMovement>()
        val pending=TacticalMotionProgress.advance(offline,galaxy,pilots,before,movement)
        val during=TacticalOfflineAi.advance(offline,galaxy,pilots,pending,1,decisions)
        assertEquals(flagshipTurn,pilots.getValue("pilot").motion)
        assertEquals(rotating,during.outfits.getValue(1).activeShips!!.values.single {it.id==ship.id})
        assertEquals(setOf(other.id),decisions.single().motions.keys)
        assertTrue(movement.isEmpty())

        val due=offline.copy(gameMillis=(now+10)*1000)
        val complete=TacticalMotionProgress.advance(due,galaxy,pilots,during,movement)
        assertEquals(1f,pilots.getValue("pilot").motion!!.direction)
        assertEquals(Math.PI.toFloat(),complete.outfits.getValue(1).activeShips!!.values.single {it.id==ship.id}.tactical!!.motion.direction)
        assertTrue(movement.any {it.unit==pilot.unitId})
        assertTrue(movement.any {it.unit==ship.id && it.reverseTarget!=null})

        TacticalOfflineAi.advance(due,galaxy,pilots,complete,1,decisions)
        assertTrue(pilot.unitId in decisions.last().motions)
        assertTrue(ship.id in decisions.last().motions)
    }

    @Test fun mismatchedParticipantCountryCannotDriveSavedFlagshipOrFleet() {
        val pilots=players()
        val before=pilots.toMap()
        val stock=stocks(3)
        val mismatch=offline.copy(participants=offline.participants.mapValues {(_,member)->member.copy(country=Power.ALLIANCE.country)})
        val events=mutableListOf<TacticalCommandResult>()
        assertSame(stock,TacticalOfflineAi.advance(mismatch,galaxy,pilots,stock,1,events))
        assertEquals(before,pilots)
        assertTrue(events.isEmpty())
        val pilot=pilots.getValue("pilot")
        assertEquals(listOf(12,99),TacticalOfflineAi.automaticTargets(mismatch,"pilot",pilot,stock,listOf(99,12)))
        val defeated=mismatch.copy(eliminated=mapOf(Power.EMPIRE.country to
            CountryDefeat(EndReason.CAPITAL_CAPTURED,now,Power.ALLIANCE.country)))
        assertTrue(TacticalOfflineAi.automaticTargets(defeated,"pilot",pilot,stock,listOf(99,12)).isEmpty())
    }

    @Test fun eliminatedCountryDoesNotIssueOfflineOrdersOrSelectAutomaticTargets() {
        val players=players()
        val pilot=players.getValue("pilot")
        val stock=stocks()
        val events=mutableListOf<TacticalCommandResult>()
        val defeated=offline.copy(eliminated=mapOf(Power.EMPIRE.country to
            CountryDefeat(EndReason.CAPITAL_CAPTURED,now,Power.ALLIANCE.country)))
        assertEquals(stock,TacticalOfflineAi.advance(defeated,galaxy,players,stock,1,events))
        assertEquals(pilot,players.getValue("pilot"))
        assertTrue(events.isEmpty())
        assertTrue(TacticalOfflineAi.automaticTargets(defeated,"pilot",pilot,stock,listOf(99)).isEmpty())
        assertEquals(listOf(99),TacticalOfflineAi.automaticTargets(offline,"pilot",pilot,stock,listOf(99)))
    }
    @Test fun nearbyOfflineSupportUsesTheNormalDelayAndConsumesRealCargo() {
        for(action in TacticalSupportAction.entries)for(targetAccount in listOf("pilot","ally")) {
            val players=players()
            val pilot=players.getValue("pilot")
            val needy=pilot.copy(supplies=9500,hull=pilot.hull!!.copy(hp=40))
            players[targetAccount]=if(targetAccount=="pilot")needy else needy.copy(characterId=10001,motion=motion(0f,.7f))
            players["unavailable"]=needy.copy(characterId=9999,cpPaused=false,motion=null,commandRange=null)
            val stock=stocks(1)
            val original=stock.outfits.getValue(1)
            val name=if(action==TacticalSupportAction.REPAIR)"工作艦" else "輸送艦"
            val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith(name)}.kind
            val stack=UnitStack(kind)
            val ship=ActiveShipUnit(1,stack,tactical=NpcTacticalState(motion(0f,.5f),now,now),supplies=500)
            val state=stock.copy(outfits=stock.outfits+(1 to original.copy(
                units=UnitInventory(mapOf(stack to 1)),activeShips=ActiveShipUnits(listOf(ship)),supplies=500)))
            val ordered=TacticalOfflineAi.advance(offline,galaxy,players,state,1,mutableListOf())
            val pending=ordered.outfits.getValue(1)
            val targetId=players.getValue(targetAccount).unitId
            val order=TacticalSupportOrder(action,10000,targetId,2488,now,now+5,issuedByOfflineAi=true)
            assertEquals(order,pending.activeShips!!.values.single().support)
            assertEquals(500,pending.supplies)
            val disabled=ordered.copy(tacticalCommands=ordered.tacticalCommands.copy(aiSwitches=emptyMap()))
            for((current,reserved) in listOf(baseSession to ordered,offline to disabled)) {
                val pilots=players.toMutableMap()
                val notices=mutableListOf<TacticalSupportResult>()
                val cancelled=TacticalSupportOperations.advance(current.copy(gameMillis=(now+1)*1000),galaxy,pilots,reserved,notices)
                val outfit=cancelled.outfits.getValue(1)
                assertTrue(notices.single().cancelled)
                assertNull(outfit.activeShips!!.values.single().support)
                assertEquals(500,OutfitSupplies.distribution(outfit).getValue(ship.id))
                assertEquals(players,pilots)
                val reenabled=cancelled.copy(tacticalCommands=ordered.tacticalCommands)
                notices.clear()
                assertEquals(reenabled,TacticalSupportOperations.advance(offline.copy(gameMillis=(now+5)*1000),
                    galaxy,pilots,reenabled,notices))
                assertTrue(notices.isEmpty())
            }
            val manual=pending.copy(activeShips=ActiveShipUnits(listOf(
                pending.activeShips.values.single().copy(support=order.copy(issuedByOfflineAi=false)))))
            val manualStock=disabled.copy(outfits=disabled.outfits+(pending.id to manual))
            val manualResults=mutableListOf<TacticalSupportResult>()
            val manuallyFinished=TacticalSupportOperations.advance(baseSession.copy(gameMillis=(now+5)*1000),
                galaxy,players.toMutableMap(),manualStock,manualResults)
            assertFalse(manualResults.single().cancelled)
            assertTrue(manuallyFinished.outfits.getValue(1).supplies<500)
            val result=mutableListOf<TacticalSupportResult>()
            val finished=TacticalSupportOperations.advance(offline.copy(gameMillis=(now+5)*1000),galaxy,players,ordered,result)
            assertFalse(result.single().cancelled)
            assertNull(finished.outfits.getValue(1).activeShips!!.values.single().support)
            if(action==TacticalSupportAction.SUPPLY) {
                assertEquals(10000,players.getValue(targetAccount).supplies)
                assertEquals(0,finished.outfits.getValue(1).supplies)
            } else {
                assertEquals(100,players.getValue(targetAccount).hull!!.hp)
                val cost=Flagships.find(0)!!.number("修理消費物資(１隻):修理消費物資(１隻)")
                assertEquals(500-cost,finished.outfits.getValue(1).supplies)
            }
        }
    }

    @Test fun offlineTransportsSplitOrdersBetweenFlagships() {
        for(pending in listOf(false,true)) {
            val players=players()
            val pilot=players.getValue("pilot").copy(supplies=9500)
            val ally=pilot.copy(characterId=10001,motion=motion(0f,.7f))
            players["pilot"]=pilot
            players["ally"]=ally
            val stock=stocks(1)
            val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind
            val source=ActiveShipUnit(1,UnitStack(kind),tactical=NpcTacticalState(motion(0f,.5f),now,now),supplies=500)
            val action=TacticalSupportAction.SUPPLY
            val first=source.copy(support=if(pending)TacticalSupportOrder(action,10000,pilot.unitId,2488,now,now+5) else null)
            val own=stock.outfits.getValue(1).copy(units=UnitInventory(mapOf(source.stack to 2)),
                activeShips=ActiveShipUnits(listOf(first,source.copy(id=2))),supplies=1000)
            val next=TacticalOfflineAi.advance(offline,galaxy,players,stock.copy(outfits=stock.outfits+(1 to own)),1,mutableListOf())
            val units=next.outfits.getValue(1).activeShips!!.values
            assertEquals(listOf(pilot.unitId,ally.unitId),units.map {it.support?.target})
            val results=mutableListOf<TacticalSupportResult>()
            TacticalSupportOperations.advance(offline.copy(gameMillis=(now+5)*1000),galaxy,players,next,results)
            assertEquals(2,results.count {!it.cancelled})
            assertEquals(listOf(10000,10000),players.values.map {it.supplies})
        }
    }

    @Test fun offlineSupportApproachesBeforeReservingAndCompletingLegalSupport() {
        val scale=24L
        val delay=5*scale
        for(action in TacticalSupportAction.entries) {
            val pilots=players()
            val pilot=pilots.getValue("pilot")
            val ally=pilot.copy(characterId=10001,supplies=9500,hull=pilot.hull!!.copy(hp=40),motion=motion(0f,1.7f))
            pilots["ally"]=ally
            val stock=stocks(1)
            val name=if(action==TacticalSupportAction.REPAIR)"工作艦" else "輸送艦"
            val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith(name)}.kind
            val source=ActiveShipUnit(1,UnitStack(kind),tactical=NpcTacticalState(motion(0f,.5f),now,now),supplies=500)
            val own=stock.outfits.getValue(1).copy(units=UnitInventory(mapOf(source.stack to 1)),
                activeShips=ActiveShipUnits(listOf(source)),supplies=500)
            val before=stock.copy(outfits=stock.outfits+(1 to own))
            val events=mutableListOf<TacticalCommandResult>()
            val approaching=TacticalOfflineAi.advance(offline,galaxy,pilots,before,scale,events)
            val moving=approaching.outfits.getValue(1).activeShips!!.values.single()
            assertNull(moving.support)
            assertNull(moving.attack)
            assertEquals(500,moving.supplies)
            assertEquals(TacticalPoint(0f,1.2f),events.single().motions.getValue(1).waypoints.last())
            assertEquals(ally,pilots.getValue("ally"))

            val ready=offline.copy(gameMillis=(now+delay)*1000)
            val ordered=TacticalOfflineAi.advance(ready,galaxy,pilots,approaching,scale,events)
            assertEquals(TacticalSupportOrder(action,10000,ally.unitId,2488,now+delay,now+2*delay,issuedByOfflineAi=true),
                ordered.outfits.getValue(1).activeShips!!.values.single().support)
            val results=mutableListOf<TacticalSupportResult>()
            val finished=TacticalSupportOperations.advance(offline.copy(gameMillis=(now+2*delay)*1000),galaxy,pilots,ordered,results)
            assertFalse(results.single().cancelled)
            assertNull(finished.outfits.getValue(1).activeShips!!.values.single().support)
            if(action==TacticalSupportAction.SUPPLY) {
                assertEquals(10000,pilots.getValue("ally").supplies)
                assertEquals(0,finished.outfits.getValue(1).supplies)
            } else {
                assertEquals(100,pilots.getValue("ally").hull!!.hp)
                val cost=Flagships.find(0)!!.number("修理消費物資(１隻):修理消費物資(１隻)")
                assertEquals(500-cost,finished.outfits.getValue(1).supplies)
            }
        }
    }

    @Test fun offlineSupportFallsBackToAnAlliedShipInRange() {
        for(action in TacticalSupportAction.entries) {
            val players=players()
            val stock=stocks(1)
            val original=stock.outfits.getValue(1)
            val name=if(action==TacticalSupportAction.REPAIR)"工作艦" else "輸送艦"
            val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith(name)}.kind
            val source=ActiveShipUnit(1,UnitStack(kind),tactical=NpcTacticalState(motion(0f,.5f),now,now),supplies=500)
            val ally=original.activeShips!!.values.single().copy(id=3,damaged=1,supplies=0)
            val far=ally.copy(id=2,tactical=NpcTacticalState(motion(0f,3f),now,now))
            val own=original.copy(units=UnitInventory(mapOf(source.stack to 1,ally.stack to 2)),
                activeShips=ActiveShipUnits(listOf(source,far,ally)),supplies=500)
            val enemy=stock.outfits.getValue(2)
            val hostile=enemy.activeShips!!.values.single().copy(damaged=1,supplies=0,tactical=ally.tactical)
            val state=stock.copy(outfits=mapOf(1 to own,2 to enemy.copy(activeShips=ActiveShipUnits(listOf(hostile)),supplies=0)))
            val ordered=TacticalOfflineAi.advance(offline,galaxy,players,state,1,mutableListOf())
            assertEquals(TacticalSupportOrder(action,10000,3,2488,now,now+5,issuedByOfflineAi=true),
                ordered.outfits.getValue(1).activeShips!!.values.single {it.id==1}.support)
            val results=mutableListOf<TacticalSupportResult>()
            val finished=TacticalSupportOperations.advance(offline.copy(gameMillis=(now+5)*1000),galaxy,players,ordered,results)
            assertFalse(results.single().cancelled)
            val supported=finished.outfits.getValue(1).activeShips!!.values.single {it.id==3}
            if(action==TacticalSupportAction.REPAIR)assertEquals(0,supported.damaged)
            else assertTrue(supported.supplies!!>0)
        }
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

    @Test fun reconnectAndAiOffReturnAutomaticTargetSelectionToTheNormalOrder() {
        val pilot=players().getValue("pilot")
        val stock=stocks(3)
        val candidates=listOf(99,12,99,42)
        assertEquals(listOf(99),TacticalOfflineAi.automaticTargets(offline,"pilot",pilot,stock,candidates))
        assertEquals(listOf(12,42,99),TacticalOfflineAi.automaticTargets(baseSession,"pilot",pilot,stock,candidates))
        val disabled=stock.copy(tacticalCommands=stock.tacticalCommands.copy(aiSwitches=emptyMap()))
        assertEquals(listOf(12,42,99),TacticalOfflineAi.automaticTargets(offline,"pilot",pilot,disabled,candidates))
    }

    @Test fun reconnectAndAiOffClearOnlyFleetOrdersIssuedByTheOfflineAi() {
        val pilots=players()
        val delegated=TacticalOfflineAi.advance(offline,galaxy,pilots,stocks(),1,mutableListOf())
        val own=delegated.outfits.getValue(1)
        val ship=own.activeShips!!.values.single()
        val delegatedOrder=assertNotNull(ship.attack)
        val playerOrder=delegatedOrder.copy(order=TacticalAttackOrder(2,now,now+5,now+5))
        for(current in listOf(baseSession,offline)) {
            val stock=if(current==offline)delegated.copy(tacticalCommands=
                delegated.tacticalCommands.copy(aiSwitches=emptyMap())) else delegated
            for(order in listOf(delegatedOrder,playerOrder)) {
                val inventory=stock.copy(outfits=stock.outfits+(1 to own.copy(
                    activeShips=ActiveShipUnits(listOf(ship.copy(attack=order))))))
                val impacts=mutableListOf<TacticalImpact>()
                val settled=TacticalFleetAttack.advance(current,galaxy,pilots,inventory,1,impacts)
                assertTrue(impacts.isEmpty())
                assertEquals(ship.copy(attack=playerOrder.takeIf {order==playerOrder}),
                    settled.outfits.getValue(1).activeShips!!.values.single())
            }
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
            attack=TacticalUnitAttack(10000,TacticalAttackOrder(2,now,now+5,now+5)))
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
        TacticalFleetAttack.advance(offline.copy(gameMillis=(now+5)*1000),galaxy,players(),stock,1,impacts)
        assertTrue(impacts.isEmpty())
        TacticalFleetAttack.advance(baseSession.copy(gameMillis=(now+5)*1000),galaxy,players(),stock,1,impacts)
        assertTrue(impacts.any {it.hit.target==99},"the enemy is a valid target while the commander is online")
    }

    @Test fun defenseDoesNotDispatchTowardACapturedBaseBeforeMissionSettlement() {
        val plain=stocks(1)
        for(power in listOf(2,3)) {
            val stock=plain.copy(bases=mapOf(1000 to PlanetStock(1000,power,0,100,0,0)))
            val players=players()
            val pilot=players.getValue("pilot").copy(motion=motion(0f,12f))
            players["pilot"]=pilot
            val events=mutableListOf<TacticalCommandResult>()
            val result=TacticalOfflineAi.advance(offline,galaxy,players,stock,1,events)
            if(power==2) {
                assertTrue(players.getValue("pilot").motion!!.velocity>0)
                assertNotNull(events.single().motions[pilot.unitId])
            } else {
                assertEquals(pilot,players.getValue("pilot"))
                assertEquals(stock.outfits,result.outfits)
                assertTrue(events.isEmpty())
            }
        }
    }

    @Test fun occupationAnchorsTroopCarriersAtTheEnemyBaseAndThenLandsTheirTroops() {
        // The fortress belongs to the alliance; the pilot's mission 2 (占領) targets it with a light infantry unit aboard ship 1.
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),carrier=1),ActiveGroundUnit(6,UnitStack(2),carrier=1)))
        val plain=stocks(2)
        val outfit=plain.outfits.getValue(1)
        val start=plain.copy(bases=mapOf(1000 to PlanetStock(1000,3,0,100,0,0)),
            outfits=plain.outfits+(1 to outfit.copy(units=UnitInventory(outfit.units.ships,troops.quantities()),activeGround=troops)),
            tacticalCommands=plain.tacticalCommands.copy(missions=mapOf(10000 to TacticalMissionState(10000,2,1,1000,now,grid=2488))))
        val stationedShip=outfit.activeShips!!.values.single().copy(posture=TacticalPosture(2488,TacticalShipMode.STATIONED,1000))
        val stationedOutfit=start.outfits.getValue(1).copy(activeShips=ActiveShipUnits(listOf(stationedShip)))
        val stationed=start.copy(outfits=start.outfits+(1 to stationedOutfit))
        val stillStationed=TacticalOfflineAi.advance(offline,galaxy,players(),stationed,1,mutableListOf())
        assertEquals(stationedOutfit,stillStationed.outfits.getValue(1))
        val anchored=TacticalOfflineAi.advance(offline,galaxy,players(),start,1,mutableListOf())
        val ship=anchored.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(TacticalShipMode.ANCHORED,ship.posture!!.order!!.mode);assertEquals(1000,ship.posture!!.order!!.base)
        assertEquals(now+10,ship.posture!!.order!!.readyAt,"the player's 10-second stance wait")
        val anchorOrder=assertNotNull(ship.posture.order)
        assertTrue(anchorOrder.issuedByOfflineAi)
        assertTrue(anchored.outfits.getValue(1).activeGround!!.values.all {it.order==null},"no landing before the carrier is anchored")
        val manualShip=ship.copy(posture=ship.posture.copy(order=anchorOrder.copy(issuedByOfflineAi=false)))
        val manualOutfit=anchored.outfits.getValue(1).copy(activeShips=ActiveShipUnits(listOf(manualShip)))
        val manualAnchor=anchored.copy(outfits=anchored.outfits+(1 to manualOutfit))
        val aiOff=anchored.copy(tacticalCommands=anchored.tacticalCommands.copy(aiSwitches=emptyMap()))
        val reassigned=anchored.copy(tacticalCommands=anchored.tacticalCommands.copy(missions=emptyMap()))
        val captured=anchored.copy(bases=mapOf(1000 to anchored.bases.getValue(1000).copy(owner=Allegiance.Country(Power.EMPIRE.country))))
        for((state,inventory,cancelled) in listOf(Triple(offline,anchored,false),Triple(baseSession,anchored,true),
            Triple(offline,aiOff,true),Triple(offline,reassigned,true),Triple(offline,captured,true),
            Triple(baseSession,manualAnchor,false))) {
            val at=if(cancelled)now+1 else anchorOrder.readyAt
            val events=mutableListOf<TacticalPostureResult>()
            val result=TacticalPostureOperations.advance(state.copy(gameMillis=at*1000),galaxy,players(),inventory,events)
            assertEquals(cancelled,events.single().cancelled)
            val posture=result.outfits.getValue(1).activeShips!!.values.single().posture!!
            assertNull(posture.order)
            assertEquals(if(cancelled)TacticalShipMode.SAILING else TacticalShipMode.ANCHORED,posture.mode)
        }
        // Once the stance order has executed, the next decision lands the admissible troops.
        val docked=anchored.copy(outfits=anchored.outfits+(1 to anchored.outfits.getValue(1).let {o->o.copy(activeShips=ActiveShipUnits(
            o.activeShips!!.values.map {it.copy(posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,1000))}))}),
            tacticalCommands=anchored.tacticalCommands.copy(aiNextAt=emptyMap()))
        val landed=TacticalOfflineAi.advance(offline.copy(gameMillis=(now+10)*1000),galaxy,players(),docked,1,mutableListOf())
        val orders=landed.outfits.getValue(1).activeGround!!.values.associate {it.id to it.order}
        assertEquals(TacticalGroundAction.DEPLOY,orders.getValue(5)!!.action);assertEquals(1000,orders.getValue(5)!!.base)
        assertNull(orders.getValue(6),"armored troops stay aboard at a fortress (W p.51)")
        val order=assertNotNull(orders.getValue(5))
        assertTrue(order.issuedByOfflineAi)
        assertEquals(setOf(order),TacticalGroundOrder.pending(landed,2488,players().values,galaxy,emptySet(),now+10))
        assertTrue(TacticalGroundOrder.pending(landed,2488,players().values,galaxy,emptySet(),now+10,onlineViewer=10000).isEmpty())
        val switchedOff=landed.copy(tacticalCommands=landed.tacticalCommands.copy(aiSwitches=emptyMap()))
        val manual=landed.copy(outfits=landed.outfits+(1 to landed.outfits.getValue(1).let {current->
            current.copy(activeGround=ActiveGroundUnits(current.activeGround!!.values.map {
                if(it.id==5)it.copy(order=order.copy(issuedByOfflineAi=false)) else it
            }))
        }))
        for((session,stocks,cancelled) in listOf(Triple(offline,landed,false),Triple(baseSession,landed,true),
            Triple(offline,switchedOff,true),Triple(baseSession,manual,false))) {
            val events=mutableListOf<TacticalGroundResult>()
            val result=TacticalGroundOperations.advance(session.copy(gameMillis=order.readyAt*1000),galaxy,players(),stocks,events)
            assertEquals(cancelled,events.single().cancelled)
            val troop=result.outfits.getValue(1).activeGround!!.values.single {it.id==5}
            assertNull(troop.order)
            assertEquals(if(cancelled)1 else 0,troop.carrier)
            assertEquals(if(cancelled)0 else 1000,troop.base)
        }
    }
    @Test fun occupationUsesTheCurrentFortressBerthForCommandAndAnchoringRange() {
        val berth=galaxy.bases.single().copy(mobile=true)
        val targetBase=berth.copy(id=1001,name="Target")
        val world=Galaxy(galaxy.systems,listOf(berth,targetBase),galaxy.starts)
        val at=now+400
        val current=TacticalPoint(0f,30f)
        val moving=TacticalMotion(2488,now,TacticalPose(TacticalPoint(0f,0f),0f),.1f,0f,listOf(current),waitSeconds=0)
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),carrier=1)))
        val plain=stocks(2)
        val outfit=plain.outfits.getValue(1)
        val carrier=outfit.activeShips!!.values.single().copy(tactical=null,
            posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,berth.id))
        val equipped=outfit.copy(activeShips=ActiveShipUnits(listOf(carrier)),activeGround=troops,
            units=UnitInventory(outfit.units.ships,troops.quantities()))
        for(target in listOf(berth,targetBase))for(inRange in listOf(false,true)) {
            val initial=plain.copy(
                bases=world.bases.associate {it.id to PlanetStock(it.id,3,0,100,0,0)},
                outfits=plain.outfits+(1 to equipped),
                tacticalCommands=plain.tacticalCommands.copy(
                    missions=mapOf(10000 to TacticalMissionState(10000,2,1,target.id,now,grid=2488)),
                    fortresses=mapOf(berth.id to TacticalFortressMotion(2488,moving),
                        targetBase.id to TacticalFortressMotion(2488,motion(0f,31f)))))
            val pilots=players()
            pilots["pilot"]=pilots.getValue("pilot").copy(motion=if(inRange)motion(0f,30f) else motion(),
                commandRange=TacticalCommandRange(at,TacticalCommandRange.MAXIMUM))
            val next=TacticalOfflineAi.advance(offline.copy(gameMillis=at*1000),world,pilots,initial,1,mutableListOf())
            val updated=next.outfits.getValue(1)
            if(!inRange)assertEquals(equipped,updated)
            else if(target.id==berth.id)assertEquals(berth.id,updated.activeGround!!.values.single().order?.base)
            else assertEquals(target.id,updated.activeShips!!.values.single().posture?.order?.base)
        }
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
    @Test fun offlineRetreatIgnoresAnEliminatedFleetAtThePreferredExit() {
        val plain=stocks(5)
        val enemy=plain.outfits.getValue(2)
        val stock=plain.copy(outfits=plain.outfits+(2 to enemy.copy(position=OutfitPosition(2487))))
        fun destination(session:SessionState):Int {
            val players=players()
            players["pilot"]=players.getValue("pilot").copy(motion=motion(-260f))
            TacticalOfflineAi.advance(session,galaxy,players,stock,1,mutableListOf())
            return assertNotNull(players.getValue("pilot").retreat).destination
        }
        assertNotEquals(2487,destination(offline))
        val eliminated=offline.copy(eliminated=mapOf(Power.ALLIANCE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        assertEquals(2487,destination(eliminated))
    }

    @Test fun reconnectAndAiOffCancelPendingOfflineRetreats() {
        val plain=stocks(5)
        val fleet=plain.outfits.getValue(1)
        val ship=fleet.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(0f,260f),now,now))
        val ready=plain.copy(outfits=plain.outfits+(1 to fleet.copy(activeShips=ActiveShipUnits(listOf(ship)))))
        val pilots=players()
        pilots["pilot"]=pilots.getValue("pilot").copy(motion=motion(0f,260f))
        val delegated=TacticalOfflineAi.advance(offline,galaxy,pilots,ready,1,mutableListOf())
        val retreat=assertNotNull(pilots.getValue("pilot").retreat)
        assertNotNull(delegated.outfits.getValue(1).activeShips!!.values.single().tactical?.retreat)

        val disabled=delegated.copy(tacticalCommands=delegated.tacticalCommands.copy(aiSwitches=emptyMap()))
        for((session,stock) in listOf(baseSession to delegated,offline to disabled)) {
            val tick=session.copy(gameMillis=retreat.startsAt*1000)
            val navigation=Navigation(galaxy,object:NavigationStore {
                override fun load()=pilots.toMap()
                override fun save(players:Map<String,Navigator>) {}
            },NavigationPolicy())
            navigation.settleRetreats(tick,stock)
            assertNull(navigation.snapshot(1).getValue("pilot").retreat)
            val after=TacticalFleetRetreat.advance(tick,galaxy,pilots.toMutableMap(),stock,mutableListOf())
            assertNull(after.outfits.getValue(1).activeShips!!.values.single().tactical?.retreat)
        }
    }

    @Test fun offlineRebelRetreatCommandsOnlyTheMatchingCamp() {
        val plain=stocks(5)
        val fleet=plain.outfits.getValue(1)
        val ship=fleet.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(260f),now,now))
        val ready=fleet.copy(activeShips=ActiveShipUnits(listOf(ship)))
        val plot=Plot(10000,Power.EMPIRE.country,0,revolt=5)
        for(loyalty in listOf(emptyMap(),mapOf(1 to 100))) {
            val coup=CoupState(mapOf(10000 to plot),loyalty=loyalty)
            val stock=plain.copy(outfits=plain.outfits+(1 to ready),social=plain.social.copy(coup=coup))
            val pilots=players()
            pilots["pilot"]=pilots.getValue("pilot").copy(motion=motion(260f))
            val after=TacticalOfflineAi.advance(offline,galaxy,pilots,stock,1,mutableListOf())
            val flagship=assertNotNull(pilots.getValue("pilot").retreat)
            val escort=after.outfits.getValue(1).activeShips!!.values.single()
            if(loyalty.isEmpty())assertEquals(ship,escort)
            else assertEquals(flagship.destination,escort.tactical?.retreat?.destination)
        }
    }

    @Test fun rebelOfflineOrdersCannotCommandRegularOutfitsOrLandOnTheCapturedFriendlyBase() {
        val plain=stocks(2)
        val fleet=plain.outfits.getValue(1)
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(5,UnitStack(0),carrier=1)))
        val ready=fleet.copy(activeGround=troops,units=UnitInventory(fleet.units.ships,troops.quantities()),
            activeShips=ActiveShipUnits(fleet.activeShips!!.values.map {it.copy(posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,1000))}))
        val plot=Plot(10000,Power.EMPIRE.country,0,revolt=5,bases=setOf(1000))
        val coup=CoupState(mapOf(10000 to plot))
        val ordered=plain.copy(outfits=plain.outfits+(1 to ready),
            social=plain.social.copy(coup=coup),
            tacticalCommands=plain.tacticalCommands.copy(missions=mapOf(10000 to TacticalMissionState(10000,2,1,1000,now,grid=2488))))
        val opposed=TacticalOfflineAi.advance(offline,galaxy,players(),ordered,1,mutableListOf())
        assertEquals(ready,opposed.outfits.getValue(1))
        val loyal=ordered.copy(social=ordered.social.copy(coup=coup.copy(loyalty=mapOf(1 to 100))))
        val landed=TacticalOfflineAi.advance(offline,galaxy,players(),loyal,1,mutableListOf())
        assertNotNull(landed.outfits.getValue(1).activeGround!!.values.single().order)
        val captured=loyal.copy(bases=loyal.bases.mapValues {(_,base)->base.copy(camp=1)})
        val after=TacticalOfflineAi.advance(offline,galaxy,players(),captured,1,mutableListOf())
        assertEquals(ready,after.outfits.getValue(1))
    }

    @Test fun onlineOrDisabledCharactersAreNotDriven() {
        for((session,stock) in listOf(baseSession to stocks(),offline to stocks().copy(tacticalCommands=TacticalCommandState()))) {
            val p=players();val before=p.toMap()
            assertEquals(stock,TacticalOfflineAi.advance(session,galaxy,p,stock,1,mutableListOf()))
            assertEquals(before,p)
        }
    }

    @Test fun pendingFlagshipBeamPreservesTheFlagshipAndLetsTheEscortAct() {
        val pilots=players()
        val shot=TacticalPendingFire(1,2488,0,99,now,now+5)
        val flagship=pilots.getValue("pilot").copy(fire=TacticalFire(1,pending=shot))
        pilots["pilot"]=flagship
        val events=mutableListOf<TacticalCommandResult>()
        val next=TacticalOfflineAi.advance(offline,galaxy,pilots,stocks(),1,events)
        val updatedFlagship=pilots.getValue("pilot")
        assertEquals(flagship,updatedFlagship.copy(commandRange=flagship.commandRange))
        val escort=next.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(flagship.characterId,escort.motionCommander)
        assertNotNull(escort.attack)
        assertEquals(setOf(escort.id),events.single().motions.keys)
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
