package org.logh7.protocol

import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class GoldenVectorTest {
    private fun load(name: String) = Json.parseToJsonElement(javaClass.getResourceAsStream("/vectors/$name.json")!!.bufferedReader().use { it.readText() }).jsonObject
    private fun JsonObject.hex(name: String) = java.util.HexFormat.of().parseHex(getValue(name).jsonPrimitive.content)
    @Test fun independentCipherVectors() {
        val vectors = load("mps-blowfish").getValue("vectors").jsonArray
        assertTrue(vectors.size >= 9)
        vectors.forEach { item ->
            val v = item.jsonObject; val cipher = LegacyBlowfish(v.hex("key_hex")); val plain = v.hex("plaintext_hex")
            assertContentEquals(v.hex("ciphertext_hex"), cipher.encrypt(plain))
            assertContentEquals(plain.copyOf((plain.size + 7) / 8 * 8), cipher.decrypt(v.hex("ciphertext_hex")))
        }
    }
    @Test fun independentEnvelopeVectors() {
        val doc = load("mps-envelope"); val cipher = LegacyBlowfish(doc.hex("key_hex"))
        doc.getValue("vectors").jsonArray.forEach { item ->
            val v = item.jsonObject; val sequence = v.getValue("sequence_hex").jsonPrimitive.content.toLong(16)
            val bytes = Envelope.encode(sequence, v.hex("body_hex"))
            assertContentEquals(v.hex("ciphertext_hex"), cipher.encrypt(bytes))
            assertContentEquals(v.hex("body_hex"), Envelope.decode(bytes, -1).second)
        }
    }
    @Test fun independentKeyExchangeFrames() {
        val doc = load("mps-kex"); val cipher = LegacyBlowfish(doc.hex("wrapping_key_hex"))
        doc.getValue("vectors").jsonArray.forEach { item ->
            val v = item.jsonObject; val type = v.getValue("frame_type_hex").jsonPrimitive.content.toInt(16)
            assertContentEquals(v.hex("socket_frame_hex"), Frames.encode(type, cipher.encrypt(v.hex("plaintext_hex"))))
        }
    }
}
