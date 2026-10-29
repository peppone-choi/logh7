package org.logh7.gateway

import org.logh7.protocol.LoginMessages
import org.logh7.protocol.LobbyMessages
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.OriginalCharacterMessages
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.Properties
import java.util.concurrent.CompletableFuture
import org.logh7.engine.Admission
import org.logh7.engine.members

/** evidence:guess — local test accounts and short-lived, single-use session tickets. */
class SessionTickets(private val clock: Clock = Clock.systemUTC()) {
    enum class Purpose { LOBBY, GAME }
    data class Ticket(val account: String, val purpose: Purpose, val expiresAt: Long)
    private val random = SecureRandom()
    private val tickets = mutableMapOf<Long, Ticket>()
    @Synchronized fun issue(account: String = "", purpose: Purpose = Purpose.LOBBY): Long {
        tickets.entries.removeIf { it.value.expiresAt <= clock.millis() }
        check(tickets.size < 4096) { "Session ticket capacity exceeded" }
        var token: Long
        do { token = random.nextInt().toLong() and 0xffffffffL } while (token == 0L || token in tickets)
        tickets[token] = Ticket(account, purpose, clock.millis() + 60_000)
        return token
    }
    @Synchronized fun take(token: Long): Ticket? = tickets.remove(token)?.takeIf { it.expiresAt > clock.millis() }
    fun consume(token: Long): Boolean = take(token) != null
}

