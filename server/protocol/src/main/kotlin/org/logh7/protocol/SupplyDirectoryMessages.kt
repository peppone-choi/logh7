package org.logh7.protocol

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Native search14 / 00561c80: u16 count, supplies/free-room then simple outfit. Author: Peppone Choi. */
object SupplyDirectoryMessages {
    private const val QUOTE_MARKER=0x5351
    data class Query(val character: Int, val grid: Int, val raw: ByteArray, val quoteNonce: Int = 0)
    data class Outfit(val id: Int, val kind: Int, val number: Int, val supplies: Int,
        val maximum: Int, val base: WorldMessages.Base)
    data class Quote(val source: Int, val target: Int, val amount: Int, val payment: Int, val status: Int)
    fun matches(message: ByteArray) = message.size == 31 && ByteBuffer.wrap(message).getShort(6).toInt() == 14
    fun query(message: ByteArray): Query {
        require(matches(message))
        val b = ByteBuffer.wrap(message)
        require(b.short.toInt() == 0x1200 && b.getShort(8).toInt() == 124)
        val marker=b.getShort(10).toInt()
        val nonce=b.getShort(12).toInt() and 0xffff
        require((marker==0 && nonce==0 || marker==QUOTE_MARKER && nonce>0) &&
            (14..18).all { message[it] == 0.toByte() } && (23..30).all { message[it] == 0.toByte() })
        val character = b.getInt(2); val grid = b.getInt(19)
        require(character > 0 && grid in 0..4999)
        return Query(character, grid, message.copyOfRange(2, message.size).also {it.fill(0,8,12)},nonce)
    }
    /** A private 1200 record read by the compatibility DLL before the unchanged native directory. */
    fun quote(q: Query, row: Quote): ByteArray {
        require(q.quoteNonce>0 && row.source>0 && row.target>0 && row.source!=row.target &&
            row.amount>=0 && row.payment in 0..2 && row.status in 0..3)
        val body=ByteBuffer.wrap(ByteArray(29)).putInt(q.character).putShort(StrategyActionMessages.SEARCH.toShort())
            .putShort(62).putShort(q.quoteNonce.toShort()).putShort(row.status.toShort()).putInt(row.source)
            .put(row.payment.toByte()).putInt(row.target).putShort(0).putInt(row.amount).array()
        return LoginMessages.sessionMessage(0x1200,body)
    }
    fun response(query: Query, outfits: List<Outfit>): List<ByteArray> {
        require(outfits.size <= 300 && outfits.distinctBy { it.id }.size == outfits.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(outfits.size)
            for (outfit in outfits) {
                require(outfit.id > 0 && outfit.kind in 0..3 && outfit.number in 0..255 &&
                    outfit.supplies in 0..outfit.maximum && outfit.base.grid == query.grid)
                out.writeInt(outfit.supplies); out.writeInt(outfit.maximum - outfit.supplies)
                out.writeInt(outfit.id); out.writeByte(outfit.number); out.writeByte(outfit.kind)
                out.write(WorldMessages.simpleBases(listOf(outfit.base)))
            }
        }
        return listOf(LoginMessages.sessionMessage(0x1200, query.raw),
            LoginMessages.sessionMessage(0x120b, bytes.toByteArray()), LoginMessages.sessionMessage(0x1201, byteArrayOf()))
    }
}
