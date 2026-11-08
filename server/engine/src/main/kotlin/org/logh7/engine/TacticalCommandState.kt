package org.logh7.engine

/** Additional native tactical commands. Opaque bytes are retained only for native replies.
 * Timing, CP costs, formation geometry and combat magnitudes are replacement policy. Author: Peppone Choi. */
data class TacticalCommand(val opcode:Int,val units:List<Int> = emptyList(),val base:Int=0,val kind:Int=0,
    val targetKind:Int=0,val target:Int=0,val velocity:Float=0f,val direction:Float=0f,
    val points:List<TacticalPoint> = emptyList(),val payload:List<Int> = emptyList()) {
    init {require(opcode in setOf(0x406,0x407,0x408,0x409,0x40b,0x40d,0x40e,0x419,0x41b,0x41c,0x41d,0x41e,0x41f,0x420,0x421,0x422) &&
        units.size<=32 && units.all {it>0} && units.distinct().size==units.size && base>=0 && target>=0 &&
        kind in 0..255 && targetKind in 0..255 && velocity.isFinite() && velocity in 0f..1f && direction.isFinite() &&
        points.size<=32 && payload.size<=800 && payload.all {it in 0..255})}
}
data class TacticalQueuedCommand(val character:Int,val grid:Int,val acceptedAt:Long,val readyAt:Long,val command:TacticalCommand) {
    init {require(character>0 && grid in 0..4999 && acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL)}
}
data class TacticalMissionState(val character:Int,val mission:Int,val targetKind:Int,val target:Int,val assignedAt:Long,
    val checkedAt:Long=assignedAt,val completed:Boolean=false,val grid:Int?=null,val defaultDefense:Boolean=false) {
    init {require(character>0 && mission in 0..5 && targetKind in 0..255 && target>=0 && assignedAt in 0..0xffffffffL && checkedAt in assignedAt..0xffffffffL && (grid==null || grid in 0..4999) &&
        (!defaultDefense || mission==1 && grid!=null && target>0))}
}
data class TacticalAirWing(val character:Int,val grid:Int,val source:Int,val target:Int,val mode:Int,val nextAt:Long,
    val slowVelocity:Float?=null) {
    init {require(character>0 && grid in 0..4999 && source>0 && target>=0 && mode in 0..1 && nextAt in 0..0xffffffffL &&
        (slowVelocity==null || slowVelocity.isFinite() && slowVelocity in 0f..1f))}
}
data class TacticalFortressMotion(val grid:Int,val motion:TacticalMotion,val reportedAt:Long=motion.acceptedAt) {
    init {require(grid==motion.grid && reportedAt in motion.acceptedAt..0xffffffffL)}
    companion object {
        /** The CD 004b3b20 041f record never writes its velocity word, so the speed is server-owned.
         * Mobile fortresses use the slowest ship speed (the 0.1 lower bound of ship speeds), evidence:guess. */
        const val VELOCITY=.1f
    }
}
data class TacticalCommandState(val orders:List<TacticalQueuedCommand> = emptyList(),
    val morale:Map<Int,Int> = emptyMap(),val authority:Map<Int,Int> = emptyMap(),val formations:Map<Int,Int> = emptyMap(),
    val missions:Map<Int,TacticalMissionState> = emptyMap(),val proposals:Map<Int,TacticalMissionState> = emptyMap(),
    val air:Map<Int,TacticalAirWing> = emptyMap(),val fortresses:Map<Int,TacticalFortressMotion> = emptyMap(),
    val ranks:Map<Int,Int> = emptyMap(),val merits:Map<Int,Long> = emptyMap(),
    /** Base -> actual country ID; legacy 2/3 records keep their meaning. */
    val emergencySupplies:Map<Int,Int> = emptyMap(),
    val formationLeaders:Map<Int,Int> = emptyMap(),val aiSwitches:Map<Int,Int> = emptyMap(),
    val creditedMerits:Map<Int,Long> = emptyMap(),val aiNextAt:Map<Int,Long> = emptyMap(),
    /** CD p.46 orbital attack on fortresses: armor damage taken (0..[FortressArmor.ARMOR]) and mobile fortresses whose propulsion broke. */
    val fortressDamage:Map<Int,Int> = emptyMap(),val brokenPropulsion:Set<Int> = emptySet(),
    /** Ground unit -> enemy ground unit it attacks (reconstructed 0417, design-ground-battle-window.md). */
    val groundTargets:Map<Int,Int> = emptyMap()) {
    init {require(orders.size<=1000 && listOf(morale.size,authority.size,formations.size,missions.size,proposals.size,air.size,ranks.size,merits.size).all {it<=1000} &&
        morale.all {(id,v)->id>0 && v in 0..100} && authority.all {(id,v)->id in 1..999999 && v>0} &&
        formations.all {(id,v)->id>0 && v in 0..7} && missions.all {(id,v)->id==v.character} && proposals.all {(id,v)->id==v.character} &&
        air.all {(id,v)->id==v.source} && fortresses.size<=350 && fortresses.keys.all {it in 1..65535} &&
        ranks.all {(id,v)->id>0 && v>=0} && merits.all {(id,v)->id>0 && v>=0} &&
        emergencySupplies.size<=350 && emergencySupplies.all {(id,country)->id in 1..65535 && country>0} &&
        formationLeaders.size<=1000 && formationLeaders.all {(unit,leader)->unit>0 && leader>0} &&
        aiSwitches.size<=1000 && aiSwitches.all {(id,v)->id>0 && v in 0..31} &&
        creditedMerits.size<=1000 && creditedMerits.all {(id,v)->id>0 && v in 0..(merits[id] ?: 0)} &&
        aiNextAt.size<=1000 && aiNextAt.all {(id,v)->id>0 && v in 0..0xffffffffL} &&
        fortressDamage.size<=350 && fortressDamage.all {(id,v)->id in 1..65535 && v in 1..FortressArmor.ARMOR} &&
        brokenPropulsion.size<=350 && brokenPropulsion.all {it in 1..65535} &&
        groundTargets.size<=1000 && groundTargets.all {(unit,target)->unit in 1..999999 && target in 1..999999 && unit!=target})}
    val empty get()=this==TacticalCommandState()
    fun confused(unit:Int)=morale[unit]?.let {it<=20}==true
    /** Native setting order: strategy, commanded, suggested, announcement, tactics. */
    fun ai(character:Int)=List(5){((aiSwitches[character] ?: 0) shr it) and 1}
    fun tacticalAi(character:Int)=((aiSwitches[character] ?: 0) and 16)!=0
    /** Actor snapshots must not expose caller-owned queues, command payloads or fortress paths. */
    fun detached():TacticalCommandState {
        fun <T> list(values:Collection<T>):List<T> = java.util.Collections.unmodifiableList(ArrayList(values))
        fun <K,V> map(values:Map<K,V>):Map<K,V> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        return copy(orders=list(orders.map {order->order.copy(command=order.command.let {
            it.copy(units=list(it.units),points=list(it.points),payload=list(it.payload))
        })}),morale=map(morale),authority=map(authority),formations=map(formations),missions=map(missions),
            proposals=map(proposals),air=map(air),fortresses=map(fortresses.mapValues {(_,fortress)->
                fortress.copy(motion=fortress.motion.let {motion->motion.copy(waypoints=list(motion.waypoints),
                    rotation=motion.rotation?.let {it.copy(selection=list(it.selection))})})
            }),ranks=map(ranks),merits=map(merits),emergencySupplies=map(emergencySupplies),formationLeaders=map(formationLeaders),
            aiSwitches=map(aiSwitches),creditedMerits=map(creditedMerits),aiNextAt=map(aiNextAt))
    }
}
data class TacticalCommandResult(val generation:Long,val order:TacticalQueuedCommand,val seconds:Long,
    val recipients:Map<Int,String>,val cancelled:Boolean=false,val motions:Map<Int,TacticalMotion> = emptyMap(),
    val destroyedUnits:List<Int> = emptyList(),val morale:Map<Int,Int> = emptyMap(),
    val stateOnly:Boolean=false,val merits:Map<Int,Long> = emptyMap(),
    /** Missions that ended this tick: character -> (mission kind, merit reward). */
    val finishedMissions:Map<Int,Pair<Int,Long>> = emptyMap())

