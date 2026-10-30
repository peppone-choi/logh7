package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class MessengerTest {
    private val a=Messenger.Endpoint("alpha",1,"a")
    private val b=Messenger.Endpoint("beta",2,"b")
    private val c=Messenger.Endpoint("gamma",3,"c")
    private val d=Messenger.Endpoint("delta",4,"d")
    private fun service()=Messenger(7).also {m->listOf(a,b,c,d).forEach {m.online(it)}}
    @Test fun callRequiresRecipientAcceptanceBeforeBothCanTalk() {
        val m=service()
        assertEquals(listOf(Messenger.Event.Calling(a,b)),m.call(a,2))
        assertEquals(listOf(a),m.callerList(b));assertNull(m.partner(a))
        assertFails {m.send(a,"연결 전")}
        assertEquals(listOf(Messenger.Event.Connected(a,b)),m.accept(b,1))
        assertEquals(b,m.partner(a));assertEquals(a,m.partner(b))
        assertTrue(m.callerList(b).isEmpty())
        assertEquals(Messenger.Event.Message(a,b,"한글 ABC 123·끝\r\n둘째 줄"),m.send(a,"한글 ABC 123·끝\r\n둘째 줄"))
        assertEquals(a,m.send(b,"답장").to)
    }
    @Test fun newCallCancelsOnlyCallersPreviousCall() {
        val m=service();m.call(a,2);m.call(d,2)
        assertEquals(listOf(Messenger.Event.Cancelled(a,b,Messenger.Reason.REPLACED),Messenger.Event.Calling(a,c)),m.call(a,3))
        assertEquals(listOf(d),m.callerList(b));assertEquals(listOf(a),m.callerList(c))
        assertTrue(m.call(a,3).isEmpty());assertFails {m.accept(b,1)}
    }
    @Test fun callingWhileConnectedBreaksOldConversationImmediately() {
        val m=service();m.call(a,2);m.accept(b,1)
        assertEquals(listOf(Messenger.Event.Disconnected(a,b,Messenger.Reason.REPLACED),Messenger.Event.Calling(a,c)),m.call(a,3))
        assertNull(m.partner(b));assertFails {m.send(b,"늦은 송신")}
        assertNull(m.partner(c));m.accept(c,1);assertEquals(c,m.partner(a))
    }
    @Test fun choosingCallerCancelsOtherCallsToBothEndpoints() {
        val m=service();m.call(a,2);m.call(c,2);m.call(d,1);m.call(b,3)
        val events=m.accept(b,1)
        assertEquals(setOf(Messenger.Event.Cancelled(c,b,Messenger.Reason.CONNECTED),
            Messenger.Event.Cancelled(d,a,Messenger.Reason.CONNECTED),
            Messenger.Event.Cancelled(b,c,Messenger.Reason.CONNECTED),Messenger.Event.Connected(a,b)),events.toSet())
        listOf(a,b,c,d).forEach {assertTrue(m.callerList(it).isEmpty())}
        assertFails {m.accept(b,3)};assertFails {m.accept(a,4)}
    }
    @Test fun acceptingIncomingCallReplacesRecipientsOldConversation() {
        val m=service();m.call(b,3);m.accept(c,2);m.call(a,2)
        assertEquals(listOf(Messenger.Event.Disconnected(b,c,Messenger.Reason.REPLACED),
            Messenger.Event.Connected(a,b)),m.accept(b,1))
        assertNull(m.partner(c));assertEquals(a,m.partner(b))
    }
    @Test fun explicitDisconnectNotifiesPeerAndIsIdempotent() {
        val m=service();m.call(a,2);m.accept(b,1)
        assertEquals(listOf(Messenger.Event.Disconnected(b,a,Messenger.Reason.DISCONNECTED)),m.disconnect(b))
        assertTrue(m.disconnect(b).isEmpty());assertNull(m.partner(a));assertFails {m.send(a,"종료 후")}
        m.call(a,2)
        assertEquals(listOf(Messenger.Event.Cancelled(a,b,Messenger.Reason.DISCONNECTED)),m.disconnect(a))
    }
    @Test fun logoutCancelsIncomingAndOutgoingCalls() {
        val m=service();m.call(a,2);m.call(b,3);m.call(d,2)
        val events=m.offline(b)
        assertEquals(3,events.size);assertTrue(events.all {it is Messenger.Event.Cancelled && it.reason==Messenger.Reason.OFFLINE})
        assertTrue(m.callerList(a).isEmpty());assertTrue(m.callerList(c).isEmpty())
        assertTrue(m.offline(b).isEmpty());assertFails {m.call(a,2)}
    }
    @Test fun reconnectDropsOldPairAndLateOldDisconnectCannotRemoveNewLease() {
        val m=service();m.call(a,2);m.accept(b,1)
        val next=a.copy(connection="a-new")
        assertEquals(listOf(Messenger.Event.Disconnected(a,b,Messenger.Reason.OFFLINE)),m.online(next))
        assertTrue(m.offline(a).isEmpty());assertTrue(next in m.directory(b))
        assertFails {m.send(a,"옛 연결")};assertFails {m.call(a,2)}
        assertNull(m.partner(next));assertFails {m.send(next,"연결은 복원되지 않음")}
    }
    @Test fun anotherCharacterOnSameAccountCannotKeepOldSession() {
        val m=service();m.call(a,2)
        val next=Messenger.Endpoint("alpha",5,"a-new")
        assertEquals(1,m.online(next).size)
        assertFalse(a in m.directory(b));assertTrue(next in m.directory(b))
        assertFails {m.call(a,2)};assertTrue(m.callerList(b).isEmpty())
    }
    @Test fun selfOfflineAndUnsolicitedAcceptanceDoNotDisturbCurrentConversation() {
        val m=service();m.call(a,2);m.accept(b,1)
        assertFails {m.call(a,1)};assertFails {m.call(a,99)};assertFails {m.accept(c,1)}
        assertEquals(b,m.partner(a));assertEquals(a,m.partner(b))
    }
    @Test fun generationChangeRejectsEveryOldOperation() {
        var generation=7L;val m=Messenger(7,{generation});m.online(a);m.online(b);m.call(a,2)
        generation=8
        assertFails {m.accept(b,1)};assertFails {m.call(a,2)};assertFails {m.send(a,"옛 세대")}
        assertFails {m.online(c)};assertFails {m.offline(a)};assertFails {m.directory(a)};assertFails {m.disconnect(a)}
    }
    @Test fun textBoundaryAndInvalidTextKeepConnectionUsable() {
        val m=service();m.call(a,2);m.accept(b,1)
        assertEquals(511,m.send(a,"힣".repeat(511)).text.length)
        for(text in listOf("", " \t\r\n", "힣".repeat(512), "bad\u0000", "bad\u001b", "bad\u007f", "\ud83d\ude00", "\ud800"))
            assertFails {m.send(a,text)}
        assertEquals(b,m.send(a,"다시 정상 송신").to)
    }
    @Test fun directoryAndCapacityAreBoundedWithoutEvictingConnectedUsers() {
        val m=Messenger(7,capacity=2);m.online(a);m.online(b);m.call(a,2);m.accept(b,1)
        assertFails {m.online(c)};assertEquals(listOf(b),m.directory(a));assertEquals(b,m.partner(a))
        assertTrue(m.online(a).isEmpty());assertFails {m.online(c.copy(connection="a"))}
        m.offline(b);m.online(c);assertEquals(listOf(c),m.directory(a))
    }
    @Test fun simultaneousAcceptanceHasOneWinnerAndNoThreeWayConnection() {
        val m=service();m.call(a,2);m.call(c,2)
        val start=java.util.concurrent.CountDownLatch(1)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results=listOf(1,3).map {caller->pool.submit<Boolean> {
                start.await();runCatching {m.accept(b,caller)}.isSuccess
            }}
            start.countDown()
            assertEquals(1,results.count {it.get(5,java.util.concurrent.TimeUnit.SECONDS)})
            val winner=assertNotNull(m.partner(b));assertEquals(b,m.partner(winner))
            val loser=if(winner==a)c else a
            assertNull(m.partner(loser));assertTrue(m.callerList(b).isEmpty())
        } finally {pool.shutdownNow()}
    }
    @Test fun newServerHasNoRestoredOnlineLeaseOrPrivateConversation() {
        val old=service();old.call(a,2);old.accept(b,1);old.send(a,"보관하지 않는 대화")
        val next=Messenger(7);next.online(a);next.online(b)
        assertNull(next.partner(a));assertTrue(next.callerList(b).isEmpty())
        assertFails {next.send(a,"재연결 전 송신")}
    }
    @Test fun targetedLateCancelDoesNotBreakNewPartnerAndRecipientCanDecline() {
        val m=service();m.call(a,2)
        assertEquals(listOf(Messenger.Event.Cancelled(a,b,Messenger.Reason.DISCONNECTED)),m.dismiss(b,1))
        assertFails {m.accept(b,1)}
        m.call(a,3);m.accept(c,1)
        assertTrue(m.dismiss(a,2).isEmpty());assertEquals(c,m.partner(a))
        assertEquals(1,m.dismiss(c,1).size);assertNull(m.partner(a))
    }
}
