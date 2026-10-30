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
    @Test fun carrierAndPassengerRowsUseReciprocalIdsAndPreserveVariableRowBoundaries() {
        val ship=TacticsMessages.Unit(1,32,7,10000,25,100f,0,0f,0f,0f,0f,troops=listOf(2,3))
        val ground=listOf(TacticsMessages.GroundDefinition(2,1000,7,0,1),TacticsMessages.GroundDefinition(3,1001,7,0,1))
        val packet=ByteBuffer.wrap(TacticsMessages.definitions(2487,listOf(ship),ground))
        assertEquals(142,packet.limit());assertEquals(3,packet.getShort(6).toInt())
        assertEquals(2,packet.get(27).toInt());assertEquals(2,packet.getInt(28));assertEquals(3,packet.getInt(32))
        assertEquals(0,packet.getInt(36));assertEquals(25,packet.getInt(46));assertEquals(100f,packet.getFloat(54))
        for((index,id) in listOf(2,3).withIndex()) {
            val row=58+42*index
            assertEquals(id,packet.getInt(row));assertEquals(10,packet.get(row+6).toInt())
            assertEquals(1,packet.getInt(row+15));assertEquals(0,packet.get(row+19).toInt());assertEquals(0,packet.getInt(row+20))
        }
    }
    @Test fun danglingCrossOutfitAndUnreciprocatedBoardingAreRejected() {
        val ship=TacticsMessages.Unit(1,32,7,10000,0,0f,0,0f,0f,0f,0f,troops=listOf(2))
        val ground=TacticsMessages.GroundDefinition(2,1000,7,0,1)
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,emptyList(),listOf(ground))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship),emptyList())}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship.copy(troops=emptyList())),listOf(ground))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship),listOf(ground.copy(outfit=8)))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship),listOf(ground.copy(base=1049)))}
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship.copy(troops=listOf(2,2))),listOf(ground))}
    }
    @Test fun tenTroopWireLimitIsEnforcedWithoutSilentlyDiscardingTheEleventh() {
        val troops=(2..11).map {TacticsMessages.GroundDefinition(it,1000,7,0,1)}
        val ship=TacticsMessages.Unit(1,32,7,10000,0,0f,0,0f,0f,0f,0f,troops=troops.map {it.id})
        assertEquals(510,TacticsMessages.definitions(2487,listOf(ship),troops).size)
        assertFailsWith<IllegalArgumentException> {TacticsMessages.definitions(2487,listOf(ship.copy(troops=(2..12).toList())),
            troops+TacticsMessages.GroundDefinition(12,1000,7,0,1))}
    }
    @Test fun deployedGroundAndCompletionNotificationMatchTheNativePackedFields() {
        val packet=ByteBuffer.wrap(TacticsMessages.definitions(2487,emptyList(),listOf(TacticsMessages.GroundDefinition(2,1000,7,1049,deployed=true))))
        assertEquals(9,packet.get(14).toInt());assertEquals(0,packet.getInt(23));assertEquals(1049,packet.getInt(28))
        for(withdraw in listOf(false,true)) {
            val moved=ByteBuffer.wrap(TacticalGroundMessages.movedTroop(700,withdraw,1,2,1049))
            assertEquals(23,moved.limit());assertEquals(0x0429,moved.getShort(4).toInt());assertEquals(700,moved.getInt(6))
            assertEquals(if(withdraw)1 else 0,moved.get(10).toInt());assertEquals(1,moved.getInt(11))
            assertEquals(2,moved.getInt(15));assertEquals(1049,moved.getInt(19))
        }
    }
    @Test fun existingBaseDefenseUsesANativeUnsignedShortNotification() {
        val changed=ByteBuffer.wrap(TacticalGroundMessages.baseDefenseChanged(700,1049,17000))
        assertEquals(16,changed.limit());assertEquals(0x042a,changed.getShort(4).toInt())
        assertEquals(700,changed.getInt(6));assertEquals(1049,changed.getInt(10));assertEquals(17000,changed.getShort(14).toInt())
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.baseDefenseChanged(700,1049,30001)}
    }
}
