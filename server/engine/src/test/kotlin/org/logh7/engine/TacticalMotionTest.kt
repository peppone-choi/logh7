package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalMotionTest {
    @Test fun nextProgressDeadlineMatchesPoseScheduleIncludingRotationsAndLegacyZeroLengthPaths() {
        val p=TacticalPose(TacticalPoint(0f,0f),0f)
        val m=TacticalMotion(2488,100,p,1f,0f,listOf(TacticalPoint(0f,10f),TacticalPoint(0f,0f)),turnRate=1f)
        assertEquals(115L,m.nextProgressAt)
        val reported=m.copy(reportedWaypoints=1)
        val end=reported.nextProgressAt!!
        assertEquals(132L,end)
        assertEquals(1,reported.completedWaypoints(end-1));assertEquals(2,reported.completedWaypoints(end))
        assertNull(m.copy(velocity=0f).nextProgressAt)
        assertEquals(106L,m.copy(waypoints=listOf(p.position),turnRate=0f).nextProgressAt)
        assertEquals(125L,m.copy(turnRate=0f,reportedWaypoints=1).nextProgressAt)
        assertNull(m.copy(acceptedAt=0xffffffffL-5).nextProgressAt)
        val turn=m.copy(velocity=0f,turnRate=0f,waypoints=listOf(p.position),
            rotation=TacticalRotation(TacticalManeuver.TURN,1010000,240))
        assertEquals(340L,turn.nextProgressAt)
    }
    @Test fun nativeScheduleTurnsBeforeTranslationAndFinalFacingAndResumesDuringTurn() {
        val origin=TacticalPose(TacticalPoint(0f,0f),0f)
        val legacy=TacticalMotion(2017,100,origin,1f,0f,listOf(TacticalPoint(0f,10f),origin.position))
        val motion=legacy.copy(turnRate=TacticalMotion.NATIVE_TURN_RATE)
        assertEquals(origin,motion.pose(105))
        assertEquals(TacticalPoint(0f,10f),motion.pose(115).position)
        assertEquals(1,motion.completedWaypoints(115))
        assertEquals(TacticalPoint(0f,10f),motion.pose(116).position)
        assertEquals(1f,kotlin.math.abs(motion.pose(116).direction),0.00001f)
        assertEquals(TacticalPoint(0f,9f),legacy.pose(116).position)
        assertEquals(listOf(origin.position),motion.remaining(116))
        // The return translation has finished, but the final in-place turn has not.
        assertEquals(origin.position,motion.pose(129).position)
        assertEquals(1,motion.completedWaypoints(129))
        assertEquals(origin,motion.pose(133));assertEquals(2,motion.completedWaypoints(133))
        val resumed=motion.copy(acceptedAt=111,origin=motion.pose(116),waypoints=motion.remaining(116))
        for(time in 117L..134L) {
            assertEquals(motion.pose(time).position.x,resumed.pose(time).position.x,0.00001f)
            assertEquals(motion.pose(time).position.y,resumed.pose(time).position.y,0.00001f)
            assertEquals(motion.pose(time).direction,resumed.pose(time).direction,0.00001f)
        }
        val parallel=motion.copy(parallel=true)
        assertEquals(TacticalPoint(0f,9f),parallel.pose(116).position)
        assertEquals(0f,parallel.pose(116).direction)
        val wrapped=motion.copy(origin=origin.copy(direction=3.1f),direction=-3.1f,
            waypoints=listOf(TacticalPoint(-0.4158f,-10f)))
        // Crossing +/-pi takes the short arc, leaving most of this G-second to move.
        assertTrue(kotlin.math.hypot(wrapped.pose(106).position.x,wrapped.pose(106).position.y) in 0.90f..0.94f)
        assertEquals(-3.1f,wrapped.pose(106).direction,0.0001f)
        assertFailsWith<IllegalArgumentException>{motion.copy(turnRate=Float.NaN)}
        assertFailsWith<IllegalArgumentException>{motion.copy(turnRate=-1f)}
    }
    @Test fun waypointArrivalIncludesExactBoundariesSkipsZeroLengthPointsAndKeepsStationaryPathsIdle() {
        val origin=TacticalPose(TacticalPoint(0f,0f),0.4f)
        val motion=TacticalMotion(2017,100,origin,0.5f,1f,
            listOf(TacticalPoint(0f,0f),TacticalPoint(3f,4f),TacticalPoint(3f,4f),TacticalPoint(6f,8f)))
        assertEquals(0,motion.completedWaypoints(105));assertEquals(1,motion.completedWaypoints(106))
        assertEquals(1,motion.completedWaypoints(114));assertEquals(3,motion.completedWaypoints(115))
        assertEquals(4,motion.completedWaypoints(125));assertEquals(0,motion.copy(velocity=0f).completedWaypoints(200))
        assertFailsWith<IllegalArgumentException>{motion.copy(reportedWaypoints=4)}
    }
    @Test fun personalParallelMovementAndEngineReductionKeepFacingAndHalfSpeed() {
        val rules=SessionRules.load();val session=SessionSimulation(rules).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()
        val now=session.gameMillis/1000
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val pose=TacticalPose(TacticalPoint(0f,0f),0.7f)
        var players=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,
            hull=FlagshipHull(0,now),energy=TacticalEnergy(listOf(100)+List(10){0}),
            motion=TacticalMotion(2487,now,pose,0f,0.7f,listOf(pose.position))),
            "enemy" to Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,1000,cpPaused=true,hull=FlagshipHull(89,now)))
        val store=object:NavigationStore {override fun load()=players;override fun save(next:Map<String,Navigator>){players=next}}
        val nav=Navigation(galaxy,store,NavigationPolicy());val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)))
        val request=NavigationRequest("pilot",10000,Power.EMPIRE,"c",1,captainCard=59,shipKind=0,
            tacticalMove=TacticalMove(listOf(1010000),1f,2f,listOf(TacticalPoint(100f,0f)),parallel=true))
        val moving=nav.apply(request,session,stocks).player!!.motion!!
        assertEquals(0.5f,moving.velocity);assertEquals(0.7f,moving.pose(now+9).direction)
        val reduced=nav.apply(request.copy(tacticalMove=null,unitId=1010000,energy=TacticalEnergy(listOf(20)+List(10){0})),
            session.copy(gameMillis=(now+9)*1000),stocks).player!!.motion!!
        assertTrue(reduced.parallel);assertEquals(0.1f,reduced.velocity);assertEquals(moving.pose(now+9),reduced.origin)
        assertEquals(0.7f,reduced.pose(now+100).direction)
    }
    @Test fun initialDeploymentKeepsBothSidesInsideNativeRadarWithoutOverlappingUnits() {
        val positions=Power.entries.flatMap {power-> (0..599).map {TacticalDeployment.formation(it,power).position}}
        assertEquals(1200,positions.distinct().size)
        assertTrue(positions.all {kotlin.math.hypot(it.x,it.y)<250})
    }
    @Test fun serverScheduleInterpolatesPathWithoutOvershootingAndKeepsFinalDirection() {
        val origin=TacticalPose(TacticalPoint(0f,0f),1.2f)
        val motion=TacticalMotion(2017,100,origin,0.5f,2.4f,
            listOf(TacticalPoint(0f,0f),TacticalPoint(3f,4f),TacticalPoint(6f,8f)))
        assertEquals(origin,motion.pose(99));assertEquals(origin,motion.pose(105))
        assertEquals(TacticalPoint(1.5f,2f),motion.pose(110).position)
        assertEquals(TacticalPoint(3f,4f),motion.pose(115).position)
        assertEquals(listOf(TacticalPoint(6f,8f)),motion.remaining(115))
        assertEquals(TacticalPose(TacticalPoint(6f,8f),2.4f),motion.pose(125))
        assertEquals(motion.pose(125),motion.pose(0xffffffffL))
        assertEquals(origin,motion.copy(velocity=0f).pose(0xffffffffL))
        assertFailsWith<IllegalArgumentException>{motion.copy(acceptedAt=0xffffffffL)}
        assertFailsWith<IllegalArgumentException>{TacticalPoint(Float.NaN,0f)}
    }
    @Test fun ownMoveUsesAuthoritativeOriginAndEnergyAndSurvivesReloadThenReroutesFromCurrentPosition() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val pilot=Navigator(1,10000,Power.ALLIANCE,2487,600,600,1000,
            energy=TacticalEnergy(listOf(50)+List(10){0}))
        val store=object:NavigationStore {
            var state=mapOf("pilot" to pilot,"enemy" to pilot.copy(characterId=10001,power=Power.EMPIRE))
            override fun load()=state
            override fun save(players:Map<String,Navigator>){state=players.toMap()}
        }
        val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,0,0,0)))
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
        val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,captainCard=195,shipKind=89,
            tacticalMove=TacticalMove(listOf(1010000),0.5f,0f,listOf(TacticalPoint(-600f,-180f))))
        var navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.UNIT,navigation.apply(request.copy(tacticalMove=request.tacticalMove!!.copy(units=listOf(1010001))),session,stocks).rejection)
        assertEquals(NavigationRejection.CARD,navigation.apply(request.copy(captainCard=59),session,stocks).rejection)
        assertEquals(NavigationRejection.ENERGY,navigation.apply(request.copy(tacticalMove=request.tacticalMove!!.copy(velocity=1f)),session,stocks).rejection)
        assertNull(store.state.getValue("pilot").motion)
        val after=navigation.apply(request,session,stocks).player!!
        val motion=after.motion!!
        assertEquals(session.gameMillis/1000+120,motion.startsAt)
        assertEquals(TacticalPoint(-180f,-95f),motion.origin.position)
        assertEquals(600,after.pcp);assertEquals(600,after.mcp)
        navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(motion,navigation.snapshot(1).getValue("pilot").motion)
        val later=session.copy(gameMillis=session.gameMillis+130000)
        val next=navigation.apply(request,later,stocks).player!!.motion!!
        val delta=kotlin.math.atan2(-420.0,-85.0)-Math.PI/2
        val firstTurn=kotlin.math.abs(kotlin.math.atan2(kotlin.math.sin(delta),kotlin.math.cos(delta)))
        val travel=((10.0-firstTurn)*0.5).toFloat() // Ten moving G-seconds include the initial turn.
        val expectedX=-180f+(-600f+180f)*travel/kotlin.math.hypot(-600f+180f,-180f+95f)
        assertEquals(expectedX,next.origin.position.x,0.0001f)
        val stopRequest=request.copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(1010000)))
        val movingTime=later.copy(gameMillis=later.gameMillis+130000)
        val currentPose=next.pose(movingTime.gameMillis/1000)
        assertEquals(NavigationRejection.UNIT,navigation.apply(stopRequest.copy(tacticalStop=TacticalStop(listOf(1010001))),movingTime,stocks).rejection)
        assertEquals(NavigationRejection.UNIT,navigation.apply(stopRequest.copy(tacticalStop=TacticalStop(listOf(1010000),listOf(1000))),movingTime,stocks).rejection)
        assertEquals(NavigationRejection.CARD,navigation.apply(stopRequest.copy(captainCard=59),movingTime,stocks).rejection)
        val stopped=navigation.apply(stopRequest,movingTime,stocks).player!!
        assertEquals(0f,stopped.motion!!.velocity)
        assertEquals(currentPose,stopped.motion!!.pose(movingTime.gameMillis/1000))
        navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(currentPose,navigation.snapshot(1).getValue("pilot").motion!!.pose(movingTime.gameMillis/1000+1000))
        assertEquals(600,stopped.pcp);assertEquals(600,stopped.mcp)
        assertEquals(pilot.energy,stopped.energy)
        // A new move starts at the stopped location, not the old deployment or destination.
        val resumed=navigation.apply(request,movingTime,stocks).player!!.motion!!
        assertEquals(currentPose,resumed.origin)
        val reduced=navigation.apply(request.copy(tacticalMove=null,unitId=1010000,
            energy=TacticalEnergy.ZERO),movingTime,stocks).player!!
        assertEquals(0f,reduced.motion!!.velocity)
        assertEquals(currentPose.position,reduced.motion!!.pose(movingTime.gameMillis/1000+100).position)
        store.state=store.state-"enemy";navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.TACTICAL,navigation.apply(request,later,stocks).rejection)
        assertNull(store.state.getValue("pilot").motion)
    }
}
