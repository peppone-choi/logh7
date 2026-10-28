package org.logh7.protocol

import java.nio.ByteBuffer

/** 0048cb80: u8 state followed by u32 grid, with no native padding. Author: 최병호. */
object TacticsMessages {
    data class Control(val character: Int, val unit: Int, val energy: List<Int>) {
        init { require(character > 0 && unit > 0 && energy.size == 11 && energy.all { it in 0..255 }) }
    }
    fun control(message: ByteArray): Control {
        require(message.size == 31)
        val input = ByteBuffer.wrap(message)
        require(input.short.toInt() and 65535 == 0x040c)
        input.int; input.int // Client time and wait do not choose the server schedule.
        val character = input.int; val unit = input.int
        input.short // Reserve is recomputed from the authenticated flagship's output.
        val beam = input.get().toInt() and 255; val gun = input.get().toInt() and 255
        val shields = List(6) { input.get().toInt() and 255 }
        val engine = input.get().toInt() and 255; val warp = input.get().toInt() and 255
        val sensor = input.get().toInt() and 255
        return Control(character,unit,listOf(engine,warp,sensor,beam,gun)+shields)
    }
    fun controlReply(control: Control, reserve: Int, seconds: Long, accepted: Boolean = true): ByteArray {
        require(reserve in 0..65535 && seconds in 0..0xffffffffL)
        val out = ByteBuffer.allocate(29).putInt(seconds.toInt()).putInt(0).putInt(control.character)
            .putInt(control.unit).putShort(reserve.toShort())
        out.put(control.energy[3].toByte()).put(control.energy[4].toByte())
        control.energy.drop(5).forEach {out.put(it.toByte())}
        control.energy.take(3).forEach {out.put(it.toByte())}
        return LoginMessages.sessionMessage(0x040c,out.array()).also {if(!accepted)ByteBuffer.wrap(it).putInt(0,1)}
    }
    data class Corps(val character: Int, val commandRange: Float, val energy: List<Int>,
        val shields: List<Int>, val mission: Int = 0, val targetKind: Int = 0, val target: Int = 0,
        val chief: Boolean = false, val formation: Int = 0, val beam: Int = 0, val gun: Int = 0,
        val damagedShields: List<Int> = List(6) { 0 }) {
        init {
            require(character > 0 && commandRange.isFinite() && commandRange >= 0)
            require(energy.size == 11 && energy.all { it in 0..255 })
            require(shields.size == 6 && damagedShields.size == 6 &&
                (shields + damagedShields + listOf(beam, gun)).all { it in 0..65535 })
            require(mission in 0..255 && targetKind in 0..255 && target >= 0 && formation in 0..127)
        }
    }
    data class Base(val id: Int,val x: Float,val y: Float,val z: Float)
    /** 00424330: 28 wire bytes, 32-byte native record. Defense defaults are provisional. */
    data class BaseInformation(val position:Base,val antiaircraft:Int=0,val cannonAngle:Int=0,
        val cannonStart:Long=0,val stamina:Int=100)
    /** 0040de80: a byte count and up to sixteen base IDs. */
    fun baseInformationQuery(message:ByteArray):List<Int> {
        require(message.size in 3..67)
        val input=ByteBuffer.wrap(message)
        require(input.short.toInt() and 65535==0x0344)
        val count=input.get().toInt() and 255
        require(count<=16 && input.remaining()==count*4)
        return List(count){input.int}.also {require(it.all {id->id>0} && it.distinct().size==it.size)}
    }
    fun baseInformation(bases:List<BaseInformation>):ByteArray {
        require(bases.size<=16 && bases.map {it.position.id}.distinct().size==bases.size)
        val out=ByteBuffer.allocate(1+28*bases.size).put(bases.size.toByte())
        bases.forEach {b->
            val p=b.position
            require(p.id>0 && listOf(p.x,p.y,p.z).all(Float::isFinite) && b.antiaircraft>=0 &&
                b.cannonAngle in 0..65535 && b.cannonStart in 0..0xffffffffL && b.stamina in 0..65535)
            out.putInt(p.id).putFloat(p.x).putFloat(p.y).putFloat(p.z).putInt(b.antiaircraft)
                .putShort(b.cannonAngle.toShort()).putInt(b.cannonStart.toInt()).putShort(b.stamina.toShort())
        }
        return LoginMessages.sessionMessage(0x0345,out.array())
    }
    data class Unit(val id: Int,val kind: Int,val outfit: Int,val character: Int,val supplies: Int,
        val cruise: Float,val destroyed: Int,val x: Float,val y: Float,val z: Float,val direction: Float,val damaged: Int = 0,
        val mode:Int=6,val base:Int=0)
    fun notify(grid: Int, active: Boolean): ByteArray {
        require(grid in 0..4999)
        return LoginMessages.sessionMessage(0x0f1f,ByteBuffer.allocate(5).put(if(active)1 else 0).putInt(grid).array())
    }
    fun queryIds(message: ByteArray, opcode: Int, byteCount: Boolean = false): List<Int> {
        val input=ByteBuffer.wrap(message)
        require(input.remaining()>=if(byteCount)3 else 4)
        require(input.short.toInt() and 65535==opcode)
        val count=if(byteCount)input.get().toInt() and 255 else input.short.toInt() and 65535
        require(count<=if(byteCount)4 else 600)
        require(input.remaining()==count*4)
        return List(count){input.int}.also {require(it.all {id->id>0} && it.distinct().size==it.size)}
    }
    /** On-base ground identities have no 033b spaceship state or 3D ship model. */
    data class GroundDefinition(val id:Int,val kind:Int,val outfit:Int,val base:Int)
    fun definitions(grid: Int, units: List<Unit>, ground:List<GroundDefinition> = emptyList()): ByteArray {
        val ids=units.map {it.id}+ground.map {it.id}
        require(grid in 0..4999 && ids.size<=600 && ids.distinct().size==ids.size)
        val out=ByteBuffer.allocate(2+42*ids.size).putShort(ids.size.toShort())
        units.forEach {u->
            require(u.id>0 && u.kind in 0..199 && u.outfit>=0 && u.supplies>=0 && u.destroyed in 0..65535 && u.damaged in 0..65535)
            require(u.mode in 4..7 && u.base in 0..65535 && (u.base>0)==(u.mode in 4..5))
            // 00419ca0: boarding_ship, troop_units count/list, THEN base.
            out.putInt(u.id).putShort(u.kind.toShort()).put(u.mode.toByte()).putInt(grid).putInt(u.outfit).putInt(0).put(0)
                .putInt(u.base).put(100).put(0).putShort(u.damaged.toShort()).putShort(u.destroyed.toShort()).putInt(u.supplies).putInt(0).putFloat(u.cruise)
        }
        ground.forEach {u ->
            require(u.id>0 && u.kind in 1000..1015 && u.outfit>0 && u.base in 1..65535)
            out.putInt(u.id).putShort(u.kind.toShort()).put(4).putInt(grid).putInt(u.outfit).putInt(0).put(0)
                .putInt(u.base).put(100).put(0).putShort(0).putShort(0).putInt(0).putInt(0).putFloat(0f)
        }
        return LoginMessages.sessionMessage(0x0325,out.array())
    }
    fun characters(grid: Int, ids: List<Int>): ByteArray {
        require(grid in 0..4999 && ids.size<=600 && ids.all {it>0} && ids.distinct().size==ids.size)
        val out=ByteBuffer.allocate(4+4*ids.size).putShort(grid.toShort()).putShort(ids.size.toShort())
        ids.forEach(out::putInt)
        return LoginMessages.sessionMessage(0x0337,out.array())
    }
    private fun validate(units: List<Unit>) {
        require(units.size<=600 && units.map {it.id}.distinct().size==units.size)
        require(units.all {it.id>0 && it.character>=0 && listOf(it.x,it.y,it.z,it.direction).all(Float::isFinite)})
    }
    fun states(units: List<Unit>): ByteArray {
        validate(units)
        val out=ByteBuffer.allocate(2+47*units.size).putShort(units.size.toShort())
        units.forEach {u->out.putInt(u.id).put(100).put(0).putInt(u.character)
            .putFloat(u.x).putFloat(u.y).putFloat(u.z).putFloat(u.direction).putInt(0)
            .putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f).put(1)}
        return LoginMessages.sessionMessage(0x033b,out.array())
    }
    /** 00422d80: 55 wire bytes per corps, versus the native padded 60-byte record. */
    fun corps(corps: List<Corps>): ByteArray {
        require(corps.size <= 600 && corps.map { it.character }.distinct().size == corps.size)
        val out = ByteBuffer.allocate(2 + 55 * corps.size).putShort(corps.size.toShort())
        corps.forEach { c ->
            out.putInt(c.character).put(c.mission.toByte()).put(c.targetKind.toByte()).putInt(c.target)
                .putFloat(c.commandRange).put(if (c.chief) 1 else 0).put(c.formation.toByte())
            c.energy.forEach { out.put(it.toByte()) }
            out.putShort(c.beam.toShort()).putShort(c.gun.toShort())
            c.shields.forEach { out.putShort(it.toShort()) }
            c.damagedShields.forEach { out.putShort(it.toShort()) }
        }
        return LoginMessages.sessionMessage(0x033f, out.array())
    }
    fun positions(units: List<Unit>): ByteArray {
        validate(units)
        val out=ByteBuffer.allocate(2+20*units.size).putShort(units.size.toShort())
        units.forEach {u->out.putInt(u.id).putFloat(u.x).putFloat(u.y).putFloat(u.z).putFloat(u.direction)}
        return LoginMessages.sessionMessage(0x0349,out.array())
    }
    fun basePositions(bases: List<Base>): ByteArray {
        require(bases.size<=4 && bases.map {it.id}.distinct().size==bases.size)
        require(bases.all {it.id>0 && listOf(it.x,it.y,it.z).all(Float::isFinite)})
        val out=ByteBuffer.allocate(1+16*bases.size).put(bases.size.toByte())
        bases.forEach {out.putInt(it.id).putFloat(it.x).putFloat(it.y).putFloat(it.z)}
        return LoginMessages.sessionMessage(0x034b,out.array())
    }
}
