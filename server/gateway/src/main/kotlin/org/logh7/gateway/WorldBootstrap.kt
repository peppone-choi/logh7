package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.protocol.WorldMessages

class WorldBootstrap(val galaxy: Galaxy) {
    val catalogues: Map<Int, ByteArray> = mapOf(
        0x0312 to WorldMessages.types(List(89) { kind ->
            Triple(kind, if (kind >= 3) 3 else kind, galaxy.systems.find { it.kind == kind }?.star ?: 4)
        }),
        0x0314 to WorldMessages.grid(galaxy.cells),
        0x031c to WorldMessages.bases(galaxy.bases.map {
            WorldMessages.Base(it.id, it.grid, it.model, it.kind, it.name, it.physicalClass,
                it.radius, it.cycle, it.direction, it.angle, it.diameter)
        }),
        0x0306 to WorldMessages.captainCommands(),
        0x0304 to WorldMessages.cards(),
        0x0308 to WorldMessages.powerDirection(),
        0x030a to WorldMessages.ships(),
    )
}
