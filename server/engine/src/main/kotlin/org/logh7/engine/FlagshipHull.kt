package org.logh7.engine

/** Server-designed hull points, six shield work pools and injury delay. Author: Peppone Choi. */
data class FlagshipHull(val kind: Int, val at: Long, val hp: Int = 100, val woundedUntil: Long = 0,
    val clockScale: Long = 24, val shields: List<Long> = List(6) { capacity(kind) * 100L * clockScale }) {
    init {
        require(Flagships.find(kind) != null && at in 0..0xffffffffL && hp in 1..100 &&
            woundedUntil in 0..0xffffffffL && clockScale in 1..1000 && shields.size == 6 &&
            shields.all {it in 0..capacity(kind)*100L*clockScale})
    }
    fun settle(seconds:Long,energy:TacticalEnergy):FlagshipHull {
        require(seconds in at..0xffffffffL)
        val capacity=capacity(kind)*100L*clockScale
        return copy(at=seconds,shields=shields.mapIndexed {face,work ->
            minOf(capacity,work+(seconds-at)*energy.values[5+face])})
    }
    fun levels() = shields.map {(it/(100*clockScale)).toInt()}
    companion object {
        fun capacity(kind:Int) = checkNotNull(Flagships.find(kind)).number("シールド（旗艦のみ）:容量")
    }
}

internal object FlagshipRecovery {
    /** Manual: return planet, injury, destroyer replacement (REVIEW05). Fallback and 24G-hour delay are design. */
    fun defeated(player:Navigator,seconds:Long,galaxy:Galaxy,stocks:PlanetStocks):Navigator {
        val power=if(player.power==Power.EMPIRE)2 else 3
        val bases=galaxy.bases.filter { base ->
            val stock=stocks.bases[base.id]
            val initial=galaxy.conditions.getValue(base.id)
            initial.habitability==1 && (stock?.power ?: initial.power)==power && (stock?.camp ?: initial.camp)==0
        }
        val home=bases.singleOrNull {it.id==player.returnBase}
            ?: bases.singleOrNull {it.grid==galaxy.starts.getValue(player.power) &&
                it.name==if(player.power==Power.EMPIRE)"オーディン" else "ハイネセン"}
            ?: bases.filter {it.grid==galaxy.starts.getValue(player.power)}.minByOrNull {it.id}
            ?: bases.minByOrNull {it.id}
        val until=minOf(0xffffffffL,seconds+86400)
        val kind=if(player.power==Power.EMPIRE)3 else 93
        return player.copy(grid=home?.grid ?: galaxy.starts.getValue(player.power),baseId=home?.id ?: 0,
            mode=if(home==null)5 else 4,spotId=0,spotOwner=0,cpPaused=false,cpRecoveryAt=seconds,
            motion=null,retreat=null,charge=null,fire=null,attack=null,commandRange=null,energy=TacticalEnergy.ZERO,
            fuelReadyAt=null,strategy=null,dockTarget=null,supplies=0,
            hull=FlagshipHull(kind,seconds,woundedUntil=until,clockScale=player.hull?.clockScale ?: 24))
    }
}
