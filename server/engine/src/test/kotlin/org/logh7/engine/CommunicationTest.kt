package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CommunicationTest {
    @Test fun officeMailBelongsToTheActualCountryAndSurvivesItsHoldersDefection() {
        val store=Store();val service=Communication(7,store)
        val holder=a.copy(country=CountryId(42));val outsider=b.copy(country=CountryId(43))
        val next=holder.copy(account="next",character=10002)
        val saved=service.send(outsider,holder,"국가42 직무","기록",0,"office",recipientPost=25).mail!!
        assertEquals(listOf(saved),service.list(next,true,25))
        assertTrue(service.list(outsider,true,25).isEmpty())
        assertEquals(MailRejection.NOT_OWNER,service.change(outsider,saved.id,true,true,25).rejection)
        assertTrue(service.exchange(holder,outsider))
        val personal=service.send(outsider,holder,"개인","보존",1,"personal").mail!!
        val moved=holder.copy(country=CountryId(43));service.defect(moved)
        assertTrue(service.addressBook(moved).isEmpty())
        assertEquals(listOf(personal),service.list(moved,true))
        assertTrue(service.list(moved,true,25).isEmpty())
        assertEquals(MailRejection.NOT_OWNER,service.change(moved,saved.id,true,true,25).rejection)
        assertEquals(listOf(saved),Communication(7,store).list(next,true,25))
        assertEquals(MailRejection.OPERATION_CONFLICT,
            service.send(outsider,moved,"개인","보존",2,"personal").rejection)
    }
    @Test fun unaffiliatedPersonalMailWorksWithoutGivingAnyoneAnUnaffiliatedOffice() {
        val store=Store();val service=Communication(7,store)
        val first=a.copy(country=null);val second=b.copy(country=null)
        assertTrue(service.exchange(first,second))
        assertTrue(service.send(first,second,"개인","본문",0,"one").changed)
        val before=store.state
        assertEquals(MailRejection.NOT_OWNER,service.send(first,second,"직무","본문",0,"two",senderPost=25).rejection)
        assertEquals(MailRejection.UNKNOWN_RECIPIENT,service.send(first,second,"직무","본문",0,"three",recipientPost=25).rejection)
        assertTrue(service.list(second,true,25).isEmpty());assertEquals(before,store.state)
        store.fail=true
        assertFails {service.defect(first.copy(country=CountryId(42)))}
        assertEquals(1,service.list(second,true).size);assertEquals(listOf(second),service.addressBook(first))
    }
    @Test fun committedMembershipRecoveryIsAtomicAndRepeatedRecoveryKeepsNewAddresses() {
        val store=Store();var service=Communication(7,store)
        service.exchange(a,b);service.send(b,a,"개인","보존",0,"personal")
        val moved=a.copy(country=CountryId(42));store.fail=true
        assertFails {service.synchronize(moved)}
        assertEquals(listOf(b),service.addressBook(a))
        store.fail=false;service=Communication(7,store);service.synchronize(moved)
        assertTrue(service.addressBook(moved).isEmpty());assertEquals(1,service.list(moved,true).size)
        assertTrue(service.exchange(moved,b));val saves=store.saves
        service.synchronize(moved);assertEquals(saves,store.saves);assertEquals(listOf(b),service.addressBook(moved))
        assertFails {service.synchronize(moved.copy(account="intruder"))}
    }
    private val a=MailIdentity("alpha",10000,CountryId(2),"한글 Alpha")
    private val b=MailIdentity("beta",10001,CountryId(3),"日本 Beta")
    private class Store:CommunicationStore {
        var state:CommunicationState?=null;var fail=false;var saves=0
        override fun load()=state
        override fun save(state:CommunicationState) {if(fail)error("disk failure");this.state=state;saves++}
    }
    @Test fun offlineDeliveryReadIndependentDeletesAndRetrySurviveReload() {
        val store=Store();var service=Communication(7,store)
        val first=service.send(a,b,"한글 제목 日本","한글 본문\r\n둘째 줄",55,"operation")
        assertTrue(first.changed);assertEquals(1,store.saves)
        assertEquals(first.mail,service.send(a,b,"한글 제목 日本","한글 본문\r\n둘째 줄",55,"operation").mail)
        assertEquals(1,store.saves)
        assertEquals(MailRejection.OPERATION_CONFLICT,service.send(a,b,"다른 제목","본문",55,"operation").rejection)
        service=Communication(7,store)
        val id=first.mail!!.id
        assertEquals(1,service.list(a,false).size);assertEquals(1,service.list(b,true).size)
        assertTrue(service.change(b,id,true,false).mail!!.read)
        assertTrue(service.list(a,false).single().read)
        assertEquals(MailRejection.NOT_OWNER,service.change(a,id,true,true).rejection)
        service.change(b,id,true,true);assertTrue(service.list(b,true).isEmpty())
        assertEquals(1,service.list(a,false).size)
        service.change(a,id,false,true);assertTrue(service.list(a,false).isEmpty())
        assertEquals(id,service.send(a,b,"한글 제목 日本","한글 본문\r\n둘째 줄",55,"operation").mail!!.id)
        assertTrue(service.list(b,true).isEmpty()) // Retry cannot resurrect a deleted copy.
    }
    @Test fun strictLengthsIdentityGenerationAndTacticsRejectWithoutWrites() {
        val store=Store();var generation=7L;val service=Communication(7,store,{generation})
        assertTrue(service.send(a,b,"가".repeat(126)+"힣","나".repeat(510)+"힣",0,"maximum").changed)
        val saves=store.saves
        for((subject,body) in listOf("가".repeat(128) to "본문","제목" to "가".repeat(512),"제목" to "x\u0000y",
            "제목" to "\ud800","\u001b" to "본문"))
            assertEquals(MailRejection.TEXT,service.send(a,b,subject,body,0,"bad").rejection)
        assertEquals(MailRejection.IDENTITY,service.send(a.copy(account="intruder"),b,"제목","본문",0,"bad").rejection)
        assertEquals(MailRejection.UNKNOWN_RECIPIENT,service.send(a,null,"제목","본문",0,"bad").rejection)
        assertEquals(MailRejection.TACTICS,service.send(a,b,"제목","본문",0,"bad",true).rejection)
        generation=8
        assertEquals(MailRejection.STALE,service.send(a,b,"제목","본문",0,"bad").rejection)
        assertEquals(saves,store.saves)
        assertFailsWith<IllegalArgumentException> {Communication(8,store)}
    }
    @Test fun exchangeIsMutualBoundedIdempotentAndAtomicOnFailure() {
        val store=Store();val service=Communication(7,store)
        repeat(100) {n->assertTrue(service.exchange(a,b.copy(account="peer$n",character=10001+n,name="상대$n")))}
        assertEquals(100,service.addressBook(a).size)
        val saves=store.saves
        assertFalse(service.exchange(a,b.copy(account="overflow",character=20000)))
        assertEquals(saves,store.saves)
        service.removeAddress(a,10001);assertEquals(99,service.addressBook(a).size)
        store.fail=true
        assertFails {service.exchange(a,b.copy(account="overflow",character=20000))}
        assertEquals(99,service.addressBook(a).size)
        assertFails {service.send(a,b.copy(account="peer0"),"제목","본문",0,"failed")}
        assertTrue(service.list(a,false).isEmpty())
    }
    @Test fun fullInboxAndSentFolderNeverDeleteOldMailAutomatically() {
        val service=Communication(7,Store())
        repeat(120) {n->assertTrue(service.send(a,b,"제목$n","본문",n.toLong(),"send$n").changed)}
        assertEquals(MailRejection.INBOX_FULL,service.send(a,b,"초과","본문",0,"overflow").rejection)
        assertEquals(120,service.list(b,true).size)
        val first=service.list(b,true).last();service.change(b,first.id,true,true)
        assertEquals(MailRejection.SENT_FULL,service.send(a,b,"초과","본문",0,"overflow").rejection)
        service.change(a,first.id,false,true)
        assertTrue(service.send(a,b,"교체","본문",0,"replacement").changed)
        assertEquals(120,service.list(b,true).size)
    }
    @Test fun emptyNativeFieldsRemainValidAndAddressMembershipIsNotRequiredForReplies() {
        val service=Communication(7,Store())
        assertTrue(service.addressBook(a).isEmpty())
        val mail=service.send(a,b,"","",0,"empty")
        assertTrue(mail.changed);assertEquals("",mail.mail!!.body)
        assertTrue(service.addressBook(b).isEmpty())
        assertTrue(service.send(b,a,"Re:","답장",1,"reply").changed)
    }
}
