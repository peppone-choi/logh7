package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

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
    @Test fun loginUsesActualUtf16LengthAndRejectsTruncation() {
        assertEquals(LoginMessages.Request("user", "pw"), LoginMessages.request(request()))
        assertFails { LoginMessages.request(request().dropLast(1).toByteArray()) }
        assertFails { LoginMessages.request(request() + byteArrayOf(0)) }
        assertContentEquals(hex("01020304002005"), LoginMessages.sessionMessage(0x20, byteArrayOf(5), 0x01020304))
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
}
