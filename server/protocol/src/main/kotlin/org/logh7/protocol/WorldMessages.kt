package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** CD readers: 00413050, 004134e0, 004142e0. No native struct padding on the wire. */
object WorldMessages {
    data class Stock(val kind: Int, val level: Int, val quantity: Int)
    /** 0041a870: kind u16, level u8, quantity u16; native six-byte rows contain one padding byte. */
    fun warehouse(base: Int, supplies: Int, food: Int, mineral: Int,
        ships: List<Stock> = emptyList(), troops: List<Stock> = emptyList()) = body {
        require(base > 0 && supplies >= 0 && food >= 0 && mineral >= 0)
        fun stacks(values: List<Stock>, maximum: Int, kinds: IntRange) {
            require(values.size <= maximum && values.map { it.kind to it.level }.distinct().size == values.size)
            writeByte(values.size)
            values.forEach { value ->
                require(value.kind in kinds && value.level in 0..3 && value.quantity in 1..65535)
                writeShort(value.kind); writeByte(value.level); writeShort(value.quantity)
            }
        }
        writeInt(base); writeInt(0); writeInt(0)
        stacks(ships, 99, 0..199); stacks(troops, 24, 0..15)
        writeInt(supplies); writeInt(food); writeInt(mineral)
    }
    fun warehouseQuery(message: ByteArray): Pair<Int, Int> {
        require(message.size == 10)
        val input = java.nio.ByteBuffer.wrap(message)
        require(input.short.toInt() == 0x0326)
        return (input.int to input.int).also { require(it.first > 0 && it.second >= 0) }
    }
    data class Condition(val id: Int, val grid: Int, val power: Int, val camp: Int,
        val population: Int, val adults: Int, val tax: Int, val approval: Int, val peace: Int,
        val atmosphere: Int, val habitability: Int, val supplies: Int, val food: Int, val living: Int)
    /** 0040b9a0/0040bcf0: query uses base IDs, never grid indices or native padding. */
    fun baseQuery(message: ByteArray, opcode: Int): List<Int> {
        require(opcode == 0x031e || opcode == 0x0320)
        val input = java.nio.ByteBuffer.wrap(message)
        require(message.size >= 3 && input.short.toInt() == opcode)
        val count = input.get().toInt() and 255
        require(count <= 4 && input.remaining() == count * 4)
        val ids = List(count) { input.int.also { require(it > 0) } }
        require(ids.distinct().size == ids.size)
        return ids
    }
    /** 00414c70: economics and environment, including owning power/camp. */
    fun conditions(bases: List<Condition>) = body {
        require(bases.size <= 4)
        writeByte(bases.size)
        bases.forEach { base ->
            require(base.id > 0 && base.grid in 0..4999 && base.power in 0..4 && base.camp in 0..1)
            require(base.population >= 0 && base.adults in 0..base.population && base.tax in 0..100 &&
                base.approval in 0..100 && base.peace in 0..100 && base.atmosphere in 0..1 && base.habitability in 0..1 &&
                base.supplies >= 0 && base.food >= 0 && base.living >= 0)
            writeInt(base.id); writeByte(base.power); writeByte(base.camp); writeInt(base.grid)
            writeInt(0); writeFloat(1f); writeInt(0); writeInt(base.supplies)
            writeByte(0); writeByte(0) // outfit/transport supplies
            repeat(3) { writeInt(0) } // patrol, ground, defence supplies
            writeInt(base.population); writeInt(base.adults); writeShort(base.tax)
            writeByte(6); listOf(20, 20, 20, 20, 10, 10).forEach(::writeShort)
            writeByte(5); repeat(5) { writeInt(base.supplies / 5) }
            writeShort(base.approval); writeShort(base.peace); writeShort(50); writeShort(50)
            writeInt(base.food); writeInt(base.living)
            writeByte(3); repeat(3) { writeInt(base.supplies / 3) }
            writeFloat(1f); writeByte(base.atmosphere); writeByte(base.habitability)
            writeShort(0); writeByte(0); writeInt(0)
        }
    }
    data class Spot(val kind: Int, val id: Int, val file: Int)
    data class Institution(val kind: Int, val id: Int, val spots: List<Spot>)
    data class BaseInstitutions(val baseId: Int, val institutions: List<Institution>)
    /** 004167f0: at most four bases, 36 institutions/base, 20 spots/institution. */
    fun institutions(bases: List<BaseInstitutions>) = body {
        require(bases.size <= 4)
        writeByte(bases.size)
        bases.forEach { base ->
            require(base.baseId > 0 && base.institutions.size <= 36)
            writeInt(base.baseId); writeByte(base.institutions.size)
            base.institutions.forEach { institution ->
                require(institution.kind in 0..38 && institution.id > 0 && institution.spots.size <= 20)
                writeShort(institution.kind); writeInt(institution.id); writeByte(institution.spots.size)
                institution.spots.forEach { spot ->
                    require(spot.kind in 0..104 && spot.id > 0 && spot.file in 1..65535)
                    writeShort(spot.kind); writeInt(spot.id); writeShort(spot.file)
                }
            }
        }
    }
    data class Base(val id: Int, val grid: Int, val model: Int, val kind: Int, val name: String,
        val physicalClass: Int, val radius: Float, val cycle: Int, val direction: Int, val angle: Float, val diameter: Float)
    /** 0055e200: public base directory. Ownership is sent separately in 031f. */
    fun simpleBases(bases: List<Base>) = body {
        require(bases.size <= 200)
        writeByte(bases.size)
        bases.forEach { base ->
            writeInt(base.id); writeShort(base.grid); writeShort(base.kind); string(base.name)
        }
    }
    private fun body(write: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(write)
    }.toByteArray()
    private fun DataOutputStream.string(value: String) {
        require(value.length <= 12 && '\u0000' !in value)
        writeByte(value.length + 1); (value + '\u0000').forEach { writeChar(it.code) }
    }
    fun types(types: List<Triple<Int, Int, Int>>) = body {
        require(types.size in 1..100)
        writeByte(types.size)
        types.forEach { (kind, type, star) ->
            require(kind in 0..255 && type in 0..255 && star in 0..255)
            writeByte(kind); writeByte(type); writeByte(star)
        }
    }
    fun grid(cells: List<Int>): ByteArray {
        require(cells.size == 5000 && cells.all { it in 0..99 })
        val packed = body {
            var at = 0
            while (at < cells.size) {
                var count = 1
                while (at + count < cells.size && count < 255 && cells[at + count] == cells[at]) count++
                writeByte(count); writeByte(cells[at]); at += count
            }
        }
        require(packed.size in 2..5000)
        return body { writeByte(100); writeByte(50); writeShort(packed.size); write(packed) }
    }
    fun bases(bases: List<Base>) = body {
        require(bases.size <= 350)
        writeShort(bases.size)
        bases.forEach {
            writeInt(it.id); writeShort(it.grid); writeShort(it.model); writeShort(it.kind); string(it.name)
            writeByte(it.physicalClass); writeFloat(it.radius); writeInt(it.cycle); writeByte(it.direction)
            writeFloat(it.angle); writeFloat(it.diameter)
        }
    }
    private fun commands(card: Int) = when (card) {
        0 -> listOf(82, 83)
        59, 195 -> listOf(43, 44, 45, 60)
        else -> emptyList()
    }
    /** 0040f9f0: card kind, command list, three raw bit-mask fields. */
    fun captainCommands() = body {
        writeShort(261)
        for (card in 0..260) {
            writeShort(card)
            val commands = commands(card)
            writeByte(commands.size)
            commands.forEach { command ->
                writeShort(command)
                write(byteArrayOf(-1, -1, 31)); write(byteArrayOf(-1, 63)); writeByte(63)
            }
        }
    }
    /** The client indexes the card catalogue by card kind, rather than searching a sparse list. */
    fun cards() = body {
        writeShort(261)
        for (card in 0..260) {
            val power = when (card) { 59 -> 2; 195 -> 3; else -> 0 }
            writeShort(card); writeByte(if (power == 0) 0 else 1)
            writeByte(power); writeByte(0); writeByte(0)
            writeShort(0); writeShort(0); writeByte(0); writeByte(20); writeByte(0)
            writeShort(0); writeByte(0); writeShort(0)
            val commands = commands(card)
            writeByte(commands.size)
            commands.forEach(::writeShort)
        }
    }
    fun powerDirection() = ByteArray(1370).also {
        val out = java.nio.ByteBuffer.wrap(it)
        repeat(11) { out.putFloat(1f) }
        out.put(3).put(3) // Strategy warp radii; local navigation policy.
        repeat(4) { out.putFloat(3f) }
    }
    data class Ship(val kind: Int, val type: Int, val model: Int, val name: String,
        val outputPower: Int, val searchRange: Float, val speed: Float,
        val armorFront: Int, val armorSide: Int, val armorBack: Int, val shield: Int, val shieldCapacity: Int,
        val beam: Int, val gun: Int, val missile: Int, val antiaircraft: Int, val repair: Int)
    /** 004109a0/00411a60: kind, class and model_file are distinct fields. */
    fun ships(ships: List<Ship>) = body {
        require(ships.size <= 200 && ships.map { it.kind }.distinct().size == ships.size)
        writeByte(ships.size)
        ships.forEach { ship ->
            require(ship.kind in 0..199 && ship.type in 0..255)
            // Native 004f3e30: faction prefix * 1000 + model within faction,
            // not the index into the combined 120-entry file table.
            require(ship.model in 0..49 || ship.model in 1000..1049 ||
                ship.model in 2000..2009 || ship.model in 3000..3009)
            require(ship.searchRange.isFinite() && ship.searchRange >= 0 && ship.speed.isFinite() && ship.speed > 0)
            require(listOf(ship.outputPower, ship.armorFront, ship.armorSide, ship.armorBack, ship.shield,
                ship.shieldCapacity, ship.beam, ship.gun, ship.missile, ship.antiaircraft, ship.repair).all { it in 0..65535 })
            writeShort(ship.kind); writeByte(ship.type); writeByte(0); writeShort(0); writeShort(ship.model)
            string(ship.name)
            writeShort(1); writeInt(0) // number, price; no purchase price recovered
            repeat(5) { writeShort(0) } // resources, cost, term, existence, crew
            writeShort(ship.outputPower)
            writeFloat(0f); writeFloat(ship.searchRange)
            repeat(13) { writeShort(0) } // sensor profile, evasion, navigation
            writeFloat(ship.speed); writeFloat(1f) // turning rate is provisional
            listOf(ship.armorFront, ship.armorBack, ship.armorSide, ship.shield, ship.shieldCapacity).forEach(::writeShort)
            // Weapon counts and arcs remain unresolved; do not invent a combat fit.
            writeByte(0); writeShort(ship.beam); writeByte(0)
            writeByte(0); writeShort(ship.gun); writeByte(0)
            writeByte(0); writeShort(ship.missile); writeByte(0); writeShort(0)
            writeByte(0); writeShort(ship.antiaircraft)
            writeShort(0); writeShort(0); writeShort(0); writeShort(ship.repair); writeShort(0)
        }
    }
}
