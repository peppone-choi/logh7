package org.logh7.gateway

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.OriginalCharacterMessages
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class GameAdmissionTest {
    @Test fun explicitForeignOrUnattachedMembershipIsNeverAdmittedAsItsDisplayFaction()=runBlocking {
        val engine=WorldEngine(this,ticking=false)
        val access=EngineGameAdmission(engine,this)
        val c=OriginalCharacterMessages.localCandidates.first {it.character.power==2}.character
        try {
            for(country in listOf(CountryId(1),CountryId(4),CountryId(42),null,CountryId(3))) {
                val profile=CharacterProfile(c,country,ShipStyleId("alliance"))
                assertEquals(Admission.FACTION_CHANGED,access.join("pilot",profile,"bad",1).awaitResult())
                assertEquals(0,engine.snapshot().session!!.participants)
                assertEquals(0,access.trackedConnections())
            }
            assertEquals(Admission.ACCEPTED,access.join("pilot",CharacterProfile(c,CountryId(2),ShipStyleId("alliance")),"good",1).awaitResult())
            access.disconnect("pilot","good");yield();yield()
        } finally {engine.drainAndClose()}
    }
    private suspend fun CompletableFuture<Admission>.awaitResult(): Admission {
        val result = CompletableDeferred<Admission>()
        whenComplete { value, error -> if (error == null) result.complete(value) else result.completeExceptionally(error) }
        return result.await()
    }
    @Test fun authenticatedIdentityIsAdmittedAndSocketCloseKeepsOfflineSlot() = runBlocking {
        val engine = WorldEngine(this, SessionRules.load().copy(capacity = 1), ticking = false)
        val access = EngineGameAdmission(engine, this)
        val character = OriginalCharacterMessages.localCandidates.first().character
        assertEquals(Admission.ACCEPTED, access.join("owner", character, "first", 1).awaitResult())
        assertEquals(1, engine.snapshot().session!!.online)
        assertEquals(Admission.ALREADY_ONLINE, access.join("owner", character, "duplicate", 1).awaitResult())
        assertEquals(Admission.FULL, access.join("other", character, "other", 1).awaitResult())
        access.disconnect("owner", "first")
        yield(); yield()
        assertEquals(Admission.ACCEPTED, access.join("owner", character, "reconnect", 1).awaitResult())
        access.disconnect("owner", "duplicate") // rejected old connection must not disconnect the new one
        yield(); yield()
        assertEquals(1, engine.snapshot().session!!.online)
        access.disconnect("owner", "reconnect"); access.disconnect("other", "other")
        yield(); yield()
        engine.drainAndClose()
        assertEquals(1, engine.snapshot().session!!.participants)
        assertEquals(0, engine.snapshot().session!!.online)
    }
    @Test fun socketCloseBeforeAdmissionCompletesDoesNotLeaveGhostAndStaleGenerationIsRejected() = runBlocking {
        val engine = WorldEngine(this, ticking = false)
        val access = EngineGameAdmission(engine, this)
        val character = OriginalCharacterMessages.localCandidates.first().character
        val pending = access.join("early", character, "early", 1)
        access.disconnect("early", "early")
        assertEquals(Admission.ACCEPTED, pending.awaitResult())
        yield(); yield()
        assertEquals(0, engine.snapshot().session!!.online)
        assertEquals(Admission.STALE_SESSION, access.join("old", character, "old", 0).awaitResult())
        access.disconnect("old", "old"); yield(); yield()
        engine.drainAndClose()
    }
}
