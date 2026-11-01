package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * 命令/提案 (CD manual p.28, design-proposals-orders.md approved 2026-11-01).
 * OPEN: [StrategyActionRequest.card] is the sender's card, target the recipient, argument the recipient's card and
 * value the paired native command; the sender pays half the command's MCP and one influence. REPLY: target is the
 * mail id and value the answer (0 拒否, 1 拒絶, 2 オウム返し, 3 承諾); approval makes the recipient pay the other
 * half and executes the command. First command: 0x0709 resignation of the recipient's card (MCP 80).
 * Author: Peppone Choi.
 */
object ProposalActions {
    const val OPEN=98
    const val REPLY=99
    val commands=setOf(OPEN,REPLY)
    const val RESIGNATION=0x0709
    /** Dismissal of a third person the recipient may dismiss (0x0708, MCP 160). */
    const val DISMISSAL=0x0708
    val costs=mapOf(RESIGNATION to 80L,DISMISSAL to 160L)
    /** Runs an approved command for its recipient; false when it can no longer be done. */
    private fun execute(g:Galaxy,mail:ProposalMail,s:PlanetStocks,power:Int,rank:Int):PlanetStocks? {
        val policy=OrganizationalOfficePolicy(g)
        return when(mail.command) {
            RESIGNATION -> policy.holders(s,mail.card).filter {it.character==mail.recipient}.singleOrNull()?.let {policy.set(s,it,remove=true)}
            DISMISSAL -> {
                val dismissal=OfficeDismissalPolicy(g)
                dismissal.available(s,mail.recipient,power,rank).singleOrNull {it.target==mail.target && it.card==mail.targetCard}
                    ?.let {dismissal.clear(s,it)}
            }
            else -> null
        }
    }

