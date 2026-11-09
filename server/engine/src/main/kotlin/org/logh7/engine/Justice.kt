package org.logh7.engine

/**
 * U-25 逮捕·処断 (design-arrest-judgment.md, approved 2026-11-01). Command numbers are the CD's constmsg group 18:
 * 処断 19, 逮捕許可 69, 執行命令 70, 逮捕命令 71. Durations and the arrest odds are evidence:guess.
 * Author: Peppone Choi.
 */
data class ArrestPermit(val target:Int,val issuer:Int,val country:CountryId,val charge:Int,val until:Long) {
    init {require(target>0 && issuer>0 && charge in 1..7 && until in 0..0xffffffffL)}
}
/** A one-shot right to arrest [target], given to [holder] by an authority holder. */
data class ArrestRight(val holder:Int,val target:Int,val issuer:Int,val until:Long,val country:CountryId?=null) {
    init {require(holder>0 && target>0 && issuer>0 && holder!=target && until in 0..0xffffffffL)}
}
/** [releaseAt]: 14 days after arrest the detainee is judged 寛容 automatically; 厳罰 sets a fixed release. */
data class Detention(val character:Int,val country:CountryId,val since:Long,val releaseAt:Long,val severe:Boolean=false,
    /** Holding base, retained when its country's capital moves; 0 in legacy rows. */
    val base:Int=0) {
    init {require(character>0 && since in 0..releaseAt && releaseAt<=0xffffffffL && base in 0..65535)}
}
/** A watch (U-25) lasts until the target notices it; [checkAt] is the next daily detection roll. */
data class Watch(val watcher:Int,val target:Int,val since:Long,val checkAt:Long,val country:CountryId?=null) {
    init {require(watcher>0 && target>0 && watcher!=target && since in 0..checkAt && checkAt<=0xffffffffL)}
}
data class JusticeState(val permits:Map<Int,ArrestPermit> = emptyMap(),val rights:Map<Int,ArrestRight> = emptyMap(),
    val detentions:Map<Int,Detention> = emptyMap(),val watches:Map<Int,Watch> = emptyMap(),
    /** U-17 工作: infiltrations by character, armed bombs by character, bases whose production stops until a time. */
    val infiltrations:Map<Int,Infiltration> = emptyMap(),val bombs:Map<Int,Bomb> = emptyMap(),val disabled:Map<Int,Long> = emptyMap(),
    /** U-17 侵入: forged identities by character (user decision 2026-11-01: disguise included). */
    val disguises:Map<Int,Disguise> = emptyMap()) {
    init {require(permits.size<=4096 && permits.all {(k,v)->k==v.target} && rights.size<=4096 && rights.all {(k,v)->k==v.holder} &&
        detentions.size<=4096 && detentions.all {(k,v)->k==v.character} && watches.size<=4096 && watches.all {(k,v)->k==v.watcher} &&
        infiltrations.size<=4096 && infiltrations.all {(k,v)->k==v.character} && bombs.size<=4096 && bombs.all {(k,v)->k==v.character} &&
        disabled.size<=350 && disabled.all {(base,until)->base in 1..65535 && until in 0..0xffffffffL} &&
        disguises.size<=4096 && disguises.all {(k,v)->k==v.character})}
    fun detached()=copy(permits=java.util.Collections.unmodifiableMap(LinkedHashMap(permits)),
        rights=java.util.Collections.unmodifiableMap(LinkedHashMap(rights)),detentions=java.util.Collections.unmodifiableMap(LinkedHashMap(detentions)),
        watches=java.util.Collections.unmodifiableMap(LinkedHashMap(watches)),infiltrations=java.util.Collections.unmodifiableMap(LinkedHashMap(infiltrations)),
        bombs=java.util.Collections.unmodifiableMap(LinkedHashMap(bombs)),disabled=java.util.Collections.unmodifiableMap(LinkedHashMap(disabled)),
        disguises=java.util.Collections.unmodifiableMap(LinkedHashMap(disguises)))
    internal fun deadlines():Sequence<Long> = permits.values.asSequence().map {it.until} +
        rights.values.asSequence().map {it.until} + watches.values.asSequence().map {it.checkAt} +
        detentions.values.asSequence().map {it.releaseAt}
    fun detained(character:Int)=character in detentions
}

