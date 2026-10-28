package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class GroundDefinitionMessagesTest {
    @Test fun stationedGroundHasBaseAfterTheEmptyBoardingAndTroopFields() {
        val bytes=ByteBuffer.wrap(TacticsMessages.definitions(2487,emptyList(),
            listOf(TacticsMessages.GroundDefinition(1201,1001,15,1049))))
        assertEquals(50,bytes.limit());assertEquals(0x0325,bytes.getShort(4).toInt())
        assertEquals(1,bytes.getShort(6).toInt());assertEquals(1201,bytes.getInt(8))
        assertEquals(1001,bytes.getShort(12).toInt());assertEquals(4,bytes.get(14).toInt())
        assertEquals(2487,bytes.getInt(15));assertEquals(15,bytes.getInt(19))
        // Independent native reader layout: record+15 boarding_ship; +19 troop count; +20 base.
        assertEquals(0,bytes.getInt(23));assertEquals(0,bytes.get(27).toInt());assertEquals(1049,bytes.getInt(28))
        assertEquals(100,bytes.get(32).toInt());assertEquals(0,bytes.getInt(38));assertEquals(0f,bytes.getFloat(46))
    }
    @Test fun sharedIdSpaceAndNativeSixHundredLimitApplyAcrossShipAndGroundRows() {
        val ship=TacticsMessages.Unit(1,32,7,10000,0,0f,0,0f,0f,0f,0f)
        val ground=TacticsMessages.GroundDefinition(2,1000,7,1049)
        assertEquals(2,ByteBuffer.wrap(TacticsMessages.definitions(2487,listOf(ship),listOf(ground))).getShort(6).toInt())
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship),listOf(ground.copy(id=1)))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,emptyList(),listOf(ground,ground))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,emptyList(),listOf(ground.copy(base=0)))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,emptyList(),listOf(ground.copy(kind=32)))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship),List(600){ground.copy(id=it+2)})}
    }
}
