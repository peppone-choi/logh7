package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** The one initial-condition rule every base follows (planet-economy.md). Author: Peppone Choi. */
class BaseConditionTest {
    private fun base(id:Int,grid:Int,model:Int,kind:Int,name:String)=GalaxyBase(id,grid,model,kind,name,0,0f,86400,0,0f,2f)
    @Test fun capitalsHabitablePlanetsAndOtherBasesFollowOneRule() {
        val odin=BaseCondition.initial(base(1049,2487,100,0,"オーディン"),2)
        val heinessen=BaseCondition.initial(base(1177,2015,100,0,"ハイネセン"),3)
        val habitable=BaseCondition.initial(base(1,10,80,0,"Earthlike"),2)
        val gas=BaseCondition.initial(base(2,10,41,0,"Gas"),3)
        val fortress=BaseCondition.initial(base(3,11,100,1,"Fortress"),2)
        val neutral=BaseCondition.initial(base(4,12,100,0,"Fezzan"),0)
        assertEquals(listOf(1_000_000_000,1_000_000_000,100_000_000,100_000,100_000,100_000_000),
            listOf(odin,heinessen,habitable,gas,fortress,neutral).map {it.population})
        for(c in listOf(odin,heinessen,habitable,gas,fortress,neutral)) {
            assertEquals(c.population/5*3,c.adultPopulation,"adults are 60%")
            assertEquals(listOf(20,70,80,100_000),listOf(c.tax,c.approval,c.peace,c.supplies))
            assertEquals(c.population/100,c.food);assertEquals(0,c.camp)
        }
        assertEquals(listOf(1,1,1,0,0,1),listOf(odin,heinessen,habitable,gas,fortress,neutral).map {it.habitability})
        assertEquals(listOf(2,3,2,3,2,0),listOf(odin,heinessen,habitable,gas,fortress,neutral).map {it.power},"owner = system power")
        assertFailsWith<IllegalArgumentException>("a capital must be habitable") {BaseCondition.initial(base(1049,2487,41,0,"オーディン"),2)}
    }
}
