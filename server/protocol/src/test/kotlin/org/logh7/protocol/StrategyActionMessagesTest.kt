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
    @Test fun repairDistinguishesCaptainFromTheHeldCommandingOutfitAndRejectsInjectedQuantities() {
        val own=request().also {ByteBuffer.wrap(it).putShort(8,61).putInt(14,0)}
        assertEquals(0,StrategyActionMessages.query(own).outfit)
        val unchanged=StrategyActionMessages.response(StrategyActionMessages.query(own),0,false).first()
        assertEquals(3,unchanged[22].toInt())
        val fleet=own.copyOf().also {ByteBuffer.wrap(it).putShort(10,39).putInt(14,200)}
        assertEquals(200,StrategyActionMessages.query(fleet).outfit)
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(fleet.copyOf().also {ByteBuffer.wrap(it).putShort(23,1)})}
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(own.copyOf().also {ByteBuffer.wrap(it).putInt(14,200)})}
    }
    @Test fun purchaseAndConsentModesKeepSelectionFieldsSeparateFromAuthorityAndCp() {
        val buy=request().also {ByteBuffer.wrap(it).putShort(8,96).putShort(10,0).putShort(12,1).putInt(14,0)}
        assertEquals(1,StrategyActionMessages.query(buy).value)
        val accept=buy.copyOf().also {ByteBuffer.wrap(it).putShort(12,0).putInt(14,10001).putInt(19,2)}
        assertEquals(10001,StrategyActionMessages.query(accept).target);assertEquals(2,StrategyActionMessages.query(accept).argument)
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(buy.copyOf().also {ByteBuffer.wrap(it).putShort(10,59)})}
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(buy.copyOf().also {ByteBuffer.wrap(it).putInt(14,10001)})}
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(accept.copyOf().also {ByteBuffer.wrap(it).putInt(19,4)})}
        val preview=StrategyActionMessages.query(buy.copyOf().also {it[18]=1})
        val rows=StrategyActionMessages.selections(preview,listOf(StrategyActionMessages.Selection(2,"순항함 200",10)))
        assertEquals(0x1202,ByteBuffer.wrap(rows[1]).getShort(4).toInt())
        assertEquals(1,rows[1][6].toInt());assertEquals(2,ByteBuffer.wrap(rows[1]).getInt(7))
    }
}
