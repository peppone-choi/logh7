package org.logh7.engine

private fun nativeBoatLimit(boatsPerUnit:Int)=minOf(65535,255*boatsPerUnit)

private fun boatsOfKind(kind:Int,boatsPerUnit:Int,ships:Map<UnitStack,Int>,spare:SpareShipBoats):Long =
    ships.entries.sumOf {if(it.key.kind==kind)it.value.toLong() else 0L}*boatsPerUnit+
        spare.values.entries.sumOf {if(it.key.kind==kind)it.value.toLong() else 0L}

private fun shipKinds(ships:Map<UnitStack,Int>,spare:SpareShipBoats)=
    (ships.keys+spare.values.keys).map {it.kind}.toSet()

/** Full units that fit in one native warehouse row, including existing boats of every grade. */
internal fun warehouseShipProductionRoom(kind:Int,ships:Map<UnitStack,Int>,spare:SpareShipBoats):Int {
    val boatsPerUnit=UnitSpecifications.ships.singleOrNull {it.kind==kind}?.number ?: return 0
    val held=boatsOfKind(kind,boatsPerUnit,ships,spare)
    if(held==0L && shipKinds(ships,spare).size>=99)return 0
    return ((nativeBoatLimit(boatsPerUnit)-held).coerceAtLeast(0)/boatsPerUnit).toInt()
}

/** Existing overflow may remain or shrink; inbound stock must not make it worse. */
internal fun warehouseShipIncreaseFits(before:Map<UnitStack,Int>,beforeSpare:SpareShipBoats,
    after:Map<UnitStack,Int>,afterSpare:SpareShipBoats):Boolean {
    val kinds=shipKinds(after,afterSpare)
    if(kinds.size>maxOf(99,shipKinds(before,beforeSpare).size))return false
    return kinds.all {kind->
        val size=UnitSpecifications.ships.singleOrNull {it.kind==kind}?.number
        val unitSize=size ?: 1
        val limit=size?.let(::nativeBoatLimit) ?: 0
        boatsOfKind(kind,unitSize,after,afterSpare)<=maxOf(limit.toLong(),boatsOfKind(kind,unitSize,before,beforeSpare))
    }
}
