package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class UnitSpecificationsMessagesTest {
    @Test fun shipUnitSizeCrewTermAndCargoUseTheRecoveredNativeWirePositions() {
        val ship = WorldMessages.Ship(32,0,12,"",0,5600f,20000f,34,20,12,0,0,48,100,80,110,140,
            category=3,number=300,term=90,crew=5,missileConsumption=5,fighterNumber=3,cargo=760)
        val wire = ByteBuffer.wrap(WorldMessages.ships(listOf(ship)))
        assertEquals(1,wire.get().toInt()); assertEquals(32,wire.short.toInt())
        assertEquals(0,wire.get().toInt()); assertEquals(3,wire.get().toInt())
        wire.short; assertEquals(12,wire.short.toInt()); assertEquals(1,wire.get().toInt()); assertEquals('\u0000',wire.char)
        assertEquals(300,wire.short.toInt()); assertEquals(0,wire.int)
        wire.short; wire.short; assertEquals(90,wire.short.toInt()); wire.short; assertEquals(5,wire.short.toInt())
        wire.position(wire.limit()-10)
        assertEquals(0,wire.short.toInt()); assertEquals(3,wire.short.toInt()); assertEquals(760,wire.short.toInt())
        assertEquals(140,wire.short.toInt()); assertEquals(0,wire.short.toInt())
        assertFails { WorldMessages.ships(listOf(ship.copy(number=0))) }
        assertFails { WorldMessages.ships(listOf(ship.copy(crew=65536))) }
    }
    @Test fun crewCatalogueUsesThousandBasedNativeIdsAndTwentyTwoByteWireRecords() {
        val crew = WorldMessages.Crew(3,3,19,120,0,0)
        val data = WorldMessages.crews(listOf(crew,crew.copy(kind=2,type=2,category=18,offence=50,defence=50)))
        assertEquals(45,data.size)
        val wire=ByteBuffer.wrap(data); assertEquals(2,wire.get().toInt())
        assertEquals(1003,wire.short.toInt()); assertEquals(3,wire.get().toInt()); assertEquals(19,wire.get().toInt())
        assertEquals(0,wire.short.toInt()); assertEquals(120,wire.short.toInt())
        wire.short; wire.short; assertEquals(1f,wire.float)
        assertEquals(0,wire.short.toInt()); assertEquals(0,wire.short.toInt()); assertEquals(0,wire.short.toInt())
        assertEquals(1002,wire.short.toInt()); wire.position(39)
        assertEquals(50,wire.short.toInt()); assertEquals(50,wire.short.toInt())
        assertFails { WorldMessages.crews(listOf(crew,crew)) }
        assertFails { WorldMessages.crews(listOf(crew.copy(kind=16))) }
        assertFails { WorldMessages.crews(listOf(crew.copy(speed=Float.NaN))) }
    }
}
