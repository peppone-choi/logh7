package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalMotionTest {
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
        assertEquals(TacticalPoint(-700f,-180f),motion.origin.position)
        assertEquals(600,after.pcp);assertEquals(600,after.mcp)
        navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(motion,navigation.snapshot(1).getValue("pilot").motion)
        val later=session.copy(gameMillis=session.gameMillis+15000)
        val next=navigation.apply(request,later,stocks).player!!.motion!!
        assertEquals(TacticalPoint(-695f,-180f),next.origin.position)
        val reduced=navigation.apply(request.copy(tacticalMove=null,unitId=1010000,
            energy=TacticalEnergy.ZERO),later,stocks).player!!
        assertEquals(0f,reduced.motion!!.velocity)
        assertEquals(next.origin.position,reduced.motion!!.pose(later.gameMillis/1000+100).position)
        store.state=store.state-"enemy";navigation=Navigation(galaxy,store,NavigationPolicy())
        assertEquals(NavigationRejection.TACTICAL,navigation.apply(request,later,stocks).rejection)
        assertNull(store.state.getValue("pilot").motion)
    }
}
