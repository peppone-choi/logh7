package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0040e700/00427160: card u16, count u8, ordinary character records. */
object CardCharacterMessages {
    const val PAGE_SIZE=64
    data class Query(val card:Int,val page:Int?=null)
    fun query(message: ByteArray): Query {
        require(message.size == 4)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x034e)
        val value=input.short.toInt() and 65535
        if (value and 0x8000 == 0) {
            require(value <= 256)
            return Query(value)
        }
        // Bit 15 marks paging and bits 8–14 hold the page. Low byte zero was unused for public office cards.
        val card = value and 0xff
        return Query(if (card == 0) 256 else card, (value shr 8) and 0x7f)
    }
    fun response(card: Int, records: List<ByteArray>, failed:Boolean=false): ByteArray {
        require(card in 0..256 && records.size <= PAGE_SIZE)
        require(!failed || records.isEmpty())
        require(records.all { it.size >= 6 && ByteBuffer.wrap(it).getShort(4).toInt() == 0x0323 })
        val out = ByteBuffer.allocate(3 + records.sumOf { it.size - 6 })
        out.putShort(card.toShort()).put(records.size.toByte())
        records.forEach { out.put(it,6,it.size-6) }
        return LoginMessages.sessionMessage(0x034f,out.array(),if(failed)1 else 0)
    }
}
