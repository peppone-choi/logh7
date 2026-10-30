package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalChargeTest {
    private fun energy(beam: Int, gun: Int) = TacticalEnergy(List(11) { when(it) { 3 -> beam; 4 -> gun; else -> 0 } })
    @Test fun clockScaleCapsAndFractionalWorkSurviveFrequentRebases() {
        for (scale in listOf(1L, 24L, 1000L)) {
            val charge=TacticalCharge(100, clockScale=scale)
            assertEquals(50 to 25, charge.levels(100+5*scale,energy(100,50)))
            assertEquals(100 to 100, charge.levels(0xffffffffL,energy(100,50)))
            assertEquals(0 to 0, charge.levels(1000,energy(0,0)))
            var frequent=charge
            repeat((5*scale).toInt()) { frequent=frequent.settle(frequent.at+1,energy(1,1)) }
            assertEquals(charge.settle(100+5*scale,energy(1,1)),frequent)
        }
        assertFailsWith<IllegalArgumentException>{TacticalCharge(100).settle(99,energy(100,100))}
        assertFailsWith<IllegalArgumentException>{TacticalCharge(0,24001)}
    }
    @Test fun allocationChangesSettleOldRateBeforePersistingNewRateAndBattleExitClearsCharge() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),emptyList(),Power.entries.associateWith {2487})
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"c")}
            .exportState().let {it.copy(gameMillis=it.gameMillis+10000)}
        val now=session.gameMillis/1000
        var saved=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2487,1000,960,900,cpPaused=true))
        var fail=false
        val store=object:NavigationStore {
            override fun load()=saved
            override fun save(players:Map<String,Navigator>){if(fail)throw java.io.IOException("disk");saved=players.toMap()}
        }
        var nav=Navigation(galaxy,store,NavigationPolicy())
        val request=NavigationRequest("pilot",10000,Power.EMPIRE,"c",1,unitId=1010000,captainCard=59,
            shipKind=0,energy=energy(100,50))
        val first=nav.apply(request,session).player!!
        assertEquals(now,first.charge!!.at)
        nav=Navigation(galaxy,store,NavigationPolicy())
        val changed=nav.apply(request.copy(energy=energy(0,100)),session.copy(gameMillis=(now+120)*1000)).player!!
        assertEquals(50 to 25, changed.charge!!.levels(now+120,changed.energy!!))
        assertEquals(50 to 75, changed.charge.levels(now+240,changed.energy))
        assertEquals(first.mcp,changed.mcp);assertEquals(first.cruise,changed.cruise)
        for ((slot,value) in listOf(0 to 101,1 to 51,2 to 31,4 to 101,5 to 101)) {
            val invalid=TacticalEnergy(List(11){if(it==slot)value else 0})
            assertEquals(NavigationRejection.ENERGY,nav.apply(request.copy(energy=invalid),
                session.copy(gameMillis=(now+120)*1000)).rejection)
        }
        val before=nav.snapshot(1)
        fail=true
        assertFailsWith<java.io.IOException>{nav.apply(request,session.copy(gameMillis=(now+120)*1000))}
        assertEquals(before,nav.snapshot(1));fail=false
        val ended=nav.apply(request.copy(energy=null,tactical=false),session.copy(gameMillis=(now+240)*1000)).player!!
        assertNull(ended.charge);assertFalse(ended.cpPaused)
    }
}
