package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class TacticalGroundMessagesTest {
    @Test fun sortieUsesCountedCommandAndOneUnitCompletionWithNativeCoordinates() {
        val request=bytes("0412000000640000007800002710020000000100000002")
        val command=TacticalGroundMessages.Command(TacticalGroundMessages.Action.SORTIE,10000,listOf(1,2))
        assertEquals(command,TacticalGroundMessages.command(request))
        val reply=TacticalGroundMessages.reply(command,100,1080)
        assertEquals(command,TacticalGroundMessages.command(reply.copyOfRange(4,reply.size)))
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.command(request+0)}
        assertFailsWith<IllegalArgumentException>{command.copy(skill=1)}
        val unit=TacticalGroundMessages.PostureUnit(1,-0.5f,1f,2f,3f)
        assertContentEquals(bytes("000000000437000000640000041900000001bf0000003f8000004000000040400000"),
            TacticalGroundMessages.sortieCompleted(100,1049,unit))
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.sortieCompleted(100,0,unit)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.sortieCompleted(-1,1049,unit)}
    }
    private fun bytes(hex:String)=java.util.HexFormat.of().parseHex(hex)
    @Test fun nativePackedLayoutsKeepOrderAndHaveNoImplicitBaseOrNativePadding() {
        // Time/wait supplied by the client are ignored; a ground command has no target-base field.
        val deploy=bytes("040fffffffffffffffff000027100200000001000f695080")
        val command=TacticalGroundMessages.command(deploy)
        assertEquals(TacticalGroundMessages.Command(TacticalGroundMessages.Action.DEPLOY,10000,listOf(1,1010000),128),command)
        val reply=TacticalGroundMessages.reply(command,777600,120)
        assertEquals(28,reply.size)
        assertEquals(777600,ByteBuffer.wrap(reply).getInt(6));assertEquals(120,ByteBuffer.wrap(reply).getInt(10))
        assertEquals(command,TacticalGroundMessages.command(reply.copyOfRange(4,reply.size)))
        val withdraw=bytes("04100000000000000000000027100200000001000f6950")
        val recalled=TacticalGroundMessages.command(withdraw)
        assertEquals(TacticalGroundMessages.Action.WITHDRAW,recalled.action);assertEquals(command.units,recalled.units)
        assertEquals(0,recalled.skill)
        val rejected=TacticalGroundMessages.reply(recalled,0xffffffffL,0,false)
        assertEquals(1,ByteBuffer.wrap(rejected).int);assertEquals(27,rejected.size)
        assertEquals(recalled,TacticalGroundMessages.command(rejected.copyOfRange(4,rejected.size)))
        val posture=bytes("0411ffffffffffffffff0000271001000000010300000419")
        assertEquals(TacticalGroundMessages.Posture(10000,listOf(1),3,1049),TacticalGroundMessages.posture(posture))
        val changed=TacticalGroundMessages.reply(TacticalGroundMessages.posture(posture),100,240)
        assertEquals(28,changed.size);assertEquals(240,ByteBuffer.wrap(changed).getInt(10))
        assertEquals(TacticalGroundMessages.posture(posture),TacticalGroundMessages.posture(changed.copyOfRange(4,changed.size)))
    }
    @Test fun countedListsAndSuffixesRejectMalformedOrAmbiguousCommands() {
        val one=TacticalGroundMessages.Command(TacticalGroundMessages.Action.DEPLOY,10000,listOf(1),255)
        val reply=TacticalGroundMessages.reply(one,0,0);val valid=reply.copyOfRange(4,reply.size)
        for(length in valid.indices)assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.command(valid.copyOf(length))}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.command(valid+0)}
        for(count in listOf(0,2,33,255)) {
            val broken=valid.copyOf();broken[14]=count.toByte()
            assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.command(broken)}
        }
        for(id in listOf(0,-1)) {
            val broken=valid.copyOf();ByteBuffer.wrap(broken).putInt(15,id)
            assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.command(broken)}
        }
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.Command(one.action,10000,listOf(1,1))}
        assertFailsWith<IllegalArgumentException>{one.copy(character=0)}
        assertFailsWith<IllegalArgumentException>{one.copy(action=TacticalGroundMessages.Action.WITHDRAW)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.reply(one,0xffffffffL,1)}
        val many=one.copy(units=(1..32).toList());val max=TacticalGroundMessages.reply(many,0,0)
        assertEquals(many,TacticalGroundMessages.command(max.copyOfRange(4,max.size)))
        assertFailsWith<IllegalArgumentException>{many.copy(units=(1..33).toList())}
    }
    @Test fun completedPostureHasNoWaitOrCharacterAndPreservesNativeFloatOrderAndSpotTrailer() {
        val unit=TacticalGroundMessages.PostureUnit(1010000,-0.5f,1f,2f,3f)
        val message=TacticalGroundMessages.postureChanged(100,6,1049,listOf(unit),777,10000)
        assertContentEquals(bytes("00000000042f00000064060000041901000f6950bf0000003f80000040000000404000000000030900002710"),message)
        assertEquals(44,message.size)
        val max=TacticalGroundMessages.postureChanged(0xffffffffL,7,0,(1..32).map {unit.copy(id=it)},0,0)
        assertEquals(664,max.size)
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.postureChanged(0,6,1049,emptyList(),0,0)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.postureChanged(0,6,1049,listOf(unit,unit),0,0)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.postureChanged(0,6,1049,(1..33).map {unit.copy(id=it)},0,0)}
        for(kind in listOf(-1,0,3,8,256))assertFailsWith<IllegalArgumentException>{
            TacticalGroundMessages.postureChanged(0,kind,1049,listOf(unit),0,0)
        }
        for(value in listOf(-1L,0x100000000L))assertFailsWith<IllegalArgumentException>{
            TacticalGroundMessages.postureChanged(value,6,1049,listOf(unit),0,0)
        }
        assertFailsWith<IllegalArgumentException>{unit.copy(x=Float.NaN)}
        assertFailsWith<IllegalArgumentException>{unit.copy(direction=Float.POSITIVE_INFINITY)}
        assertFailsWith<IllegalArgumentException>{unit.copy(id=0)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.postureChanged(0,6,-1,listOf(unit),0,0)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.postureChanged(0,6,1049,listOf(unit),-1,0)}
        assertFailsWith<IllegalArgumentException>{TacticalGroundMessages.postureChanged(0,6,1049,listOf(unit),0,-1)}
    }
    @Test fun occupationUsesSixPackedBytesWithoutClockOrPadding() {
        assertContentEquals(bytes("000000000442000004190300"),
            TacticalGroundMessages.occupationFinished(1049,3,0))
        assertContentEquals(bytes("0000000004420000ffff0401"),
            TacticalGroundMessages.occupationFinished(65535,4,1))
        for(base in listOf(-1,0,65536)) assertFailsWith<IllegalArgumentException> {
            TacticalGroundMessages.occupationFinished(base,2,0)
        }
        for(power in listOf(-1,5,256)) assertFailsWith<IllegalArgumentException> {
            TacticalGroundMessages.occupationFinished(1049,power,0)
        }
        for(camp in listOf(-1,2,256)) assertFailsWith<IllegalArgumentException> {
            TacticalGroundMessages.occupationFinished(1049,2,camp)
        }
    }

}
