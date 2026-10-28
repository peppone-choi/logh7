package org.logh7.engine

import kotlin.math.cos
import kotlin.math.sin

/** Native 004b2740 orbit and x/z projection (wire x/y). Author: 최병호. */
object TacticalBasePosition {
    fun at(base:GalaxyBase,seconds:Long):TacticalPoint {
        require(seconds in 0..0xffffffffL)
        val tau=6.2831855f
        val offset=base.angle*0.017453292f
        // The x87 client rounds the offset and final angle to float, not each intermediate operation.
        // 004b284c/004b284d use cdq/idiv on the low 32 bits of the wire clock.
        val elapsed=if(base.kind==1)0 else seconds.toInt()%base.cycle
        var angle=(elapsed.toFloat().toDouble()/base.cycle.toFloat().toDouble()*tau.toDouble()+offset.toDouble()).toFloat()
        if(angle>3.1415927f)angle-=tau
        if(angle< -3.1415927f)angle+=tau
        if(base.direction!=0)angle=-angle
        return TacticalPoint((sin(angle.toDouble())*base.radius).toFloat(),(cos(angle.toDouble())*base.radius).toFloat())
    }
}
