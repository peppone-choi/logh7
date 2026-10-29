package org.logh7.protocol

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import kotlin.test.*

class StrategyActionMessagesTest {
    private fun request()=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f00).putShort(48).putShort(59).putShort(0).putInt(200)
        .put(0).putInt(0).putShort(0).putInt(0).putShort(0).array()
    @Test fun explicitExtensionKeepsTheNativeEnvelopeAndRejectsAuthorityFields() {
        val body=request();val q=StrategyActionMessages.query(body)
        assertEquals(10000,q.character);assertEquals(48,q.command);assertEquals(59,q.card);assertEquals(200,q.outfit)
        val response=StrategyActionMessages.response(q,5)
        assertEquals(2,response.size);assertEquals(5,ByteBuffer.wrap(response[0]).getShort(16).toInt())
        assertEquals(0x1201,ByteBuffer.wrap(response[1]).getShort(4).toInt())
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(body+byteArrayOf(0))}
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(body.copyOf().also {it[13]=1})}
        val token=body.copyOf().also {it[30]=1}
        assertEquals(1,ByteBuffer.wrap(StrategyActionMessages.query(token).raw).getShort(27).toInt())
    }
    @Test fun operationFieldsAreStrictAndTheRequestTokenSurvivesTheResponse() {
        val body=request().also {ByteBuffer.wrap(it).putShort(8,22).putShort(10,24).putShort(12,1)
            .putInt(14,2487).putInt(19,2).putShort(23,12).putShort(29,37)}
        val q=StrategyActionMessages.query(body)
        assertEquals(2487,q.target);assertEquals(12,q.amount);assertEquals(2,q.argument)
        val response=StrategyActionMessages.response(q,0).first()
        assertEquals(37,ByteBuffer.wrap(response).getShort(33).toInt())
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(body.copyOf().also {ByteBuffer.wrap(it).putInt(19,128)})}
    }
}
