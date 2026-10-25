package org.logh7.engine

import java.util.Collections

data class UnitStack(val kind: Int, val level: Int = 0) {
    init { require(kind in 0..199 && level in 0..3) }
}

/** Native warehouse limits: 99 ship stacks, 24 troop stacks, u16 quantities. Author: 최병호. */
class UnitInventory(ships: Map<UnitStack, Int> = emptyMap(), troops: Map<UnitStack, Int> = emptyMap(),
    work: Map<String, Int> = emptyMap()) {
    val ships: Map<UnitStack, Int> = Collections.unmodifiableMap(LinkedHashMap(ships))
    val troops: Map<UnitStack, Int> = Collections.unmodifiableMap(LinkedHashMap(troops))
    val work: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(work))
    init {
        require(ships.size < 100 && troops.size < 25 && ships.values.all { it in 1..65535 } &&
            troops.all { (key, value) -> key.kind in 0..15 && value in 1..65535 } && work.size <= 123 &&
            work.all { (key, value) -> key.matches(Regex("[ST][0-9]{1,3}")) && value in 0..65535 })
    }
    override fun equals(other: Any?) = other is UnitInventory && ships == other.ships && troops == other.troops && work == other.work
    override fun hashCode() = 31 * (31 * ships.hashCode() + troops.hashCode()) + work.hashCode()
}
