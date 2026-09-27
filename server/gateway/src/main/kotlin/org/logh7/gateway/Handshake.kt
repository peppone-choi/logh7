package org.logh7.gateway

/** Skeleton only: no fabricated key-exchange replies before the phase layouts are known. */
class Handshake {
    enum class State { AWAITING_INITIAL_KEY, INITIAL_KEY_OBSERVED, CLOSED }
    var state = State.AWAITING_INITIAL_KEY
        private set
    fun observe(type: Int) {
        check(state != State.CLOSED)
        when (state) {
            State.AWAITING_INITIAL_KEY -> { require(type == 0x34) { "Expected initial key frame" }; state = State.INITIAL_KEY_OBSERVED }
            State.INITIAL_KEY_OBSERVED -> error("Key exchange reply layout is not implemented")
            State.CLOSED -> error("Closed")
        }
    }
    fun close() { state = State.CLOSED }
}
