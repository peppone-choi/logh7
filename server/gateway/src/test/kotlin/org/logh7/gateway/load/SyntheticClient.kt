package org.logh7.gateway.load

import org.logh7.protocol.*
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/** Synthetic local client: shared codecs, strict lengths/checksum/sequence, no original assets. */
class SyntheticClient(port: Int, private val metrics: LoadMetrics, fragmentedHandshake: Boolean = false) : AutoCloseable {
    private val socket = Socket()
    private val input: DataInputStream
    private val outgoing = LegacyBlowfish(ByteArray(16) { it.toByte() })
    private lateinit var incoming: LegacyBlowfish
    private var sendSequence = 1L
    private var previous = -1L
    init {
        try {
            socket.soTimeout = 10_000; socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
            input = DataInputStream(socket.getInputStream())
            metrics.measure("handshake") { handshake(fragmentedHandshake) }
        } catch (failure: Throwable) { socket.close(); throw failure }
    }
    private fun handshake(fragmented: Boolean) {
        val wrapping = LegacyBlowfish(LegacyBlowfish.HANDSHAKE_KEY.toByteArray(Charsets.US_ASCII))
        val key = ByteArray(16) { it.toByte() }
        fun keyMessage(bytes: ByteArray, initial: Boolean): ByteArray {
            val plain = ByteBuffer.allocate(4 + bytes.size + if (initial) 4 else 0).putShort(0).putShort(bytes.size.toShort()).put(bytes)
            if (initial) plain.putInt(1)
            val data = plain.array(); ByteBuffer.wrap(data).putShort(Envelope.checksum(data.copyOfRange(2, data.size)).toShort())
            return wrapping.encrypt(data)
        }
        val initial = Frames.encode(0x34, keyMessage(key, true))
        check(initial.size == 28)
        if (fragmented) writeFragmented(initial) else write(initial)
        val (type, bytes) = Frames.decodePayload(readPayload())
        check(type == 0x35)
        val decrypted = wrapping.decrypt(bytes); val reply = ByteBuffer.wrap(decrypted)
        val checksum = reply.short.toInt() and 65535
        val peerSize = reply.short.toInt() and 65535; check(peerSize == key.size)
        check(ByteArray(peerSize).also { reply.get(it) }.contentEquals(key))
        val localSize = reply.short.toInt() and 65535; check(localSize == 16)
        val local = ByteArray(localSize).also { reply.get(it) }
        val sequence = reply.int.toLong() and 0xffffffffL
        check(sequence in 1..0x7fffffffL)
        check(checksum == Envelope.checksum(decrypted.copyOfRange(2, reply.position())))
        check(decrypted.drop(reply.position()).all { it == 0.toByte() })
        incoming = LegacyBlowfish(local); previous = sequence - 1
        write(Frames.encode(0x36, keyMessage(local, false)))
    }
    private fun readPayload(): ByteArray {
        val size = input.readUnsignedShort(); check(size in 2..Frames.MAX_PAYLOAD)
        return ByteArray(size).also { input.readFully(it) }
    }
    private fun write(bytes: ByteArray) { socket.getOutputStream().write(bytes); socket.getOutputStream().flush() }
    private fun writeFragmented(bytes: ByteArray) {
        socket.getOutputStream().write(bytes, 0, 1); socket.getOutputStream().flush()
        Thread.sleep(5)
        socket.getOutputStream().write(bytes, 1, 2); socket.getOutputStream().flush()
        Thread.sleep(5)
        socket.getOutputStream().write(bytes, 3, bytes.size - 3); socket.getOutputStream().flush()
    }
    fun send(vararg bodies: ByteArray) {
        val frames = bodies.fold(ByteArray(0)) { result, body ->
            result + Frames.encode(0x30, outgoing.encrypt(Envelope.encode(sendSequence++, body)))
        }
        write(frames)
    }
    fun receive(clearHeader: Boolean = true): ByteArray {
        var payload = readPayload()
        if (clearHeader) { check(ByteBuffer.wrap(payload).int == 0); payload = payload.copyOfRange(4, payload.size) }
        val (type, bytes) = Frames.decodePayload(payload); check(type == 0x30)
        val decoded = Envelope.decode(incoming.decrypt(bytes), previous); previous = decoded.first
        return decoded.second
    }
    fun expect(opcode: Int): ByteArray = receive().also {
        check(it.size >= 6 && ByteBuffer.wrap(it).int == 0 && (ByteBuffer.wrap(it).getShort(4).toInt() and 65535) == opcode)
    }
    fun request(body: ByteArray, opcode: Int, operation: String): ByteArray = metrics.measure(operation) { send(body); expect(opcode) }
    fun expectClosed() { check(input.read() == -1) { "Unexpected data after expected connection close" } }
    fun reset() { socket.setSoLinger(true, 0); socket.close() }
    override fun close() { socket.close() }
    companion object {
        fun opcode(value: Int) = ByteBuffer.allocate(2).putShort(value.toShort()).array()
        fun token(value: Long) = ByteBuffer.allocate(6).putShort(0x20).putInt(value.toInt()).array()
        fun login(account: String, password: String): ByteArray {
            val name = account + '\u0000'; val secret = password + '\u0000'
            return ByteBuffer.allocate(13 + 2 * (name.length + secret.length)).also { out ->
                out.putShort(0x7000).putInt(0x47494e37).putShort(1).putShort(0).put(0).put(name.length.toByte())
                name.forEach { out.putChar(it) }; out.put(secret.length.toByte()); secret.forEach { out.putChar(it) }
            }.array()
        }
        fun creationBody(character: CharacterMessages.Generate): ByteArray {
            val reply = CharacterMessages.generateReply(character)
            return opcode(0x1008) + reply.copyOfRange(6, reply.size)
        }
        fun generated(response: ByteArray): CharacterMessages.Generate {
            check(ByteBuffer.wrap(response).getShort(4).toInt() and 65535 == 0x1008)
            return CharacterMessages.generate(opcode(0x1008) + response.copyOfRange(6, response.size))
        }
    }
}
