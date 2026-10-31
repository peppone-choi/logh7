package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** update05: a retreat leaves for the adjacent grid in the direction of the unit's position. Author: Peppone Choi. */
class TacticalRetreatDirectionTest {
    private fun galaxy(blocked:Set<Int> = emptySet()):Galaxy {
        val rows=List(50) {y->String(CharArray(100) {x->if(y*100+x in blocked)'#' else '.'})}
        return Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),emptyList(),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487),terrain=rows)
    }
    private val player=Navigator(1,10000,Power.EMPIRE,2015,1000,1000,1000)
    private fun from(x:Float,y:Float,g:Galaxy=galaxy())=
        RetreatRules.destination(g,player,listOf(player),null,TacticalPose(TacticalPoint(x,y),0f))

    @Test fun eachSideOfTheFieldLeadsToThatNeighbour() {
        assertEquals(2016,from(300f,0f));assertEquals(2014,from(-300f,0f))
        assertEquals(2115,from(0f,300f));assertEquals(1915,from(0f,-300f))
        assertEquals(2116,from(260f,260f));assertEquals(1914,from(-260f,-260f))
    }
    @Test fun aBlockedNeighbourFallsBackToTheNextClosestDirection() {
        val east=from(300f,30f,galaxy(setOf(2016)))
        assertNotNull(east);assertNotEquals(2016,east)
        assertTrue(east==2116 || east==1916)
        assertNull(from(300f,0f,galaxy((-1..1).flatMap {dy->(-1..1).map {dx->2015+dy*100+dx}}.filter {it!=2015}.toSet())))
    }
}
