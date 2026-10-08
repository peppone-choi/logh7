package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.logh7.engine.Admission
import java.util.concurrent.CompletableFuture

class GameExchangeTest {
    private fun hex(s: String) = java.util.HexFormat.of().parseHex(s)
    private val a = ByteArray(16) { it.toByte() }
    private val b = ByteArray(16) { (240 + it).toByte() }
    private val wrap = LegacyBlowfish(LegacyBlowfish.HANDSHAKE_KEY.toByteArray())
    private val initial = hex("35eb18155d09bbb52a8d5c7fcef57d7712d9f1f6a2bebede")
    private val confirm = hex("385c1e213879e5fc44d62157e541b061e556d88f3a5ae35b")
    private fun handshake() = Handshake(b, 2).also { it.accept(0x34, initial); it.accept(0x36, confirm) }
    private fun encrypted(body: ByteArray, sequence: Long = 1) = LegacyBlowfish(a).encrypt(Envelope.encode(sequence, body))
    private fun request(password: String = "pw"): ByteArray {
        val name = "user\u0000"; val secret = "$password\u0000"
        val out = ByteBuffer.allocate(13 + 2 * (name.length + secret.length))
        out.putShort(0x7000).putInt(0x47494e37).putShort(1).putShort(0).put(0).put(name.length.toByte())
        name.forEach { out.putChar(it) }; out.put(secret.length.toByte()); secret.forEach { out.putChar(it) }
        return out.array()
    }
    private fun exchange(role: GameExchange.Role, tickets: SessionTickets = SessionTickets()) =
        GameExchange(role, mapOf("user" to "pw"), tickets, "127.0.0.1", 47903, Handshake(b, 2)).also {
            it.accept(0x34, initial); it.accept(0x36, confirm)
        }
    @Test fun responderMatchesSyntheticKexAndDirectionalCipher() {
        val h = Handshake(b, 2)
        assertContentEquals(hex("003200350ae5496598e865832a8d5c7fcef57d774f45662f518c4787df09d00b325f2b21d79b00d06e006dced97727f232edeec3"), h.accept(0x34, initial))
        assertEquals(Handshake.State.AWAITING_CONFIRMATION, h.state)
        assertNull(h.accept(0x36, confirm)); assertEquals(Handshake.State.ESTABLISHED, h.state)
        assertContentEquals(request(), h.receive(0x30, encrypted(request())))
        assertFailsWith<IllegalArgumentException> { h.receive(0x30, encrypted(request())) }
        val frame = h.send(byteArrayOf(0x70, 1))
        val (seq, body) = Envelope.decode(LegacyBlowfish(b).decrypt(frame.copyOfRange(4, frame.size)), 1)
        assertEquals(2L, seq); assertContentEquals(byteArrayOf(0x70, 1), body)
    }
    @Test fun rejectsBadKeyFramesAndEarlyApplication() {
        assertFails { Handshake().accept(0x30, initial) }
        assertFails { Handshake().receive(0x30, encrypted(request())) }
        assertFails { Handshake().accept(0x34, initial.dropLast(1).toByteArray()) }
        val corrupt = wrap.decrypt(initial).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { Handshake().accept(0x34, wrap.encrypt(corrupt)) }
        val h = Handshake(b, 2); h.accept(0x34, initial)
        assertFails { h.accept(0x34, initial) }; assertFails { h.accept(0x36, initial) }
        assertEquals(Handshake.State.AWAITING_CONFIRMATION, h.state)
        h.close(); assertFails { h.accept(0x36, confirm) }
    }
    @Test fun rejectsEncryptedRekeyAndWrongOuterType() {
        assertFailsWith<IllegalStateException> { handshake().receive(0x30, encrypted(byteArrayOf(0, 0x31))) }
        assertFailsWith<IllegalArgumentException> { handshake().receive(0x31, encrypted(request())) }
        val h = handshake()
        val damaged = Envelope.encode(1, request()).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { h.receive(0x30, LegacyBlowfish(a).encrypt(damaged)) }
        assertContentEquals(request(), h.receive(0x30, encrypted(request())))
    }
    @Test fun loginRedirectAndSingleUseSessionToken() {
        val tickets = SessionTickets(); val login = exchange(GameExchange.Role.LOGIN, tickets)
        val response = login.accept(0x30, encrypted(request()))!!
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(4, response.size)), 1).second
        assertContentEquals(hex("70010000000000000100007fbb1f"), body.copyOfRange(0, 14))
        val token = ByteBuffer.wrap(body, 14, 4).int
        val auth = ByteBuffer.allocate(6).putShort(0x20).putInt(token).array()
        val session = exchange(GameExchange.Role.SESSION, tickets)
        assertNull(session.accept(0x30, encrypted(auth)))
        assertEquals(GameExchange.State.AUTHENTICATED, session.state)
        assertFails { exchange(GameExchange.Role.SESSION, tickets).accept(0x30, encrypted(auth)) }
        assertFails { session.accept(0x30, encrypted(auth, 2)) }
    }
    @Test fun badAccountGetsDirectionalFailureHeader() {
        val login = exchange(GameExchange.Role.LOGIN)
        val response = login.accept(0x30, encrypted(request("bad")))!!
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(4, response.size)), 1).second
        assertContentEquals(hex("7002000000000000010000"), body)
        assertEquals(GameExchange.State.REJECTED, login.state)
        assertFails { login.accept(0x30, encrypted(request(), 2)) }
    }
    @Test fun lobbyListSelectionAndPurposeBoundGameTicket() {
        val tickets = SessionTickets()
        val session = exchange(GameExchange.Role.SESSION, tickets)
        val token = tickets.issue("user")
        assertNull(session.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array())))
        fun request(body: ByteArray, sequence: Long): ByteArray {
            val frame = session.accept(0x30, encrypted(body, sequence))!!
            assertEquals(frame.size - 2, ByteBuffer.wrap(frame).short.toInt() and 65535)
            assertContentEquals(hex("000000000030"), frame.copyOfRange(2, 8))
            return Envelope.decode(LegacyBlowfish(b).decrypt(frame.copyOfRange(8, frame.size)), sequence - 1).second
        }
        assertContentEquals(hex("000000002001000000"), request(hex("200047494e3700040000070069006e00650069003000300000"), 2))
        assertContentEquals(hex("00000000200400"), request(hex("2003"), 3))
        val list = request(hex("200501"), 4)
        assertContentEquals(hex("00000000200600010001010c"), list.copyOfRange(0, 12))
        assertTrue(list.toList().windowed(24).any { it.toByteArray().contentEquals("LOGH7 Local\u0000".toByteArray(Charsets.UTF_16BE)) })
        assertContentEquals(list, request(hex("200502"), 5))
        assertContentEquals(list, request(hex("200500"), 6))
        assertContentEquals(hex("00000000200b0100"), request(hex("20090002"), 7))
        val selected = request(hex("20090001"), 8)
        assertContentEquals(hex("00000000200a0100007fbb1f"), selected.copyOfRange(0, 12))
        val gameToken = ByteBuffer.wrap(selected, 12, 4).int
        val game = exchange(GameExchange.Role.SESSION, tickets)
        val auth = ByteBuffer.allocate(6).putShort(0x20).putInt(gameToken).array()
        assertNull(game.accept(0x30, encrypted(auth)))
        val accepted = game.accept(0x30, encrypted(hex("0200"), 2))!!
        assertContentEquals(hex("00000000020100"), Envelope.decode(LegacyBlowfish(b).decrypt(accepted.copyOfRange(8, accepted.size)), 1).second)
        assertFails { exchange(GameExchange.Role.SESSION, tickets).accept(0x30, encrypted(auth)) }
        val wrongRole = exchange(GameExchange.Role.SESSION, tickets)
        val otherToken = tickets.issue("user", SessionTickets.Purpose.GAME)
        wrongRole.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(otherToken.toInt()).array()))
        assertFails { wrongRole.accept(0x30, encrypted(hex("2000"), 2)) }
    }
    @Test fun loginUsesActualUtf16LengthAndRejectsTruncation() {
        assertEquals(LoginMessages.Request("user", "pw"), LoginMessages.request(request()))
        assertFails { LoginMessages.request(request().dropLast(1).toByteArray()) }
        assertFails { LoginMessages.request(request() + byteArrayOf(0)) }
        assertContentEquals(hex("01020304002005"), LoginMessages.sessionMessage(0x20, byteArrayOf(5), 0x01020304))
    }
    @Test fun storedCharacterBootstrapsWorldAndGridBeforeAcknowledgements() {
        val characters = CharacterCreation()
        var data = CharacterMessages.Generate(0, 0, 2, 0, 0, "Alpha", "Pilot", 0, 0, 0, 0,
            List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0)
        fun step(next: CharacterMessages.Generate) {
            val reply = characters.accept("user", next)
            data = CharacterMessages.generate(reply.copyOfRange(4, reply.size))
        }
        step(data); step(data.copy(category = 1, age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001))
        step(data.copy(category = 2)); step(data.copy(category = 3, shipName = "AlphaShip")); step(data.copy(category = 4))
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903, Handshake(b, 2), characters, gameSeconds = { 86_400 })
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2))
        game.accept(0x30, encrypted(hex("0205"), 3))
        var serverSequence = 3L
        fun opcodes(bytes: ByteArray): List<Int> {
            val results = mutableListOf<Int>(); var offset = 0
            while (offset < bytes.size) {
                val length = (ByteBuffer.wrap(bytes, offset, 2).short.toInt() and 65535) + 2
                assertContentEquals(hex("000000000030"), bytes.copyOfRange(offset + 2, offset + 8))
                val (sequence, body) = Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset + 8, offset + length)), serverSequence)
                serverSequence = sequence
                results += ByteBuffer.wrap(body, 4, 2).short.toInt() and 65535
                offset += length
            }
            return results
        }
        assertEquals(listOf(0x204, 0x323, 0x325, 0xf01), opcodes(game.accept(0x30, encrypted(hex("0f00"), 4))!!))
        assertEquals(listOf(0x204, 0x323, 0x325, 0xf03), opcodes(game.accept(0x30, encrypted(hex("0f02"), 5))!!))
        val clockFrame = game.accept(0x30, encrypted(hex("0300"), 6))!!
        val clockBody = Envelope.decode(LegacyBlowfish(b).decrypt(clockFrame.copyOfRange(8, clockFrame.size)), serverSequence).second
        assertContentEquals(hex("00000000030100015180"), clockBody)
    }
    @Test fun goldenCapturedInitialFrameWhenAvailable() {
        val dir = System.getenv("LOGH7_GOLDEN_DIR")
        assumeTrue(!dir.isNullOrBlank(), "LOGH7_GOLDEN_DIR not set")
        val path = Path.of(dir!!).resolve("initial-0034.bin")
        assumeTrue(Files.exists(path), "initial-0034.bin not available")
        val frame = Files.readAllBytes(path)
        require(frame.size >= 4 && (ByteBuffer.wrap(frame).short.toInt() and 65535) == frame.size - 2)
        val (type, data) = Frames.decodePayload(frame.copyOfRange(2, frame.size))
        assertNotNull(Handshake().accept(type, data))
    }
    @Test fun originalCatalogueUsesSessionFramesAndApplicationBelongsToAuthenticatedAccount() {
        val characters = CharacterCreation()
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903, Handshake(b, 2), characters)
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2))
        val request = ByteBuffer.allocate(31).putShort(0x1200).putInt(0).putShort(0x13).array()
        val bytes = game.accept(0x30, encrypted(request, 3))!!
        val opcodes = mutableListOf<Int>(); var offset = 0; var serverSequence = 2L
        while (offset < bytes.size) {
            val length = (ByteBuffer.wrap(bytes, offset, 2).short.toInt() and 65535) + 2
            assertContentEquals(hex("000000000030"), bytes.copyOfRange(offset + 2, offset + 8))
            val (sequence, body) = Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset + 8, offset + length)), serverSequence)
            serverSequence = sequence; opcodes += ByteBuffer.wrap(body, 4, 2).short.toInt() and 65535; offset += length
        }
        assertEquals(listOf(0x1200, 0x120f, 0x1201), opcodes)
        game.accept(0x30, encrypted(hex("100601000003e9"), 4))
        assertEquals(listOf(1001L), characters.entries("user"))
        assertTrue(characters.entries("other").isEmpty())
        assertFails { game.accept(0x30, encrypted(hex("0205"), 5)) }
    }
    @Test fun gameLoginWaitsForActorAdmissionAndRejectsBeforeWorldInitialization() {
        for (decision in listOf(Admission.ACCEPTED, Admission.FULL)) {
            var now = java.time.Instant.EPOCH
            val clock = object : java.time.Clock() {
                override fun getZone() = java.time.ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
                override fun instant() = now
            }
            val characters = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
            characters.apply("user", listOf(1001)); now = now.plusMillis(1)
            val pending = CompletableFuture<Admission>()
            val closed = mutableListOf<String>()
            val access = object : GameAdmission {
                override val generation = 1L
                override val running = true
                override fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission> {
                    assertEquals("user", account); assertEquals(1001L, character.id); assertEquals(1L, generation)
                    return pending
                }
                override fun disconnect(account: String, connection: String) { closed += account }
            }
            val tickets = SessionTickets()
            val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
                Handshake(b, 2), characters, admission = access)
            game.accept(0x34, initial); game.accept(0x36, confirm)
            val token = tickets.issue("user", SessionTickets.Purpose.GAME)
            game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30, encrypted(hex("0200"), 2))
            assertNull(game.accept(0x30, encrypted(hex("0205"), 3)))
            assertSame(pending, game.takePendingJoin()); assertNull(game.takePendingJoin())
            pending.complete(decision)
            val response = game.finishJoin(decision)!!
            val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(8, response.size)), 2).second
            assertContentEquals(hex(if (decision == Admission.ACCEPTED) "00000000020600" else "00000000020601"), body)
            if (decision == Admission.FULL) assertFails { game.accept(0x30, encrypted(hex("0300"), 4)) }
            game.close(); game.close()
            assertEquals(listOf("user"), closed)
        }
    }
    @Test fun rejectsTruncatedSequenceAndWrongEchoWithValidChecksums() {
        fun sealed(plain: ByteArray): ByteArray {
            ByteBuffer.wrap(plain).putShort(Envelope.checksum(plain.copyOfRange(2, plain.size)).toShort())
            return wrap.encrypt(plain)
        }
        // Key length 2 leaves only two sequence bytes in an aligned eight-byte block.
        assertFails { Handshake().accept(0x34, sealed(hex("0000000201020001"))) }
        val zeroSequence = wrap.decrypt(initial).also { ByteBuffer.wrap(it).putInt(20, 0) }
        assertFails { Handshake().accept(0x34, sealed(zeroSequence)) }
        val wrongEcho = ByteBuffer.allocate(20).putShort(0).putShort(16).put(a).array()
        val h = Handshake(b, 2); h.accept(0x34, initial)
        assertFails { h.accept(0x36, sealed(wrongEcho)) }
        assertEquals(Handshake.State.AWAITING_CONFIRMATION, h.state)
        assertNull(h.accept(0x36, confirm))
    }
    @Test fun sendingStopsBeforeSequenceWrap() {
        val h = Handshake(b, 0x7fffffff); h.accept(0x34, initial); h.accept(0x36, confirm)
        h.send(byteArrayOf(0x70, 1))
        assertFailsWith<IllegalStateException> { h.send(byteArrayOf(0x70, 1)) }
    }
    @Test fun expiredTicketsCannotAuthenticate() {
        var now = java.time.Instant.EPOCH
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        val tickets = SessionTickets(clock); val token = tickets.issue()
        now = now.plusSeconds(60)
        assertFalse(tickets.consume(token))
    }
    @Test fun goldenCompleteExchangeWhenAvailable() {
        val dir = System.getenv("LOGH7_GOLDEN_DIR")
        assumeTrue(!dir.isNullOrBlank(), "LOGH7_GOLDEN_DIR not set")
        val paths = listOf("initial-0034.bin", "reply-0035.bin", "confirm-0036.bin").map { Path.of(dir!!).resolve(it) }
        assumeTrue(paths.all { Files.exists(it) }, "Complete key exchange captures not available")
        val frames = paths.map { Files.readAllBytes(it) }
        fun payload(frame: ByteArray): Pair<Int, ByteArray> {
            require((ByteBuffer.wrap(frame).short.toInt() and 65535) == frame.size - 2)
            return Frames.decodePayload(frame.copyOfRange(2, frame.size))
        }
        val reply = payload(frames[1]); assertEquals(0x35, reply.first)
        val plain = ByteBuffer.wrap(wrap.decrypt(reply.second)); plain.short
        val aLength = plain.short.toInt() and 65535; plain.position(4 + aLength)
        val bLength = plain.short.toInt() and 65535
        val key = ByteArray(bLength).also { plain.get(it) }
        require(plain.remaining() >= 4)
        val h = Handshake(key, plain.int.toLong() and 0xffffffffL)
        val first = payload(frames[0]); assertContentEquals(frames[1], h.accept(first.first, first.second))
        val last = payload(frames[2]); assertNull(h.accept(last.first, last.second))
        assertEquals(Handshake.State.ESTABLISHED, h.state)
    }

    @Test fun capturedLoginStreamsWhenAvailable() {
        val directory = System.getenv("LOGH7_CAPTURED_LOGIN_DIR")
        assumeTrue(!directory.isNullOrBlank(), "LOGH7_CAPTURED_LOGIN_DIR not set")
        val root = Path.of(directory!!)
        val clients = Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".client-to-server.bin") }.sorted().toList()
        }
        assertTrue(clients.size >= 3, "At least three independent captured login streams required")
        fun frames(path: Path): List<Pair<Int, ByteArray>> {
            val stream = ByteBuffer.wrap(Files.readAllBytes(path))
            val result = mutableListOf<Pair<Int, ByteArray>>()
            while (stream.hasRemaining()) {
                assertTrue(stream.remaining() >= 2, "Truncated length: $path")
                val length = stream.short.toInt() and 65535
                assertTrue(length in 2..Frames.MAX_PAYLOAD && length <= stream.remaining(), "Invalid frame: $path")
                val payload = ByteArray(length).also { stream.get(it) }
                val decoded = Frames.decodePayload(payload)
                assertContentEquals(byteArrayOf((length ushr 8).toByte(), length.toByte()) + payload,
                    Frames.encode(decoded.first, decoded.second))
                result += decoded
            }
            return result
        }
        clients.forEach { clientPath ->
            val serverPath = root.resolve(clientPath.fileName.toString().replace(".client-to-server.bin", ".server-to-client.bin"))
            val client = frames(clientPath); val server = frames(serverPath)
            assertEquals(listOf(0x34, 0x36, 0x30), client.map { it.first })
            assertEquals(listOf(0x35, 0x30), server.map { it.first })
            val reply = ByteBuffer.wrap(wrap.decrypt(server[0].second))
            reply.short
            val aLength = reply.short.toInt() and 65535
            reply.position(4 + aLength)
            val bLength = reply.short.toInt() and 65535
            val key = ByteArray(bLength).also { reply.get(it) }
            val sequence = reply.int.toLong() and 0xffffffffL
            val h = Handshake(key, sequence)
            assertContentEquals(Frames.encode(server[0].first, server[0].second), h.accept(client[0].first, client[0].second))
            assertNull(h.accept(client[1].first, client[1].second))
            assertEquals(Handshake.State.ESTABLISHED, h.state)
            val login = h.receive(client[2].first, client[2].second)
            assertEquals(LoginMessages.Request("ginei00", "dummy"), LoginMessages.request(login))
            // Synthetic test credentials only; retain the decoded body in the local test output.
            println("${clientPath.fileName}: login=${java.util.HexFormat.of().formatHex(login)}")
            val (replySequence, body) = Envelope.decode(LegacyBlowfish(key).decrypt(server[1].second), sequence - 1)
            assertEquals(sequence, replySequence)
            assertContentEquals(LoginMessages.failure(), body)
            assertContentEquals(Frames.encode(server[1].first, server[1].second), h.send(body))
        }
    }
}