object Justice {
    const val JUDGMENT=19
    const val PERMIT=69
    const val EXECUTION=70
    const val ARREST=71
    /** Phase 2 (design §2): 一斉捜索 68 finds a person staying at the same base; 襲撃 73 attacks the other side. */
    const val SEARCH=68
    const val RAID=73
    const val WATCH=74
    /** 亡命 86, a personal-card command paid in PCP (design §3.5). */
    const val DEFECT=86
    val commands=setOf(JUDGMENT,PERMIT,EXECUTION,ARREST,SEARCH,RAID,WATCH,DEFECT)
    val costs=mapOf(JUDGMENT to 320L,PERMIT to 800L,EXECUTION to 800L,ARREST to 160L,SEARCH to 160L,RAID to 160L,WATCH to 160L)
    /** Daily chance (percent) that the target notices a watch: 30 + (target - watcher intelligence)/2, 5..60 (evidence:guess). */
    fun detection(watcherIntelligence:Int,targetIntelligence:Int)=(30+(targetIntelligence-watcherIntelligence)/2).coerceIn(5,60)
    /** Police and intelligence duties (CM group 6): 7 内務尚書, 10 司法尚書, 20 調査局長, 32 憲兵総監, 58 諜報官,
     * 137 法秩序委員長, 157 憲兵司令官, 158 査閲部長, 162 情報部長, 194 諜報官. Raids are for police/intelligence only. */
    val searchCards=setOf(7,10,20,32,58,137,157,158,162,194)
    val raidCards=setOf(20,32,58,157,158,162,194)
    /** A raid wounds the loser for one game day (approved design pattern, evidence:guess). */
    const val WOUND_SECONDS=86400L
    const val DAY=86400L
    const val PERMIT_DAYS=30L
    const val RIGHT_DAYS=7L
    const val DETENTION_DAYS=14L
    const val SEVERE_DAYS=60L
    const val ACQUITTAL=1
    const val LENIENT=2
    const val SEVERE=3

    /** Rank numbers: 1 politician, 2 元帥 .. 20 二等兵; colonel 8, commander 9 (OrganizationOffices rank names). */
    private fun military(rank:Int)=rank in 2..20
    /** Who a card may put on the list or arrest (CM group 6 duties, W p.60-63). */
    fun arrests(card:Int,rank:Int):Boolean = when(card) {
        7 -> rank in 8..20       // 内務尚書: colonels and below
        10,137 -> rank==1        // 司法尚書 / 法秩序委員長: politicians
        32 -> rank in 3..20      // 憲兵総監: soldiers except marshals
        157 -> military(rank)    // 憲兵司令官: all soldiers
        else -> false
    }
    /** Who a card may judge. */
    fun judges(card:Int,rank:Int):Boolean = when(card) {
        7 -> rank in 8..20
        10,137 -> rank==1
        160 -> rank in 9..20     // 人事部長: commanders (中佐) and below
        else -> false
    }
    /** The holding room (facility 0, room 18) of the actual country's currently owned capital. */
    fun cell(galaxy:Galaxy,stocks:PlanetStocks,country:CountryId,
        countries:CountryCatalog=CountryCatalogLoader.current()):Pair<GalaxyBase,Int>? {
        val seat=OrganizationalOfficePolicy(galaxy,countries).capital(stocks,country) ?: return null
        val capital=galaxy.bases.singleOrNull {it.id==seat} ?: return null
        val spot=Math.addExact(Math.multiplyExact(seat,10_000),19)
        if(galaxy.facilitiesAt(seat,stocks,countries).none {it.kind==0 && it.spots.any {room->room.id==spot}})return null
        return capital to spot
    }
    /** 0..99 roll below this succeeds; the next 20 evade (approved design §3.2, intelligence = ability 3). */
    fun arrestThreshold(actorIntelligence:Int,targetIntelligence:Int)=(50+(actorIntelligence-targetIntelligence)/2).coerceIn(20,80)
}
