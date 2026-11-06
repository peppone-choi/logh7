package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class CountrySessionStoreTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules(24, 20, 7200, 795, GameDate(795, 1, 1), GameDate(796, 1, 1), 1000, 250, 1000)
    private val fingerprint get() = SessionCodec.fingerprint(rules)
    private fun apply(store: FileSessionStore, action: SessionAction) =
        transitionSession(rules, store.load(), action).also { store.commit(action, it) }

    // Frozen schema-1 bytes, independent of the current writer: join Empire, defect Alliance, exclude.
    private val legacyCheckpoint = "AAAAAQBAOWJlNjg4MzRmZWU5Y2M3ZDZhNTgyOGMxOTU2MWNkZTdlNDA3OThmNmMzYmM1NDdmYTRkODcwOWJmMjIwOTY2NQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val legacyEntries = listOf(
        "AAAAAQBAOWJlNjg4MzRmZWU5Y2M3ZDZhNTgyOGMxOTU2MWNkZTdlNDA3OThmNmMzYmM1NDdmYTRkODcwOWJmMjIwOTY2NQAAAAAAAAABAgAFcGlsb3QABkVNUElSRQABAANvbGQBAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEABXBpbG90AAZFTVBJUkUAAQEAA29sZAAAAAAAAAAAAAEACEFDQ0VQVEVEAAAAAAA=",
        "AAAAAQBAOWJlNjg4MzRmZWU5Y2M3ZDZhNTgyOGMxOTU2MWNkZTdlNDA3OThmNmMzYmM1NDdmYTRkODcwOWJmMjIwOTY2NQAAAAAAAAACCgAFcGlsb3QACEFMTElBTkNFAAAAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAVwaWxvdAAIQUxMSUFOQ0UAAQEAA29sZAAAAAAAAAAAAAAAAAAAAA==",
        "AAAAAQBAOWJlNjg4MzRmZWU5Y2M3ZDZhNTgyOGMxOTU2MWNkZTdlNDA3OThmNmMzYmM1NDdmYTRkODcwOWJmMjIwOTY2NQAAAAAAAAADBAAFcGlsb3QAAAAAAAAAAAAAAAAAAAAAAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAVwaWxvdAAIQUxMSUFOQ0UAAAAAAAAAAAAAAA==")

    @Test fun legacyFramesStayCanonicalAndReplayAlongsideNewCountryAndUnaffiliatedEntries() {
        val checkpoint = Base64.getDecoder().decode(legacyCheckpoint)
        val frames = legacyEntries.map { Base64.getDecoder().decode(it) }
        assertContentEquals(checkpoint, SessionCodec.checkpoint(fingerprint, SessionCodec.readCheckpoint(fingerprint, checkpoint)))
        frames.forEach { assertContentEquals(it, SessionCodec.entry(fingerprint, SessionCodec.readEntry(fingerprint, it))) }
        Files.write(directory.resolve("session.checkpoint"), FileSessionStore.frame(checkpoint))
        Files.write(directory.resolve("session.journal"), frames.fold(ByteArray(0)) { bytes, frame -> bytes + FileSessionStore.frame(frame) })
        val expected: DurableWorldState
        localSessionStore(directory, rules).use { store ->
            assertEquals(CountryId(3), store.load().session.excluded.getValue("pilot"))
            assertEquals(Admission.FACTION_CHANGED, apply(store, SessionAction.Join("pilot", CountryId(42), false, "new", 1)).admission)
            assertEquals(Admission.ACCEPTED, apply(store, SessionAction.Join("pilot", Power.ALLIANCE, false, "new", 1)).admission)
            apply(store, SessionAction.Join("other", CountryId(42), false, "other", 1))
            apply(store, SessionAction.Join("free", null, false, "free", 1))
            apply(store, SessionAction.Exclude("free"))
            assertEquals(Admission.ORIGINAL_RETURN_FORBIDDEN, apply(store, SessionAction.Join("free", null, true, "free", 1)).admission)
            expected = store.load()
            store.checkpoint()
        }
        val mixed = FileSessionStore.readFrames(directory.resolve("session.journal"))
        assertEquals(listOf(1, 1, 1), mixed.take(3).map { ByteBuffer.wrap(it).int })
        assertTrue(mixed.drop(3).all { ByteBuffer.wrap(it).int == 3 })
        frames.indices.forEach { assertContentEquals(frames[it], mixed[it]) }
        localSessionStore(directory, rules).use { store ->
            assertEquals(expected, store.load())
            assertEquals(CountryId(42), store.load().session.participants.getValue("other").country)
            assertTrue("free" in store.load().session.excluded)
            assertNull(store.load().session.excluded.getValue("free"))
            assertEquals(Admission.ACCEPTED, apply(store, SessionAction.Join("free", null, false, "free", 1)).admission)
        }
    }

    @Test fun worldsKeepTheSameAccountInDifferentCountriesWithoutSharingMembership() {
        val first = directory.resolve("first")
        val second = directory.resolve("second")
        localSessionStore(first, rules).use { a -> localSessionStore(second, rules).use { b ->
            apply(a, SessionAction.Join("same-account", CountryId(42), false, "a", 1))
            apply(b, SessionAction.Join("same-account", CountryId(43), false, "b", 1))
            apply(a, SessionAction.Exclude("same-account"))
            assertEquals(CountryId(43), b.load().session.participants.getValue("same-account").country)
        } }
        localSessionStore(first, rules).use { assertEquals(CountryId(42), it.load().session.excluded.getValue("same-account")) }
        localSessionStore(second, rules).use { assertEquals(CountryId(43), it.load().session.participants.getValue("same-account").country) }
    }

    @Test fun invalidCountryIdIsRejectedOnReadAndNewMembershipCannotBeWrittenAsLegacyPower() {
        val genesis = DurableWorldState(SessionSimulation(rules).exportState())
        val action = SessionAction.Join("pilot", CountryId(0x12345678), false, "new", 1)
        val entry = SessionCodec.Entry(1, action, transitionSession(rules, genesis, action))
        assertFailsWith<IllegalStateException> { SessionCodec.entry(fingerprint, entry.copy(schema = 1)) }
        val bytes = SessionCodec.entry(fingerprint, entry)
        val marker = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val offset = (0..bytes.size - 4).first { bytes.copyOfRange(it, it + 4).contentEquals(marker) }
        ByteBuffer.wrap(bytes).putInt(offset, 0)
        assertFailsWith<IllegalArgumentException> { SessionCodec.readEntry(fingerprint, bytes) }
    }
}
