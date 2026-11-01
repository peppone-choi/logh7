package org.logh7.engine

import org.logh7.engine.strategy.*

/**
 * U-25 phase 1a (design-arrest-judgment.md): 逮捕許可 69, 執行命令 70, 逮捕命令 71, 処断 19 through the 7f00 envelope.
 * [StrategyActionRequest.card] is the authority card (0 for a right holder's arrest), target the person, value the
 * charge (69), 0 grant / 1 revoke (70) or the verdict 1..3 (19); 70 names the listed person in base.
 * Author: Peppone Choi.
 */
object JusticeActions {
    enum class Outcome { NONE, ARRESTED, EVADED, FAILED }
    /** Deterministic arrest roll, like BattleDeath's per-event roll. */
    fun roll(actor:Int,target:Int,seconds:Long)=java.util.Random(seconds*1_000_003L+actor*131L+target*97L+29).nextInt(100)

    private fun rank(s:PlanetStocks,ids:List<StrategicIdentity>,id:Int)=
        s.strategic.people[id]?.rank ?: ids.singleOrNull {it.id==id}?.rank ?: 20
    private fun intelligence(s:PlanetStocks,ids:List<StrategicIdentity>,id:Int)=
        s.strategic.people[id]?.abilities?.getOrNull(3) ?: ids.singleOrNull {it.id==id}?.abilities?.getOrNull(3) ?: 50
    private fun powerOf(p:Navigator)=if(p.power==Power.EMPIRE)2 else 3

