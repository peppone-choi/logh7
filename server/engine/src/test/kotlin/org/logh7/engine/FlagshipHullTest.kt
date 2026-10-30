package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FlagshipHullTest {
    @Test fun shieldFractionAndPerFaceRegenerationSurviveAllocationChangesWithoutRetroactiveEnergy() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val rules=SessionRules.load();val session=SessionSimulation(rules).also {it.join("p",Power.ALLIANCE,false,"c")}.exportState()
        val now=session.gameMillis/1000
        val energy=TacticalEnergy(List(11){if(it==5)100 else 0})
        val hull=FlagshipHull(89,now,shields=List(6){0L})
        var saved=mapOf("p" to Navigator(1,10000,Power.ALLIANCE,2487,1000,1000,900,cpPaused=true,energy=energy,hull=hull))
        val store=object:NavigationStore {
            override fun load()=saved
            override fun save(players:Map<String,Navigator>){saved=players.toMap()}
        }
        val nav=Navigation(galaxy,store,NavigationPolicy())
        val request=NavigationRequest("p",10000,Power.ALLIANCE,"c",1,unitId=1010000,captainCard=195,shipKind=89,energy=TacticalEnergy.ZERO)
        val changed=nav.apply(request,session.copy(gameMillis=(now+12)*1000)).player!!
        assertEquals(1200,changed.hull!!.shields[0]);assertEquals(List(6){0},changed.hull!!.levels())
        val restored=Navigation(galaxy,store,NavigationPolicy()).snapshot(1).getValue("p").hull!!
        assertEquals(1200,restored.settle(now+24,TacticalEnergy.ZERO).shields[0])
        assertEquals(1,hull.settle(now+24,energy).levels()[0]);assertEquals(0,hull.settle(now+24,energy).levels()[1])
        assertEquals(FlagshipHull.capacity(89),hull.settle(now+100000,energy).levels()[0])
        assertFailsWith<IllegalArgumentException>{restored.settle(now+11,TacticalEnergy.ZERO)}
    }
}
