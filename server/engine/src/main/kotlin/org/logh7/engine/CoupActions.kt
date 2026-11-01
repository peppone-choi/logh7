package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-10 クーデター (design-coup.md, approved 2026-11-01): 叛意 88, 謀議 89, 説得 90, 叛乱 91, 参加 92 (personal card,
 * PCP) and 査閲 72 (police/intelligence cards, MCP) over 7f00. CP costs are manual p.70/74; who may plot, the odds,
 * the loyalty step and what a revolt turns over are evidence:guess. Author: Peppone Choi.
 */
data class Plot(val leader:Int,val power:Int,val since:Long,val consenting:Set<Int> = emptySet(),
    /** Participants in joining order (the first becomes 副盟主 when the revolt starts). */
    val members:List<Int> = emptyList(),
    /** 0 while plotting; the game second the revolt broke out. */
    val revolt:Long=0,
    /** Bases turned to the rebel camp by the revolt. */
    val bases:Set<Int> = emptySet()) {
    init {require(leader>0 && power in 2..3 && since in 0..0xffffffffL && revolt in 0..0xffffffffL &&
        consenting.all {it>0 && it!=leader} && members.all {it>0 && it!=leader && it !in consenting} &&
        members.distinct().size==members.size && consenting.size+members.size<=256 &&
        bases.all {it in 1..65535} && (revolt>0 || bases.isEmpty()))}
    val rebels get()=listOf(leader)+members
    fun involves(character:Int)=character==leader || character in consenting || character in members
}
data class CoupState(val plots:Map<Int,Plot> = emptyMap(),
    /** 叛乱忠誠度 per outfit, 0..100 (説得 raises it). */
    val loyalty:Map<Int,Int> = emptyMap()) {
    init {require(plots.size<=256 && plots.all {(k,v)->k==v.leader} && loyalty.size<=4096 &&
        loyalty.all {(k,v)->k>0 && v in 1..100})}
    fun plotOf(character:Int)=plots.values.singleOrNull {it.involves(character)}
    fun revolting(power:Int)=plots.values.any {it.power==power && it.revolt>0}
    fun detached()=copy(plots=java.util.Collections.unmodifiableMap(LinkedHashMap(plots)),
        loyalty=java.util.Collections.unmodifiableMap(LinkedHashMap(loyalty)))
}

object CoupActions {
    const val INSPECTION=72
    const val INTENT=88
    const val CONSPIRACY=89
    const val PERSUASION=90
    const val REVOLT=91
    const val JOIN=92
    val commands=setOf(INSPECTION,INTENT,CONSPIRACY,PERSUASION,REVOLT,JOIN)
    val costs=mapOf(INSPECTION to 160L,INTENT to 640L,CONSPIRACY to 640L,PERSUASION to 640L,REVOLT to 640L,JOIN to 160L)
    /** 准将 (7) or above among soldiers (rank numbers of OrganizationOffices). */
    val leaderRanks=2..7
    const val LOYALTY_STEP=10
    /** Outfits at least this loyal take their station base into the revolt. */
    const val LOYAL=50
    /** 盟主 / 副盟主 of the empire's and the alliance's rebels (CM group 3). */
    fun leaderCard(power:Int)=if(power==2)68 else 197
    fun deputyCard(power:Int)=if(power==2)69 else 198

