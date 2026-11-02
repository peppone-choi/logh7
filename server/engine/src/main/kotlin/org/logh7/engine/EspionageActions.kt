package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-17 工作 phase 1 (design-espionage.md, approved 2026-11-01): 潜入 75, 脱出 76, 情報 77, 破壊 78, 煽動 79 over 7f00.
 * A failed infiltration or escape is caught and detained by the base's side (U-25); a bomb stops the base's automatic
 * production for seven game days. Odds and durations are evidence:guess. Author: Peppone Choi.
 */
data class Infiltration(val character:Int,val base:Int,val spot:Int,val until:Long) {
    init {require(character>0 && base in 1..65535 && spot>0 && until in 0..0xffffffffL)}
}
data class Bomb(val character:Int,val base:Int,val spot:Int,val explodesAt:Long) {
    init {require(character>0 && base in 1..65535 && spot>0 && explodesAt in 0..0xffffffffL)}
}

/** A forged identity: others see [alias] (an index of [EspionageActions.aliases]) of [power] until detected. */
data class Disguise(val character:Int,val alias:Int,val power:Int,val since:Long,val checkAt:Long) {
    init {require(character>0 && alias in EspionageActions.aliases.indices && power in 2..3 && since in 0..checkAt && checkAt<=0xffffffffL)}
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
    fun threshold(intelligence:Int,guard:Int)=Justice.arrestThreshold(intelligence,50)-if(guard>=2)20 else 0
    fun roll(actor:Int,spot:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+spot*7L+53).nextInt(100)

