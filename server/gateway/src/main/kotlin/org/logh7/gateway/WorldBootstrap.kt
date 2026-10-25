package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.WorldMessages
import org.logh7.protocol.LoginMessages
import org.logh7.engine.UnitSpecifications
import org.logh7.protocol.OutfitMessages

class WorldBootstrap(val galaxy: Galaxy) {
    fun outfits(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks): ByteArray = LoginMessages.sessionMessage(0x032b,
        OutfitMessages.information(ids.distinct().mapNotNull(stocks.outfits::get).map {
            OutfitMessages.Definition(it.id,it.kind.wire,it.power,0,it.number)
        }))
    fun gridOutfits(query: OutfitMessages.GridQuery, stocks: org.logh7.engine.PlanetStocks): ByteArray =
        LoginMessages.sessionMessage(0x032d,OutfitMessages.grid(stocks.outfits.values.filter { outfit ->
            (query.grid == -1 || galaxy.bases.single { it.id == outfit.baseId }.grid == query.grid) &&
                (query.power == 255 || outfit.power == query.power) && (query.camp == 255 || query.camp == 0)
        }.sortedBy { it.id }.map { OutfitMessages.Entry(it.id,it.kind.wire,it.power,0,it.number,it.supplies) }))
    fun outfitWarehouse(base: Int, outfitId: Int, character: Int, power: Int, stocks: org.logh7.engine.PlanetStocks): ByteArray {
        val outfit = requireNotNull(stocks.outfits[outfitId]) { "Unknown outfit" }
        require(outfit.baseId == base && outfit.power == power) { "Outfit warehouse identity mismatch" }
        val warehouse = stocks.warehouses.getValue(outfit.warehouseId)
        val commandsSharedWarehouse = stocks.outfits.values.any { it.warehouseId == warehouse.id && it.commanderId == character }
        require(character > 0 && (commandsSharedWarehouse || stocks.logisticsOfficers[character]?.permits(power,outfit.kind) == true)) {
            "Outfit warehouse access denied"
        }
        fun entries(values: Map<org.logh7.engine.UnitStack,Int>) = values.entries
            .sortedWith(compareBy({ it.key.kind },{ it.key.level })).map { WorldMessages.Stock(it.key.kind,it.key.level,it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base,warehouse.supplies,warehouse.food,warehouse.mineral,
            entries(warehouse.units.ships),entries(warehouse.units.troops),outfitId))
    }
    fun warehouse(base: Int, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray {
        val state = requireNotNull(galaxy.conditions[base]) { "Unknown warehouse base" }
        val stored = stocks?.let { checkNotNull(it.bases[base]) }
        fun entries(values: Map<org.logh7.engine.UnitStack, Int>?) = values.orEmpty().entries
            .sortedWith(compareBy({ it.key.kind }, { it.key.level })).map { WorldMessages.Stock(it.key.kind, it.key.level, it.value) }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base, stored?.supplies ?: state.supplies,
            stored?.food ?: state.food, stored?.mineral ?: 100_000, entries(stored?.units?.ships), entries(stored?.units?.troops)))
    }
    private fun baseWire(base: org.logh7.engine.GalaxyBase) = WorldMessages.Base(base.id, base.grid,
        base.model, base.kind, base.name, base.physicalClass, base.radius, base.cycle, base.direction, base.angle, base.diameter)
    fun initialize(grid: Int, directory: Boolean = false, stocks: org.logh7.engine.PlanetStocks? = null): List<ByteArray> {
        val ids = galaxy.bases.filter { it.grid == grid }.map { it.id }
        return (if (directory) galaxy.bases.chunked(200).map { chunk ->
            LoginMessages.sessionMessage(0x1204, WorldMessages.simpleBases(chunk.map(::baseWire)))
        } else emptyList()) + listOf(conditions(ids, stocks), places(ids))
    }
    fun conditions(ids: List<Int>, stocks: org.logh7.engine.PlanetStocks? = null): ByteArray = LoginMessages.sessionMessage(0x031f,
        WorldMessages.conditions(ids.mapNotNull { galaxy.conditions[it] }.map {
            val stored = stocks?.let { state -> checkNotNull(state.bases[it.baseId]) }
            val people = stored?.economy
            WorldMessages.Condition(it.baseId, it.grid, stored?.power ?: it.power, stored?.camp ?: it.camp,
                people?.population ?: it.population, people?.adults ?: it.adultPopulation,
                it.tax, people?.approval ?: it.approval, people?.peace ?: it.peace, it.atmosphere, it.habitability,
                stored?.supplies ?: it.supplies, stored?.food ?: it.food, it.living)
        }))
    fun places(ids: List<Int>): ByteArray = LoginMessages.sessionMessage(0x0321,
        WorldMessages.institutions(ids.mapNotNull { id -> galaxy.bases.find { it.id == id } }.map { base ->
            WorldMessages.BaseInstitutions(base.id, galaxy.facilities.getValue(base.id).map { facility ->
                WorldMessages.Institution(facility.kind, facility.id, facility.spots.map {
                    WorldMessages.Spot(it.kind, it.id, it.image)
                })
            })
        }))
    val catalogues: Map<Int, ByteArray> = mapOf(
        0x0312 to WorldMessages.types(List(89) { kind ->
            Triple(kind, if (kind >= 3) 3 else kind, galaxy.systems.find { it.kind == kind }?.star ?: 4)
        }),
        0x0314 to WorldMessages.grid(galaxy.cells),
        0x031c to WorldMessages.bases(galaxy.bases.map(::baseWire)),
        0x0306 to WorldMessages.captainCommands(),
        0x0304 to WorldMessages.cards(),
        0x0308 to WorldMessages.powerDirection(),
        0x030a to WorldMessages.ships(UnitSpecifications.ships.map { ship ->
            WorldMessages.Ship(ship.kind, ship.type, ship.model, ship.name,
                ship.number("出力:出力"), ship.number("索敵範囲（万km）:索敵範囲（万km）").toFloat(),
                ship.number("最高速度（km/1G秒）:最高速度（km/1G秒）").toFloat(),
                ship.number("装甲:前"), ship.number("装甲:側"), ship.number("装甲:後"),
                ship.number("シールド（旗艦のみ）:防護値"), ship.number("シールド（旗艦のみ）:容量"),
                ship.number("ビーム兵装破壊力:ビーム兵装破壊力"),
                ship.number("ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資"),
                ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資"),
                ship.number("対空兵装破壊力:対空兵装破壊力"), ship.number("修理消費物資(１隻):修理消費物資(１隻)"),
                category = ship.category, number = ship.number, term = ship.number("建造工期:建造工期"),
                crew = ship.number("必要乗組員ユニット数:必要乗組員ユニット数"),
                missileConsumption = ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資", 1),
                fighterNumber = ship.number("戦闘艇/雷撃艇搭載数:戦闘艇/雷撃艇搭載数"),
                cargo = ship.number("物資搭載量:物資搭載量"))
        }),
        0x030c to WorldMessages.crews(UnitSpecifications.crews.map { crew ->
            WorldMessages.Crew(crew.kind, crew.type, crew.category, crew.manual.training,
                crew.manual.groundAttack ?: 0, crew.manual.groundDefense ?: 0)
        }),
    )
}
