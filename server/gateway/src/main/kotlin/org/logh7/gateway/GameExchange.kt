package org.logh7.gateway

import org.logh7.protocol.LoginMessages
import org.logh7.protocol.LobbyMessages
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.OriginalCharacterMessages
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.Properties

/** evidence:guess — local test accounts and short-lived, single-use session tickets. */
class SessionTickets(private val clock: Clock = Clock.systemUTC()) {
    enum class Purpose { LOBBY, GAME }
    data class Ticket(val account: String, val purpose: Purpose, val expiresAt: Long)
    private val random = SecureRandom()
    private val tickets = mutableMapOf<Long, Ticket>()
    @Synchronized fun issue(account: String = "", purpose: Purpose = Purpose.LOBBY): Long {
        tickets.entries.removeIf { it.value.expiresAt <= clock.millis() }
        check(tickets.size < 4096) { "Session ticket capacity exceeded" }
        var token: Long
        do { token = random.nextInt().toLong() and 0xffffffffL } while (token == 0L || token in tickets)
        tickets[token] = Ticket(account, purpose, clock.millis() + 60_000)
        return token
    }
    @Synchronized fun take(token: Long): Ticket? = tickets.remove(token)?.takeIf { it.expiresAt > clock.millis() }
    fun consume(token: Long): Boolean = take(token) != null
}

class GameExchange(
    val role: Role,
    private val accounts: Map<String, String>,
    private val tickets: SessionTickets,
    private val sessionAddress: String,
    private val sessionPort: Int,
    private val handshake: Handshake = Handshake(),
    private val characters: CharacterCreation = CharacterCreation(),
) {
    enum class Role { LOGIN, SESSION }
    enum class State { KEY_EXCHANGE, AWAITING_AUTH, AUTHENTICATED, REJECTED, CLOSED }
    var state = State.KEY_EXCHANGE
        private set
    private var ticket: SessionTickets.Ticket? = null
    private var lobbyLoggedIn = false
    private var gameLoggedIn = false
    private var gameStarted = false
    fun accept(type: Int, data: ByteArray): ByteArray? {
        check(state != State.CLOSED && state != State.REJECTED)
        if (state == State.KEY_EXCHANGE) {
            val response = handshake.accept(type, data)
            if (handshake.state == Handshake.State.ESTABLISHED) state = State.AWAITING_AUTH
            return response
        }
        if (state == State.AUTHENTICATED && role == Role.SESSION) {
            val body = handshake.receive(type, data)
            val opcode = java.nio.ByteBuffer.wrap(body).short.toInt() and 65535
            val identity = checkNotNull(ticket)
            val response = if (identity.purpose == SessionTickets.Purpose.GAME) {
                when (opcode) {
                    0x0200 -> { check(!gameLoggedIn); gameLoggedIn = true; LobbyMessages.gameLoginOk() }
                    0x1008 -> { check(gameLoggedIn); characters.accept(identity.account, CharacterMessages.generate(body)) }
                    0x1000 -> { check(gameLoggedIn && !gameStarted && body.size == 2); OriginalCharacterMessages.account(characters.fame(identity.account), characters.entries(identity.account)) }
                    0x1200 -> {
                        check(gameLoggedIn && !gameStarted && OriginalCharacterMessages.catalogueRequest(body))
                        val messages = listOf(LoginMessages.sessionMessage(0x1200, body.copyOfRange(2, body.size)),
                            OriginalCharacterMessages.catalogue(characters.candidates(identity.account)), LoginMessages.sessionMessage(0x1201, byteArrayOf()))
                        return messages.fold(ByteArray(0)) { frames, message -> frames + handshake.send(message, clearHeader = 0) }
                    }
                    0x1004 -> {
                        check(gameLoggedIn && !gameStarted)
                        val id = OriginalCharacterMessages.entryId(body)
                        characters.entryState(identity.account, id)
                    }
                    0x1006 -> { check(gameLoggedIn && !gameStarted); characters.apply(identity.account, OriginalCharacterMessages.application(body)) }
                    0x0205 -> {
                        check(gameLoggedIn && !gameStarted && body.size == 2 && characters.character(identity.account) != null)
                        gameStarted = true
                        LoginMessages.sessionMessage(0x0206, byteArrayOf(0))
                    }
                    0x0203 -> {
                        check(gameStarted && body.size == 2)
                        BootstrapMessages.characterId(checkNotNull(characters.character(identity.account)))
                    }
                    0x0f00, 0x0f02 -> {
                        check(gameStarted && body.size == 2)
                        val character = checkNotNull(characters.character(identity.account))
                        val messages = listOf(BootstrapMessages.characterId(character),
                            BootstrapMessages.character(character), BootstrapMessages.unit(character),
                            checkNotNull(BootstrapMessages.response(body)))
                        println("world initialized character=${character.id} power=${character.power}")
                        return messages.fold(ByteArray(0)) { frames, message -> frames + handshake.send(message, clearHeader = 0) }
                    }
                    else -> {
                        check(gameStarted)
                        BootstrapMessages.response(body)
                            ?: error("Unsupported game opcode 0x${opcode.toString(16)}")
                    }
                }
            } else when (opcode) {
                0x2000 -> { check(!lobbyLoggedIn); lobbyLoggedIn = true; LobbyMessages.loginOk() }
                0x2003 -> { check(lobbyLoggedIn && body.size == 2); LobbyMessages.characters(characters.character(identity.account)) }
                0x2005 -> { check(lobbyLoggedIn && body.size == 3 && body[2].toInt() in 0..2); LobbyMessages.sessions() }
                0x2009 -> {
                    check(lobbyLoggedIn)
                    if (LobbyMessages.select(body) == LobbyMessages.LOCAL_SESSION_ID) {
                        LobbyMessages.selected(sessionAddress, sessionPort, tickets.issue(identity.account, SessionTickets.Purpose.GAME))
                    } else LobbyMessages.selectionFailed()
                }
                else -> error("Unsupported lobby opcode 0x${opcode.toString(16)}")
            }
            return handshake.send(response, clearHeader = 0)
        }
        check(state == State.AWAITING_AUTH) { "Post-authentication messages are not implemented" }
        val body = handshake.receive(type, data)
        return when (role) {
            Role.LOGIN -> {
                val request = LoginMessages.request(body)
                val expected = accounts[request.account]
                val accepted = expected != null && java.security.MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8), request.credential.toByteArray(Charsets.UTF_8))
                val reply = if (accepted) LoginMessages.success(sessionAddress, sessionPort, tickets.issue(request.account)) else LoginMessages.failure()
                state = if (accepted) State.AUTHENTICATED else State.REJECTED
                handshake.send(reply)
            }
            Role.SESSION -> {
                ticket = requireNotNull(tickets.take(LoginMessages.sessionToken(body))) { "Invalid or expired session token" }
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
        require(accounts.all { (name, password) -> name.length in 1..30 && !name.startsWith('@') && password.length <= 10 })
    }
}
