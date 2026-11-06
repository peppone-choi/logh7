package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.*

class LiveCountryJoinTest {
    @Test fun liveJoinsWithoutAStoreAlsoKeepActualMembership()=runBlocking {
        for(country in listOf(CountryId(42),null)) {
            val engine=WorldEngine(this,ticking=false)
            suspend fun join(id:CountryId?,connection:String):Admission {
                val result=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",id,false,result,connection,1))
                return result.await()
            }
            try {
                assertEquals(Admission.ACCEPTED,join(country,"first"))
                assertEquals(Admission.IDENTITY_CHANGED,join(CountryId(2),"display"))
                engine.submit(WorldCommand.Disconnect("pilot","first"));engine.synchronizeTime()
                assertEquals(Admission.ACCEPTED,join(country,"next"))
                assertEquals(1,engine.snapshot().session!!.online)
            } finally {engine.drainAndClose()}
        }
    }

    @Test fun liveAndJournaledJoinsKeepTheActualCountryThroughRecoveryAndRejectTheDisplayCountry()=runBlocking {
        for(country in listOf(CountryId(42),null)) {
            var state=DurableWorldState(SessionSimulation(SessionRules.load()).exportState())
            val actions=mutableListOf<SessionAction>()
            fun store()=object:SessionDurability {
                override fun load()=state
                override fun commit(action:SessionAction,transition:SessionTransition) {actions+=action;state=transition.state}
                override fun close() {}
            }
            var engine=WorldEngine(this,ticking=false,durability=store())
            suspend fun join(id:CountryId?,connection:String):Admission {
                val result=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",id,false,result,connection,1))
                return result.await()
            }
            try {
                assertEquals(Admission.ACCEPTED,join(country,"first"))
                assertEquals(country,state.session.participants.getValue("pilot").country)
                assertEquals(country,(actions.last() as SessionAction.Join).country)
                assertEquals(Admission.IDENTITY_CHANGED,join(CountryId(2),"forged"))
                assertEquals("first",state.session.participants.getValue("pilot").connection)
            } finally {engine.drainAndClose()}
            engine=WorldEngine(this,ticking=false,durability=store())
            try {
                assertEquals(country,state.session.participants.getValue("pilot").country)
                assertFalse(state.session.participants.getValue("pilot").online)
                assertEquals(Admission.ACCEPTED,join(country,"restored"))
                assertEquals(country,state.session.participants.getValue("pilot").country)
                assertEquals(1,engine.snapshot().session!!.online)
            } finally {engine.drainAndClose()}
        }
    }

    @Test fun aFailedDurableJoinNeverPublishesOrReplacesMembership()=runBlocking {
        var state=DurableWorldState(SessionSimulation(SessionRules.load()).exportState())
        var fail=true
        val store=object:SessionDurability {
            override fun load()=state
            override fun commit(action:SessionAction,transition:SessionTransition) {
                if(action is SessionAction.Join && fail)throw IOException("owned fixture")
                state=transition.state
            }
            override fun close() {}
        }
        var engine=WorldEngine(this,ticking=false,durability=store)
        try {
            val failed=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",CountryId(42),false,failed,"c",1))
            assertFailsWith<IOException> {failed.await()}
            assertTrue(state.session.participants.isEmpty());assertEquals(0,engine.snapshot().session!!.participants)
            val retry=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",CountryId(42),false,retry,"c",1))
            assertFailsWith<IOException> {retry.await()}
            assertTrue(state.session.participants.isEmpty())
            engine.drainAndClose();fail=false
            engine=WorldEngine(this,ticking=false,durability=store)
            val accepted=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",CountryId(42),false,accepted,"c",1))
            assertEquals(Admission.ACCEPTED,accepted.await())
            assertEquals(CountryId(42),state.session.participants.getValue("pilot").country)
        } finally {engine.drainAndClose()}
    }
}
