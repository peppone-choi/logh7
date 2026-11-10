package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-17 工作 phase 1 (design-espionage.md, approved 2026-11-01): 潜入 75, 脱出 76, 情報 77, 破壊 78, 煽動 79 over 7f00.
 * A failed infiltration or escape is caught and detained by the base's side (U-25); a bomb stops the base's automatic
 * production for seven game days. Odds and durations are evidence:guess. Author: Peppone Choi.
 */
data class Infiltration(val character:Int,val base:Int,val spot:Int,val until:Long,
    val country:CountryId?=null,val targetCountry:CountryId?=null) {
    init {require(character>0 && base in 1..65535 && spot>0 && until in 0..0xffffffffL && (country!=null || targetCountry==null))}
}
data class Bomb(val character:Int,val base:Int,val spot:Int,val explodesAt:Long) {
    init {require(character>0 && base in 1..65535 && spot>0 && explodesAt in 0..0xffffffffL)}
}

/** A snapshot sent home by 情報工作, accessible to the actual country for one game day. */
data class HomeIntelligenceReport(val country:CountryId,val base:Int,val observedAt:Long,val treasury:Long,
    val approval:Int,val peace:Int) {
    init {require(base in 1..65535 && observedAt in 0..0xffffffffL && treasury>=0 && approval in 0..100 && peace in 0..100)}
    val expiresAt get()=observedAt+Justice.DAY
}

/** Others see [alias] of the forged [country]; [originCountry] binds new records to the actual agent. */
data class Disguise(val character:Int,val alias:Int,val country:CountryId,val since:Long,val checkAt:Long,
    val originCountry:CountryId?=null) {
    init {require(character>0 && alias in EspionageActions.aliases.indices && since in 0..checkAt && checkAt<=0xffffffffL)}
}

object EspionageActions {
    const val INFILTRATE=75
    const val ESCAPE=76
    const val INTELLIGENCE=77
    const val SABOTAGE=78
    const val AGITATE=79
    /** 侵入 80 / 帰還 81 (MCP 320, manual p.74): to another side's / own side's base within 1000 light years (10 grid cells). */
    const val INTRUDE=80
    const val RETURN=81
    val commands=setOf(INFILTRATE,ESCAPE,INTELLIGENCE,SABOTAGE,AGITATE,INTRUDE,RETURN)
    private val sabotageFacilities = setOf(
        0, 1, 7, 10, 12, 13, 14, 21, 23, // Government, defense, and military production facilities.
        29, 31, 32, 33, 34, 35, 36, 37 // Military command and logistics headquarters.
    )
    /** Forged surnames offered by the window (evidence:guess). */
    val aliases=listOf("Muller","Schmidt","Fischer","Weber","Wagner","Becker","Hoffmann","Schulz")
    const val RANGE_CELLS=10
    fun cells(a:Int,b:Int)=maxOf(kotlin.math.abs(a%100-b%100),kotlin.math.abs(a/100-b/100))
    /** Daily chance (percent) that a disguise is seen through: 20 − (intelligence − 50)/4, 5..40 (evidence:guess). */
    fun exposure(intelligence:Int)=(20-(intelligence-50)/4).coerceIn(5,40)
    /** Intelligence duties (CM group 6): 20 調査局長, 58/194 諜報官, 162 情報部長. */
    val cards=setOf(20,58,162,194)
    const val INFILTRATION_DAYS=7L
    const val FUSE_SECONDS=4*3600L
    const val DISABLED_DAYS=7L

    /** U-25's formula against a guard of intelligence 50; a special guard (strength 2) takes 20 off. */
    fun threshold(intelligence:Int,guard:Int)=Justice.arrestThreshold(intelligence,50)-CivicActions.guardPenalty(guard)
    fun roll(actor:Int,spot:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+spot*7L+53).nextInt(100)

    /** The caller supplies the authenticated account's current authoritative navigator. */
    fun homeReport(s:PlanetStocks,session:SessionState,account:String,connection:String,viewer:Navigator,base:Int):HomeIntelligenceReport? {
        val country=viewer.country ?: return null
        val member=session.participants[account] ?: return null
        if(!member.online || member.connection!=connection || member.country!=country || country in session.eliminated ||
            session.ended!=null || viewer.generation!=s.generation || viewer.generation!=session.generation)return null
        val now=session.gameMillis/1000
        return s.social.justice.homeReports[country to base]?.takeIf {now in it.observedAt until it.expiresAt}
    }

