package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.WorldMessages
import org.logh7.protocol.LoginMessages

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
        0x030a to WorldMessages.ships(),
    )
}
