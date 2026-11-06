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
            // 0x4c374d33 adds the office (card) addresses of every mail.
            val format=input.readInt().also {require(it in 0x4c374d31..0x4c374d34)}
            val generation=input.readLong();val next=input.readInt()
            fun count(limit:Int)=input.readInt().also {require(it in 0..limit)}
            fun identity():MailIdentity {
                val account=input.readUTF();val character=input.readInt();val country=input.readInt()
                require(if(format<0x4c374d34)country in 2..3 else country>=0)
                val identity=MailIdentity(account,character,country.takeIf {it!=0}?.let(::CountryId),input.readUTF(),
                    input.readUnsignedShort(),input.readUnsignedShort(),input.readUTF())
                return if(format>=0x4c374d32)identity.copy(face=input.readInt(),ageSeconds=input.readInt()) else identity
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
                    input.readBoolean(),input.readBoolean(),input.readBoolean()).let {mail->
                        if(format>=0x4c374d33)mail.copy(senderPost=input.readUnsignedShort(),recipientPost=input.readUnsignedShort()) else mail}
            }
            require(input.read()==-1)
            CommunicationState(generation,next,owners,addresses,messages)
        }
    }
    @Synchronized override fun save(state:CommunicationState) {
        val bytes=ByteArrayOutputStream().also {buffer->DataOutputStream(buffer).use {out->
            fun identity(p:MailIdentity) {
                out.writeUTF(p.account);out.writeInt(p.character);out.writeInt(p.country?.value ?: 0);out.writeUTF(p.name)
                out.writeShort(p.origin);out.writeShort(p.rank);out.writeUTF(p.title)
                out.writeInt(p.face);out.writeInt(p.ageSeconds)
            }
            val posts=state.messages.any {it.senderPost!=0 || it.recipientPost!=0}
            val countries=(state.owners.values.asSequence()+state.messages.asSequence().flatMap {sequenceOf(it.sender,it.recipient)})
                .any {it.country?.value !in 2..3}
            out.writeInt(if(countries)0x4c374d34 else if(posts)0x4c374d33 else 0x4c374d32)
            out.writeLong(state.generation);out.writeInt(state.nextId)
            out.writeInt(state.owners.size);state.owners.toSortedMap().values.forEach(::identity)
            out.writeInt(state.addresses.size);state.addresses.toSortedMap().forEach {(owner,peers)->
                out.writeInt(owner);out.writeInt(peers.size);peers.sorted().forEach(out::writeInt)
            }
            out.writeInt(state.messages.size);state.messages.forEach {mail->
                out.writeInt(mail.id);identity(mail.sender);identity(mail.recipient);out.writeUTF(mail.subject);out.writeUTF(mail.body)
                out.writeLong(mail.seconds);out.writeUTF(mail.operation);out.writeBoolean(mail.read)
                out.writeBoolean(mail.inboxDeleted);out.writeBoolean(mail.sentDeleted)
                if(posts || countries) {out.writeShort(mail.senderPost);out.writeShort(mail.recipientPost)}
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
