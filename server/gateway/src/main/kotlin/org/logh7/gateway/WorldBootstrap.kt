package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.WorldMessages
import org.logh7.protocol.LoginMessages
import org.logh7.engine.Flagships

class WorldBootstrap(val galaxy: Galaxy) {
    fun warehouse(base: Int): ByteArray {
        val state = requireNotNull(galaxy.conditions[base]) { "Unknown warehouse base" }
        return LoginMessages.sessionMessage(0x0327, WorldMessages.warehouse(base, state.supplies, state.food, 100_000))
    }
    private fun baseWire(base: org.logh7.engine.GalaxyBase) = WorldMessages.Base(base.id, base.grid,
        base.model, base.kind, base.name, base.physicalClass, base.radius, base.cycle, base.direction, base.angle, base.diameter)
    fun initialize(grid: Int, directory: Boolean = false): List<ByteArray> {
        val ids = galaxy.bases.filter { it.grid == grid }.map { it.id }
        return (if (directory) galaxy.bases.chunked(200).map { chunk ->
            LoginMessages.sessionMessage(0x1204, WorldMessages.simpleBases(chunk.map(::baseWire)))
        } else emptyList()) + listOf(conditions(ids), places(ids))
    }
    fun conditions(ids: List<Int>): ByteArray = LoginMessages.sessionMessage(0x031f,
        WorldMessages.conditions(ids.mapNotNull { galaxy.conditions[it] }.map {
            WorldMessages.Condition(it.baseId, it.grid, it.power, it.camp, it.population, it.adultPopulation,
                it.tax, it.approval, it.peace, it.atmosphere, it.habitability, it.supplies, it.food, it.living)
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
        0x030a to WorldMessages.ships(Flagships.definitions.map { ship ->
            WorldMessages.Ship(ship.kind, ship.type, ship.model, ship.name,
                ship.number("出力:出力"), ship.number("索敵範囲（万km）:索敵範囲（万km）").toFloat(),
                ship.number("最高速度（km/1G秒）:最高速度（km/1G秒）").toFloat(),
                ship.number("装甲:前"), ship.number("装甲:側"), ship.number("装甲:後"),
                ship.number("シールド（旗艦のみ）:防護値"), ship.number("シールド（旗艦のみ）:容量"),
                ship.number("ビーム兵装破壊力:ビーム兵装破壊力"),
                ship.number("ガン兵装破壊力/消費物資:ガン兵装破壊力/消費物資"),
                ship.number("ミサイル兵装破壊力/消費物資:ミサイル兵装破壊力/消費物資"),
                ship.number("対空兵装破壊力:対空兵装破壊力"), ship.number("修理消費物資(１隻):修理消費物資(１隻)"))
        }),
    )
}
