package org.logh7.gateway

import org.logh7.protocol.LoginMessages
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.Properties

/** evidence:guess — local test accounts and short-lived, single-use session tickets. */
class SessionTickets(private val clock: Clock = Clock.systemUTC()) {
    private val random = SecureRandom()
    private val tickets = mutableMapOf<Long, Long>()
    @Synchronized fun issue(): Long {
        tickets.entries.removeIf { it.value <= clock.millis() }
        check(tickets.size < 4096) { "Session ticket capacity exceeded" }
        var token: Long
        do { token = random.nextInt().toLong() and 0xffffffffL } while (token == 0L || token in tickets)
        tickets[token] = clock.millis() + 60_000
        return token
    }
    @Synchronized fun consume(token: Long): Boolean = (tickets.remove(token) ?: return false) > clock.millis()
}

class GameExchange(
    val role: Role,
    private val accounts: Map<String, String>,
    private val tickets: SessionTickets,
    private val sessionAddress: String,
    private val sessionPort: Int,
    private val handshake: Handshake = Handshake(),
) {
    enum class Role { LOGIN, SESSION }
    enum class State { KEY_EXCHANGE, AWAITING_AUTH, AUTHENTICATED, REJECTED, CLOSED }
    var state = State.KEY_EXCHANGE
        private set
    fun accept(type: Int, data: ByteArray): ByteArray? {
        check(state != State.CLOSED && state != State.REJECTED)
        if (state == State.KEY_EXCHANGE) {
            val response = handshake.accept(type, data)
            if (handshake.state == Handshake.State.ESTABLISHED) state = State.AWAITING_AUTH
            return response
        }
        check(state == State.AWAITING_AUTH) { "Post-authentication messages are not implemented" }
        val body = handshake.receive(type, data)
        return when (role) {
            Role.LOGIN -> {
                val request = LoginMessages.request(body)
                val expected = accounts[request.account]
                val accepted = expected != null && java.security.MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8), request.credential.toByteArray(Charsets.UTF_8))
                val reply = if (accepted) LoginMessages.success(sessionAddress, sessionPort, tickets.issue()) else LoginMessages.failure()
                state = if (accepted) State.AUTHENTICATED else State.REJECTED
                handshake.send(reply)
            }
            Role.SESSION -> {
                require(tickets.consume(LoginMessages.sessionToken(body))) { "Invalid or expired session token" }
                state = State.AUTHENTICATED
                null // No invented response: session acceptance opcode is not established yet.
            }
        }
    }
    fun close() { handshake.close(); state = State.CLOSED }
}

fun loadTestAccounts(path: Path?): Map<String, String> {
    if (path == null) return emptyMap()
    val properties = Properties().also { Files.newBufferedReader(path).use(it::load) }
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }.also { accounts ->
        require(accounts.all { (name, password) -> name.length in 1..30 && password.length <= 10 })
    }
}
