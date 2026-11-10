package org.logh7.engine

import org.logh7.engine.strategy.*

/** One open invitation; a host has at most one. */
data class GatheringInvite(val host:Int,val guest:Int,val command:Int,val base:Int,val spot:Int,val expiresAt:Long) {
    init {require(host>0 && guest>0 && host!=guest && command in GatheringActions.commands && base in 1..65535 && spot>0 &&
        expiresAt in 0..0xffffffffL)}
}
data class GatheringState(val invites:Map<Int,GatheringInvite> = emptyMap(),val hostNextAt:Map<Int,Long> = emptyMap(),
    val pairNextAt:Map<Long,Long> = emptyMap()) {
    init {require(invites.size<=4096 && hostNextAt.size<=4096 && pairNextAt.size<=MeetingActions.PAIRS &&
        invites.all {(host,i)->host==i.host} && hostNextAt.all {(id,at)->id>0 && at in 0..0xffffffffL} &&
        pairNextAt.all {(k,at)->MeetingActions.valid(k) && at in 0..0xffffffffL})}
    fun detached()=copy(invites=java.util.Collections.unmodifiableMap(LinkedHashMap(invites)),
        hostNextAt=java.util.Collections.unmodifiableMap(LinkedHashMap(hostNextAt)),
        pairNextAt=java.util.Collections.unmodifiableMap(LinkedHashMap(pairNextAt)))
}
enum class GatheringGrade { GREAT, SUCCESS, FAILURE }
/** [invite] the open invitation involving the requester; [grade]/deltas only after an accepted gathering. */
data class GatheringView(val ticket:Int,val target:Int,val friendship:Int,val hostWait:Long,val invite:GatheringInvite?,
    val grade:GatheringGrade?=null,val hostInfluence:Int=0,val guestInfluence:Int=0,val friendshipDelta:Int=0)

/**
 * 会談(12)·談話(13), constmsg group 0 #79/#80: a host in its own hotel room invites a person; MCP 320, no wait.
 * Invitation flow, outcome formula and amounts are the design approved 2026-10-31
 * (design-social-gatherings.md, evidence:guess). value 0 invites [identity.target], 1 accepts and 2 declines
 * the invitation from host [identity.target]. Author: Peppone Choi.
 */
object GatheringActions {
    const val PARTY=10
    const val HUNT=11
    const val TALK=12
    const val DISCUSSION=13
    val commands=setOf(PARTY,HUNT,TALK,DISCUSSION)
    /** 狩猟/談話 change the host-guest friendship; 夜会 multiplies influence by 1.5 (rounded up). */
    fun friendly(command:Int)=command==HUNT || command==DISCUSSION
    const val MANSION=18
    const val BALLROOM=14
    const val FOREST=15
    const val COST=320L
    /** Sixty real seconds at the default 24x clock. */
    const val INVITE_SECONDS=1440L
    /** Friendship at which a guest who is not connected accepts an invitation. */
    const val NPC_ACCEPT=50
    const val HOTEL=17
    const val HOTEL_ROOM=13
    /** Spot ids are base*10000 + facility*200 + room + 1 (Places). */
    fun room(base:Int,spot:Int):Pair<Int,Int>? {
        val local=spot-base*10000-1
        return if(base<=0 || local<0 || local>=10000)null else local/200 to local%200
    }
    fun grade(score:Int)=when {score>=100->GatheringGrade.GREAT;score>=70->GatheringGrade.SUCCESS;else->GatheringGrade.FAILURE}
    fun score(politics:Int,host:Int,day:Long)=politics+java.util.Random(day*1_000_003L+host*131L+17).nextInt(50)
    fun hostInfluence(grade:GatheringGrade)=when(grade){GatheringGrade.GREAT->3;GatheringGrade.SUCCESS->1;GatheringGrade.FAILURE->-1}
    fun friendshipDelta(grade:GatheringGrade)=when(grade){GatheringGrade.GREAT->10;GatheringGrade.SUCCESS->5;GatheringGrade.FAILURE->-3}

