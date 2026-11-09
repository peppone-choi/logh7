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
    /** Appointment of the sender itself to a vacancy the recipient may fill (0x0707, MCP 160; self-recommendation,
     * evidence:guess). */
    const val APPOINTMENT=0x0707
    val costs=mapOf(RESIGNATION to 80L,DISMISSAL to 160L,APPOINTMENT to 160L)
    /** (card, seat) vacancies [actor] may fill with [candidate]: the actor's appointment authority with a free seat,
     * the candidate's rank eligible, not already holding the card and under the 14-card limit. Old fleet fields excluded. */
    fun vacancies(g:Galaxy,s:PlanetStocks,actor:Int,power:Int,rank:Int,candidate:Int,candidateRank:Int,
        countries:CountryCatalog=CountryCatalogLoader.current()):List<Pair<Int,Int>> {
        if(power !in countries.countries)return emptyList()
        val country=CountryId(power);val policy=OrganizationalOfficePolicy(g,countries)
        if(candidate==actor || BattleDeath.deceased(s,actor) || BattleDeath.deceased(s,candidate) ||
            s.social.justice.detained(candidate) || policy.cardCount(s,candidate)>=14)return emptyList()
        val held=policy.held(s,candidate).filter {it.country==country}.map {it.card}.toSet()
        return policy.available(s,actor,power,rank).flatMap {(card,seats)->
            val role=checkNotNull(policy.role(card,country))
            if(FleetOffice.of(card)!=null || card in held || !role.eligible(rank,candidateRank))emptyList()
            else seats.filter {role.scope!=OfficeScope.OUTFIT || candidate !in s.outfits.getValue(it).members()}.map {card to it}
        }.sortedWith(compareBy({it.first},{it.second}))
    }
    /** Rank comparison follows the configured rank system, independently of office and display templates. */
    fun rank(s:PlanetStocks,character:Int,country:CountryId,fallback:Int,countries:CountryCatalog=CountryCatalogLoader.current()):Int {
        val family=countries.countries[country.value]?.let {countries.families.getValue(it.family)} ?: return fallback
        val person=s.strategic.people[character] ?: StrategicPerson()
        return if(family.ranks=="empire")person.ladderRank(fallback) else person.rank ?: fallback
    }
    /** Runs an approved command only while its country, current people and post still permit it. */
    private fun execute(g:Galaxy,mail:ProposalMail,s:PlanetStocks,rank:Int,players:Map<String,Navigator>,
        countries:CountryCatalog):PlanetStocks? {
        val country=mail.originCountry?.takeIf {it.value in countries.countries} ?: return null
        fun belongs(id:Int)=!BattleDeath.deceased(s,id) && players.values.singleOrNull {it.generation==s.generation && it.characterId==id}?.country==country
        if(!belongs(mail.sender) || !belongs(mail.recipient) || mail.target>0 && !belongs(mail.target))return null
        val policy=OrganizationalOfficePolicy(g,countries)
        if(policy.holders(s,mail.card,country).none {it.character==mail.recipient})return null
        return when(mail.command) {
            RESIGNATION -> policy.holders(s,mail.card,country).filter {it.character==mail.recipient}.singleOrNull()
                ?.let {OfficeResignation.clear(policy,s,it)}
            DISMISSAL -> {
                val dismissal=OfficeDismissalPolicy(g,countries)
                dismissal.available(s,mail.recipient,country.value,rank).singleOrNull {it.target==mail.target && it.card==mail.targetCard}
                    ?.let {dismissal.clear(s,it)}
            }
            APPOINTMENT -> {
                val candidateRank=this.rank(s,mail.target,country,20,countries)
                if(mail.target==mail.sender && vacancies(g,s,mail.recipient,country.value,rank,mail.target,candidateRank,countries)
                        .contains(mail.targetCard to mail.seat))
                    policy.set(s,OfficeHolding(mail.targetCard,mail.seat,mail.target,country)) else null
            }
            else -> null
        }
    }

    /** U-16 (user decision 2026-11-01): an order to an absent recipient is approved for it one game hour after
     * arrival; a proposal waits for the recipient. */
    const val ABSENT_ORDER_SECONDS=3600L

    private fun newlyApproved(before: ProposalState, after: ProposalState): List<ProposalMail> {
        if (before == after) return emptyList()
        val pending = before.mails.filter { it.pending }.mapTo(hashSetOf()) { it.id }
        return after.mails.filter { it.id in pending && it.reply == Proposals.APPROVE }
    }

    internal fun approvedOfficeTargets(before: ProposalState, after: ProposalState): Set<Int> =
        newlyApproved(before, after).filter {
            it.target > 0 && (it.command == APPOINTMENT || it.command == DISMISSAL)
        }.mapTo(hashSetOf()) { it.target }

    internal fun approvedOfficeRemovals(before: ProposalState, after: ProposalState): Set<Int> =
        newlyApproved(before, after).mapNotNullTo(hashSetOf()) {
            when (it.command) {
                RESIGNATION -> it.recipient
                DISMISSAL -> it.target.takeIf { target -> target > 0 }
                else -> null
            }
        }
    /** Expired rows refund the sender's half and absent recipients' due orders are approved (the recipient pays the
     * other half and the command runs). Runs in the periodic world tick and before each proposal action. */
    fun settle(g:Galaxy,session:SessionState,players:MutableMap<String,Navigator>,s:PlanetStocks,
        countries:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        val now=session.gameMillis/1000
        fun keyOf(id:Int)=players.entries.singleOrNull {it.value.generation==session.generation && it.value.characterId==id}?.key
        var settled=s
        for(mail in Proposals.expired(s.social.proposals,now)) {
            val key=keyOf(mail.sender) ?: continue
            players[key]=players.getValue(key).let {it.copy(pcp=Math.addExact(it.pcp,mail.pcp),mcp=Math.addExact(it.mcp,mail.mcp))}
        }
        if(Proposals.expired(s.social.proposals,now).isNotEmpty())
            settled=settled.copy(social=settled.social.copy(proposals=Proposals.withoutExpired(settled.social.proposals,now)))
        val policy=OrganizationalOfficePolicy(g,countries)
        // Office mail belongs to the post (user decision 2026-11-01): a pending row whose recipient no longer holds the
        // card passes to the card's single current holder; a vacant or shared post keeps it until it expires.
        val moved=settled.social.proposals.mails.map {mail ->
            val country=mail.originCountry
            // Keep the original recipient when a death invalidates the command, so a successor cannot execute it.
            if(!mail.pending || country==null || BattleDeath.deceased(settled,mail.sender) ||
                BattleDeath.deceased(settled,mail.recipient) || BattleDeath.deceased(settled,mail.target))return@map mail
            fun currentHolder(id:Int)=!BattleDeath.deceased(settled,id) && (keyOf(id)?.let {players.getValue(it).country==country} ?: true)
            if(currentHolder(mail.recipient) &&
                policy.holders(settled,mail.card,country).any {it.character==mail.recipient})mail
            else policy.holders(settled,mail.card,country).map {it.character}.filter(::currentHolder).distinct().singleOrNull()
                ?.takeIf {it!=mail.sender}?.let {mail.copy(recipient=it)} ?: mail
        }
        if(moved!=settled.social.proposals.mails)
            settled=settled.copy(social=settled.social.copy(proposals=settled.social.proposals.copy(mails=moved)))
        for(mail in settled.social.proposals.mails.filter {it.pending && it.order && it.createdAt+ABSENT_ORDER_SECONDS<=now}) {
            val key=keyOf(mail.recipient) ?: continue
            if(session.participants[key]?.online==true)continue
            val recipient=players.getValue(key)
            val country=recipient.country?.takeIf {it==mail.originCountry && it.value in countries.countries} ?: continue
            if(recipient.cpPaused || recipient.navigationPending || settled.social.justice.detained(recipient.characterId))continue
            val cost=costs[mail.command]?.div(2) ?: continue
            val paid=CpPayment.charge(CpBalance(recipient.pcp.toLong(),recipient.mcp.toLong()),cost,CpPool.MCP)
            if(paid !is PaymentResult.Paid)continue
            val (answered,refused)=Proposals.reply(settled.social.proposals,mail.id,mail.recipient,Proposals.APPROVE,now)
            if(refused!=null)continue
            val rank=rank(settled,mail.recipient,country,20,countries)
            settled=execute(g,mail,settled.copy(social=settled.social.copy(proposals=answered)),rank,players,countries) ?: continue
            players[key]=recipient.paidWith(paid.balance)
        }
        return settled
    }
    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>,
        countries:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        if(p==null)return act(g,r,session,p,s,accounts,countries)
        val players=LinkedHashMap(accounts).also {it[r.identity.account]=p}
        val settled=settle(g,session,players,s,countries)
        val self=players.getValue(r.identity.account)
        val unchanged=settled==s && self==p
        val result=act(g,r,session,self,settled,if(unchanged)accounts else players,countries)
        if(result.rejection!=null)return if(unchanged)result else SocialResult(p,s,result.rejection)
        val targets=approvedOfficeTargets(s.social.proposals,checkNotNull(result.stocks).social.proposals)
        val changed=players.filter { (account, player) -> account!=r.identity.account &&
            (accounts[account]!=player || player.generation==s.generation && player.characterId in targets) }
        return result.copy(navigators=changed+result.navigators)
    }
    private fun act(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>,
        countries:CountryCatalog):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        if(BattleDeath.deceased(s,i.characterId))return reject(SocialRejection.IDENTITY)
        if(i.country.value !in countries.countries)return reject(SocialRejection.CARD)
        if(p.cpPaused || p.navigationPending)return reject(SocialRejection.BUSY)
        val policy=OrganizationalOfficePolicy(g,countries)
        val proposals=s.social.proposals
        if(i.command==REPLY) {
            val mail=proposals.mails.singleOrNull {it.id==i.target} ?: return reject(SocialRejection.STALE)
            if(mail.originCountry!=i.country)return reject(SocialRejection.IDENTITY)
            if(policy.holders(s,mail.card,i.country).none {it.character==i.characterId})return reject(SocialRejection.CARD)
            val (answered,refused)=Proposals.reply(proposals,i.target,i.characterId,i.value,now)
            if(refused!=null)return reject(if(refused==ProposalRejection.NOT_RECIPIENT)SocialRejection.IDENTITY else SocialRejection.STALE)
            var next=s.copy(social=s.social.copy(proposals=answered))
            if(i.value!=Proposals.APPROVE)return SocialResult(p,next,changed=true)
            val cost=costs[mail.command]?.div(2) ?: return reject(SocialRejection.COMMAND)
            val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),cost,CpPool.MCP)
            if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
            next=execute(g,mail,next,rank(s,i.characterId,i.country,i.rank,countries),accounts,countries)
                ?: return reject(SocialRejection.CARD)
            return SocialResult(p.paidWith(paid.balance),next,changed=true)
        }
        if(i.command!=OPEN)return reject(SocialRejection.COMMAND)
        val cost=costs[i.value] ?: return reject(SocialRejection.COMMAND)
        if(i.target<=0 || i.target==i.characterId)return reject(SocialRejection.COMMAND)
        if(BattleDeath.deceased(s,i.target))return reject(SocialRejection.TARGET)
        if(policy.holders(s,i.card,i.country).none {it.character==i.characterId})return reject(SocialRejection.CARD)
        val recipient=accounts.values.singleOrNull {it.generation==i.generation && it.characterId==i.target}
            ?: return reject(SocialRejection.LOCATION)
        // CD p.28: not in tactics; the same spot inside a facility, otherwise the same grid.
        if(recipient.country!=p.country || recipient.cpPaused || recipient.grid!=p.grid ||
            (p.spotId!=0 || recipient.spotId!=0) &&
                (recipient.baseId!=p.baseId || recipient.spotId!=p.spotId || recipient.spotOwner!=p.spotOwner))
            return reject(SocialRejection.LOCATION)
        if(policy.holders(s,i.argument,i.country).none {it.character==i.target})return reject(SocialRejection.CARD)
        val emperor=i.card==1
        if(!emperor && policy.role(i.card,i.country)?.institution.let {it==null || it!=policy.role(i.argument,i.country)?.institution})
            return reject(SocialRejection.CARD)
        val people=s.strategic.people
        val sender=people[i.characterId] ?: StrategicPerson(rank=i.rank)
        val senderRank=rank(s,i.characterId,i.country,i.rank,countries);val recipientRank=rank(s,i.target,i.country,20,countries)
        // 命令 goes down the ranks, 提案 up (a smaller rank number is senior); the emperor orders everyone.
        val order=emperor || senderRank<=recipientRank
        if(sender.influence<1)return reject(SocialRejection.RANK)
        // Dismissal: base = the third person, amount = that person's card; the recipient must be able to dismiss it.
        // Appointment: base = the seat, amount = the card; the sender asks the recipient to appoint the sender there.
        val target=when(i.value) {DISMISSAL -> i.base;APPOINTMENT -> i.characterId;else -> 0}
        val targetCard=if(i.value==DISMISSAL || i.value==APPOINTMENT)i.amount.toInt() else 0
        val seat=if(i.value==APPOINTMENT)i.base else 0
        if(target>0 && accounts.values.singleOrNull {it.generation==i.generation && it.characterId==target}?.country!=i.country)
            return reject(SocialRejection.TARGET)
        val power=i.country.value
        if(i.value==DISMISSAL && OfficeDismissalPolicy(g,countries).available(s,i.target,power,recipientRank)
                .none {it.target==target && it.card==targetCard})return reject(SocialRejection.TARGET)
        if(i.value==APPOINTMENT && (targetCard to seat) !in vacancies(g,s,i.target,power,recipientRank,i.characterId,senderRank,countries))
            return reject(SocialRejection.TARGET)
        val half=cost/2
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),half,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        if(s.social.nextId==Int.MAX_VALUE)return reject(SocialRejection.MAXIMUM)
        val (opened,refused)=Proposals.open(proposals,s.social.nextId,i.characterId,i.target,i.card,i.argument,i.value,order,now,
            (p.pcp-paid.balance.pcp).toInt(),(p.mcp-paid.balance.mcp).toInt(),target,targetCard,seat,i.country)
        if(refused!=null)return reject(if(refused==ProposalRejection.BLOCKED)SocialRejection.COOLDOWN else SocialRejection.MAXIMUM)
        val next=s.copy(social=s.social.copy(nextId=s.social.nextId+1,proposals=opened),
            strategic=s.strategic.copy(people=people+(i.characterId to sender.copy(influence=sender.influence-1))))
        return SocialResult(p.paidWith(paid.balance),next,changed=true)
    }
}
