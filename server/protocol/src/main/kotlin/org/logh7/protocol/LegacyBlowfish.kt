package org.logh7.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** evidence:client, protocol-draft §3. This is a compatibility cipher, not a new security design. */
class LegacyBlowfish(key: ByteArray) {
    private val p: IntArray
    private val s: IntArray
    init {
        require(key.size in 1..56)
        val words = javaClass.getResourceAsStream("/blowfish-pi.hex")!!.bufferedReader().use { it.readText() }.trim().chunked(8).map { hex ->
            val n = hex.toLong(16)
            var value = 0
            for (shift in 0..24 step 8) value = value or (((((n ushr shift).toInt() and 255) + 1) and 255) shl shift)
            value
        }.toIntArray()
        require(words.size == 1042)
        p = words.copyOfRange(0, 18); s = words.copyOfRange(18, words.size)
        var index = 0
        for (i in p.indices) {
            var word = 0
            repeat(4) { word = (word shl 8) or (key[index].toInt() and 255); index = (index + 1) % key.size }
            p[i] = p[i] xor word
        }
        var block = 0 to 0
        for (i in p.indices step 2) { block = enc(block.first, block.second); p[i] = block.first; p[i + 1] = block.second }
        for (i in s.indices step 2) { block = enc(block.first, block.second); s[i] = block.first; s[i + 1] = block.second }
    }
    private fun f(x: Int): Int = ((s[x ushr 24] + s[256 + ((x ushr 16) and 255)]) xor s[512 + ((x ushr 8) and 255)]) + s[768 + (x and 255)]
    private fun enc(left: Int, right: Int): Pair<Int, Int> {
        var l = left; var r = right
        for (i in 0..15) { l = l xor p[i]; r = r xor f(l); val t = l; l = r; r = t }
        val t = l; l = r; r = t
        return (l xor p[17]) to (r xor p[16])
    }
    private fun dec(left: Int, right: Int): Pair<Int, Int> {
        var l = left; var r = right
        for (i in 17 downTo 2) { l = l xor p[i]; r = r xor f(l); val t = l; l = r; r = t }
        val t = l; l = r; r = t
        return (l xor p[0]) to (r xor p[1])
    }
    fun encrypt(plain: ByteArray): ByteArray = transform(plain.copyOf(((plain.size + 7) / 8) * 8), true)
    fun decrypt(cipher: ByteArray): ByteArray { require(cipher.size % 8 == 0); return transform(cipher, false) }
    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) {
            val l = input.int; val r = input.int; val pair = if (encrypt) enc(l, r) else dec(l, r)
            output.putInt(pair.first).putInt(pair.second)
        }
        return output.array()
    }
    companion object {
        const val HANDSHAKE_KEY = "{A4C13748-0159-4c54-AEB3-1D68575761B3}"
        fun maskStoredKey(key: ByteArray): ByteArray = key.map { (it.toInt() xor 0x17).toByte() }.toByteArray()
    }
}