    fun roll(actor:Int,target:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+target*7L+89).nextInt(100)
    /** 謀議: 50 + (leader − target influence)/2, 20..80. */
    fun persuasion(leaderInfluence:Int,targetInfluence:Int)=(50+(leaderInfluence-targetInfluence)/2).coerceIn(20,80)
    /** The rebel alias of a regular card, or null when the original catalogue has none. */
    fun alias(card:Int):Int? {
        val role=OrganizationOffices.of(card)?.takeIf {it.camp==0} ?: return null
        return OrganizationOffices.all.values.firstOrNull {it.camp==1 && it.canonical==role.canonical && it.power==role.power &&
            it.card !in setOf(68,69,197,198)}?.card
    }
    private fun coup(s:PlanetStocks,c:CoupState)=s.copy(social=s.social.copy(coup=c))

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val policy=OrganizationalOfficePolicy(g)
        if(if(i.command==INSPECTION)i.card !in Justice.searchCards || policy.held(s,i.characterId).none {it.card==i.card} else i.card!=0)
            return reject(SocialRejection.CARD)
        val own=if(p.power==Power.EMPIRE)2 else 3
        val c=s.social.coup;val mine=c.plotOf(i.characterId)
        val person=s.strategic.people[i.characterId]
        fun ability(id:Int,index:Int,fallback:Int=50)=s.strategic.people[id]?.abilities?.getOrNull(index)
            ?: i.identities.singleOrNull {it.id==id}?.abilities?.getOrNull(index) ?: fallback
        fun influence(id:Int)=s.strategic.people[id]?.influence ?: 50
        // Validate everything before charging.
        when(i.command) {
            INTENT -> {
                val rank=person?.rank ?: i.rank
                if(rank !in leaderRanks)return reject(SocialRejection.RANK)
                if(policy.held(s,i.characterId).none {OrganizationOffices.of(it.card)?.camp==0})return reject(SocialRejection.CARD)
                if(mine!=null)return reject(SocialRejection.BUSY)
            }
            CONSPIRACY -> {
                if(mine?.leader!=i.characterId)return reject(SocialRejection.STALE)
                val target=accounts.values.singleOrNull {it.characterId==i.target} ?: return reject(SocialRejection.TARGET)
                if(target.characterId==i.characterId || target.power!=p.power || target.baseId!=p.baseId || target.spotId!=p.spotId ||
                    p.baseId==0 || c.plotOf(target.characterId)!=null || s.social.justice.detained(target.characterId))
                    return reject(SocialRejection.TARGET)
            }
            PERSUASION -> if(mine==null || i.characterId !in mine.rebels)return reject(SocialRejection.STALE)
                else if(outfits(policy,s,i.characterId).isEmpty())return reject(SocialRejection.TARGET)
            REVOLT -> if(mine?.leader!=i.characterId || mine.revolt>0)return reject(SocialRejection.STALE)
                else if(p.baseId==0 || s.bases[p.baseId]?.let {it.power==own && it.camp==0}!=true)return reject(SocialRejection.LOCATION)
            JOIN -> if(mine==null || i.characterId !in mine.consenting)return reject(SocialRejection.STALE)
            INSPECTION -> if(p.baseId==0)return reject(SocialRejection.LOCATION)
        }
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),costs.getValue(i.command),
            if(i.command==INSPECTION)CpPool.MCP else CpPool.PCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        when(i.command) {
            INTENT -> return SocialResult(payer,coup(s,c.copy(plots=c.plots+(i.characterId to Plot(i.characterId,own,now)))),
                changed=true,outcome=1)
            CONSPIRACY -> {
                val plot=checkNotNull(mine)
                if(roll(i.characterId,i.target,now)>=persuasion(influence(i.characterId),influence(i.target)))
                    // Refused: the gateway tells the target who asked (user decision 2026-11-01).
                    return SocialResult(payer,s,changed=true,outcome=3,found=i.target)
                return SocialResult(payer,coup(s,c.copy(plots=c.plots+(plot.leader to plot.copy(consenting=plot.consenting+i.target)))),
                    changed=true,outcome=1,found=i.target)
            }
            JOIN -> {
                val plot=checkNotNull(mine)
                var next=coup(s,c.copy(plots=c.plots+(plot.leader to plot.copy(consenting=plot.consenting-i.characterId,
                    members=plot.members+i.characterId))))
                // Joining a revolt already under way turns the joiner over at once.
                if(plot.revolt>0)next=turnOver(g,next,listOf(i.characterId),plot.leader,setOf(p.baseId).filter {
                    next.bases[it]?.let {b->b.power==own && b.camp==0}==true }.toSet(),deputy=false)
                return SocialResult(payer,next,changed=true,outcome=1)
            }
            PERSUASION -> {
                val loyalty=LinkedHashMap(c.loyalty)
                for(o in outfits(policy,s,i.characterId))loyalty[o]=minOf(100,(loyalty[o] ?: 0)+LOYALTY_STEP)
                return SocialResult(payer,coup(s,c.copy(loyalty=loyalty)),changed=true,outcome=1,
                    found=outfits(policy,s,i.characterId).maxOf {loyalty.getValue(it)})
            }
            REVOLT -> {
                val plot=checkNotNull(mine)
                val stations=(plot.rebels.mapNotNull {id->accounts.values.singleOrNull {it.characterId==id}?.baseId} +
                    plot.rebels.flatMap {outfits(policy,s,it)}.filter {(c.loyalty[it] ?: 0)>=LOYAL}.mapNotNull {s.outfits[it]?.baseId})
                    .filter {it>0 && s.bases[it]?.let {b->b.power==own && b.camp==0}==true}.toSet()+p.baseId
                val next=turnOver(g,coup(s,c.copy(plots=c.plots+(plot.leader to plot.copy(revolt=maxOf(now,1L))))),plot.rebels,plot.leader,stations,deputy=true)
                return SocialResult(payer,next,changed=true,outcome=1,found=stations.size)
            }
            INSPECTION -> {
                // Finds one plot of this side (the oldest first); a revolt is no longer a sign but a fact.
                val suspect=c.plots.values.filter {it.power==own && it.revolt==0L}.minByOrNull {it.since}
                    ?: return SocialResult(payer,s,changed=true,outcome=3)
                return if(roll(i.characterId,suspect.leader,now)<Justice.arrestThreshold(ability(i.characterId,3),ability(suspect.leader,3)))
                    SocialResult(payer,s,changed=true,outcome=1,found=suspect.leader)
                else SocialResult(payer,s,changed=true,outcome=3)
            }
        }
        return reject(SocialRejection.COMMAND)
    }

    /** Outfits the character serves in through an outfit card. */
    fun outfits(policy:OrganizationalOfficePolicy,s:PlanetStocks,character:Int)=policy.held(s,character)
        .filter {OrganizationOffices.of(it.card)?.scope==OfficeScope.OUTFIT && it.seat in s.outfits}.map {it.seat}.distinct()

    /** Turns [rebels] over: their regular cards go, [stations] join the rebel camp, cards with a rebel alias come back
     * under it, and the leader takes 盟主 (the first member 副盟主) at the leader's revolt base. */
    private fun turnOver(g:Galaxy,start:PlanetStocks,rebels:List<Int>,leader:Int,stations:Set<Int>,deputy:Boolean):PlanetStocks {
        val policy=OrganizationalOfficePolicy(g);var s=start
        val plot=checkNotNull(s.social.coup.plots[leader])
        val regular=rebels.flatMap {policy.held(s,it)}.filter {OrganizationOffices.of(it.card)?.camp==0}
        for(h in regular)s=policy.set(s,h,remove=true)
        for(b in stations)s=s.copy(bases=s.bases+(b to s.bases.getValue(b).copy(camp=1)))
        val bases=plot.bases+stations
        val seat=bases.firstOrNull {s.bases[it]?.camp==1} ?: return s.copy(social=s.social.copy(coup=s.social.coup.copy(
            plots=s.social.coup.plots+(leader to plot.copy(bases=bases)))))
        for(h in regular) {
            val card=alias(h.card) ?: continue;val role=checkNotNull(OrganizationOffices.of(card))
            val at=if(role.scope in setOf(OfficeScope.NATIONAL,OfficeScope.CAPITAL))seat else h.seat
            if(policy.active(s,role,at) && policy.holders(s,card).count {it.seat==at}<role.seats)
                s=policy.set(s,OfficeHolding(card,at,h.character))
        }
        if(deputy) {
            s=policy.set(s,OfficeHolding(leaderCard(plot.power),seat,leader))
            plot.members.firstOrNull()?.let {s=policy.set(s,OfficeHolding(deputyCard(plot.power),seat,it))}
        }
        return s.copy(social=s.social.copy(coup=s.social.coup.copy(plots=s.social.coup.plots+(leader to plot.copy(bases=bases)))))
    }

    /** A plot ends when its leader is detained; a revolt also ends when it holds no rebel base. The rebels lose their
     * rebel cards and the bases still in the rebel camp return to the regular camp. */
    fun settle(s:PlanetStocks):PlanetStocks {
        val c=s.social.coup
        val ended=c.plots.values.filter {plot->s.social.justice.detained(plot.leader) ||
            plot.revolt>0 && plot.bases.none {s.bases[it]?.let {b->b.camp==1 && b.power==plot.power}==true}}
        if(ended.isEmpty())return s
        var next=s
        for(plot in ended) {
            val rebels=plot.rebels.toSet()
            next=next.copy(strategic=next.strategic.copy(offices=next.strategic.offices.filterNot {
                it.character in rebels && OrganizationOffices.of(it.card)?.camp==1}))
            for(b in plot.bases)next.bases[b]?.takeIf {it.camp==1 && it.power==plot.power}?.let {
                next=next.copy(bases=next.bases+(b to it.copy(camp=0)))}
        }
        return next.copy(social=next.social.copy(coup=next.social.coup.copy(plots=next.social.coup.plots-ended.map {it.leader}.toSet())))
    }
}
