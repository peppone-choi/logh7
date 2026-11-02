package org.logh7.engine

/** Native CRC growth uses command ability and stamina (004b2740 unit update).
 * Maximum radius remains a provisional shared ship policy. Author: Peppone Choi. */
data class TacticalCommandRange(val at:Long,val radius:Float=0f,val command:Int=50,val stamina:Int=100) {
    init {require(at in 0..0xffffffffL && radius.isFinite() && radius in 0f..MAXIMUM &&
        command in 0..32767 && stamina in 0..100)}
    val growth:Double get()=(command.toDouble()*maxOf(80,stamina)/80000.0).coerceAtLeast(0.0000001)
    fun radius(now:Long):Float {
        require(now>=at)
        return (radius+(now-at)*growth).coerceAtMost(MAXIMUM.toDouble()).toFloat()
    }
    fun settle(now:Long,ability:Int=command)=copy(at=now,radius=radius(now),command=ability)
    fun reset(now:Long)=copy(at=now,radius=0f)
    companion object {const val MAXIMUM=20f}
}