/** Tactical reassignment changes per-unit command, never the strategic personnel appointment. */
object TacticalAuthority {
    fun commander(outfit:OutfitStock,unit:ActiveShipUnit,stocks:PlanetStocks)=stocks.tacticalCommands.authority[unit.id] ?: outfit.commanderId
    fun uncommanded(outfit:OutfitStock,stocks:PlanetStocks)=outfit.members().isEmpty() &&
        outfit.activeShips?.values.orEmpty().none {commander(outfit,it,stocks)>0}
    fun camp(player:Navigator,stocks:PlanetStocks)=Camp.of(player,CoupActions.rebel(stocks,player.characterId,player.country))
    fun camp(outfit:OutfitStock,stocks:PlanetStocks)=Camp(outfit.country,CoupActions.outfitCamp(stocks,outfit)==1)
    fun camp(base:PlanetStock):Camp?=base.country?.let {Camp(it,base.camp==1)}
    fun ownsBase(player:Navigator,owner:Allegiance,camp:Int,playerCamp:Int):Boolean =
        camp==playerCamp && when(owner) {
            is Allegiance.Country -> player.country==owner.id
            is Allegiance.Independent -> player.country==null && player.characterId==owner.characterId
            else -> false
        }

    /** Native captain-card variant; this display code never determines who owns a unit. */
    fun captainCard(player:Navigator,countries:CountryCatalog=CountryCatalogLoader.current()):Int? {
        val display=player.country?.let {countries.countries[it.value]?.nativePower ?: return null} ?: player.shipStyle.nativePower
        return when(display) {
            2->59;3->195;else->null
        }
    }
}

/** Existing outfit command takes precedence; unassigned local ground defenders follow their garrison office.
 * No appointment or delegation is made by choosing a commander in the ground window. Author: Peppone Choi (peppone-choi). */
object GroundCommandAuthority {
    fun garrison(base:Int,stocks:PlanetStocks,galaxy:Galaxy,countries:CountryCatalog=CountryCatalogLoader.current()):Int {
        val owner=stocks.bases[base] ?: return 0
        val country=owner.country ?: return 0
        if(owner.camp!=0)return 0
        val policy=OrganizationalOfficePolicy(galaxy,countries)
        fun holders(cards:Set<Int>)=cards.flatMap {policy.holders(stocks,it,country)}
            .filter {it.seat==base}.map {it.character}.distinct().singleOrNull()
        return holders(setOf(56,193)) ?: holders(setOf(52,55,188,191)) ?: 0
    }
    fun commander(outfit:OutfitStock,unit:ActiveGroundUnit,stocks:PlanetStocks,galaxy:Galaxy):Int {
        if(outfit.commanderId>0)return outfit.commanderId
        if(outfit.kind!=OutfitKind.GROUND || unit.base==0 || unit.origin!=0 || outfit.baseId!=unit.base ||
            outfit.positionIn(galaxy)!=OutfitPosition(galaxy.bases.single {it.id==unit.base}.grid,unit.base))return 0
        val owner=stocks.bases[unit.base] ?: return 0
        if(owner.country!=outfit.country || owner.camp!=0)return 0
        return garrison(unit.base,stocks,galaxy)
    }
}
