package org.logh7.gateway

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext

/** evidence:guess — event-loop-only completion; IO success is not peer acceptance. */
internal object SecureWriter {
    fun write(ctx: ChannelHandlerContext, exchange: GameExchange, batch: OutboundBatch) {
        fun terminate() {
            try { exchange.close() } finally { ctx.close() }
        }
        if (!ctx.channel().isActive || exchange.state == GameExchange.State.CLOSED) { terminate(); return }
        val rejected = exchange.state == GameExchange.State.REJECTED
        try {
            exchange.submitting(batch)
            ctx.writeAndFlush(Unpooled.wrappedBuffer(batch.bytes)).addListener(ChannelFutureListener { result ->
                if (!result.isSuccess) terminate()
                else {
                    try { exchange.committed(batch) } catch (_: Exception) { terminate(); return@ChannelFutureListener }
                    if (rejected) terminate()
                }
            })
        } catch (_: Exception) { terminate() }
    }
}
