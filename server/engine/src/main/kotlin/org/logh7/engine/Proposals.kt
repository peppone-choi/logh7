package org.logh7.engine

/**
 * 命令/提案 mail (CD manual p.28, design-proposals-orders.md, approved 2026-11-01).
 * [card] is the recipient's office card whose mailbox shows the row, [command] the native command opcode
 * paired with the 0x0f13 row. [reply] -1 waits; 0 拒否, 1 拒絶, 2 オウム返し (the CD's three buttons) and
 * 3 承諾 (the compatibility DLL's approval button). [pcp]/[mcp] record the sender's actual payment pools.
 * Author: Peppone Choi.
 */
data class ProposalMail(val id:Int,val sender:Int,val recipient:Int,val senderCard:Int,val card:Int,val command:Int,
    val order:Boolean,val createdAt:Long,val expiresAt:Long,val reply:Int=-1,val pcp:Int=0,val mcp:Int=0,
    /** A third person the command acts on (dismissal) and that person's card; 0 when the recipient itself is the subject. */
    val target:Int=0,val targetCard:Int=0,
    /** Appointment, dismissal or local resignation: the selected seat (base or outfit id); 0 if unbound. */
    val seat:Int=0,
    /** Null only in old records whose original 2/3 country is identified by the office card. */
    val country:CountryId?=null,
    /** Native mail rank: before demotion or after special promotion. Absent in earlier records. */
    val rankChangeMailRank:Int?=null,
    val assignment: StockAssignmentProposal? = null,
    val assignmentOriginalRecipient: Int = 0,
    val assignmentExpired: Boolean = false) {
    init {require(id>0 && sender>0 && recipient>0 && sender!=recipient && senderCard in 0..260 && card in 1..260 &&
        command in 0..0xffff && createdAt in 0..expiresAt && expiresAt<=0xffffffffL && reply in -1..3 && pcp>=0 && mcp>=0 &&
        target>=0 && targetCard in 0..260 && (if(ProposalActions.rankChange(command)) target>0 && targetCard==0 else (target==0)==(targetCard==0)) &&
        seat>=0 && (seat==0 || target>0 || command==ProposalActions.RESIGNATION && country!=null) &&
        (rankChangeMailRank==null || ProposalActions.rankChange(command) && reply==Proposals.APPROVE && rankChangeMailRank in 2..19) &&
        (if (assignment == null) command != ProposalActions.ASSIGNMENT && assignmentOriginalRecipient == 0 && !assignmentExpired
        else command == ProposalActions.ASSIGNMENT && assignmentOriginalRecipient > 0 && assignmentOriginalRecipient != sender &&
            country != null && target == 0 && targetCard == 0 && seat == 0 && (!assignmentExpired || reply < 0)))}
    val pending get()=reply<0 && !assignmentExpired
    val originCountry get()=country ?: OrganizationOffices.of(card)?.let {CountryId(it.power)}
}
/** [blocked] keys are (sender,recipient,command) refused with 拒絶, valued by the game second the refusal ends. */
data class ProposalState(val mails:List<ProposalMail> = emptyList(),val blocked:Map<Triple<Int,Int,Int>,Long> = emptyMap()) {
    init {
        val assignmentTokens = mails.mapNotNull { mail -> mail.assignment?.let { mail.sender to it.token } }
        require(assignmentTokens.distinct().size == assignmentTokens.size)
        require(mails.size<=4096 && mails.distinctBy {it.id}.size==mails.size && blocked.size<=4096 &&
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
        now:Long,pcp:Int,mcp:Int,target:Int=0,targetCard:Int=0,seat:Int=0,country:CountryId?=null,
        assignment: StockAssignmentProposal? = null):Pair<ProposalState,ProposalRejection?> {
        if((state.blocked[Triple(sender,recipient,command)] ?: 0)>now)return state to ProposalRejection.BLOCKED
        val live=state.mails.filter {it.expiresAt>now || !it.pending}
        if(live.size>=4096 || now>0xffffffffL-LIFETIME)return state to ProposalRejection.MAXIMUM
        val mail=ProposalMail(id,sender,recipient,senderCard,card,command,order,now,now+LIFETIME,pcp=pcp,mcp=mcp,
            target=target,targetCard=targetCard,seat=seat,country=country,assignment=assignment,
            assignmentOriginalRecipient=if(assignment==null)0 else recipient)
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
    fun withoutExpired(state:ProposalState,now:Long)=state.copy(mails=state.mails.mapNotNull {
        if(!it.pending || it.expiresAt>now)it else if(it.assignment==null)null else it.copy(assignmentExpired=true)
    })
}