    /** A forged identity cannot follow an agent who changed country or left its host country. */
    fun disguise(s:PlanetStocks,player:Navigator):Disguise?=s.social.justice.disguises[player.characterId]?.takeIf {d->
        player.generation==s.generation && (if(d.originCountry!=null)player.country==d.originCountry
            else player.country?.value in 2..3 && d.country.value in 2..3) && s.bases[player.baseId]?.country==d.country}

    internal fun endDepartedInfiltration(stocks: PlanetStocks, before: Navigator?, after: Navigator): PlanetStocks {
        val justice = stocks.social.justice
        val infiltration = justice.infiltrations[after.characterId] ?: return stocks
        if (before?.characterId != after.characterId || before.baseId != infiltration.base || before.spotId != infiltration.spot ||
            after.baseId == infiltration.base && after.spotId == infiltration.spot) return stocks
        return stocks.copy(social = stocks.social.copy(justice = justice.copy(infiltrations = justice.infiltrations - after.characterId)))
    }

    /** Explodes due bombs (the base stops automatic production; people still in that spot are wounded), drops ended
     * infiltrations and production stops. */
    fun settle(galaxy:Galaxy,session:SessionState,players:MutableMap<String,Navigator>,s:PlanetStocks,
        catalog:CountryCatalog=CountryCatalogLoader.current()):PlanetStocks {
        val now=session.gameMillis/1000;val j=s.social.justice
        val due=j.bombs.values.filter {it.explodesAt<=now}
        val seen=j.disguises.values.filter {it.checkAt<=now}
        if(due.isEmpty() && seen.isEmpty() && j.infiltrations.none {it.value.until<=now} && j.disabled.none {it.value<=now} &&
            j.homeReports.none {it.value.expiresAt<=now})return s
        val disabled=LinkedHashMap(j.disabled.filterValues {it>now})
        for(b in due.sortedBy {it.character}) {
            val resumesAt=productionResumesAt(b)
            if(resumesAt>now)disabled[b.base]=maxOf(disabled[b.base] ?: 0,resumesAt)
            for((key,n) in players.entries.toList())if(n.baseId==b.base && n.spotId==b.spot)
                players[key]=n.hull?.let {n.copy(hull=it.copy(woundedUntil=maxOf(it.woundedUntil,b.explodesAt+Justice.WOUND_SECONDS)))} ?: n
        }
        var next=s.copy(social=s.social.copy(justice=j.copy(bombs=j.bombs.filterValues {it.explodesAt>now},
            infiltrations=j.infiltrations.filterValues {it.until>now},disabled=disabled,
            homeReports=j.homeReports.filterValues {it.expiresAt>now})))
        // A disguise is rolled once per game day; seen through, the intruder is arrested by the side it hid among.
        for(d in seen.sortedBy {it.character}) {
            val intelligence=next.strategic.people[d.character]?.abilities?.getOrNull(3) ?: 50
            val justice=next.social.justice
            val agent=players.values.singleOrNull {it.characterId==d.character && it.generation==session.generation}
            if(agent==null || disguise(next,agent)!=d) {
                next=next.copy(social=next.social.copy(justice=justice.copy(disguises=justice.disguises-d.character)))
                continue
            }
            if(roll(d.character,d.alias,d.checkAt)<exposure(intelligence)) {
                next=next.copy(social=next.social.copy(justice=justice.copy(disguises=justice.disguises-d.character)))
                JusticeActions.detain(galaxy,next,players,d.character,d.country,now,catalog)?.let {next=it}
            } else next=next.copy(social=next.social.copy(justice=justice.copy(disguises=justice.disguises+(d.character to d.copy(checkAt=d.checkAt+Justice.DAY)))))
        }
        return next
    }
    fun disabled(s:PlanetStocks,base:Int,seconds:Long)=(s.social.justice.disabled[base] ?: 0)>seconds
    private fun productionResumesAt(bomb:Bomb)=(bomb.explodesAt+DISABLED_DAYS*Justice.DAY).coerceAtMost(0xffffffffL)
    fun stopsProductionAt(bomb:Bomb,seconds:Long)=seconds in bomb.explodesAt until productionResumesAt(bomb)
    fun productionPausedUntil(s:PlanetStocks,base:Int,seconds:Long):Long {
        val justice=s.social.justice
        val active=justice.disabled[base]?.takeIf {it>seconds} ?: 0
        val pending=justice.bombs.values.asSequence().filter {it.base==base && stopsProductionAt(it,seconds)}
            .maxOfOrNull(::productionResumesAt) ?: 0
        return maxOf(active,pending)
    }

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>,
        catalog:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        val own=i.country
        if(own.value !in catalog.countries)return reject(SocialRejection.CARD)
        if(own in session.eliminated)return reject(SocialRejection.SESSION)
        if(p.cpPaused || p.navigationPending || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        if(OrganizationalOfficePolicy(g,catalog).held(s,i.characterId).none {it.card==i.card && it.country==own} || i.card !in cards)return reject(SocialRejection.CARD)
        if(p.baseId==0)return reject(SocialRejection.LOCATION)
        val owner=s.bases[p.baseId]?.country
        val j=s.social.justice;val inside=j.infiltrations[i.characterId]?.takeIf {it.until>now && it.base==p.baseId && it.spot==p.spotId &&
            (if(it.country!=null)it.country==own && it.targetCountry==owner else own.value in 2..3)}
        val intelligence=s.strategic.people[i.characterId]?.abilities?.getOrNull(3)
            ?: i.identities.singleOrNull {it.id==i.characterId}?.abilities?.getOrNull(3) ?: 50
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command in setOf(INTRUDE,RETURN))320L else 160L,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        if(p.baseId !in s.bases)return reject(SocialRejection.LOCATION)
        /** Caught: detained by the base's side (user decision 2026-11-01). */
        fun caught(state:PlanetStocks):SocialResult {
            val players=LinkedHashMap(accounts);players[i.account]=payer
            val side=owner ?: own
            val cleared=state.copy(social=state.social.copy(justice=state.social.justice.copy(infiltrations=state.social.justice.infiltrations-i.characterId)))
            val next=JusticeActions.detain(g,cleared,players,i.characterId,side,now,catalog) ?: return SocialResult(payer,cleared,changed=true,outcome=3)
            return SocialResult(players.getValue(i.account),next,changed=true,outcome=3,
                navigators=players.filter {(k,v)->k!=i.account && accounts[k]!=v})
        }
        when(i.command) {
            INFILTRATE -> {
                val spot=i.base
                val facilities=g.facilitiesAt(p.baseId,s,catalog)
                if(spot<=0 || spot==p.spotId || facilities.none {f->f.spots.any {it.id==spot}})return reject(SocialRejection.TARGET)
                val guard=CivicActions.guardStrength(s,p.baseId,spot,now)
                if(roll(i.characterId,spot,now)>=threshold(intelligence,guard))return caught(s)
                val next=s.copy(social=s.social.copy(justice=j.copy(infiltrations=j.infiltrations+(i.characterId to
                    Infiltration(i.characterId,p.baseId,spot,now+INFILTRATION_DAYS*Justice.DAY,own,owner)))))
                return SocialResult(payer.copy(spotId=spot,spotOwner=0),next,changed=true,outcome=1,found=spot)
            }
            ESCAPE -> {
                inside ?: return reject(SocialRejection.STALE)
                val guard=CivicActions.guardStrength(s,p.baseId,p.spotId,now)
                if(roll(i.characterId,p.spotId,now)>=threshold(intelligence,guard))return caught(s)
                return SocialResult(payer.copy(spotId=0,spotOwner=0),s.copy(social=s.social.copy(justice=j.copy(
                    infiltrations=j.infiltrations-i.characterId))),changed=true,outcome=1)
            }
            INTELLIGENCE -> {
                inside ?: return reject(SocialRejection.STALE)
                val b=s.bases.getValue(p.baseId)
                if(roll(i.characterId,p.spotId,now)>=threshold(intelligence,CivicActions.guardStrength(s,p.baseId,p.spotId,now)))
                    return SocialResult(payer,s,changed=true,outcome=3)
                val report=HomeIntelligenceReport(own,b.baseId,now,b.treasury,b.economy?.approval ?: 0,b.economy?.peace ?: 0)
                val key=own to b.baseId
                val reports=j.homeReports.filterValues {it.expiresAt>now}.toMutableMap()
                if(key !in reports && reports.size==JusticeState.MAX_HOME_REPORTS)return reject(SocialRejection.MAXIMUM)
                reports[key]=report
                val next=s.copy(social=s.social.copy(justice=j.copy(homeReports=reports)))
                return SocialResult(payer,next,changed=true,outcome=1,found=minOf(report.treasury,0x7fffffffL).toInt(),
                    report=report.approval*256+report.peace)
            }
            SABOTAGE -> {
                inside ?: return reject(SocialRejection.STALE)
                if (g.facilitiesAt(p.baseId, s, catalog).none { facility ->
                        facility.kind in sabotageFacilities && facility.spots.any { it.id == p.spotId }
                    }) return reject(SocialRejection.TARGET)
                if(j.bombs.containsKey(i.characterId))return reject(SocialRejection.BUSY)
                if(roll(i.characterId,p.spotId,now)>=threshold(intelligence,CivicActions.guardStrength(s,p.baseId,p.spotId,now)))
                    return SocialResult(payer,s,changed=true,outcome=3)
                return SocialResult(payer,s.copy(social=s.social.copy(justice=j.copy(bombs=j.bombs+(i.characterId to
                    Bomb(i.characterId,p.baseId,p.spotId,now+FUSE_SECONDS))))),changed=true,outcome=1)
            }
            INTRUDE,RETURN -> {
                val target=g.bases.singleOrNull {it.id==i.base} ?: return reject(SocialRejection.TARGET)
                val side=s.bases[target.id]?.country ?: return reject(SocialRejection.TARGET)
                val here=g.bases.singleOrNull {it.id==p.baseId} ?: return reject(SocialRejection.LOCATION)
                if(target.id==p.baseId || cells(here.grid,target.grid)>RANGE_CELLS)return reject(SocialRejection.LOCATION)
                if(i.command==INTRUDE && (side==own || side.value !in catalog.countries || i.value !in aliases.indices))return reject(SocialRejection.TARGET)
                if(i.command==RETURN && side!=own)return reject(SocialRejection.TARGET)
                val moved=payer.copy(grid=target.grid,baseId=target.id,mode=4,spotId=0,spotOwner=0)
                val cleared=j.copy(infiltrations=j.infiltrations-i.characterId,disguises=j.disguises-i.characterId)
                if(i.command==RETURN)return SocialResult(moved,s.copy(social=s.social.copy(justice=cleared)),changed=true,outcome=1)
                if(roll(i.characterId,target.id,now)>=threshold(intelligence,1)) {
                    // Caught at the border: detained by the side being entered.
                    val players=LinkedHashMap(accounts);players[i.account]=payer
                    val next=JusticeActions.detain(g,s.copy(social=s.social.copy(justice=cleared)),players,i.characterId,side,now,catalog)
                        ?: return SocialResult(payer,s,changed=true,outcome=3)
                    return SocialResult(players.getValue(i.account),next,changed=true,outcome=3,
                        navigators=players.filter {(k,v)->k!=i.account && accounts[k]!=v})
                }
                return SocialResult(moved,s.copy(social=s.social.copy(justice=cleared.copy(disguises=cleared.disguises+(i.characterId to
                    Disguise(i.characterId,i.value,side,now,now+Justice.DAY,own))))),changed=true,outcome=1)
            }
            AGITATE -> {
                val b=s.bases.getValue(p.baseId)
                if(owner==own || b.economy==null)return reject(SocialRejection.TARGET)
                if(roll(i.characterId,p.baseId,now)>=threshold(intelligence,1))return SocialResult(payer,s,changed=true,outcome=3)
                val e=b.economy
                return SocialResult(payer,s.copy(bases=s.bases+(b.baseId to b.copy(economy=e.copy(approval=maxOf(0,e.approval-5),
                    peace=maxOf(0,e.peace-3))))),changed=true,outcome=1)
            }
        }
        return reject(SocialRejection.COMMAND)
    }
}