    /** Pays the host and applies one gathering; null when the host cannot pay, a null result at the people limit. */
    private fun outcome(command:Int,hostId:Int,guestId:Int,host:Navigator,s:PlanetStocks,live:Map<Int,GatheringInvite>,
        friendship:Int,now:Long):Pair<PaymentResult.Paid,Pair<PlanetStocks,GatheringView>?>? {
        val g=s.social.gatherings
        val paid=CpPayment.charge(CpBalance(host.pcp.toLong(),host.mcp.toLong()),COST,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return null
        val people=s.strategic.people
        val hostPerson=people[hostId] ?: StrategicPerson()
        val guestPerson=people[guestId] ?: StrategicPerson()
        val politics=hostPerson.abilities?.get(1) ?: 50
        val grade=grade(score(politics,hostId,now/SocialActions.DAY))
        val hostGain=hostInfluence(grade).let {if(command==PARTY)(if(it>0)(it*3+1)/2 else -((-it*3+1)/2)) else it}
        val guestGain=if(grade==GatheringGrade.FAILURE)0 else 1
        val key=MeetingActions.pair(hostId,guestId)
        val pairReady=(g.pairNextAt[key] ?: 0)<=now
        val friendGain=if(friendly(command) && pairReady)friendshipDelta(grade) else 0
        if(hostId !in people && people.size>=4096 || guestId !in people && people.size>=4095)return paid to null
        val hostAfter=(hostPerson.influence+hostGain).coerceIn(0,100)
        val guestAfter=(guestPerson.influence+guestGain).coerceIn(0,100)
        val friendAfter=(friendship+friendGain).coerceIn(0,100)
        val meeting=if(friendGain==0)s.social.meeting else s.social.meeting.copy(friendship=s.social.meeting.friendship+(key to friendAfter))
        val pairs=if(friendly(command) && pairReady)g.pairNextAt.filterValues {it>now}+(key to now+SocialActions.DAY) else g.pairNextAt
        val gatherings=GatheringState(live-hostId,g.hostNextAt.filterValues {it>now}+(hostId to now+SocialActions.DAY),pairs)
        val next=s.copy(strategic=s.strategic.copy(people=people+(hostId to hostPerson.copy(influence=hostAfter))+
            (guestId to guestPerson.copy(influence=guestAfter))),
            social=s.social.copy(nextId=s.social.nextId+1,meeting=meeting,gatherings=gatherings))
        return paid to (next to GatheringView(next.social.nextId,hostId,friendAfter,0,null,grade,
            hostAfter-hostPerson.influence,guestAfter-guestPerson.influence,friendAfter-friendship))
    }

    fun evaluate(galaxy:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,navigators:Map<String,Navigator>,
        countries:CountryCatalog=CountryCatalogLoader.current()):SocialResult {
        val i=r.identity;val g=s.social.gatherings;val now=session.gameMillis/1000
        fun reject(reason:SocialRejection,view:GatheringView?=null)=SocialResult(p,s,reason,gathering=view)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || i.country==null || p.generation!=i.generation || p.characterId!=i.characterId || p.country!=i.country || member.country!=i.country)
            return reject(SocialRejection.IDENTITY)
        if(i.country.value !in countries.countries)return reject(SocialRejection.CARD)
        if(i.command !in commands || r.phase !in 0..1 || (if(r.phase==0)r.ticket!=0 else r.ticket<=0) || i.target<=0 ||
            i.target==i.characterId || i.value !in 0..2 || i.outfit!=0 || i.base!=0 || i.amount!=0L || i.argument!=0)
            return reject(SocialRejection.COMMAND)
        val hosting=i.value==0
        val hostId=if(hosting)i.characterId else i.target
        val guestId=if(hosting)i.target else i.characterId
        val live=g.invites.filterValues {it.expiresAt>now}
        val invite=live[hostId]?.takeIf {it.guest==guestId && it.command==i.command}
        val friendship=s.social.meeting.friendship(hostId,guestId)
        val view=GatheringView(s.social.nextId,i.target,friendship,((g.hostNextAt[hostId] ?: 0)-now).coerceAtLeast(0),invite)
        // A preview reports the open invitation in either direction: received from or sent to the target.
        if(r.phase==0)return SocialResult(p,s,gathering=view.copy(invite=live[i.target]?.takeIf {it.guest==i.characterId && it.command==i.command}
            ?: live[i.characterId]?.takeIf {it.guest==i.target && it.command==i.command}))
        if(r.ticket!=s.social.nextId)return reject(SocialRejection.STALE,view)
        if(i.card!=0)return reject(SocialRejection.CARD,view)
        if (i.value != 2 && (s.social.justice.detained(hostId) || s.social.justice.detained(guestId)))
            return reject(SocialRejection.BUSY, view)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null || p.warpTarget!=null)return reject(SocialRejection.BUSY,view)
        val hostEntry=navigators.entries.singleOrNull {it.value.generation==i.generation && it.value.characterId==hostId}
        val guest=navigators.values.singleOrNull {it.generation==i.generation && it.characterId==guestId}
        val host=hostEntry?.value
        // 会談/談話: own hotel room. 狩猟: own fief mansion forest. 夜会: own capital mansion ballroom,
        // for nobles under imperial peerage rules, otherwise politicians or commodores and above (evidence:guess).
        fun hostInRoom(h:Navigator):Boolean {
            val country=h.country ?: return false
            val definition=countries.countries[country.value] ?: return false
            val base=s.bases[h.baseId] ?: return false
            if(!base.belongsTo(country) || base.camp!=0 || h.mode !in 1..4 || h.cpPaused || h.navigationPending ||
                galaxy.bases.singleOrNull {it.id==h.baseId}?.grid!=h.grid)return false
            val facility=if(i.command in setOf(PARTY,HUNT))MANSION else HOTEL
            val spot=when(i.command) {PARTY->BALLROOM;HUNT->FOREST;else->HOTEL_ROOM}
            if(galaxy.facilitiesAt(h.baseId,s,countries).none {it.kind==facility && it.spots.any {it.id==h.spotId && it.kind==spot}})return false
            // Mansion rooms are owner-0 spots in the CD; the hotel room keeps its saved owner.
            if(h.spotOwner!=if(facility==MANSION)0 else h.characterId)return false
            val person=s.strategic.people[h.characterId]
            return when(i.command) {
                PARTY -> OrganizationalOfficePolicy(galaxy,countries).capital(s,country)==h.baseId &&
                    (if(countries.families.getValue(definition.family).peerage=="empire")person?.peerage!=null else
                        (person?.rank ?: if(h.characterId==i.characterId)i.rank else
                            i.identities.singleOrNull {it.id==h.characterId && it.country==country}?.rank).let {it==1 || it in 2..7})
                HUNT -> h.baseId in FundActions.governed(galaxy,s,h.characterId,country,countries,card=66)
                else -> true
            }
        }
        when(i.value) {
            0 -> {
                if(BattleDeath.deceased(s,guestId))return reject(SocialRejection.TARGET,view)
                if(!hostInRoom(p))return reject(SocialRejection.LOCATION,view)
                if(guest==null || guest.country!=p.country || guest.grid!=p.grid || guest.baseId!=p.baseId || guest.cpPaused)
                    return reject(SocialRejection.LOCATION,view)
                if(view.hostWait>0)return reject(SocialRejection.COOLDOWN,view)
                if(CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),COST,CpPool.MCP) !is PaymentResult.Paid)
                    return reject(SocialRejection.CP,view)
                if(now>0xffffffffL-INVITE_SECONDS || s.social.nextId==Int.MAX_VALUE || hostId !in live && live.size>=4096)
                    return reject(SocialRejection.MAXIMUM,view)
                // A guest who is not connected (an NPC or an offline player) answers at once: friendship 50 or more
                // accepts, less declines (user decision 2026-11-01, evidence:guess).
                val guestKey=navigators.entries.singleOrNull {it.value===guest}?.key
                if(guestKey==null || session.participants[guestKey]?.online!=true) {
                    if(friendship<NPC_ACCEPT)return SocialResult(p,s.copy(social=s.social.copy(nextId=s.social.nextId+1)),changed=true,
                        gathering=view.copy(ticket=s.social.nextId+1,invite=null))
                    val outcome=outcome(i.command,hostId,guestId,p,s,live,friendship,now) ?: return reject(SocialRejection.CP,view)
                    val (paid,result)=outcome;val (next,done)=result ?: return reject(SocialRejection.MAXIMUM,view)
                    return SocialResult(p.paidWith(paid.balance),next,changed=true,gathering=done.copy(hostWait=SocialActions.DAY))
                }
                val opened=GatheringInvite(hostId,guestId,i.command,p.baseId,p.spotId,now+INVITE_SECONDS)
                val next=s.copy(social=s.social.copy(nextId=s.social.nextId+1,gatherings=g.copy(invites=live+(hostId to opened))))
                return SocialResult(p,next,changed=true,gathering=view.copy(ticket=next.social.nextId,invite=opened))
            }
            2 -> {
                if(invite==null)return reject(SocialRejection.STALE,view)
                val next=s.copy(social=s.social.copy(nextId=s.social.nextId+1,gatherings=g.copy(invites=live-hostId)))
                return SocialResult(p,next,changed=true,gathering=view.copy(ticket=next.social.nextId,invite=null))
            }
        }
        // Accept: the host must still be in the invited room and the guest at the same base.
        if(invite==null || host==null || hostEntry.key==i.account)return reject(SocialRejection.STALE,view)
        if(BattleDeath.deceased(s,hostId))return reject(SocialRejection.TARGET,view)
        if(host.country!=p.country || !hostInRoom(host) || host.baseId!=invite.base || host.spotId!=invite.spot || p.grid!=host.grid || p.baseId!=invite.base)
            return reject(SocialRejection.LOCATION,view)
        val outcome=outcome(i.command,hostId,guestId,host,s,live,friendship,now) ?: return reject(SocialRejection.CP,view)
        if(outcome.second==null)return reject(SocialRejection.MAXIMUM,view)
        val (paid,result)=outcome;val (next,done)=checkNotNull(result)
        // The CD has no server-initiated room move, so the guest stays where it is (deviation from the design's §2.2-3).
        return SocialResult(p,next,changed=true,navigators=mapOf(hostEntry.key to host.paidWith(paid.balance)),
            // The view belongs to the guest: its own hosting wait, not the host's new limit.
            gathering=done.copy(hostWait=((g.hostNextAt[guestId] ?: 0)-now).coerceAtLeast(0)))
    }
}
