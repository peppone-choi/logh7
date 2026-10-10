package org.logh7.gateway.load

import kotlinx.coroutines.*
import org.logh7.engine.*
import org.logh7.gateway.*
import org.logh7.protocol.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*

class LoadFixture(val directory: Path, val capacity: Int, val metrics: LoadMetrics) : AutoCloseable {
    val accounts = (0 until capacity + 3).associate { "load-%03d".format(it) to "fixture" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = WorldEngine(scope, SessionRules.load().copy(capacity = capacity), timings = metrics)
    val access = EngineGameAdmission(engine, scope)
    private val clients = ConcurrentLinkedQueue<SyntheticClient>()
    private val characters = ConcurrentHashMap<String, CharacterMessages.Generate>()
    @Volatile private var gate: HeldAdmission? = null
    val decisions = ConcurrentLinkedQueue<Admission>()
    private val observedAdmission = object : GameAdmission {
        override val generation get() = access.generation
        override val running get() = access.running
        override fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission> {
            val future = access.join(account, character, connection, generation)
            future.whenComplete { value, failure ->
                if (failure == null) { decisions += value; metrics.increment("admission_${value.name}") }
            }
            val held = gate?.takeIf { it.account == account }
            if (held != null) { future.whenComplete(held::decided); return held.reply }
            return future
        }
        override fun disconnect(account: String, connection: String) = access.disconnect(account, connection)
    }
    val gateway: Gateway
    val loginPort: Int
    val sessionPort: Int
    private var closed = false
    init {
        Files.createDirectories(directory)
        try {
            val started = startGateway()
            gateway = started.first; loginPort = started.second[0]; sessionPort = started.second[2]
        } catch (failure: Throwable) { engine.close(); scope.cancel(); throw failure }
    }
    private fun startGateway(): Pair<Gateway, List<Int>> {
        repeat(5) { attempt ->
            val reservations = mutableListOf<ServerSocket>()
            val ports = try {
                repeat(3) { reservations += ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
                reservations.map { it.localPort }
            } finally { reservations.forEach { it.close() } }
            val candidate = Gateway(directory.resolve("captures"), accounts, directory.resolve("characters.properties"),
                gameSeconds = { engine.snapshot().session!!.gameSeconds }, admission = observedAdmission)
            try {
                candidate.start(gamePort = ports[0], updatePort = ports[1], sessionPort = ports[2])
                metrics.observe("bindRetries", attempt.toLong()); return candidate to ports
            } catch (failure: Throwable) {
                if (generateSequence(failure) { it.cause }.none { it is BindException } || attempt == 4) throw failure
            }
        }
        error("No available loopback ports")
    }
    fun account(index: Int) = "load-%03d".format(index)
    private fun client(port: Int, fragmented: Boolean = false) = SyntheticClient(port, metrics, fragmented).also { clients += it }
    fun badLogin() {
        client(loginPort).use { connection ->
            metrics.measure("rejectedLogin") {
                connection.send(SyntheticClient.login(account(0), "wrong"))
                val body = connection.receive(false)
                check(body.size == 11 && ByteBuffer.wrap(body).short.toInt() and 65535 == 0x7002 && body[8] == 1.toByte())
                connection.expectClosed()
                metrics.increment("expectedRejectedLogin")
            }
        }
    }
    fun game(account: String, create: Boolean): SyntheticClient {
        val lobbyToken = client(loginPort, fragmented = true).use { login -> metrics.measure("login") {
            login.send(SyntheticClient.login(account, accounts.getValue(account)))
            val body = login.receive(false); val fields = ByteBuffer.wrap(body)
            check(body.size == 18 && fields.short.toInt() and 65535 == 0x7001)
            check(fields.getInt(8) == 0x0100007f && fields.getShort(12).toInt() and 65535 == sessionPort)
            fields.getInt(14).toLong() and 0xffffffffL
        } }
        val gameToken = client(sessionPort).use { lobby -> metrics.measure("lobby") {
            lobby.send(SyntheticClient.token(lobbyToken))
            // Three complete encrypted frames in one socket write, after authentication.
            lobby.send(SyntheticClient.opcode(0x2000), SyntheticClient.opcode(0x2003), SyntheticClient.opcode(0x2005) + byteArrayOf(0))
            lobby.expect(0x2001); lobby.expect(0x2004)
            val sessions = lobby.expect(0x2006); check(sessions[6] == 0.toByte() && sessions[7] == 1.toByte())
            val selected = lobby.request(ByteBuffer.allocate(4).putShort(0x2009).putShort(1).array(), 0x200a, "selectSession")
            check(selected.size == 16 && ByteBuffer.wrap(selected).getInt(6) == 0x0100007f)
            check(ByteBuffer.wrap(selected).getShort(10).toInt() and 65535 == sessionPort)
            ByteBuffer.wrap(selected).getInt(12).toLong() and 0xffffffffL
        } }
        val game = client(sessionPort)
        try {
            metrics.measure("gameLogin") { game.send(SyntheticClient.token(gameToken), SyntheticClient.opcode(0x0200)); game.expect(0x0201) }
            if (create) characters[account] = create(game, account.removePrefix("load-").toInt())
            return game
        } catch (failure: Throwable) { game.close(); throw failure }
    }
    private fun create(client: SyntheticClient, index: Int): CharacterMessages.Generate = metrics.measure("create") {
        var current = CharacterMessages.Generate(0, 0, 2, 0, 0, "Load%03d".format(index), "Test", 30,
            1, 1, 1, List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0)
        for (category in 0..4) {
            current = current.copy(category = category, shipName = if (category >= 3) "Ship%03d".format(index) else "")
            current = SyntheticClient.generated(client.request(SyntheticClient.creationBody(current), 0x1008, "creationStep"))
            check(current.category == category && current.id >= 10_000)
        }
        current
    }
    data class Joined(val client: SyntheticClient, val accepted: Boolean)
    fun join(account: String, create: Boolean): Joined {
        val client = game(account, create)
        try {
            val body = client.request(SyntheticClient.opcode(0x0205), 0x0206, "join")
            check(body.size == 7 && body[6].toInt() in 0..1)
            val accepted = body[6] == 0.toByte()
            if (accepted) bootstrap(client, characters.getValue(account)) else client.expectClosed()
            return Joined(client, accepted)
        } catch (failure: Throwable) { client.close(); throw failure }
    }
    private fun bootstrap(client: SyntheticClient, character: CharacterMessages.Generate) = metrics.measure("bootstrap") {
        client.send(SyntheticClient.opcode(0x0f00))
        val id = client.expect(0x0204); check(id.size == 10 && ByteBuffer.wrap(id).getInt(6).toLong() == character.id)
        val charged = client.expect(0x0323); check(ByteBuffer.wrap(charged).getInt(6).toLong() == character.id)
        val unit = client.expect(0x0325); check(ByteBuffer.wrap(unit).getShort(6).toInt() == 1)
        check(ByteBuffer.wrap(unit).getInt(8).toLong() == character.id + 1_000_000)
        check(client.expect(0x0f01).size == 6)
    }
    fun clock(client: SyntheticClient): Long {
        val before = engine.snapshot().session!!.gameSeconds
        val response = client.request(SyntheticClient.opcode(0x0300), 0x0301, "clockQuery")
        check(response.size == 10)
        val value = ByteBuffer.wrap(response).getInt(6).toLong() and 0xffffffffL
        check(value in before..engine.snapshot().session!!.gameSeconds)
        return value
    }
    fun awaitState(online: Int, connections: Int, participants: Int) = metrics.measure("settle") {
        await("online=$online channels=$connections participants=$participants") {
            val session = engine.snapshot().session!!; val channels = gateway.connections()
            session.online == online && session.participants == participants && channels.active == connections &&
                channels.tracked == connections && access.trackedConnections() == connections
        }
    }
    fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $description; world=${engine.snapshot()} channels=${gateway.connections()} bindings=${access.trackedConnections()}" }
            Thread.sleep(5)
        }
    }
    fun endSession() {
        runBlocking { engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 3, Power.ALLIANCE to 20), emptySet())) }
        await("session ended") { engine.snapshot().session!!.ended?.reason == EndReason.SYSTEMS_REDUCED }
    }
    fun holdNextAdmission(account: String) = HeldAdmission(account).also { check(gate == null); gate = it }
    class HeldAdmission(val account: String) {
        val reply = CompletableFuture<Admission>()
        private val real = CompletableFuture<Admission>()
        fun decided(value: Admission?, failure: Throwable?) { if (failure == null) real.complete(value) else real.completeExceptionally(failure) }
        fun awaitApplied(): Admission = real.get(10, TimeUnit.SECONDS)
        fun release() { real.whenComplete { value, failure -> if (failure == null) reply.complete(value) else reply.completeExceptionally(failure) } }
    }
    fun captureFragmentObserved(): Boolean = Files.list(directory.resolve("captures")).use { files ->
        files.filter { it.fileName.toString().endsWith(".tsv") }.anyMatch { path ->
            Files.readAllLines(path).any { it.endsWith("\tC>S\t00") }
        }
    }
    override fun close() {
        if (closed) return; closed = true
        try {
            gate?.release(); clients.forEach { it.close() }; gateway.close()
            await("closed fixture has no online members or bindings") { engine.snapshot().session!!.online == 0 && access.trackedConnections() == 0 }
            check(gateway.connections() == GatewayConnections(0, 0))
            runBlocking { engine.drainAndClose() }
        } finally { engine.close(); scope.cancel() }
    }
}
