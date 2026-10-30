package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileCommunicationStoreTest {
    @TempDir lateinit var directory:Path
    private val a=MailIdentity("alpha",10000,2,"송신 한글",face=1000001,ageSeconds=30*31_536_000)
    private val b=MailIdentity("beta",10001,3,"수신 日本",face=1000002,ageSeconds=35*31_536_000)
    @Test fun oldMailboxLoadsWithoutInventingAPortraitAndCanBeSavedInNewFormat() {
        val file=directory.resolve("legacy")
        DataOutputStream(Files.newOutputStream(file)).use {out->
            fun identity(p:MailIdentity) {
                out.writeUTF(p.account);out.writeInt(p.character);out.writeInt(p.power);out.writeUTF(p.name)
                out.writeShort(p.origin);out.writeShort(p.rank);out.writeUTF(p.title)
            }
            out.writeInt(0x4c374d31);out.writeLong(7);out.writeInt(2)
            out.writeInt(2);identity(a);identity(b)
            out.writeInt(1);out.writeInt(a.character);out.writeInt(1);out.writeInt(b.character)
            out.writeInt(1);out.writeInt(1);identity(a);identity(b)
            out.writeUTF("기존 제목");out.writeUTF("기존 본문");out.writeLong(12);out.writeUTF("legacy-op")
            out.writeBoolean(true);out.writeBoolean(false);out.writeBoolean(false)
        }
        FileCommunicationStore(file).use {store->
            val state=store.load()!!
            assertEquals(-1,state.messages.single().sender.face)
            assertEquals("기존 본문",state.messages.single().body)
            assertTrue(state.messages.single().read)
            assertEquals(setOf(b.character),state.addresses[a.character])
            val updated=state.copy(messages=state.messages.map {it.copy(sender=a,recipient=b)})
            store.save(updated);assertEquals(updated,store.load())
        }
    }
    @Test fun atomicSaveFailureKeepsBothFoldersAndRestartRestoresKoreanAndReadState() {
        val file=directory.resolve("mail.session-7");var fail=false
        FileCommunicationStore(file,{if(fail)throw IOException("before rename")}).use {store->
            val service=Communication(7,store)
            assertTrue(service.exchange(a,b))
            val saved=service.send(a,b,"한글 제목 日本","본문\n마지막 힣",0,"one").mail!!
            service.change(b,saved.id,true,false)
            val previous=Files.readAllBytes(file);fail=true
            assertFailsWith<IOException> {service.send(a,b,"두 번째","보관 실패",1,"two")}
            assertContentEquals(previous,Files.readAllBytes(file))
            assertEquals(1,service.list(a,false).size)
            assertFails {FileCommunicationStore(file).close()}
        }
        FileCommunicationStore(file).use {store->
            val service=Communication(7,store)
            assertEquals(b,service.addressBook(a).single())
            val mail=service.list(b,true).single()
            assertTrue(mail.read);assertEquals("한글 제목 日本",mail.subject);assertEquals("본문\n마지막 힣",mail.body)
        }
        FileCommunicationStore(directory.resolve("mail.session-8")).use {
            assertTrue(Communication(8,it).list(a,true).isEmpty())
        }
        Files.write(file,byteArrayOf(1,2,3))
        FileCommunicationStore(file).use {assertFails {it.load()}}
    }
}
