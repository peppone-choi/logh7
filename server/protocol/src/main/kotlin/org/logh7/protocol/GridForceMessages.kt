package org.logh7.protocol

import java.nio.ByteBuffer
import org.logh7.protocol.generated.MessageId

/** CD NotifySearch reader 0044c960 and map import 004bfd30. Author: 최병호. */
object GridForceMessages {
    // The native importer replaces one power/camp across the map per packet.
    // Every row must therefore carry the same allegiance, with one nonempty group.
    fun search(seconds: Long, character: Int, power: Int, camp: Int, units: Map<Int, Int>): ByteArray {
        require(seconds in 0..0xffffffffL && character > 0 && power in 2..3 && camp in 0..1)
        require(units.size in 1..225 && units.all { (grid, count) -> grid in 0..4999 && count in 1..65535 })
        val out = ByteBuffer.allocate(13 + units.size * 7)
            .putInt(seconds.toInt()).putInt(0).putInt(character).put(units.size.toByte())
        units.toSortedMap().forEach { (grid, count) ->
            out.putShort(grid.toShort()).put(1).put(power.toByte()).put(camp.toByte()).putShort(count.toShort())
        }
        return LoginMessages.sessionMessage(MessageId.NotifySearch.opcode, out.array())
    }
}
