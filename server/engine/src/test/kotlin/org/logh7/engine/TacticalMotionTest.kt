package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalMotionTest {
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
        assertEquals(TacticalPoint(-180f,-95f),motion.origin.position)
        assertEquals(600,after.pcp);assertEquals(600,after.mcp)
        navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(motion,navigation.snapshot(1).getValue("pilot").motion)
        val later=session.copy(gameMillis=session.gameMillis+15000)
        val next=navigation.apply(request,later,stocks).player!!.motion!!
        val expectedX=-180f+(-600f+180f)*5f/kotlin.math.hypot(-600f+180f,-180f+95f)
        assertEquals(expectedX,next.origin.position.x,0.0001f)
        val stopRequest=request.copy(tacticalMove=null,tacticalStop=TacticalStop(listOf(1010000)))
        val movingTime=later.copy(gameMillis=later.gameMillis+15000)
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
