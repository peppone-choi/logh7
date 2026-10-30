package org.logh7.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** CD readers: 00413050, 004134e0, 004142e0. No native struct padding on the wire. */
object WorldMessages {
    /** Card institution byte for cards outside every constmsg group 4 organisation. */
    const val NO_INSTITUTION = 0xff
    data class Stock(val kind: Int, val level: Int, val quantity: Int)
    data class WarehouseShips(val kind: Int, val units: Int, val boats: Int)
    /** 0041a870/0041aff0: ships carry units/boats; troops carry grade/units. */
    fun warehouse(base: Int, supplies: Int, food: Int, mineral: Int,
        ships: List<WarehouseShips> = emptyList(), troops: List<Stock> = emptyList(), outfit: Int = 0) = body {
        require(base > 0 && outfit >= 0 && supplies >= 0 && food >= 0 && mineral >= 0)
        fun stacks(values: List<Stock>, maximum: Int, kinds: IntRange, kindOffset: Int = 0) {
            require(values.size <= maximum && values.map { it.kind to it.level }.distinct().size == values.size)
            writeByte(values.size)
            values.forEach { value ->
                require(value.kind in kinds && value.level in 0..3 && value.quantity in 1..65535)
                writeShort(value.kind + kindOffset); writeByte(value.level); writeShort(value.quantity)
            }
        }
        writeInt(base); writeInt(outfit); writeInt(0)
        require(ships.size <= 99 && ships.map { it.kind }.distinct().size == ships.size)
        writeByte(ships.size)
        ships.forEach { ship ->
            require(ship.kind in 0..199 && ship.units in 1..255 && ship.boats in 1..65535)
            writeShort(ship.kind); writeByte(ship.units); writeShort(ship.boats)
        }
        stacks(troops, 24, 0..15, 1000)
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
        0 -> listOf(14, 82, 83, 84, 96)
        59, 195 -> listOf(43, 44, 45, 48, 50, 52, 53, 54, 55, 56, 60, 61, 85)
        54 -> listOf(5,6,7,30)
        2,3,17,134,135,161,169,171 -> listOf(5,6,7)
        190,192 -> listOf(7,30)
        39,175 -> listOf(5,6,7,61,63,64)
        40,41,42,43,176,177,178,179 -> listOf(7)
        44,180 -> listOf(7,61,62,63,64)
        47,50,183,186 -> listOf(7,61,63,64)
        52,55,56,57,188,191,193 -> listOf(7)
        24,25,150 -> listOf(67)
        else -> emptyList()
    }
    fun commandsForCard(card:Int):List<Int> = commands(card)
    /** 0040f9f0: card kind, command list, three raw bit-mask fields. */
    fun captainCommands(overrides:Map<Int,List<Int>> = emptyMap()) = body {
        writeShort(261)
        for (card in 0..260) {
            writeShort(card)
            val commands = overrides[card] ?: commands(card)
            writeByte(commands.size)
            commands.forEach { command ->
                writeShort(command)
                write(byteArrayOf(-1, -1, 31)); write(byteArrayOf(-1, 63)); writeByte(63)
            }
        }
    }
    /** The client indexes the card catalogue by card kind, rather than searching a sparse list. */
    data class CardDefinition(val kind:Int,val power:Int,val camp:Int,val institution:Int,val authority:Int,
        val lowestRank:Int,val highestRank:Int,val commands:List<Int>)
    fun cards(overrides:Map<Int,CardDefinition> = emptyMap()) = body {
        writeShort(261)
        for (card in 0..260) {
            val definition=overrides[card]
            if(definition!=null) {
                require(definition.kind==card && definition.power in 2..3 && definition.camp in 0..1 &&
                    definition.institution in 0..25 && definition.authority in 0..260 &&
                    definition.lowestRank in 0..20 && definition.highestRank in 0..20 && definition.commands.size<=255)
                writeShort(card);writeByte(1);writeByte(definition.power);writeByte(definition.camp);writeByte(definition.institution)
                writeShort(definition.authority);writeShort(0);writeByte(definition.lowestRank);writeByte(definition.highestRank)
                writeByte(0);writeShort(0);writeByte(0);writeShort(0);writeByte(definition.commands.size)
                definition.commands.forEach(::writeShort)
                continue
            }
            val power = when (card) { 2,3,17,24,25,54,59,39,40,41,42,43,44,47,50,52,55,56,57 -> 2; 134,135,150,161,169,171,190,195,175,176,177,178,179,180,183,186,188,191,192,193 -> 3; else -> 0 }
            writeShort(card); writeByte(if (power == 0) 0 else 1)
            writeByte(power); writeByte(0)
            // 00579800 -> 004c8c40 uses this institution category to choose a base/outfit seat.
            writeByte(when(card) {
                // Card institutions use constmsg group 4, not physical facilities in group 73.
                // 0x4c8d50 returns no name for >= 0xff, so 004f5d50 shows the card alone
                // instead of "皇宮 : 艦長" for personal/captain cards that belong to no institution.
                0,59,195 -> NO_INSTITUTION
                2,3 -> 0; 169,171 -> 5; 17 -> 3; 134,135 -> 20; 161 -> 23
                24,25 -> 4; 150 -> 21
                54,55,190,191 -> 16
                56,57,192,193 -> 17
                52,188 -> 15
                in 39..43,in 175..179 -> 11
                44,180 -> 12
                47,183 -> 13
                50,186 -> 14
                else -> 0
            })
            // 004c9140: parent appointment-authority card, not a faction ID.
            writeShort(when(card) { 3 -> 2; 169 -> 135; 171 -> 169; 40 -> 17; 41 -> 3; 42,43 -> 39; 175,176 -> 169; 177,178 -> 171; 179 -> 175; 54 -> 6; 55 -> 54; 52 -> 51; 56 -> 2; 39,57 -> 17;
                188 -> 187; 192 -> 134; 193 -> 161; 24,25 -> 22; 150 -> 147; else -> 0 }); writeShort(0)
            val governor=card==54 || card==190 || card==192
            val garrison=card in setOf(52,55,188,191)
            // First is the lowest eligible rank (0057a080), not the lowest numeric index.
            writeByte(when { governor || card in setOf(134,135) -> 1; garrison -> 10; card in setOf(24,150) -> 4; card in setOf(40,41,175) -> 6; card in setOf(176,177) -> 7; card in setOf(42,178) -> 11; card in setOf(43,179) -> 12; card==171 -> 5; card in setOf(3,169) -> 2; card==25 || card==39 -> 5; card==56 -> 4; card==57 -> 3; card==193 -> 8; card==2 || card==17 -> 2; card==161 -> 5; else -> 0 })
            writeByte(if(governor || card in setOf(134,135))1 else if(garrison || card in setOf(2,3,17,24,25,39,40,41,42,43,169,171,175,176,177,178,179,150,56,57,161,193))2 else 20); writeByte(0)
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
        val beam: Int, val gun: Int, val missile: Int, val antiaircraft: Int, val repair: Int,
        val category: Int = 0, val number: Int = 1, val term: Int = 0, val crew: Int = 0,
        val missileConsumption: Int = 0, val fighterNumber: Int = 0, val cargo: Int = 0,
        val beamArms: Int = 0, val beamAngles: Int = 0, val gunArms: Int = 0, val gunAngles: Int = 0,
        val missileArms: Int = 0, val missileAngles: Int = 0, val antiaircraftArms: Int = 0,
        val commandRange:Float=0f)
    /** 00412eb0/004c5140: fixed 27 profiles, eight signed short samples each, no count/padding. */
    fun armaments(profiles: List<List<Int>>): ByteArray {
        require(profiles.size == 27 && profiles.all { it.size == 8 && it.all { value -> value in Short.MIN_VALUE..Short.MAX_VALUE } })
        return java.nio.ByteBuffer.allocate(432).also { out ->
            profiles.forEach { profile -> profile.forEach { out.putShort(it.toShort()) } }
        }.array()
    }
    /** 004109a0/00411a60: kind, class and model_file are distinct fields. */
    fun ships(ships: List<Ship>) = body {
        require(ships.size <= 200 && ships.map { it.kind }.distinct().size == ships.size)
        writeByte(ships.size)
        ships.forEach { ship ->
            require(ship.kind in 0..199 && ship.type in 0..255 && ship.category in 0..255 && ship.number in 1..65535)
            // Native 004f3e30: faction prefix * 1000 + model within faction,
            // not the index into the combined 120-entry file table.
            require(ship.model in 0..49 || ship.model in 1000..1049 ||
                ship.model in 2000..2009 || ship.model in 3000..3009)
            require(ship.searchRange.isFinite() && ship.searchRange >= 0 && ship.speed.isFinite() && ship.speed > 0)
            require(ship.commandRange.isFinite() && ship.commandRange>=0)
            // 004c7820: 0xff means absent; 0..26 indexes the separate 0311 ability table.
            // A zero profile index is NOT a zero weapon count.
            require(listOf(ship.beamArms, ship.gunArms, ship.missileArms)
                .all { it in 0..26 || it == 255 })
            require(ship.antiaircraftArms in 0..255) // Its lookup path is not yet recovered.
            require(listOf(ship.beamAngles, ship.gunAngles, ship.missileAngles).all { it in 0..63 })
            require(listOf(ship.outputPower, ship.armorFront, ship.armorSide, ship.armorBack, ship.shield,
                ship.shieldCapacity, ship.beam, ship.gun, ship.missile, ship.antiaircraft, ship.repair,
                ship.term, ship.crew, ship.missileConsumption, ship.fighterNumber, ship.cargo).all { it in 0..65535 })
            writeShort(ship.kind); writeByte(ship.type); writeByte(ship.category); writeShort(0); writeShort(ship.model)
            string(ship.name)
            writeShort(ship.number); writeInt(0) // price has not been recovered
            writeShort(0); writeShort(0); writeShort(ship.term); writeShort(0); writeShort(ship.crew)
            writeShort(ship.outputPower)
            // 004c32a0 reads constmsg +0x254 as maximum CRC; zero clamps the original to 1.6.
            writeFloat(ship.commandRange); writeFloat(ship.searchRange)
            repeat(13) { writeShort(0) } // sensor profile, evasion, navigation
            writeFloat(ship.speed); writeFloat(1f) // turning rate is provisional
            listOf(ship.armorFront, ship.armorBack, ship.armorSide, ship.shield, ship.shieldCapacity).forEach(::writeShort)
            // Profile assignments and six-sector masks are separate from destructive power.
            writeByte(ship.beamArms); writeShort(ship.beam); writeByte(ship.beamAngles)
            writeByte(ship.gunArms); writeShort(ship.gun); writeByte(ship.gunAngles)
            writeByte(ship.missileArms); writeShort(ship.missile); writeByte(ship.missileAngles); writeShort(ship.missileConsumption)
            writeByte(ship.antiaircraftArms); writeShort(ship.antiaircraft)
            writeShort(0); writeShort(ship.fighterNumber); writeShort(ship.cargo); writeShort(ship.repair); writeShort(0)
        }
    }
    data class Crew(val kind: Int, val type: Int, val category: Int, val training: Int,
        val offence: Int, val defence: Int, val speed: Float = 1f, val cargo: Int = 0)
    /** Native 004121f0/00412770: 22 wire bytes, 24-byte padded records, local kind + 1000. */
    fun crews(crews: List<Crew>) = body {
        require(crews.size <= 16 && crews.map { it.kind }.distinct().size == crews.size)
        writeByte(crews.size)
        crews.forEach { crew ->
            require(crew.kind in 0..15 && crew.type in 0..255 && crew.category in 0..255)
            require(listOf(crew.training, crew.offence, crew.defence, crew.cargo).all { it in 0..65535 })
            require(crew.speed.isFinite() && crew.speed >= 0)
            writeShort(1000 + crew.kind); writeByte(crew.type); writeByte(crew.category); writeShort(0)
            writeShort(crew.training); writeShort(0); writeShort(0); writeFloat(crew.speed)
            writeShort(crew.offence); writeShort(crew.defence); writeShort(crew.cargo)
        }
    }
}
