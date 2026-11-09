package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.MailMessages as Wire
import java.nio.ByteBuffer
import kotlin.test.*

/** 실제 프로토콜 코덱/서비스 경계 검사. 원본 UI 실행·송신 실기와 구분한다. 작성: Peppone Choi. */
class GameCommunicationTest {
    @Test fun sameNativeDisplayAndOfficeNumberDoNotShareCountriesMailAndStalePresenceCannotReadIt() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        var owner=a.copy(country=CountryId(42));val foreign=b.copy(country=CountryId(43))
        val successor=a.copy(account="next",character=10002,country=CountryId(42))
        val updates=mutableMapOf<Int,MutableList<List<ByteArray>>>()
        fun officer(person:MailIdentity,connection:String)=member(person,connection,updates.getOrPut(person.character){mutableListOf()}).copy(
            offices=listOf(org.logh7.protocol.BootstrapMessages.OfficeCard(25,1049)))
        hub.update(officer(owner,"first"));hub.update(officer(foreign,"second"));hub.update(officer(successor,"third"))
        val ids=mutableMapOf(owner.character to owner,foreign.character to foreign,successor.character to successor)
        val store=Store()
        GameCommunication(hub,{7},{_,id->ids[id]},{false},{store}).use {service->
            val office=peer(owner).copy(extra=listOf(owner.face,owner.ageSeconds,25))
            service.handle("second",foreign,7,body(Wire.item(Wire.Item(0,-1,false,peer(foreign),office,0,"국가42 직무","기록"),0x0f10)),1)
            fun rows(person:MailIdentity,connection:String)=service.handle(connection,person,7,
                body(Wire.begin(Wire.Query(peer(person).copy(extra=listOf(person.face,person.ageSeconds,25)),true,true))),2)
            assertEquals(1,rows(successor,"third").count {opcode(it)==0x0f0a})
            assertTrue(rows(foreign,"second").none {opcode(it)==0x0f0a})
            val id=store.state!!.messages.single().id
            val change=Wire.Change(peer(foreign).copy(extra=listOf(foreign.face,foreign.ageSeconds,25)),true,id)
            assertEquals(0x0500,opcode(service.handle("second",foreign,7,body(Wire.change(change,0x0f12)),3).single()))
            hub.update(member(owner,"first",received));received.clear();updates.values.forEach {it.clear()}
            service.handle("second",foreign,7,body(Wire.item(Wire.Item(0,-1,false,peer(foreign),office,0,"인계 후","기록"),0x0f10)),4)
            assertTrue(received.isEmpty(),"a former holder must not receive an unsolicited office archive")
            assertTrue(updates.getValue(foreign.character).isEmpty(),"the same card in another country gets no office refresh")
            assertEquals(2,updates.getValue(successor.character).flatten().count {opcode(it)==0x0f0a})
            val stale=owner;owner=owner.copy(country=CountryId(43));ids[owner.character]=owner
            assertFails {rows(stale,"first")};assertFails {rows(owner,"first")}
            hub.update(officer(owner,"first"));assertTrue(rows(owner,"first").none {opcode(it)==0x0f0a})
            assertEquals(2,rows(successor,"third").count {opcode(it)==0x0f0a})
            assertTrue(store.state!!.messages.all {it.recipient.country==CountryId(42)})
        }
    }
    private val a=MailIdentity("alpha",10000,CountryId(2),"송신 한글",face=1000001,ageSeconds=30*31_536_000)
    private val b=MailIdentity("beta",10001,CountryId(3),"수신 日本",face=1000002,ageSeconds=35*31_536_000)
    private fun peer(p:MailIdentity)=p.communicationPeer()
    private fun body(packet:ByteArray)=packet.copyOfRange(4,packet.size)
    private fun opcode(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private class Store:CommunicationStore {
        var state:CommunicationState?=null
        override fun load()=state
        override fun save(state:CommunicationState) {this.state=state}
    }
    private fun member(owner:MailIdentity,connection:String,received:MutableList<List<ByteArray>>) = GamePresence.Member(
        connection,CharacterMessages.Generate(4,owner.character.toLong(),if(owner.country==CountryId(3))3 else 2,0,0,owner.name,"Pilot",30,1,1,1000001,
            List(8){50},0,0,0,20,0,0,"Ship",1),
        Navigator(7,owner.character,owner.country,2487,1000,1000,1000,
            baseId=1049,mode=4,spotId=10490001,shipStyle=ShipStyleId(if(owner.country==CountryId(3))"alliance" else "empire")),received::add)
    @Test fun proposalOfficeRowsUseCurrentActualMembershipAndKeepNominalCostWhenTheSenderPaidPcp() {
        val hub=GamePresence();val messages=mutableListOf<List<ByteArray>>()
        var owner=a.copy(country=CountryId(42));val sender=b.copy(country=CountryId(42))
        val post=19
        hub.update(member(owner,"first",messages).let {it.copy(player=it.player.copy(country=CountryId(42)),
            offices=listOf(org.logh7.protocol.BootstrapMessages.OfficeCard(post,1049)))})
        val mail=ProposalMail(1,sender.character,owner.character,17,post,ProposalActions.RESIGNATION,true,100,86500,pcp=80,country=CountryId(42))
        val state=ProposalState(listOf(mail,mail.copy(id=2,country=CountryId(43)),mail.copy(id=3,country=null)))
        GameCommunication(hub,{7},{_,id->listOf(owner,sender).singleOrNull {it.character==id}},{false},{Store()},proposals={state}).use {service->
            val query=Wire.Query(peer(owner).copy(extra=listOf(0,0,post)),true,true)
            fun rows()=service.handle("first",owner,7,body(Wire.begin(query)),200)
            val received=rows();assertEquals(1,received.count {opcode(it)==0x0f13})
            val packet=received.single {opcode(it)==0x0f13};val row=ByteBuffer.wrap(packet)
            assertEquals(1,row.getInt(6));assertEquals(80,row.getInt(packet.size-8));assertEquals(0,row.getInt(packet.size-4))
            val paired=ByteBuffer.wrap(received.single {opcode(it)==0x0709})
            assertEquals(40,paired.getInt(18),"the recipient still owes half of the 80 MCP command")
            owner=owner.copy(country=CountryId(43))
            hub.update(member(owner,"first",messages).copy(offices=listOf(org.logh7.protocol.BootstrapMessages.OfficeCard(post,1049))))
            val foreign=rows();assertEquals(2,ByteBuffer.wrap(foreign.single {opcode(it)==0x0f13}).getInt(6))
            owner=owner.copy(country=null);hub.update(member(owner,"first",messages))
            assertTrue(rows().none {opcode(it)==0x0f13})
        }
    }
    @Test fun consentThenKoreanMailOfflineDeliveryReadDeleteAndReconnectUseRealCodecs() {
        val hub=GamePresence();val messagesA=mutableListOf<List<ByteArray>>();val messagesB=mutableListOf<List<ByteArray>>()
        val first=member(a,"first",messagesA);val second=member(b,"second",messagesB)
        hub.update(first);hub.update(second);messagesA.clear();messagesB.clear()
        val identities=mapOf(a.character to a,b.character to b);val store=Store()
        GameCommunication(hub,{7},{_,id->identities[id]},{false},{store}).use {service->
            val request=Wire.Exchange(peer(a),peer(b),1,0)
            service.handle("first",a,7,body(Wire.exchange(request)),5)
            val invite=Wire.exchange(body(messagesB.single().single()))
            assertEquals(2,invite.phase)
            service.handle("second",b,7,body(Wire.exchange(invite.copy(phase=3,result=1))),6)
            assertEquals(setOf(b.character),store.state!!.addresses[a.character])
            assertEquals(setOf(a.character),store.state!!.addresses[b.character])
            hub.remove("second");service.disconnected("second");messagesB.clear()
            val mail=Wire.Item(0,-1,false,peer(a).copy(name="위조 표시명"),peer(b),0xffffffffL,"한글 제목 日本","한글 본문\n끝 힣")
            val result=service.handle("first",a,7,body(Wire.item(mail,0x0f10)),7,"one")
            val saved=Wire.item(body(result.first()),0x0f10)
            assertEquals(7L,saved.seconds);assertEquals(a.name,saved.sender.name)
            assertTrue(messagesB.isEmpty())
            hub.update(second.copy(connection="reconnected"));messagesB.clear()
            val query=Wire.Query(Wire.Peer(b.character),true,true)
            val inbox=service.handle("reconnected",b,7,body(Wire.begin(query)),8)
            assertEquals(listOf(0x0f08,0x0f0a,0x0f09),inbox.map(::opcode))
            val delivered=Wire.item(body(inbox[1]),0x0f0a)
            assertEquals(saved,delivered)
            val change=Wire.Change(peer(b),true,saved.id)
            val read=service.handle("reconnected",b,7,body(Wire.change(change,0x0f11)),9)
            assertTrue(Wire.item(body(read[2]),0x0f0a).read)
            val deleted=service.handle("reconnected",b,7,body(Wire.change(change,0x0f12)),10)
            assertEquals(listOf(0x0f12,0x0f08,0x0f09),deleted.map(::opcode))
            val sent=service.handle("first",a,7,body(Wire.begin(Wire.Query(peer(a),false,false))),11)
            assertEquals(1,sent.count {opcode(it)==0x0f0a})
            assertTrue(store.state!!.messages.single().inboxDeleted)
        }
    }
    @Test fun incomingMailRefreshesInboxAfterNativeAutomaticOutboxQuery() {
        val hub=GamePresence();val sent=mutableListOf<List<ByteArray>>();val received=mutableListOf<List<ByteArray>>()
        hub.update(member(a,"first",sent));hub.update(member(b,"second",received))
        val owners=mapOf(a.character to a,b.character to b)
        GameCommunication(hub,{7},{_,id->owners[id]},{false},{Store()}).use {service->
            service.handle("second",b,7,body(Wire.begin(Wire.Query(peer(b),false,false))),1)
            received.clear()
            service.handle("first",a,7,body(Wire.item(Wire.Item(0,-1,false,peer(a),peer(b),2,"한글 제목","본문"),0x0f10)),2)
            val pushed=received.single()
            assertEquals(listOf(0x0f08,0x0f0a,0x0f09),pushed.map(::opcode))
            assertTrue(Wire.query(body(pushed.first())).inbox)
            assertEquals(b.character,Wire.item(body(pushed[1]),0x0f0a).recipient.id)
        }
    }
    @Test fun nativeAutomaticOfficeQueryKeepsPersonalMailboxConnected() {
        val hub=GamePresence();val delivered=mutableListOf<List<ByteArray>>()
        hub.update(member(a,"first",delivered))
        GameCommunication(hub,{7},{_,id->if(id==a.character)a else null},{false},{Store()}).use {service->
            // Captured run-324: the original mailbox queries personal inbox,
            // personal outbox, then its card 195 address without user input.
            val native=java.util.HexFormat.of().parseHex("0f080000271000000000000000000000000000000000000000c30001")
            val unheld=service.handle("first",a,7,native,5)
            assertEquals(listOf(0x0f08,0x0f09),unheld.map(::opcode),"a card that is not held lists an empty office mailbox")
            val personal=service.handle("first",a,7,body(Wire.begin(Wire.Query(peer(a),false,true))),6)
            assertEquals(listOf(0x0f08,0x0f09),personal.map(::opcode))
        }
    }
    @Test fun forgedAccountOwnerOfficeAddressOldConnectionAndGenerationCannotReadAnotherMailbox() {
        val hub=GamePresence();val delivered=mutableListOf<List<ByteArray>>()
        val first=member(a,"first",delivered);hub.update(first)
        var generation=7L
        GameCommunication(hub,{generation},{_,id->if(id==a.character)a else if(id==b.character)b else null},{false},{Store()}).use {service->
            val query=body(Wire.begin(Wire.Query(peer(a),false,true)))
            assertFails {service.handle("first",a.copy(account="other"),7,query,0)}
            assertFails {service.handle("first",a,7,body(Wire.begin(Wire.Query(peer(b),false,true))),0)}
            val office=service.handle("first",a,7,body(Wire.begin(Wire.Query(peer(a).copy(extra=listOf(a.face,a.ageSeconds,195)),false,true))),0)
            assertEquals(listOf(0x0f08,0x0f09),office.map(::opcode),"no card 195, no office mail")
            hub.remove("first");service.disconnected("first")
            assertFails {service.handle("first",a,7,query,0)}
            hub.update(first.copy(connection="new"));generation=8
            assertFails {service.handle("new",a,7,query,0)}
            assertTrue(delivered.none {packets->packets.any {opcode(it)==0x0f0a}})
        }
    }
    @Test fun legacyMailPortraitUsesOnlyTheOriginalAccountsCurrentCharacter() {
        for(sameAccount in listOf(true,false)) {
            val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
            hub.update(member(b,"second",received))
            val legacyA=a.copy(face=-1,ageSeconds=0);val legacyB=b.copy(face=-1,ageSeconds=0)
            val store=Store().apply {state=CommunicationState(7,2,mapOf(a.character to legacyA,b.character to legacyB),
                messages=listOf(StoredMail(1,legacyA,legacyB,"기존 편지","초상 복원",0,"legacy")))}
            val currentA=if(sameAccount)a else a.copy(account="recycled")
            GameCommunication(hub,{7},{_,id->if(id==a.character)currentA else if(id==b.character)b else null},{false},{store}).use {service->
                val packets=service.handle("second",b,7,body(Wire.begin(Wire.Query(peer(b),false,true))),0)
                val mail=Wire.item(body(packets.single {opcode(it)==0x0f0a}),0x0f0a)
                assertEquals(if(sameAccount)a.face else -1,mail.sender.extra[0])
                assertEquals(b.face,mail.recipient.extra[0])
                assertEquals("초상 복원",mail.text)
            }
        }
    }
    @Test fun nameCardConsentExpiresAndCannotMoveAcrossRoomsOrReconnect() {
        val hub=GamePresence();val messages=mutableListOf<List<ByteArray>>()
        val first=member(a,"first",messages);val second=member(b,"second",messages)
        hub.update(first);hub.update(second)
        var clock=0L;val store=Store()
        GameCommunication(hub,{7},{_,id->if(id==a.character)a else if(id==b.character)b else null},{false},{store},{clock}).use {service->
            val request=Wire.Exchange(peer(a),peer(b),1,0)
            service.handle("first",a,7,body(Wire.exchange(request)),0)
            clock=60_001
            assertFails {service.handle("second",b,7,body(Wire.exchange(request.copy(phase=3,result=1))),0)}
            hub.update(second.copy(player=second.player.copy(spotId=10490002)))
            assertFails {service.handle("first",a,7,body(Wire.exchange(request)),0)}
            hub.update(second);service.handle("first",a,7,body(Wire.exchange(request)),0)
            service.disconnected("second");hub.remove("second");hub.update(second.copy(connection="again"))
            assertFails {service.handle("again",b,7,body(Wire.exchange(request.copy(phase=3,result=1))),0)}
            assertNull(store.state)
        }
    }
    @Test fun unknownRecipientBadTextAndTacticsReturn0500AndAllowEditingThenSendingOnSameConnection() {
        val hub=GamePresence();val notifications=mutableListOf<List<ByteArray>>()
        hub.update(member(a,"first",notifications))
        val store=Store();var tactics=false
        GameCommunication(hub,{7},{_,id->if(id==a.character)a else if(id==b.character)b else null},{tactics},{store}).use {service->
            val draft=Wire.Item(-123,765,false,peer(a),peer(b),0,"한국어 초안","수정 전\n본문")
            fun send(mail:Wire.Item)=service.handle("first",a,7,body(Wire.item(mail,0x0f10)),1)
            fun rejected(packets:List<ByteArray>) {
                assertEquals(listOf(0x0500),packets.map(::opcode))
                assertEquals(0x0f10,ByteBuffer.wrap(packets.single()).getShort(6).toInt() and 65535)
            }
            rejected(send(draft.copy(recipient=Wire.Peer(77777))))
            rejected(send(draft.copy(subject="제목\u001b")))
            tactics=true;rejected(send(draft));assertNull(store.state)
            tactics=false
            val accepted=send(draft.copy(subject="수정한 한글 제목"))
            assertEquals(0x0f10,opcode(accepted.first()))
            assertEquals(765,Wire.item(body(accepted.first()),0x0f10).reference)
            assertEquals("수정한 한글 제목",store.state!!.messages.single().subject)
            val query=service.handle("first",a,7,body(Wire.begin(Wire.Query(peer(a),false,false))),2)
            assertEquals(listOf(0x0f08,0x0f0a,0x0f09),query.map(::opcode))
            assertFails {service.handle("first",a,7,body(Wire.item(draft.copy(sender=peer(b)),0x0f10)),3)}
            assertFails {service.handle("first",a,7,body(Wire.item(draft,0x0f10)).dropLast(1).toByteArray(),3)}
        }
    }
    @Test fun fullFoldersReturn0500AndFreeingOneSlotAllowsRetryWithoutDroppingOldMail() {
        val hub=GamePresence();val notifications=mutableListOf<List<ByteArray>>()
        hub.update(member(a,"first",notifications));hub.update(member(b,"second",notifications))
        val store=Store();val seed=Communication(7,store)
        repeat(120) {n->seed.send(a,b,"제목$n","본문",0,"seed$n")}
        GameCommunication(hub,{7},{_,id->if(id==a.character)a else if(id==b.character)b else null},{false},{store}).use {service->
            val draft=body(Wire.item(Wire.Item(-123,1,false,peer(a),peer(b),0,"초과 초안","한국어 본문"),0x0f10))
            assertEquals(0x0500,opcode(service.handle("first",a,7,draft,1).single()))
            val oldest=store.state!!.messages.first().id
            service.handle("second",b,7,body(Wire.change(Wire.Change(peer(b),true,oldest),0x0f12)),2)
            assertEquals(0x0500,opcode(service.handle("first",a,7,draft,3).single())) // Sent still full.
            service.handle("first",a,7,body(Wire.change(Wire.Change(peer(a),false,oldest),0x0f12)),4)
            assertEquals(0x0f10,opcode(service.handle("first",a,7,draft,5).first()))
            assertEquals(121,store.state!!.messages.size)
            assertEquals(120,store.state!!.messages.count {!it.inboxDeleted})
            assertEquals(0x0500,opcode(service.handle("first",a,7,
                body(Wire.change(Wire.Change(peer(a),true,oldest),0x0f11)),6).single()))
        }
    }
    @Test fun offlineMailSurvivesRestartAndOlderRowsAppearAfterDeletion(@org.junit.jupiter.api.io.TempDir folder:java.nio.file.Path) {
        val hub=GamePresence()
        val notifications=mutableListOf<List<ByteArray>>()
        hub.update(member(a,"first",notifications))
        val identities=mapOf(a.character to a,b.character to b)
        val path=folder.resolve("mail")
        fun service()=GameCommunication(hub,{7},{_,id->identities[id]},{false},
            {org.logh7.persistence.FileCommunicationStore(path)})
        service().use {sender->
            for (index in 0..120) {
                val draft=Wire.Item(Int.MIN_VALUE,index,false,peer(a),peer(b),0,"제목 $index","한글 본문")
                val reply=sender.handle("first",a,7,body(Wire.item(draft,0x0f10)),1)
                assertEquals(if(index==120)0x0500 else 0x0f10,opcode(reply.first()))
            }
        }

        hub.update(member(b,"second",notifications))
        service().use {recipient->
            fun rows(packets:List<ByteArray>)=packets.filter {opcode(it)==0x0f0a}.map {Wire.item(body(it),0x0f0a)}
            val inbox=rows(recipient.unreadInbox(b,7).get())
            assertEquals((119 downTo 20).map {"제목 $it"},inbox.map {it.subject})
            assertTrue(inbox.all {!it.read && it.text=="한글 본문"})
            val delete=Wire.Change(peer(b),true,inbox.first().id)
            recipient.handle("second",b,7,body(Wire.change(delete,0x0f12)),2)
            val query=Wire.Query(peer(b),false,true)
            val refreshed=rows(recipient.handle("second",b,7,body(Wire.begin(query)),3))
            assertEquals((118 downTo 19).map {"제목 $it"},refreshed.map {it.subject})
        }
    }

    @Test fun taggedRequestReplaysAcrossReconnectAndServiceReloadWithoutDuplicateDelivery() {
        val hub=GamePresence();val notifications=mutableListOf<List<ByteArray>>()
        val first=member(a,"first",notifications);hub.update(first);hub.update(member(b,"second",notifications))
        val store=Store();val identities=mapOf(a.character to a,b.character to b)
        val draft=Wire.Item(Int.MIN_VALUE,1234567,false,peer(a),peer(b),0,"한글 제목","재시도 본문")
        val request=body(Wire.item(draft,0x0f10));var id=0
        GameCommunication(hub,{7},{_,n->identities[n]},{false},{store}).use {service->
            id=Wire.item(body(service.handle("first",a,7,request,1).first()),0x0f10).id
            notifications.clear()
            assertEquals(id,Wire.item(body(service.handle("first",a,7,request,2).first()),0x0f10).id)
            assertTrue(notifications.isEmpty())
            assertEquals(0x0500,opcode(service.handle("first",a,7,
                body(Wire.item(draft.copy(text="위조 변경"),0x0f10)),3).single()))
        }
        hub.remove("first");hub.update(first.copy(connection="new"));notifications.clear()
        GameCommunication(hub,{7},{_,n->identities[n]},{false},{store}).use {service->
            assertEquals(id,Wire.item(body(service.handle("new",a,7,request,4).first()),0x0f10).id)
            assertEquals(1,store.state!!.messages.size);assertTrue(notifications.isEmpty())
            val next=body(Wire.item(draft.copy(reference=1234568),0x0f10))
            assertEquals(id+1,Wire.item(body(service.handle("new",a,7,next,5).first()),0x0f10).id)
        }
    }
    @Test fun anOfficeMailboxBelongsToThePostAndPassesToTheNextHolder() {
        val hub=GamePresence();val got=mutableListOf<List<ByteArray>>()
        val officer=member(a,"first",got).copy(offices=listOf(org.logh7.protocol.BootstrapMessages.OfficeCard(25,1049)))
        val successorId=a.copy(account="gamma",character=10002,name="후임")
        val successor=member(successorId,"third",got).copy(offices=listOf(org.logh7.protocol.BootstrapMessages.OfficeCard(25,1049)))
        hub.update(officer);hub.update(member(b,"second",got));hub.update(successor)
        val ids=mapOf(a.character to a,b.character to b,successorId.character to successorId)
        val office=peer(a).copy(extra=listOf(a.face,a.ageSeconds,25))
        GameCommunication(hub,{7},{_,id->ids[id]},{false},{Store()}).use {service->
            val sent=service.handle("first",a,7,body(Wire.item(Wire.Item(0,-1,false,office,peer(b),1,"보급 지시","내용"),0x0f10)),1,"o1")
            assertEquals(25,Wire.item(body(sent.first()),0x0f10).sender.extra[2],"sent from the post")
            service.handle("second",b,7,body(Wire.item(Wire.Item(0,-1,false,peer(b),office,2,"답장","확인"),0x0f10)),2,"o2")
            fun list(owner:MailIdentity,connection:String,inbox:Boolean)=service.handle(connection,owner,7,
                body(Wire.begin(Wire.Query(peer(owner).copy(extra=listOf(owner.face,owner.ageSeconds,25)),true,inbox))),3)
                .filter {opcode(it)==0x0f0a}.map {Wire.item(body(it),0x0f0a).subject}
            assertEquals(listOf("답장"),list(a,"first",true));assertEquals(listOf("보급 지시"),list(a,"first",false))
            assertEquals(listOf("답장"),list(successorId,"third",true),"the next holder of card 25 inherits the mailbox")
            assertTrue(service.handle("first",a,7,body(Wire.begin(Wire.Query(peer(a),true,true))),4).none {opcode(it)==0x0f0a},
                "office mail stays out of the personal inbox")
            hub.update(officer.copy(offices=emptyList()))
            assertTrue(list(a,"first",true).isEmpty(),"a former holder no longer reads it")
        }
    }
}
