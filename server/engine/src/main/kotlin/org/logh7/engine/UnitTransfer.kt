package org.logh7.engine

/** Validated, signed transfer amounts; positive moves source -> destination. Author: Peppone Choi. */
class UnitTransfer(ships: Map<UnitStack, Int> = emptyMap(), troops: Map<UnitStack, Int> = emptyMap()) {
    val ships: Map<UnitStack, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(ships))
    val troops: Map<UnitStack, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(troops))
    init {
        require(this.ships.size <= 99 && this.troops.size <= 24)
        require((this.ships.values + this.troops.values).all { it != 0 && it in -65535..65535 })
        require(this.troops.keys.all { it.kind <= 15 })
    }
    data class Result(val source: UnitInventory, val destination: UnitInventory)

    /** All checks precede publication. Client-proposed resulting balances are deliberately absent. */
    fun apply(source: UnitInventory, destination: UnitInventory): Result {
        fun move(from: Map<UnitStack, Int>, to: Map<UnitStack, Int>, amounts: Map<UnitStack, Int>):
            Pair<Map<UnitStack, Int>, Map<UnitStack, Int>> {
            val left = LinkedHashMap(from); val right = LinkedHashMap(to)
            for ((stack, quantity) in amounts) {
                val remainder = (from[stack] ?: 0) - quantity
                val received = (to[stack] ?: 0) + quantity
                require(remainder in 0..65535 && received in 0..65535) { "Insufficient stock or stack overflow" }
                if (remainder == 0) left.remove(stack) else left[stack] = remainder
                if (received == 0) right.remove(stack) else right[stack] = received
            }
            return left to right
        }
        val (leftShips, rightShips) = move(source.ships, destination.ships, ships)
        val (leftTroops, rightTroops) = move(source.troops, destination.troops, troops)
        return Result(UnitInventory(leftShips, leftTroops, source.work),
            UnitInventory(rightShips, rightTroops, destination.work))
    }
}
