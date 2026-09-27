package org.logh7.gateway

import io.netty.buffer.Unpooled
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.logh7.protocol.Frames

class GatewayTest {
    @TempDir lateinit var captures: Path

    @Test fun rawCapturePreservesInboundChunksAndOutboundBytes() {
        val channel = EmbeddedChannel(CaptureHandler(Capture(captures), "sample"))
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0, 4)))
            channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x68, 0x10, 1, 2)))
            channel.writeOutbound(Unpooled.wrappedBuffer(byteArrayOf(0, 2, 0x68, 0x11)))
            assertContentEquals(byteArrayOf(0, 4, 0x68, 0x10, 1, 2), Files.readAllBytes(captures.resolve("sample.client-to-server.bin")))
            assertContentEquals(byteArrayOf(0, 2, 0x68, 0x11), Files.readAllBytes(captures.resolve("sample.server-to-client.bin")))
            val lines = Files.readAllLines(captures.resolve("sample.tsv"))
            assertEquals(3, lines.size)
            assertTrue(lines[0].endsWith("\tC>S\t0004"))
            assertTrue(lines[1].endsWith("\tC>S\t68100102"))
            assertTrue(lines[2].endsWith("\tS>C\t00026811"))
        } finally { channel.finishAndReleaseAll() }
    }

    @Test fun bindRejectsExternalAndWildcardAddresses() {
        assertEquals("127.0.0.1", checkedBindAddress("127.0.0.1"))
        listOf("0.0.0.0", "8.8.8.8", "localhost", "127.0.0.2").forEach { address ->
            assertFailsWith<IllegalArgumentException>(address) { checkedBindAddress(address) }
        }
    }
    @Test fun fragmentedAndCoalescedFrames() {
        val channel = EmbeddedChannel(LengthFieldBasedFrameDecoder(Frames.MAX_PAYLOAD + 2, 0, 2, 0, 2))
        try {
            val frame = Frames.encode(0x34, byteArrayOf(1, 2))
            assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(frame.copyOfRange(0, 1))))
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame.copyOfRange(1, frame.size) + frame)))
            repeat(2) { val buffer = channel.readInbound<ByteBuf>(); try { assertEquals(4, buffer.readableBytes()); assertEquals(0x34, buffer.readUnsignedShort()) } finally { buffer.release() } }
            assertNull(channel.readInbound<Any>())
        } finally { channel.finishAndReleaseAll() }
    }
    @Test fun keyExchangeDoesNotAcceptApplicationBeforeKeys() {
        assertFailsWith<IllegalArgumentException> { Handshake().observe(0x30) }
        val handshake = Handshake(); handshake.observe(0x34)
        assertEquals(Handshake.State.INITIAL_KEY_OBSERVED, handshake.state)
        assertFailsWith<IllegalStateException> { handshake.observe(0x35) }
    }

    @Test fun updaterCompletesOnlyAfterIdentificationAndVersion() {
        val exchange = UpdateExchange()
        assertContentEquals(byteArrayOf(0, 2, 0x68, 0x11), exchange.accept(byteArrayOf(0x68, 0x10, 1, 2)))
        assertEquals(UpdateExchange.State.AWAITING_VERSION, exchange.state)
        assertFailsWith<IllegalArgumentException> { exchange.accept(byteArrayOf(0x68, 0x20, 0, 0, 0)) }
        assertContentEquals(byteArrayOf(0, 2, 0x68, 0x22), exchange.accept(byteArrayOf(0x68, 0x20, 0, 0, 0, 0x83.toByte())))
        assertEquals(UpdateExchange.State.COMPLETE, exchange.state)
        assertFailsWith<IllegalStateException> { exchange.accept(byteArrayOf(0x68, 0x20, 0, 0, 0, 0)) }
    }
}
