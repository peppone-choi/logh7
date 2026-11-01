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
    val costs=mapOf(RESIGNATION to 80L)

    /** U-16 (user decision 2026-11-01): an order to an absent recipient is approved for it one game hour after
     * arrival; a proposal waits for the recipient. */
    const val ABSENT_ORDER_SECONDS=3600L
    /** Before each proposal action: expired rows refund the sender's half, absent recipients' due orders are approved
     * (the recipient pays the other half and the command runs), all in the same commit as the action. */
    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>):SocialResult {
        if(p==null)return act(g,r,session,p,s,accounts)
        val now=session.gameMillis/1000
        var self:Navigator=p;val changed=linkedMapOf<String,Navigator>()
        fun current(key:String)=changed[key] ?: accounts.getValue(key)
        fun keyOf(id:Int)=accounts.entries.singleOrNull {it.value.generation==p.generation && it.value.characterId==id}?.key
        var settled=s
        for(mail in Proposals.expired(s.social.proposals,now)) {
            if(mail.sender==p.characterId) {self=self.copy(mcp=Math.addExact(self.mcp,mail.mcp));continue}
            val key=keyOf(mail.sender) ?: continue
            changed[key]=current(key).let {it.copy(mcp=Math.addExact(it.mcp,mail.mcp))}
        }
        settled=settled.copy(social=settled.social.copy(proposals=Proposals.withoutExpired(settled.social.proposals,now)))
        val policy=OrganizationalOfficePolicy(g)
        for(mail in settled.social.proposals.mails.filter {it.pending && it.order && it.createdAt+ABSENT_ORDER_SECONDS<=now}) {
            val key=keyOf(mail.recipient) ?: continue
            if(session.participants[key]?.online==true || mail.recipient==p.characterId)continue
            val recipient=current(key)
            val paid=CpPayment.charge(CpBalance(recipient.pcp.toLong(),recipient.mcp.toLong()),costs.getValue(mail.command)-mail.mcp,CpPool.MCP)
            if(paid !is PaymentResult.Paid)continue
            val held=policy.holders(settled,mail.card).filter {it.character==mail.recipient}
            if(held.size!=1)continue
            val (answered,refused)=Proposals.reply(settled.social.proposals,mail.id,mail.recipient,Proposals.APPROVE,now)
            if(refused!=null)continue
            settled=policy.set(settled.copy(social=settled.social.copy(proposals=answered)),held.single(),remove=true)
            changed[key]=recipient.paidWith(paid.balance)
        }
        if(settled==s && self==p)return act(g,r,session,p,s,accounts)
        val result=act(g,r,session,self,settled,accounts+changed)
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
            when(mail.command) {
                RESIGNATION -> {
                    val held=policy.holders(next,mail.card).filter {it.character==i.characterId}
                    if(held.size!=1)return reject(SocialRejection.CARD)
                    next=policy.set(next,held.single(),remove=true)
                }
            }
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
        val half=cost/2
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),half,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        if(s.social.nextId==Int.MAX_VALUE)return reject(SocialRejection.MAXIMUM)
        val (opened,refused)=Proposals.open(proposals,s.social.nextId,i.characterId,i.target,i.card,i.argument,i.value,order,now,0,half.toInt())
        if(refused!=null)return reject(if(refused==ProposalRejection.BLOCKED)SocialRejection.COOLDOWN else SocialRejection.MAXIMUM)
        val next=s.copy(social=s.social.copy(nextId=s.social.nextId+1,proposals=opened),
            strategic=s.strategic.copy(people=people+(i.characterId to sender.copy(influence=sender.influence-1))))
        return SocialResult(p.paidWith(paid.balance),next,changed=true)
    }
}
