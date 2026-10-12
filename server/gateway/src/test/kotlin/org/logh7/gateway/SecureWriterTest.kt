package org.logh7.gateway

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import org.junit.jupiter.api.Test
import org.logh7.engine.Admission
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.time.*
import java.util.concurrent.CompletableFuture
import kotlin.test.*

/** Synthetic aux/keys and held promises exercise local IO safety, not original-client interoperability. */
class SecureWriterTest {
    private val a = ByteArray(16) { it.toByte() }
    private val b = ByteArray(16) { (240 + it).toByte() }
    private val c = ByteArray(16) { (64 + it).toByte() }
    private fun hex(value: String) = java.util.HexFormat.of().parseHex(value)
    private fun encrypted(body: ByteArray, seq: Long = 1, key: ByteArray = a) =
        LegacyBlowfish(key).encrypt(Envelope.encode(seq, body))
    private class Held : ChannelOutboundHandlerAdapter() {
        data class Write(val bytes: ByteBuf, val promise: ChannelPromise)
        val writes = mutableListOf<Write>()
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            writes += Write(msg as ByteBuf, promise)
        }
        fun complete(index: Int, failure: Boolean = false, cancel: Boolean = false, consumed: Int = 0) {
            val write = writes[index]
            write.bytes.skipBytes(consumed); write.bytes.release()
            if (cancel) write.promise.cancel(false)
            else if (failure) write.promise.setFailure(IllegalStateException("synthetic IO failure"))
            else write.promise.setSuccess()
        }
        fun release() { writes.forEach { if (it.bytes.refCnt() > 0) it.bytes.release() } }
    }
    private data class Fixture(val h: Handshake, val exchange: GameExchange, val held: Held,
        val channel: EmbeddedChannel, val ctx: ChannelHandlerContext) {
        fun write(batch: OutboundBatch) = SecureWriter.write(ctx, exchange, batch)
        fun cleanup() { held.release(); channel.finishAndReleaseAll() }
    }
    private fun fixture(next: Long = 2, admission: GameAdmission? = null, characters: CharacterCreation = CharacterCreation(),
        purpose: SessionTickets.Purpose = SessionTickets.Purpose.LOBBY): Fixture {
        val h = Handshake(b, next, { c })
        val tickets = SessionTickets()
        val exchange = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
            h, characters, admission = admission)
        exchange.accept(0x34, hex("35eb18155d09bbb52a8d5c7fcef57d7712d9f1f6a2bebede"))
        exchange.accept(0x36, hex("385c1e213879e5fc44d62157e541b061e556d88f3a5ae35b"))
        exchange.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(tickets.issue("user", purpose).toInt()).array()))
        val held = Held()
        val bridge = object : ChannelInboundHandlerAdapter() {
            override fun channelInactive(ctx: ChannelHandlerContext) { exchange.close(); ctx.fireChannelInactive() }
        }
        val channel = EmbeddedChannel(held, bridge)
        return Fixture(h, exchange, held, channel, channel.pipeline().context(bridge))
    }
    private fun app() = LoginMessages.sessionMessage(0x0301, byteArrayOf())
    @Test fun delayedWritesUseCandidatesAndCommitOnlyTheSuccessfulPrefix() {
        val f = fixture(0x7fffffff)
        try {
            val batches = List(3) { f.h.stageAll(listOf(app()), 0) }
            batches.forEach(f::write)
            assertEquals(3, f.held.writes.size)
            f.held.complete(2); f.channel.runPendingTasks()
            assertEquals(0x7fffffffL, f.h.committedSequence)
            f.held.complete(1); f.channel.runPendingTasks()
            assertEquals(3, f.h.pendingBatches)
            f.held.complete(0); f.channel.runPendingTasks()
            assertEquals(2L, f.h.committedSequence); assertEquals(0, f.h.pendingBatches)
            // Byte snapshots belong to their original IDs even when later stages have already been prepared.
            val last = batches[2].bytes
            assertEquals(1L, Envelope.decode(LegacyBlowfish(c).decrypt(last.copyOfRange(8, last.size)), 0).first)
        } finally { f.cleanup() }
    }
    @Test fun applicationControlFailureAndCancellationInvalidateSuccessorsAndLateCallbacks() {
        for (mode in listOf("application", "control", "cancel", "later-success")) {
            val f = fixture(0x80000000)
            try {
                val first = f.h.stageAll(listOf(app()), 0)
                val second = f.h.stageAll(listOf(app()), 0)
                f.write(first); f.write(second)
                if (mode == "later-success") {
                    f.held.complete(1); f.channel.runPendingTasks()
                    assertEquals(0x80000000L, f.h.committedSequence)
                    assertEquals(2, f.h.pendingBatches)
                }
                val appLength = (ByteBuffer.wrap(first.bytes).short.toInt() and 65535) + 2
                f.held.complete(0, failure = mode != "cancel", cancel = mode == "cancel",
                    consumed = if (mode == "control") appLength else 0)
                f.channel.runPendingTasks()
                assertEquals(GameExchange.State.CLOSED, f.exchange.state)
                assertFalse(f.channel.isActive); assertEquals(0, f.h.pendingBatches)
                if (mode != "later-success") f.held.complete(1)
                f.channel.runPendingTasks(); f.exchange.committed(second)
                assertEquals(0x80000000L, f.h.committedSequence)
                f.write(second); assertEquals(2, f.held.writes.size) // no retry after close
            } finally { f.cleanup() }
        }
    }
    @Test fun immutableBytesAndSubmissionOrderCannotBeDetachedFromTheId() {
        for (forged in listOf(false, true)) {
            val f = fixture()
            try {
                val first = f.h.stageAll(listOf(app()), 0); val second = f.h.stageAll(listOf(app()), 0)
                val copy = first.bytes; copy.fill(0)
                assertFalse(first.bytes.contentEquals(copy))
                val replacement = OutboundBatch(f.h, first.id, second.bytes)
                f.exchange.committed(replacement)
                assertEquals(2L, f.h.committedSequence)
                f.write(if (forged) replacement else second)
                assertEquals(0, f.held.writes.size); assertFalse(f.channel.isActive)
                assertEquals(0, f.h.pendingBatches)
            } finally { f.cleanup() }
        }
    }
    @Test fun foreignOwnerAndDuplicateSubmissionCloseWithoutRewritingBytes() {
        val foreign = Handshake(b)
        foreign.accept(0x34, hex("35eb18155d09bbb52a8d5c7fcef57d7712d9f1f6a2bebede"))
        foreign.accept(0x36, hex("385c1e213879e5fc44d62157e541b061e556d88f3a5ae35b"))
        val f = fixture()
        try {
            f.write(foreign.stageAll(listOf(app()), 0))
            assertFalse(f.channel.isActive); assertEquals(0, f.held.writes.size)
        } finally { f.cleanup(); foreign.close() }
        val duplicate = fixture()
        try {
            val batch = duplicate.h.stageAll(listOf(app()), 0)
            duplicate.write(batch); duplicate.write(batch)
            assertEquals(1, duplicate.held.writes.size); assertFalse(duplicate.channel.isActive)
            duplicate.held.complete(0); duplicate.channel.runPendingTasks()
            assertEquals(2L, duplicate.h.committedSequence)
        } finally { duplicate.cleanup() }
    }
    @Test fun throwingOutboundHandlerClosesAndDoesNotCommit() {
        val f = fixture()
        try {
            f.channel.pipeline().addBefore(f.ctx.name(), "synthetic-failure", object : ChannelOutboundHandlerAdapter() {
                override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                    (msg as ByteBuf).release()
                    throw IllegalStateException("synthetic outbound exception")
                }
            })
            f.write(f.h.stageAll(listOf(app()), 0)); f.channel.runPendingTasks()
            assertFalse(f.channel.isActive); assertEquals(GameExchange.State.CLOSED, f.exchange.state)
            assertEquals(0, f.h.pendingBatches); assertEquals(2L, f.h.committedSequence)
            assertTrue(f.held.writes.isEmpty())
        } finally { f.cleanup() }
    }
    @Test fun closeBeforeCompletionAndDuplicateCallbackHaveNoEffect() {
        val f = fixture()
        try {
            val batch = f.h.stageAll(listOf(app()), 0); f.write(batch)
            f.channel.close(); f.held.complete(0); f.channel.runPendingTasks()
            f.exchange.committed(batch)
            assertEquals(2L, f.h.committedSequence); assertEquals(0, f.h.pendingBatches)
        } finally { f.cleanup() }
        val success = fixture()
        try {
            val batch = success.h.stageAll(listOf(app()), 0); success.write(batch)
            success.held.complete(0); success.channel.runPendingTasks()
            success.exchange.committed(batch)
            assertEquals(3L, success.h.committedSequence)
        } finally { success.cleanup() }
    }
    @Test fun inboundRekeyWhileWritesWaitDoesNotChangeOutboundCandidate() {
        val f = fixture()
        try {
            val first = f.h.stageAll(listOf(app()), 0); f.write(first)
            val peerKey = ByteArray(16) { (96 + it).toByte() }
            assertNull(f.exchange.acceptOutbound(0x30, encrypted(byteArrayOf(0, 0x31) + peerKey, 2)))
            assertContentEquals(byteArrayOf(3, 0), f.h.receive(0x30, encrypted(byteArrayOf(3, 0), 1, peerKey)))
            val second = f.h.stageAll(listOf(app()), 0)
            assertEquals(3L, Envelope.decode(LegacyBlowfish(b).decrypt(second.bytes.copyOfRange(8, second.bytes.size)), 0).first)
            f.write(second); f.held.complete(0); f.held.complete(1); f.channel.runPendingTasks()
            assertEquals(4L, f.h.committedSequence)
        } finally { f.cleanup() }
    }
    @Test fun splitOrMergedIncomingControlIsConsumedWithoutAnAck() {
        for (split in listOf(false, true)) {
            val f = fixture()
            val responses = mutableListOf<OutboundBatch>()
            try {
                f.channel.pipeline().addLast(LengthFieldBasedFrameDecoder(Frames.MAX_PAYLOAD + 2, 0, 2, 0, 2))
                f.channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                        val bytes = ByteArray(msg.readableBytes()); msg.readBytes(bytes)
                        val (type, data) = Frames.decodePayload(bytes)
                        f.exchange.acceptOutbound(type, data)?.let(responses::add)
                    }
                })
                val control = Frames.encode(0x30, encrypted(byteArrayOf(0, 0x31) + c, 2))
                val application = Frames.encode(0x30, encrypted(byteArrayOf(0x20, 0), 1, c))
                val wire = control + application
                if (split) {
                    f.channel.writeInbound(Unpooled.wrappedBuffer(wire.copyOfRange(0, 3)))
                    assertTrue(responses.isEmpty())
                    f.channel.writeInbound(Unpooled.wrappedBuffer(wire.copyOfRange(3, wire.size)))
                } else f.channel.writeInbound(Unpooled.wrappedBuffer(wire))
                assertEquals(1, responses.size) // 0x2000 lobby request only; rekey emits no ACK.
                assertEquals(GameExchange.State.AUTHENTICATED, f.exchange.state)
            } finally { f.cleanup() }
        }
    }
    @Test fun asynchronousFinishJoinUsesTheBoundWriterAndIgnoresLateAdmissionAfterClose() {
        var now = Instant.EPOCH
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId) = this
            override fun instant() = now
        }
        val characters = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
        characters.apply("user", listOf(1001)); now = now.plusMillis(2)
        var pending = CompletableFuture<Admission>(); var disconnected = 0
        val access = object : GameAdmission {
            override val generation = 1L; override val running = true
            override fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long) = pending
            override fun disconnect(account: String, connection: String) { disconnected++ }
        }
        val f = fixture(0x7fffffff, access, characters, SessionTickets.Purpose.GAME)
        try {
            f.exchange.acceptOutbound(0x30, encrypted(byteArrayOf(2, 0), 2))!!.let(f::write)
            f.held.complete(0); f.channel.runPendingTasks()
            assertNull(f.exchange.acceptOutbound(0x30, encrypted(byteArrayOf(2, 5), 3)))
            assertSame(pending, f.exchange.takePendingJoin())
            pending.complete(Admission.ACCEPTED)
            val batch = assertNotNull(f.exchange.finishJoinOutbound(pending.getNow(null)))
            f.write(batch)
            assertEquals(2, f.held.writes.size)
            f.held.complete(1, failure = true); f.channel.runPendingTasks()
            assertEquals(GameExchange.State.CLOSED, f.exchange.state); assertEquals(1, disconnected)
            assertNull(f.exchange.finishJoinOutbound(Admission.ACCEPTED))
        } finally { f.cleanup() }
        pending = CompletableFuture()
        val late = fixture(2, access, characters, SessionTickets.Purpose.GAME)
        try {
            late.exchange.accept(0x30, encrypted(byteArrayOf(2, 0), 2))
            assertNull(late.exchange.acceptOutbound(0x30, encrypted(byteArrayOf(2, 5), 3)))
            assertSame(pending, late.exchange.takePendingJoin())
            late.channel.close(); pending.complete(Admission.ACCEPTED)
            assertNull(late.exchange.finishJoinOutbound(Admission.ACCEPTED))
            assertEquals(2, disconnected); assertTrue(late.held.writes.isEmpty())
            assertEquals(0, late.h.pendingBatches)
        } finally { late.cleanup() }
    }
}
