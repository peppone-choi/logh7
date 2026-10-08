package org.logh7.protocol

import java.nio.ByteBuffer

/** Account/application codecs: CD readers 00407920 and 00409b60. */
object OriginalCharacterMessages {
    data class Candidate(val character: CharacterMessages.Generate, val minimumFame: Int = 0)
    // Names occur in manual initial-card-holders.csv. All other values are local P2 placeholders.
    val localCandidates = listOf(
        Candidate(localCharacter(1001, 2, "キルヒアイス", "ジークフリード")),
        Candidate(localCharacter(1002, 3, "ヤン", "ウェンリー")),
    )
    private fun localCharacter(id: Long, power: Int, surname: String, given: String) =
        CharacterMessages.Generate(4, id, power, if (power == 2) 0 else 3, 0, surname, given,
            18, 1, 1, 1000001, List(8) { 50 }, 0, 0, 0, 20, 3, 0, "Local $id", 1)

    fun account(fame: Int = 0, entries: List<Long> = emptyList()): ByteArray {
        require(fame >= 0 && entries.size <= 5)
        val data = ByteBuffer.allocate(11 + entries.size * 4)
        data.put(0).putInt(fame).put(0) // state, fame, extension characters
        data.putInt(0).put(entries.size.toByte()) // id, entry characters
        entries.forEach { require(it in 1..0xffffffffL); data.putInt(it.toInt()) }
        return LoginMessages.sessionMessage(0x1001, data.array())
    }
    fun catalogue(candidates: List<Candidate>): ByteArray {
        require(candidates.size <= 100)
        val data = ByteBuffer.allocate(16_384).put(candidates.size.toByte())
        for (candidate in candidates) {
            val character = candidate.character
            data.put(character.power.toByte()).put(character.power.toByte()).putShort(character.rank.toShort())
                .putInt(candidate.minimumFame).putInt(character.id.toInt())
            val name = character.surname + '\u0000'
            require(name.length <= 13)
            data.put(name.length.toByte()); name.forEach(data::putChar)
            data.putShort(character.rank.toShort()).putShort(0).put(0).put(0).put(0)
                .putInt(character.face).putInt(Math.multiplyExact(character.age, CharacterMessages.AGE_SECONDS_PER_YEAR))
        }
        return LoginMessages.sessionMessage(0x120f, data.array().copyOf(data.position()))
    }
    fun catalogueRequest(bytes: ByteArray): Boolean {
        require(bytes.size == 31)
        val data = ByteBuffer.wrap(bytes)
        require(data.short.toInt() and 65535 == 0x1200)
        return data.int == 0 && data.short.toInt() == 0x13 && data.remaining() == 23 &&
            ByteArray(data.remaining()).also(data::get).all { it == 0.toByte() }
    }
    fun entryId(bytes: ByteArray): Long {
        require(bytes.size == 6 && ByteBuffer.wrap(bytes).short.toInt() and 65535 == 0x1004)
        return ByteBuffer.wrap(bytes, 2, 4).int.toLong() and 0xffffffffL
    }
    fun entryState(id: Long, eligible: Boolean, applicants: List<Long>): ByteArray {
        require(id in 1..0xffffffffL && applicants.size <= 5)
        val data = ByteBuffer.allocate(6 + applicants.size * 4).put(if (eligible) 0 else 1).putInt(id.toInt()).put(applicants.size.toByte())
        applicants.forEach { data.putInt(it.toInt()) }
        return LoginMessages.sessionMessage(0x1005, data.array())
    }
    fun application(bytes: ByteArray): List<Long> {
        require(bytes.size >= 3)
        val data = ByteBuffer.wrap(bytes)
        require(data.short.toInt() and 65535 == 0x1006)
        val count = data.get().toInt() and 255
        require(count in 1..5 && data.remaining() == count * 4)
        return List(count) { data.int.toLong() and 0xffffffffL }.also { require(it.distinct().size == it.size && it.none { id -> id == 0L }) }
    }
    fun applicationReply(ids: List<Long>): ByteArray {
        require(ids.size in 1..5)
        val data = ByteBuffer.allocate(1 + ids.size * 4).put(ids.size.toByte())
        ids.forEach { data.putInt(it.toInt()) }
        return LoginMessages.sessionMessage(0x1006, data.array())
    }
}
