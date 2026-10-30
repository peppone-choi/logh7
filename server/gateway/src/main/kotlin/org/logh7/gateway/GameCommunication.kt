package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.persistence.FileCommunicationStore
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.MailMessages as Wire
import java.nio.ByteBuffer
import java.nio.file.Path

/** 개인 명함·편지. 클라이언트가 보낸 이름/권한·시각은 저장 근거로 쓰지 않는다. 작성: 최병호. */
class GameCommunication(private val presence:GamePresence,private val generation:()->Long,
    private val directory:(Long,Int)->MailIdentity?,private val inTactics:(String)->Boolean,
    private val store:(Long)->CommunicationStore,private val now:()->Long=System::currentTimeMillis):AutoCloseable {
    private var current:Triple<Long,CommunicationStore,Communication>?=null
    private var messenger:Pair<Long,GameMessenger>?=null
    private data class Offer(val from:String,val to:String,val expires:Long)
    private val offers=mutableMapOf<Triple<Long,Int,Int>,Offer>()
    private val views=mutableMapOf<String,Boolean>()
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor {task->
        Thread(task,"logh7-personal-mail").apply {isDaemon=true}
    }
    fun submit(connection:String,owner:MailIdentity,expected:Long,body:ByteArray,seconds:Long)=
        java.util.concurrent.CompletableFuture.supplyAsync({handle(connection,owner,expected,body,seconds)},executor)
    private fun engine(expected:Long):Communication {
        require(expected==generation()) {"Stale communication generation"}
        current?.takeIf {it.first==expected}?.let {return it.third}
        current?.second?.close();current=null;offers.clear();views.clear()
        val backend=store(expected)
        val engine=try {Communication(expected,backend,generation)} catch(failure:Throwable){backend.close();throw failure}
        current=Triple(expected,backend,engine);return engine
    }
    private fun peer(p:MailIdentity):Wire.Peer {
        // Upgrade a legacy saved mail's missing portrait only from the same account's character.
        val expected=current?.first
        val current=if(p.face==-1 && expected!=null)directory(expected,p.character)?.takeIf {it.account==p.account} else null
        return (current?.let {p.copy(face=it.face,ageSeconds=it.ageSeconds)} ?: p).communicationPeer()
    }
    private fun item(p:StoredMail)=Wire.Item(p.id,-1,p.read,peer(p.sender),peer(p.recipient),p.seconds,p.subject,p.body)
    private fun member(connection:String,owner:MailIdentity,expected:Long):GamePresence.Member {
        require(expected==generation() && directory(expected,owner.character)==owner)
        return checkNotNull(presence.communicationMember(connection)).also {
            require(it.player.generation==expected && it.character.id.toInt()==owner.character && it.character.power==owner.power)
        }
    }
    private fun books(engine:Communication,owner:MailIdentity)=Wire.addresses(engine.addressBook(owner).map(::peer))
    private fun inbox(engine:Communication,owner:MailIdentity,received:Boolean,all:Boolean=false):List<ByteArray> {
        val query=Wire.Query(peer(owner),all,received)
        // The CD cache has 100 slots immediately before its next object. The
        // manual's 120 remain stored; deleting visible mail reveals older rows.
        return listOf(Wire.begin(query))+engine.list(owner,received).take(100).map {Wire.item(item(it))}+Wire.end()
    }
    private fun publish(expected:Long,owner:MailIdentity,messages:List<ByteArray>,except:String) {
        presence.communicationSend(expected,owner.character,messages,except)
    }
    private fun rejection(opcode:Int,reason:MailRejection):List<ByteArray> {
        // Authentication/generation failures stay exceptional. These are normal
        // authenticated game outcomes, and must not close the secure channel.
        require(reason !in listOf(MailRejection.STALE,MailRejection.IDENTITY))
        val text=when(reason) {
            MailRejection.UNKNOWN_RECIPIENT->"받는 이를 찾을 수 없습니다. 받는 이를 다시 선택해 주세요."
            MailRejection.TEXT->"제목은 127자, 본문은 511자까지 입력할 수 있습니다. 지원하지 않는 문자를 확인해 주세요."
            MailRejection.TACTICS->"상대가 전술 중이어서 편지를 받을 수 없습니다. 잠시 뒤 다시 보내 주세요."
            MailRejection.INBOX_FULL->"상대의 받은 편지함이 가득 찼습니다. 편지는 배달되지 않았습니다."
            MailRejection.SENT_FULL->"보낸 편지함이 가득 찼습니다. 편지를 정리한 뒤 다시 보내 주세요."
            MailRejection.CAPACITY->"편지 저장 한도에 도달했습니다. 편지는 배달되지 않았습니다."
            MailRejection.NOT_OWNER->"이 편지를 조회하거나 변경할 수 없습니다. 목록을 다시 확인해 주세요."
            MailRejection.OPERATION_CONFLICT->"이미 사용한 송신 요청과 내용이 다릅니다. 편지를 새로 작성해 주세요."
            else->error("Invalid normal mail rejection")
        }
        return listOf(Wire.rejected(opcode,text))
    }
    private fun sameChat(a:GamePresence.Member,b:GamePresence.Member):Boolean {
        val x=a.player;val y=b.player
        return x.generation==y.generation && x.grid==y.grid &&
            (x.baseId>0 && x.baseId==y.baseId && x.spotId>0 && x.spotId==y.spotId && x.spotOwner==y.spotOwner ||
                a.outfit>0 && a.outfit==b.outfit)
    }
    @Synchronized fun handle(connection:String,owner:MailIdentity,expected:Long,body:ByteArray,seconds:Long,
        operation:String=java.util.UUID.randomUUID().toString()):List<ByteArray> {
        require(body.size>=2)
        val opcode=ByteBuffer.wrap(body).short.toInt() and 65535
        if(opcode==0x0f04) {
            require(body.size==2 && expected==generation() && directory(expected,owner.character)==owner)
            return listOf(books(engine(expected),owner))
        }
        if(opcode in listOf(0x0f06,0x0f0d,0x0f0e,0x0f0f)) {
            require(expected==generation() && directory(expected,owner.character)==owner)
            member(connection,owner,expected)
            val service=messenger?.takeIf {it.first==expected}?.second ?: GameMessenger(
                presence,expected,generation,directory).also {messenger=expected to it}
            return service.handle(connection,body)
        }
        val actor=member(connection,owner,expected)
        val engine=engine(expected)
        fun own(p:Wire.Peer) {require(p.id==owner.character && p.personal) {"Personal mail owner mismatch"}}
        return when(opcode) {
            0x0f08 -> {
                val query=Wire.query(body)
                require(query.owner.id==owner.character) {"Mail owner mismatch"}
                // Opening the CD mailbox automatically queries every card
                // address after the personal inbox/outbox. An unconnected
                // office mailbox is a normal outcome, not lost authentication.
                if(!query.owner.personal) return listOf(Wire.rejected(opcode,
                    "직무 주소 편지함은 아직 연결되지 않았습니다. 개인 주소를 선택해 주세요."),Wire.end())
                views[connection]=query.inbox
                inbox(engine,owner,query.inbox,query.all)
            }
            0x0f0b -> {
                val command=Wire.exchange(body)
                require(command.first.personal && command.second.personal)
                val first=checkNotNull(directory(expected,command.first.id))
                val second=checkNotNull(directory(expected,command.second.id))
                require(first.character!=second.character)
                offers.entries.removeIf {it.value.expires<=now() || it.key.first!=expected}
                val key=Triple(expected,first.character,second.character)
                if(command.phase==1) {
                    own(command.first)
                    val recipient=checkNotNull(presence.communicationMember(expected,second.character))
                    require(sameChat(actor,recipient)) {"Name-card exchange requires a shared chat"}
                    require(offers.size<1024 || key in offers)
                    offers[key]=Offer(connection,recipient.connection,Math.addExact(now(),60_000))
                    publish(expected,second,listOf(Wire.exchange(Wire.Exchange(peer(first),peer(second),2,0))),connection)
                    listOf(Wire.exchange(Wire.Exchange(peer(first),peer(second),1,0)))
                } else {
                    require(command.phase==3 && command.result in 1..2);own(command.second)
                    val offer=checkNotNull(offers.remove(key)) {"No current name-card offer"}
                    require(offer.to==connection && presence.communicationMember(offer.from)?.let {
                        it.character.id.toInt()==first.character && sameChat(it,actor)
                    }==true) {"Name-card connection changed"}
                    val result=if(command.result==2)2 else if(engine.exchange(first,second))1 else 3
                    val packet=Wire.exchange(Wire.Exchange(peer(first),peer(second),4,result))
                    publish(expected,first,listOf(packet,books(engine,first)),connection)
                    listOf(packet,books(engine,second))
                }
            }
            0x0f0c -> {
                val target=Wire.deleteAddress(body);require(target.personal && target.id>0)
                engine.removeAddress(owner,target.id)
                listOf(Wire.deleteAddress(Wire.Peer(target.id)),books(engine,owner))
            }
            0x0f10 -> {
                val command=Wire.item(body);own(command.sender);require(command.recipient.personal)
                val recipient=directory(expected,command.recipient.id)
                // Compatibility DLL puts a random 63-bit nonce in the existing
                // request ID/ref words. Legacy ID=0 requests keep per-call keys.
                // Account + character + generation bind the untrusted nonce.
                val key=if(command.id<=-2)"native:${owner.account}:${owner.character}:${command.id}:${command.reference}" else operation
                val result=engine.send(owner,recipient,command.subject,command.text,seconds,key,
                    recipient?.let {inTactics(it.account)} ?: false)
                result.rejection?.let {return rejection(opcode,it)}
                val saved=checkNotNull(result.mail)
                if(result.changed) {
                    // CD opening queries both folders, so the last query is
                    // not the visible tab. Delivery always changes the inbox.
                    publish(expected,saved.recipient,inbox(engine,saved.recipient,true),connection)
                }
                // Echo the nonce's low word only in the command ACK. Stored
                // inbox/outbox rows keep the ordinary reference=-1 value.
                listOf(Wire.item(item(saved).copy(reference=command.reference),0x0f10))+inbox(engine,owner,views[connection] ?: true)
            }
            0x0f11,0x0f12 -> {
                val command=Wire.change(body,opcode);own(command.owner)
                val result=engine.change(owner,command.id,command.inbox,opcode==0x0f12)
                result.rejection?.let {return rejection(opcode,it)}
                listOf(Wire.change(command.copy(owner=peer(owner)),opcode))+inbox(engine,owner,command.inbox)
            }
            else -> error("Unsupported communication opcode")
        }
    }
    @Synchronized fun disconnected(connection:String) {
        views.remove(connection);offers.entries.removeIf {it.value.from==connection || it.value.to==connection}
        if(messenger?.first==generation())messenger?.second?.disconnected(connection) else messenger=null
    }
    override fun close() {
        executor.shutdown()
        if(!executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))executor.shutdownNow()
        synchronized(this) {current?.second?.close();current=null;messenger=null;offers.clear();views.clear()}
    }
}

internal fun communicationIdentity(characters:CharacterCreation,id:Int):MailIdentity? {
    val character=characters.characterById(id) ?: return null
    val account=characters.accountByCharacter(id) ?: return null
    val name=(character.surname+" "+character.givenName).trim().take(12)
    return MailIdentity(account,id,character.power,name,character.origin,character.rank,
        face=character.face,ageSeconds=Math.multiplyExact(character.age,CharacterMessages.AGE_SECONDS_PER_YEAR))
}

internal fun MailIdentity.communicationPeer()=Wire.Peer(character,name,origin,rank,title,listOf(face,ageSeconds,0))

internal fun communicationStore(path:Path?,generation:Long):CommunicationStore =
    path?.let {FileCommunicationStore(it.resolveSibling("${it.fileName}.mail.session-$generation"))} ?:
    object:CommunicationStore {
        private var state:CommunicationState?=null
        override fun load()=state
        override fun save(state:CommunicationState) {this.state=state}
    }
