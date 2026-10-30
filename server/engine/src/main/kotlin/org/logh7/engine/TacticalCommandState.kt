package org.logh7.engine

/** Additional native tactical commands. Opaque bytes are retained only for native replies.
 * Timing, CP costs, formation geometry and combat magnitudes are replacement policy. Author: 최병호. */
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
    val checkedAt:Long=assignedAt,val completed:Boolean=false,val grid:Int?=null) {
    init {require(character>0 && mission in 0..5 && targetKind in 0..255 && target>=0 && assignedAt in 0..0xffffffffL && checkedAt in assignedAt..0xffffffffL && (grid==null || grid in 0..4999))}
}
data class TacticalAirWing(val character:Int,val grid:Int,val source:Int,val target:Int,val mode:Int,val nextAt:Long,
    val slowVelocity:Float?=null) {
    init {require(character>0 && grid in 0..4999 && source>0 && target>=0 && mode in 0..1 && nextAt in 0..0xffffffffL &&
        (slowVelocity==null || slowVelocity.isFinite() && slowVelocity in 0f..1f))}
}
data class TacticalFortressMotion(val grid:Int,val motion:TacticalMotion,val reportedAt:Long=motion.acceptedAt) {
    init {require(grid==motion.grid && reportedAt in motion.acceptedAt..0xffffffffL)}
}
data class TacticalCommandState(val orders:List<TacticalQueuedCommand> = emptyList(),
    val morale:Map<Int,Int> = emptyMap(),val authority:Map<Int,Int> = emptyMap(),val formations:Map<Int,Int> = emptyMap(),
    val missions:Map<Int,TacticalMissionState> = emptyMap(),val proposals:Map<Int,TacticalMissionState> = emptyMap(),
    val air:Map<Int,TacticalAirWing> = emptyMap(),val fortresses:Map<Int,TacticalFortressMotion> = emptyMap(),
    val ranks:Map<Int,Int> = emptyMap(),val merits:Map<Int,Long> = emptyMap(),val emergencySupplies:Map<Int,Int> = emptyMap(),
    val formationLeaders:Map<Int,Int> = emptyMap(),val aiSwitches:Map<Int,Int> = emptyMap(),
    val creditedMerits:Map<Int,Long> = emptyMap(),val aiNextAt:Map<Int,Long> = emptyMap()) {
    init {require(orders.size<=1000 && listOf(morale.size,authority.size,formations.size,missions.size,proposals.size,air.size,ranks.size,merits.size).all {it<=1000} &&
        morale.all {(id,v)->id>0 && v in 0..100} && authority.all {(id,v)->id in 1..999999 && v>0} &&
        formations.all {(id,v)->id>0 && v in 0..7} && missions.all {(id,v)->id==v.character} && proposals.all {(id,v)->id==v.character} &&
        air.all {(id,v)->id==v.source} && fortresses.size<=350 && fortresses.keys.all {it in 1..65535} &&
        ranks.all {(id,v)->id>0 && v>=0} && merits.all {(id,v)->id>0 && v>=0} &&
        emergencySupplies.size<=350 && emergencySupplies.all {(id,power)->id in 1..65535 && power in 2..3} &&
        formationLeaders.size<=1000 && formationLeaders.all {(unit,leader)->unit>0 && leader>0} &&
        aiSwitches.size<=1000 && aiSwitches.all {(id,v)->id>0 && v in 0..31} &&
        creditedMerits.size<=1000 && creditedMerits.all {(id,v)->id>0 && v in 0..(merits[id] ?: 0)} &&
        aiNextAt.size<=1000 && aiNextAt.all {(id,v)->id>0 && v in 0..0xffffffffL})}
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
    val stateOnly:Boolean=false,val merits:Map<Int,Long> = emptyMap())

/** Tactical reassignment changes per-unit command, never the strategic personnel appointment. */
object TacticalAuthority {
    fun commander(outfit:OutfitStock,unit:ActiveShipUnit,stocks:PlanetStocks)=stocks.tacticalCommands.authority[unit.id] ?: outfit.commanderId
}