    /** Explodes due bombs (the base stops automatic production; people still in that spot are wounded), drops ended
     * infiltrations and production stops. */
    fun settle(session:SessionState,players:MutableMap<String,Navigator>,s:PlanetStocks):PlanetStocks {
        val now=session.gameMillis/1000;val j=s.social.justice
        val due=j.bombs.values.filter {it.explodesAt<=now}
        val seen=j.disguises.values.filter {it.checkAt<=now}
        if(due.isEmpty() && seen.isEmpty() && j.infiltrations.none {it.value.until<=now} && j.disabled.none {it.value<=now})return s
        val disabled=LinkedHashMap(j.disabled.filterValues {it>now})
        for(b in due.sortedBy {it.character}) {
            disabled[b.base]=maxOf(disabled[b.base] ?: 0,b.explodesAt+DISABLED_DAYS*Justice.DAY)
            for((key,n) in players.entries.toList())if(n.baseId==b.base && n.spotId==b.spot)
                players[key]=n.hull?.let {n.copy(hull=it.copy(woundedUntil=maxOf(it.woundedUntil,b.explodesAt+Justice.WOUND_SECONDS)))} ?: n
        }
        var next=s.copy(social=s.social.copy(justice=j.copy(bombs=j.bombs.filterValues {it.explodesAt>now},
            infiltrations=j.infiltrations.filterValues {it.until>now},disabled=disabled)))
        // A disguise is rolled once per game day; seen through, the intruder is arrested by the side it hid among.
        for(d in seen.sortedBy {it.character}) {
            val intelligence=next.strategic.people[d.character]?.abilities?.getOrNull(3) ?: 50
            val justice=next.social.justice
            if(roll(d.character,d.alias,d.checkAt)<exposure(intelligence)) {
                next=next.copy(social=next.social.copy(justice=justice.copy(disguises=justice.disguises-d.character)))
                caughtBy?.invoke(next,players,d.character,d.power,now)?.let {next=it}
            } else next=next.copy(social=next.social.copy(justice=justice.copy(disguises=justice.disguises+(d.character to d.copy(checkAt=d.checkAt+Justice.DAY)))))
        }
        return next
    }
    /** Detention hook (set to JusticeActions.detain by the world tick, which knows the galaxy). */
    @Volatile var caughtBy:((PlanetStocks,MutableMap<String,Navigator>,Int,Int,Long)->PlanetStocks?)?=null
    fun disabled(s:PlanetStocks,base:Int,seconds:Long)=(s.social.justice.disabled[base] ?: 0)>seconds

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null || s.social.justice.detained(i.characterId))return reject(SocialRejection.BUSY)
        if(OrganizationalOfficePolicy(g).held(s,i.characterId).none {it.card==i.card} || i.card !in cards)return reject(SocialRejection.CARD)
        if(p.baseId==0)return reject(SocialRejection.LOCATION)
        val j=s.social.justice;val inside=j.infiltrations[i.characterId]?.takeIf {it.base==p.baseId && it.spot==p.spotId}
        val intelligence=s.strategic.people[i.characterId]?.abilities?.getOrNull(3)
            ?: i.identities.singleOrNull {it.id==i.characterId}?.abilities?.getOrNull(3) ?: 50
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command in setOf(INTRUDE,RETURN))320L else 160L,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val payer=p.paidWith(paid.balance)
        val owner=s.bases[p.baseId]?.power ?: return reject(SocialRejection.LOCATION)
        /** Caught: detained by the base's side (user decision 2026-11-01). */
        fun caught(state:PlanetStocks):SocialResult {
            val players=LinkedHashMap(accounts);players[i.account]=payer
            val side=if(owner in 2..3)owner else if(payer.power==Power.EMPIRE)2 else 3
            val cleared=state.copy(social=state.social.copy(justice=state.social.justice.copy(infiltrations=state.social.justice.infiltrations-i.characterId)))
            val next=JusticeActions.detain(g,cleared,players,i.characterId,side,now) ?: return SocialResult(payer,cleared,changed=true,outcome=3)
            return SocialResult(players.getValue(i.account),next,changed=true,outcome=3,
                navigators=players.filter {(k,v)->k!=i.account && accounts[k]!=v})
        }
        when(i.command) {
            INFILTRATE -> {
                val spot=i.base
                val facilities=g.facilitiesAt(p.baseId,s)
                if(spot<=0 || spot==p.spotId || facilities.none {f->f.spots.any {it.id==spot}})return reject(SocialRejection.TARGET)
                val guard=CivicActions.guardStrength(s,p.baseId,spot,now)
                if(roll(i.characterId,spot,now)>=threshold(intelligence,guard))return caught(s)
                val next=s.copy(social=s.social.copy(justice=j.copy(infiltrations=j.infiltrations+(i.characterId to
                    Infiltration(i.characterId,p.baseId,spot,now+INFILTRATION_DAYS*Justice.DAY)))))
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
                // Report: the base treasury and approval/peace (0 when the base keeps no economy).
                return if(roll(i.characterId,p.spotId,now)<threshold(intelligence,CivicActions.guardStrength(s,p.baseId,p.spotId,now)))
                    SocialResult(payer,s,changed=true,outcome=1,found=minOf(b.treasury,0x7fffffffL).toInt(),
                        report=(b.economy?.approval ?: 0)*256+(b.economy?.peace ?: 0))
                else SocialResult(payer,s,changed=true,outcome=3)
            }
            SABOTAGE -> {
                inside ?: return reject(SocialRejection.STALE)
                if(j.bombs.containsKey(i.characterId))return reject(SocialRejection.BUSY)
                if(roll(i.characterId,p.spotId,now)>=threshold(intelligence,CivicActions.guardStrength(s,p.baseId,p.spotId,now)))
                    return SocialResult(payer,s,changed=true,outcome=3)
                return SocialResult(payer,s.copy(social=s.social.copy(justice=j.copy(bombs=j.bombs+(i.characterId to
                    Bomb(i.characterId,p.baseId,p.spotId,now+FUSE_SECONDS))))),changed=true,outcome=1)
            }
            INTRUDE,RETURN -> {
                val own=if(p.power==Power.EMPIRE)2 else 3
                val target=g.bases.singleOrNull {it.id==i.base} ?: return reject(SocialRejection.TARGET)
                val side=s.bases[target.id]?.power ?: return reject(SocialRejection.TARGET)
                val here=g.bases.singleOrNull {it.id==p.baseId} ?: return reject(SocialRejection.LOCATION)
                if(target.id==p.baseId || cells(here.grid,target.grid)>RANGE_CELLS)return reject(SocialRejection.LOCATION)
                if(i.command==INTRUDE && (side==own || side !in 2..3 || i.value !in aliases.indices))return reject(SocialRejection.TARGET)
                if(i.command==RETURN && side!=own)return reject(SocialRejection.TARGET)
                val moved=payer.copy(grid=target.grid,baseId=target.id,mode=4,spotId=0,spotOwner=0)
                val cleared=j.copy(infiltrations=j.infiltrations-i.characterId,disguises=j.disguises-i.characterId)
                if(i.command==RETURN)return SocialResult(moved,s.copy(social=s.social.copy(justice=cleared)),changed=true,outcome=1)
                if(roll(i.characterId,target.id,now)>=threshold(intelligence,1)) {
                    // Caught at the border: detained by the side being entered.
                    val players=LinkedHashMap(accounts);players[i.account]=payer
                    val next=JusticeActions.detain(g,s.copy(social=s.social.copy(justice=cleared)),players,i.characterId,side,now)
                        ?: return SocialResult(payer,s,changed=true,outcome=3)
                    return SocialResult(players.getValue(i.account),next,changed=true,outcome=3,
                        navigators=players.filter {(k,v)->k!=i.account && accounts[k]!=v})
                }
                return SocialResult(moved,s.copy(social=s.social.copy(justice=cleared.copy(disguises=cleared.disguises+(i.characterId to
                    Disguise(i.characterId,i.value,side,now,now+Justice.DAY))))),changed=true,outcome=1)
            }
            AGITATE -> {
                val b=s.bases.getValue(p.baseId)
                if(owner==(if(p.power==Power.EMPIRE)2 else 3) || b.economy==null)return reject(SocialRejection.TARGET)
                if(roll(i.characterId,p.baseId,now)>=threshold(intelligence,1))return SocialResult(payer,s,changed=true,outcome=3)
                val e=b.economy
                return SocialResult(payer,s.copy(bases=s.bases+(b.baseId to b.copy(economy=e.copy(approval=maxOf(0,e.approval-5),
                    peace=maxOf(0,e.peace-3))))),changed=true,outcome=1)
            }
        }
        return reject(SocialRejection.COMMAND)
    }
}
