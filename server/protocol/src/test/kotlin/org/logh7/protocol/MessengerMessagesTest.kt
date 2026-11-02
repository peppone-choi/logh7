package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class MessengerMessagesTest {
    private fun body(packet:ByteArray)=packet.copyOfRange(4,packet.size)
    private fun minimalPeer(id:Int)=ByteBuffer.allocate(24).putInt(id).array()
    @Test fun nativeStatusShapeIsPeerThenOneByte() {
        val raw=byteArrayOf(0x0f,0x0d)+minimalPeer(10000)+byteArrayOf(1)
        val result=MessengerMessages.status(raw)
        assertEquals(10000,result.peer.id);assertEquals(1,result.status)
        assertContentEquals(raw,body(MessengerMessages.status(result)))
    }
    @Test fun nativeLinkIsCloseByteTwoPeersPhaseAndResult() {
        val raw=byteArrayOf(0x0f,0x0e,0)+minimalPeer(10000)+minimalPeer(10001)+byteArrayOf(3,1)
        val result=MessengerMessages.link(raw)
        assertEquals(MessengerMessages.Link(false,MailMessages.Peer(10000),MailMessages.Peer(10001),3,1),result)
        assertContentEquals(raw,body(MessengerMessages.link(result)))
        val close=byteArrayOf(0x0f,0x0e,1)+minimalPeer(10000)+minimalPeer(0)+byteArrayOf(0,0)
        assertEquals(0,MessengerMessages.link(close).second.id) // Native close leaves this peer unset.
    }
    @Test fun nativeTextIsWordPeerThenWideLengthIncludingNul() {
        val raw=byteArrayOf(0x0f,0x0f)+ByteArray(4)+minimalPeer(10001)+byteArrayOf(0,3,0xd5.toByte(),0x5c,0xae.toByte(),0x00,0,0)
        val result=MessengerMessages.text(raw)
        assertEquals("한글",result.text);assertEquals(10001,result.peer.id);assertEquals(0,result.id)
        assertContentEquals(raw,body(MessengerMessages.text(result)))
        assertEquals(511,MessengerMessages.text(body(MessengerMessages.text(result.copy(text="힣".repeat(511))))).text.length)
        assertFails {MessengerMessages.text(result.copy(text="힣".repeat(512)))}
    }
    @Test fun onlineDirectoryKeepsByteCountAndNativeCapacity() {
        val rows=(1..100).map {MessengerMessages.Status(MailMessages.Peer(it,"한글$it"),it%2)}
        assertEquals(rows,MessengerMessages.directory(body(MessengerMessages.directory(rows))))
        assertEquals(100,body(MessengerMessages.directory(rows))[2].toInt())
        assertFails {MessengerMessages.directory(rows+MessengerMessages.Status(MailMessages.Peer(101),0))}
        assertFails {MessengerMessages.directory(rows+rows.first())}
        assertFails {MessengerMessages.directory(byteArrayOf(0x0f,0x07,101))}
    }
    @Test fun truncationExtraDataWrongOpcodeAndBrokenWideTextReject() {
        val packets=listOf(MessengerMessages.status(MessengerMessages.Status(MailMessages.Peer(1),0)),
            MessengerMessages.link(MessengerMessages.Link(false,MailMessages.Peer(1),MailMessages.Peer(2),1,0)),
            MessengerMessages.text(MessengerMessages.Text(0,MailMessages.Peer(2),"본문")))
        val parsers=listOf<(ByteArray)->Any>(MessengerMessages::status,MessengerMessages::link,MessengerMessages::text)
        for((index,packet) in packets.withIndex()) {
            val raw=body(packet)
            for(size in 0 until raw.size)assertFails {parsers[index](raw.copyOf(size))}
            assertFails {parsers[index](raw+byteArrayOf(0))}
            assertFails {parsers[index](raw.copyOf().also {it[1]=0})}
        }
        val broken=body(packets.last()).also {it[it.lastIndex]=1}
        assertFails {MessengerMessages.text(broken)}
    }
}
