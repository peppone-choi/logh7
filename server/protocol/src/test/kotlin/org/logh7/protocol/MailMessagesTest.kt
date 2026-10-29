package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

/** 코덱 단위 검사이며 원본 UI의 송신 완료 증거가 아니다. 작성: 최병호. */
class MailMessagesTest {
    private val a=MailMessages.Peer(10000,"한글 日本",1,20)
    private val b=MailMessages.Peer(10001,"수신인",2,20)
    private fun body(packet:ByteArray)=packet.copyOfRange(4,packet.size)
    @Test fun queryMinimalSizeAndUnicodeItemRoundTripWithNativeNulCounts() {
        val query=MailMessages.Query(MailMessages.Peer(10000),true,true)
        val request=body(MailMessages.begin(query))
        assertEquals(28,request.size);assertEquals(query,MailMessages.query(request))
        val item=MailMessages.Item(19,-1,false,a,b,0xffffffffL,"가".repeat(126)+"힣","나".repeat(510)+"힣")
        val encoded=body(MailMessages.item(item,0x0f10))
        assertEquals(item,MailMessages.item(encoded))
        for(length in encoded.indices)assertFails {MailMessages.item(encoded.copyOf(length))}
        assertFails {MailMessages.item(encoded+byteArrayOf(0))}
        assertFails {MailMessages.item(item.copy(subject="가".repeat(128)))}
        assertFails {MailMessages.item(item.copy(text="가".repeat(512)))}
        assertFails {MailMessages.item(item.copy(subject="bad\u0000title"))}
    }
    @Test fun exchangesUpdatesAndListsUseDifferentNativePayloads() {
        val exchange=MailMessages.Exchange(a,b,3,1)
        assertEquals(exchange,MailMessages.exchange(body(MailMessages.exchange(exchange))))
        val change=MailMessages.Change(a,true,17)
        for(opcode in listOf(0x0f11,0x0f12))assertEquals(change,MailMessages.change(body(MailMessages.change(change,opcode)),opcode))
        assertEquals(a,MailMessages.deleteAddress(body(MailMessages.deleteAddress(a))))
        val book=MailMessages.addresses(listOf(a,b))
        assertEquals(0x0f05,ByteBuffer.wrap(book).getShort(4).toInt() and 65535)
        assertEquals(2,book[6].toInt())
        assertFails {MailMessages.addresses(List(101){a.copy(id=it+1)})}
        assertFails {MailMessages.addresses(listOf(a,a))}
    }
    @Test fun normalRejectionUsesOriginal0500ReaderFieldsAndLeavesRoomForDispatchNul() {
        val packet=MailMessages.rejected(0x0f10,"실패")
        assertContentEquals(byteArrayOf(0,0,0,0,5,0,15,16,0,3,
            0xc2.toByte(),0xe4.toByte(),0xd3.toByte(),0x28,0,0),packet)
        val maximum=MailMessages.rejected(0x0f10,"가".repeat(126))
        assertEquals(127,maximum[9].toInt() and 255)
        assertEquals(10+127*2,maximum.size)
        assertFails {MailMessages.rejected(0x0f10,"가".repeat(127))}
        assertFails {MailMessages.rejected(0x0f10,"bad\u0000text")}
        assertFails {MailMessages.rejected(0x0f10,"")}
        assertFails {MailMessages.rejected(0x0700,"오류")}
    }
}
