package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import kotlin.test.*

class SystemOwnershipBootstrapTest {
    @Test fun loginPublishesEveryCurrentOwnerInNativeFourRecordBatches() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Other",3)),
            (1..7).map { GalaxyBase(it,if(it<=3)2487 else 2488,80,0,"Base$it",0,20f,86400,0,0f,2f) },
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
        val state=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,if(it.id==5)1 else 0,0,0,0)})
        val world=WorldBootstrap(galaxy)
        fun conditions(directory:Boolean)=world.initialize(2487,directory,state).filter {
            ByteBuffer.wrap(it).getShort(4).toInt() and 65535 == 0x031f
        }
        val all=conditions(true)
        assertEquals(listOf(4,3,3),all.map {it[6].toInt()})
        assertEquals(listOf(1,5,1),all.map {ByteBuffer.wrap(it).getInt(7)})
        assertEquals(2,all[1][11].toInt()) // current Empire owner, despite Alliance seed
        assertEquals(1,all[1][12].toInt()) // current rebel camp survives
        assertEquals(listOf(3),conditions(false).map {it[6].toInt()})
    }
}
