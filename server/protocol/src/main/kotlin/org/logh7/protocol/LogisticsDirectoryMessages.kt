package org.logh7.protocol

import java.nio.ByteBuffer

/** Native allocation's base (search 32) and outfit (search 31) selectors. Author: Peppone Choi. */
object LogisticsDirectoryMessages {
    data class Query(val character: Int, val search: Int, val base: Int, val card: Int, val raw: ByteArray)
    fun matches(message: ByteArray) = message.size == 31 && ByteBuffer.wrap(message).getShort(6).toInt() in setOf(31,32)
    fun query(message: ByteArray): Query {
        require(matches(message))
        val b=ByteBuffer.wrap(message)
        require(b.short.toInt()==0x1200)
        val character=b.int;val search=b.short.toInt();val flags=b.short.toInt()
        val base=b.getInt(25);val card=b.getShort(29).toInt()
        require(character>0 && when(search) {
            32 -> flags==80 && message.drop(10).all { it==0.toByte() }
            31 -> flags==124 && message.slice(10..24).all { it==0.toByte() } &&
                base in 1..65535 && card in setOf(24,25,150)
            else -> false
        })
        return Query(character,search,base,card,message.copyOfRange(2,message.size))
    }
    private fun response(query: Query, opcode: Int, body: ByteArray) = listOf(
        LoginMessages.sessionMessage(0x1200,query.raw),LoginMessages.sessionMessage(opcode,body),
        LoginMessages.sessionMessage(0x1201,byteArrayOf()))
    /** 0055e200: count, base ID, grid, kind and UTF-16 name; no native padding. */
    fun bases(query: Query, bases: List<WorldMessages.Base>): List<ByteArray> {
        require(query.search==32 && bases.distinctBy { it.id }.size == bases.size)
        return response(query,0x1204,WorldMessages.simpleBases(bases))
    }
    data class Outfit(val id: Int, val kind: Int, val number: Int, val base: WorldMessages.Base)
    /** 0055d6c0: u32 outfit, byte number/kind, byte base count, optional SimpleInformationBase. */
    fun outfits(query: Query, outfits: List<Outfit>): List<ByteArray> {
        require(query.search==31 && outfits.size<=200 && outfits.distinctBy { it.id }.size==outfits.size)
        val bytes=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeByte(outfits.size)
            for (outfit in outfits) {
                require(outfit.id>0 && outfit.kind in 0..3 && outfit.number in 0..255 && outfit.base.id==query.base)
                out.writeInt(outfit.id);out.writeByte(outfit.number);out.writeByte(outfit.kind)
                // simpleBases begins with exactly the one-byte nested base count.
                out.write(WorldMessages.simpleBases(listOf(outfit.base)))
            }
        }
        return response(query,0x1203,bytes.toByteArray())
    }
}
