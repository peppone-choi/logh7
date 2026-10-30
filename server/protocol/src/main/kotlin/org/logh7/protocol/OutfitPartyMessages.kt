package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Native 0041cba0 / 0041f3d0, variable wire records without C++ padding. Author: Peppone Choi. */
object OutfitPartyMessages {
    data class Query(val outfit: Int, val base: Int, val mode: Int)
    data class UnitQuery(val id: Int, val base: Int)
    data class Member(val id: Int, val kind: Int, val rank: Int, val name: String)
    data class Ships(val kind: Int, val boats: Int, val ids: List<Int>)
    data class Troops(val kind: Int, val grade: Int, val quantity: Int)
    data class UnitInformation(val id: Int, val kind: Int, val grid: Int, val outfit: Int, val base: Int, val supplies: Int = 0,
        val damaged:Int=0,val destroyed:Int=0,val carrier:Int=0,val mode:Int=if(base==0)5 else 4)
    fun query(bytes: ByteArray): Query {
        require(bytes.size == 11)
        val input = ByteBuffer.wrap(bytes)
        require(input.short.toInt() == 0x032e)
        return Query(input.int,input.int,input.get().toInt() and 255).also {
            // Mode 2 is the revival ground-location page: base carries an exclusive unit-ID cursor.
            require(it.outfit > 0 && it.base in 0..(if(it.mode==2)999999 else 65535))
        }
    }
    fun unitQuery(bytes: ByteArray): UnitQuery {
        require(bytes.size == 10)
        val input = ByteBuffer.wrap(bytes)
        require(input.short.toInt() == 0x0330)
        return UnitQuery(input.int,input.int).also { require(it.id > 0 && it.base in 0..65535) }
    }
    private fun body(write: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).use { it.write() }; return bytes.toByteArray()
    }
    fun party(query: Query, power: Int, kind: Int, number: Int, ships: List<Ships>, troops: List<Troops>,
        supplies: Int, maximumSupplies: Int, members: List<Member> = emptyList()): ByteArray = body {
        require(query.outfit > 0 && query.base in 0..(if(query.mode==2)999999 else 65535) && query.mode in 0..255 && power in 2..3 && kind in 0..3 && number in 0..255)
        require(ships.size <= 60 && troops.size <= 24 && supplies in 0..maximumSupplies)
        val ids = ships.flatMap { it.ids }
        require(ids.distinct().size == ids.size && ids.all { it > 0 })
        writeInt(query.outfit); writeInt(query.base); writeByte(query.mode)
        writeByte(power); writeByte(0); writeInt(kind); writeInt(number)
        require(members.size <= 10 && members.map { it.id }.distinct().size == members.size)
        writeByte(members.size)
        for (member in members) {
            require(member.id > 0 && member.kind in 0..255 && member.rank in 0..255 && member.name.length <= 12 &&
                member.name.none { it == '\u0000' || it.isSurrogate() })
            writeInt(member.id); writeByte(member.kind); writeByte(member.rank)
            if (member.name.isEmpty()) writeByte(0) else {
                writeByte(member.name.length+1); (member.name+'\u0000').forEach { writeChar(it.code) }
            }
        }
        writeByte(ships.size)
        for (ship in ships) {
            require(ship.kind in 0..199 && ship.boats in 0..65535 && ship.ids.size in 1..70)
            writeShort(ship.kind); writeByte(ship.ids.size); writeShort(ship.boats); writeByte(ship.ids.size)
            ship.ids.forEach(::writeInt)
        }
        writeByte(troops.size)
        for (troop in troops) {
            require(troop.kind in 1000..1015 && troop.grade in 0..3 && troop.quantity in 1..65535)
            writeShort(troop.kind); writeByte(troop.grade); writeShort(troop.quantity)
        }
        // 00533980 adds both native fields: the second is free carrying room.
        writeInt(supplies); writeInt(maximumSupplies-supplies); writeShort(0) // No package inventory.
        writeByte(0); writeByte(0) // Other packages and troop packages.
        writeByte(0); writeByte(0); writeByte(0) // Empty transport slots and carrying flag.
        writeByte(0); writeByte(0) // No detached ships or troops.
    }
    fun units(units: List<UnitInformation>): ByteArray = body {
        require(units.size <= 70 && units.map { it.id }.distinct().size == units.size)
        writeByte(units.size)
        for (unit in units) {
            require(unit.id > 0 && (unit.kind in 0..199 || unit.kind in 1000..1015) && unit.grid in 0..4999 && unit.outfit > 0 && unit.base in 0..65535)
            require(unit.carrier in 0..999999 && unit.carrier!=unit.id && (unit.carrier==0 || unit.base==0))
            require(if(unit.kind<1000)unit.mode in 4..7 && unit.carrier==0
                else when(unit.mode){0->unit.base==0 && unit.carrier==0;4,9->unit.base>0 && unit.carrier==0;10->unit.carrier>0 && unit.base==0;else->false})
            writeInt(unit.id); writeShort(unit.kind); writeByte(unit.mode)
            writeInt(unit.grid); writeInt(unit.outfit); writeInt(unit.carrier); writeByte(0) // No nested troop list in this single-unit view.
            writeInt(unit.base); writeByte(100); writeByte(0) // Initial morale, no rebellion.
            require(unit.damaged in 0..65535 && unit.destroyed in 0..unit.damaged)
            writeShort(unit.damaged); writeShort(unit.destroyed)
            require(unit.supplies >= 0)
            writeInt(unit.supplies); writeInt(0); writeFloat(0f) // Mobilization and cruising not assigned yet.
        }
    }
}
