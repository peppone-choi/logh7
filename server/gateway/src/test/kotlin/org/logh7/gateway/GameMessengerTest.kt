package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.MailMessages
import org.logh7.protocol.MessengerMessages as Wire
import kotlin.test.*

class GameMessengerTest {
    private val a=MailIdentity("alpha",10000,2,"송신 한글",face=1000001,ageSeconds=30*31_536_000)
    private val b=MailIdentity("beta",10001,3,"수신 한글",face=1000002,ageSeconds=35*31_536_000)
    private val c=MailIdentity("gamma",10002,2,"세번째")
    private fun peer(p:MailIdentity)=p.communicationPeer()
    private fun body(packet:ByteArray)=packet.copyOfRange(4,packet.size)
    private class Fixture {
        val presence=GamePresence()
        val received=mutableMapOf<Int,MutableList<ByteArray>>()
        val people=mutableMapOf<Int,MailIdentity>()
        fun add(p:MailIdentity,connection:String=p.account) {
            people[p.character]=p
            val character=CharacterMessages.Generate(4,p.character.toLong(),p.power,0,0,p.name,"Pilot",30,1,1,1000001,
                List(8){50},0,0,0,20,0,0,"Ship",1)
            presence.update(GamePresence.Member(connection,character,
                Navigator(7,p.character,if(p.power==2)Power.EMPIRE else Power.ALLIANCE,2487,1000,1000,1000),
                {received.getOrPut(p.character){mutableListOf()}.addAll(it)}))
        }
        fun service()=GameCommunication(presence,{7},{_,id->people[id]},{false},{object:CommunicationStore {
            override fun load():CommunicationState?=null
            override fun save(state:CommunicationState)=error("Messenger must not write a mailbox")
        }})
        fun clear()=received.clear()
    }
    private fun call(service:GameCommunication,from:MailIdentity,to:MailIdentity)=service.handle(
        from.account,from,7,body(Wire.link(Wire.Link(false,peer(from),peer(to),1,0))),0)
    private fun enable(service:GameCommunication,vararg people:MailIdentity) {
        for(p in people)service.handle(p.account,p,7,body(Wire.status(Wire.Status(peer(p),0))),0)
    }
    private fun accept(service:GameCommunication,from:MailIdentity,to:MailIdentity)=service.handle(
        to.account,to,7,body(Wire.link(Wire.Link(false,peer(from),peer(to),3,1))),0)
    @Test fun realWireDirectoryCallConsentKoreanEchoAndPeerDelivery() {
        val f=Fixture();f.add(a);f.add(b);f.clear()
        f.service().use {service->
            enable(service,a,b);f.clear()
            val directory=Wire.directory(body(service.handle(a.account,a,7,byteArrayOf(0x0f,6),0).single()))
            assertEquals(listOf(a.character,b.character),directory.map {it.peer.id})
            assertEquals(b.name,directory.single {it.peer.id==b.character}.peer.name)
            assertEquals(listOf(b.face,b.ageSeconds,0),directory.single {it.peer.id==b.character}.peer.extra)
            call(service,a,b)
            assertEquals(2,Wire.link(body(f.received.getValue(b.character).last())).phase)
            f.clear();accept(service,a,b)
            assertEquals(4,Wire.link(body(f.received.getValue(a.character).last())).phase)
            assertEquals(1,Wire.link(body(f.received.getValue(b.character).last())).result)
            f.clear()
            val text="한글 ABC 123·끝\r\n둘째 줄"
            service.handle(a.account,a,7,body(Wire.text(Wire.Text(0,peer(a).copy(name="위조 표시"),text))),0)
            for(owner in listOf(a,b)) {
                val message=Wire.text(body(f.received.getValue(owner.character).single()))
                assertEquals(a.name,message.peer.name);assertEquals(a.character,message.peer.id);assertEquals(text,message.text)
                assertEquals(listOf(a.face,a.ageSeconds,0),message.peer.extra)
            }
        }
    }
    @Test fun noConsentAndForgedSenderRejectWithoutDroppingExistingConnection() {
        val f=Fixture();listOf(a,b,c).forEach {f.add(it)};f.clear()
        f.service().use {service->
            enable(service,a,b);f.clear()
            val request=body(Wire.text(Wire.Text(0,peer(a),"미수락")))
            assertEquals(0x0500,java.nio.ByteBuffer.wrap(service.handle(a.account,a,7,request,0).single()).getShort(4).toInt())
            call(service,a,b);accept(service,a,b);f.clear()
            assertFails {service.handle(a.account,a,7,body(Wire.text(Wire.Text(0,peer(c),"타인으로 위조"))),0)}
            assertTrue(f.received.isEmpty())
            service.handle(a.account,a,7,body(Wire.text(Wire.Text(0,peer(a),"정상"))),0)
            assertEquals("정상",Wire.text(body(f.received.getValue(b.character).single())).text)
        }
    }
    @Test fun disconnectAndReconnectDoNotRestoreConversationOrAcceptOldConnection() {
        val f=Fixture();f.add(a);f.add(b)
        f.service().use {service->
            enable(service,a,b);f.clear()
            call(service,a,b);accept(service,a,b);f.clear()
            f.presence.remove(b.account);service.disconnected(b.account)
            assertTrue(Wire.link(body(f.received.getValue(a.character).first())).close)
            f.add(b,"b-new");f.clear()
            assertFails {service.handle(b.account,b,7,byteArrayOf(0x0f,6),0)}
            service.handle("b-new",b,7,byteArrayOf(0x0f,6),0)
            assertEquals(1,service.handle(a.account,a,7,body(Wire.text(Wire.Text(0,peer(a),"재수락 전"))),0).size)
            assertTrue(f.received.isEmpty())
        }
    }
    @Test fun spoofedCallerAndRecipientCannotChangeOtherPeoplesConnection() {
        val f=Fixture();listOf(a,b,c).forEach {f.add(it)}
        f.service().use {service->
            enable(service,a,b);f.clear()
            call(service,a,b);accept(service,a,b);f.clear()
            assertFails {service.handle(c.account,c,7,body(Wire.link(Wire.Link(false,peer(a),peer(b),1,0))),0)}
            assertFails {service.handle(c.account,c,7,body(Wire.link(Wire.Link(false,peer(a),peer(b),3,1))),0)}
            assertFails {service.handle(c.account,c,7,body(Wire.link(Wire.Link(true,peer(a),peer(b),0,0))),0)}
            service.handle(a.account,a,7,body(Wire.text(Wire.Text(0,peer(a),"계속 연결"))),0)
            assertEquals("계속 연결",Wire.text(body(f.received.getValue(b.character).single())).text)
        }
    }
    @Test fun recipientDisabledStatusCancelsCallsAndCanBeEnabledAgain() {
        val f=Fixture();listOf(a,b,c).forEach {f.add(it)}
        f.service().use {service->
            enable(service,a,b);f.clear()
            enable(service,c);call(service,a,b);call(service,c,b);f.clear()
            service.handle(b.account,b,7,body(Wire.status(Wire.Status(peer(b),2))),0)
            val rows=Wire.directory(body(service.handle(a.account,a,7,byteArrayOf(0x0f,6),0).single()))
            assertEquals(2,rows.single {it.peer.id==b.character}.status)
            f.clear();assertEquals(1,call(service,a,b).size)
            assertTrue(f.received.isEmpty())
            assertEquals(1,accept(service,a,b).size) // Disabled state cannot accept an old call.
            service.handle(b.account,b,7,body(Wire.status(Wire.Status(peer(b),0))),0)
            f.clear();call(service,a,b);accept(service,a,b)
            assertEquals(4,Wire.link(body(f.received.getValue(a.character).last())).phase)
        }
    }
    @Test fun nativeZeroPeerStatusUsesAuthenticatedOwnerAndDoesNotAckOthers() {
        val f=Fixture();f.add(a);f.add(b);f.clear()
        f.service().use {service->
            val before=Wire.directory(body(service.handle(a.account,a,7,byteArrayOf(0x0f,6),0).single()))
            assertTrue(before.all {it.status==2})
            // 005440d1 zeros the 0x128-byte native object and sets only +0x124.
            service.handle(a.account,a,7,byteArrayOf(0x0f,0x0d)+ByteArray(24)+byteArrayOf(0),0)
            val own=f.received.getValue(a.character)
            assertEquals(a.character,Wire.status(body(own.first())).peer.id)
            assertEquals(0,Wire.directory(body(f.received.getValue(b.character).single())).single {it.peer.id==a.character}.status)
            assertFails {service.handle(a.account,a,7,body(Wire.status(Wire.Status(peer(b),0))),0)}
        }
    }
}
