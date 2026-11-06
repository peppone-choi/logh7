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
    private fun transport(command:Int,card:Int,target:Int,value:Int,argument:Int,amount:Int,base:Int,preview:Boolean=false)=
        ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(command.toShort()).putShort(card.toShort()).putShort(value.toShort())
            .putInt(target).put(if(preview)1 else 0).putInt(argument).putShort(amount.toShort()).putInt(base).putShort(0).array()
    @Test fun foreignTradeDirectoriesAndSelectedCountriesKeepTheOldEnvelopeAndRejectUnrelatedFields() {
        val convoy=StrategyActionMessages.query(transport(40,44,43,0,0,1000,0))
        assertEquals(43,convoy.target);assertEquals(1000,convoy.amount)
        val intel=StrategyActionMessages.query(transport(20,5,Int.MAX_VALUE,2,0,0,0,true))
        assertTrue(intel.preview);assertEquals(Int.MAX_VALUE,intel.target)
        val directory=StrategyActionMessages.query(transport(40,44,0,0,0,0,0,true))
        for(q in listOf(directory,intel)) {
            val rows=StrategyActionMessages.selections(q,listOf(StrategyActionMessages.Selection(43,"국가43")))
            assertEquals(43,ByteBuffer.wrap(rows[1]).getInt(7))
        }
        // Old clients still omit the country. The engine accepts this only when one candidate remains.
        StrategyActionMessages.query(transport(40,44,0,0,0,1000,0))
        StrategyActionMessages.query(transport(20,5,0,2,0,0,0))
        for(body in listOf(transport(20,5,43,0,0,0,0),transport(20,5,0,1,0,0,0,true),
            transport(40,44,0,0,0,1000,0,true),transport(40,44,43,0,0,0,0),transport(20,5,-1,2,0,0,0),
            transport(20,5,43,2,1,0,0),transport(40,44,43,0,0,1000,1)))
            assertFailsWith<IllegalArgumentException> {StrategyActionMessages.query(body)}
    }
    @Test fun transportCommandsCarrySourceDestinationQuantitiesAndTheOutfitSeparately() {
        val plan=StrategyActionMessages.query(transport(28,25,1050,32,3,1000,1049))
        assertEquals(1050,plan.target);assertEquals(1049,plan.base);assertEquals(1000,plan.amount);assertEquals(32,plan.value)
        assertEquals(3,plan.argument);assertEquals(0,plan.outfit)
        StrategyActionMessages.query(transport(28,150,1050,0xffff,0,1,1049))
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(transport(28,25,1050,0xffff,3,1,1049))}
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(transport(28,44,1050,32,3,1,1049))}
        val load=StrategyActionMessages.query(transport(65,44,7,0,200,0,0))
        assertEquals(200,load.outfit);assertEquals(7,load.target)
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(transport(65,44,7,0,0,0,0))}
        assertTrue(StrategyActionMessages.query(transport(29,24,0,0,0,0,0,preview=true)).preview)
        assertFailsWith<IllegalArgumentException>{StrategyActionMessages.query(transport(29,24,7,0,0,5,0))}
    }
}
