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
        val actor=EngineGameAdmission(engine,this)
        val access=object:GameAdmission {
            override val generation get()=actor.generation
            override val running get()=actor.running
            override fun join(account:String,character:org.logh7.protocol.CharacterMessages.Generate,connection:String,generation:Long)=
                actor.join(account,character,connection,generation)
            override fun disconnect(account:String,connection:String)=actor.disconnect(account,connection)
        }
        val c=OriginalCharacterMessages.localCandidates.first {it.character.power==2}.character
        try {
            for(country in listOf(CountryId(1),CountryId(4),CountryId(42),null,CountryId(3))) {
                val profile=CharacterProfile(c,country,ShipStyleId("alliance"))
                assertEquals(Admission.FACTION_CHANGED,access.join("pilot",profile,"bad",1).awaitResult())
                assertEquals(0,engine.snapshot().session!!.participants)
                assertEquals(0,actor.trackedConnections())
            }
            assertEquals(Admission.ACCEPTED,access.join("pilot",CharacterProfile(c,CountryId(2),ShipStyleId("alliance")),"good",1).awaitResult())
            access.disconnect("pilot","good");yield();yield()
        } finally {engine.drainAndClose()}
    }
    @Test fun theActorAdapterKeepsActualAndUnaffiliatedMembershipInsteadOfUsingTheDisplayByte()=runBlocking {
        for(country in listOf(CountryId(42),null)) {
            var state=DurableWorldState(SessionSimulation(SessionRules.load()).exportState())
            val store=object:SessionDurability {
                override fun load()=state
                override fun commit(action:SessionAction,transition:SessionTransition) {state=transition.state}
                override fun close() {}
            }
            val engine=WorldEngine(this,ticking=false,durability=store)
            val access=EngineGameAdmission(engine,this)
            val c=OriginalCharacterMessages.localCandidates.first {it.character.power==2}.character
            val profile=CharacterProfile(c,country,ShipStyleId("alliance"))
            try {
                assertFalse(profile.canEnterNativeCountry())
                assertEquals(Admission.ACCEPTED,access.join("pilot",profile,"real",1).awaitResult())
                assertEquals(country,state.session.participants.getValue("pilot").country)
                assertEquals(Admission.IDENTITY_CHANGED,access.join("pilot",c,"forged",1).awaitResult())
                access.disconnect("pilot","forged");access.disconnect("pilot","real")
                engine.synchronizeTime()
                assertEquals(Admission.ACCEPTED,access.join("pilot",profile,"next",1).awaitResult())
                access.disconnect("pilot","real");engine.synchronizeTime()
                assertEquals(country,state.session.participants.getValue("pilot").country)
                assertEquals("next",state.session.participants.getValue("pilot").connection)
                access.disconnect("pilot","next");engine.synchronizeTime()
                assertEquals(0,access.trackedConnections())
                for(display in listOf(0,1,4,42))assertFailsWith<IllegalArgumentException> {
                    access.join("invalid",c.copy(power=display),"invalid",1)
                }
                assertFalse("invalid" in state.session.participants);assertEquals(0,access.trackedConnections())
            } finally {engine.drainAndClose()}
        }
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
