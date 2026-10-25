package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Native 0041cba0 / 0041f3d0, variable wire records without C++ padding. Author: 최병호. */
object OutfitPartyMessages {
    data class Query(val outfit: Int, val base: Int, val mode: Int)
    data class UnitQuery(val id: Int, val base: Int)
    data class Ships(val kind: Int, val boats: Int, val ids: List<Int>)
    data class Troops(val kind: Int, val grade: Int, val quantity: Int)
    data class UnitInformation(val id: Int, val kind: Int, val grid: Int, val outfit: Int, val base: Int)
    fun query(bytes: ByteArray): Query {
        require(bytes.size == 11)
        val input = ByteBuffer.wrap(bytes)
        require(input.short.toInt() == 0x032e)
        return Query(input.int,input.int,input.get().toInt() and 255).also {
            require(it.outfit > 0 && it.base in 0..65535)
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
        supplies: Int, maximumSupplies: Int): ByteArray = body {
        require(query.outfit > 0 && query.base in 1..65535 && query.mode in 0..255 && power in 2..3 && kind in 0..3 && number in 0..255)
        require(ships.size <= 60 && troops.size <= 24 && supplies >= 0 && maximumSupplies >= 0)
        val ids = ships.flatMap { it.ids }
        require(ids.distinct().size == ids.size && ids.all { it > 0 })
        writeInt(query.outfit); writeInt(query.base); writeByte(query.mode)
        writeByte(power); writeByte(0); writeInt(kind); writeInt(number)
        writeByte(0) // No character memberships are represented by this response yet.
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
        writeInt(supplies); writeInt(maximumSupplies); writeShort(0) // No package inventory.
        writeByte(0); writeByte(0) // Other packages and troop packages.
        writeByte(0); writeByte(0); writeByte(0) // Empty transport slots and carrying flag.
        writeByte(0); writeByte(0) // No detached ships or troops.
    }
    fun units(units: List<UnitInformation>): ByteArray = body {
        require(units.size <= 70 && units.map { it.id }.distinct().size == units.size)
        writeByte(units.size)
        for (unit in units) {
            require(unit.id > 0 && unit.kind in 0..199 && unit.grid in 0..4999 && unit.outfit > 0 && unit.base in 1..65535)
            writeInt(unit.id); writeShort(unit.kind); writeByte(4) // Stationed at the outfit's base.
            writeInt(unit.grid); writeInt(unit.outfit); writeInt(0); writeByte(0) // No boarding ship / attached troop instances.
            writeInt(unit.base); writeByte(100); writeByte(0) // Initial morale, no rebellion.
            writeShort(0); writeShort(0) // No damage or destruction mechanics yet.
            writeInt(0); writeInt(0); writeFloat(0f) // Individual supplies, mobilization and cruising not assigned yet.
        }
    }
}
