package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.persistence.FileCommunicationStore
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.MailMessages as Wire
import java.nio.ByteBuffer
import java.nio.file.Path

/** 개인 명함·편지. 클라이언트가 보낸 이름/권한·시각은 저장 근거로 쓰지 않는다. 작성: Peppone Choi. */
class GameCommunication(private val presence:GamePresence,private val generation:()->Long,
    private val directory:(Long,Int)->MailIdentity?,private val inTactics:(String)->Boolean,
    private val store:(Long)->CommunicationStore,private val now:()->Long=System::currentTimeMillis,
    /** 命令/提案 rows of the world state (design-proposals-orders.md). */
    private val proposals:(Long)->ProposalState?={null},
    /** Current rank (promotion, accession) over the character file's creation rank; null keeps the file's. */
    private val rank:(Int)->Int?={null}):AutoCloseable {
    private var current:Triple<Long,CommunicationStore,Communication>?=null
    private var messenger:Pair<Long,GameMessenger>?=null
    private data class Offer(val from:String,val to:String,val expires:Long)
    private val offers=mutableMapOf<Triple<Long,Int,Int>,Offer>()
    private val views=mutableMapOf<String,Boolean>()
    private val executor=java.util.concurrent.Executors.newSingleThreadExecutor {task->
        Thread(task,"logh7-personal-mail").apply {isDaemon=true}
    }
    /** The personal inbox when it holds unread mail, sent at strategy entry: the CD never queries a closed mailbox,
     * and the compatibility DLL marks the HUD from this list (user decision 2026-11-01). */
    fun unreadInbox(owner:MailIdentity,expected:Long)=java.util.concurrent.CompletableFuture.supplyAsync({
        runCatching {
            require(directory(expected,owner.character)==owner)
            val engine=engine(expected)
            engine.synchronize(owner)
            if(engine.list(owner,true).none {!it.read})emptyList() else inbox(engine,owner,true)
        }.getOrDefault(emptyList())
    },executor)
    /** U-25 defection: record the new side and clear the address book, on the mail executor. */
    fun defect(owner:MailIdentity,expected:Long)=java.util.concurrent.CompletableFuture.runAsync({
        require(directory(expected,owner.character)==owner);engine(expected).synchronize(owner)
    },executor)
    /** A server-written notice from [sender] to [recipient] (U-10 謀議 refusal), on the mail executor. */
    fun notice(sender:MailIdentity,recipient:MailIdentity,subject:String,body:String,expected:Long,seconds:Long)=
        java.util.concurrent.CompletableFuture.runAsync({runCatching {engine(expected).send(sender,recipient,subject,body,seconds,
            "coup-${sender.character}-${recipient.character}-$seconds")}},executor)
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
    /** An office address is the holder's peer with the card number in native +120. */
    private fun peer(p:MailIdentity,post:Int)=peer(p).let {if(post==0)it else it.copy(extra=listOf(it.extra[0],it.extra[1],post))}
    private fun item(p:StoredMail)=Wire.Item(p.id,-1,p.read,peer(p.sender,p.senderPost),peer(p.recipient,p.recipientPost),p.seconds,p.subject,p.body)
    /** The card number of an office address; a proposal row's address carries an unset value instead (36173 observed). */
    private fun post(p:Wire.Peer)=p.extra[2].takeIf {it in 0..260} ?: -1
    private fun holds(member:GamePresence.Member,post:Int)=post==0 || member.player.country!=null && member.offices.any {it.kind==post}
    private fun member(connection:String,owner:MailIdentity,expected:Long):GamePresence.Member {
        require(expected==generation() && directory(expected,owner.character)==owner)
        return checkNotNull(presence.communicationMember(connection)).also {
            require(it.player.generation==expected && it.character.id.toInt()==owner.character && it.player.country==owner.country)
        }
    }
    private fun books(engine:Communication,owner:MailIdentity)=Wire.addresses(engine.addressBook(owner).map(::peer))
    private fun inbox(engine:Communication,owner:MailIdentity,received:Boolean,all:Boolean=false,post:Int=0):List<ByteArray> {
        val query=Wire.Query(peer(owner,post),all,received)
        // The CD cache has 100 slots immediately before its next object. The
        // manual's 120 remain stored; deleting visible mail reveals older rows.
        return listOf(Wire.begin(query))+engine.list(owner,received,post).take(100).map {Wire.item(item(it))}+Wire.end()
    }
    private companion object {const val REPLY_ROWS=true}
    private fun officePost(post:Int)=post>0 && OrganizationOffices.of(post)!=null
    private fun pendingRecipient(mail:ProposalMail,character:Int,country:CountryId)=
        mail.pending && mail.recipient==character && mail.originCountry==country

    internal fun pendingOfficeMailCount(state:ProposalState,player:Navigator,posts:Collection<Int>,seconds:Long):Int {
        val country=player.country ?: return 0
        val held=posts.filter(::officePost).toSet()
        return state.mails.count {it.card in held && it.expiresAt>seconds && pendingRecipient(it,player.characterId,country)}
    }
    /** The row's command field picks the command-name table (constmsg group 18), so it is the manual command number. */
    private fun commandNumber(mail:ProposalMail)=when(mail.command) {ProposalActions.RESIGNATION -> 7;ProposalActions.DISMISSAL -> 6;ProposalActions.APPOINTMENT -> 5;ProposalActions.DEMOTION -> 2;else -> 0}
    /** The native pair that commits a 0x0f13/0x0f14 row; its time must be 0 so it runs before 0x0f09 (004b8950). */
    private fun paired(mail:ProposalMail,expected:Long)=when(mail.command) {
        ProposalActions.RESIGNATION -> org.logh7.protocol.ResignationMessages.response(
            org.logh7.protocol.ResignationMessages.Query(mail.recipient,mail.card),0,0,(ProposalActions.costs.getValue(mail.command)/2).toInt(),true)
        ProposalActions.DISMISSAL -> org.logh7.protocol.DismissalMessages.response(
            org.logh7.protocol.DismissalMessages.Query(mail.recipient,mail.target,mail.targetCard),0,0,(ProposalActions.costs.getValue(mail.command)/2).toInt(),true)
        // Appointment: changed=false keeps target 0 so 004c5580 appends no card; +0x14 still names the card.
        ProposalActions.APPOINTMENT -> org.logh7.protocol.AppointmentMessages.response(
            org.logh7.protocol.AppointmentMessages.Query(mail.recipient,mail.target,mail.targetCard,0,0),0,0,(ProposalActions.costs.getValue(mail.command)/2).toInt(),true,false)
        ProposalActions.DEMOTION -> org.logh7.protocol.RankDownMessages.mailPair(mail.recipient,mail.target,
            (mail.demotionFromRank ?: rank(mail.target) ?: directory(expected,mail.target)?.rank ?: 20).coerceIn(0,20),
            (ProposalActions.costs.getValue(mail.command)/2).toInt(),0)
        else -> error("Unsupported proposal command")
    }
    /** Office rows: received pending 0x0f13, replies to own proposals as 0x0f14 (reply+1 picks 22/78..81), sent pending 0x0f13. */
    private fun proposalRows(expected:Long,owner:MailIdentity,post:Int,received:Boolean):List<ByteArray> {
        // Only office cards own proposal rows; a peerage or fief card row crashed the CD's row builder (0053c933).
        if(!officePost(post))return emptyList()
        val state=proposals(expected) ?: return emptyList()
        val membership=owner.country ?: return emptyList()
        fun ranked(p:MailIdentity)=rank(p.character)?.let {p.copy(rank=it)} ?: p
        fun who(id:Int,card:Int)=directory(expected,id)?.let {peer(ranked(it),card)} ?: Wire.Peer(id,extra=listOf(0,0,card))
        val self=peer(ranked(owner),post)
        val rows=mutableListOf<ByteArray>()
        for(mail in state.mails.sortedBy {it.id}) {
            if(mail.originCountry!=membership)continue
            val type=if(mail.order)1 else 0
            if(received && mail.card==post && pendingRecipient(mail,owner.character,membership))
                rows+=listOf(Wire.suggest(Wire.Suggest(mail.id,0,0,who(mail.sender,mail.senderCard),self,mail.createdAt,
                    commandNumber(mail),type,1,mail.pcp,mail.mcp)),paired(mail,expected))
            // Reply rows need the compatibility DLL's 0053c933 null fix (proposal_approval.h). +0x25b 0 shows 22/77 承諾,
            // 1..3 show 22/78..80 (拒否·拒絶·オウム返し).
            if(REPLY_ROWS && received && !mail.pending && mail.sender==owner.character && mail.senderCard==post)
                // status 1 = already read (no 22/76 pending-read text); approval 5 keeps 22/77 with the reply buttons off.
                rows+=listOf(Wire.reply(Wire.Reply(mail.id,mail.id,1,who(mail.recipient,mail.card),self,mail.createdAt,
                    commandNumber(mail),0,if(mail.reply==Proposals.APPROVE)5 else mail.reply+1)),paired(mail,expected))
            if(!received && mail.pending && mail.sender==owner.character && mail.senderCard==post)
                rows+=listOf(Wire.suggest(Wire.Suggest(mail.id,0,0,self,who(mail.recipient,mail.card),mail.createdAt,
                    commandNumber(mail),type,1,mail.pcp,mail.mcp)),paired(mail,expected))
        }
        return rows
    }
    private fun publish(expected:Long,owner:MailIdentity,messages:List<ByteArray>,except:String) {
        presence.communicationSend(expected,owner.character,messages,except)
    }
    private fun deliver(expected:Long,engine:Communication,mail:StoredMail,except:String) {
        if(mail.recipientPost==0) {
            publish(expected,mail.recipient,inbox(engine,mail.recipient,true),except)
            return
        }
        // The address may still name a former holder. Refresh only this country's current holders.
        for(member in presence.communicationMembers(expected)) {
            val owner=directory(expected,member.character.id.toInt()) ?: continue
            if(owner.country==null || owner.country!=mail.recipient.country || member.player.country!=owner.country || !holds(member,mail.recipientPost))continue
            engine.synchronize(owner)
            publish(expected,owner,inbox(engine,owner,true,post=mail.recipientPost),except)
        }
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
            val engine=engine(expected);engine.synchronize(owner)
            return listOf(books(engine,owner))
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
        engine.synchronize(owner)
        fun own(p:Wire.Peer) {require(p.id==owner.character && holds(actor,post(p))) {"Mail owner mismatch"}}
        return when(opcode) {
            0x0f08 -> {
                val query=Wire.query(body)
                require(query.owner.id==owner.character) {"Mail owner mismatch"}
                // Opening the CD mailbox queries the personal inbox/outbox and then every held card
                // address (+120 = card number). A card that is no longer held simply lists nothing.
                val post=post(query.owner)
                if(!holds(actor,post))return listOf(Wire.begin(query),Wire.end())
                if(post==0)views[connection]=query.inbox
                val listed=inbox(engine,owner,query.inbox,query.all,post)
                listed.dropLast(1)+proposalRows(expected,owner,post,query.inbox)+listed.last()
            }
            0x0f0b -> {
                val command=Wire.exchange(body)
                require(command.first.personal && command.second.personal)
                val first=checkNotNull(directory(expected,command.first.id))
                val second=checkNotNull(directory(expected,command.second.id))
                engine.synchronize(first);engine.synchronize(second)
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
                val command=Wire.item(body);own(command.sender)
                val senderPost=post(command.sender);val recipientPost=post(command.recipient)
                val recipient=directory(expected,command.recipient.id)
                recipient?.let(engine::synchronize)
                // Compatibility DLL puts a random 63-bit nonce in the existing
                // request ID/ref words. Legacy ID=0 requests keep per-call keys.
                // Account + character + generation bind the untrusted nonce.
                val key=if(command.id<=-2)"native:${owner.account}:${owner.character}:${command.id}:${command.reference}" else operation
                val result=engine.send(owner,recipient,command.subject,command.text,seconds,key,
                    recipient?.let {inTactics(it.account)} ?: false,senderPost,recipientPost)
                result.rejection?.let {return rejection(opcode,it)}
                val saved=checkNotNull(result.mail)
                if(result.changed) {
                    // CD opening queries both folders, so the last query is
                    // not the visible tab. Delivery always changes the inbox.
                    deliver(expected,engine,saved,connection)
                }
                // Echo the nonce's low word only in the command ACK. Stored
                // inbox/outbox rows keep the ordinary reference=-1 value.
                listOf(Wire.item(item(saved).copy(reference=command.reference),0x0f10))+
                    inbox(engine,owner,if(senderPost==0)views[connection] ?: true else false,post=senderPost)
            }
            0x0f11,0x0f12 -> {
                val command=Wire.change(body,opcode)
                // Proposal/order rows (0x0f13) are not stored mail yet: acknowledge their read/delete marks only.
                // Their +120 is not filled in (36173 and in-range values were both seen), so anything but a held card is acknowledged.
                if(command.owner.id==owner.character && post(command.owner).let {it!=0 && !holds(actor,it)})return listOf(Wire.change(command,opcode))
                own(command.owner);val post=post(command.owner)
                val result=engine.change(owner,command.id,command.inbox,opcode==0x0f12,post)
                result.rejection?.let {return rejection(opcode,it)}
                listOf(Wire.change(command.copy(owner=peer(owner,post)),opcode))+inbox(engine,owner,command.inbox,post=post)
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

internal fun communicationIdentity(characters:CharacterCreation,id:Int,snapshot:WorldSnapshot?=null):MailIdentity? {
    val entry=WorldCharacters.profiles(characters.profiles(),snapshot).entries.singleOrNull {it.value.character.id==id.toLong()} ?: return null
    val account=entry.key;val profile=entry.value;val character=characters.characterById(id) ?: return null
    val name=(character.surname+" "+character.givenName).trim().take(12)
    return MailIdentity(account,id,profile.country,name,character.origin,character.rank,
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
