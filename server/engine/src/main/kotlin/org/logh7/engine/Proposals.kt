package org.logh7.engine

/**
 * 命令/提案 mail (CD manual p.28, design-proposals-orders.md, approved 2026-11-01).
 * [card] is the recipient's office card whose mailbox shows the row, [command] the native command opcode
 * paired with the 0x0f13 row. [reply] -1 waits; 0 拒否, 1 拒絶, 2 オウム返し (the CD's three buttons) and
 * 3 承諾 (the compatibility DLL's approval button). [pcp]/[mcp] are the sender's half of the command cost.
 * Author: Peppone Choi.
 */
data class ProposalMail(val id:Int,val sender:Int,val recipient:Int,val senderCard:Int,val card:Int,val command:Int,
    val order:Boolean,val createdAt:Long,val expiresAt:Long,val reply:Int=-1,val pcp:Int=0,val mcp:Int=0) {
    init {require(id>0 && sender>0 && recipient>0 && sender!=recipient && senderCard in 0..260 && card in 1..260 &&
        command in 0..0xffff && createdAt in 0..expiresAt && expiresAt<=0xffffffffL && reply in -1..3 && pcp>=0 && mcp>=0)}
    val pending get()=reply<0
}
/** [blocked] keys are (sender,recipient,command) refused with 拒絶, valued by the game second the refusal ends. */
data class ProposalState(val mails:List<ProposalMail> = emptyList(),val blocked:Map<Triple<Int,Int,Int>,Long> = emptyMap()) {
    init {require(mails.size<=4096 && mails.distinctBy {it.id}.size==mails.size && blocked.size<=4096 &&
        blocked.values.all {it in 0..0xffffffffL})}
    fun detached()=copy(mails=java.util.Collections.unmodifiableList(ArrayList(mails)),
        blocked=java.util.Collections.unmodifiableMap(LinkedHashMap(blocked)))
}
enum class ProposalRejection { STALE, NOT_RECIPIENT, REPLIED, BLOCKED, INFLUENCE, MAXIMUM, VALUE }

object Proposals {
    /** Twenty-four game hours (approved design §2.2). */
    const val LIFETIME=86400L
    const val APPROVE=3
    const val REFUSE=1
    /** Creates a pending row; the caller has checked range, cards and paid the sender's half. */
    fun open(state:ProposalState,id:Int,sender:Int,recipient:Int,senderCard:Int,card:Int,command:Int,order:Boolean,
        now:Long,pcp:Int,mcp:Int):Pair<ProposalState,ProposalRejection?> {
        if((state.blocked[Triple(sender,recipient,command)] ?: 0)>now)return state to ProposalRejection.BLOCKED
        val live=state.mails.filter {it.expiresAt>now || !it.pending}
        if(live.size>=4096 || now>0xffffffffL-LIFETIME)return state to ProposalRejection.MAXIMUM
        val mail=ProposalMail(id,sender,recipient,senderCard,card,command,order,now,now+LIFETIME,pcp=pcp,mcp=mcp)
        return state.copy(mails=live+mail,blocked=state.blocked.filterValues {it>now}) to null
    }
    /** The recipient answers a pending row once. 拒絶 also blocks the same command from the same sender for a day. */
    fun reply(state:ProposalState,id:Int,recipient:Int,value:Int,now:Long):Pair<ProposalState,ProposalRejection?> {
        if(value !in 0..3)return state to ProposalRejection.VALUE
        val mail=state.mails.singleOrNull {it.id==id} ?: return state to ProposalRejection.STALE
        if(mail.recipient!=recipient)return state to ProposalRejection.NOT_RECIPIENT
        if(!mail.pending)return state to ProposalRejection.REPLIED
        if(mail.expiresAt<=now)return state to ProposalRejection.STALE
        val blocked=if(value==REFUSE)state.blocked+(Triple(mail.sender,mail.recipient,mail.command) to now+LIFETIME) else state.blocked
        return state.copy(mails=state.mails.map {if(it.id==id)it.copy(reply=value) else it},blocked=blocked) to null
    }
    /** Pending rows past their lifetime; the caller refunds each sender's half and then drops them. */
    fun expired(state:ProposalState,now:Long)=state.mails.filter {it.pending && it.expiresAt<=now}
    fun withoutExpired(state:ProposalState,now:Long)=state.copy(mails=state.mails.filterNot {it.pending && it.expiresAt<=now})
}
