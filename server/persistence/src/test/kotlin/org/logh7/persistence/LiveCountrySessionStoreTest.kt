package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.*
import org.logh7.protocol.OriginalCharacterMessages
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class LiveCountrySessionStoreTest {
    @TempDir lateinit var directory:Path
    private suspend fun CompletableFuture<Admission>.awaitResult():Admission {
        val result=CompletableDeferred<Admission>()
        whenComplete {value,error->if(error==null)result.complete(value) else result.completeExceptionally(error)}
        return result.await()
    }
    @Test fun realActorJoinsReplayFromTheExistingJournalAndCheckpointWithoutAppearanceAliasing()=runBlocking {
        val rules=SessionRules.load()
        val c=OriginalCharacterMessages.localCandidates.first {it.character.power==2}.character
        for(checkpointEvery in listOf(1L,256L))for(country in listOf(CountryId(42),null)) {
            val folder=directory.resolve("$checkpointEvery-${country?.value ?: "free"}")
            var store=localSessionStore(folder,rules,checkpointEvery=checkpointEvery)
            var engine=WorldEngine(this,rules,ticking=false,durability=store)
            val profile=CharacterProfile(c,country,ShipStyleId("alliance"))
            try {
                assertEquals(Admission.ACCEPTED,EngineGameAdmission(engine,this).join("pilot",profile,"old",1).awaitResult())
                assertEquals(country,store.load().session.participants.getValue("pilot").country)
            } finally {engine.drainAndClose()}
            store=localSessionStore(folder,rules,checkpointEvery=checkpointEvery)
            engine=WorldEngine(this,rules,ticking=false,durability=store)
            try {
                val access=EngineGameAdmission(engine,this)
                assertFalse(store.load().session.participants.getValue("pilot").online)
                assertEquals(country,store.load().session.participants.getValue("pilot").country)
                assertEquals(Admission.IDENTITY_CHANGED,access.join("pilot",c,"display",1).awaitResult())
                assertEquals(Admission.ACCEPTED,access.join("pilot",profile,"new",1).awaitResult())
                assertEquals(country,store.load().session.participants.getValue("pilot").country)
                assertEquals("new",store.load().session.participants.getValue("pilot").connection)
            } finally {engine.drainAndClose()}
        }
    }
}
