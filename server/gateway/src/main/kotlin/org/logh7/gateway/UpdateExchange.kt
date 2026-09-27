package org.logh7.gateway

import java.nio.ByteBuffer

/** Candidate exchange from docs/protocol/update-protocol.md; still needs client capture. */
class UpdateExchange {
    enum class State { AWAITING_IDENTIFICATION, AWAITING_VERSION, COMPLETE }

    var state: State = State.AWAITING_IDENTIFICATION
        private set

    fun accept(payload: ByteArray): ByteArray {
        require(payload.size >= 2) { "Missing update opcode" }
        val input = ByteBuffer.wrap(payload)
        val opcode = input.short.toInt() and 0xffff
        val response = when (state) {
            State.AWAITING_IDENTIFICATION -> {
                require(opcode == 0x6810) { "Expected 0x6810 identification" }
                // Remaining bytes are the client's opaque identification blob.
                state = State.AWAITING_VERSION
                0x6811
            }
            State.AWAITING_VERSION -> {
                require(opcode == 0x6820 && payload.size == 6) { "Expected 0x6820 with BE32 version" }
                state = State.COMPLETE
                0x6822
            }
            State.COMPLETE -> error("Update exchange already complete")
        }
        return ByteBuffer.allocate(4).putShort(2).putShort(response.toShort()).array()
    }
}
