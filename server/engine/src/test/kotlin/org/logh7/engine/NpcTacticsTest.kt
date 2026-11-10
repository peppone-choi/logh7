package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcTacticsTest {
    @Test fun autonomousFireUsesTheCurrentMovingFortressPositionForAnchoredTargets() {
        val home=galaxy.bases.single()
        val fortress=home.copy(id=1001,grid=2488,kind=1,mobile=true)
        val battle=Galaxy(galaxy.systems,galaxy.bases+fortress,galaxy.starts)
        val near=TacticalPoint(-180f,-95f)
        val far=TacticalPoint(180f,-95f)
        val source=TacticalPoint(-178f,-95f)
        val heading=(-Math.PI/2).toFloat()
        val at=now+4000
        val posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,fortress.id)
        fun stopped(point:TacticalPoint,seconds:Long=now,direction:Float=0f)=
            TacticalMotion(2488,seconds,TacticalPose(point,direction),0f,direction,listOf(point))
        for(targetType in listOf("flagship","ship","ship without motion"))for(approaches in listOf(false,true)) {
            val state=state()
            val origin=if(approaches)far else near
            val destination=if(approaches)near else far
            val moving=TacticalMotion(2488,now,TacticalPose(origin,0f),TacticalFortressMotion.VELOCITY,0f,
                listOf(destination),waitSeconds=0)
            val original=state.stocks.outfits.getValue(1)
            val gunship=original.activeShips!!.values.single().copy(tactical=NpcTacticalState(stopped(source,at,heading),at+1,at))
            val outfits=mutableMapOf(1 to original.copy(activeShips=ActiveShipUnits(listOf(gunship))))
            val warehouses=state.stocks.warehouses.toMutableMap()
            val players=mutableMapOf<String,Navigator>()
            val targetId=if(targetType=="flagship") {
                val flagship=state.players.getValue("pilot").copy(posture=posture,motion=stopped(origin))
                players["pilot"]=flagship
                flagship.unitId
            } else {
                val target=ActiveShipUnit(2,UnitStack(119),posture=posture,
                    tactical=if(targetType=="ship")NpcTacticalState(stopped(origin),at,at) else null)
                val ships=ActiveShipUnits(listOf(target))
                outfits[2]=OutfitStock(2,3,OutfitKind.FLEET,fortress.id,11,
                    units=UnitInventory(ships.quantities()),activeShips=ships,position=OutfitPosition(2488))
                warehouses[11]=OutfitWarehouse(11,3,OutfitKind.FLEET,fortress.id,2)
                target.id
            }
            val stocks=state.stocks.copy(outfits=outfits,warehouses=warehouses,nextShipId=3,
                bases=state.stocks.bases+(fortress.id to PlanetStock(fortress.id,3,0,100,0,0)),
                tacticalCommands=TacticalCommandState(fortresses=mapOf(fortress.id to TacticalFortressMotion(2488,moving))))
            val impacts=mutableListOf<TacticalImpact>()
            NpcTactics.advance(session.copy(gameMillis=at*1000),battle,players,stocks,NpcTacticalPolicy(),24,impacts,mutableListOf())
            assertEquals(if(approaches)setOf(targetId) else emptySet(),impacts.map {it.hit.target}.toSet(),targetType)
        }
    }

    @Test fun weakenedFleetOnlyBeginsRetreatWhenAnEnemyIsInSight() {
        for(distance in listOf(501f,500f)) {
            val state=state(boats=75)
            val outfit=state.stocks.outfits.getValue(1)
            val ship=outfit.activeShips!!.values.single()
            val start=TacticalPoint(0f,-250f)
            fun pose(point:TacticalPoint)=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point))
            val ready=ship.copy(tactical=NpcTacticalState(pose(start),now,now))
            val stocks=state.stocks.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(listOf(ready)))))
            val pilots=state.players.mapValues {(_,pilot)->pilot.copy(motion=pose(start.copy(y=start.y+distance)))}.toMutableMap()
            val movements=mutableListOf<TacticalMovement>()
            val next=NpcTactics.advance(session,galaxy,pilots,stocks,NpcTacticalPolicy(),24,mutableListOf(),movements)
            val updated=next.outfits.getValue(1).activeShips!!.values.single()
            assertEquals(if(distance==500f)2487 else null,updated.tactical?.retreat?.destination)
            if(distance>500f) {
                assertEquals(stocks,next)
                assertTrue(movements.isEmpty())
            }
        }
    }

    @Test fun precisionPrioritizesTheBestLegalShotWithoutAnAdvisor() {
        for(behavior in listOf(NpcCombatBehavior.STANDARD,NpcCombatBehavior.PRECISION)) {
            val state=state()
            val pilot=state.players.getValue("pilot")
            fun pose(x:Float,y:Float,heading:Float=0f):TacticalMotion {
                val point=TacticalPoint(x,y)
                return TacticalMotion(2488,now,TacticalPose(point,heading),0f,heading,listOf(point))
            }
            val players=mapOf("far" to pilot.copy(motion=pose(-182.5f,-95f)),
                "near" to pilot.copy(characterId=10001,motion=pose(-180f,-94.6f))).toMutableMap()
            val outfit=state.stocks.outfits.getValue(1)
            val shooter=outfit.activeShips!!.values.single().copy(tactical=NpcTacticalState(pose(-178f,-95f,(-Math.PI/2).toFloat()),now+1,now))
            val stocks=state.stocks.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(listOf(shooter)))))
            val hits=mutableListOf<TacticalImpact>()
            NpcTactics.advance(session,galaxy,players,stocks,NpcTacticalPolicy(behavior={ generation,id ->
                assertEquals(1L,generation);assertEquals(1,id);behavior
            }),24,hits,mutableListOf())
            assertEquals(if(behavior==NpcCombatBehavior.PRECISION)1010001 else 1010000,hits.first().hit.target)
        }
    }

    @Test fun autonomousFireSkipsCloserFlagshipsAndOutfitsOfEliminatedCountries() {
        val eliminated = CountryId(42)
        val combat = session.copy(eliminated = mapOf(eliminated to CountryDefeat(EndReason.SYSTEMS_REDUCED, now)))
        val state = state()
        val original = state.stocks.outfits.getValue(1)
        val unit = original.activeShips!!.values.single()
        val pilot = state.players.getValue("pilot")
        fun pose(x: Float, y: Float, heading: Float = 0f): TacticalMotion {
            val point = TacticalPoint(x, y)
            return TacticalMotion(2488, now, TacticalPose(point, heading), 0f, heading, listOf(point))
        }
        val shooter = unit.copy(tactical = NpcTacticalState(pose(-178f, -95f, (-Math.PI / 2).toFloat()), now + 1, now))
        val firing = original.copy(activeShips = ActiveShipUnits(listOf(shooter)))
        val near = pose(-180f, -94.6f)
        for (flagship in listOf(true, false)) {
            val players = mutableMapOf("pilot" to pilot.copy(motion = pose(-182.5f, -95f)))
            val outfits = mutableMapOf(1 to firing)
            if (flagship) {
                players["eliminated"] = pilot.copy(characterId = 10001, country = eliminated, motion = near)
            } else {
                val ship = unit.copy(id = 2, tactical = NpcTacticalState(near, now + 1, now))
                outfits[2] = original.copy(id = 2, country = eliminated, warehouseId = 11,
                    activeShips = ActiveShipUnits(listOf(ship)))
            }
            val warehouses = state.stocks.warehouses.let {
                if (flagship) it else it + (11 to it.getValue(10).copy(id = 11, country = eliminated, outfitId = 2))
            }
            val stocks = state.stocks.copy(outfits = outfits, warehouses = warehouses, nextShipId = 3)
            val impacts = mutableListOf<TacticalImpact>()
            NpcTactics.advance(combat, galaxy, players, stocks,
                NpcTacticalPolicy(behavior = { _, _ -> NpcCombatBehavior.PRECISION }), 24, impacts, mutableListOf())
            assertEquals(setOf(pilot.unitId), impacts.map { it.hit.target }.toSet())
        }
    }

    @Test fun aDeceasedFlagshipInAnOlderSaveCannotDrawAutonomousFireOrRetreat() {
        fun pose(x:Float,y:Float,heading:Float=0f):TacticalMotion {
            val point=TacticalPoint(x,y)
            return TacticalMotion(2488,now,TacticalPose(point,heading),0f,heading,listOf(point))
        }
        for(withLivingEnemy in listOf(true,false)) {
            val state=state(boats=if(withLivingEnemy)300 else 75)
            val fleet=state.stocks.outfits.getValue(1)
            val ship=fleet.activeShips!!.values.single()
            val ready=ship.copy(tactical=NpcTacticalState(pose(-178f,-95f,(-Math.PI/2).toFloat()),now,now))
            val pilot=state.players.getValue("pilot")
            val deceased=pilot.copy(motion=pose(-180f,-94.6f))
            val living=pilot.copy(characterId=10001,motion=pose(-182.5f,-95f))
            val players=mutableMapOf("deceased" to deceased)
            if(withLivingEnemy)players["living"]=living
            val stock=state.stocks.copy(outfits=mapOf(1 to fleet.copy(activeShips=ActiveShipUnits(listOf(ready)))),
                strategic=StrategicState(people=mapOf(deceased.characterId to StrategicPerson(deceasedAt=1))))
            val hits=mutableListOf<TacticalImpact>()
            val after=NpcTactics.advance(session,galaxy,players,stock,NpcTacticalPolicy(),24,hits,mutableListOf())
            val tactical=assertNotNull(after.outfits.getValue(1).activeShips!!.values.single().tactical)
            assertEquals(if(withLivingEnemy)living.unitId else 0,tactical.target)
            assertEquals(if(withLivingEnemy)setOf(living.unitId) else emptySet(),hits.map {it.hit.target}.toSet())
            assertNull(tactical.retreat)
            assertEquals(deceased,players.getValue("deceased"))
        }
    }

    @Test fun aStarHidesTargetsFromOutsideObserversButAllowsFireInsideTheSensorCircle() {
        val world=Galaxy(galaxy.systems.map { if(it.grid==2488)it.copy(star=0) else it },galaxy.bases,galaxy.starts)
        val heading=(-Math.PI/2).toFloat()
        fun motion(x:Float):TacticalMotion {
            val point=TacticalPoint(x,0f)
            return TacticalMotion(2488,now,TacticalPose(point,heading),0f,heading,listOf(point))
        }
        val cases=listOf(Triple(20f,19f,300),Triple(21f,19f,50),Triple(19f,17f,300)).map {it to false} +
            (Triple(21f,19f,300) to true)
        for((positions,shared) in cases) {
            val (observer,target,boats)=positions
            val state=state(boats)
            val players=state.players.mapValues {(_,player)->player.copy(motion=motion(target))}.toMutableMap()
            if(shared) {
                val point=TacticalPoint(18f,2f)
                players["scout"]=Navigator(1,10001,Power.EMPIRE,2488,1000,1000,900,cpPaused=true,
                    hull=FlagshipHull(0,now),motion=TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point)))
            }
            val outfit=state.stocks.outfits.getValue(1)
            val unit=outfit.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(observer),now,now))
            val stocks=state.stocks.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(listOf(unit)))))
            val hits=mutableListOf<TacticalImpact>()
            val after=NpcTactics.advance(session,world,players,stocks,NpcTacticalPolicy(),24,hits,mutableListOf())
            val tactical=after.outfits.getValue(1).activeShips!!.values.single().tactical!!
            if(observer>=20f && !shared) {
                assertEquals(0,tactical.target)
                assertNull(tactical.retreat)
                assertTrue(hits.isEmpty())
            } else {
                assertEquals(1010000,tactical.target)
                assertTrue(hits.isNotEmpty())
            }
        }
    }

    @Test fun autonomousNpcApproachesAnEnemyAroundTheStar() {
        val world=Galaxy(galaxy.systems.map {if(it.grid==2488)it.copy(star=0) else it},galaxy.bases,galaxy.starts)
        fun motion(x:Float):TacticalMotion {
            val point=TacticalPoint(x,0f)
            return TacticalMotion(2488,now,TacticalPose(point,0f),0f,0f,listOf(point))
        }
        val state=state()
        val players=state.players.mapValues {(_,player)->player.copy(motion=motion(21f))}.toMutableMap()
        val outfit=state.stocks.outfits.getValue(1)
        val ship=outfit.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion(-21f),now,now))
        val stocks=state.stocks.copy(outfits=mapOf(1 to outfit.copy(activeShips=ActiveShipUnits(listOf(ship)))))
        val movements=mutableListOf<TacticalMovement>()
        val hits=mutableListOf<TacticalImpact>()
        val after=NpcTactics.advance(session,world,players,stocks,NpcTacticalPolicy(),24,hits,movements)
        val current=after.outfits.getValue(1).activeShips!!.values.single().tactical!!
        val route=current.motion
        assertEquals(1010000,current.target)
        assertEquals(route,movements.single().motion)
        assertTrue(route.velocity>0f)
        assertTrue(route.waypoints.size>1)
        assertTrue(TacticalTerrain.pathBlocked(world,2488,route.origin.position,listOf(route.waypoints.last())))
        assertFalse(TacticalTerrain.pathBlocked(world,2488,route.origin.position,route.waypoints))
        assertTrue(hits.isEmpty())
    }

    @Test fun anEliminatedFleetStopsItsAutonomousRetreatAndDoesNotResume() {
        val state=state(150)
        val players=state.players.toMutableMap()
        val hits=mutableListOf<TacticalImpact>()
        val moves=mutableListOf<TacticalMovement>()
        val policy=NpcTacticalPolicy(behavior={_,_->NpcCombatBehavior.CAUTIOUS})
        val active=NpcTactics.advance(session,galaxy,players,state.stocks,policy,24,hits,moves)
        val outfit=active.outfits.getValue(1)
        assertNotNull(outfit.activeShips!!.values.single().tactical!!.retreat)
        val defeated=session.copy(eliminated=mapOf(checkNotNull(outfit.country) to
            CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        moves.clear()

        val stopped=NpcTactics.advance(defeated,galaxy,players,active,policy,24,hits,moves)
        val current=stopped.outfits.getValue(1).activeShips!!.values.single().tactical!!
        assertNull(current.retreat)
        assertEquals(0,current.target)
        assertEquals(0f,current.motion.velocity)
        assertEquals(current.motion,moves.single().motion)
        assertEquals(outfit.supplies,stopped.outfits.getValue(1).supplies)
        moves.clear()
        assertEquals(stopped,NpcTactics.advance(defeated.copy(gameMillis=(now+24)*1000),
            galaxy,players,stopped,policy,24,hits,moves))
        assertTrue(moves.isEmpty())
        assertTrue(hits.isEmpty())
    }

    @Test fun cautiousFleetsStartLegalWithdrawalAtHalfStrength() {
        for((boats,behavior,withdraws) in listOf(
            Triple(150,NpcCombatBehavior.STANDARD,false),
            Triple(151,NpcCombatBehavior.CAUTIOUS,false),
            Triple(150,NpcCombatBehavior.CAUTIOUS,true),
        )) {
            val state=state(boats)
            val players=state.players.toMutableMap()
            val hits=mutableListOf<TacticalImpact>()
            val moves=mutableListOf<TacticalMovement>()
            val after=NpcTactics.advance(session,galaxy,players,state.stocks,NpcTacticalPolicy(behavior={_,_->behavior}),24,hits,moves)
            val retreat=after.outfits.getValue(1).activeShips!!.values.single().tactical!!.retreat
            assertEquals(if(withdraws)2487 else null,retreat?.destination)
            assertTrue(moves.isNotEmpty())
            assertTrue(hits.isEmpty())
        }
    }

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
    @Test fun sameInitialBattleReplaysAfterReopeningWithoutRepeatingShots() {
        fun replay(reopen:Boolean):Pair<State,List<TacticalMovement>> {
            val state=state()
            var nav=Navigation(galaxy,state.nav,NavigationPolicy())
            var stock=production(state)
            val moves=mutableListOf<TacticalMovement>()
            assertTrue(settle(now,nav,stock,state,moves).isEmpty())
            val hits=settle(now+480,nav,stock,state,moves)
            assertEquals(TacticalWeapon.entries,hits.map {it.weapon})
            if(reopen) {
                nav=Navigation(galaxy,state.nav,NavigationPolicy())
                stock=PlanetProduction(galaxy,state.inventory,rules.startSeconds,PlanetProductionPolicy())
            }
            assertTrue(settle(now+480,nav,stock,state,moves).isEmpty())
            assertEquals(hits.map {it.weapon},settle(now+960,nav,stock,state,moves).map {it.weapon})
            return state to moves
        }

        val (continuous,continuousMoves)=replay(false)
        val (reopened,reopenedMoves)=replay(true)
        assertEquals(continuous.players,reopened.players)
        assertEquals(continuous.stocks,reopened.stocks)
        assertEquals(continuousMoves,reopenedMoves)
        assertEquals(6400,reopened.stocks.outfits.getValue(1).supplies)
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
    @Test fun anAnchoredPeerPreventsFleetRetreatButLeavesMobileShipsInCombat() {
        val anchorBase=galaxy.bases.single().copy(id=1001,grid=2488,name="Anchor")
        val world=Galaxy(galaxy.systems,galaxy.bases+anchorBase,galaxy.starts)
        val point=TacticalPoint(-178f,-95f)
        val heading=(-Math.PI/2).toFloat()
        val motion=TacticalMotion(2488,now,TacticalPose(point,heading),0f,heading,listOf(point))
        for(retreat in listOf(null,TacticalRetreat(2487,now,120,3600))) {
            val state=state(75)
            val outfit=state.stocks.outfits.getValue(1)
            val mobile=outfit.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion,now,now,retreat=retreat))
            val anchored=mobile.copy(id=2,tactical=null,posture=TacticalPosture(2488,TacticalShipMode.ANCHORED,1001))
            val fleet=outfit.copy(units=UnitInventory(mapOf(mobile.stack to 2)),activeShips=ActiveShipUnits(listOf(mobile,anchored)))
            val stocks=state.stocks.copy(outfits=mapOf(1 to fleet),nextShipId=3,
                bases=state.stocks.bases+(1001 to PlanetStock(1001,3,0,0,0,0)))
            val players=state.players.toMutableMap()
            val movements=mutableListOf<TacticalMovement>()
            val impacts=mutableListOf<TacticalImpact>()
            val after=NpcTactics.advance(session,world,players,stocks,NpcTacticalPolicy(),24,impacts,movements)
            val retained=after.outfits.getValue(1)
            val ships=retained.activeShips!!.values.associateBy {it.id}
            assertEquals(OutfitPosition(2488),retained.position)
            assertNull(ships.getValue(1).tactical?.retreat)
            assertEquals(anchored.posture,ships.getValue(2).posture)
            assertNull(ships.getValue(2).tactical)
            assertTrue(movements.none {it.unit==2 || it.destination!=null})
            assertTrue(impacts.isNotEmpty() && impacts.all {it.source==1})
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
    @Test fun npcRetreatIgnoresEliminatedMobileForcesWhenChoosingAndEnteringItsDestination() {
        val state = state(75)
        val eliminated = CountryId(42)
        val retreatSession = session.copy(eliminated = mapOf(eliminated to CountryDefeat(EndReason.SYSTEMS_REDUCED, now)))
        val players = state.players.toMutableMap()
        players["eliminated"] = Navigator(1, 10001, Power.ALLIANCE, 2487, 1000, 1000, 1000).copy(country = eliminated)
        val movements = mutableListOf<TacticalMovement>()

        val preparing = NpcTactics.advance(retreatSession, galaxy, players, state.stocks,
            NpcTacticalPolicy(), 24, mutableListOf(), movements)
        val retreat = assertNotNull(preparing.outfits.getValue(1).activeShips!!.values.single().tactical?.retreat)
        assertEquals(2487, retreat.destination)

        // A second live camp joins the destination after the retreat is accepted.
        players["other"] = Navigator(1, 10002, Power.ALLIANCE, 2487, 1000, 1000, 1000)
        val arrived = NpcTactics.advance(retreatSession.copy(gameMillis = retreat.completesAt * 1000),
            galaxy, players, preparing, NpcTacticalPolicy(), 24, mutableListOf(), movements)
        assertEquals(OutfitPosition(2487), arrived.outfits.getValue(1).position)
        assertEquals(listOf(2487), movements.mapNotNull { it.destination })
    }

    @Test fun npcReplansBlockedRetreatOrReturnsToCombatWhenThereIsNoExit() {
        for(hasAlternative in listOf(true,false)) {
            val world=if(hasAlternative)galaxy else Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,defaultCell=2)
            val state=state(75)
            val players=state.players.toMutableMap()
            val moves=mutableListOf<TacticalMovement>()
            val hits=mutableListOf<TacticalImpact>()
            fun advance(at:Long,stocks:PlanetStocks)=NpcTactics.advance(session.copy(gameMillis=at*1000),
                world,players,stocks,NpcTacticalPolicy(),24,hits,moves)
            val prepared=advance(now,state.stocks)
            val fleet=prepared.outfits.getValue(1)
            val ship=fleet.activeShips!!.values.single()
            val tactical=assertNotNull(ship.tactical)
            val retreat=assertNotNull(tactical.retreat)
            assertEquals(2487,retreat.destination)

            val player=players.getValue("pilot")
            players["other"]=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,1000)
            players["third"]=Navigator(1,10002,CountryId(42),2487,1000,1000,1000,shipStyle=ShipStyleId("alliance"))
            moves.clear()
            val ready=retreat.completesAt
            val waiting=advance(ready-1,prepared)
            assertEquals(retreat,waiting.outfits.getValue(1).activeShips!!.values.single().tactical?.retreat)
            assertTrue(moves.none {it.destination!=null})
            if(!hasAlternative) {
                // The enemy follows the withdrawing fleet so returning fire is observable.
                val pose=assertNotNull(waiting.outfits.getValue(1).activeShips!!.values.single().tactical).motion.pose(ready)
                val point=pose.position.copy(x=pose.position.x-2f)
                players["pilot"]=player.copy(motion=TacticalMotion(2488,ready,TacticalPose(point,0f),0f,0f,listOf(point)))
            }
            val retried=advance(ready,waiting)
            val current=retried.outfits.getValue(1)
            assertEquals(OutfitPosition(2488),current.position)
            assertTrue(moves.none {it.destination!=null})
            val order=current.activeShips!!.values.single().tactical?.retreat
            if(hasAlternative) {
                val replacement=assertNotNull(order)
                assertEquals(2387,replacement.destination)
                assertEquals(ready,replacement.acceptedAt)
                assertEquals(ready+(retreat.completesAt-retreat.acceptedAt),replacement.completesAt)
                assertEquals(fleet.supplies,current.supplies)
                val finished=advance(replacement.completesAt,retried)
                assertEquals(OutfitPosition(2387),finished.outfits.getValue(1).position)
                assertEquals(listOf(2387),moves.mapNotNull {it.destination})
            } else {
                assertNull(order)
                assertTrue(hits.any {it.hit.target==player.unitId})
                val next=advance(ready+24,retried)
                assertNull(next.outfits.getValue(1).activeShips!!.values.single().tactical?.retreat)
                assertTrue(moves.none {it.destination!=null})
            }
        }
    }

    @Test fun aSavedNpcRetreatCannotWarpOnItsCompletionTickAfterElimination() {
        val state=state(75)
        val moves=mutableListOf<TacticalMovement>()
        settle(now,Navigation(galaxy,state.nav,NavigationPolicy()),production(state),state,moves)
        val fleet=state.stocks.outfits.getValue(1)
        val retreat=assertNotNull(fleet.activeShips!!.values.single().tactical!!.retreat)
        val defeated=session.copy(gameMillis=retreat.completesAt*1000,
            eliminated=mapOf(Power.EMPIRE.country to CountryDefeat(EndReason.SYSTEMS_REDUCED,now)))
        moves.clear()

        val hits=TacticalCombat.settle(defeated,galaxy,Navigation(galaxy,state.nav,NavigationPolicy()),
            production(state),state.commit,NpcTacticalPolicy(),24,moves::add)
        val saved=state.stocks.outfits.getValue(1)
        assertEquals(fleet.position,saved.position)
        assertEquals(fleet.supplies,saved.supplies)
        assertNull(saved.activeShips!!.values.single().tactical!!.retreat)
        assertTrue(moves.none {it.destination!=null})
        assertTrue(hits.isEmpty())
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
    @Test fun uncommandedFleetApproachesAndFiresOnLivingPiratesInItsGrid() {
        val state=state()
        val outfit=state.stocks.outfits.getValue(1)
        val ship=outfit.activeShips!!.values.single()
        val origin=TacticalPoint(0f,-20f)
        val motion=TacticalMotion(2488,now,TacticalPose(origin,0f),0f,0f,listOf(origin))
        val pirates=PirateState(mapOf(1 to PirateFleet(1,2488,now,listOf(PirateUnit(900_001,300),PirateUnit(900_002,0))),
            2 to PirateFleet(2,2487,now,listOf(PirateUnit(900_003,300)))),3)
        val stocks=state.stocks.copy(social=state.stocks.social.copy(pirates=pirates),outfits=mapOf(1 to outfit.copy(
            activeShips=ActiveShipUnits(listOf(ship.copy(tactical=NpcTacticalState(motion,now,now)))))))
        val players=mutableMapOf<String,Navigator>()
        val hits=mutableListOf<TacticalImpact>()
        val moves=mutableListOf<TacticalMovement>()
        val underway=NpcTactics.advance(session,galaxy,players,stocks,NpcTacticalPolicy(),24,hits,moves)
        assertEquals(900_001,underway.outfits.getValue(1).activeShips!!.values.single().tactical?.target)
        assertEquals(TacticalPoint(0f,-12f),moves.single().motion?.waypoints?.last())
        assertTrue(hits.isEmpty())
        val fired=NpcTactics.advance(session.copy(gameMillis=(now+480)*1000),galaxy,players,underway,
            NpcTacticalPolicy(),24,hits,moves)
        assertEquals(setOf(900_001),hits.map {it.hit.target}.toSet())
        assertTrue(fired.social.pirates.fleets.getValue(1).units.first().boats<300)
        assertEquals(pirates.fleets.getValue(2),fired.social.pirates.fleets.getValue(2))
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
