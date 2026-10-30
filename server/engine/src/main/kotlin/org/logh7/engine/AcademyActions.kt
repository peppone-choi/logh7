package org.logh7.engine

import org.logh7.engine.strategy.*

/** A running 講義: valid for 120 G-minutes or until the lecturer leaves the classroom (W p.71). */
data class Lecture(val lecturer:Int,val base:Int,val spot:Int,val content:Int,val at:Long,val until:Long) {
    init {require(lecturer>0 && base in 1..65535 && spot>0 && content in 0..3 && at in 0..0xffffffffL &&
        until==at+AcademyActions.LECTURE_SECONDS)}
}
/** Ability experience (0..99 per ability, AbilityGrowthRules) and academy limits. */
data class AcademyState(val experience:Map<Int,List<Int>> = emptyMap(),val lectures:Map<Int,Lecture> = emptyMap(),
    val attendedUntil:Map<Int,Long> = emptyMap(),val wargames:Map<Int,Pair<Long,Int>> = emptyMap()) {
    init {require(experience.size<=4096 && experience.all {(id,e)->id>0 && e.size==8 && e.all {it in 0..99}} &&
        lectures.size<=256 && lectures.all {(id,l)->id==l.lecturer} && attendedUntil.size<=4096 &&
        attendedUntil.all {(id,at)->id>0 && at in 0..0xffffffffL} && wargames.size<=4096 &&
        wargames.all {(id,v)->id>0 && v.first>=0 && v.second in 1..AcademyActions.WARGAMES_PER_DAY})}
    fun detached()=copy(experience=java.util.Collections.unmodifiableMap(LinkedHashMap(experience.mapValues {java.util.Collections.unmodifiableList(ArrayList(it.value))})),
        lectures=java.util.Collections.unmodifiableMap(LinkedHashMap(lectures)),
        attendedUntil=java.util.Collections.unmodifiableMap(LinkedHashMap(attendedUntil)),
        wargames=java.util.Collections.unmodifiableMap(LinkedHashMap(wargames)))
}
/** Query/commit view: [content] -1 without a lecture; [gained] the experience of the last commit. */
data class AcademyView(val ticket:Int,val base:Int,val spot:Int,val content:Int,val lectureRemaining:Long,
    val ability:Int,val value:Int,val experience:Int,val wait:Long,val usesLeft:Int,val gained:Int=0)

/**
 * 受講(93)·兵棋演習(94)·講義(27), manual W69–71 and constmsg group 0 #94/#160/#161 (MCP 160/10/160, academy only).
 * Content→ability pairs, +50/+20/+5 experience, daily limits and the ability cap are the design approved
 * 2026-10-30 (docs/server/design-warp-academy-meeting.md, evidence:guess). Author: Peppone Choi.
 */
object AcademyActions {
    const val ATTEND=93
    const val WARGAME=94
    const val LECTURE=27
    const val LECTURE_SECONDS=120*60L
    const val WARGAMES_PER_DAY=5
    const val ABILITY_CAP=100
    val commands=setOf(ATTEND,WARGAME,LECTURE)
    /** Academy offices: empire principal/instructor 37/38, alliance 173/174 (constmsg group 3). */
    val lecturerCards=setOf(37,38,173,174)
    /** 戦術関係→指揮·機動, 戦史関係→攻撃·防御, 参謀要務→統率·運営, 特殊技能→政治·情報 (ability order of W p.14). */
    val contentAbilities=listOf(listOf(4,5),listOf(6,7),listOf(0,2),listOf(1,3))
    const val CLASSROOM=16
    const val SIMULATOR=17
    private const val ACADEMY=20