    /** U-16 (user decision 2026-11-01): an order to an absent recipient is approved for it one game hour after
     * arrival; a proposal waits for the recipient. */
    const val ABSENT_ORDER_SECONDS=3600L
    /** Expired rows refund the sender's half and absent recipients' due orders are approved (the recipient pays the
     * other half and the command runs). Runs in the periodic world tick and before each proposal action. */
    fun settle(g:Galaxy,session:SessionState,players:MutableMap<String,Navigator>,s:PlanetStocks):PlanetStocks {
        val now=session.gameMillis/1000
        fun keyOf(id:Int)=players.entries.singleOrNull {it.value.generation==session.generation && it.value.characterId==id}?.key
        var settled=s
        for(mail in Proposals.expired(s.social.proposals,now)) {
            val key=keyOf(mail.sender) ?: continue
            players[key]=players.getValue(key).let {it.copy(mcp=Math.addExact(it.mcp,mail.mcp))}
        }
        if(Proposals.expired(s.social.proposals,now).isNotEmpty())
            settled=settled.copy(social=settled.social.copy(proposals=Proposals.withoutExpired(settled.social.proposals,now)))
        val policy=OrganizationalOfficePolicy(g)
        for(mail in settled.social.proposals.mails.filter {it.pending && it.order && it.createdAt+ABSENT_ORDER_SECONDS<=now}) {
            val key=keyOf(mail.recipient) ?: continue
            if(session.participants[key]?.online==true)continue
            val recipient=players.getValue(key)
            val paid=CpPayment.charge(CpBalance(recipient.pcp.toLong(),recipient.mcp.toLong()),costs.getValue(mail.command)-mail.mcp,CpPool.MCP)
            if(paid !is PaymentResult.Paid)continue
            val (answered,refused)=Proposals.reply(settled.social.proposals,mail.id,mail.recipient,Proposals.APPROVE,now)
            if(refused!=null)continue
            val rank=(settled.strategic.people[mail.recipient] ?: StrategicPerson()).ladderRank(20)
            settled=execute(g,mail,settled.copy(social=settled.social.copy(proposals=answered)),
                if(recipient.power==Power.EMPIRE)2 else 3,rank) ?: continue
            players[key]=recipient.paidWith(paid.balance)
        }
        return settled
    }
    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>):SocialResult {
        if(p==null)return act(g,r,session,p,s,accounts)
        val players=LinkedHashMap(accounts).also {it[r.identity.account]=p}
        val settled=settle(g,session,players,s)
        val self=players.getValue(r.identity.account)
        val changed=players.filter {(k,v)->k!=r.identity.account && accounts[k]!=v}
        if(settled==s && self==p)return act(g,r,session,p,s,accounts)
        val result=act(g,r,session,self,settled,players)
        return if(result.rejection!=null)SocialResult(p,s,result.rejection) else result.copy(navigators=changed+result.navigators)
    }
    private fun act(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null)return reject(SocialRejection.BUSY)
        val policy=OrganizationalOfficePolicy(g)
        val proposals=s.social.proposals
        if(i.command==REPLY) {
            val mail=proposals.mails.singleOrNull {it.id==i.target} ?: return reject(SocialRejection.STALE)
            val (answered,refused)=Proposals.reply(proposals,i.target,i.characterId,i.value,now)
            if(refused!=null)return reject(if(refused==ProposalRejection.NOT_RECIPIENT)SocialRejection.IDENTITY else SocialRejection.STALE)
            var next=s.copy(social=s.social.copy(proposals=answered))
            if(i.value!=Proposals.APPROVE)return SocialResult(p,next,changed=true)
            val cost=costs.getValue(mail.command)-mail.mcp
            val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.MCP)
            if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
            next=execute(g,mail,next,if(p.power==Power.EMPIRE)2 else 3,
                (s.strategic.people[i.characterId] ?: StrategicPerson()).ladderRank(i.rank)) ?: return reject(SocialRejection.CARD)
            return SocialResult(p.paidWith(paid.balance),next,changed=true)
        }
        if(i.command!=OPEN)return reject(SocialRejection.COMMAND)
        val cost=costs[i.value] ?: return reject(SocialRejection.COMMAND)
        if(i.target<=0 || i.target==i.characterId)return reject(SocialRejection.COMMAND)
        if(policy.held(s,i.characterId).none {it.card==i.card})return reject(SocialRejection.CARD)
        val recipient=accounts.values.singleOrNull {it.generation==i.generation && it.characterId==i.target}
            ?: return reject(SocialRejection.LOCATION)
        // CD p.28: not in tactics; the same spot inside a facility, otherwise the same grid.
        if(recipient.power!=p.power || recipient.cpPaused || recipient.grid!=p.grid ||
            p.spotId!=0 && (recipient.baseId!=p.baseId || recipient.spotId!=p.spotId || recipient.spotOwner!=p.spotOwner))
            return reject(SocialRejection.LOCATION)
        if(policy.held(s,i.target).none {it.card==i.argument})return reject(SocialRejection.CARD)
        val emperor=i.card==1
        if(!emperor && OrganizationOffices.of(i.card)?.institution.let {it==null || it!=OrganizationOffices.of(i.argument)?.institution})
            return reject(SocialRejection.CARD)
        val people=s.strategic.people
        val sender=people[i.characterId] ?: StrategicPerson(rank=i.rank)
        val senderRank=sender.ladderRank(i.rank);val recipientRank=(people[i.target] ?: StrategicPerson()).ladderRank(20)
        // 命令 goes down the ranks, 提案 up (a smaller rank number is senior); the emperor orders everyone.
        val order=emperor || senderRank<=recipientRank
        if(sender.influence<1)return reject(SocialRejection.RANK)
        // Dismissal: base = the third person, amount = that person's card; the recipient must be able to dismiss it.
        val target=if(i.value==DISMISSAL)i.base else 0;val targetCard=if(i.value==DISMISSAL)i.amount.toInt() else 0
        if(i.value==DISMISSAL && OfficeDismissalPolicy(g).available(s,i.target,if(p.power==Power.EMPIRE)2 else 3,recipientRank)
                .none {it.target==target && it.card==targetCard})return reject(SocialRejection.TARGET)
        val half=cost/2
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),half,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        if(s.social.nextId==Int.MAX_VALUE)return reject(SocialRejection.MAXIMUM)
        val (opened,refused)=Proposals.open(proposals,s.social.nextId,i.characterId,i.target,i.card,i.argument,i.value,order,now,0,half.toInt(),
            target,targetCard)
        if(refused!=null)return reject(if(refused==ProposalRejection.BLOCKED)SocialRejection.COOLDOWN else SocialRejection.MAXIMUM)
        val next=s.copy(social=s.social.copy(nextId=s.social.nextId+1,proposals=opened),
            strategic=s.strategic.copy(people=people+(i.characterId to sender.copy(influence=sender.influence-1))))
        return SocialResult(p.paidWith(paid.balance),next,changed=true)
    }
}
