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
    private data class Publication(val character: CharacterMessages.Generate, val player: org.logh7.engine.Navigator, val initialize: Boolean)
    private var publication: Publication? = null
    private var worldInitialized = false
    private var recoveryCheckedAt: Long? = null
    private fun stocks(): CompletableFuture<org.logh7.engine.PlanetStocks?> =
        stock?.snapshot(checkNotNull(generation))?.thenApply { it } ?: CompletableFuture.completedFuture(null)
    private fun offices(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator) =
        administration?.offices(character.id.toInt(), character.power, player.generation).orEmpty()
    private fun unitMessage(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator) =
        BootstrapMessages.unit(character, player.grid, player.cruise, player.baseId, player.mode,
            administration?.stationedOutfit(player) ?: 0)
    private fun characterMessage(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator, changed: Boolean = false) =
        if (changed) BootstrapMessages.changedCharacter(character,player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices(character,player))
        else BootstrapMessages.character(character,player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices(character,player))
    /** Restore the accepted job, without submitting or charging it a second time. */
    private fun pendingNavigation(player: org.logh7.engine.Navigator): List<ByteArray> {
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
        if (!worldInitialized || state != State.AUTHENTICATED || obsolete() || navigation == null) return null
        val now = gameSeconds()
        if (recoveryCheckedAt?.let { now >= it && now - it < 24 } == true) return null
        recoveryCheckedAt = now
        val identity = checkNotNull(ticket)
        val character = checkNotNull(characters.character(identity.account))
        return recover(character, identity.account)
    }
    private fun recover(character: CharacterMessages.Generate, account: String, clockRequest: ByteArray? = null): CompletableFuture<List<ByteArray>> =
        checkNotNull(navigation).apply(org.logh7.engine.NavigationRequest(account,
            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
            connection, checkNotNull(generation), politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
            val player = checkNotNull(result.player) { "CP refresh lost its authenticated session" }
            if (player != result.previous) publication = Publication(character, player, false)
            val changed = result.previous?.let { it.pcp != player.pcp || it.mcp != player.mcp } == true
            clockRequest?.let { listOf(checkNotNull(BootstrapMessages.response(it, gameSeconds()))) }.orEmpty() +
                (if (changed) listOf(characterMessage(character, player, true)) else emptyList()) +
                completedWork(result, character)
        }
    fun takePendingWorld() = pendingWorld.also { pendingWorld = null }
    internal fun finishWorld(messages: List<ByteArray>): OutboundBatch? = prepared {
        if (state == State.CLOSED) null else {
            val update = publication.also { publication = null }
            if (update?.initialize == true) worldInitialized = true
            val shared = update?.let { presence?.update(GamePresence.Member(connection, it.character, it.player, onPresence, offices(it.character,it.player), administration?.stationedOutfit(it.player) ?: 0), gameSeconds()) }
            val output = if (update?.initialize == true && shared != null) {
                val index = messages.indexOfFirst { java.nio.ByteBuffer.wrap(it).getShort(4).toInt() == 0x0325 }
                check(index >= 0)
                messages.take(index) + shared.peers.map(presence!!::character) + messages.drop(index)
            } else messages + shared?.refresh.orEmpty()
            if (output.isEmpty()) null else handshake.stageAll(output, clearHeader = 0)
        }
    }
    internal fun notifyWorld(messages: List<ByteArray>): OutboundBatch? = prepared {
        if (state == State.CLOSED || !gameStarted || obsolete()) null else handshake.stageAll(messages, clearHeader = 0)
    }
    fun obsolete() = ticket?.purpose == SessionTickets.Purpose.GAME && admission != null &&
        (generation != admission.generation || !admission.running)
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
            val response = if (identity.purpose == SessionTickets.Purpose.GAME) {
                when (opcode) {
                    0x0300 -> {
                        check(gameStarted && body.size == 2)
                        if (navigation == null) checkNotNull(BootstrapMessages.response(body, gameSeconds())) else {
                            val character = checkNotNull(characters.character(identity.account))
                            pendingWorld = recover(character, identity.account, body)
                            return null
                        }
                    }
                    0x0f1a -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character = checkNotNull(characters.character(identity.account))
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
                        val character = checkNotNull(characters.character(identity.account))
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
                        val character = checkNotNull(characters.character(identity.account))
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
                        val character=checkNotNull(characters.character(identity.account))
                        require(query.character==character.id.toInt())
                        pendingWorld=administration.resignOffice(org.logh7.engine.OfficeResignationRequest(
                            identity.account,connection,query.character,
                            if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.card,character.abilities[1],character.abilities[2])).thenApply { result ->
                            val player=checkNotNull(result.player) { "Resignation lost its authenticated actor" }
                            publication=Publication(character,player,false)
                            listOf(org.logh7.protocol.ResignationMessages.response(query,gameSeconds(),player.pcp,player.mcp,result.rejection==null),
                                characterMessage(character,player,true))+
                                result.recovery?.let {completedWork(it.copy(player=player),character)}.orEmpty()
                        }
                        return null
                    }
                    0x0707 -> {
                        check(gameStarted && administration!=null && world!=null)
                        val received=org.logh7.protocol.AppointmentMessages.query(body)
                        // Native capital institution 17 skips the base picker and sends zero.
                        val query=if(received.base==0) received.copy(base=checkNotNull(
                            org.logh7.engine.CapitalAppointmentPolicy.rule(received.card)?.let {
                                org.logh7.engine.CapitalAppointmentPolicy(world.galaxy).base(it)?.id
                            }) { "Capital appointment has no matching world base" }) else received
                        val character=checkNotNull(characters.character(identity.account))
                        require(query.character==character.id.toInt())
                        val target=characters.characterById(query.target)
                        pendingWorld=administration.appointGarrison(org.logh7.engine.GarrisonAppointmentRequest(
                            identity.account,connection,character.id.toInt(),
                            if (character.power==2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            checkNotNull(generation),query.base,query.target,query.card,character.rank,target?.rank ?: -1,
                            target?.power ?: 0,character.abilities[1],character.abilities[2])).thenApply { result ->
                            val player=checkNotNull(result.player) { "Appointment lost its authenticated actor" }
                            publication=Publication(character,player,false)
                            if (result.rejection==null && target!=null) result.targetPlayer?.let { targetPlayer ->
                                presence?.refreshOffices(target.id.toInt(),targetPlayer.generation,offices(target,targetPlayer),gameSeconds())
                            }
                            listOf(org.logh7.protocol.AppointmentMessages.response(query,gameSeconds(),player.pcp,player.mcp,
                                result.rejection==null,result.changed),characterMessage(character,player,true)) +
                                result.recovery?.let { completedWork(it.copy(player=player),character) }.orEmpty()
                        }
                        return null
                    }
                    0x0a00 -> {
                        check(gameStarted && administration != null && world != null)
                        val query = org.logh7.protocol.TaxMessages.query(body)
                        val character = checkNotNull(characters.character(identity.account))
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
                        val character = checkNotNull(characters.character(identity.account))
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
                                    world.assignmentInventory(base.units),base.supplies,
                                    world.assignmentInventory(warehouse.units),warehouse.supplies)
                            } else org.logh7.protocol.LogisticsMessages.assignmentResponse(query,gameSeconds(),player.pcp,player.mcp,false)
                            listOf(reply,characterMessage(character,player,true)) +
                                (if (accepted) listOf(world.warehouse(query.base,result.stocks),
                                    world.outfitWarehouse(query.base,query.outfit,character.id.toInt(),character.power,checkNotNull(result.stocks)))
                                else emptyList()) + result.recovery?.let { completedWork(it.copy(player=player),character) }.orEmpty()
                        }
                        return null
                    }
                    0x0c02 -> {
                        check(gameStarted && administration != null && world != null)
                        val query = org.logh7.protocol.LogisticsMessages.reorganization(body)
                        val character = checkNotNull(characters.character(identity.account))
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
                                    supplies,accepted))
                            }
                            listOf(org.logh7.protocol.LogisticsMessages.reorganizationResponse(query, gameSeconds(),
                                player.pcp, player.mcp, supplies, accepted), characterMessage(character, player, true)) +
                                (if (accepted) listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(
                                    query.outfit, query.base, 0), character.power, checkNotNull(result.stocks), characterById=characters::characterById),
                                    world.outfitWarehouse(query.base, query.outfit, character.id.toInt(), character.power, checkNotNull(result.stocks)))
                                else emptyList()) + result.recovery?.let { completedWork(it.copy(player = player), character) }.orEmpty()
                        }
                        return null
                    }
                    0x032e, 0x0330 -> {
                        check(gameStarted && world != null && stock != null)
                        val character = checkNotNull(characters.character(identity.account))
                        val party = if (opcode == 0x032e) org.logh7.protocol.OutfitPartyMessages.query(body) else null
                        val unit = if (opcode == 0x0330) org.logh7.protocol.OutfitPartyMessages.unitQuery(body) else null
                        pendingWorld = stocks().thenApply { stored -> listOf(if (party != null)
                            world.outfitParty(party,character.power,checkNotNull(stored),character.id.toInt(),characters::characterById) else
                            world.outfitUnit(checkNotNull(unit).id,character.power,checkNotNull(stored),unit.base)) }
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
                        val actor = checkNotNull(characters.character(identity.account))
                        val target = checkNotNull(characters.characterById(id))
                        require(target.power == actor.power) { "Character detail requires friendly affiliation" }
                        BootstrapMessages.character(target,0,0,
                            offices=administration.offices(id,target.power,checkNotNull(generation)))
                    }
                    0x034e -> {
                        check(gameStarted && world != null && stock != null)
                        val card = org.logh7.protocol.CardCharacterMessages.query(body)
                        pendingWorld = stocks().thenApply { stored -> listOf(world.cardCharacters(card,
                            checkNotNull(stored),characters::characterById)) }
                        return null
                    }
                    0x0326 -> {
                        check(gameStarted && world != null)
                        val (base, outfit) = org.logh7.protocol.WorldMessages.warehouseQuery(body)
                        if (stock == null) {
                            require(outfit == 0) { "Outfit warehouse storage is disabled" }
                            world.warehouse(base)
                        } else {
                            val character = checkNotNull(characters.character(identity.account))
                            pendingWorld = stocks().thenApply { listOf(if (outfit == 0) world.warehouse(base, it) else
                                world.outfitWarehouse(base,outfit,character.id.toInt(),character.power,checkNotNull(it))) }
                            return null
                        }
                    }
                    0x031e, 0x0320 -> {
                        check(gameStarted && world != null)
                        val ids = org.logh7.protocol.WorldMessages.baseQuery(body, opcode)
                        if (opcode == 0x031e && stock != null) {
                            pendingWorld = stocks().thenApply { listOf(world.conditions(ids, it)) }
                            return null
                        }
                        if (opcode == 0x031e) world.conditions(ids) else world.places(ids)
                    }
                    0x0b02 -> {
                        check(gameStarted && navigation != null)
                        val character = checkNotNull(characters.character(identity.account))
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
                        val character = checkNotNull(characters.character(identity.account))
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
                                    gameSeconds(), origin.pcp, origin.mcp)) + completedWork(result, character)
                            }
                            publication = Publication(character, player, false)
                            val seconds = gameSeconds()
                            listOf(org.logh7.protocol.NavigationMessages.accepted(command, seconds, player.pcp, player.mcp),
                                characterMessage(character, player, true),
                                unitMessage(character, player),
                                org.logh7.protocol.NavigationMessages.grid(player.grid),
                                org.logh7.protocol.NavigationMessages.moved(command, seconds, player.unitId, player.cruise)) + completedWork(result, character)
                        }
                        return null
                    }
                    0x0f1d -> {
                        check(gameStarted && worldInitialized && presence != null)
                        val message = org.logh7.protocol.ChatMessages.spot(body)
                        require(message.characterId.toLong() == checkNotNull(characters.character(identity.account)).id)
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
                            if (org.logh7.protocol.LogisticsDirectoryMessages.matches(body)) {
                                val query=org.logh7.protocol.LogisticsDirectoryMessages.query(body)
                                val character=checkNotNull(characters.character(identity.account))
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
                            val character=checkNotNull(characters.character(identity.account))
                            require(query.character==character.id.toInt())
                            pendingWorld=stocks().thenApply { stored ->
                                val inventory=checkNotNull(stored)
                                val officePolicy=org.logh7.engine.BaseOfficePolicy(world.galaxy)
                                val available=if(character.power==2) world.galaxy.bases.filter { base ->
                                    val held=inventory.bases[base.id]
                                    officePolicy.ordinaryPlanet(base.id) && held?.power==2 && held.camp==0 &&
                                        held.governorId==character.id.toInt() && held.garrisonCommanderId==0
                                } else emptyList()
                                val capitalPolicy=org.logh7.engine.CapitalAppointmentPolicy(world.galaxy)
                                val capitals=capitalPolicy.vacancies(inventory,character.id.toInt(),character.power,character.rank)
                                val selected=if(query.role==55)available else capitals.filter { it.rule.card==query.role }.map { it.base }
                                val capitalRule=org.logh7.engine.CapitalAppointmentPolicy.rule(query.role)
                                when(query.search) {
                                    18 -> org.logh7.protocol.AppointmentDirectoryMessages.cards(query,
                                        (if(available.isEmpty())emptyMap() else mapOf(55 to available.size))+
                                            capitals.associate { it.rule.card to 1 })
                                    28 -> org.logh7.protocol.AppointmentDirectoryMessages.bases(query,selected.map {
                                        org.logh7.protocol.AppointmentDirectoryMessages.Base(it.id,it.grid,it.name)
                                    })
                                    4 -> {
                                        require(selected.isNotEmpty() && (query.base==0 || selected.any { it.id==query.base })) { "No appointment authority at selected base" }
                                        val eligible=characters.registeredCharacters().filter { target ->
                                            target.id!=character.id && target.power==character.power &&
                                                (capitalRule?.eligible(character.rank,target.rank) ?: (target.rank in 2..10 && character.rank<target.rank)) && administration!!.offices(target.id.toInt(),target.power,
                                                    checkNotNull(generation)).size<14
                                        }
                                        org.logh7.protocol.AppointmentDirectoryMessages.people(query,eligible.map { target ->
                                            org.logh7.protocol.AppointmentDirectoryMessages.Person(target,listOf(0,if(target.power==2)59 else 195)+
                                                administration!!.offices(target.id.toInt(),target.power,checkNotNull(generation)).map { it.kind })
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
                        val character = checkNotNull(characters.character(identity.account))
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
                        BootstrapMessages.characterId(checkNotNull(characters.character(identity.account)))
                    }
                    0x0f00, 0x0f02 -> {
                        check(gameStarted && body.size == 2)
                        val character = checkNotNull(characters.character(identity.account))
                        if (navigation != null) {
                            val request = org.logh7.engine.NavigationRequest(identity.account, character.id.toInt(),
                                if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                                connection, checkNotNull(generation), politics = character.abilities[1], administration = character.abilities[2])
                            pendingWorld = stocks().thenCompose { stored -> navigation.apply(request).thenApply { result ->
                                val player = checkNotNull(result.player) { "Navigation initialization rejected: ${result.rejection}" }
                                publication = Publication(character, player, true)
                                listOf(BootstrapMessages.characterId(character),
                                    characterMessage(character, player),
                                    unitMessage(character, player),
                                    org.logh7.protocol.NavigationMessages.grid(player.grid)) +
                                    world?.initialize(player.grid, directory = opcode == 0x0f00, stocks = stored).orEmpty() +
                                    listOf(checkNotNull(BootstrapMessages.response(body))) + pendingNavigation(player)
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
                0x2003 -> { check(lobbyLoggedIn && body.size == 2); LobbyMessages.characters(if (admission?.running == false) null else characters.character(identity.account)) }
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
