package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** CD 0042a420: dedicated public profile, not a 0323 character/cache record. Author: 최병호. */
object CharacterInformationMessages {
    data class ChargedBase(val card: Int, val base: WorldMessages.Base)
    data class Profile(val character: CharacterMessages.Generate, val grid: Int, val base: Int,
        val spot: Int, val owner: Int, val cards: List<Int>,
        val outfit: LogisticsDirectoryMessages.Outfit? = null, val charged: List<ChargedBase> = emptyList())

    fun query(message: ByteArray): Int {
        require(message.size == 6)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0354)
        return input.int.also { require(it > 0) }
    }

    /** A denied or absent target has no personal fields. Keep the ID for reply correlation. */
    fun response(id: Int, profile: Profile?): ByteArray {
        require(id > 0)
        val c = profile?.character
        require(c == null || c.id == id.toLong())
        require(profile == null || (profile.cards.size <= 16 && profile.cards.all { it in 0..260 } &&
            profile.charged.size <= 5 && profile.charged.all { it.card in profile.cards } &&
            profile.charged.distinctBy { it.card to it.base.id }.size == profile.charged.size &&
            profile.grid in 0..65535 && profile.base in 0..65535 && profile.spot >= 0 && profile.owner >= 0))
        require(c == null || (c.abilities.size == 8 && c.abilities.all { it in 0..255 } &&
            c.power in 2..3 && c.gender in 0..255 && c.rank in 0..255 && c.face >= 0 &&
            c.age >= 0 && c.birthMonth in 1..12 && c.birthDay in 1..31))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun string(value: String) {
                require(value.length <= 12 && value.none { it == '\u0000' || it.isSurrogate() })
                out.writeByte(if (value.isEmpty()) 0 else value.length + 1)
                if (value.isNotEmpty()) (value + '\u0000').forEach { out.writeChar(it.code) }
            }
            out.writeInt(id); string(c?.surname.orEmpty())
            out.writeByte(c?.power ?: 0); out.writeByte(0); out.writeByte(0); out.writeByte(c?.gender ?: 0)
            out.writeInt(c?.let { Math.multiplyExact(it.age, CharacterMessages.AGE_SECONDS_PER_YEAR) } ?: 0)
            out.writeByte(c?.birthMonth ?: 0); out.writeByte(c?.birthDay ?: 0)
            out.writeInt(if (c == null) 0 else Math.addExact(id, 1_000_000)); string(c?.shipName.orEmpty())
            out.writeInt(profile?.grid ?: 0); out.writeInt(profile?.base ?: 0)
            out.writeInt(profile?.spot ?: 0); out.writeInt(profile?.owner ?: 0)
            out.writeInt(0) // Evaluation: same current server baseline as 0323.
            (c?.abilities ?: List(8) { 0 }).forEach(out::writeByte)
            out.writeByte(0); out.writeByte(if (c == null) 0 else 100) // Influence, stamina (0323 baseline).
            out.writeShort(c?.rank ?: 255); string("")
            out.writeInt(c?.face ?: 0); out.writeInt(0) // Achievement (0323 baseline).
            out.writeByte(profile?.cards?.size ?: 0); profile?.cards?.forEach(out::writeShort)
            val outfit = profile?.outfit
            out.writeByte(if (outfit == null) 0 else 1)
            if (outfit != null) {
                require(outfit.id > 0 && outfit.number in 0..255 && outfit.kind in 0..3)
                out.writeInt(outfit.id); out.writeByte(outfit.number); out.writeByte(outfit.kind)
                out.write(WorldMessages.simpleBases(listOf(outfit.base)))
            }
            out.writeByte(profile?.charged?.size ?: 0)
            profile?.charged?.forEach { charged ->
                out.writeShort(charged.card)
                // Each charged entry embeds one base directly, without a second count.
                out.write(WorldMessages.simpleBases(listOf(charged.base)).drop(1).toByteArray())
            }
        }
        return LoginMessages.sessionMessage(0x0355, bytes.toByteArray(), if (profile == null) 1 else 0)
    }
}
