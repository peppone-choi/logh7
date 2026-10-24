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
import java.util.concurrent.CompletableFuture
import org.logh7.engine.Admission

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
    private val gameSeconds: () -> Long = { 0 },
    private val admission: GameAdmission? = null,
    private val connection: String = java.util.UUID.randomUUID().toString(),
    private val expectedGeneration: Long? = admission?.generation,
    private val characterProvider: () -> CharacterCreation = { characters },
    private val world: WorldBootstrap? = null,
    private val navigation: GameNavigation? = null,
    internal val presence: GamePresence? = null,
    private val onPresence: (List<ByteArray>) -> Unit = {},
) {
    enum class Role { LOGIN, SESSION }
    enum class State { KEY_EXCHANGE, AWAITING_AUTH, AUTHENTICATED, REJECTED, CLOSED }
    var state = State.KEY_EXCHANGE
        private set
    private var ticket: SessionTickets.Ticket? = null
    private var lobbyLoggedIn = false
    private var gameLoggedIn = false
    private var gameStarted = false
    private val generation = expectedGeneration
    private var joining = false
    private var joinRequested = false
    private var pendingJoin: CompletableFuture<Admission>? = null
    fun takePendingJoin(): CompletableFuture<Admission>? = pendingJoin.also { pendingJoin = null }
    private var pendingWorld: CompletableFuture<List<ByteArray>>? = null
    private data class Publication(val character: CharacterMessages.Generate, val player: org.logh7.engine.Navigator, val initialize: Boolean)
    private var publication: Publication? = null
    fun takePendingWorld() = pendingWorld.also { pendingWorld = null }
    internal fun finishWorld(messages: List<ByteArray>): OutboundBatch? = prepared {
        if (state == State.CLOSED) null else {
            val update = publication.also { publication = null }
            val shared = update?.let { presence?.update(GamePresence.Member(connection, it.character, it.player, onPresence)) }
            val output = if (update?.initialize == true && shared != null) {
                val index = messages.indexOfFirst { java.nio.ByteBuffer.wrap(it).getShort(4).toInt() == 0x0325 }
                check(index >= 0)
                messages.take(index) + shared.peers.map(presence!!::character) + messages.drop(index)
            } else messages + shared?.refresh.orEmpty()
            handshake.stageAll(output, clearHeader = 0)
        }
    }
    internal fun notifyWorld(messages: List<ByteArray>): OutboundBatch? = prepared {
        if (state == State.CLOSED || !gameStarted || obsolete()) null else handshake.stageAll(messages, clearHeader = 0)
    }
    fun obsolete() = ticket?.purpose == SessionTickets.Purpose.GAME && admission != null &&
        (generation != admission.generation || !admission.running)
    // ByteArray convenience is retained for codec callers. Gateway always writes the bound outbound batch.
    fun finishJoin(result: Admission): ByteArray? = finishJoinOutbound(result)?.bytes
    fun accept(type: Int, data: ByteArray): ByteArray? = acceptOutbound(type, data)?.bytes
    internal fun submitting(batch: OutboundBatch) = handshake.submitting(batch)
    internal fun committed(batch: OutboundBatch) = handshake.committed(batch)
    private inline fun prepared(block: () -> OutboundBatch?): OutboundBatch? = try { block() } catch (failure: Exception) {
        close(); throw failure
    }
    internal fun finishJoinOutbound(result: Admission): OutboundBatch? = prepared { finishJoinCore(result) }
    internal fun acceptOutbound(type: Int, data: ByteArray): OutboundBatch? = prepared { acceptCore(type, data) }
    private fun finishJoinCore(result: Admission): OutboundBatch? {
        if (state == State.CLOSED) return null
        check(joining && !gameStarted)
        joining = false
        gameStarted = result == Admission.ACCEPTED
        if (!gameStarted) state = State.REJECTED
        return handshake.stageAll(listOf(LoginMessages.sessionMessage(0x0206, byteArrayOf(if (gameStarted) 0 else 1))), clearHeader = 0)
    }
    private fun acceptCore(type: Int, data: ByteArray): OutboundBatch? {
        check(state != State.CLOSED && state != State.REJECTED)
        check(!obsolete()) { "Session ended or changed" }
        val characters = if (ticket?.purpose == SessionTickets.Purpose.GAME) characters else characterProvider()
        if (state == State.KEY_EXCHANGE) {
            val response = handshake.accept(type, data)
            if (handshake.state == Handshake.State.ESTABLISHED) state = State.AWAITING_AUTH
            return response?.let(handshake::plain)
        }
        if (state == State.AUTHENTICATED && role == Role.SESSION) {
            val body = handshake.receive(type, data) ?: return null
            val opcode = java.nio.ByteBuffer.wrap(body).short.toInt() and 65535
            val identity = checkNotNull(ticket)
            val response = if (identity.purpose == SessionTickets.Purpose.GAME) {
                when (opcode) {
                    0x0b00, 0x0e00 -> {
                        check(gameStarted && navigation != null && world != null)
                        val character = checkNotNull(characters.character(identity.account))
                        val dock = if (opcode == 0x0b00) org.logh7.protocol.PlaceMessages.dock(body) else null
                        val move = if (opcode == 0x0e00) org.logh7.protocol.PlaceMessages.move(body) else null
                        require((dock?.characterId ?: move!!.characterId) == character.id.toInt())
                        val place = dock?.let { org.logh7.engine.PlaceMove.Dock(it.base, it.mode) }
                            ?: org.logh7.engine.PlaceMove.Spot(move!!.spot, move.owner)
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = character.id.toInt() + 1_000_000,
                            captainCard = dock?.card, place = place, rank = character.rank)).thenApply { result ->
                            val player = result.player ?: result.previous
                            val accepted = result.player != null
                            if (accepted) publication = Publication(character, checkNotNull(player), false)
                            val seconds = gameSeconds()
                            // The CD's 004beaa0 applies ACK coordinates even on a rejection.
                            // Echo the authoritative origin so denial cannot move its background.
                            check(player != null) { "Place request lost its authenticated session" }
                            val ack = if (dock != null) org.logh7.protocol.PlaceMessages.docked(
                                dock.copy(base = player.baseId, mode = player.mode), seconds, player.pcp, player.mcp, accepted)
                            else org.logh7.protocol.PlaceMessages.moved(
                                move!!.copy(spot = player.spotId, owner = if (player.spotId == 0) character.id.toInt() else player.spotOwner),
                                seconds, player.pcp, player.mcp, accepted)
                            listOf(ack,
                                BootstrapMessages.changedCharacter(character, player.pcp, player.mcp, player.spotId, player.spotOwner),
                                BootstrapMessages.unit(character, player.grid, player.cruise, player.baseId, player.mode)) +
                                (if (dock != null && accepted) listOf(org.logh7.protocol.PlaceMessages.dockNotification(character.id.toInt(),
                                    player.baseId, player.mode, seconds)) else emptyList())
                        }
                        return null
                    }
                    0x0326 -> {
                        check(gameStarted && world != null)
                        val (base, outfit) = org.logh7.protocol.WorldMessages.warehouseQuery(body)
                        require(outfit == 0) { "Outfit warehouses require an owned outfit" }
                        world.warehouse(base)
                    }
                    0x031e, 0x0320 -> {
                        check(gameStarted && world != null)
                        val ids = org.logh7.protocol.WorldMessages.baseQuery(body, opcode)
                        if (opcode == 0x031e) world.conditions(ids) else world.places(ids)
                    }
                    0x0b01 -> {
                        check(gameStarted && navigation != null)
                        val character = checkNotNull(characters.character(identity.account))
                        val command = org.logh7.protocol.NavigationMessages.warp(body)
                        require(command.characterId == character.id.toInt())
                        require(command.base == 0 || world?.galaxy?.bases?.any {
                            it.id == command.base && it.grid == command.grid
                        } == true) { "Warp base is outside the destination system" }
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), command.grid, character.id.toInt() + 1_000_000,
                            command.card)).thenApply { result ->
                            val player = result.player ?: return@thenApply listOf(org.logh7.protocol.NavigationMessages.rejected(command))
                            publication = Publication(character, player, false)
                            val seconds = gameSeconds()
                            listOf(org.logh7.protocol.NavigationMessages.accepted(command, seconds, player.pcp, player.mcp),
                                BootstrapMessages.changedCharacter(character, player.pcp, player.mcp, player.spotId, player.spotOwner),
                                BootstrapMessages.unit(character, player.grid, player.cruise, player.baseId, player.mode),
                                org.logh7.protocol.NavigationMessages.grid(player.grid),
                                org.logh7.protocol.NavigationMessages.moved(command, seconds, player.unitId, player.cruise))
                        }
                        return null
                    }
                    0x0f08 -> {
                        check(gameStarted && body.size == 28)
                        // This local world has no mail yet; finish the client's periodic inbox query.
                        LoginMessages.sessionMessage(0x0f09, byteArrayOf(0))
                    }
                    0x0200 -> { check(!gameLoggedIn); gameLoggedIn = true; LobbyMessages.gameLoginOk() }
                    0x1008 -> { check(gameLoggedIn); characters.accept(identity.account, CharacterMessages.generate(body)) }
                    0x1000 -> { check(gameLoggedIn && !gameStarted && body.size == 2); OriginalCharacterMessages.account(characters.fame(identity.account), characters.entries(identity.account)) }
                    0x1200 -> {
                        check(gameLoggedIn && !gameStarted && OriginalCharacterMessages.catalogueRequest(body))
                        val messages = listOf(LoginMessages.sessionMessage(0x1200, body.copyOfRange(2, body.size)),
                            OriginalCharacterMessages.catalogue(characters.candidates(identity.account)), LoginMessages.sessionMessage(0x1201, byteArrayOf()))
                        return handshake.stageAll(messages, clearHeader = 0)
                    }
                    0x1004 -> {
                        check(gameLoggedIn && !gameStarted)
                        val id = OriginalCharacterMessages.entryId(body)
                        characters.entryState(identity.account, id)
                    }
                    0x1006 -> { check(gameLoggedIn && !gameStarted); characters.apply(identity.account, OriginalCharacterMessages.application(body)) }
                    0x0205 -> {
                        check(gameLoggedIn && !gameStarted && !joining && body.size == 2)
                        val character = checkNotNull(characters.character(identity.account))
                        if (admission != null) {
                            joining = true; joinRequested = true
                            pendingJoin = admission.join(identity.account, character, connection, checkNotNull(generation))
                            return null
                        }
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
                        if (navigation != null) {
                            val request = org.logh7.engine.NavigationRequest(identity.account, character.id.toInt(),
                                if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                                connection, checkNotNull(generation))
                            pendingWorld = navigation.apply(request).thenApply { result ->
                                val player = checkNotNull(result.player) { "Navigation initialization rejected: ${result.rejection}" }
                                publication = Publication(character, player, true)
                                listOf(BootstrapMessages.characterId(character),
                                    BootstrapMessages.character(character, player.pcp, player.mcp, player.spotId, player.spotOwner),
                                    BootstrapMessages.unit(character, player.grid, player.cruise, player.baseId, player.mode),
                                    org.logh7.protocol.NavigationMessages.grid(player.grid)) +
                                    world?.initialize(player.grid, directory = opcode == 0x0f00).orEmpty() +
                                    listOf(checkNotNull(BootstrapMessages.response(body)))
                            }
                            return null
                        }
                        val messages = listOf(BootstrapMessages.characterId(character),
                            BootstrapMessages.character(character), BootstrapMessages.unit(character),
                            checkNotNull(BootstrapMessages.response(body)))
                        println("world initialized character=${character.id} power=${character.power}")
                        return handshake.stageAll(messages, clearHeader = 0)
                    }
                    else -> {
                        check(gameStarted)
                        BootstrapMessages.response(body, gameSeconds(), world?.catalogues.orEmpty())
                            ?: error("Unsupported game opcode 0x${opcode.toString(16)}")
                    }
                }
            } else when (opcode) {
                0x2000 -> { check(!lobbyLoggedIn); lobbyLoggedIn = true; LobbyMessages.loginOk() }
                0x2003 -> { check(lobbyLoggedIn && body.size == 2); LobbyMessages.characters(if (admission?.running == false) null else characters.character(identity.account)) }
                0x2005 -> { check(lobbyLoggedIn && body.size == 3 && body[2].toInt() in 0..2); LobbyMessages.sessions(admission?.running != false) }
                0x2009 -> {
                    check(lobbyLoggedIn)
                    if (LobbyMessages.select(body) == LobbyMessages.LOCAL_SESSION_ID && admission?.running != false) {
                        LobbyMessages.selected(sessionAddress, sessionPort, tickets.issue(identity.account, SessionTickets.Purpose.GAME))
                    } else LobbyMessages.selectionFailed()
                }
                else -> error("Unsupported lobby opcode 0x${opcode.toString(16)}")
            }
            return handshake.stageAll(listOf(response), clearHeader = 0)
        }
        check(state == State.AWAITING_AUTH) { "Post-authentication messages are not implemented" }
        val body = handshake.receive(type, data) ?: return null
        return when (role) {
            Role.LOGIN -> {
                val request = LoginMessages.request(body)
                val expected = accounts[request.account]
                val accepted = expected != null && java.security.MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8), request.credential.toByteArray(Charsets.UTF_8))
                val reply = if (accepted) LoginMessages.success(sessionAddress, sessionPort, tickets.issue(request.account)) else LoginMessages.failure()
                state = if (accepted) State.AUTHENTICATED else State.REJECTED
                handshake.stageAll(listOf(reply))
            }
            Role.SESSION -> {
                ticket = requireNotNull(tickets.take(LoginMessages.sessionToken(body))) { "Invalid or expired session token" }
                state = State.AUTHENTICATED
                null // No invented response: session acceptance opcode is not established yet.
            }
        }
    }
    fun close() {
        if (state == State.CLOSED) return
        handshake.close(); state = State.CLOSED
        presence?.remove(connection)
        if (joinRequested) admission?.disconnect(checkNotNull(ticket).account, connection)
    }
}

fun loadTestAccounts(path: Path?): Map<String, String> {
    if (path == null) return emptyMap()
    val properties = Properties().also { Files.newBufferedReader(path).use(it::load) }
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }.also { accounts ->
        require(accounts.all { (name, password) -> name.length in 1..30 && !name.startsWith('@') && password.length <= 10 })
    }
}
