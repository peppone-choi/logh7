package org.logh7.engine

/** 개인 주소만 지원한다. 직무 주소·망명·건의 편지는 별도 연결 대상. 작성: Peppone Choi. */
data class MailIdentity(val account:String,val character:Int,val power:Int,val name:String,
    val origin:Int=0,val rank:Int=0,val title:String="",val face:Int=-1,val ageSeconds:Int=0) {
    init {
        require(account.matches(Regex("[A-Za-z0-9_.@-]{1,30}")) && character in 1..0x7fefffff && power in 2..3)
        require(name.isNotBlank() && name.length<=12 && title.length<=15 && origin in 0..65535 && rank in 0..65535)
        require((name+title).none {it.isSurrogate() || it.isISOControl()})
        require(face>=-1 && ageSeconds>=0)
    }
}
data class StoredMail(val id:Int,val sender:MailIdentity,val recipient:MailIdentity,val subject:String,val body:String,
    val seconds:Long,val operation:String,val read:Boolean=false,val inboxDeleted:Boolean=false,val sentDeleted:Boolean=false) {
    init {
        require(id>0 && seconds in 0..0xffffffffL && operation.length in 1..160)
        require(validMailText(subject,127) && validMailText(body,511))
    }
}
fun validMailText(text:String,maximum:Int)=text.length<=maximum && text.none {
    it.isSurrogate() || it=='\u0000' || it.isISOControl() && it!='\n' && it!='\r' && it!='\t'
}
data class CommunicationState(val generation:Long,val nextId:Int=1,
    val owners:Map<Int,MailIdentity> = emptyMap(),val addresses:Map<Int,Set<Int>> = emptyMap(),
    val messages:List<StoredMail> = emptyList()) {
    init {
        require(generation>0 && nextId>0 && owners.size<=4096 && messages.size<=100_000)
        require(owners.all {(id,p)->id==p.character} && owners.values.map {it.account}.distinct().size==owners.size)
        require(addresses.all {(id,peers)->id in owners && peers.size<=100 && id !in peers && peers.all {it in owners}})
        require(messages.map {it.id}.distinct().size==messages.size && messages.map {it.operation}.distinct().size==messages.size)
        require(messages.all {it.id<nextId && owners[it.sender.character]?.account==it.sender.account &&
            owners[it.recipient.character]?.account==it.recipient.account})
        require(owners.keys.all {owner->messages.count {it.recipient.character==owner && !it.inboxDeleted}<=120 &&
            messages.count {it.sender.character==owner && !it.sentDeleted}<=120})
    }
}
interface CommunicationStore:AutoCloseable {
    fun load():CommunicationState?
    /** 한 번의 저장으로 송신 보관·수신 원본·작업 키를 함께 확정한다. */
    fun save(state:CommunicationState)
    override fun close() {}
}
enum class MailRejection { STALE, IDENTITY, UNKNOWN_RECIPIENT, TEXT, TACTICS, INBOX_FULL, SENT_FULL, CAPACITY, NOT_OWNER, OPERATION_CONFLICT }
data class MailResult(val mail:StoredMail?=null,val rejection:MailRejection?=null,val changed:Boolean=false)

