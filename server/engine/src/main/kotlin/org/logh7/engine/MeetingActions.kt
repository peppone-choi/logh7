package org.logh7.engine

import org.logh7.engine.strategy.*

/** Undirected friendship 0..100 (default 50) and the per-pair daily 会見 limit, keyed by [MeetingActions.pair]. */
data class MeetingState(val friendship:Map<Long,Int> = emptyMap(),val nextAt:Map<Long,Long> = emptyMap()) {
    init {require(friendship.size<=MeetingActions.PAIRS && nextAt.size<=MeetingActions.PAIRS &&
        friendship.all {(k,v)->MeetingActions.valid(k) && v in 0..100} && nextAt.all {(k,at)->MeetingActions.valid(k) && at in 0..0xffffffffL})}
    fun friendship(a:Int,b:Int)=friendship[MeetingActions.pair(a,b)] ?: MeetingActions.INITIAL
    fun detached()=copy(friendship=java.util.Collections.unmodifiableMap(LinkedHashMap(friendship)),
        nextAt=java.util.Collections.unmodifiableMap(LinkedHashMap(nextAt)))
}
/** [gained] the friendship change of the last commit. */
data class MeetingView(val ticket:Int,val target:Int,val friendship:Int,val wait:Long,val gained:Int=0)

/**
 * 会見(87), constmsg group 18 #87: MCP 10, a person in the same spot. Friendship values, +3 and the
 * once-per-pair-per-G-day limit are the design approved 2026-10-30 (design-warp-academy-meeting.md §3, evidence:guess).
 * Author: Peppone Choi.
 */
object MeetingActions {
    const val MEETING=87
    const val INITIAL=50
    const val GAIN=3
    const val PAIRS=4096
    fun pair(a:Int,b:Int):Long {require(a>0 && b>0 && a!=b);return (minOf(a,b).toLong() shl 32) or maxOf(a,b).toLong()}
    fun valid(key:Long)=(key ushr 32).let {low->low in 1..Int.MAX_VALUE.toLong() && (key and 0xffffffffL).let {it in 1..Int.MAX_VALUE.toLong() && it>low}}

    fun evaluate(r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,navigators:Collection<Navigator>):SocialResult {
        val i=r.identity;val m=s.social.meeting
        fun reject(reason:SocialRejection,view:MeetingView?=null)=SocialResult(p,s,reason,meeting=view)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(i.command!=MEETING || r.phase !in 0..1 || (if(r.phase==0)r.ticket!=0 else r.ticket<=0) || i.target<=0 ||
            i.target==i.characterId || i.outfit!=0 || i.base!=0 || i.value!=0 || i.amount!=0L || i.argument!=0)return reject(SocialRejection.COMMAND)
        val now=session.gameMillis/1000
        val key=pair(i.characterId,i.target)
        val wait=((m.nextAt[key] ?: 0)-now).coerceAtLeast(0)
        val view=MeetingView(s.social.nextId,i.target,m.friendship(i.characterId,i.target),wait)
        if(r.phase==0)return SocialResult(p,s,meeting=view)
        if(r.ticket!=s.social.nextId)return reject(SocialRejection.STALE,view)
        if(i.card!=0)return reject(SocialRejection.CARD,view)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null || p.warpTarget!=null)return reject(SocialRejection.BUSY,view)
        // The partner need not be online; its saved place must be this exact spot (and private-room owner).
        val other=navigators.singleOrNull {it.generation==i.generation && it.characterId==i.target}
        if(other==null || p.mode !in 1..4 || p.spotId==0 || other.grid!=p.grid || other.baseId!=p.baseId ||
            other.spotId!=p.spotId || other.spotOwner!=p.spotOwner)return reject(SocialRejection.LOCATION,view)
        if(wait>0)return reject(SocialRejection.COOLDOWN,view)
        if(now>0xffffffffL-SocialActions.DAY || s.social.nextId==Int.MAX_VALUE || view.friendship>=100 ||
            key !in m.friendship && m.friendship.size==PAIRS || key !in m.nextAt && m.nextAt.size==PAIRS)return reject(SocialRejection.MAXIMUM,view)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),10,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP,view)
        val value=minOf(100,view.friendship+GAIN)
        val meeting=MeetingState(m.friendship+(key to value),m.nextAt.filterValues {it>now}+(key to now+SocialActions.DAY))
        val next=s.copy(social=s.social.copy(nextId=s.social.nextId+1,meeting=meeting))
        return SocialResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,changed=true,
            meeting=MeetingView(next.social.nextId,i.target,value,SocialActions.DAY,value-view.friendship))
    }
}
