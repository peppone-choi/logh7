package org.logh7.engine

/**
 * U-25 逮捕·処断 (design-arrest-judgment.md, approved 2026-11-01). Command numbers are the CD's constmsg group 18:
 * 処断 19, 逮捕許可 69, 執行命令 70, 逮捕命令 71. Durations and the arrest odds are evidence:guess.
 * Author: Peppone Choi.
 */
data class ArrestPermit(val target:Int,val issuer:Int,val power:Int,val charge:Int,val until:Long) {
    init {require(target>0 && issuer>0 && power in 2..3 && charge in 1..7 && until in 0..0xffffffffL)}
}
/** A one-shot right to arrest [target], given to [holder] by an authority holder. */
data class ArrestRight(val holder:Int,val target:Int,val issuer:Int,val until:Long) {
    init {require(holder>0 && target>0 && issuer>0 && holder!=target && until in 0..0xffffffffL)}
}
/** [releaseAt]: 14 days after arrest the detainee is judged 寛容 automatically; 厳罰 sets a fixed release. */
data class Detention(val character:Int,val power:Int,val since:Long,val releaseAt:Long,val severe:Boolean=false) {
    init {require(character>0 && power in 2..3 && since in 0..releaseAt && releaseAt<=0xffffffffL)}
}
data class JusticeState(val permits:Map<Int,ArrestPermit> = emptyMap(),val rights:Map<Int,ArrestRight> = emptyMap(),
    val detentions:Map<Int,Detention> = emptyMap()) {
    init {require(permits.size<=4096 && permits.all {(k,v)->k==v.target} && rights.size<=4096 && rights.all {(k,v)->k==v.holder} &&
        detentions.size<=4096 && detentions.all {(k,v)->k==v.character})}
    fun detached()=copy(permits=java.util.Collections.unmodifiableMap(LinkedHashMap(permits)),
        rights=java.util.Collections.unmodifiableMap(LinkedHashMap(rights)),detentions=java.util.Collections.unmodifiableMap(LinkedHashMap(detentions)))
    fun detained(character:Int)=character in detentions
}

object Justice {
    const val JUDGMENT=19
    const val PERMIT=69
    const val EXECUTION=70
    const val ARREST=71
    val commands=setOf(JUDGMENT,PERMIT,EXECUTION,ARREST)
    val costs=mapOf(JUDGMENT to 320L,PERMIT to 800L,EXECUTION to 800L,ARREST to 160L)
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
    fun power(card:Int)=OrganizationOffices.of(card)?.power

    /** The holding room (facility 0, room 18) of [power]'s capital: spot base*10000+19. */
    fun cell(galaxy:Galaxy,power:Int):Pair<GalaxyBase,Int>? {
        val capital=galaxy.bases.singleOrNull {Capital.of(it)?.power==power} ?: return null
        return capital to Math.addExact(Math.multiplyExact(capital.id,10_000),19)
    }
    /** 0..99 roll below this succeeds; the next 20 evade (approved design §3.2, intelligence = ability 3). */
    fun arrestThreshold(actorIntelligence:Int,targetIntelligence:Int)=(50+(actorIntelligence-targetIntelligence)/2).coerceIn(20,80)
}
