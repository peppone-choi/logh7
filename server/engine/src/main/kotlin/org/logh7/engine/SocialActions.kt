package org.logh7.engine

import org.logh7.engine.strategy.*

data class SpeechReceipt(val id:Int,val character:Int,val power:Int,val base:Int,val spot:Int,val at:Long,
    val politics:Int,val beforeInfluence:Int,val influence:Int,val beforeApproval:Int,val approval:Int,val nextAt:Long) {
    init {require(id in 1 until Int.MAX_VALUE && character>0 && power in 2..3 && base in 1..65535 && spot>0 &&
        at in 0..0xffffffffL && nextAt in at..0xffffffffL && politics in 0..100 &&
        beforeInfluence in 0..100 && influence in 0..100 && beforeApproval in 0..100 && approval in 0..100 &&
        influence==(beforeInfluence+if(politics>=50)1 else -1).coerceIn(0,100) &&
        approval==(beforeApproval+if(politics>=50)2 else -2).coerceIn(0,100) && nextAt==at+86400)}
    val influenceDelta get()=influence-beforeInfluence
    val approvalDelta get()=approval-beforeApproval
}
data class SocialState(val nextId:Int=1,val speeches:Map<Int,SpeechReceipt> = emptyMap(),
    val speakers:Map<Int,Long> = emptyMap(),val squares:Map<Int,Long> = emptyMap()) {
    init {require(nextId>0 && speeches.size<=256 && speeches.all {(id,r)->id==r.character && r.id<nextId} &&
        speeches.values.map {it.id}.distinct().size==speeches.size && speakers.size<=4096 && squares.size<=350 &&
        speakers.all {(id,at)->id>0 && at in 0..0xffffffffL} && squares.all {(base,at)->base in 1..65535 && at in 0..0xffffffffL} &&
        speeches.values.all {r->(speakers[r.character] ?: 0)>=r.nextAt && (squares[r.base] ?: 0)>=r.nextAt})}
    fun detached()=copy(speeches=java.util.Collections.unmodifiableMap(LinkedHashMap(speeches)),
        speakers=java.util.Collections.unmodifiableMap(LinkedHashMap(speakers)),squares=java.util.Collections.unmodifiableMap(LinkedHashMap(squares)))
}
data class SocialRequest(val identity:StrategyActionRequest,val phase:Int=0,val ticket:Int=0,val profile:StrategicPerson?=null)
enum class SocialRejection { SESSION, IDENTITY, COMMAND, CARD, RANK, LOCATION, BUSY, CP, COOLDOWN, STALE, MAXIMUM, STORAGE }
data class SocialView(val ticket:Int,val base:Int,val influence:Int,val approval:Int,val wait:Long,val last:SpeechReceipt?)
data class SocialResult(val player:Navigator?,val stocks:PlanetStocks?,val rejection:SocialRejection?=null,
    val changed:Boolean=false,val view:SocialView?=null,val recovery:NavigationResult?=null)

/** First social slice: speech only. Manual W73/native command14, MCP320, immediate.
 * Personal-card politician authority, deterministic politics threshold50, +/-1 influence,
 * +/-2 approval and separate speaker/base daily limits are restoration policy. Author: Peppone Choi. */
object SocialActions {
    const val DAY=86400L
    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks):SocialResult {
        val i=r.identity
        fun reject(reason:SocialRejection,view:SocialView?=null)=SocialResult(p,s,reason,view=view)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        if(i.command!=14 || r.phase !in 0..1 || (if(r.phase==0)r.ticket!=0 else r.ticket<=0) ||
            i.target!=0 || i.outfit!=0 || i.base!=0 || i.value!=0 || i.amount!=0L || i.argument!=0)return reject(SocialRejection.COMMAND)
        if(i.card!=0)return reject(SocialRejection.CARD)
        val person=s.strategic.people[i.characterId] ?: r.profile ?: StrategicPerson(rank=i.rank)
        val rank=person.rank ?: i.rank;val now=session.gameMillis/1000
        val base=s.bases[p.baseId]
        val nextAt=maxOf(s.social.speakers[i.characterId] ?: 0,s.social.squares[p.baseId] ?: 0)
        val view=SocialView(s.social.nextId,p.baseId,person.influence,base?.economy?.approval ?: 0,
            (nextAt-now).coerceAtLeast(0),s.social.speeches[i.characterId])
        if(r.phase==0)return SocialResult(p,s,view=view)
        if(r.ticket==view.last?.id)return SocialResult(p,s,view=view)
        if(r.ticket!=s.social.nextId)return reject(SocialRejection.STALE,view)
        if(rank!=1)return reject(SocialRejection.RANK,view)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null)return reject(SocialRejection.BUSY,view)
        val power=if(p.power==Power.EMPIRE)2 else 3
        if(base==null || base.power!=power || base.camp!=0 || p.mode !in 1..4 || p.spotOwner!=0 ||
            g.bases.single {it.id==p.baseId}.grid!=p.grid || g.facilitiesAt(p.baseId,s).none {facility ->
                facility.kind==2 && facility.spots.any {it.id==p.spotId && it.kind==3 && it.access==SpotAccess.PUBLIC}})
            return reject(SocialRejection.LOCATION,view)
        val economy=base.economy ?: return reject(SocialRejection.LOCATION,view)
        if(nextAt>now)return reject(SocialRejection.COOLDOWN,view)
        if(now>0xffffffffL-DAY || s.social.nextId==Int.MAX_VALUE ||
            i.characterId !in s.strategic.people && s.strategic.people.size==4096 ||
            i.characterId !in s.social.speakers && s.social.speakers.size==4096)return reject(SocialRejection.MAXIMUM,view)
        val politics=(person.abilities?.get(1) ?: i.politics).coerceIn(0,100)
        val direction=if(politics>=50)1 else -1
        val influence=(person.influence+direction).coerceIn(0,100);val approval=(economy.approval+direction*2).coerceIn(0,100)
        if(influence==person.influence && approval==economy.approval)return reject(SocialRejection.MAXIMUM,view)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),320,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP,view)
        val receipt=SpeechReceipt(s.social.nextId,i.characterId,power,base.baseId,p.spotId,now,politics,
            person.influence,influence,economy.approval,approval,now+DAY)
        val speeches=s.social.speeches.toMutableMap();speeches[i.characterId]=receipt
        if(speeches.size>256)speeches.remove(speeches.values.minBy {it.id}.character)
        val next=s.copy(strategic=s.strategic.copy(people=s.strategic.people+(i.characterId to person.copy(influence=influence))),
            bases=s.bases+(base.baseId to base.copy(economy=economy.copy(approval=approval))),
            social=SocialState(receipt.id+1,speeches,s.social.speakers+(i.characterId to receipt.nextAt),
                s.social.squares+(base.baseId to receipt.nextAt)))
        return SocialResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,changed=true,
            view=SocialView(next.social.nextId,base.baseId,influence,approval,DAY,receipt))
    }
}
