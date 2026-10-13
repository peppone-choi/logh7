package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.gateway.load.LoadMetrics
import org.logh7.gateway.load.SyntheticClient
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Synthetic TCP contract only: no original updater or game process is launched. */
class UpdatePortTest {
    @TempDir lateinit var captures: Path

    private val identification = byteArrayOf(0, 4, 0x68, 0x10, 1, 2)
    private val identified = byteArrayOf(0, 2, 0x68, 0x11)
    private val version = byteArrayOf(0, 6, 0x68, 0x20, 0, 0, 0, 0x83.toByte())
    private val complete = byteArrayOf(0, 2, 0x68, 0x22)

    private class Fixture(val gateway: Gateway, val gamePort: Int, val updatePort: Int) : AutoCloseable {
        fun updater(): Socket {
            val socket = Socket()
            try {
                socket.soTimeout = 5_000
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", updatePort), 5_000)
                return socket
            } catch (failure: Throwable) { socket.close(); throw failure }
        }
        fun assertGameListenerAlive() {
            // A successful application handshake proves more than a TCP backlog accepting connect().
            SyntheticClient(gamePort, LoadMetrics()).use { }
        }
        override fun close() = gateway.close()
    }

    private fun startGateway(): Fixture {
        repeat(5) { attempt ->
            val reservations = mutableListOf<ServerSocket>()
            val ports = try {
                repeat(3) { reservations += ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
                reservations.map { it.localPort }
            } finally { reservations.forEach { it.close() } }
            val gateway = Gateway(captures.resolve("attempt-$attempt"))
            try {
                gateway.start(gamePort = ports[0], updatePort = ports[1], sessionPort = ports[2])
                return Fixture(gateway, ports[0], ports[1])
            } catch (failure: Throwable) {
                gateway.close()
                if (generateSequence(failure) { it.cause }.none { it is BindException } || attempt == 4) throw failure
            }
        }
        error("No available loopback ports")
    }

    private fun Socket.send(bytes: ByteArray) {
        getOutputStream().write(bytes)
        getOutputStream().flush()
    }
    private fun Socket.expect(bytes: ByteArray) {
        assertContentEquals(bytes, getInputStream().readNBytes(bytes.size))
    }
    private fun Socket.expectEof() {
        // A timeout or reset fails: require actual EOF and zero additional response bytes.
        assertEquals(-1, getInputStream().read(), "Expected server EOF without additional response")
    }

    @Test fun normalExchangeUsesExactPlaintextBytesAndCloses() {
        startGateway().use { fixture ->
            fixture.updater().use { socket ->
                socket.send(identification); socket.expect(identified)
                socket.send(version); socket.expect(complete); socket.expectEof()
            }
            fixture.assertGameListenerAlive()
        }
    }

    @Test fun emptyIdentificationBlobIsAccepted() {
        startGateway().use { fixture ->
            fixture.updater().use { socket ->
                socket.send(byteArrayOf(0, 2, 0x68, 0x10)); socket.expect(identified)
                socket.send(version); socket.expect(complete); socket.expectEof()
            }
        }
    }

    @Test fun oneByteWritesPreserveExchange() {
        startGateway().use { fixture ->
            fixture.updater().use { socket ->
                // TCP may coalesce writes; this deliberately splits both length/opcode/body at the sender.
                identification.forEach { socket.send(byteArrayOf(it)) }
                socket.expect(identified)
                version.forEach { socket.send(byteArrayOf(it)) }
                socket.expect(complete); socket.expectEof()
            }
        }
    }

    @Test fun coalescedRequestsProduceOnlyTheTwoReplies() {
        startGateway().use { fixture ->
            fixture.updater().use { socket ->
                socket.send(identification + version)
                socket.expect(identified + complete); socket.expectEof()
            }
        }
    }

    @Test fun invalidFirstOpcodeClosesWithoutResponseAndGameListenerSurvives() {
        startGateway().use { fixture ->
            listOf(byteArrayOf(0, 2, 0x68, 0x20), byteArrayOf(0, 2, 0x68, 0x11)).forEach { request ->
                fixture.updater().use { socket -> socket.send(request); socket.expectEof() }
                fixture.assertGameListenerAlive()
            }
        }
    }

    @Test fun missingOpcodeClosesWithoutResponseAndGameListenerSurvives() {
        startGateway().use { fixture ->
            listOf(byteArrayOf(0, 0), byteArrayOf(0, 1, 0x68)).forEach { request ->
                fixture.updater().use { socket -> socket.send(request); socket.expectEof() }
                fixture.assertGameListenerAlive()
            }
        }
    }

    @Test fun invalidVersionBodyLengthsCloseWithoutFurtherResponseAndGameListenerSurvives() {
        startGateway().use { fixture ->
            listOf(0, 3, 5).forEach { bodyLength ->
                fixture.updater().use { socket ->
                    socket.send(identification); socket.expect(identified)
                    socket.send(byteArrayOf(0, (bodyLength + 2).toByte(), 0x68, 0x20) + ByteArray(bodyLength))
                    socket.expectEof()
                }
                fixture.assertGameListenerAlive()
            }
        }
    }

    @Test fun requestQueuedAfterCompletionGetsNoAdditionalReplyAndActualEof() {
        startGateway().use { fixture ->
            fixture.updater().use { socket ->
                socket.send(identification); socket.expect(identified)
                // Queue the extra request with the terminal request to avoid a write-vs-close race.
                socket.send(version + identification)
                // The valid version request still owns its normal reply; only the EXTRA reply is forbidden.
                socket.expect(complete); socket.expectEof()
            }
            fixture.assertGameListenerAlive()
        }
    }
}
