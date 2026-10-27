package org.logh7.persistence

import org.logh7.engine.FleetStaff

internal object FleetStaffCodec {
    fun encode(value:FleetStaff)="${value.deputy}/${value.chief}/"+value.officers.joinToString(",").ifEmpty {"-"}
    fun decode(value:String):FleetStaff {
        val f=value.split('/');require(f.size==3)
        return FleetStaff(f[0].toInt(),f[1].toInt(),if(f[2]=="-")emptyList() else f[2].split(',').map(String::toInt))
    }
}
