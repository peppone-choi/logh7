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
import java.util.concurrent.atomic.AtomicBoolean
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor

data class GatewayConnections(val active: Int, val tracked: Int)

fun captureDirectoryFrom(value: String?): Path? = value?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

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

class Gateway(private val captureDirectory: Path? = null, private val accounts: Map<String, String> = emptyMap(), private val characterFile: Path? = null,
    private val gameSeconds: () -> Long = { 0 }, private val admission: GameAdmission? = null,
    private val world: WorldBootstrap? = null, private val navigation: GameNavigation? = null,
    private val stock: GameStock? = null, private val administration: GameAdministration? = null) : AutoCloseable {
    private val boss = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workers = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
    private val channels = mutableListOf<Channel>()
    private val clients = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
    private val closed = AtomicBoolean()
    private var combatSubscription: AutoCloseable? = null
    private var movementSubscription: AutoCloseable? = null
    private var supportSubscription: AutoCloseable? = null
    /** Read-only lifecycle observation; listeners and bind policy are unchanged. */
    fun connections() = GatewayConnections(clients.count { it.isActive }, clients.size)
    fun start(gamePort: Int = 47900, updatePort: Int = 47902, bindAddress: String = "127.0.0.1", sessionPort: Int = 47903, sessionAddress: String = bindAddress,
        additionalBindAddresses: List<String> = emptyList()) {
        val hosts = (listOf(bindAddress) + additionalBindAddresses).map(::checkedBindAddress)
        require(hosts.distinct().size == hosts.size)
        require(listOf(gamePort, updatePort, sessionPort).all { it in 1..65535 } && setOf(gamePort, updatePort, sessionPort).size == 3)
        val tickets = SessionTickets()
        val presence = if (world != null && navigation != null) GamePresence() else null
        combatSubscription = presence?.let { navigation!!.observeCombat(it::combat) }
        movementSubscription = presence?.let { navigation!!.observeMovements(it::movement) }
        val characterStores = CharacterStores(characterFile)
        fun currentCharacters() = characterStores.get(admission?.generation ?: 1)
        supportSubscription = presence?.let { p -> navigation!!.observeSupport { result ->
            navigation.snapshot()?.let { snapshot -> p.support(result,snapshot,world!!.galaxy,currentCharacters()::characterById) }
        } }
        val capture = captureDirectory?.let(::Capture)
        try {
            for (host in hosts) {
                val advertisedAddress = if (host == bindAddress) sessionAddress else host
                listOf(gamePort, updatePort, sessionPort).forEach { port ->
                    val bootstrap = ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel::class.java)
                        .childHandler(object : ChannelInitializer<SocketChannel>() {
                            override fun initChannel(ch: SocketChannel) {
                                clients.add(ch)
                                val generation = admission?.generation
                                val exchange = GameExchange(if (port == sessionPort) GameExchange.Role.SESSION else GameExchange.Role.LOGIN, accounts, tickets, advertisedAddress, sessionPort, characters = characterStores.get(generation ?: 1), gameSeconds = gameSeconds, admission = admission, expectedGeneration = generation, characterProvider = ::currentCharacters, world = world, navigation = navigation,
                                stock = stock, administration = administration, presence = presence, onPresence = { messages ->
                                        if (ch.isActive) ch.eventLoop().execute { ch.pipeline().fireUserEventTriggered(PresenceNotification(messages)) }
                                    })
                                val updateExchange = UpdateExchange()
                                if (capture != null) ch.pipeline().addLast(CaptureHandler(capture, "$port-${UUID.randomUUID()}"))
                                ch.pipeline().addLast(LengthFieldBasedFrameDecoder(Frames.MAX_PAYLOAD + 2, 0, 2, 0, 2))
                                ch.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                                    private val waitingFrames = ArrayDeque<ByteArray>()
                                    private var waiting = false
                                    private var refreshRequested = false
                                    private val notifications = ArrayDeque<List<ByteArray>>()
                                    private var notificationBytes = 0
                                    override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
                                        if (event !is PresenceNotification) { ctx.fireUserEventTriggered(event); return }
                                        if (!ctx.channel().isActive) return
                                        if (waiting) {
                                            val size = event.messages.sumOf { it.size }
                                            if (notifications.size >= 128 || notificationBytes + size > 512 * 1024) {
                                                exchange.close(); ctx.close(); return
                                            }
                                            notifications.addLast(event.messages); notificationBytes += size
                                        } else sendNotification(ctx, event.messages)
                                    }
                                    private fun sendNotification(ctx: ChannelHandlerContext, messages: List<ByteArray>) {
                                        try { exchange.notifyWorld(messages)?.let { SecureWriter.write(ctx, exchange, it) } }
                                        catch (_: Exception) { try { exchange.close() } finally { ctx.close() } }
                                    }
                                    private var lifecycleCheck: java.util.concurrent.ScheduledFuture<*>? = null
                                    override fun channelActive(ctx: ChannelHandlerContext) {
                                        if (admission != null) lifecycleCheck = ctx.executor().scheduleAtFixedRate({
                                            try {
                                                if (exchange.obsolete()) { exchange.close(); ctx.close() }
                                                else if (port == sessionPort && ctx.channel().isActive) {
                                                    if (waiting) refreshRequested = true else refresh(ctx)
                                                }
                                            } catch (_: Exception) { try { exchange.close() } finally { ctx.close() } }
                                        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
                                        ctx.fireChannelActive()
                                    }
                                    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                                        val payload = ByteArray(msg.readableBytes()); msg.readBytes(payload)
                                        if (waiting) { check(waitingFrames.size < 128); waitingFrames.addLast(payload); return }
                                        process(ctx, payload)
                                    }
                                    private fun process(ctx: ChannelHandlerContext, payload: ByteArray) {
                                        if (port != updatePort) {
                                            val (type, data) = Frames.decodePayload(payload)
                                            exchange.acceptOutbound(type, data)?.let { response -> SecureWriter.write(ctx, exchange, response) }
                                            val join = exchange.takePendingJoin()
                                            val operation = exchange.takePendingWorld()
                                            val pending = join?.thenApply { exchangeResult -> ({ exchange.finishJoinOutbound(exchangeResult) }) }
                                                ?: operation?.thenApply { messages -> ({ exchange.finishWorld(messages) }) }
                                            if (pending != null) awaitPending(ctx, pending)
                                        } else {
                                            val response = updateExchange.accept(payload)
                                            val written = ctx.writeAndFlush(Unpooled.wrappedBuffer(response))
                                            if (updateExchange.state == UpdateExchange.State.COMPLETE) {
                                                written.addListener(ChannelFutureListener.CLOSE)
                                            }
                                        }
                                    }
                                    private fun refresh(ctx: ChannelHandlerContext) {
                                        refreshRequested = false
                                        val operation = exchange.refreshWorld() ?: return
                                        awaitPending(ctx, operation.thenApply { messages -> ({ exchange.finishWorld(messages) }) })
                                    }
                                    private fun awaitPending(ctx: ChannelHandlerContext, pending: java.util.concurrent.CompletableFuture<() -> OutboundBatch?>) {
                                        waiting = true
                                        pending.whenComplete { finish, failure ->
                                            ctx.executor().execute {
                                                if (!ctx.channel().isActive || failure != null) {
                                                    if(failure != null) System.err.println("gateway async: ${failure.cause?.message ?: failure.message}")
                                                    try { exchange.close() } finally { ctx.close() }
                                                }
                                                else try {
                                                    finish()?.let { response -> SecureWriter.write(ctx, exchange, response) }
                                                    waiting = false
                                                    while (notifications.isNotEmpty() && ctx.channel().isActive) {
                                                        val messages = notifications.removeFirst(); notificationBytes -= messages.sumOf { it.size }
                                                        sendNotification(ctx, messages)
                                                    }
                                                    while (!waiting && waitingFrames.isNotEmpty() && ctx.channel().isActive) process(ctx, waitingFrames.removeFirst())
                                                    if (!waiting && refreshRequested && ctx.channel().isActive) refresh(ctx)
                                                } catch (failure: Exception) {
                                                    System.err.println("gateway outbound: ${failure.message}")
                                                    try { exchange.close() } finally { ctx.close() }
                                                }
                                            }
                                        }
                                    }
                                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                        System.err.println("gateway: ${cause.message}")
                                        try { exchange.close() } finally { ctx.close() }
                                    }
                                    override fun channelInactive(ctx: ChannelHandlerContext) {
                                        lifecycleCheck?.cancel(false); waitingFrames.clear(); notifications.clear(); notificationBytes = 0
                                        exchange.close(); ctx.fireChannelInactive()
                                    }
                                })
                            }
                        })
                    channels += bootstrap.bind(host, port).sync().channel()
                }
            }
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        combatSubscription?.close()
        movementSubscription?.close()
        supportSubscription?.close()
        try { channels.forEach { it.close().syncUninterruptibly() }; clients.close().awaitUninterruptibly() }
        finally { try { workers.shutdownGracefully().syncUninterruptibly() } finally { boss.shutdownGracefully().syncUninterruptibly() } }
    }
}