/** 저장 성공 뒤에만 상태를 공개한다. 모든 호출은 인증된 gateway 임대 안에서 실행한다. */
class Communication(private val generation:Long,private val store:CommunicationStore,
    private val currentGeneration:()->Long = {generation}) {
    private var state=store.load()?.also {require(it.generation==generation)} ?: CommunicationState(generation)
    private fun active()=generation==currentGeneration()
    private fun owned(owner:MailIdentity)=(state.owners[owner.character]?.let {it.account==owner.account && it.power==owner.power} ?: true) &&
        state.owners.values.none {it.account==owner.account && it.character!=owner.character}
    private fun remember(vararg identities:MailIdentity)=state.owners+identities.associateBy {it.character}
    private fun commit(next:CommunicationState) { check(active());store.save(next);state=next }
    @Synchronized fun addressBook(owner:MailIdentity):List<MailIdentity> {
        require(active() && owned(owner))
        return state.addresses[owner.character].orEmpty().sorted().map {state.owners.getValue(it)}
    }
    @Synchronized fun exchange(first:MailIdentity,second:MailIdentity):Boolean {
        require(active() && owned(first) && owned(second) && first.character!=second.character)
        val a=state.addresses[first.character].orEmpty();val b=state.addresses[second.character].orEmpty()
        if ((second.character !in a && a.size>=100) || (first.character !in b && b.size>=100)) return false
        if(remember(first,second).size>4096)return false
        val next=state.copy(owners=remember(first,second),addresses=state.addresses+
            (first.character to (a+second.character))+(second.character to (b+first.character)))
        if(next!=state)commit(next)
        return true
    }
    @Synchronized fun removeAddress(owner:MailIdentity,peer:Int) {
        require(active() && owned(owner))
        val addresses=state.addresses[owner.character].orEmpty()
        if(peer in addresses)commit(state.copy(addresses=state.addresses+(owner.character to (addresses-peer))))
    }
    @Synchronized fun list(owner:MailIdentity,inbox:Boolean):List<StoredMail> {
        require(active() && owned(owner))
        return state.messages.filter {if(inbox)it.recipient.character==owner.character && !it.inboxDeleted
            else it.sender.character==owner.character && !it.sentDeleted}.sortedByDescending {it.id}
    }
    @Synchronized fun send(sender:MailIdentity,recipient:MailIdentity?,subject:String,body:String,
        seconds:Long,operation:String,recipientInTactics:Boolean=false):MailResult {
        fun reject(reason:MailRejection)=MailResult(rejection=reason)
        if(!active())return reject(MailRejection.STALE)
        if(!owned(sender))return reject(MailRejection.IDENTITY)
        if(recipient==null || !owned(recipient))return reject(MailRejection.UNKNOWN_RECIPIENT)
        if(!validMailText(subject,127) || !validMailText(body,511))return reject(MailRejection.TEXT)
        state.messages.singleOrNull {it.operation==operation}?.let {previous->
            return if(previous.sender.account==sender.account && previous.sender.character==sender.character &&
                previous.recipient.character==recipient.character && previous.subject==subject && previous.body==body)
                MailResult(previous) else reject(MailRejection.OPERATION_CONFLICT)
        }
        if(recipientInTactics)return reject(MailRejection.TACTICS)
        if(state.messages.count {it.recipient.character==recipient.character && !it.inboxDeleted}>=120)return reject(MailRejection.INBOX_FULL)
        if(state.messages.count {it.sender.character==sender.character && !it.sentDeleted}>=120)return reject(MailRejection.SENT_FULL)
        if(state.messages.size>=100_000 || state.nextId==Int.MAX_VALUE || remember(sender,recipient).size>4096)
            return reject(MailRejection.CAPACITY)
        val mail=StoredMail(state.nextId,sender,recipient,subject,body,seconds,operation)
        commit(state.copy(nextId=mail.id+1,owners=remember(sender,recipient),messages=state.messages+mail))
        return MailResult(mail,changed=true)
    }
    @Synchronized fun change(owner:MailIdentity,id:Int,inbox:Boolean,delete:Boolean):MailResult {
        if(!active())return MailResult(rejection=MailRejection.STALE)
        if(!owned(owner))return MailResult(rejection=MailRejection.IDENTITY)
        val previous=state.messages.singleOrNull {it.id==id} ?: return MailResult(rejection=MailRejection.NOT_OWNER)
        if((if(inbox)previous.recipient else previous.sender).let {it.character!=owner.character || it.account!=owner.account})
            return MailResult(rejection=MailRejection.NOT_OWNER)
        val next=if(delete)if(inbox)previous.copy(inboxDeleted=true) else previous.copy(sentDeleted=true)
            else if(inbox && !previous.inboxDeleted)previous.copy(read=true) else previous
        if(next!=previous)commit(state.copy(messages=state.messages.map {if(it.id==id)next else it}))
        return MailResult(next,changed=next!=previous)
    }
}
