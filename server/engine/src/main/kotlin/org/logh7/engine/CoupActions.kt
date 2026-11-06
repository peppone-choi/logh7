package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-10 クーデター (design-coup.md, approved 2026-11-01): 叛意 88, 謀議 89, 説得 90, 叛乱 91, 参加 92 (personal card,
 * PCP) and 査閲 72 (police/intelligence cards, MCP) over 7f00. CP costs are manual p.70/74; who may plot, the odds,
 * the loyalty step and what a revolt turns over are evidence:guess. Author: Peppone Choi.
 */
data class Plot(val leader:Int,val country:CountryId,val since:Long,val consenting:Set<Int> = emptySet(),
    /** Participants in joining order (the first becomes 副盟主 when the revolt starts). */
    val members:List<Int> = emptyList(),
    /** 0 while plotting; the game second the revolt broke out. */
    val revolt:Long=0,
    /** Bases turned to the rebel camp by the revolt. */
    val bases:Set<Int> = emptySet(),
    /** Game second since the rebel camp has held its side's capital without a break; 0 while it does not. */
    val capitalSince:Long=0,
    /** Capital whose continuous occupation [capitalSince] measures; 0 in legacy records. */
    val capitalBase:Int=0) {
    init {require(leader>0 && since in 0..0xffffffffL && revolt in 0..0xffffffffL && capitalSince in 0..0xffffffffL &&
        consenting.all {it>0 && it!=leader} && members.all {it>0 && it!=leader && it !in consenting} &&
        members.distinct().size==members.size && consenting.size+members.size<=256 &&
        bases.all {it in 1..65535} && (revolt>0 || bases.isEmpty()) && capitalBase in 0..65535 &&
        (capitalBase==0 || capitalSince>0))}
    val rebels get()=listOf(leader)+members
    fun involves(character:Int)=character==leader || character in consenting || character in members
}
data class CoupState(val plots:Map<Int,Plot> = emptyMap(),
    /** 叛乱忠誠度 per outfit, 0..100 (説得 raises it). */
    val loyalty:Map<Int,Int> = emptyMap()) {
    init {require(plots.size<=256 && plots.all {(k,v)->k==v.leader} && loyalty.size<=4096 &&
        loyalty.all {(k,v)->k>0 && v in 1..100})}
    fun plotOf(character:Int)=plots.values.singleOrNull {it.involves(character)}
    fun revolting(country:CountryId)=plots.values.any {it.country==country && it.revolt>0}
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
    private fun leaderCard(system:Int)=if(system==2)68 else 197
    private fun deputyCard(system:Int)=if(system==2)69 else 198

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
    /** A revolt's rebels form their side's rebel camp (manual p.31: regular and rebel camps count apart). */
    fun rebel(s:PlanetStocks,character:Int,country:CountryId?)=country!=null && character>0 &&
        s.social.coup.plots.values.any {it.country==country && it.revolt>0 && character in it.rebels}
    fun camp(s:PlanetStocks,character:Int,country:CountryId?)=if(rebel(s,character,country))1 else 0
    /** An outfit follows its rebel commander once its 叛乱忠誠度 reaches [LOYAL]. */
    fun outfitCamp(s:PlanetStocks,o:OutfitStock)=if(rebel(s,o.commanderId,o.country) && (s.social.coup.loyalty[o.id] ?: 0)>=LOYAL)1 else 0

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>,
        countries:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        val own=i.country
        if(OrganizationOffices.system(own,countries)==null)return reject(SocialRejection.CARD)
        if(own in session.eliminated)return reject(SocialRejection.SESSION)
        if(p.cpPaused || p.navigationPending || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        val policy=OrganizationalOfficePolicy(g,countries)
        val held=policy.held(s,i.characterId).filter {it.country==own}
        if(if(i.command==INSPECTION)i.card !in Justice.searchCards || held.none {it.card==i.card} else i.card!=0)
            return reject(SocialRejection.CARD)
        val c=s.social.coup;val mine=c.plotOf(i.characterId)
        if(mine!=null && mine.country!=own)return reject(SocialRejection.STALE)
        val person=s.strategic.people[i.characterId]
        fun ability(id:Int,index:Int,fallback:Int=50)=s.strategic.people[id]?.abilities?.getOrNull(index)
            ?: i.identities.singleOrNull {it.id==id}?.abilities?.getOrNull(index) ?: fallback
        fun influence(id:Int)=s.strategic.people[id]?.influence ?: 50
        // Validate everything before charging.
        when(i.command) {
            INTENT -> {
                val rank=person?.rank ?: i.rank
                if(rank !in leaderRanks)return reject(SocialRejection.RANK)
                if(held.none {policy.role(it.card,own)?.camp==0})return reject(SocialRejection.CARD)
                if(mine!=null)return reject(SocialRejection.BUSY)
            }
            CONSPIRACY -> {
                if(mine?.leader!=i.characterId)return reject(SocialRejection.STALE)
                val target=accounts.values.singleOrNull {it.characterId==i.target} ?: return reject(SocialRejection.TARGET)
                if(target.generation!=i.generation || target.characterId==i.characterId || target.country!=own || target.baseId!=p.baseId || target.spotId!=p.spotId ||
                    p.baseId==0 || c.plotOf(target.characterId)!=null || s.social.justice.detained(target.characterId))
                    return reject(SocialRejection.TARGET)
            }
            PERSUASION -> if(mine==null || i.characterId !in mine.rebels)return reject(SocialRejection.STALE)
                else if(outfits(policy,s,i.characterId,own).isEmpty())return reject(SocialRejection.TARGET)
            REVOLT -> if(mine?.leader!=i.characterId || mine.revolt>0)return reject(SocialRejection.STALE)
                else if(p.baseId==0 || s.bases[p.baseId]?.let {it.belongsTo(own) && it.camp==0}!=true)return reject(SocialRejection.LOCATION)
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
                    next.bases[it]?.let {b->b.belongsTo(own) && b.camp==0}==true }.toSet(),deputy=false,countries=countries)
                return SocialResult(payer,next,changed=true,outcome=1)
            }
            PERSUASION -> {
                val loyalty=LinkedHashMap(c.loyalty)
                for(o in outfits(policy,s,i.characterId,own))loyalty[o]=minOf(100,(loyalty[o] ?: 0)+LOYALTY_STEP)
                return SocialResult(payer,coup(s,c.copy(loyalty=loyalty)),changed=true,outcome=1,
                    found=outfits(policy,s,i.characterId,own).maxOf {loyalty.getValue(it)})
            }
            REVOLT -> {
                val plot=checkNotNull(mine)
                val rebels=plot.rebels.filter {id->
                    val current=accounts.values.singleOrNull {it.characterId==id && it.generation==i.generation}
                    id==i.characterId || (if(current!=null)current.country else i.identities.singleOrNull {it.id==id}?.country)==own}
                val stations=(rebels.mapNotNull {id->accounts.values.singleOrNull {it.characterId==id && it.generation==i.generation && it.country==own}?.baseId} +
                    rebels.flatMap {outfits(policy,s,it,own)}.filter {(c.loyalty[it] ?: 0)>=LOYAL}.mapNotNull {s.outfits[it]?.baseId})
                    .filter {it>0 && s.bases[it]?.let {b->b.belongsTo(own) && b.camp==0}==true}.toSet()+p.baseId
                val next=turnOver(g,coup(s,c.copy(plots=c.plots+(plot.leader to plot.copy(revolt=maxOf(now,1L),
                    members=plot.members.filter {it in rebels})))),rebels,plot.leader,stations,deputy=true,countries=countries)
                return SocialResult(payer,next,changed=true,outcome=1,found=stations.size)
            }
            INSPECTION -> {
                // Finds one plot of this side (the oldest first); a revolt is no longer a sign but a fact.
                val suspect=c.plots.values.filter {it.country==own && it.revolt==0L}.minByOrNull {it.since}
                    ?: return SocialResult(payer,s,changed=true,outcome=3)
                return if(roll(i.characterId,suspect.leader,now)<Justice.arrestThreshold(ability(i.characterId,3),ability(suspect.leader,3)))
                    SocialResult(payer,s,changed=true,outcome=1,found=suspect.leader)
                else SocialResult(payer,s,changed=true,outcome=3)
            }
        }
        return reject(SocialRejection.COMMAND)
    }

    /** 継承拒否 (SuccessionActions): the runner-up rises at once as 盟主 of a one-person revolt holding [stations]. */
    internal fun contest(g:Galaxy,s:PlanetStocks,leader:Int,stations:Set<Int>,now:Long,country:CountryId=CountryId(2),
        catalog:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        val c=s.social.coup
        return turnOver(g,coup(s,c.copy(plots=c.plots+(leader to Plot(leader,country,now,revolt=maxOf(now,1L))))),listOf(leader),leader,stations,deputy=true,countries=catalog)
    }

    /** Outfits the character serves in through an outfit card. */
    private fun outfits(policy:OrganizationalOfficePolicy,s:PlanetStocks,character:Int,country:CountryId)=policy.held(s,character)
        .filter {it.country==country && policy.role(it.card,country)?.scope==OfficeScope.OUTFIT && s.outfits[it.seat]?.country==country}.map {it.seat}.distinct()

    /** Turns [rebels] over: their regular cards go, [stations] join the rebel camp, cards with a rebel alias come back
     * under it, and the leader takes 盟主 (the first member 副盟主) at the leader's revolt base. */
    private fun turnOver(g:Galaxy,start:PlanetStocks,rebels:List<Int>,leader:Int,stations:Set<Int>,deputy:Boolean,
        countries:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        val policy=OrganizationalOfficePolicy(g,countries);var s=start
        val plot=checkNotNull(s.social.coup.plots[leader])
        val system=OrganizationOffices.system(plot.country,countries) ?: return start
        val regular=rebels.flatMap {policy.held(s,it)}.filter {it.country==plot.country && policy.role(it.card,it.country)?.camp==0}
        for(h in regular)s=policy.set(s,h,remove=true)
        val ownStations=stations.filter {s.bases[it]?.belongsTo(plot.country)==true}.toSet()
        for(b in ownStations)s=s.copy(bases=s.bases+(b to s.bases.getValue(b).copy(camp=1,
            governorId=0,garrisonCommanderId=0,capitalAdministratorId=0,capitalDefenseCommanderId=0,guardInspectorId=0)))
        val bases=plot.bases+ownStations
        val seat=bases.firstOrNull {s.bases[it]?.let {b->b.belongsTo(plot.country) && b.camp==1}==true} ?: return s.copy(social=s.social.copy(coup=s.social.coup.copy(
            plots=s.social.coup.plots+(leader to plot.copy(bases=bases)))))
        for(h in regular) {
            val card=alias(h.card) ?: continue;val role=policy.role(card,plot.country) ?: continue
            val at=if(role.scope in setOf(OfficeScope.NATIONAL,OfficeScope.CAPITAL))seat else h.seat
            if(policy.active(s,role,at) && policy.holders(s,card,plot.country).count {it.seat==at}<role.seats)
                s=policy.set(s,OfficeHolding(card,at,h.character,plot.country))
        }
        if(deputy) {
            s=policy.set(s,OfficeHolding(leaderCard(system),seat,leader,plot.country))
            plot.members.firstOrNull {it in rebels}?.let {s=policy.set(s,OfficeHolding(deputyCard(system),seat,it,plot.country))}
        }
        return s.copy(social=s.social.copy(coup=s.social.coup.copy(plots=s.social.coup.plots+(leader to plot.copy(bases=bases)))))
    }

    /** Holding the capital this long wins the revolt (user decision 2026-11-02, evidence:guess). */
    const val CAPITAL_DAYS=7L
    /** 皇帝 / 議長: the head-of-state card a successful leader takes. */
    private fun headCard(system:Int)=if(system==2)1 else 132
    private fun legacyCapital(g:Galaxy,country:CountryId)=g.bases.singleOrNull {Capital.of(it)?.power==country.value}?.id
    private fun capital(g:Galaxy,s:PlanetStocks,country:CountryId)=s.strategic.capitals[country] ?: legacyCapital(g,country)

    /** A revolt that has held its side's capital for [CAPITAL_DAYS] changes the regime: rebel cards turn back into the
     * regular offices they alias (where a seat is free), the leader takes the head-of-state card from its holder, and
     * the rebel bases return to the regular camp. Other plots end when the leader is detained or every rebel base is
     * lost (the rebels lose their rebel cards). */
    fun settle(g:Galaxy,s:PlanetStocks,now:Long,countries:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        var current=s
        for(plot in s.social.coup.plots.values.filter {it.revolt>0}) {
            val seat=capital(g,current,plot.country)
            val held=current.bases[seat]?.let {it.camp==1 && it.belongsTo(plot.country)}==true
            val previousCapital=plot.capitalBase.takeIf {it>0} ?: legacyCapital(g,plot.country)
            val since=if(!held)0L else if(plot.capitalSince==0L || previousCapital!=seat)now else plot.capitalSince
            if(held && now-since>=CAPITAL_DAYS*Justice.DAY && !s.social.justice.detained(plot.leader)) {
                current=succeed(g,current,plot,checkNotNull(seat),countries,now);continue
            }
            val capitalBase=if(since>0)checkNotNull(seat) else 0
            if(since!=plot.capitalSince || capitalBase!=plot.capitalBase)current=current.copy(social=current.social.copy(coup=current.social.coup.copy(
                plots=current.social.coup.plots+(plot.leader to plot.copy(capitalSince=since,capitalBase=capitalBase)))))
        }
        return suppress(current,countries)
    }
    private fun succeed(g:Galaxy,s:PlanetStocks,plot:Plot,capital:Int,countries:CountryCatalog,now:Long):PlanetStocks {
        val system=OrganizationOffices.system(plot.country,countries) ?: return s
        val policy=OrganizationalOfficePolicy(g,countries);val rebels=plot.rebels.toSet()
        val aliases=s.strategic.offices.filter {it.country==plot.country && it.character in rebels && policy.role(it.card,plot.country)?.camp==1}
        var next=s.copy(strategic=s.strategic.copy(offices=s.strategic.offices-aliases.toSet()))
        for(b in plot.bases+capital)next.bases[b]?.takeIf {it.camp==1 && it.belongsTo(plot.country)}?.let {
            next=next.copy(bases=next.bases+(b to it.copy(camp=0)))}
        for(h in aliases.filter {it.card !in setOf(68,69,197,198)}) {
            val role=policy.role(checkNotNull(policy.role(h.card,plot.country)).canonical,plot.country) ?: continue
            val at=if(role.scope in setOf(OfficeScope.NATIONAL,OfficeScope.CAPITAL))capital else h.seat
            if(policy.active(next,role,at) && policy.holders(next,role.card,plot.country).count {it.seat==at}<role.seats)
                next=policy.set(next,OfficeHolding(role.card,at,h.character,plot.country))
        }
        val head=headCard(system)
        for(old in policy.holders(next,head,plot.country))next=policy.set(next,old,remove=true)
        next=policy.set(next,OfficeHolding(head,capital,plot.leader,plot.country))
        if(system==2)next=next.copy(social=next.social.copy(succession=next.social.succession.with(plot.country,
            ThroneState(emperor=plot.leader,at=maxOf(now,1L)))))
        return next.copy(social=next.social.copy(coup=next.social.coup.copy(plots=next.social.coup.plots-plot.leader)))
    }
    private fun suppress(s:PlanetStocks,countries:CountryCatalog):PlanetStocks {
        val c=s.social.coup
        val ended=c.plots.values.filter {plot->s.social.justice.detained(plot.leader) ||
            plot.revolt>0 && plot.bases.none {s.bases[it]?.let {b->b.camp==1 && b.belongsTo(plot.country)}==true}}
        if(ended.isEmpty())return s
        var next=s
        for(plot in ended) {
            val rebels=plot.rebels.toSet()
            next=next.copy(strategic=next.strategic.copy(offices=next.strategic.offices.filterNot {
                it.country==plot.country && it.character in rebels && OrganizationOffices.of(it.card,plot.country,countries)?.camp==1}))
            for(b in plot.bases)next.bases[b]?.takeIf {it.camp==1 && it.belongsTo(plot.country)}?.let {
                next=next.copy(bases=next.bases+(b to it.copy(camp=0)))}
        }
        return next.copy(social=next.social.copy(coup=next.social.coup.copy(plots=next.social.coup.plots-ended.map {it.leader}.toSet())))
    }
}
