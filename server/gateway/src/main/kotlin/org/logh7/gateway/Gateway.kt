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
import org.logh7.protocol.LobbyMessages
import org.logh7.protocol.SessionEndingMessages
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor

data class GatewayConnections(val active: Int, val tracked: Int)

// Launcher-only diagnostic frame. The legacy client never sends it.
private val launcherProbe = byteArrayOf(0, 0x38) + "LOGH7?".toByteArray(Charsets.US_ASCII)
private val launcherReady = byteArrayOf(0, 8, 0, 0x38) + "LOGH7!".toByteArray(Charsets.US_ASCII)
private val launcherMaintenance = byteArrayOf(0, 8, 0, 0x38) + "LOGH7-".toByteArray(Charsets.US_ASCII)

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
    private val stock: GameStock? = null, private val administration: GameAdministration? = null,
    private val sessionDirectory:GameSessionDirectory?=null,
    private val sessionId:Int=LobbyMessages.LOCAL_SESSION_ID,
    private val sessionName:String=LobbyMessages.LOCAL_SESSION_NAME,
    private val accountFame:FameLedger?=null,
    private val npcDialogue:NpcCombatDialogue?=null,
    private val npcProfiles:org.logh7.engine.PublishedNpcFleetDialogue=org.logh7.engine.PublishedNpcFleetDialogue(),
    private val originalCandidates:org.logh7.engine.PublishedOriginalCandidates=org.logh7.engine.PublishedOriginalCandidates(),
    maintenanceStateFile: Path? = null) : AutoCloseable {
    /** Read the maintenance state before allocating listener resources. */
    val maintenance = MaintenanceGate(maintenanceStateFile)
    private val boss = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val workers = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
    private val channels = mutableListOf<Channel>()
    private val clients = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
    private data class PlayerConnection(val account: String, val channel: Channel)
    private val players = ConcurrentHashMap<String, PlayerConnection>()
    private val closed = AtomicBoolean()
    @Volatile private var started=false
    /** Login/ticket/update/connection outcomes for the ops metrics (repeated-failure alerts). */
    val counters = GatewayCounters()
    @Volatile private var livePresence: GamePresence? = null
    /** Operator notice to every online player as a "[운영]" spot chat line; returns the recipients (0 without a world). */
    fun operatorNotice(text: String): Int = livePresence?.operatorNotice(text, gameSeconds()) ?: 0
    private var sessionRegistration:AutoCloseable?=null
    private var tacticalEndSubscription: AutoCloseable? = null
    private var combatSubscription: AutoCloseable? = null
    private var movementSubscription: AutoCloseable? = null
    private var strategicMovementSubscription: AutoCloseable? = null
    private var offlineDockSubscription: AutoCloseable? = null
    private var occupationSubscription:AutoCloseable?=null
    private var coupDockSubscription:AutoCloseable?=null
    private var fortressSinkingSubscription:AutoCloseable?=null
    private var characterRefreshSubscription:AutoCloseable?=null
    private var tacticalOfficeRefreshSubscription: AutoCloseable? = null
    private var groundBattleSubscription:AutoCloseable?=null
    private var groundSubscription:AutoCloseable?=null
    private var postureSubscription:AutoCloseable? = null
    private var tacticalCommandSubscription:AutoCloseable? = null
    private var supportSubscription: AutoCloseable? = null
    private var coupBaseSubscription:AutoCloseable?=null
    private var baseFacilitySubscription: AutoCloseable? = null
    private var civicSubscription:AutoCloseable?=null
    private var budgetSubscription:AutoCloseable?=null
    private var successionSubscription:AutoCloseable?=null
    private var tidalSubscription:AutoCloseable?=null
    private var identitySubscription:AutoCloseable?=null
    private var originalBerthSubscription:AutoCloseable?=null
    private var fameSubscription:AutoCloseable?=null
    private var communication:GameCommunication?=null
    /** Read-only lifecycle observation; listeners and bind policy are unchanged. */
    fun connections() = GatewayConnections(clients.count { it.isActive }, clients.size)
    fun playerConnections(): Map<String, String> = players.entries.associate { (id, player) -> id to player.account }

    fun disconnectPlayer(id: String): CompletableFuture<Boolean> {
        val player = players[id] ?: return CompletableFuture.completedFuture(false)
        val result = CompletableFuture<Boolean>()
        val channel = player.channel
        // Run with channel lifecycle callbacks. A request can only close the connection selected in the form.
        channel.eventLoop().execute {
            if (!channel.isActive || players[id] !== player) result.complete(false)
            else channel.close().addListener { closed ->
                if (closed.isSuccess) result.complete(true) else result.completeExceptionally(closed.cause())
            }
        }
        return result
    }

    fun start(gamePort: Int = 47900, updatePort: Int = 47902, bindAddress: String = "127.0.0.1", sessionPort: Int = 47903, sessionAddress: String = bindAddress,
        additionalBindAddresses: List<String> = emptyList()) {
        val hosts = (listOf(bindAddress) + additionalBindAddresses).map(::checkedBindAddress)
        require(hosts.distinct().size == hosts.size)
        require(listOf(gamePort, updatePort, sessionPort).all { it in 1..65535 } && setOf(gamePort, updatePort, sessionPort).size == 3)
        require(sessionDirectory!=null || sessionId==LobbyMessages.LOCAL_SESSION_ID && sessionName==LobbyMessages.LOCAL_SESSION_NAME)
        val tickets = sessionDirectory?.tickets ?: SessionTickets()
        val presence = if (world != null && navigation != null) GamePresence(npcDialogue,npcProfiles) else null
        livePresence = presence
        presence?.disguise ={ id -> navigation?.snapshot()?.let {snapshot->
            val stocks=snapshot.planetStocks
            val player=snapshot.navigators.values.singleOrNull {it.characterId==id && it.generation==snapshot.session?.generation}
            if(stocks==null || player==null)null else org.logh7.engine.EspionageActions.disguise(stocks,player)
        }?.let {
            org.logh7.engine.EspionageActions.aliases[it.alias] to org.logh7.engine.CountryCatalogLoader.current()
                .nativePower(org.logh7.engine.Allegiance.Country(it.country)) } }
        tacticalEndSubscription = presence?.let { p -> navigation!!.observeTacticalEnds { event ->
            navigation.snapshot()?.let { snapshot -> p.tacticalEnd(event,snapshot) }
        } }
        movementSubscription = presence?.let { p -> navigation!!.observeMovements { event ->
            p.movement(event,navigation.snapshot())
        } }
        coupBaseSubscription=presence?.let {p->stock?.observeCoupBases {stocks,bases->
            val bootstrap=checkNotNull(world)
            val seconds=gameSeconds()
            val packets=bases.chunked(4).flatMap {ids->listOf(bootstrap.conditions(ids,stocks),bootstrap.places(ids,stocks,seconds))}
            p.refreshBaseOwnership(stocks.generation,null,packets)
        }}
        baseFacilitySubscription = presence?.let { p -> stock?.observeBaseFacilities { stocks, bases, seconds ->
            p.refreshBaseFacilities(checkNotNull(world), stocks, bases, seconds)
        } }
        civicSubscription=presence?.let {p->stock?.observeCivic {event->p.civic(event,checkNotNull(world))}}
        budgetSubscription=presence?.let {p->stock?.observeBudget {event->p.budget(event,checkNotNull(world))}}
        val characterStores = CharacterStores(characterFile,accountFame,originalCandidates) { navigation?.snapshot() }
        fun currentCharacters() = characterStores.get(admission?.generation ?: 1)
        fun currentOffices(player:org.logh7.engine.Navigator)=player.country
            ?.takeIf {world?.lobbyCountryDefinitions()?.first?.countries?.containsKey(it.value)==true}
            ?.let {administration?.offices(player.characterId,it.value,player.generation)}.orEmpty()
        fun information(snapshot:org.logh7.engine.WorldSnapshot?,registered:Map<String,CharacterProfile>):SessionEndingMessages.Session {
            return SessionEndingReport.information(snapshot,registered,sessionId,sessionName,admission?.generation ?: 1L)
        }
        // Mode 7 lobby catalogue of this world: definitions, colors and counts read once per request.
        fun lobbyCountries(request:org.logh7.protocol.LobbyCountryMessages.Request):List<ByteArray> {
            val (catalog,revision)=world?.lobbyCountryDefinitions()
                ?: org.logh7.engine.CountryCatalogLoader.snapshot()?.let {it.catalog to it.revision}
                ?: (org.logh7.engine.CountryCatalogLoader.current() to 0L)
            val snapshot=navigation?.snapshot()
            val starts=(world?.galaxy?.countryStarts?.keys ?: setOf(org.logh7.engine.CountryId(2),org.logh7.engine.CountryId(3)))+
                org.logh7.engine.CountryFounding.activeCapitals(snapshot?.planetStocks,catalog).keys
            return LobbyCountryCatalogue.reply(request,LobbyCountryCatalogue.Source(admission?.generation ?: 1L,
                snapshot,currentCharacters().profiles(),catalog,revision,world?.publishedCountryAppearances().orEmpty(),starts,
                world?.galaxy?.unaffiliatedStart!=null))
        }
        fun countryEndings(request:org.logh7.protocol.CountryEndingMessages.Request,account:String):List<ByteArray> {
            val snapshot=navigation?.snapshot()
            val profiles=currentCharacters().profiles()
            val viewer=WorldCharacters.profiles(profiles,snapshot)[account]?.country?.value ?: 0
            return CountryEndingReport.reply(request,snapshot,profiles,
                world?.lobbyCountryDefinitions()?.first ?: org.logh7.engine.CountryCatalogLoader.current(),
                world?.publishedCountryAppearances().orEmpty(),viewer,world?.galaxy)
        }
        sessionRegistration=sessionDirectory?.register(GameSessionDirectory.Entry(sessionId,sessionName,sessionAddress,sessionPort,hosts.toSet(),
            generation={admission?.generation ?: 1L},unavailableReason={
                when {
                    !started || closed.get() || admission?.running==false -> LobbyMessages.SelectionFailure.UNAVAILABLE
                    maintenance.closed() -> LobbyMessages.SelectionFailure.MAINTENANCE
                    else -> null
                }
            },
            information={information(navigation?.snapshot(),currentCharacters().profiles())},
            character={account->
                val snapshot=navigation?.snapshot();val characters=currentCharacters()
                val generation=snapshot?.session?.generation
                if(!started || closed.get() || generation!=null && generation!=(admission?.generation ?: 1L))null else {
                    snapshot?.planetStocks?.takeIf { it.generation == generation }?.deletedCharacters?.get(account)
                        ?.let { characters.reconcileVoluntaryDeletion(account, it) }
                    if (snapshot?.session?.ended == null && admission?.running != false) {
                        characters.character(account)?.takeIf {org.logh7.engine.BattleDeath.deceased(snapshot?.planetStocks,it.id.toInt())}
                            ?.let {characters.retire(account,it.id)}
                    }
                    characters.character(account)?.takeIf {admission?.running!=false || snapshot?.result!=null}?.let {character->
                        val info=information(snapshot,characters.profiles())
                        val evaluation=if(snapshot?.result==null)null else characterStores.fame.evaluation(snapshot,characters,account)
                        LobbyMessages.ChargedSession(character,info,evaluation)
                    }
                }
            },countries={request->if(!started || closed.get())null else lobbyCountries(request)},
            endings={request,account->if(!started || closed.get())null else countryEndings(request,account)},
            countryEmblem = { request -> world?.lobbyCountryEmblem(request) },
            portrait={account,id->
                currentCharacters().character(account)?.takeIf {it.id==id.toLong() && !it.generated}
                    ?.let {originalCandidates.portrait(it.id)}
            }, deleteCharacter = { account, generation, character ->
                val current = admission?.generation ?: 1L
                deleteLobbyCharacter(account, generation, character, current, characterStores.get(current), stock)
            }),accounts)
        successionSubscription=presence?.let {p->stock?.observeSuccession {event->p.succession(event,{id->
            currentCharacters().characterById(id) ?: WorldCharacters.governmentProfiles(navigation?.snapshot())
                .singleOrNull {it.character.id.toInt()==id}?.character},
            {country->org.logh7.engine.CountryCatalogLoader.current().countries[country.value]?.name})}}
        tidalSubscription=presence?.let {p->stock?.observeTides {event->
            p.tides(event,navigation!!.snapshot(),world!!.galaxy,currentCharacters()::characterById)
        }}
        fun settleResult(s:org.logh7.engine.WorldSnapshot) {s.session?.generation?.let {g->
            characterStores.fame.settle(s,characterStores.get(g))
            val countries=world?.lobbyCountryDefinitions()?.first ?: org.logh7.engine.CountryCatalogLoader.current()
            characterStores.carryOver(s,countries).takeIf {it.isNotEmpty()}?.let {println("carried over ${it.size} characters to generation ${g+1}")}}}
        fameSubscription=stock?.observeSessionResults(::settleResult)
        // An ended world can have been committed before the award observer ran; replay the durable result on boot.
        val recovered=navigation?.snapshot()
        if(stock!=null && recovered?.session?.ended!=null) {
            val generation=checkNotNull(recovered.session).generation
            stock.snapshot(generation).get(10,java.util.concurrent.TimeUnit.SECONDS)
            navigation?.snapshot()?.takeIf {it.result!=null}?.let(::settleResult)
        }
        originalBerthSubscription=stock?.provideOriginalBerthClaims {generation->characterStores.get(generation).originalBerthClaims()}
        identitySubscription=stock?.provideIdentities {WorldCharacters.identities(currentCharacters().profiles(),navigation?.snapshot())}
        communication=presence?.let {p->GameCommunication(p,{if(admission?.running==false)0 else admission?.generation ?: 1},
            {generation,id->communicationIdentity(characterStores.get(generation),id,
                navigation?.snapshot()?.takeIf {it.session?.generation==generation})},
            {account->navigation?.snapshot()?.navigators?.get(account)?.cpPaused==true},
            {generation->communicationStore(characterFile,generation)},
            proposals={_->navigation?.snapshot()?.planetStocks?.social?.proposals},
            rank={id->navigation?.snapshot()?.planetStocks?.strategic?.people?.get(id)?.rank},
            militaryRank={id->navigation?.snapshot()?.planetStocks?.strategic?.people?.get(id)?.let {
                it.militaryRank.takeIf {rank->rank>0} ?: it.rank
            }})}
        strategicMovementSubscription=presence?.let {p->navigation!!.observeStrategicMovements {event->
            navigation.snapshot()?.let {snapshot->p.strategicMovement(event,snapshot,world!!,currentCharacters()::characterById)}
        }}
        offlineDockSubscription=presence?.let {p->navigation!!.observeOfflineDockArrivals {event->
            navigation.snapshot()?.let {snapshot->p.offlineDockArrival(event,snapshot,world!!.galaxy)}
        }}
        combatSubscription = presence?.let { p -> navigation!!.observeCombat { impact ->
            navigation.snapshot()?.let { snapshot -> p.combat(impact,snapshot,world!!.galaxy,currentCharacters()::characterById) }
        }}
        coupDockSubscription=presence?.let {p->navigation!!.observeCoupDockCancellations {players->
            val session=navigation.snapshot()?.session
            if(session!=null)for(player in players.filter {it.generation==session.generation}) {
                val seconds=session.gameSeconds
                p.toCharacter(player.characterId,player.generation,listOf(
                    org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,player.baseId,player.mode,
                        seconds,player.spotId,if(player.spotId==0)player.characterId else player.spotOwner),
                    org.logh7.protocol.ChatMessages.spot(org.logh7.protocol.ChatMessages.Spot(
                        player.characterId,"[기항] 진영 변경·취소"),seconds)))
            }
        }}
        occupationSubscription=presence?.let {p->navigation!!.observeOccupations {event->
            navigation.snapshot()?.let {snapshot->p.occupation(event,snapshot,world!!,currentCharacters()::characterById,
                {id,power,generation->administration?.offices(id,power,generation).orEmpty()})}
        }}
        fortressSinkingSubscription=presence?.let {p->navigation!!.observeFortressSinking {departed->
            navigation.snapshot()?.let {snapshot->
                // A later commit (return, reentry) supersedes this sinking; never roll the client back.
                if(snapshot.navigators.values.none {it==departed})return@let
                val character=currentCharacters().characterById(departed.characterId)?.let {WorldCharacters.view(it,snapshot)} ?: return@let
                p.fortressSunk(departed,character,currentOffices(departed),
                    snapshot.session?.gameSeconds ?: 0L)
            }
        }}
        // Settlement can change the character record outside a request.
        characterRefreshSubscription=presence?.let {p->navigation!!.observeCharacterRefresh {ids->
            navigation.snapshot()?.let {snapshot->for(id in ids) {
                val character=currentCharacters().characterById(id)?.let {WorldCharacters.view(it,snapshot)} ?: continue
                val player=snapshot.navigators.values.singleOrNull {it.characterId==id} ?: continue
                p.refreshCharacter(character,player,currentOffices(player),
                    snapshot.session?.gameSeconds ?: 0L,administration?.stationedOutfit(player) ?: 0)
            }}
        }}
        tacticalOfficeRefreshSubscription = presence?.let { p -> navigation!!.observeTacticalOfficeRefresh { generation, outfits ->
            p.refreshTacticalOffices(generation, outfits, checkNotNull(world), navigation::snapshot, currentCharacters()::characterById)
        } }
        groundBattleSubscription=presence?.let {p->navigation!!.observeGroundBattles {event->
            navigation.snapshot()?.let {snapshot->
                val bootstrap=checkNotNull(world)
                val countries=bootstrap.lobbyCountryDefinitions().first.countries
                p.groundBattle(event,snapshot,bootstrap.galaxy,{countries[it.value]?.name},currentCharacters()::characterById)
            }
        }}
        groundSubscription=presence?.let {p->navigation!!.observeGround {event->
            navigation.snapshot()?.let {snapshot->p.ground(event,snapshot,world!!.galaxy,currentCharacters()::characterById)}
        }}
        postureSubscription=presence?.let {p->navigation!!.observePostures {event->
            navigation.snapshot()?.let {snapshot->p.posture(event,snapshot)}
        }}
        tacticalCommandSubscription=presence?.let {p->navigation!!.observeTacticalCommands {event->
            navigation.snapshot()?.let {snapshot->p.tacticalCommand(event,snapshot,world!!.galaxy,currentCharacters()::characterById)}
        }}
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
                                stock = stock, administration = administration, communication=communication, fame=characterStores.fame,
                                sessionDirectory=sessionDirectory,sessionId=sessionId,sessionName=sessionName,bindHost=host,counters=counters,maintenance=maintenance,lobbyCountries=::lobbyCountries,countryEndings=::countryEndings,originalCandidates=originalCandidates,
                                presence = presence, onPresence = { messages ->
                                        if (ch.isActive) ch.eventLoop().execute { ch.pipeline().fireUserEventTriggered(PresenceNotification(messages)) }
                                    }, onTacticalPresence = { grid, base, messages ->
                                        if (ch.isActive) ch.eventLoop().execute { ch.pipeline().fireUserEventTriggered(PresenceNotification(messages, grid, base)) }
                                    })
                                val updateExchange = UpdateExchange()
                                if (capture != null) ch.pipeline().addLast(CaptureHandler(capture, "$port-${UUID.randomUUID()}"))
                                ch.pipeline().addLast(LengthFieldBasedFrameDecoder(Frames.MAX_PAYLOAD + 2, 0, 2, 0, 2))
                                ch.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                                    private val waitingFrames = ArrayDeque<ByteArray>()
                                    private var firstFrame = true
                                    private var probeFinished = false
                                    private var waiting = false
                                    private var refreshRequested = false
                                    private val notifications = ArrayDeque<PresenceNotification>()
                                    private var notificationBytes = 0
                                    override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
                                        if (event !is PresenceNotification) { ctx.fireUserEventTriggered(event); return }
                                        if (!ctx.channel().isActive) return
                                        if (waiting) {
                                            val size = event.messages.sumOf { it.size }
                                            if (notifications.size >= 128 || notificationBytes + size > 512 * 1024) {
                                                counters.notificationOverflow.incrementAndGet()
                                                exchange.close(); ctx.close(); return
                                            }
                                            notifications.addLast(event); notificationBytes += size
                                            counters.notificationsQueued.incrementAndGet()
                                        } else sendNotification(ctx, event)
                                    }
                                    private fun sendNotification(ctx: ChannelHandlerContext, event: PresenceNotification) {
                                        if (exchange.accountRecovered()) { exchange.close(); ctx.close(); return }
                                        try { exchange.notifyWorld(event.messages, event.tacticalGrid, event.tacticalBase)?.let { SecureWriter.write(ctx, exchange, it) } }
                                        catch (_: Exception) { try { exchange.close() } finally { ctx.close() } }
                                    }
                                    private var lifecycleCheck: java.util.concurrent.ScheduledFuture<*>? = null
                                    override fun channelActive(ctx: ChannelHandlerContext) {
                                        if (admission != null || accounts is FileAccounts && port == sessionPort) lifecycleCheck = ctx.executor().scheduleAtFixedRate({
                                            try {
                                                if (exchange.accountRecovered()) { exchange.close(); ctx.close(); return@scheduleAtFixedRate }
                                                if(!waiting)exchange.finishSession()?.let {SecureWriter.write(ctx,exchange,it)}
                                                if (exchange.obsolete()) { exchange.close(); ctx.close() }
                                                else if (port == sessionPort && ctx.channel().isActive) {
                                                    if (waiting) refreshRequested = true else refresh(ctx)
                                                }
                                            } catch (_: Exception) { try { exchange.close() } finally { ctx.close() } }
                                        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
                                        ctx.fireChannelActive()
                                    }
                                    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                                        if (probeFinished) return
                                        val payload = ByteArray(msg.readableBytes()); msg.readBytes(payload)
                                        if (firstFrame) {
                                            firstFrame = false
                                            if (payload.contentEquals(launcherProbe)) {
                                                probeFinished = true
                                                if (port == gamePort) {
                                                    val reply = if (maintenance.closed()) launcherMaintenance else launcherReady
                                                    ctx.writeAndFlush(Unpooled.wrappedBuffer(reply)).addListener(ChannelFutureListener.CLOSE)
                                                } else ctx.close()
                                                return
                                            }
                                        }
                                        if (waiting) { check(waitingFrames.size < 128); waitingFrames.addLast(payload); return }
                                        process(ctx, payload)
                                    }
                                    private fun process(ctx: ChannelHandlerContext, payload: ByteArray) {
                                        if (port != updatePort) {
                                            val (type, data) = Frames.decodePayload(payload)
                                            exchange.acceptOutbound(type, data)?.let { response -> SecureWriter.write(ctx, exchange, response) }
                                            if (exchange.state == GameExchange.State.CLOSED) { ctx.close(); return }
                                            val join = exchange.takePendingJoin()
                                            val operation = exchange.takePendingWorld()
                                            val pending = join?.thenApply { exchangeResult -> ({
                                                val response = exchange.finishJoinOutbound(exchangeResult)
                                                exchange.joinedAccount()?.let { players[exchange.connection] = PlayerConnection(it, ctx.channel()) }
                                                response
                                            }) }
                                                ?: operation?.thenApply { messages -> ({ exchange.finishWorld(messages) }) }
                                            if (pending != null) awaitPending(ctx, pending)
                                        } else {
                                            val response = updateExchange.accept(payload)
                                            val written = ctx.writeAndFlush(Unpooled.wrappedBuffer(response))
                                            if (updateExchange.state == UpdateExchange.State.COMPLETE) {
                                                counters.updateCompleted.incrementAndGet()
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
                                                if (exchange.accountRecovered() || failure == null && exchange.sessionGenerationChanged()) {
                                                    exchange.close(); ctx.close()
                                                }
                                                else if(ctx.channel().isActive && failure!=null && exchange.sessionEndingAvailable()) {
                                                    waiting=false;refreshRequested=false
                                                    counters.notificationsQueued.addAndGet(-notifications.size.toLong());notifications.clear();notificationBytes=0
                                                    exchange.finishSession()?.let {SecureWriter.write(ctx,exchange,it)}
                                                    while(waitingFrames.isNotEmpty() && ctx.channel().isActive)process(ctx,waitingFrames.removeFirst())
                                                }
                                                else if (!ctx.channel().isActive || failure != null) {
                                                    if(failure != null) {val cause=failure.cause ?: failure
                                                        System.err.println("gateway async: ${cause.message} at ${cause.stackTrace.firstOrNull { it.className.startsWith("org.logh7") }}" +
                                                            (generateSequence(cause.cause) {it.cause}.take(64).lastOrNull()?.let {root->" caused by ${root.javaClass.simpleName}: ${root.message} at ${root.stackTrace.firstOrNull { it.className.startsWith("org.logh7") }}"} ?: ""))}
                                                    try { exchange.close() } finally { ctx.close() }
                                                }
                                                else try {
                                                    finish()?.let { response -> SecureWriter.write(ctx, exchange, response) }
                                                    waiting = false
                                                    while (notifications.isNotEmpty() && ctx.channel().isActive) {
                                                        val event = notifications.removeFirst(); notificationBytes -= event.messages.sumOf { it.size }
                                                        counters.notificationsQueued.decrementAndGet()
                                                        sendNotification(ctx, event)
                                                    }
                                                    while (!waiting && waitingFrames.isNotEmpty() && ctx.channel().isActive) process(ctx, waitingFrames.removeFirst())
                                                    if(!waiting && ctx.channel().isActive)exchange.finishSession()?.let {SecureWriter.write(ctx,exchange,it)}
                                                    if (!waiting && refreshRequested && ctx.channel().isActive) refresh(ctx)
                                                } catch (failure: Exception) {
                                                    System.err.println("gateway outbound: ${failure.message}")
                                                    try { exchange.close() } finally { ctx.close() }
                                                }
                                            }
                                        }
                                    }
                                    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                        counters.connectionErrors.incrementAndGet()
                                        // The first project frame locates a failed requirement without a full trace.
                                        System.err.println("gateway: ${cause.message} at ${cause.stackTrace.firstOrNull { it.className.startsWith("org.logh7") }}")
                                        try { exchange.close() } finally { ctx.close() }
                                    }
                                    override fun channelInactive(ctx: ChannelHandlerContext) {
                                        players.remove(exchange.connection)
                                        // A bare TCP probe never sends a frame; only a started, unfinished update is a failure.
                                        if (port == updatePort && updateExchange.state == UpdateExchange.State.AWAITING_VERSION)
                                            counters.updateFailed.incrementAndGet()
                                        lifecycleCheck?.cancel(false); waitingFrames.clear()
                                        counters.notificationsQueued.addAndGet(-notifications.size.toLong()); notifications.clear(); notificationBytes = 0
                                        exchange.close(); ctx.fireChannelInactive()
                                    }
                                })
                            }
                        })
                    channels += bootstrap.bind(host, port).sync().channel()
                }
            }
            started=true
        } catch (failure: Throwable) { close(); throw failure }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        started=false;sessionRegistration?.close()
        tacticalEndSubscription?.close()
        combatSubscription?.close()
        movementSubscription?.close()
        strategicMovementSubscription?.close()
        offlineDockSubscription?.close()
        groundSubscription?.close();groundBattleSubscription?.close();occupationSubscription?.close();fortressSinkingSubscription?.close();characterRefreshSubscription?.close()
        tacticalOfficeRefreshSubscription?.close()
        postureSubscription?.close()
        tacticalCommandSubscription?.close()
        supportSubscription?.close()
        civicSubscription?.close();successionSubscription?.close();tidalSubscription?.close()
        budgetSubscription?.close()
        coupBaseSubscription?.close()
        baseFacilitySubscription?.close()
        coupDockSubscription?.close()
        identitySubscription?.close()
        originalBerthSubscription?.close()
        fameSubscription?.close()
        try { channels.forEach { it.close().syncUninterruptibly() }; clients.close().awaitUninterruptibly() }
        finally {
            try {communication?.close()} finally {
                try { workers.shutdownGracefully().syncUninterruptibly() } finally { boss.shutdownGracefully().syncUninterruptibly() }
            }
        }
    }
}