    fun evaluate(g:Galaxy,r:SocialRequest,session:SessionState,p:Navigator?,s:PlanetStocks,
        navigators:Collection<Navigator> = emptyList()):SocialResult {
        val i=r.identity;val a=s.social.academy
        fun reject(reason:SocialRejection,view:AcademyView?=null)=SocialResult(p,s,reason,academy=view)
        val member=session.participants[i.account]
        if(session.ended!=null || session.generation!=i.generation || s.generation!=i.generation || member==null ||
            !member.online || member.connection!=i.connection)return reject(SocialRejection.SESSION)
        if(p==null || p.generation!=i.generation || p.characterId!=i.characterId || p.power!=i.power || member.power!=i.power)
            return reject(SocialRejection.IDENTITY)
        val ability=i.argument
        if(i.command !in commands || r.phase !in 0..1 || (if(r.phase==0)r.ticket!=0 else r.ticket<=0) ||
            i.target!=0 || i.outfit!=0 || i.base!=0 || i.value!=0 || i.amount!=0L ||
            ability !in (if(i.command==LECTURE)0..3 else if(i.command==WARGAME)4..7 else 0..7))return reject(SocialRejection.COMMAND)
        val now=session.gameMillis/1000
        val person=s.strategic.people[i.characterId] ?: r.profile ?: StrategicPerson(rank=i.rank)
        val abilities=person.abilities ?: r.profile?.abilities ?: return reject(SocialRejection.IDENTITY)
        val power=if(p.power==Power.EMPIRE)2 else 3
        val base=s.bases[p.baseId]
        val room=if(i.command==WARGAME)SIMULATOR else CLASSROOM
        val inRoom=base!=null && base.power==power && base.camp==0 && p.mode in 1..4 && p.spotOwner==0 &&
            g.bases.single {it.id==p.baseId}.grid==p.grid &&
            g.facilitiesAt(p.baseId,s).any {f->f.kind==ACADEMY && f.spots.any {it.id==p.spotId && it.kind==room}}
        val lecture=if(!inRoom)null else a.lectures.values.filter {it.base==p.baseId && it.spot==p.spotId && it.until>now &&
            it.lecturer!=i.characterId && present(it,session.generation,navigators)}.minByOrNull {it.at}
        val exp=a.experience[i.characterId] ?: List(8) {0}
        val day=now/SocialActions.DAY
        val used=a.wargames[i.characterId]?.takeIf {it.first==day}?.second ?: 0
        val wait=when(i.command) {
            ATTEND->((a.attendedUntil[i.characterId] ?: 0)-now).coerceAtLeast(0)
            LECTURE->((a.lectures[i.characterId]?.until ?: 0)-now).coerceAtLeast(0)
            else->if(used>=WARGAMES_PER_DAY)(day+1)*SocialActions.DAY-now else 0}
        val own=a.lectures[i.characterId]?.takeIf {it.until>now}
        val shown=if(i.command==LECTURE)own else lecture
        val target=if(i.command==ATTEND && lecture!=null)contentAbilities[lecture.content][0] else if(i.command==LECTURE)
            contentAbilities[ability][0] else ability
        val view=AcademyView(s.social.nextId,p.baseId,p.spotId,shown?.content ?: -1,((shown?.until ?: now)-now).coerceAtLeast(0),
            target,abilities[target],exp[target],wait,if(i.command==WARGAME)WARGAMES_PER_DAY-used else if(wait>0)0 else 1)
        if(r.phase==0)return SocialResult(p,s,academy=view)
        if(r.ticket!=s.social.nextId)return reject(SocialRejection.STALE,view)
        if(i.command==LECTURE && (i.card !in lecturerCards ||
                s.strategic.offices.none {it.card==i.card && it.character==i.characterId}))return reject(SocialRejection.CARD,view)
        if(i.command!=LECTURE && i.card!=0)return reject(SocialRejection.CARD,view)
        if(p.cpPaused || p.fuelReadyAt!=null || p.dockTarget!=null || p.warpTarget!=null)return reject(SocialRejection.BUSY,view)
        if(!inRoom)return reject(SocialRejection.LOCATION,view)
        if(wait>0)return reject(SocialRejection.COOLDOWN,view)
        if(now>0xffffffffL-SocialActions.DAY || s.social.nextId==Int.MAX_VALUE ||
            i.characterId !in a.experience && a.experience.size==4096 || i.characterId !in s.strategic.people && s.strategic.people.size==4096 ||
            i.command==LECTURE && i.characterId !in a.lectures && a.lectures.size==256)return reject(SocialRejection.MAXIMUM,view)
        val gains:List<Pair<Int,Int>> = when(i.command) {
            ATTEND->lecture?.let {l->contentAbilities[l.content].map {it to 50}} ?: listOf(ability to 20)
            WARGAME->listOf(ability to 5)
            else->emptyList()}
        if(gains.isNotEmpty() && gains.all {(k,_)->abilities[k]>=ABILITY_CAP})return reject(SocialRejection.MAXIMUM,view)
        val paid=CpPayment.charge(CpBalance(p.pcp.toLong(),p.mcp.toLong()),if(i.command==WARGAME)10 else 160,CpPool.MCP)
        if(paid !is PaymentResult.Paid)return reject(SocialRejection.CP,view)
        val values=abilities.toMutableList();val points=exp.toMutableList()
        for((k,amount) in gains) {
            if(values[k]>=ABILITY_CAP)continue
            val total=points[k]+amount;values[k]=minOf(ABILITY_CAP,values[k]+total/100)
            points[k]=if(values[k]>=ABILITY_CAP)0 else total%100
        }
        val academy=when(i.command) {
            ATTEND->a.copy(attendedUntil=a.attendedUntil+(i.characterId to now+SocialActions.DAY))
            WARGAME->a.copy(wargames=a.wargames+(i.characterId to (day to used+1)))
            else->a.copy(lectures=a.lectures.filterValues {it.until>now}+(i.characterId to Lecture(i.characterId,p.baseId,p.spotId,ability,now,now+LECTURE_SECONDS)))
        }.let {if(gains.isEmpty())it else it.copy(experience=it.experience+(i.characterId to points.toList()))}
        val people=if(gains.isEmpty())s.strategic.people else s.strategic.people+(i.characterId to person.copy(abilities=values.toList()))
        val next=s.copy(strategic=s.strategic.copy(people=people),social=s.social.copy(nextId=s.social.nextId+1,academy=academy))
        val after=AcademyView(next.social.nextId,p.baseId,p.spotId,if(i.command==LECTURE)ability else lecture?.content ?: -1,
            if(i.command==LECTURE)LECTURE_SECONDS else ((lecture?.until ?: now)-now).coerceAtLeast(0),
            target,values[target],points[target],if(i.command==WARGAME && used+1<WARGAMES_PER_DAY)0 else SocialActions.DAY,
            if(i.command==WARGAME)WARGAMES_PER_DAY-used-1 else 0,gains.sumOf {it.second})
        return SocialResult(p.copy(pcp=paid.balance.pcp.toInt(),mcp=paid.balance.mcp.toInt()),next,changed=true,academy=after)
    }
    /** The lecture ends when its lecturer is no longer in that classroom (offline lecturers keep their saved spot). */
    private fun present(l:Lecture,generation:Long,navigators:Collection<Navigator>)=
        navigators.any {it.generation==generation && it.characterId==l.lecturer && it.baseId==l.base && it.spotId==l.spot}
}
