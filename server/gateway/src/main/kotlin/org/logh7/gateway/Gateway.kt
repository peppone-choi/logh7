package org.logh7.gateway

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import org.logh7.protocol.Frames
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.*
import java.time.Instant
import java.util.UUID

class Capture(private val directory: Path) {
    init { Files.createDirectories(directory) }
    @Synchronized fun record(connection: String, direction: String, buffer: ByteBuf) {
        val bytes = ByteArray(buffer.readableBytes()); buffer.getBytes(buffer.readerIndex(), bytes)
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        Files.writeString(directory.resolve("$connection.tsv"), "${Instant.now()}\t$direction\t$hex\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val suffix = when (direction) {
            "C>S" -> "client-to-server"
            "S>C" -> "server-to-client"
            else -> error("Unknown direction")
        }
        Files.write(directory.resolve("$connection.$suffix.bin"), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

internal fun checkedBindAddress(address: String): String {
    if (address == "127.0.0.1") return address
    val octets = address.split('.')
    require(octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }) {
        "Bind address must be a literal IPv4 address"
    }
    val ip = InetAddress.getByName(address)
    require(ip.isSiteLocalAddress || ip.isLinkLocalAddress) { "Bind address must be private or link-local" }
    val adapter = NetworkInterface.getByInetAddress(ip)
    require(adapter != null && adapter.isUp &&
        (adapter.displayName.contains("VirtualBox Host-Only", ignoreCase = true) ||
         adapter.name.contains("vboxnet", ignoreCase = true))) {
        "Bind address must belong to an active VirtualBox host-only adapter"
    }
    return address
}
internal class CaptureHandler(private val capture: Capture, private val id: String) : ChannelDuplexHandler() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { if (msg is ByteBuf) capture.record(id, "C>S", msg); ctx.fireChannelRead(msg) }
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) { if (msg is ByteBuf) capture.record(id, "S>C", msg); ctx.write(msg, promise) }
}

class Gateway(private val captureDirectory: Path = Path.of("E:/logh7/work/logh7-dynamic-p2/captures"), private val accounts: Map<String, String> = emptyMap(), private val characterFile: Path? = null) : AutoCloseable {
    private val boss = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workers = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
    private val channels = mutableListOf<Channel>()
    fun start(gamePort: Int = 47900, updatePort: Int = 47902, bindAddress: String = "127.0.0.1", sessionPort: Int = 47903, sessionAddress: String = bindAddress) {
        val host = checkedBindAddress(bindAddress)
        require(listOf(gamePort, updatePort, sessionPort).all { it in 1..65535 } && setOf(gamePort, updatePort, sessionPort).size == 3)
        val tickets = SessionTickets()
        val characters = CharacterCreation(characterFile)
        val capture = Capture(captureDirectory)
        try {
            listOf(gamePort, updatePort, sessionPort).forEach { port ->
                val bootstrap = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
                    .childHandler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            val exchange = GameExchange(if (port == sessionPort) GameExchange.Role.SESSION else GameExchange.Role.LOGIN, accounts, tickets, sessionAddress, sessionPort, characters = characters)
                            val updateExchange = UpdateExchange()
                            ch.pipeline().addLast(CaptureHandler(capture, "$port-${UUID.randomUUID()}"))
                            ch.pipeline().addLast(LengthFieldBasedFrameDecoder(Frames.MAX_PAYLOAD + 2, 0, 2, 0, 2))
                            ch.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                                override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                                    val payload = ByteArray(msg.readableBytes()); msg.readBytes(payload)
                                    if (port != updatePort) {
                                        val (type, data) = Frames.decodePayload(payload)
                                        exchange.accept(type, data)?.let { response ->
                                            val written = ctx.writeAndFlush(Unpooled.wrappedBuffer(response))
                                            if (exchange.state == GameExchange.State.REJECTED) written.addListener(ChannelFutureListener.CLOSE)
                                        }
                                    } else {
                                        val response = updateExchange.accept(payload)
                                        val written = ctx.writeAndFlush(Unpooled.wrappedBuffer(response))
                                        if (updateExchange.state == UpdateExchange.State.COMPLETE) {
                                            written.addListener(ChannelFutureListener.CLOSE)
                                        }
                                    }
                                }
                                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { System.err.println("gateway: ${cause.message}"); exchange.close(); ctx.close() }
                            })
                        }
                    })
                channels += bootstrap.bind(host, port).sync().channel()
            }
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun close() { channels.forEach { it.close().syncUninterruptibly() }; workers.shutdownGracefully().syncUninterruptibly(); boss.shutdownGracefully().syncUninterruptibly() }
}
