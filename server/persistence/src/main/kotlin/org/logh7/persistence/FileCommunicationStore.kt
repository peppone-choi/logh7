package org.logh7.persistence

import org.logh7.engine.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.*

/** 송/수신 사본을 분리 저장하지 않는다. 강제 기록 후 원자 교체. 작성: Peppone Choi. */
class FileCommunicationStore(private val path:Path,private val beforeReplace:()->Unit={}):CommunicationStore {
    private val channel:FileChannel
    private val lock:java.nio.channels.FileLock
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        channel=FileChannel.open(path.resolveSibling("${path.fileName}.lock"),CREATE,WRITE)
        lock=try {checkNotNull(channel.tryLock()) {"Communication store already open"}}
        catch(failure:Throwable){channel.close();throw failure}
    }
    @Synchronized override fun load():CommunicationState? {
        if(!Files.exists(path))return null
        require(Files.size(path) in 20L..128_000_000L)
        return DataInputStream(ByteArrayInputStream(Files.readAllBytes(path))).use {input->
            val format=input.readInt().also {require(it==0x4c374d31 || it==0x4c374d32)}
            val generation=input.readLong();val next=input.readInt()
            fun count(limit:Int)=input.readInt().also {require(it in 0..limit)}
            fun identity():MailIdentity {
                val identity=MailIdentity(input.readUTF(),input.readInt(),input.readInt(),input.readUTF(),
                    input.readUnsignedShort(),input.readUnsignedShort(),input.readUTF())
                return if(format==0x4c374d32)identity.copy(face=input.readInt(),ageSeconds=input.readInt()) else identity
            }
            val owners=List(count(4096)){identity()}.also {require(it.map {p->p.character}.distinct().size==it.size)}.associateBy {it.character}
            val addresses=linkedMapOf<Int,Set<Int>>()
            repeat(count(4096)) {
                val owner=input.readInt();require(owner !in addresses)
                val peers=List(count(100)){input.readInt()};require(peers.distinct().size==peers.size)
                addresses[owner]=peers.toSet()
            }
            val messages=List(count(100_000)) {
                val id=input.readInt();val sender=identity();val recipient=identity()
                StoredMail(id,sender,recipient,input.readUTF(),input.readUTF(),input.readLong(),input.readUTF(),
                    input.readBoolean(),input.readBoolean(),input.readBoolean())
            }
            require(input.read()==-1)
            CommunicationState(generation,next,owners,addresses,messages)
        }
    }
    @Synchronized override fun save(state:CommunicationState) {
        val bytes=ByteArrayOutputStream().also {buffer->DataOutputStream(buffer).use {out->
            fun identity(p:MailIdentity) {
                out.writeUTF(p.account);out.writeInt(p.character);out.writeInt(p.power);out.writeUTF(p.name)
                out.writeShort(p.origin);out.writeShort(p.rank);out.writeUTF(p.title)
                out.writeInt(p.face);out.writeInt(p.ageSeconds)
            }
            out.writeInt(0x4c374d32);out.writeLong(state.generation);out.writeInt(state.nextId)
            out.writeInt(state.owners.size);state.owners.toSortedMap().values.forEach(::identity)
            out.writeInt(state.addresses.size);state.addresses.toSortedMap().forEach {(owner,peers)->
                out.writeInt(owner);out.writeInt(peers.size);peers.sorted().forEach(out::writeInt)
            }
            out.writeInt(state.messages.size);state.messages.forEach {mail->
                out.writeInt(mail.id);identity(mail.sender);identity(mail.recipient);out.writeUTF(mail.subject);out.writeUTF(mail.body)
                out.writeLong(mail.seconds);out.writeUTF(mail.operation);out.writeBoolean(mail.read)
                out.writeBoolean(mail.inboxDeleted);out.writeBoolean(mail.sentDeleted)
            }
        }}.toByteArray()
        require(bytes.size<=128_000_000)
        val temporary=Files.createTempFile(path.toAbsolutePath().parent,".mail-",".tmp")
        try {
            FileChannel.open(temporary,WRITE,TRUNCATE_EXISTING).use {file->
                val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())file.write(buffer);file.force(true)
            }
            beforeReplace()
            Files.move(temporary,path,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally {Files.deleteIfExists(temporary)}
    }
    override fun close() {try {if(lock.isValid)lock.release()} finally {channel.close()}}
}
