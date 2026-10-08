package org.logh7.protocol

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

class OriginalCharacterMessagesTest {
    private fun payload(bytes: ByteArray) = ByteBuffer.wrap(bytes.copyOfRange(6, bytes.size))
    @Test fun catalogueMatchesNativeReaderIncludingVariableStringsAndEmptyCollections() {
        val candidates = OriginalCharacterMessages.localCandidates
        val input = payload(OriginalCharacterMessages.catalogue(candidates))
        assertEquals(2, input.get().toInt())
        for (candidate in candidates) {
            val character = candidate.character
            assertEquals(character.power, input.get().toInt()); assertEquals(character.power, input.get().toInt())
            assertEquals(character.rank, input.short.toInt()); assertEquals(candidate.minimumFame, input.int)
            assertEquals(character.id, input.int.toLong())
            val count = input.get().toInt()
            assertEquals(character.surname + '\u0000', String(CharArray(count) { input.char }))
            assertEquals(character.rank, input.short.toInt()); assertEquals(0, input.short.toInt())
            repeat(3) { assertEquals(0, input.get().toInt()) }
            assertEquals(character.face, input.int)
            assertEquals(character.age * CharacterMessages.AGE_SECONDS_PER_YEAR, input.int)
        }
        assertFalse(input.hasRemaining())
        val account = payload(OriginalCharacterMessages.account(entries = listOf(1001, 1002)))
        assertEquals(0, account.get().toInt()); assertEquals(0, account.int); assertEquals(0, account.get().toInt())
        assertEquals(0, account.int); assertEquals(2, account.get().toInt())
        assertEquals(1001, account.int); assertEquals(1002, account.int); assertFalse(account.hasRemaining())
    }
    @Test fun validatesApplicationLengthsDuplicatesAndCandidateRequest() {
        val ids = listOf(1001L, 1002L)
        val reply = OriginalCharacterMessages.applicationReply(ids)
        assertEquals(ids, OriginalCharacterMessages.application(reply.copyOfRange(4, reply.size)))
        assertFails { OriginalCharacterMessages.application(byteArrayOf(0x10, 6, 0)) }
        assertFails { OriginalCharacterMessages.application(byteArrayOf(0x10, 6, 6)) }
        assertFails { OriginalCharacterMessages.application(byteArrayOf(0x10, 6, 1, 0, 0, 0, 0)) }
        val duplicate = OriginalCharacterMessages.applicationReply(listOf(1001, 1001))
        assertFails { OriginalCharacterMessages.application(duplicate.copyOfRange(4, duplicate.size)) }
        assertFails { OriginalCharacterMessages.entryId(byteArrayOf(0x10, 4)) }
        val request = ByteBuffer.allocate(31).putShort(0x1200).putInt(0).putShort(0x13).array()
        assertTrue(OriginalCharacterMessages.catalogueRequest(request))
        assertFalse(OriginalCharacterMessages.catalogueRequest(request.also { it[8] = 1 }))
    }
}
