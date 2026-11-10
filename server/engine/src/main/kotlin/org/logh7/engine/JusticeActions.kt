package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-25 phase 1a (design-arrest-judgment.md): 逮捕許可 69, 執行命令 70, 逮捕命令 71, 処断 19 through the 7f00 envelope.
 * [StrategyActionRequest.card] is the authority card (0 for a right holder's arrest), target the person, value the
 * charge (69), 0 grant / 1 revoke (70) or the verdict 1..3 (19); 70 names the listed person in base.
 * Author: Peppone Choi.
 */
object JusticeActions {
    private val countries get()=CountryCatalogLoader.current()
    enum class Outcome { NONE, ARRESTED, EVADED, FAILED }
    /** Deterministic arrest roll, like BattleDeath's per-event roll. */
    fun roll(actor:Int,target:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+target*97L+29).nextInt(100)

    private fun rank(s:PlanetStocks,ids:List<StrategicIdentity>,id:Int)=
        s.strategic.people[id]?.rank ?: ids.singleOrNull {it.id==id}?.rank ?: 20
    private fun intelligence(s:PlanetStocks,ids:List<StrategicIdentity>,id:Int)=
        s.strategic.people[id]?.abilities?.getOrNull(3) ?: ids.singleOrNull {it.id==id}?.abilities?.getOrNull(3) ?: 50

    /** Moves a person into the custodian country's holding room, strips offices and records the detention. */
    fun detain(g:Galaxy,s:PlanetStocks,players:MutableMap<String,Navigator>,target:Int,country:CountryId,now:Long,
        catalog:CountryCatalog=countries):PlanetStocks? {
        val (capital,cell)=Justice.cell(g,s,country,catalog) ?: return null
        val key=players.entries.singleOrNull {it.value.characterId==target && it.value.generation==s.generation}?.key ?: return null
        val n=players.getValue(key)
        if(n.cpPaused)return null
        players[key]=n.copy(grid=capital.grid,baseId=capital.id,mode=4,spotId=cell,spotOwner=0,
            strategy=null,dockTarget=null,fuelReadyAt=null,warpTarget=null)
        val cleared=StrategyActions.clearOffices(s,target)
        val justice=cleared.social.justice
        return cleared.copy(social=cleared.social.copy(justice=justice.copy(permits=justice.permits-target,
            rights=justice.rights.filterValues {it.target!=target && it.holder!=target},
            infiltrations=justice.infiltrations-target,disguises=justice.disguises-target,
            detentions=justice.detentions+(target to Detention(target,country,now,now+Justice.DETENTION_DAYS*Justice.DAY,base=capital.id)))))
    }
    /** Releases a detainee to its capital's central square (no other effect). */
    private fun release(g:Galaxy,s:PlanetStocks,players:MutableMap<String,Navigator>,d:Detention):PlanetStocks {
        val key=players.entries.singleOrNull {it.value.characterId==d.character && it.value.generation==s.generation}?.key
        if (key != null) {
            val player = players.getValue(key)
            if (d.holdsAt(g, player)) players[key] = player.copy(spotId = 0, spotOwner = 0)
        }
        val j=s.social.justice
        return s.copy(social=s.social.copy(justice=j.copy(detentions=j.detentions-d.character)))
    }
    private fun demote(s:PlanetStocks,id:Int,steps:Int,halveMerit:Boolean):PlanetStocks {
        val person=s.strategic.people[id] ?: return s
        val rank=person.rank ?: return s
        val next=if(rank<=1)rank else minOf(20,rank+steps)
        return s.copy(strategic=s.strategic.copy(people=s.strategic.people+(id to person.copy(rank=next,
            merit=if(halveMerit)person.merit/2 else person.merit))))
    }
    /** Periodic world settlement: expired permits/rights drop, 14 days without a verdict mean 寛容, 厳罰 ends. */
    fun settle(g:Galaxy,session:SessionState,players:MutableMap<String,Navigator>,s:PlanetStocks,
        identities:()->List<StrategicIdentity> = {emptyList()}):PlanetStocks {
        val now=session.gameMillis/1000;val j=s.social.justice
        var next=s
        if(j.permits.any {it.value.until<=now} || j.rights.any {it.value.until<=now})
            next=next.copy(social=next.social.copy(justice=j.copy(permits=j.permits.filterValues {it.until>now},
                rights=j.rights.filterValues {it.until>now})))
        // Watches: one detection roll per watch and game day; a noticed watch ends.
        val due=next.social.justice.watches.values.filter {it.checkAt<=now}
        if(due.isNotEmpty()) {
            val roster=identities()
            val watches=LinkedHashMap(next.social.justice.watches)
            for(w in due.sortedBy {it.watcher}) {
                val chance=Justice.detection(intelligence(next,roster,w.watcher),intelligence(next,roster,w.target))
                if(roll(w.target,w.watcher,w.checkAt)<chance)watches.remove(w.watcher)
                else watches[w.watcher]=w.copy(checkAt=w.checkAt+Justice.DAY)
            }
            next=next.copy(social=next.social.copy(justice=next.social.justice.copy(watches=watches)))
        }
        for(d in next.social.justice.detentions.values.filter {it.releaseAt<=now}.sortedBy {it.character}) {
            next=release(g,next,players,d)
            next=if(d.severe)demote(next,d.character,2,false) else demote(next,d.character,1,true)
        }
        return next
    }
    /** 亡命 (design §3.5): PCP 320, to the other side's capital holding room; offices, outfits and the flagship stay
     * behind; the destination's judges decide (Detention.country = destination). */
    private fun defect(g:Galaxy,i:StrategyActionRequest,session:SessionState,p:Navigator,s:PlanetStocks,country:CountryId,now:Long,catalog:CountryCatalog):SocialResult {
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        if(i.card!=0 || i.target!=0 || i.base!=0)return reject(SocialRejection.COMMAND)
        // 0 = the other side (the compatibility window does not know its own side).
        val roster=session.countries+s.bases.values.mapNotNull {it.country}
        val destination=when {
            i.value>0->CountryId(i.value)
            i.value==0 && country.value in 2..3 && roster.all {it.value in 2..3}->CountryId(if(country.value==2)3 else 2)
            else->return reject(SocialRejection.TARGET)
        }
        if(destination==country || destination.value !in catalog.countries || destination in session.eliminated)
            return reject(SocialRejection.TARGET)
        if(session.participants[i.account]==null)return reject(SocialRejection.SESSION)
        val (capital,cell)=Justice.cell(g,s,destination,catalog) ?: return reject(SocialRejection.LOCATION)
        val style=catalog.shipStyle(destination)
        if(style.value !in setOf("empire","alliance"))return reject(SocialRejection.TARGET)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),320L,CpPool.PCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
        val id=i.characterId
        // Offices, fiefs and the peerage belong to the side left behind (their cards carry that side).
        val offices=StrategyActions.clearOffices(s,id)
        val person=offices.strategic.people[id]
        val cleared=offices.copy(strategic=offices.strategic.copy(fiefs=offices.strategic.fiefs.filterValues {it!=id},
            people=if(person==null)offices.strategic.people else offices.strategic.people+(id to person.copy(peerage=null))))
        val j=cleared.social.justice
        val next=cleared.copy(social=cleared.social.copy(justice=j.copy(permits=j.permits-id,
            rights=j.rights.filterValues {it.holder!=id && it.target!=id},watches=j.watches.filterValues {it.watcher!=id && it.target!=id},
            infiltrations=j.infiltrations-id,disguises=j.disguises-id,
            detentions=j.detentions+(id to Detention(id,destination,now,now+Justice.DETENTION_DAYS*Justice.DAY,base=capital.id)))))
        val moved=p.paidWith(paid.balance).copy(country=destination,
            shipStyle=style,grid=capital.grid,
            baseId=capital.id,mode=4,spotId=cell,spotOwner=0,hull=null,strategy=null,dockTarget=null,fuelReadyAt=null,warpTarget=null,returnBase=0)
        return SocialResult(moved,next,changed=true,defected=destination)
    }
    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>,
        catalog:CountryCatalog=countries):SocialResult {
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
        if(p.cpPaused || p.navigationPending)return reject(SocialRejection.BUSY)
        val j=s.social.justice
        if(j.detained(i.characterId))return reject(SocialRejection.BUSY)
        val ids=i.identities
        if(i.command==Justice.DEFECT)return defect(g,i,session,p,s,own,now,catalog)
        val cost=Justice.costs[i.command] ?: return reject(SocialRejection.COMMAND)
        val holds=i.card!=0 && OrganizationalOfficePolicy(g,catalog).held(s,i.characterId).any {it.card==i.card && it.country==own}
        if(i.card!=0 && !holds)return reject(SocialRejection.CARD)
        if(i.target<=0 || i.target==i.characterId)return reject(SocialRejection.TARGET)
        val targetNav=accounts.values.singleOrNull {it.generation==i.generation && it.characterId==i.target}
        val targetIdentity=ids.singleOrNull {it.id==i.target}
        if(targetNav==null && targetIdentity==null)return reject(SocialRejection.TARGET)
        val targetCountry=if(targetNav!=null)targetNav.country else targetIdentity?.country
        fun countryOf(id:Int):CountryId? {
            val located=accounts.values.singleOrNull {it.generation==i.generation && it.characterId==id}
            return if(located!=null)located.country else ids.singleOrNull {it.id==id}?.country
        }
        val targetRank=rank(s,ids,i.target)
        fun pay(next:PlanetStocks,player:Navigator=p,players:Map<String,Navigator> = emptyMap()):SocialResult {
            val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),cost,CpPool.MCP)
            if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
            return SocialResult(player.paidWith(paid.balance),next,changed=true,navigators=players)
        }
        when(i.command) {
            Justice.PERMIT -> {
                if(!holds || !Justice.arrests(i.card,targetRank) || targetCountry!=p.country)return reject(SocialRejection.TARGET)
                if(i.value !in 1..7 || j.detained(i.target))return reject(SocialRejection.COMMAND)
                return pay(s.copy(social=s.social.copy(justice=j.copy(permits=j.permits+(i.target to
                    ArrestPermit(i.target,i.characterId,own,i.value,now+Justice.PERMIT_DAYS*Justice.DAY))))))
            }
            Justice.EXECUTION -> {
                val listed=j.permits[i.base]?.takeIf {it.country==own && it.until>now} ?: return reject(SocialRejection.STALE)
                if(!holds || !Justice.arrests(i.card,rank(s,ids,listed.target)) || targetCountry!=own ||
                    countryOf(listed.target)!=own || i.target==listed.target)
                    return reject(SocialRejection.TARGET)
                val rights=if(i.value==1)j.rights.filterValues {!(it.holder==i.target && it.target==listed.target)}
                    else if(i.value==0)j.rights+(i.target to ArrestRight(i.target,listed.target,i.characterId,now+Justice.RIGHT_DAYS*Justice.DAY,own))
                    else return reject(SocialRejection.COMMAND)
                return pay(s.copy(social=s.social.copy(justice=j.copy(rights=rights))))
            }
            Justice.ARREST -> {
                j.permits[i.target]?.takeIf {it.country==own && it.until>now} ?: return reject(SocialRejection.STALE)
                if(targetCountry!=own)return reject(SocialRejection.TARGET)
                val right=j.rights[i.characterId]?.takeIf {it.target==i.target && it.until>now &&
                    (it.country==own || it.country==null && own.value in 2..3)}
                if(!(holds && Justice.arrests(i.card,targetRank)) && right==null)return reject(SocialRejection.CARD)
                val t=targetNav ?: return reject(SocialRejection.LOCATION)
                if(t.cpPaused || j.detained(i.target))return reject(SocialRejection.LOCATION)
                // Same spot, or the same grid aboard the same outfit (W p.73-74).
                val sameSpot=p.spotId!=0 && t.baseId==p.baseId && t.spotId==p.spotId && t.spotOwner==p.spotOwner
                val sameOutfit=t.grid==p.grid && s.outfits.values.any {it.country==own && i.characterId in it.members() && i.target in it.members()}
                if(!sameSpot && !sameOutfit)return reject(SocialRejection.LOCATION)
                val threshold=Justice.arrestThreshold(intelligence(s,ids,i.characterId),intelligence(s,ids,i.target))
                val dice=roll(i.characterId,i.target,now)
                val outcome=when {dice<threshold-10 -> Outcome.ARRESTED;dice<threshold+10 -> Outcome.EVADED;else -> Outcome.FAILED}
                val spent=if(right!=null && outcome!=Outcome.EVADED)j.rights-i.characterId else j.rights
                var next=s.copy(social=s.social.copy(justice=j.copy(rights=spent)))
                val players=LinkedHashMap(accounts)
                if(outcome==Outcome.ARRESTED)next=detain(g,next,players,i.target,own,now,catalog) ?: return reject(SocialRejection.LOCATION)
                val changed=players.filter {(k,v)->accounts[k]!=v}
                return pay(next,p,changed).let {if(it.rejection==null)it.copy(outcome=outcome.ordinal) else it}
            }
            Justice.SEARCH -> {
                if(!holds || i.card !in Justice.searchCards)return reject(SocialRejection.CARD)
                val t=targetNav ?: return reject(SocialRejection.TARGET)
                // Found only when staying at the searcher's base and the intelligence roll succeeds.
                val threshold=Justice.arrestThreshold(intelligence(s,ids,i.characterId),intelligence(s,ids,i.target))
                val found=p.baseId!=0 && t.baseId==p.baseId && !t.cpPaused && roll(i.characterId,i.target,now)<threshold
                return pay(s).let {if(it.rejection==null)it.copy(outcome=if(found)1 else 3,found=if(found)maxOf(t.spotId,1) else 0) else it}
            }
            Justice.RAID -> {
                if(!holds || i.card !in Justice.raidCards)return reject(SocialRejection.CARD)
                val t=targetNav ?: return reject(SocialRejection.LOCATION)
                if(targetCountry==p.country)return reject(SocialRejection.TARGET)
                val sameSpot=p.spotId!=0 && t.baseId==p.baseId && t.spotId==p.spotId && t.spotOwner==p.spotOwner && !t.cpPaused
                if(!sameSpot)return reject(SocialRejection.LOCATION)
                val guard=if(p.spotOwner==0) CivicActions.guardStrength(s,p.baseId,p.spotId,now) else 1
                val threshold=Justice.arrestThreshold(intelligence(s,ids,i.characterId),intelligence(s,ids,i.target))-
                    CivicActions.guardPenalty(guard)
                val dice=roll(i.characterId,i.target,now)
                val outcome=when {dice<threshold-10 -> Outcome.ARRESTED;dice<threshold+10 -> Outcome.EVADED;else -> Outcome.FAILED}
                fun wound(n:Navigator)=n.hull?.let {n.copy(hull=it.copy(woundedUntil=maxOf(it.woundedUntil,now+Justice.WOUND_SECONDS)))} ?: n
                val targetKey=accounts.entries.single {it.value.characterId==i.target}.key
                val others=if(outcome==Outcome.ARRESTED)mapOf(targetKey to wound(t)) else emptyMap()
                val self=if(outcome==Outcome.FAILED)wound(p) else p
                return pay(s,self,others).let {if(it.rejection==null)it.copy(outcome=outcome.ordinal) else it}
            }
            Justice.WATCH -> {
                if(!holds || i.card !in Justice.raidCards)return reject(SocialRejection.CARD)
                val t=targetNav ?: return reject(SocialRejection.LOCATION)
                val current=j.watches[i.characterId]
                // While the watch lasts, asking again reports the target's current place without cost.
                if(current?.target==i.target && (current.country==own || current.country==null && own.value in 2..3))
                    return SocialResult(p,s,outcome=1,found=if(t.baseId==0)0 else maxOf(t.spotId,1))
                val sameSpot=p.spotId!=0 && t.baseId==p.baseId && t.spotId==p.spotId && t.spotOwner==p.spotOwner && !t.cpPaused
                if(!sameSpot)return reject(SocialRejection.LOCATION)
                return pay(s.copy(social=s.social.copy(justice=j.copy(watches=j.watches+(i.characterId to
                    Watch(i.characterId,i.target,now,now+Justice.DAY,own)))))).let {if(it.rejection==null)it.copy(outcome=1,found=maxOf(t.spotId,1)) else it}
            }
            Justice.JUDGMENT -> {
                val d=j.detentions[i.target]?.takeIf {it.country==own && !it.severe} ?: return reject(SocialRejection.STALE)
                if(!holds || !Justice.judges(i.card,targetRank))return reject(SocialRejection.TARGET)
                val players=LinkedHashMap(accounts)
                val next=when(i.value) {
                    Justice.ACQUITTAL -> release(g,s,players,d)
                    Justice.LENIENT -> demote(release(g,s,players,d),i.target,1,true)
                    Justice.SEVERE -> s.copy(social=s.social.copy(justice=j.copy(detentions=j.detentions+(i.target to
                        d.copy(releaseAt=now+Justice.SEVERE_DAYS*Justice.DAY,severe=true)))))
                    else -> return reject(SocialRejection.COMMAND)
                }
                return pay(next,p,players.filter {(k,v)->accounts[k]!=v})
            }
        }
        return reject(SocialRejection.COMMAND)
    }
}