class GameExchange(
    val role: Role,
    private val accounts: Map<String, String>,
    private val tickets: SessionTickets,
    private val sessionAddress: String,
    private val sessionPort: Int,
    private val handshake: Handshake = Handshake(),
    private val characters: CharacterCreation = CharacterCreation(),
    private val gameSeconds: () -> Long = { 0 },
    private val admission: GameAdmission? = null,
    private val connection: String = java.util.UUID.randomUUID().toString(),
    private val expectedGeneration: Long? = admission?.generation,
    private val characterProvider: () -> CharacterCreation = { characters },
    private val world: WorldBootstrap? = null,
    private val navigation: GameNavigation? = null,
    internal val presence: GamePresence? = null,
    private val onPresence: (List<ByteArray>) -> Unit = {},
    private val stock: GameStock? = null,
    private val administration: GameAdministration? = null,
) {
    enum class Role { LOGIN, SESSION }
    enum class State { KEY_EXCHANGE, AWAITING_AUTH, AUTHENTICATED, REJECTED, CLOSED }
    var state = State.KEY_EXCHANGE
        private set
    private var ticket: SessionTickets.Ticket? = null
    private var lobbyLoggedIn = false
    private var gameLoggedIn = false
    private var gameStarted = false
    private val generation = expectedGeneration
    private var joining = false
    private var joinRequested = false
    private var pendingJoin: CompletableFuture<Admission>? = null
    fun takePendingJoin(): CompletableFuture<Admission>? = pendingJoin.also { pendingJoin = null }
    private var pendingWorld: CompletableFuture<List<ByteArray>>? = null
    private data class Publication(val character: CharacterMessages.Generate, val player: org.logh7.engine.Navigator, val initialize: Boolean,val stage:Int=0,val revision:Long?=null)
    private var publication: Publication? = null
    private var deliveredPlayer: org.logh7.engine.Navigator? = null
    private var deliveredTactics: Boolean? = null
    private var worldInitialized = false
    private var tacticalInitializationPending = false
    private var pendingTacticalRevision = -1L
    private fun rememberTacticalRefresh() {
        navigation?.snapshot()?.revision?.let {pendingTacticalRevision=maxOf(pendingTacticalRevision,it)}
    }
    private var endingPrepared=false
    internal fun sessionEndingAvailable():Boolean {
        if(state!=State.AUTHENTICATED || !gameStarted || !worldInitialized || ticket?.purpose!=SessionTickets.Purpose.GAME)return false
        val snapshot=navigation?.snapshot() ?: return false
        return generation==snapshot.session?.generation && generation==admission?.generation && snapshot.result!=null
    }
    internal fun finishSession():OutboundBatch? = prepared {
        if(endingPrepared || !sessionEndingAvailable())null else {
            val snapshot=checkNotNull(navigation?.snapshot())
            val account=checkNotNull(ticket).account
            val player=checkNotNull(snapshot.navigators[account])
            val character=checkNotNull(currentCharacter(account))
            val message=SessionEndingReport.message(snapshot,character,player,characters.registeredCharacters(),offices(character,player))
            endingPrepared=true
            handshake.stageAll(listOf(message),clearHeader=0)
        }
    }
    private var tacticalResumeGrid: Int? = null
    private var recoveryCheckedAt: Long? = null
    private fun stocks(): CompletableFuture<org.logh7.engine.PlanetStocks?> =
        stock?.snapshot(checkNotNull(generation))?.thenApply { it } ?: CompletableFuture.completedFuture(null)
    private fun tactics(account: String): TacticalBootstrap {
        check(gameStarted && worldInitialized)
        val snapshot=checkNotNull(navigation?.snapshot())
        val player=checkNotNull(snapshot.navigators[account])
        require(player.cpPaused && player.generation==generation) { "Tactical data is outside the current battlefield" }
        return TacticalBootstrap(snapshot,checkNotNull(world).galaxy,player,::currentCharacterById)
    }
    private fun commandCorps(account:String):List<ByteArray> {
        val snapshot=navigation?.snapshot() ?: return emptyList()
        val player=snapshot.navigators[account] ?: return emptyList()
        if(!player.cpPaused || player.baseId!=0)return emptyList()
        return listOf(org.logh7.protocol.TacticsMessages.corps(
            TacticalBootstrap(snapshot,checkNotNull(world).galaxy,player,::currentCharacterById).corps))
    }
    private fun offices(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator) =
        administration?.offices(character.id.toInt(), character.power, player.generation).orEmpty()
    private fun flagshipCharacter(character:CharacterMessages.Generate,player:org.logh7.engine.Navigator) =
        player.hull?.let { hull -> org.logh7.engine.Flagships.find(hull.kind)?.let {
            character.copy(shipKind=it.kind,shipType=it.type) } } ?: character
    private fun currentCharacterById(id:Int):CharacterMessages.Generate? = characters.characterById(id)?.let {
        WorldCharacters.view(it,navigation?.snapshot())
    }
    private fun currentCharacter(account:String):CharacterMessages.Generate? = characters.character(account)?.let {
        WorldCharacters.view(it,navigation?.snapshot())
    }
    private fun unitMessage(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator) =
        BootstrapMessages.unit(flagshipCharacter(character,player), player.grid, player.cruise, player.baseId, player.mode,
            administration?.stationedOutfit(player) ?: 0,player.supplies,if(player.hull?.hp?.let {it<100}==true)1 else 0)
    private fun characterMessage(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator, changed: Boolean = false) =
        if (changed) BootstrapMessages.changedCharacter(character,player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices(character,player))
        else BootstrapMessages.character(character,player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices(character,player))
    /** Restore the accepted job, without submitting or charging it a second time. */
    private fun pendingNavigation(player: org.logh7.engine.Navigator): List<ByteArray> {
        player.retreat?.let { retreat ->
            return listOf(org.logh7.protocol.TacticalRetreatMessages.reply(
                org.logh7.protocol.TacticalRetreatMessages.Retreat(player.characterId,listOf(player.unitId)),
                gameSeconds(),maxOf(0,retreat.startsAt-gameSeconds())))
        }
        val target = player.dockTarget ?: return emptyList()
        val seconds = gameSeconds()
        return listOf(org.logh7.protocol.PlaceMessages.docked(org.logh7.protocol.PlaceMessages.Dock(
            player.characterId, if (player.power == org.logh7.engine.Power.EMPIRE) 59 else 195, target, 4),
            seconds, player.pcp, player.mcp, waitSeconds = maxOf(0, checkNotNull(player.dockReadyAt) - seconds)))
    }
    private fun completedWork(result: org.logh7.engine.NavigationResult, character: CharacterMessages.Generate): List<ByteArray> {
        if (!result.completedFuel && !result.completedDock) return emptyList()
        val player = checkNotNull(result.player ?: result.previous)
        val seconds = gameSeconds()
        return (if (result.completedFuel) listOf(org.logh7.protocol.FuelMessages.supplied(seconds,
            player.characterId, player.unitId, player.cruise)) else emptyList()) +
            (if (result.completedDock) listOf(unitMessage(character, player),
                org.logh7.protocol.PlaceMessages.dockNotification(player.characterId, player.baseId, player.mode, seconds,
                    player.spotId, if (player.spotId == 0) player.characterId else player.spotOwner)) else emptyList())
    }
    internal fun refreshWorld(): CompletableFuture<List<ByteArray>>? {
        if (!worldInitialized || state != State.AUTHENTICATED || obsolete() || sessionEndingAvailable() || navigation == null) return null
        val now = gameSeconds()
        if (recoveryCheckedAt?.let { now >= it && now - it < 24 } == true) return null
        recoveryCheckedAt = now
        val identity = checkNotNull(ticket)
        val character = checkNotNull(currentCharacter(identity.account))
        return recover(character, identity.account)
    }
    private fun recover(character: CharacterMessages.Generate, account: String, clockRequest: ByteArray? = null): CompletableFuture<List<ByteArray>> =
        checkNotNull(navigation).apply(org.logh7.engine.NavigationRequest(account,
            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
            connection, checkNotNull(generation), shipKind=character.shipKind,commandAbility=character.abilities[4], politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
            val player = checkNotNull(result.player) { "CP refresh lost its authenticated session" }
            val previous = deliveredPlayer ?: result.previous
            if (player != previous) publication = Publication(character, player, false)
            val changed = previous?.let { it.pcp != player.pcp || it.mcp != player.mcp } == true
            val relocated = previous?.let { it.grid != player.grid || it.baseId != player.baseId ||
                it.mode != player.mode || it.spotId != player.spotId || it.spotOwner != player.spotOwner } == true
            clockRequest?.let { listOf(checkNotNull(BootstrapMessages.response(it, gameSeconds()))) }.orEmpty() +
                (if (changed || relocated) listOf(characterMessage(character, player, true)) else emptyList()) +
                (if (relocated) (if(previous.cpPaused && previous.grid != player.grid)
                    listOf(org.logh7.protocol.TacticalRetreatMessages.warped(gameSeconds(),player.grid,listOf(player.unitId))) else emptyList()) +
                    listOf(unitMessage(character, player)) +
                    (if (previous.grid != player.grid) listOf(org.logh7.protocol.NavigationMessages.grid(player.grid,player.cpPaused),
                        org.logh7.protocol.NavigationMessages.moved(org.logh7.protocol.NavigationMessages.Warp(player.characterId,
                            if(character.power==2)59 else 195,player.grid,0,5),gameSeconds(),player.unitId,player.cruise)) else emptyList()) +
                    (if (previous.baseId != player.baseId || previous.mode != player.mode) listOf(
                        org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,player.baseId,player.mode,
                            gameSeconds(),player.spotId,if(player.spotId==0)player.characterId else player.spotOwner)) else emptyList())
                else completedWork(result, character)) +
                (if(worldInitialized && player.cpPaused && deliveredTactics==true && previous?.grid==player.grid)
                    listOf(org.logh7.protocol.TacticsMessages.corps(tactics(account).corps.filter {it.character==player.characterId}))
                    else emptyList()) +
                (world?.let { map -> navigation.snapshot()?.let { GridForceReport.message(it, map.galaxy, player, gameSeconds()) } }
                    ?.let(::listOf).orEmpty())
        }
    fun takePendingWorld() = pendingWorld.also { pendingWorld = null }
    internal fun finishWorld(messages: List<ByteArray>): OutboundBatch? = prepared {
        if (state == State.CLOSED) null else {
            val update = publication.also { publication = null }
            val repeatTactical = update?.initialize==true && update.stage==0x0f02 && update.player.cpPaused &&
                update.revision?.let {pendingTacticalRevision>it}==true
            val tactical = update?.let {
                val changed = deliveredTactics != null &&
                    (deliveredTactics != it.player.cpPaused && deliveredPlayer?.grid == it.player.grid ||
                        deliveredTactics == true && deliveredPlayer?.grid != it.player.grid)
                deliveredTactics = it.player.cpPaused
                if(changed) listOf(org.logh7.protocol.TacticsMessages.notify(it.player.grid,it.player.cpPaused)) else emptyList()
            }.orEmpty()
            if (update != null) deliveredPlayer = update.player
            if (update?.initialize == true) {
                worldInitialized = true
                tacticalInitializationPending = update.player.cpPaused && (update.stage != 0x0f02 || repeatTactical)
                if(!tacticalInitializationPending)pendingTacticalRevision=-1
            }
            val shared = update?.let { presence?.update(GamePresence.Member(connection, it.character, it.player, onPresence, offices(it.character,it.player), administration?.stationedOutfit(it.player) ?: 0), gameSeconds()) }
            val output = if (update?.initialize == true && shared != null) {
                val index = messages.indexOfFirst { java.nio.ByteBuffer.wrap(it).getShort(4).toInt() == 0x0325 }
                check(index >= 0)
                messages.take(index) + shared.peers.flatMap { listOf(presence!!.character(it),presence.unit(it)) } + messages.drop(index)
            } else messages + tacticalPresence(shared?.refresh.orEmpty())
            val retry=if(repeatTactical)listOf(org.logh7.protocol.TacticsMessages.notify(checkNotNull(update).player.grid,true)) else emptyList()
            if (output.isEmpty() && tactical.isEmpty() && retry.isEmpty()) null else {
                presence?.observeMotions(connection,output+tactical+retry)
                handshake.stageAll(output + tactical + retry, clearHeader = 0)
            }
        }
    }
    internal fun notifyWorld(messages: List<ByteArray>): OutboundBatch? = prepared {
        if (state == State.CLOSED || !gameStarted || obsolete() || sessionEndingAvailable()) null else {
            val current=tacticalPresence(messages)
            fun entering(message:ByteArray)=message.size>=7 &&
                java.nio.ByteBuffer.wrap(message).getShort(4).toInt() and 65535==0x0f1f && message[6].toInt()==1
            val output=if(current.any(::entering)) {
                rememberTacticalRefresh()
                if(tacticalInitializationPending)current.filterNot(::entering)
                else current.also {tacticalInitializationPending=true}
            } else current
            if(output.isEmpty())null else {
                presence?.observeMotions(connection,output)
                handshake.stageAll(output, clearHeader = 0)
            }
        }
    }
    private fun tacticalPresence(messages: List<ByteArray>): List<ByteArray> {
        if (messages.isEmpty() || !worldInitialized) return messages
        val account = ticket?.account ?: return messages
        val snapshot = navigation?.snapshot() ?: return messages
        val player = snapshot.navigators[account] ?: return messages
        if (!player.cpPaused || world == null) return messages
        fun opcode(message: ByteArray) = java.nio.ByteBuffer.wrap(message).getShort(4).toInt() and 65535
        // 0b09 clears the native character cache; 0325 replaces the entire unit list.
        // A strategy presence transaction containing one peer would discard the battlefield
        // definitions while leaving 033b's unit states live (004c32a0 then imports stale data).
        if (messages.any { opcode(it) == 0x0b09 }) {
            // A second transition while 0f00 is still loading can rebuild with a null
            // own-character pointer (004b5bb0). The forthcoming 0f02 snapshot includes peers.
            if (tacticalInitializationPending) {rememberTacticalRefresh();return emptyList()}
            // Native 0b0a also rebuilds live models (004c32a0(1)); use the native
            // tactical transition instead, then answer its 0f02 initialization request.
            return listOf(org.logh7.protocol.TacticsMessages.notify(player.grid, true))
        }
        if (messages.none { opcode(it) == 0x0325 }) return messages
        val tactical = TacticalBootstrap(snapshot, world.galaxy, player, ::currentCharacterById)
        return messages.filterNot { opcode(it) == 0x0325 } +
            org.logh7.protocol.TacticsMessages.definitions(player.grid, tactical.units,tactical.groundDefinitions)
    }
    fun obsolete() = ticket?.purpose == SessionTickets.Purpose.GAME && admission != null &&
        (generation != admission.generation || !admission.running && !sessionEndingAvailable())
    // ByteArray convenience is retained for codec callers. Gateway always writes the bound outbound batch.
    fun finishJoin(result: Admission): ByteArray? = finishJoinOutbound(result)?.bytes
    fun accept(type: Int, data: ByteArray): ByteArray? = acceptOutbound(type, data)?.bytes
    internal fun submitting(batch: OutboundBatch) = handshake.submitting(batch)
    internal fun committed(batch: OutboundBatch) = handshake.committed(batch)
    private inline fun prepared(block: () -> OutboundBatch?): OutboundBatch? = try { block() } catch (failure: Exception) {
        close(); throw failure
    }
    internal fun finishJoinOutbound(result: Admission): OutboundBatch? = prepared { finishJoinCore(result) }
    internal fun acceptOutbound(type: Int, data: ByteArray): OutboundBatch? = prepared { acceptCore(type, data) }
    private fun finishJoinCore(result: Admission): OutboundBatch? {
        if (state == State.CLOSED) return null
        check(joining && !gameStarted)
        joining = false
        gameStarted = result == Admission.ACCEPTED
        if (!gameStarted) state = State.REJECTED
        return handshake.stageAll(listOf(LoginMessages.sessionMessage(0x0206, byteArrayOf(if (gameStarted) 0 else 1))), clearHeader = 0)
    }
    private fun acceptCore(type: Int, data: ByteArray): OutboundBatch? {
        check(state != State.CLOSED && state != State.REJECTED)
        check(!obsolete()) { "Session ended or changed" }
        val characters = if (ticket?.purpose == SessionTickets.Purpose.GAME) characters else characterProvider()
        if (state == State.KEY_EXCHANGE) {
            val response = handshake.accept(type, data)
            if (handshake.state == Handshake.State.ESTABLISHED) state = State.AWAITING_AUTH
            return response?.let(handshake::plain)
        }
        if (state == State.AUTHENTICATED && role == Role.SESSION) {
            val body = handshake.receive(type, data) ?: return null
            val opcode = java.nio.ByteBuffer.wrap(body).short.toInt() and 65535
            val identity = checkNotNull(ticket)
            if(sessionEndingAvailable()) {
                // Consume and validate encrypted in-flight requests without reopening commands.
                // Clock polling remains safe while the player reads the final result.
                return if(opcode==0x0300 && body.size==2)handshake.stageAll(listOf(checkNotNull(
                    BootstrapMessages.response(body,checkNotNull(navigation?.snapshot()?.result).end.gameSeconds))),clearHeader=0) else null
            }
            val response = if (identity.purpose == SessionTickets.Purpose.GAME) {
                when (opcode) {
                    0x040f,0x0410 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalGroundMessages.command(body)
                        require(command.character==character.id.toInt())
                        if(command.units.any {it !in 1..999999})return handshake.stageAll(listOf(
                            org.logh7.protocol.TacticalGroundMessages.reply(command,gameSeconds(),0,false)),clearHeader=0)
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalGround=org.logh7.engine.TacticalGroundCommand(
                                if(opcode==0x040f)org.logh7.engine.TacticalGroundAction.DEPLOY else org.logh7.engine.TacticalGroundAction.WITHDRAW,
                                command.units,command.skill))).thenApply {result->
                            val now=gameSeconds()
                            listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,now,
                                result.groundReadyAt?.let {(it-now).coerceAtLeast(0)} ?: 0,result.player!=null))+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0412 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalGroundMessages.command(body)
                        require(command.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalPosture=org.logh7.engine.TacticalPostureChange(command.units,org.logh7.engine.TacticalShipMode.ANCHORED,0,sortie=true))).thenApply {result->
                            val now=gameSeconds()
                            listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,now,
                                result.postureReadyAt?.let {(it-now).coerceAtLeast(0)} ?: 0,result.player!=null))+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0411 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalGroundMessages.posture(body)
                        require(command.character==character.id.toInt())
                        val mode=org.logh7.engine.TacticalShipMode.entries.singleOrNull {it.wire==command.kind}
                        if(mode==null || (command.base>0)!=mode.requiresBase || command.base>65535)
                            return handshake.stageAll(listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,gameSeconds(),0,false)),clearHeader=0)
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalPosture=org.logh7.engine.TacticalPostureChange(command.units,mode,command.base))).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            val now=gameSeconds()
                            listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,now,
                                result.postureReadyAt?.let {(it-now).coerceAtLeast(0)} ?: 0,result.player!=null))+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0413,0x0414 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val support=org.logh7.protocol.TacticalSupportMessages.command(body)
                        require(support.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalSupport=org.logh7.engine.TacticalSupport(
                                if(support.action==org.logh7.protocol.TacticalSupportMessages.Action.REPAIR)
                                    org.logh7.engine.TacticalSupportAction.REPAIR else org.logh7.engine.TacticalSupportAction.SUPPLY,
                                support.unit,support.target))).thenApply {result ->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            val now=gameSeconds()
                            listOf(org.logh7.protocol.TacticalSupportMessages.reply(support,now,
                                result.supportReadyAt?.let {(it-now).coerceAtLeast(0)} ?: 0,result.player!=null)) +
                                commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0405 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val attack=org.logh7.protocol.TacticalAttackMessages.attack(body)
                        require(attack.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,commandAbility=character.abilities[4],tacticalAttack=org.logh7.engine.TacticalAttack(
                                attack.units,attack.mode,attack.target))).thenApply {result ->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            val order=player.attack ?: navigation.snapshot()?.planetStocks?.outfits?.values?.asSequence()
                                ?.flatMap {it.activeShips?.values.orEmpty().asSequence()}?.firstOrNull {it.id in attack.units}?.attack?.order
                            val wait=if(result.player!=null && attack.mode!=0) checkNotNull(order).let {it.startsAt-it.acceptedAt} else 0L
                            listOf(org.logh7.protocol.TacticalAttackMessages.reply(attack,gameSeconds(),wait,result.player!=null)) + commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0406 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val shoot = org.logh7.protocol.TacticalAttackMessages.shoot(body)
                        require(shoot.character == character.id.toInt())
                        val antiAir=shoot.antiAir
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,rank=character.rank,commandAbility=character.abilities[4],
                            tacticalShoot=if(antiAir)null else org.logh7.engine.TacticalShoot(shoot.units,shoot.arms,shoot.targetKind,shoot.target),
                            tacticalCommand=if(antiAir)org.logh7.engine.TacticalCommand(0x406,shoot.units,kind=3,
                                targetKind=shoot.targetKind,target=shoot.target,payload=body.drop(2).map {it.toInt() and 255}) else null)).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous)
                            publication = Publication(character,player,false)
                            val shot=navigation.snapshot()?.planetStocks?.outfits?.values?.asSequence()
                                ?.flatMap {it.activeShips?.values.orEmpty().asSequence()}?.firstOrNull {it.id in shoot.units}?.shot
                            val wait = if(result.player == null) 0 else if(antiAir)
                                (checkNotNull(result.commandReadyAt)-gameSeconds()).coerceAtLeast(0)
                                else if(player.unitId in shoot.units)
                                checkNotNull(player.fire?.pending).let {it.firesAt-it.acceptedAt}
                                else checkNotNull(shot).let {it.firesAt-it.acceptedAt}
                            listOf(org.logh7.protocol.TacticalAttackMessages.reply(shoot,gameSeconds(),wait,result.player != null)) + commandCorps(identity.account)
                        }
                        return null
                    }
                    in org.logh7.protocol.TacticalCommandMessages.opcodes -> {
                        check(gameStarted && worldInitialized && navigation!=null && world!=null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalCommandMessages.command(body)
                        require(command.character==character.id.toInt() && command.points.all {it.z==0f})
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,character.id.toInt(),
                            if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,rank=character.rank,commandAbility=character.abilities[4],
                            tacticalRanks=navigation.snapshot()?.navigators?.values.orEmpty().filter {it.grid==navigation.snapshot()?.navigators?.get(identity.account)?.grid}
                                .mapNotNull {p->characters.characterById(p.characterId)?.let {p.characterId to it.rank}}.toMap(),
                            tacticalCommand=org.logh7.engine.TacticalCommand(command.opcode,command.units,command.base,command.kind,
                                command.targetKind,command.target,command.velocity,command.direction,
                                command.points.map {org.logh7.engine.TacticalPoint(it.x,it.y)},command.payload))).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            listOf(org.logh7.protocol.TacticalCommandMessages.reply(command,gameSeconds(),
                                (result.commandReadyAt?.minus(gameSeconds()) ?: 0).coerceAtLeast(0),result.player!=null))+
                                characterMessage(character,player,true)+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0401,0x0403 -> {
                        check(gameStarted && worldInitialized && navigation!=null && world!=null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val turn=org.logh7.protocol.TacticalTurnMessages.command(body)
                        require(turn.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,commandAbility=character.abilities[4],tacticalMove=org.logh7.engine.TacticalMove(
                                turn.units.map {it.id},0f,turn.direction ?: 0f,listOf(org.logh7.engine.TacticalPoint(0f,0f)),
                                maneuver=if(turn.reverse)org.logh7.engine.TacticalManeuver.REVERSE else org.logh7.engine.TacticalManeuver.TURN,
                                reverseSide=turn.reverseByte))).thenApply {result ->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            (if(result.player==null)listOf(org.logh7.protocol.TacticalTurnMessages.reply(turn,gameSeconds(),0,false))
                                else TacticalMotionMessages.formation(result.tacticalMotions,gameSeconds(),player.characterId))+
                                commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0400,0x0402 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val move=org.logh7.protocol.TacticalMoveMessages.move(body)
                        require(move.character==character.id.toInt() && move.waypoints.all {it.z==0f})
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,commandAbility=character.abilities[4],tacticalMove=org.logh7.engine.TacticalMove(move.units.map {it.id},
                                move.velocity,move.direction,move.waypoints.map {org.logh7.engine.TacticalPoint(it.x,it.y)},move.parallel))).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            val replies=if(result.player!=null) {
                                if(result.tacticalMotions.isNotEmpty())result.tacticalMotions.map {(id,motion)->
                                    TacticalMotionMessages.npc(id,motion,gameSeconds(),player.characterId)}
                                else listOf(checkNotNull(TacticalMotionMessages.reply(player,gameSeconds())))
                            } else {
                                val unit=TacticalBootstrap(checkNotNull(navigation.snapshot()),world.galaxy,player,::currentCharacterById)
                                    .units.single {it.id==player.unitId}
                                val pose=org.logh7.protocol.TacticalMoveMessages.Point(unit.x,unit.y,0f)
                                listOf(org.logh7.protocol.TacticalMoveMessages.reply(move.copy(units=listOf(
                                    org.logh7.protocol.TacticalMoveMessages.Unit(unit.id,unit.direction,pose)),velocity=0f,
                                    direction=unit.direction,waypoints=listOf(pose)),gameSeconds(),0,false))
                            }
                            replies+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0404 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val retreat=org.logh7.protocol.TacticalRetreatMessages.retreat(body)
                        require(retreat.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,commandAbility=character.abilities[4],tacticalRetreat=retreat.units)).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            listOf(org.logh7.protocol.TacticalRetreatMessages.reply(retreat,gameSeconds(),
                                if(result.player!=null)maxOf(0,(result.commandReadyAt ?: checkNotNull(player.retreat).startsAt)-gameSeconds()) else 0,
                                result.player!=null))
                        }
                        return null
                    }
                    0x040a -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val stop=org.logh7.protocol.TacticalStopMessages.stop(body)
                        require(stop.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,commandAbility=character.abilities[4],tacticalStop=org.logh7.engine.TacticalStop(stop.units,stop.bases))).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            listOf(org.logh7.protocol.TacticalStopMessages.reply(stop,gameSeconds(),result.player!=null)) +
                                (if(result.player!=null) {
                                    if(result.tacticalMotions.isNotEmpty())result.tacticalMotions.map {(id,motion)->
                                        TacticalMotionMessages.npc(id,motion,gameSeconds(),player.characterId)}
                                    else TacticalMotionMessages.reply(player,gameSeconds())?.let(::listOf).orEmpty()
                                } else emptyList()) +
                                commandCorps(identity.account)
                        }
                        return null
                    }
                    0x040c -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val control=org.logh7.protocol.TacticsMessages.control(body)
                        require(control.character==character.id.toInt() && control.unit==character.id.toInt()+1_000_000)
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),unitId=control.unit,
                            captainCard=if(character.power==2)59 else 195,shipKind=character.shipKind,commandAbility=character.abilities[4],
                            energy=org.logh7.engine.TacticalEnergy(control.energy))).thenApply { result ->
                            val player=checkNotNull(result.player ?: result.previous)
                            publication=Publication(character,player,false)
                            val energy=(player.energy ?: org.logh7.engine.TacticalEnergy.ZERO).values
                            val ship=org.logh7.engine.Flagships.find(character.shipKind)!!
                            listOf(org.logh7.protocol.TacticsMessages.controlReply(control.copy(energy=energy),
                                ship.number("出力:出力")-energy.sum(),gameSeconds(),result.player!=null)) +
                                (if(player.motion!=result.previous?.motion)TacticalMotionMessages.reply(player,gameSeconds())?.let(::listOf).orEmpty()
                                    else emptyList())
                        }
                        return null
                    }
                    0x0300 -> {
                        check(gameStarted && body.size == 2)
                        if (navigation == null) checkNotNull(BootstrapMessages.response(body, gameSeconds())) else {
                            val character = checkNotNull(currentCharacter(identity.account))
                            // A clock reset must sample the actor now, not its last periodic tick.
                            pendingWorld = navigation.synchronizeTime().thenCompose {recover(character,identity.account,body)}
                            return null
                        }
                    }
                    0x0f18 -> {
                        check(gameStarted && worldInitialized && navigation!=null && world!=null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val settings=org.logh7.protocol.OfflineDirectionMessages.settings(body)
                        require(settings.character==character.id.toInt())
                        pendingWorld=navigation.apply(org.logh7.engine.NavigationRequest(identity.account,settings.character,
                            if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),offlineDirection=settings.mask)).thenApply {result ->
                            val player=checkNotNull(result.player ?: result.previous)
                            val updated=checkNotNull(currentCharacter(identity.account))
                            publication=Publication(updated,player,false)
                            val actual=org.logh7.protocol.OfflineDirectionMessages.Settings(player.characterId,updated.ai.drop(1))
                            listOf(org.logh7.protocol.OfflineDirectionMessages.reply(actual,gameSeconds(),result.player!=null),
                                characterMessage(updated,player,true))+completedWork(result,updated)
                        }
                        return null
                    }
                    0x0f1a -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val selection = org.logh7.protocol.ReturnBaseMessages.selection(body)
                        require(selection.character == character.id.toInt())
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), politics = character.abilities[1], administration = character.abilities[2],
                            returnBase = selection.base)).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous) { "Return base selection lost its authenticated session" }
                            publication = Publication(character, player, false)
                            listOf(org.logh7.protocol.ReturnBaseMessages.reply(player.characterId, player.returnBase, gameSeconds(), result.player != null),
                                characterMessage(character, player, true)) +
                                completedWork(result, character)
                        }
                        return null
                    }
                    0x0b06 -> {
                        check(gameStarted && navigation != null && world != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val command = org.logh7.protocol.PlaceMessages.switchMode(body)
                        val id = character.id.toInt()
                        require(command.characterId == id && command.units.all { it == id + 1_000_000 } &&
                            command.characters.all { it == id } && command.units.distinct().size == command.units.size &&
                            command.characters.distinct().size == command.characters.size)
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account, id,
                            if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = id + 1_000_000, captainCard = command.card,
                            place = org.logh7.engine.PlaceMove.SwitchMode(command.mode), rank = character.rank,
                            politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous) { "Departure lost its authenticated session" }
                            val accepted = result.player != null
                            publication = Publication(character, player, false)
                            val seconds = gameSeconds()
                            listOf(org.logh7.protocol.PlaceMessages.switched(command, seconds, player.pcp, player.mcp,
                                player.mode, player.spotId, if (player.spotId == 0) id else player.spotOwner, accepted),
                                characterMessage(character, player, true),
                                unitMessage(character, player)) +
                                (if (accepted && !result.completedDock) listOf(org.logh7.protocol.PlaceMessages.dockNotification(id,
                                    player.baseId, player.mode, seconds)) else emptyList()) + completedWork(result, character)
                        }
                        return null
                    }
                    0x0b00, 0x0e00 -> {
                        check(gameStarted && navigation != null && world != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val dock = if (opcode == 0x0b00) org.logh7.protocol.PlaceMessages.dock(body) else null
                        val move = if (opcode == 0x0e00) org.logh7.protocol.PlaceMessages.move(body) else null
                        require((dock?.characterId ?: move!!.characterId) == character.id.toInt())
                        val place = dock?.let { org.logh7.engine.PlaceMove.Dock(it.base, it.mode) }
                            ?: org.logh7.engine.PlaceMove.Spot(move!!.spot, move.owner)
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = character.id.toInt() + 1_000_000,
                            captainCard = dock?.card, place = place, rank = character.rank,
                            politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous) { "Place request lost its authenticated session" }
                            val accepted = result.player != null
                            publication = Publication(character, player, false)
                            val seconds = gameSeconds()
                            // The CD's 004beaa0 applies ACK coordinates even on a rejection.
                            // Echo the authoritative origin so denial cannot move its background.
                            val ack = if (dock != null) org.logh7.protocol.PlaceMessages.docked(
                                dock.copy(base = if (accepted) player.dockTarget ?: player.baseId else player.baseId,
                                    mode = if (accepted && player.dockTarget != null) 4 else player.mode),
                                seconds, player.pcp, player.mcp, accepted,
                                if (accepted) player.dockReadyAt?.let { maxOf(0, it - seconds) } ?: 0 else 0)
                            else org.logh7.protocol.PlaceMessages.moved(
                                move!!.copy(spot = player.spotId, owner = if (player.spotId == 0) character.id.toInt() else player.spotOwner),
                                seconds, player.pcp, player.mcp, accepted)
                            listOf(ack,
                                characterMessage(character, player, true),
                                unitMessage(character, player)) +
                                (if (dock != null && accepted && player.dockTarget == null && !result.completedDock) listOf(org.logh7.protocol.PlaceMessages.dockNotification(character.id.toInt(),
                                    player.baseId, player.mode, seconds)) else emptyList()) + completedWork(result, character)
                        }
                        return null
                    }
                    0x0709 -> {
                        check(gameStarted && administration!=null && world!=null)
                        val query=org.logh7.protocol.ResignationMessages.query(body)
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(query.character==character.id.toInt())
                        pendingWorld=administration.resignOffice(org.logh7.engine.OfficeResignationRequest(
                            identity.account,connection,query.character,
                            if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.card,character.abilities[1],character.abilities[2])).thenApply { result ->
                            val player=checkNotNull(result.player) { "Resignation lost its authenticated actor" }
                            publication=Publication(character,player,false)
                            listOf(org.logh7.protocol.ResignationMessages.response(query,gameSeconds(),player.pcp,player.mcp,result.rejection==null),
                                characterMessage(character,player,true),unitMessage(character,player))+
                                result.recovery?.let {completedWork(it.copy(player=player),character)}.orEmpty()
                        }
                        return null
                    }
                    0x0707 -> {
                        check(gameStarted && administration!=null && world!=null)
                        val received=org.logh7.protocol.AppointmentMessages.query(body)
                        // Native capital institution 17 skips the base picker and sends zero.
                        val query=if(received.base==0 && received.outfit==0) received.copy(base=checkNotNull(
                            org.logh7.engine.CapitalAppointmentPolicy.rule(received.card)?.let {
                                org.logh7.engine.CapitalAppointmentPolicy(world.galaxy).base(it)?.id
                            } ?: org.logh7.engine.NationalOffice.of(received.card)?.let {role ->
                                world.galaxy.bases.singleOrNull {org.logh7.engine.Capital.of(it)==role.capital}?.id
                            } ?: org.logh7.engine.OrganizationOffices.of(received.card)?.takeIf {
                                it.scope in setOf(org.logh7.engine.OfficeScope.NATIONAL,org.logh7.engine.OfficeScope.CAPITAL)
                            }?.let {role ->world.galaxy.bases.singleOrNull {org.logh7.engine.Capital.of(it)?.power==role.power}?.id
                            }) { "Capital appointment has no matching world base" }) else received
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(query.character==character.id.toInt())
                        val target=currentCharacterById(query.target)
                        pendingWorld=administration.appointGarrison(org.logh7.engine.GarrisonAppointmentRequest(
                            identity.account,connection,character.id.toInt(),
                            if (character.power==2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.base,query.target,query.card,character.rank,target?.rank ?: -1,
                            target?.power ?: 0,character.abilities[1],character.abilities[2],query.outfit)).thenApply { result ->
                            val player=checkNotNull(result.player) { "Appointment lost its authenticated actor" }
                            publication=Publication(character,player,false)
                            if (result.rejection==null && target!=null) result.targetPlayer?.let { targetPlayer ->
                                presence?.refreshOffices(target.id.toInt(),targetPlayer.generation,offices(target,targetPlayer),gameSeconds(),
                                    administration.stationedOutfit(targetPlayer))
                            }
                            val resolved=if(query.outfit>0 && result.rejection==null) query.copy(
                                base=checkNotNull(result.stocks).outfits.getValue(query.outfit).baseId) else query
                            listOf(org.logh7.protocol.AppointmentMessages.response(resolved,gameSeconds(),player.pcp,player.mcp,
                                result.rejection==null,result.changed),characterMessage(character,player,true)) +
                                result.recovery?.let { completedWork(it.copy(player=player),character) }.orEmpty()
                        }
                        return null
                    }
                    0x0708 -> {
                        check(gameStarted && administration!=null && world!=null)
                        val query=org.logh7.protocol.DismissalMessages.query(body)
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(query.character==character.id.toInt())
                        val target=currentCharacterById(query.target)
                        pendingWorld=administration.dismissOffice(org.logh7.engine.OfficeDismissalRequest(
                            identity.account,connection,query.character,
                            if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.target,query.card,character.rank,target?.power ?: 0,
                            character.abilities[1],character.abilities[2])).thenApply { result ->
                            val player=checkNotNull(result.player) { "Dismissal lost its authenticated actor" }
                            publication=Publication(character,player,false)
                            if(result.changed && target!=null) result.targetPlayer?.let { targetPlayer ->
                                presence?.refreshOffices(target.id.toInt(),targetPlayer.generation,offices(target,targetPlayer),gameSeconds(),
                                    administration.stationedOutfit(targetPlayer))
                            }
                            listOf(org.logh7.protocol.DismissalMessages.response(query,gameSeconds(),player.pcp,player.mcp,result.changed),
                                characterMessage(character,player,true),unitMessage(character,player))+
                                result.recovery?.let {completedWork(it.copy(player=player),character)}.orEmpty()
                        }
                        return null
                    }
                    0x0a00 -> {
                        check(gameStarted && administration != null && world != null)
                        val query = org.logh7.protocol.TaxMessages.query(body)
                        val character = checkNotNull(currentCharacter(identity.account))
                        require(query.character == character.id.toInt())
                        pendingWorld = administration.tax(org.logh7.engine.TaxRequest(identity.account, connection, query.character,
                            if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation), query.base, query.rate, query.card, query.mode,
                            character.abilities[1], character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player) { "Tax command lost its authenticated character" }
                            publication = Publication(character, player, false)
                            listOf(org.logh7.protocol.TaxMessages.response(query, gameSeconds(), player.pcp, player.mcp, result.rejection == null),
                                characterMessage(character, player, true),
                                world.conditions(listOf(query.base), result.stocks)) +
                                result.recovery?.let { completedWork(it.copy(player = player), character) }.orEmpty()
                        }
                        return null
                    }
                    0x0c0b -> {
                        check(gameStarted && administration != null && world != null)
                        val query = org.logh7.protocol.LogisticsMessages.assignment(body)
                        val character = checkNotNull(currentCharacter(identity.account))
                        require(query.header.characterId == character.id.toInt())
                        pendingWorld = administration.assignStock(org.logh7.engine.StockAssignmentRequest(
                            identity.account,connection,character.id.toInt(),
                            if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.base,query.outfit,query.kind,
                            query.moves.ships.map { org.logh7.engine.ReorganizationShip(it.kind,it.units,it.boats) },
                            query.moves.troops.map { org.logh7.engine.ReorganizationTroop(it.kind,it.grade,it.units) },
                            query.moveSupplies,character.abilities[1],character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player) { "Assignment lost its authenticated character" }
                            publication = Publication(character,player,false)
                            val accepted = result.rejection == null
                            val reply = if (accepted) {
                                val stocks = checkNotNull(result.stocks)
                                val base = stocks.bases.getValue(query.base)
                                val warehouse = stocks.warehouses.getValue(stocks.outfits.getValue(query.outfit).warehouseId)
                                org.logh7.protocol.LogisticsMessages.assignmentResponse(query,gameSeconds(),player.pcp,player.mcp,true,
                                    world.assignmentInventory(base.units,base.spareShips),base.supplies,
                                    world.assignmentInventory(warehouse.units,warehouse.spareShips),warehouse.supplies)
                            } else org.logh7.protocol.LogisticsMessages.assignmentResponse(query,gameSeconds(),player.pcp,player.mcp,false)
                            listOf(reply,characterMessage(character,player,true)) +
                                (if (accepted) listOf(world.warehouse(query.base,result.stocks),
                                    world.outfitWarehouse(query.base,query.outfit,character.id.toInt(),character.power,checkNotNull(result.stocks)))
                                else emptyList()) + result.recovery?.let { completedWork(it.copy(player=player),character) }.orEmpty()
                        }
                        return null
                    }
                    0x0c01 -> {
                        check(gameStarted && world != null && administration != null)
                        val query = org.logh7.protocol.CompletenessSupplyMessages.query(body)
                        val character = checkNotNull(currentCharacter(identity.account))
                        require(query.character == character.id.toInt()) { "Supply character mismatch" }
                        pendingWorld = administration.supplyOutfit(org.logh7.engine.CompletenessSupplyRequest(identity.account,
                            connection, character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation), query.from, query.to, character.abilities[1], character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player) { "Supply lost its authenticated character" }
                            publication = Publication(character, player, false)
                            val accepted = result.rejection == null
                            val stored = checkNotNull(result.stocks)
                            fun units(id: Int) = if (!accepted) emptyList() else
                                org.logh7.engine.OutfitSupplies.distribution(stored.outfits.getValue(id)).map { (unit, amount) ->
                                    org.logh7.protocol.CompletenessSupplyMessages.UnitSupply(unit, amount) }
                            listOf(org.logh7.protocol.CompletenessSupplyMessages.response(query, gameSeconds(), player.pcp, player.mcp,
                                if (accepted) stored.outfits.getValue(query.to).supplies else 0,
                                units(query.from), units(query.to), accepted), characterMessage(character, player, true)) +
                                (if (accepted) listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(query.from, 0, 0),
                                    character.power, stored, characterById = ::currentCharacterById),
                                    world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(query.to, 0, 0),
                                        character.power, stored, characterById = ::currentCharacterById)) else emptyList()) +
                                result.recovery?.let { completedWork(it.copy(player = player), character) }.orEmpty()
                        }
                        return null
                    }
                    0x0c05 -> {
                        check(gameStarted && world!=null && administration!=null)
                        val query=org.logh7.protocol.SupplementMessages.query(body)
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(query.header.characterId==character.id.toInt())
                        require(query.units.map {it.kind}.distinct().size==query.units.size) { "Duplicate supplement kind" }
                        pendingWorld=administration.supplementOutfit(org.logh7.engine.SupplementRequest(identity.account,connection,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.base,query.outfit,query.kind,query.mode,
                            query.units.associate {it.kind to it.ids},query.supplies,character.abilities[1],character.abilities[2])).thenApply {result ->
                            val player=checkNotNull(result.player);val accepted=result.rejection==null
                            publication=Publication(character,player,false)
                            val stored=checkNotNull(result.stocks);val outfit=stored.outfits[query.outfit]
                            val inventory=if(accepted) {
                                val value=checkNotNull(outfit)
                                val ships=checkNotNull(value.activeShips).values.groupBy {it.stack.kind}.toSortedMap().map {(kind,units) ->
                                    org.logh7.protocol.LogisticsMessages.ShipStock(kind,units.size,units.sumOf {it.boats})
                                }
                                org.logh7.protocol.LogisticsMessages.Inventory(ships,
                                    value.units.troops.entries.sortedWith(compareBy({it.key.kind},{it.key.level})).map {
                                        org.logh7.protocol.LogisticsMessages.TroopStock(1000+it.key.kind,it.key.level,it.value) })
                            } else org.logh7.protocol.LogisticsMessages.Inventory(emptyList(),emptyList())
                            listOf(org.logh7.protocol.SupplementMessages.response(query,gameSeconds(),player.pcp,player.mcp,accepted,
                                result.troops.map {org.logh7.protocol.LogisticsMessages.TroopMove(1000+it.key.kind,it.key.level,it.value)},
                                result.supplies,inventory,outfit?.supplies ?: 0,result.maxCrew,
                                outfit?.let {org.logh7.engine.OutfitSupplies.capacity(it.units)} ?: 0))+
                                (if(query.mode==1)listOf(characterMessage(character,player,true)) else emptyList())+
                                (if(accepted && query.mode==1)listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(
                                    query.outfit,query.base,0),character.power,stored,characterById=::currentCharacterById),
                                    world.outfitWarehouse(query.base,query.outfit,character.id.toInt(),character.power,stored)) else emptyList())+
                                result.recovery?.let {completedWork(it.copy(player=player),character)}.orEmpty()
                        }
                        return null
                    }
                    0x0c02 -> {
                        check(gameStarted && administration != null && world != null)
                        val query = org.logh7.protocol.LogisticsMessages.reorganization(body)
                        val character = checkNotNull(currentCharacter(identity.account))
                        require(query.header.characterId == character.id.toInt())
                        // 005313f0 previews in mode 0; 00574ba0 commits in mode 1.
                        require(query.mode in 0..1) { "Unsupported strategic reorganization mode" }
                        pendingWorld = administration.reorganize(org.logh7.engine.ReorganizationRequest(
                            identity.account, connection, character.id.toInt(),
                            if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation), query.base, query.outfit, query.kind, query.mode,
                            query.moves.ships.map { org.logh7.engine.ReorganizationShip(it.kind, it.units, it.boats) },
                            query.moves.troops.map { org.logh7.engine.ReorganizationTroop(it.kind, it.grade, it.units) },
                            query.supplies, character.abilities[1], character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player) { "Reorganization lost its authenticated character" }
                            publication = Publication(character, player, false)
                            val accepted = result.rejection == null
                            val supplies = if (accepted) checkNotNull(result.stocks).outfits.getValue(query.outfit).supplies else 0
                            if (query.mode == 0) {
                                val capacity=result.capacity
                                return@thenApply listOf(org.logh7.protocol.LogisticsMessages.reorganizationPreview(query,
                                    gameSeconds(),player.pcp,player.mcp,capacity?.additionalTroops ?: 0,capacity?.crewDelta ?: 0,
                                    supplies,accepted,capacity?.ships.orEmpty().map {
                                        org.logh7.protocol.LogisticsMessages.ShipStock(it.kind,it.units,it.boats) }))
                            }
                            listOf(org.logh7.protocol.LogisticsMessages.reorganizationResponse(query, gameSeconds(),
                                player.pcp, player.mcp, supplies, accepted), characterMessage(character, player, true)) +
                                (if (accepted) listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(
                                    query.outfit, query.base, 0), character.power, checkNotNull(result.stocks), characterById=::currentCharacterById),
                                    world.outfitWarehouse(query.base, query.outfit, character.id.toInt(), character.power, checkNotNull(result.stocks)))
                                else emptyList()) + result.recovery?.let { completedWork(it.copy(player = player), character) }.orEmpty()
                        }
                        return null
                    }
                    0x032e, 0x0330 -> {
                        check(gameStarted && world != null && stock != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val party = if (opcode == 0x032e) org.logh7.protocol.OutfitPartyMessages.query(body) else null
                        val unit = if (opcode == 0x0330) org.logh7.protocol.OutfitPartyMessages.unitQuery(body) else null
                        pendingWorld = stocks().thenApply { stored ->
                            if(party?.mode==2)world.outfitGround(party,character.power,checkNotNull(stored))
                            else listOf(if(party!=null)world.outfitParty(party,character.power,checkNotNull(stored),character.id.toInt(),::currentCharacterById)
                                else world.outfitUnit(checkNotNull(unit).id,character.power,checkNotNull(stored),unit.base))
                        }
                        return null
                    }
                    0x032a, 0x032c -> {
                        check(gameStarted && world != null && stock != null)
                        val ids = if (opcode == 0x032a) org.logh7.protocol.OutfitMessages.query(body) else null
                        val query = if (opcode == 0x032c) org.logh7.protocol.OutfitMessages.gridQuery(body) else null
                        pendingWorld = stocks().thenApply { stored -> listOf(if (ids != null) world.outfits(ids,checkNotNull(stored))
                            else world.gridOutfits(checkNotNull(query),checkNotNull(stored))) }
                        return null
                    }
                    0x0322 -> {
                        check(gameStarted && worldInitialized && administration != null)
                        require(body.size == 6)
                        val id = java.nio.ByteBuffer.wrap(body).getInt(2)
                        val actor = checkNotNull(currentCharacter(identity.account))
                        val target = checkNotNull(currentCharacterById(id))
                        require(target.power == actor.power) { "Character detail requires friendly affiliation" }
                        BootstrapMessages.character(target,0,0,
                            offices=administration.offices(id,target.power,checkNotNull(generation)))
                    }
                    0x0354 -> {
                        check(gameStarted && worldInitialized && world != null && presence != null)
                        val id = org.logh7.protocol.CharacterInformationMessages.query(body)
                        // Read storage first, then resolve current visibility: a pending lookup must
                        // not publish the former room's opponent after either participant leaves.
                        pendingWorld = stocks().thenApply { stored ->
                            val target = presence.detail(connection, id)
                            val profile = target?.let { member ->
                                val player = member.player
                                val outfit = stored?.outfits?.get(member.outfit)?.takeIf { it.power == member.character.power }
                                val outfitBase = outfit?.let { unit -> world.galaxy.bases.singleOrNull { it.id == unit.baseId } }
                                val charged = member.offices.mapNotNull { card ->
                                    world.galaxy.bases.singleOrNull { it.id == card.target }?.let {
                                        org.logh7.protocol.CharacterInformationMessages.ChargedBase(card.kind, world.baseWire(it)) }
                                }.take(5) // Native structure has only five charged-base slots.
                                org.logh7.protocol.CharacterInformationMessages.Profile(member.character, player.grid,
                                    player.baseId, player.spotId, if (player.spotId == 0) id else player.spotOwner,
                                    listOf(0, if (member.character.power == 2) 59 else 195) + member.offices.map { it.kind },
                                    if (outfit != null && outfitBase != null) org.logh7.protocol.LogisticsDirectoryMessages.Outfit(
                                        outfit.id, outfit.kind.wire, outfit.number, world.baseWire(outfitBase)) else null, charged)
                            }
                            listOf(org.logh7.protocol.CharacterInformationMessages.response(id, profile))
                        }
                        return null
                    }
                    0x0336, 0x033a, 0x033e, 0x0344, 0x0348, 0x034a -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val current=checkNotNull(navigation.snapshot()?.navigators?.get(identity.account))
                        if(!current.cpPaused || deliveredTactics==true && deliveredPlayer?.grid!=current.grid) {
                            // Position polls already in flight when the clock finishes a retreat are valid.
                            // Answer them without leaking another battlefield, then deliver the mode transition.
                            val empty=if(opcode==0x0336) {
                                require(body.size==4)
                                val grid=java.nio.ByteBuffer.wrap(body).getShort(2).toInt() and 65535
                                require(grid in 0..4999)
                                org.logh7.protocol.TacticsMessages.characters(grid,emptyList())
                            } else {
                                if(opcode==0x0344)org.logh7.protocol.TacticsMessages.baseInformationQuery(body)
                                else org.logh7.protocol.TacticsMessages.queryIds(body,opcode,byteCount=opcode==0x034a)
                                when(opcode) {
                                    0x0344 -> org.logh7.protocol.TacticsMessages.baseInformation(emptyList())
                                    0x033e -> org.logh7.protocol.TacticsMessages.corps(emptyList())
                                    0x034a -> org.logh7.protocol.TacticsMessages.basePositions(emptyList())
                                    0x0348 -> org.logh7.protocol.TacticsMessages.positions(emptyList())
                                    else -> org.logh7.protocol.TacticsMessages.states(emptyList())
                                }
                            }
                            val character=checkNotNull(currentCharacter(identity.account))
                            pendingWorld=recover(character,identity.account).thenApply { listOf(empty)+it }
                            return null
                        }
                        if(opcode==0x0348) {
                            val ids=org.logh7.protocol.TacticsMessages.queryIds(body,opcode).toSet()
                            // 0349 has no timestamp: its coordinates must describe the committed
                            // current time, rather than the previous periodic tick.
                            pendingWorld=navigation.synchronizeTime().thenCompose {
                                val latest=checkNotNull(navigation.snapshot()?.navigators?.get(identity.account))
                                if(!latest.cpPaused || deliveredTactics==true && deliveredPlayer?.grid!=latest.grid) {
                                    val character=checkNotNull(currentCharacter(identity.account))
                                    recover(character,identity.account).thenApply {
                                        listOf(org.logh7.protocol.TacticsMessages.positions(emptyList()))+it
                                    }
                                } else {
                                    val tactical=tactics(identity.account)
                                    val units=tactical.units.filter {it.id in ids}
                                    val positions=org.logh7.protocol.TacticsMessages.positions(units)
                                    // The native bootstrap can precede live unit creation. Replay
                                    // once after the first nonempty live poll, using the same sample.
                                    val messages=if(tacticalResumeGrid==tactical.grid && units.isNotEmpty()) {
                                        tacticalResumeGrid=null
                                        listOf(positions)+tactical.resume()
                                    } else listOf(positions)
                                    CompletableFuture.completedFuture(messages)
                                }
                            }
                            return null
                        }
                        val tactical=tactics(identity.account)
                        if(opcode==0x0336) {
                            require(body.size==4 && java.nio.ByteBuffer.wrap(body).getShort(2).toInt()==tactical.grid)
                            org.logh7.protocol.TacticsMessages.characters(tactical.grid,tactical.characterIds)
                        } else {
                            val ids=(if(opcode==0x0344)org.logh7.protocol.TacticsMessages.baseInformationQuery(body)
                                else org.logh7.protocol.TacticsMessages.queryIds(body,opcode,byteCount=opcode==0x034a)).toSet()
                            when(opcode) {
                                0x0344 -> org.logh7.protocol.TacticsMessages.baseInformation(tactical.baseInformation.filter {it.position.id in ids})
                                0x033e -> org.logh7.protocol.TacticsMessages.corps(tactical.corps.filter {it.character in ids})
                                0x034a -> org.logh7.protocol.TacticsMessages.basePositions(tactical.bases.filter {it.id in ids})
                                else -> org.logh7.protocol.TacticsMessages.states(tactical.units.filter {it.id in ids})
                            }
                        }
                    }
                    0x034e -> {
                        check(gameStarted && world != null && stock != null)
                        val card = org.logh7.protocol.CardCharacterMessages.query(body)
                        pendingWorld = stocks().thenApply { stored -> listOf(world.cardCharacters(card,
                            checkNotNull(stored),::currentCharacterById)) }
                        return null
                    }
                    0x0326 -> {
                        check(gameStarted && world != null)
                        val (base, outfit) = org.logh7.protocol.WorldMessages.warehouseQuery(body)
                        if (stock == null) {
                            require(outfit == 0) { "Outfit warehouse storage is disabled" }
                            world.warehouse(base)
                        } else {
                            val character = checkNotNull(currentCharacter(identity.account))
                            pendingWorld = stocks().thenApply { listOf(if (outfit == 0) world.warehouse(base, it) else
                                world.outfitWarehouse(base,outfit,character.id.toInt(),character.power,checkNotNull(it))) }
                            return null
                        }
                    }
                    0x031e, 0x0320 -> {
                        check(gameStarted && world != null)
                        val ids = org.logh7.protocol.WorldMessages.baseQuery(body, opcode)
                        if (stock != null) {
                            pendingWorld = stocks().thenApply {
                                listOf(if (opcode == 0x031e) world.conditions(ids, it) else world.places(ids, it))
                            }
                            return null
                        }
                        if (opcode == 0x031e) world.conditions(ids) else world.places(ids)
                    }
                    0x0b04,0x0b05 -> {
                        check(gameStarted && worldInitialized && administration!=null && world!=null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.GroundTransferMessages.command(body)
                        require(command.character==character.id.toInt())
                        // Only the explicit whole-outfit path is supported. Unknown native values
                        // cannot silently become a quantity or an individual unit ID.
                        if(command.values.isNotEmpty()) {
                            val p=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                            return handshake.stageAll(listOf(org.logh7.protocol.GroundTransferMessages.reply(command,gameSeconds(),p.pcp,p.mcp,false)),clearHeader=0)
                        }
                        pendingWorld=administration.transferGround(org.logh7.engine.GroundTransferRequest(identity.account,connection,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),command.outfit,
                            if(opcode==0x0b05)org.logh7.engine.GroundTransferAction.LOAD else org.logh7.engine.GroundTransferAction.UNLOAD,
                            character.abilities[1],character.abilities[2])).thenApply {result->
                            val player=checkNotNull(result.player ?: result.recovery?.previous)
                            publication=Publication(character,player,false)
                            val accepted=result.rejection==null
                            val stored=checkNotNull(result.stocks ?: navigation?.snapshot()?.planetStocks)
                            val outfit=stored.outfits[command.outfit]
                            listOf(org.logh7.protocol.GroundTransferMessages.reply(command,gameSeconds(),player.pcp,player.mcp,accepted),
                                characterMessage(character,player,true))+
                                (if(accepted && outfit!=null)listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(
                                    outfit.id,0,0),character.power,stored,characterById=::currentCharacterById)) else emptyList())+
                                result.recovery?.let {completedWork(it.copy(player=player),character)}.orEmpty()
                        }
                        return null
                    }
                    0x0b02 -> {
                        check(gameStarted && navigation != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val command = org.logh7.protocol.FuelMessages.supply(body)
                        require(command.characterId == character.id.toInt())
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = character.id.toInt() + 1_000_000,
                            captainCard = command.card, politics = character.abilities[1], administration = character.abilities[2],
                            supplyFuel = true)).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous) { "Supply lost its authenticated session" }
                            publication = Publication(character, player, false)
                            listOf(org.logh7.protocol.FuelMessages.reply(command, gameSeconds(), player.pcp, player.mcp, result.player != null),
                                characterMessage(character, player, true)) + completedWork(result, character)
                        }
                        return null
                    }
                    0x0b01 -> {
                        check(gameStarted && navigation != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val command = org.logh7.protocol.NavigationMessages.warp(body)
                        require(command.characterId == character.id.toInt())
                        require(command.base == 0 || world?.galaxy?.bases?.any {
                            it.id == command.base && it.grid == command.grid
                        } == true) { "Warp base is outside the destination system" }
                        pendingWorld = navigation.apply(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), command.grid, character.id.toInt() + 1_000_000,
                            command.card, politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
                            val player = result.player ?: run {
                                val origin = checkNotNull(result.previous) { "Warp lost its authenticated session" }
                                publication = Publication(character, origin, false)
                                return@thenApply listOf(org.logh7.protocol.NavigationMessages.rejected(
                                    command.copy(grid = origin.grid, base = origin.baseId, mode = origin.mode),
                                    gameSeconds(), origin.pcp, origin.mcp)) +
                                    // The CD's warp ACK handler is empty. Its command chain
                                    // waits for 0b07 even when entry fails; restore the actual
                                    // position so the next card command can be selected.
                                    (if (origin.baseId == 0 && origin.mode == 5) listOf(
                                        characterMessage(character, origin, true), unitMessage(character, origin),
                                        org.logh7.protocol.NavigationMessages.grid(origin.grid,origin.cpPaused),
                                        org.logh7.protocol.NavigationMessages.moved(command.copy(grid = origin.grid),
                                            gameSeconds(), origin.unitId, origin.cruise)) else emptyList()) +
                                    completedWork(result, character)
                            }
                            publication = Publication(character, player, false)
                            val seconds = gameSeconds()
                            listOf(org.logh7.protocol.NavigationMessages.accepted(command, seconds, player.pcp, player.mcp),
                                characterMessage(character, player, true),
                                unitMessage(character, player),
                                org.logh7.protocol.NavigationMessages.grid(player.grid,player.cpPaused),
                                org.logh7.protocol.NavigationMessages.moved(command, seconds, player.unitId, player.cruise)) + completedWork(result, character)
                        }
                        return null
                    }
                    0x0f1c -> {
                        check(gameStarted && worldInitialized && presence != null)
                        val message = org.logh7.protocol.ChatMessages.grid(body)
                        require(message.characterId.toLong() == checkNotNull(currentCharacter(identity.account)).id)
                        if (message.text.isBlank()) return null
                        presence.gridChat(connection, message, gameSeconds())
                    }
                    0x0f1d -> {
                        check(gameStarted && worldInitialized && presence != null)
                        val message = org.logh7.protocol.ChatMessages.spot(body)
                        require(message.characterId.toLong() == checkNotNull(currentCharacter(identity.account)).id)
                        if (message.text.isBlank()) return null
                        presence.spotChat(connection, message, gameSeconds())
                    }
                    0x0f08 -> {
                        check(gameStarted && body.size == 28)
                        // This local world has no mail yet; finish the client's periodic inbox query.
                        LoginMessages.sessionMessage(0x0f09, byteArrayOf(0))
                    }
                    0x0200 -> { check(!gameLoggedIn); gameLoggedIn = true; LobbyMessages.gameLoginOk() }
                    0x1008 -> { check(gameLoggedIn); characters.accept(identity.account, CharacterMessages.generate(body)) }
                    0x1000 -> { check(gameLoggedIn && !gameStarted && body.size == 2); OriginalCharacterMessages.account(characters.fame(identity.account), characters.entries(identity.account)) }
                    0x1200 -> {
                        if(gameStarted) {
                            check(worldInitialized && world!=null && stock!=null)
                            if(org.logh7.protocol.StrategyActionMessages.matches(body)) {
                                check(administration!=null)
                                val query=org.logh7.protocol.StrategyActionMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account))
                                require(query.character==character.id.toInt())
                                if(query.preview) {
                                    pendingWorld=stocks().thenApply {stored ->
                                        val inventory=checkNotNull(stored)
                                        val policy=org.logh7.engine.OrganizationalOfficePolicy(world.galaxy)
                                        require(query.command==96 && query.card==0 || policy.held(inventory,character.id.toInt()).any {it.card==query.card}) {"Strategy directory requires a held office"}
                                        if(query.command==96) {
                                            if(query.argument==0)return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,
                                                org.logh7.engine.FlagshipMarketCatalog.options(character.power).map {option->
                                                    org.logh7.protocol.StrategyActionMessages.Selection(option.index+1,
                                                        "${checkNotNull(org.logh7.engine.Flagships.find(option.kind)).name} ${option.price}",option.maximumRank)})
                                            val eligible=if(query.argument==2)inventory.flagshipMarket.offers.filter {
                                                it.receiver==character.id.toInt() && it.expiresAt>gameSeconds()}.map {it.giver}.toSet() else null
                                            val candidates=characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}.filter {
                                                it.power==character.power && it.id!=character.id && it.id>query.target && (it.rank==0 || it.rank in 2..20) &&
                                                    (eligible==null || it.id.toInt() in eligible)}.sortedBy {it.id}.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,candidates.map {
                                                org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)})
                                        }
                                        if(query.command==24) {
                                            val force=when(query.card) {
                                                28,169->0;29,170->1;30,171->2;else->error("Not an operation issuing office")
                                            }
                                            val rows=if(force==2)characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                                .filter {it.power==character.power && it.rank in 2..20 &&
                                                    inventory.outfits.values.none {o->it.id.toInt() in o.members()}}
                                                .map {org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)}
                                            else inventory.outfits.values.filter {it.power==character.power &&
                                                (if(force==0)it.kind==org.logh7.engine.OutfitKind.FLEET else it.kind in setOf(
                                                    org.logh7.engine.OutfitKind.TRANSPORT,org.logh7.engine.OutfitKind.PATROL))}
                                                .map {org.logh7.protocol.StrategyActionMessages.Selection(it.id,"부대 ${it.id}")}
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows
                                                .filter {it.id>query.target}.sortedBy {it.id}.take(200))
                                        }
                                        val candidates=characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                            .filter {it.power==character.power && it.id>query.target && it.id!=character.id}.sortedBy {it.id}.take(200)
                                        org.logh7.protocol.StrategyActionMessages.candidates(query,candidates.map {target ->
                                            org.logh7.protocol.AppointmentDirectoryMessages.Person(target,
                                                listOf(0,if(target.power==2)59 else 195)+administration.offices(target.id.toInt(),target.power,
                                                    checkNotNull(generation)).map {it.kind})})
                                    }
                                    return null
                                }
                                pendingWorld=administration.strategyAction(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    query.character,if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                                    checkNotNull(generation),query.command,query.card,character.rank,query.outfit,
                                    character.abilities[1],character.abilities[2],query.target,query.base,query.value,
                                    identities=characters.registeredCharacters().map {id->org.logh7.engine.StrategicIdentity(id.id.toInt(),id.power,
                                        id.rank,id.origin,id.abilities.toList())},amount=query.amount.toLong(),argument=query.argument)).thenApply {result ->
                                    val player=checkNotNull(result.player)
                                    val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    val changedPeople=result.navigators.values.map {it.characterId}+
                                        (if(query.command in setOf(0,1,2,3,4,8,9) && query.target>0)listOf(query.target) else emptyList())
                                    if(result.changed)for(id in changedPeople.distinct().filter {it!=query.character})currentCharacterById(id)?.let {target ->
                                        navigation?.snapshot()?.navigators?.values?.singleOrNull {it.characterId==id}?.let {targetPlayer ->
                                            presence?.refreshCharacter(target,targetPlayer,offices(target,targetPlayer),gameSeconds(),
                                                administration.stationedOutfit(targetPlayer))
                                        }
                                    }
                                    org.logh7.protocol.StrategyActionMessages.response(query,result.rejection?.let {it.ordinal+1} ?: 0,result.changed)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.SupplementDirectoryMessages.matches(body)) {
                                val query=org.logh7.protocol.SupplementDirectoryMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account))
                                require(query.character==character.id.toInt())
                                pendingWorld=stocks().thenApply {stored ->
                                    val inventory=checkNotNull(stored)
                                    val source=inventory.outfits.values.singleOrNull {it.commanderId==character.id.toInt() && it.power==character.power}
                                    val owner=source?.let {inventory.bases.getValue(it.baseId)}
                                    val units=if(source==null || owner?.power!=character.power || owner.camp!=0) emptyList() else
                                        checkNotNull(source.activeShips).values.filter {it.stack.kind==query.kind}.sortedBy {it.id}.map {
                                            org.logh7.protocol.SupplementDirectoryMessages.Unit(it.id,it.stack.kind,
                                                org.logh7.engine.UnitSpecifications.ships.single {spec->spec.kind==it.stack.kind}.number-it.boats)
                                        }.filter {it.destroyed>0}
                                    org.logh7.protocol.SupplementDirectoryMessages.response(query,units)
                                }
                                return null
                            }
                            if (org.logh7.protocol.SupplyDirectoryMessages.matches(body)) {
                                val query=org.logh7.protocol.SupplyDirectoryMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account))
                                require(query.character==character.id.toInt())
                                pendingWorld=stocks().thenApply { stored ->
                                    val inventory=checkNotNull(stored)
                                    val source=inventory.outfits.values.singleOrNull { it.commanderId==character.id.toInt() &&
                                        it.kind==org.logh7.engine.OutfitKind.TRANSPORT && it.power==character.power &&
                                        world.galaxy.bases.single { base->base.id==it.baseId }.grid==query.grid }
                                    val base=source?.let { inventory.bases.getValue(it.baseId) }
                                    val allowed=if(source==null || base?.power!=character.power || base.camp!=0) emptyList() else
                                        inventory.outfits.values.filter { it.id!=source.id && it.baseId==source.baseId && it.power==character.power }
                                            .sortedBy { it.id }.map { outfit -> org.logh7.protocol.SupplyDirectoryMessages.Outfit(outfit.id,
                                                outfit.kind.wire,outfit.number,outfit.supplies,org.logh7.engine.OutfitSupplies.visibleCapacity(outfit),
                                                world.baseWire(world.galaxy.bases.single { it.id==outfit.baseId })) }
                                    org.logh7.protocol.SupplyDirectoryMessages.response(query,allowed)
                                }
                                return null
                            }
                            if (org.logh7.protocol.LogisticsDirectoryMessages.matches(body)) {
                                val query=org.logh7.protocol.LogisticsDirectoryMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account))
                                require(query.character==character.id.toInt())
                                pendingWorld=stocks().thenApply { stored ->
                                    val inventory=checkNotNull(stored)
                                    val office=inventory.logisticsOfficers[character.id.toInt()]
                                    val allowed=inventory.outfits.values.filter { outfit ->
                                        val base=inventory.bases[outfit.baseId]
                                        office?.permits(character.power,outfit.kind)==true && outfit.power==character.power &&
                                            base?.power==character.power && base.camp==0
                                    }
                                    if(query.search==32) {
                                        val bases=allowed.map { it.baseId }.distinct().sorted()
                                            .map { id -> world.baseWire(world.galaxy.bases.single { it.id==id }) }
                                        org.logh7.protocol.LogisticsDirectoryMessages.bases(query,bases)
                                    } else {
                                        val outfits=allowed.filter { it.baseId==query.base && office?.card==query.card }.sortedBy { it.id }
                                            .map { org.logh7.protocol.LogisticsDirectoryMessages.Outfit(it.id,it.kind.wire,it.number,
                                                world.baseWire(world.galaxy.bases.single { base -> base.id==it.baseId })) }
                                        org.logh7.protocol.LogisticsDirectoryMessages.outfits(query,outfits)
                                    }
                                }
                                return null
                            }
                            val query=org.logh7.protocol.AppointmentDirectoryMessages.query(body)
                            val character=checkNotNull(currentCharacter(identity.account))
                            require(query.character==character.id.toInt())
                            pendingWorld=stocks().thenApply { stored ->
                                val inventory=checkNotNull(stored)
                                val policy=org.logh7.engine.OrganizationalOfficePolicy(world.galaxy)
                                val available=policy.available(inventory,character.id.toInt(),character.power,character.rank)
                                val role=org.logh7.engine.OrganizationOffices.of(query.role)
                                val selected=available[query.role].orEmpty()
                                val dismissals=org.logh7.engine.OfficeDismissalPolicy(world.galaxy)
                                    .available(inventory,character.id.toInt(),character.power,character.rank)
                                when(query.search) {
                                    18 -> {
                                        val vacancies=available.mapValues { (card,seats)->
                                            val definition=checkNotNull(org.logh7.engine.OrganizationOffices.of(card))
                                            seats.sumOf {seat->definition.seats-policy.holders(inventory,card).count {it.seat==seat}}.coerceAtMost(255)
                                        }
                                        val held=dismissals.groupingBy {it.card}.eachCount()
                                        org.logh7.protocol.AppointmentDirectoryMessages.cards(query,
                                            (vacancies.keys+held.keys).associateWith {maxOf(vacancies[it] ?: 0,held[it] ?: 0).coerceAtMost(255)})
                                    }
                                    27 -> org.logh7.protocol.AppointmentDirectoryMessages.outfits(query,selected.map {id ->
                                        val outfit=inventory.outfits.getValue(id)
                                        org.logh7.protocol.AppointmentDirectoryMessages.Outfit(id,outfit.number,outfit.kind.wire,
                                            world.baseWire(world.galaxy.bases.single {it.id==outfit.baseId}))
                                    })
                                    28 -> org.logh7.protocol.AppointmentDirectoryMessages.bases(query,selected.map {id ->
                                        val base=world.galaxy.bases.single {it.id==id}
                                        org.logh7.protocol.AppointmentDirectoryMessages.Base(base.id,base.grid,base.name)
                                    })
                                    4 -> {
                                        require(role!=null && selected.isNotEmpty() && (query.base==0 || selected.any {
                                            (if(role.scope==org.logh7.engine.OfficeScope.OUTFIT)inventory.outfits[it]?.baseId else it)==query.base
                                        })) {"No appointment authority at selected seat"}
                                        val eligible=characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}.filter {target ->
                                            target.id!=character.id && target.power==character.power && role.eligible(character.rank,target.rank) &&
                                                policy.cardCount(inventory,target.id.toInt())<14
                                        }
                                        org.logh7.protocol.AppointmentDirectoryMessages.people(query,eligible.map {target ->
                                            org.logh7.protocol.AppointmentDirectoryMessages.Person(target,listOf(0,if(target.power==2)59 else 195)+
                                                administration!!.offices(target.id.toInt(),target.power,checkNotNull(generation)).map {it.kind})
                                        })
                                    }
                                    5 -> {
                                        val ids=dismissals.filter {it.card==query.role}.map {it.target}.toSet()
                                        org.logh7.protocol.AppointmentDirectoryMessages.people(query,
                                            characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                                .filter {it.id.toInt() in ids && it.power==character.power}.map {target ->
                                                org.logh7.protocol.AppointmentDirectoryMessages.Person(target,listOf(0,if(target.power==2)59 else 195)+
                                                    administration!!.offices(target.id.toInt(),target.power,checkNotNull(generation)).map {it.kind})
                                            })
                                    }
                                    else -> error("Unsupported appointment directory")
                                }
                            }
                            return null
                        }
                        check(gameLoggedIn && OriginalCharacterMessages.catalogueRequest(body))
                        val messages = listOf(LoginMessages.sessionMessage(0x1200, body.copyOfRange(2, body.size)),
                            OriginalCharacterMessages.catalogue(characters.candidates(identity.account)), LoginMessages.sessionMessage(0x1201, byteArrayOf()))
                        return handshake.stageAll(messages, clearHeader = 0)
                    }
                    0x1004 -> {
                        check(gameLoggedIn && !gameStarted)
                        val id = OriginalCharacterMessages.entryId(body)
                        characters.entryState(identity.account, id)
                    }
                    0x1006 -> { check(gameLoggedIn && !gameStarted); characters.apply(identity.account, OriginalCharacterMessages.application(body)) }
                    0x0205 -> {
                        check(gameLoggedIn && !gameStarted && !joining && body.size == 2)
                        val character = checkNotNull(currentCharacter(identity.account))
                        if (admission != null) {
                            joining = true; joinRequested = true
                            pendingJoin = admission.join(identity.account, character, connection, checkNotNull(generation))
                            return null
                        }
                        gameStarted = true
                        LoginMessages.sessionMessage(0x0206, byteArrayOf(0))
                    }
                    0x0203 -> {
                        check(gameStarted && body.size == 2)
                        BootstrapMessages.characterId(checkNotNull(currentCharacter(identity.account)))
                    }
                    0x0f00, 0x0f02 -> {
                        check(gameStarted && body.size == 2)
                        val character = checkNotNull(currentCharacter(identity.account))
                        if (navigation != null) {
                            val request = org.logh7.engine.NavigationRequest(identity.account, character.id.toInt(),
                                if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                                connection, checkNotNull(generation), shipKind=character.shipKind,commandAbility=character.abilities[4], politics = character.abilities[1], administration = character.abilities[2])
                            pendingWorld = stocks().thenCompose { stored -> navigation.apply(request).thenApply { result ->
                                val player = checkNotNull(result.player) { "Navigation initialization rejected: ${result.rejection}" }
                                val captured=navigation.snapshot()
                                publication = Publication(character, player, true,opcode,captured?.revision)
                                val tactical=if(player.cpPaused && world!=null)TacticalBootstrap(checkNotNull(captured),
                                    world.galaxy,player,::currentCharacterById) else null
                                tacticalResumeGrid=tactical?.grid
                                listOf(BootstrapMessages.characterId(character),
                                    characterMessage(character, player),
                                    unitMessage(character, player),
                                    org.logh7.protocol.NavigationMessages.grid(player.grid,player.cpPaused)) +
                                    world?.initialize(player.grid, directory = opcode == 0x0f00, stocks = stored).orEmpty() +
                                    tactical?.initialize(::offices).orEmpty() +
                                    listOf(checkNotNull(BootstrapMessages.response(body))) + pendingNavigation(player) + tactical?.resume().orEmpty()
                            } }
                            return null
                        }
                        val messages = listOf(BootstrapMessages.characterId(character),
                            BootstrapMessages.character(character), BootstrapMessages.unit(character),
                            checkNotNull(BootstrapMessages.response(body)))
                        println("world initialized character=${character.id} power=${character.power}")
                        return handshake.stageAll(messages, clearHeader = 0)
                    }
                    else -> {
                        check(gameStarted)
                        BootstrapMessages.response(body, gameSeconds(), world?.catalogues.orEmpty())
                            ?: error("Unsupported game opcode 0x${opcode.toString(16)}")
                    }
                }
            } else when (opcode) {
                0x2000 -> { check(!lobbyLoggedIn); lobbyLoggedIn = true; LobbyMessages.loginOk() }
                0x2003 -> {
                    check(lobbyLoggedIn && body.size==2)
                    val character=characters.character(identity.account)
                    val snapshot=navigation?.snapshot()
                    // Lobby accounts may inspect their own ended character without joining,
                    // changing CP/navigation, or obtaining a playable ended-session ticket.
                    if(characters!==characterProvider())LobbyMessages.noCharacters()
                    else if(character!=null && snapshot?.result!=null && snapshot.session?.generation==admission?.generation)
                        LobbyMessages.characters(character,SessionEndingReport.session(snapshot,characters.registeredCharacters()),
                            org.logh7.protocol.SessionEndingMessages.Evaluation(0,0,0,0,0))
                    else LobbyMessages.characters(if(admission?.running==false)null else character)
                }
                0x2005 -> { check(lobbyLoggedIn && body.size == 3 && body[2].toInt() in 0..2); LobbyMessages.sessions(admission?.running != false) }
                0x2009 -> {
                    check(lobbyLoggedIn)
                    if (LobbyMessages.select(body) == LobbyMessages.LOCAL_SESSION_ID && admission?.running != false) {
                        LobbyMessages.selected(sessionAddress, sessionPort, tickets.issue(identity.account, SessionTickets.Purpose.GAME))
                    } else LobbyMessages.selectionFailed()
                }
                else -> error("Unsupported lobby opcode 0x${opcode.toString(16)}")
            }
            return handshake.stageAll(listOf(response), clearHeader = 0)
        }
        check(state == State.AWAITING_AUTH) { "Post-authentication messages are not implemented" }
        val body = handshake.receive(type, data) ?: return null
        return when (role) {
            Role.LOGIN -> {
                val request = LoginMessages.request(body)
                val expected = accounts[request.account]
                val accepted = expected != null && java.security.MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8), request.credential.toByteArray(Charsets.UTF_8))
                val reply = if (accepted) LoginMessages.success(sessionAddress, sessionPort, tickets.issue(request.account)) else LoginMessages.failure()
                state = if (accepted) State.AUTHENTICATED else State.REJECTED
                handshake.stageAll(listOf(reply))
            }
            Role.SESSION -> {
                ticket = requireNotNull(tickets.take(LoginMessages.sessionToken(body))) { "Invalid or expired session token" }
                state = State.AUTHENTICATED
                null // No invented response: session acceptance opcode is not established yet.
            }
        }
    }
    fun close() {
        if (state == State.CLOSED) return
        handshake.close(); state = State.CLOSED
        presence?.remove(connection)
        if (joinRequested) admission?.disconnect(checkNotNull(ticket).account, connection)
    }
}

fun loadTestAccounts(path: Path?): Map<String, String> {
    if (path == null) return emptyMap()
    val properties = Properties().also { Files.newBufferedReader(path).use(it::load) }
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }.also { accounts ->
        require(accounts.all { (name, password) -> name.length in 1..30 && !name.startsWith('@') && password.length <= 10 })
    }
}
