package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 성계 그리드 항성의 이동·사선 장애(그룹 71 반경, 1단위=3000만km). 작성: Peppone Choi. */
class TacticalTerrainTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,0,"Blue",2),GalaxySystem(4,2488,6,"Red",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Base",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private fun p(x:Float,y:Float)=TacticalPoint(x,y)

    @Test fun starClassesGiveTheGroupSeventyOneRadiiAndSpaceHasNone() {
        assertEquals(10.0,TacticalTerrain.starRadius(galaxy,2487))
        assertEquals(0.05,TacticalTerrain.starRadius(galaxy,2488)!!,1e-9)
        assertNull(TacticalTerrain.starRadius(galaxy,2489))
    }
    @Test fun movesThroughOrIntoTheStarAreRefusedButOutwardMovesFromInsideAreAllowed() {
        assertTrue(TacticalTerrain.pathBlocked(galaxy,2487,p(-20f,0f),listOf(p(20f,0f))))
        assertTrue(TacticalTerrain.pathBlocked(galaxy,2487,p(-20f,0f),listOf(p(-20f,20f),p(5f,0f))))
        assertFalse(TacticalTerrain.pathBlocked(galaxy,2487,p(-20f,11f),listOf(p(20f,11f))))
        assertFalse(TacticalTerrain.pathBlocked(galaxy,2487,p(5f,0f),listOf(p(30f,0f))))
        assertTrue(TacticalTerrain.pathBlocked(galaxy,2487,p(5f,0f),listOf(p(1f,0f))))
        assertFalse(TacticalTerrain.pathBlocked(galaxy,2489,p(-20f,0f),listOf(p(20f,0f))))
    }
    @Test fun weaponsCannotFireAcrossTheStar() {
        assertTrue(TacticalTerrain.fireBlocked(galaxy,2487,p(-20f,0f),p(20f,0f)))
        assertFalse(TacticalTerrain.fireBlocked(galaxy,2487,p(-20f,15f),p(20f,15f)))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)))
        assertTrue(TacticalLineOfFire.blocked(1,2487,Power.EMPIRE,p(-20f,0f),p(20f,0f),0,emptyList(),stocks,galaxy))
        assertFalse(TacticalLineOfFire.blocked(1,2487,Power.EMPIRE,p(-20f,15f),p(20f,15f),0,emptyList(),stocks,galaxy))
    }
}