    /** Moves a person into its side's holding room, strips its offices and records the detention. */
    fun detain(g:Galaxy,s:PlanetStocks,players:MutableMap<String,Navigator>,target:Int,power:Int,now:Long):PlanetStocks? {
        val (capital,cell)=Justice.cell(g,power) ?: return null
        val key=players.entries.singleOrNull {it.value.characterId==target}?.key ?: return null
        val n=players.getValue(key)
        if(n.cpPaused)return null
        players[key]=n.copy(grid=capital.grid,baseId=capital.id,mode=4,spotId=cell,spotOwner=0,dockTarget=null,fuelReadyAt=null)
        val cleared=StrategyActions.clearOffices(s,target)
        val justice=cleared.social.justice
        return cleared.copy(social=cleared.social.copy(justice=justice.copy(permits=justice.permits-target,
            rights=justice.rights.filterValues {it.target!=target && it.holder!=target},
            detentions=justice.detentions+(target to Detention(target,power,now,now+Justice.DETENTION_DAYS*Justice.DAY)))))
    }
    /** Releases a detainee to its capital's central square (no other effect). */
    private fun release(g:Galaxy,s:PlanetStocks,players:MutableMap<String,Navigator>,d:Detention):PlanetStocks {
        val key=players.entries.singleOrNull {it.value.characterId==d.character}?.key
        val capital=Justice.cell(g,d.power)?.first
        if(key!=null && capital!=null)players[key]=players.getValue(key).let {
            if(it.spotId==Math.addExact(Math.multiplyExact(capital.id,10_000),19))it.copy(spotId=0,spotOwner=0) else it}
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
    fun settle(g:Galaxy,session:SessionState,players:MutableMap<String,Navigator>,s:PlanetStocks):PlanetStocks {
        val now=session.gameMillis/1000;val j=s.social.justice
        var next=s
        if(j.permits.any {it.value.until<=now} || j.rights.any {it.value.until<=now})
            next=next.copy(social=next.social.copy(justice=j.copy(permits=j.permits.filterValues {it.until>now},
                rights=j.rights.filterValues {it.until>now})))
        for(d in next.social.justice.detentions.values.filter {it.releaseAt<=now}.sortedBy {it.character}) {
            next=release(g,next,players,d)
            next=if(d.severe)demote(next,d.character,2,false) else demote(next,d.character,1,true)
        }
        return next
    }
    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,accounts:Map<String,Navigator>):SocialResult {
        val i=r.identity;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection)=SocialResult(p,s,reason)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null)return reject(SocialRejection.BUSY)
        val j=s.social.justice
        if(j.detained(i.characterId))return reject(SocialRejection.BUSY)
        val power=powerOf(p);val ids=i.identities
        val cost=Justice.costs[i.command] ?: return reject(SocialRejection.COMMAND)
        val holds=i.card!=0 && OrganizationalOfficePolicy(g).held(s,i.characterId).any {it.card==i.card} && Justice.power(i.card)==power
        if(i.card!=0 && !holds)return reject(SocialRejection.CARD)
        if(i.target<=0 || i.target==i.characterId)return reject(SocialRejection.TARGET)
        val targetNav=accounts.values.singleOrNull {it.generation==i.generation && it.characterId==i.target}
        val targetPower=targetNav?.let(::powerOf) ?: ids.singleOrNull {it.id==i.target}?.power ?: return reject(SocialRejection.TARGET)
        val targetRank=rank(s,ids,i.target)
        fun pay(next:PlanetStocks,player:Navigator=p,players:Map<String,Navigator> = emptyMap()):SocialResult {
            val paid=CpPayment.charge(CpBalance(player.pcp.toLong(),player.mcp.toLong()),cost,CpPool.MCP)
            if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP)
            return SocialResult(player.paidWith(paid.balance),next,changed=true,navigators=players)
        }
        when(i.command) {
            Justice.PERMIT -> {
                if(!holds || !Justice.arrests(i.card,targetRank) || targetPower!=power)return reject(SocialRejection.TARGET)
                if(i.value !in 1..7 || j.detained(i.target))return reject(SocialRejection.COMMAND)
                return pay(s.copy(social=s.social.copy(justice=j.copy(permits=j.permits+(i.target to
                    ArrestPermit(i.target,i.characterId,power,i.value,now+Justice.PERMIT_DAYS*Justice.DAY))))))
            }
            Justice.EXECUTION -> {
                val listed=j.permits[i.base]?.takeIf {it.power==power && it.until>now} ?: return reject(SocialRejection.STALE)
                if(!holds || !Justice.arrests(i.card,rank(s,ids,listed.target)) || targetPower!=power || i.target==listed.target)
                    return reject(SocialRejection.TARGET)
                val rights=if(i.value==1)j.rights.filterValues {!(it.holder==i.target && it.target==listed.target)}
                    else if(i.value==0)j.rights+(i.target to ArrestRight(i.target,listed.target,i.characterId,now+Justice.RIGHT_DAYS*Justice.DAY))
                    else return reject(SocialRejection.COMMAND)
                return pay(s.copy(social=s.social.copy(justice=j.copy(rights=rights))))
            }
            Justice.ARREST -> {
                val listed=j.permits[i.target]?.takeIf {it.power==power && it.until>now} ?: return reject(SocialRejection.STALE)
                val right=j.rights[i.characterId]?.takeIf {it.target==i.target && it.until>now}
                if(!(holds && Justice.arrests(i.card,targetRank)) && right==null)return reject(SocialRejection.CARD)
                val t=targetNav ?: return reject(SocialRejection.LOCATION)
                if(t.cpPaused || j.detained(i.target))return reject(SocialRejection.LOCATION)
                // Same spot, or the same grid aboard the same outfit (W p.73-74).
                val sameSpot=p.spotId!=0 && t.baseId==p.baseId && t.spotId==p.spotId && t.spotOwner==p.spotOwner
                val sameOutfit=t.grid==p.grid && s.outfits.values.any {i.characterId in it.members() && i.target in it.members()}
                if(!sameSpot && !sameOutfit)return reject(SocialRejection.LOCATION)
                val threshold=Justice.arrestThreshold(intelligence(s,ids,i.characterId),intelligence(s,ids,i.target))
                val dice=roll(i.characterId,i.target,now)
                val outcome=when {dice<threshold-10 -> Outcome.ARRESTED;dice<threshold+10 -> Outcome.EVADED;else -> Outcome.FAILED}
                val spent=if(right!=null && outcome!=Outcome.EVADED)j.rights-i.characterId else j.rights
                var next=s.copy(social=s.social.copy(justice=j.copy(rights=spent)))
                val players=LinkedHashMap(accounts)
                if(outcome==Outcome.ARRESTED)next=detain(g,next,players,i.target,power,now) ?: return reject(SocialRejection.LOCATION)
                val changed=players.filter {(k,v)->accounts[k]!=v}
                return pay(next,p,changed).let {if(it.rejection==null)it.copy(outcome=outcome.ordinal) else it}
            }
            Justice.JUDGMENT -> {
                val d=j.detentions[i.target]?.takeIf {it.power==power && !it.severe} ?: return reject(SocialRejection.STALE)
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
