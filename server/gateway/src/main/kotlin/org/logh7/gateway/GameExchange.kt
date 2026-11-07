package org.logh7.gateway

import org.logh7.protocol.LoginMessages
import org.logh7.protocol.LobbyMessages
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.CountryImageMessages
import org.logh7.protocol.BootstrapMessages
import org.logh7.protocol.ChatMessages
import org.logh7.protocol.OriginalCharacterMessages
import org.logh7.engine.NavigationRejection
import org.logh7.engine.positionIn
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.Properties
import java.util.concurrent.CompletableFuture
import org.logh7.engine.Admission
import org.logh7.engine.members
import org.logh7.engine.country

/** evidence:guess — local test accounts and short-lived, single-use session tickets. */
class SessionTickets(private val clock: Clock = Clock.systemUTC()) {
    enum class Purpose { LOBBY, GAME }
    data class Ticket(val account: String, val purpose: Purpose, val expiresAt: Long,
        val sessionId:Int=1,val generation:Long?=null)
    private val random = SecureRandom()
    private val tickets = mutableMapOf<Long, Ticket>()
    @Synchronized fun issue(account: String = "", purpose: Purpose = Purpose.LOBBY,
        sessionId:Int=1,generation:Long?=null): Long {
        require(sessionId in 1..65535 && (generation==null || generation>0))
        tickets.entries.removeIf { it.value.expiresAt <= clock.millis() }
        check(tickets.size < 4096) { "Session ticket capacity exceeded" }
        var token: Long
        do { token = random.nextInt().toLong() and 0xffffffffL } while (token == 0L || token in tickets)
        tickets[token] = Ticket(account, purpose, clock.millis() + 60_000,sessionId,generation)
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
    internal val connection: String = java.util.UUID.randomUUID().toString(),
    private val expectedGeneration: Long? = admission?.generation,
    private val characterProvider: () -> CharacterCreation = { characters },
    private val world: WorldBootstrap? = null,
    private val navigation: GameNavigation? = null,
    internal val presence: GamePresence? = null,
    private val onPresence: (List<ByteArray>) -> Unit = {},
    private val stock: GameStock? = null,
    private val administration: GameAdministration? = null,
    private val communication:GameCommunication? = null,
    private val fame:FameLedger? = null,
    private val sessionDirectory:GameSessionDirectory?=null,
    private val sessionId:Int=LobbyMessages.LOCAL_SESSION_ID,
    private val sessionName:String=LobbyMessages.LOCAL_SESSION_NAME,
    private val bindHost:String?=null,
    /** Login/ticket outcome counts for the ops metrics; null in tests that do not look at them. */
    private val counters:GatewayCounters?=null,
    /** While closed, new logins are refused (maintenance); existing sessions are untouched. */
    private val maintenance:MaintenanceGate?=null,
    /** This world's lobby country catalogue (mode 7); null answers an empty catalogue. */
    private val lobbyCountries:((org.logh7.protocol.LobbyCountryMessages.Request)->List<ByteArray>)?=null,
    private val countryEndings:((org.logh7.protocol.CountryEndingMessages.Request,String)->List<ByteArray>)?=null,
) {
    enum class Role { LOGIN, SESSION }
    enum class State { KEY_EXCHANGE, AWAITING_AUTH, AUTHENTICATED, REJECTED, CLOSED }
    var state = State.KEY_EXCHANGE
        private set
    private var ticket: SessionTickets.Ticket? = null
    private var lobbyLoggedIn = false
    private var gameLoggedIn = false
    private var gameStarted = false
    internal fun joinedAccount(): String? = ticket?.account?.takeIf { gameStarted && state == State.AUTHENTICATED }
    private var countrySelection:CharacterCreation.CountrySelection?=null
    private var countrySelectionMode=0
    private fun selectedCountry():CharacterCreation.CountrySelection?=countrySelection?.also {selection->
        val catalog=world?.lobbyCountryDefinitions()?.first ?: org.logh7.engine.CountryCatalogLoader.current()
        require(catalog.countries[selection.country.value]?.nativePower==selection.nativePower) {"Selected country changed"}
    }
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
    private var startupWarpNotice: ByteArray? = null
    private var startupGridReady = false
    private var firstWarpGuideChecked = false
    private data class OfflineWarpNotice(val reason: NavigationRejection, val message: ByteArray, var batch: OutboundBatch? = null)
    private var pendingOfflineWarpNotice: OfflineWarpNotice? = null
    private var sentOfflineWarpCancellation: NavigationRejection? = null
    private var tacticalInitializationPending = false
    private var tacticalExitPending = false
    private var pendingTacticalRevision = -1L
    private fun rememberTacticalRefresh() {
        navigation?.snapshot()?.revision?.let {pendingTacticalRevision=maxOf(pendingTacticalRevision,it)}
    }
    private var endingPrepared=0
    internal fun sessionEndingAvailable():Boolean {
        if(state!=State.AUTHENTICATED || !gameStarted || !worldInitialized || ticket?.purpose!=SessionTickets.Purpose.GAME)return false
        val snapshot=navigation?.snapshot() ?: return false
        return generation==snapshot.session?.generation && generation==admission?.generation &&
            (snapshot.result!=null || snapshot.navigators[ticket?.account]?.country in snapshot.session?.eliminated.orEmpty())
    }
    internal fun finishSession():OutboundBatch? = prepared {
        if(!sessionEndingAvailable())null else {
            val snapshot=checkNotNull(navigation?.snapshot())
            val phase=if(snapshot.result==null)1 else 2
            if(endingPrepared>=phase)return@prepared null
            val account=checkNotNull(ticket).account
            val player=checkNotNull(snapshot.navigators[account])
            val character=checkNotNull(currentCharacter(account))
            val evaluation=if(snapshot.result==null || fame==null)org.logh7.protocol.SessionEndingMessages.Evaluation(0,0,0,0,0)
                else fame.evaluation(snapshot,characters,account) ?: return@prepared null
            val countries=world?.lobbyCountryDefinitions()?.first ?: org.logh7.engine.CountryCatalogLoader.current()
            val total=CountryEndingReport.count(snapshot,countries)
            val messages=(0..maxOf(0,total-1) step org.logh7.protocol.CountryEndingMessages.PAGE).flatMap {offset->
                CountryEndingReport.reply(org.logh7.protocol.CountryEndingMessages.Request(
                    org.logh7.protocol.OutfitPartyMessages.Query(sessionId,127*512+offset,14)),snapshot,characters.profiles(),
                    countries,world?.publishedCountryAppearances().orEmpty(),player.country?.value ?: 0,world?.galaxy)
            }+if(snapshot.result==null)emptyList() else listOf(SessionEndingReport.message(snapshot,character,player,
                characters.registeredCharacters(),offices(character,player),evaluation,sessionId,sessionName,countries))
            endingPrepared=phase
            handshake.stageAll(messages,clearHeader=0)
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
    /** 命令/提案 pairs (CD p.28): same side; same spot inside a facility, else the same grid; not in tactics.
     * Each target is paired with my card and theirs of the same institution; the emperor card pairs with any office. */
    private fun proposalPairs(inventory:org.logh7.engine.PlanetStocks,me:org.logh7.engine.Navigator):List<Triple<Int,Int,Int>> {
        val country=me.country ?: return emptyList()
        val policy=org.logh7.engine.OrganizationalOfficePolicy(checkNotNull(world).galaxy)
        fun institution(card:Int)=policy.role(card,country)?.institution
        val mine=policy.held(inventory,me.characterId).filter {it.country==country}.map {it.card}.filter {institution(it)!=null}.distinct()
        val players=navigation?.snapshot()?.navigators.orEmpty().values.filter {it.generation==me.generation &&
            it.characterId!=me.characterId && it.country==country && !it.cpPaused && it.grid==me.grid &&
            (me.spotId==0 || it.baseId==me.baseId && it.spotId==me.spotId && it.spotOwner==me.spotOwner)}
        return players.sortedBy {it.characterId}.mapNotNull {other ->
            val theirs=policy.held(inventory,other.characterId).filter {it.country==country}.map {it.card}.filter {institution(it)!=null}.distinct()
            mine.firstNotNullOfOrNull {card->theirs.firstOrNull {card==1 || institution(it)==institution(card)}?.let {Triple(other.characterId,card,it)}}
        }
    }
    /** Other people whose CP changed in the same commit (a proposal refund) see it at once. */
    private fun refreshPaid(navigators:Map<String,org.logh7.engine.Navigator>) {
        navigators.values.forEach {other->currentCharacterById(other.characterId)?.let {c->
            presence?.refreshCharacter(c,other,offices(c,other),gameSeconds(),administration?.stationedOutfit(other) ?: 0)}}
    }
    private fun offices(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator) =
        player.country?.let {administration?.offices(character.id.toInt(), it.value, player.generation)}.orEmpty()
    private fun flagshipCharacter(character:CharacterMessages.Generate,player:org.logh7.engine.Navigator) =
        player.hull?.let { hull -> org.logh7.engine.Flagships.find(hull.kind)?.let {
            character.copy(shipKind=it.kind,shipType=it.type) } } ?: character
    private val traceOpcodes = System.getenv("LOGH7_TRACE_OPCODES") == "1"
    private fun currentCharacterById(id:Int):CharacterMessages.Generate? = characters.characterById(id)?.let {
        WorldCharacters.view(it,navigation?.snapshot())
    }
    private fun currentCharacter(account:String):CharacterMessages.Generate? = characters.character(account)?.let {
        WorldCharacters.view(it,navigation?.snapshot())
    }
    private fun currentProfile(account:String,characterId:Int):CharacterProfile =
        WorldCharacters.profiles(characters.profiles(),navigation?.snapshot()).getValue(account).also {
            check(it.character.id.toInt()==characterId) {"Command character identity mismatch"}
        }
    private fun navigate(request:org.logh7.engine.NavigationRequest):CompletableFuture<org.logh7.engine.NavigationResult> {
        val profile=currentProfile(request.account,request.characterId)
        return checkNotNull(navigation).apply(request.copy(country=profile.country,shipStyle=profile.shipStyle))
    }
    private fun unitMessage(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator) =
        BootstrapMessages.unit(flagshipCharacter(character,player), player.grid, player.cruise, player.baseId, player.mode,
            administration?.stationedOutfit(player) ?: 0,player.supplies,if(player.hull?.hp?.let {it<100}==true)1 else 0)
    private fun characterMessage(character: CharacterMessages.Generate, player: org.logh7.engine.Navigator, changed: Boolean = false) =
        if (changed) BootstrapMessages.changedCharacter(character,player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices(character,player))
        else BootstrapMessages.character(character,player.pcp,player.mcp,player.spotId,player.spotOwner,player.returnBase,offices(character,player))
    /** Restore the accepted job, without submitting or charging it a second time. */
    private fun pendingNavigation(player: org.logh7.engine.Navigator): List<ByteArray> {
        // The captain card the accepted request carried: the actual country's display code (engine contract,
        // TacticalAuthority), never Navigator.power. A country missing from the catalogue has no card to restore.
        val captainCard by lazy { org.logh7.engine.TacticalAuthority.captainCard(player) }
        player.retreat?.let { retreat ->
            return listOf(org.logh7.protocol.TacticalRetreatMessages.reply(
                org.logh7.protocol.TacticalRetreatMessages.Retreat(player.characterId,listOf(player.unitId)),
                gameSeconds(),maxOf(0,retreat.startsAt-gameSeconds())))
        }
        player.warpTarget?.let { warp ->
            // Same restore order as docking: the HUD module records the reserved ACK.
            val seconds = gameSeconds()
            return listOf(org.logh7.protocol.NavigationMessages.reserved(org.logh7.protocol.NavigationMessages.Warp(
                player.characterId, captainCard ?: return emptyList(), warp, 0, 5),
                seconds, player.pcp, player.mcp, maxOf(0, checkNotNull(player.warpReadyAt) - seconds)))
        }
        val target = player.dockTarget ?: return emptyList()
        val seconds = gameSeconds()
        return listOf(org.logh7.protocol.PlaceMessages.docked(org.logh7.protocol.PlaceMessages.Dock(
            player.characterId, captainCard ?: return emptyList(), target, 4),
            seconds, player.pcp, player.mcp, waitSeconds = maxOf(0, checkNotNull(player.dockReadyAt) - seconds)))
    }
    private fun completedWork(result: org.logh7.engine.NavigationResult, character: CharacterMessages.Generate,
        warpNotified: Boolean = false): List<ByteArray> {
        val warpFinished = !warpNotified && result.completedWarp
        if (!result.completedFuel && !result.completedDock && !warpFinished && result.warpCancellation == null) return emptyList()
        val player = checkNotNull(result.player ?: result.previous)
        val seconds = gameSeconds()
        return (if (result.completedFuel) listOf(org.logh7.protocol.FuelMessages.supplied(seconds,
            player.characterId, player.unitId, player.cruise)) else emptyList()) +
            (if (result.completedDock) listOf(unitMessage(character, player),
                org.logh7.protocol.PlaceMessages.dockNotification(player.characterId, player.baseId, player.mode, seconds,
                    player.spotId, if (player.spotId == 0) player.characterId else player.spotOwner)) else emptyList()) +
            // Every completed reserved warp must end the native 0b07 wait, including failed entry.
            (if (warpFinished) listOf(characterMessage(character, player, true), unitMessage(character, player),
                org.logh7.protocol.NavigationMessages.grid(player.grid, player.cpPaused),
                org.logh7.protocol.NavigationMessages.moved(org.logh7.protocol.NavigationMessages.Warp(player.characterId,
                    if (character.power == 2) 59 else 195, player.grid, 0, 5), seconds, player.unitId, player.cruise)) +
                (if(player.baseId!=0 || player.mode!=5 || player.spotId!=0) listOf(
                    org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,player.baseId,player.mode,seconds,
                        player.spotId,if(player.spotId==0)player.characterId else player.spotOwner)) else emptyList()) else emptyList()) +
            reservedWarpNotice(result.warpCancellation, player.characterId, seconds)
    }
    private fun reservedWarpNotice(cancellation: NavigationRejection?, characterId: Int, seconds: Long): List<ByteArray> {
        val reason = when (cancellation) {
            NavigationRejection.GRID -> "진입 불가"
            NavigationRejection.CRUISE -> "항속 부족"
            NavigationRejection.STAR_SYSTEM_NOT_ADJACENT -> "성계 거리 초과"
            null -> return emptyList()
            else -> "예약 취소"
        }
        return listOf(ChatMessages.spot(ChatMessages.Spot(characterId, "[워프] $reason·CP 미환불"), seconds))
    }
    private fun warpRefusal(characterId: Int, rejection: NavigationRejection?, seconds: Long): ByteArray {
        // The original strategy chat shows only the tail of long notices.
        val reason = when (rejection) {
            NavigationRejection.SAME_GRID -> "다른 칸 선택"
            NavigationRejection.RANGE -> "더 가까운 칸 선택"
            NavigationRejection.STAR_SYSTEM_NOT_ADJACENT -> "성계에 인접 필요"
            NavigationRejection.GRID -> "진입 불가·재선택"
            NavigationRejection.CRUISE -> "항속 부족·보급 필요"
            NavigationRejection.CP -> "MCP/PCP 부족"
            NavigationRejection.CARD -> "함장 카드 필요"
            NavigationRejection.TACTICAL -> "전술 중 사용 불가"
            NavigationRejection.EMBARKED -> "출항 필요"
            NavigationRejection.SUPPLYING -> "보급 완료까지 대기"
            NavigationRejection.TRAVELLING -> "이동 완료까지 대기"
            NavigationRejection.WOUNDED -> "부상 회복까지 대기"
            else -> "현재 사용 불가"
        }
        return ChatMessages.spot(ChatMessages.Spot(characterId, "[워프] $reason"), seconds)
    }
    private fun dockRefusal(characterId: Int, rejection: NavigationRejection?, seconds: Long): ByteArray {
        val reason = when (rejection) {
            NavigationRejection.BASE -> "거점과 현재 위치 확인"
            NavigationRejection.ACCESS -> "아군 거점 선택"
            NavigationRejection.CP -> "MCP/PCP 부족"
            NavigationRejection.CARD -> "함장 카드 필요"
            NavigationRejection.TACTICAL -> "전술 중 사용 불가"
            NavigationRejection.EMBARKED -> "함내로 돌아가세요"
            NavigationRejection.SUPPLYING -> "보급 완료까지 대기"
            NavigationRejection.TRAVELLING -> "이동 완료까지 대기"
            NavigationRejection.WOUNDED -> "부상 회복까지 대기"
            else -> "현재 기항 불가"
        }
        return ChatMessages.spot(ChatMessages.Spot(characterId, "[기항] $reason"), seconds)
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
    private fun recover(character: CharacterMessages.Generate, account: String, clockRequest: ByteArray? = null): CompletableFuture<List<ByteArray>> {
        val readyForNotice = clockRequest != null && startupGridReady
        val canQueueOfflineNotice = readyForNotice && pendingOfflineWarpNotice == null
        // There is no native chat acknowledgement. Clear the durable notice on a later clock request from this connection.
        val acknowledged = sentOfflineWarpCancellation.takeIf { readyForNotice }
        return navigate(org.logh7.engine.NavigationRequest(account,
            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
            connection, checkNotNull(generation), shipKind=character.shipKind,commandAbility=character.abilities[4], politics = character.abilities[1], administration = character.abilities[2])
            .copy(acknowledgedOfflineWarpCancellation = acknowledged)).thenApply { result ->
            val player = checkNotNull(result.player ?: result.previous?.takeIf { it.navigationPending &&
                (result.rejection == org.logh7.engine.NavigationRejection.BUSY ||
                    result.rejection == org.logh7.engine.NavigationRejection.CRUISE) }) { "CP refresh lost its authenticated session" }
            val previous = deliveredPlayer ?: result.previous
            if (player != previous) publication = Publication(character, player, false)
            val changed = previous?.let { it.pcp != player.pcp || it.mcp != player.mcp } == true
            val relocated = previous?.let { it.grid != player.grid || it.baseId != player.baseId ||
                it.mode != player.mode || it.spotId != player.spotId || it.spotOwner != player.spotOwner } == true
            if (!worldInitialized) reservedWarpNotice(result.warpCancellation, player.characterId, gameSeconds()).singleOrNull()?.let {
                startupWarpNotice = it
            }
            // The CD can still clear chat while consuming 0f03. Send after its next clock request.
            val startupNotice = if (readyForNotice)
                listOfNotNull(startupWarpNotice.also { startupWarpNotice = null }) else emptyList()
            val offlineNotice = if (canQueueOfflineNotice) {
                val cancellation = player.offlineWarpCancellation.takeUnless { it == acknowledged }
                if (acknowledged != null && player.offlineWarpCancellation != acknowledged) sentOfflineWarpCancellation = null
                reservedWarpNotice(cancellation, player.characterId, gameSeconds()).also { messages ->
                    if (cancellation != null) pendingOfflineWarpNotice = OfflineWarpNotice(cancellation, messages.single())
                }
            } else emptyList()
            val firstWarpGuide = if (readyForNotice && !firstWarpGuideChecked) {
                firstWarpGuideChecked = true
                val map = world
                val canWarp = player.mode == 5 && player.baseId == 0 && !player.cpPaused && !player.navigationPending &&
                    player.hull?.let { it.woundedUntil <= gameSeconds() } == true
                if (map != null && character.generated && player.moves == 0L && canWarp &&
                    player.country?.let { map.galaxy.countryStarts[it] } == player.grid &&
                    startupNotice.isEmpty() && offlineNotice.isEmpty() &&
                    org.logh7.engine.TacticalAuthority.captainCard(player, map.lobbyCountryDefinitions().first) != null) {
                    // The original strategy HUD keeps only the most recent spot line visible.
                    listOf(ChatMessages.spot(ChatMessages.Spot(player.characterId, "직무카드→함장→워프"), gameSeconds()))
                } else emptyList()
            } else emptyList()
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
                            gameSeconds(),player.spotId,if(player.spotId==0)player.characterId else player.spotOwner)) else emptyList()) +
                    (if (worldInitialized) reservedWarpNotice(result.warpCancellation, player.characterId, gameSeconds()) else emptyList())
                else completedWork(if (worldInitialized) result else result.copy(warpCancellation = null), character)) +
                (if(worldInitialized && player.cpPaused && deliveredTactics==true && previous?.grid==player.grid)
                    listOf(org.logh7.protocol.TacticsMessages.corps(tactics(account).corps.filter {it.character==player.characterId}))
                    else emptyList()) +
                (world?.let { map -> navigation?.snapshot()?.let { GridForceReport.message(it, map.galaxy, player, gameSeconds()) } }
                    ?.let(::listOf).orEmpty()) + offlineNotice + startupNotice + firstWarpGuide
        }
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
                if(changed) {
                    tacticalExitPending = !it.player.cpPaused
                    listOf(org.logh7.protocol.TacticsMessages.notify(it.player.grid,it.player.cpPaused))
                } else emptyList()
            }.orEmpty()
            if (update != null) {
                // A strategic warp starts its own grid initialization after 0b07.
                if(worldInitialized && update.player.cpPaused && deliveredPlayer?.grid?.let {it!=update.player.grid}==true)
                    tacticalInitializationPending=true
                deliveredPlayer = update.player
            }
            if (update?.initialize == true) {
                worldInitialized = true
                tacticalExitPending = false
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
                handshake.stageAll(output + tactical + retry, clearHeader = 0).also { batch ->
                    pendingOfflineWarpNotice?.let { notice ->
                        if (output.any { it === notice.message }) notice.batch = batch
                    }
                }
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
                val relocating=navigation?.snapshot()?.navigators?.get(ticket?.account)?.let {player ->
                    deliveredPlayer?.grid?.let {it!=player.grid}==true}==true
                if(tacticalInitializationPending || relocating)current.filterNot(::entering)
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
        if ((!player.cpPaused && !tacticalExitPending && deliveredTactics != true) || world == null) return messages
        fun opcode(message: ByteArray) = java.nio.ByteBuffer.wrap(message).getShort(4).toInt() and 65535
        // 0b09 clears the native character cache; 0325 replaces the entire unit list.
        // A strategy presence transaction containing one peer would discard the battlefield
        // definitions while leaving 033b's unit states live (004c32a0 then imports stale data).
        if (messages.any { opcode(it) == 0x0b09 }) {
            // 0f1f closes the battlefield asynchronously; its next initialization imports strategic peers.
            if (!player.cpPaused) return emptyList()
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
    /** U-25 defection ends the connection after its reply; the client reconnects on the new side. */
    @Volatile private var defectedLogout=false
    fun obsolete() = defectedLogout || ticket?.purpose == SessionTickets.Purpose.GAME && admission != null &&
        (generation != admission.generation || !admission.running && !sessionEndingAvailable())
    // ByteArray convenience is retained for codec callers. Gateway always writes the bound outbound batch.
    fun finishJoin(result: Admission): ByteArray? = finishJoinOutbound(result)?.bytes
    fun accept(type: Int, data: ByteArray): ByteArray? = acceptOutbound(type, data)?.bytes
    internal fun submitting(batch: OutboundBatch) = handshake.submitting(batch)
    internal fun committed(batch: OutboundBatch) {
        handshake.committed(batch)
        pendingOfflineWarpNotice?.takeIf { it.batch === batch }?.let { notice ->
            sentOfflineWarpCancellation = notice.reason
            pendingOfflineWarpNotice = null
        }
    }
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
        val reply = when (result) {
            Admission.ACCEPTED -> LoginMessages.sessionMessage(0x0206, byteArrayOf(0))
            Admission.ALREADY_ONLINE -> {
                counters?.duplicateAdmissionRejected?.incrementAndGet()
                LoginMessages.sessionRejection(LoginMessages.SessionRejection.ALREADY_ONLINE)
            }
            else -> LoginMessages.sessionRejection()
        }
        return handshake.stageAll(listOf(reply), clearHeader = 0)
    }
    private fun refuseGameAdmissionForMaintenance(): OutboundBatch? {
        if (maintenance?.refuses() != true) return null
        state = State.REJECTED
        return handshake.stageAll(listOf(LoginMessages.sessionRejection(LoginMessages.SessionRejection.MAINTENANCE)), clearHeader = 0)
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
            // Opt-in QA trace of authenticated game requests (no payload, which may hold chat text).
            if(traceOpcodes)System.err.println("trace ${identity.account} <- 0x%04x (%d bytes)".format(opcode,body.size))
            if(sessionEndingAvailable()) {
                // Consume and validate encrypted in-flight requests without reopening commands.
                // Clock polling remains safe while the player reads the final result.
                return if(opcode==0x0300 && body.size==2)handshake.stageAll(listOf(checkNotNull(
                    BootstrapMessages.response(body,checkNotNull(navigation?.snapshot()?.session).gameSeconds))),clearHeader=0) else null
            }
            val response = if (identity.purpose == SessionTickets.Purpose.GAME) {
                check(gameLoggedIn || opcode == 0x0200) { "Game login required" }
                when (opcode) {
                    0x0417,0x0418 -> {
                        // Reconstructed ground battle window (design-ground-battle-window.md): the CD's unsent attack/stop troop orders.
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalGroundMessages.target(body)
                        require(command.character==character.id.toInt())
                        if(command.units.any {it !in 1..999999} || command.target !in 0..999999 || command.target in command.units)
                            return handshake.stageAll(listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,gameSeconds(),false)),clearHeader=0)
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalGround=org.logh7.engine.TacticalGroundCommand(
                                if(command.target>0)org.logh7.engine.TacticalGroundAction.TARGET else org.logh7.engine.TacticalGroundAction.CLEAR,
                                command.units,target=command.target))).thenApply {result->
                            if(result.player==null)println("tactical ground target rejected account=${identity.account} units=${command.units} target=${command.target} reason=${result.rejection}")
                            listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,gameSeconds(),result.player!=null))
                        }
                        return null
                    }
                    0x040f,0x0410 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalGroundMessages.command(body)
                        require(command.character==character.id.toInt())
                        if(command.units.any {it !in 1..999999})return handshake.stageAll(listOf(
                            org.logh7.protocol.TacticalGroundMessages.reply(command,gameSeconds(),0,false)),clearHeader=0)
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalGround=org.logh7.engine.TacticalGroundCommand(
                                if(opcode==0x040f)org.logh7.engine.TacticalGroundAction.DEPLOY else org.logh7.engine.TacticalGroundAction.WITHDRAW,
                                command.units,command.skill))).thenApply {result->
                            if(result.player==null)println("tactical ground rejected account=${identity.account} opcode=0x${opcode.toString(16)} units=${command.units} reason=${result.rejection}")
                            val now=gameSeconds()
                            // The CD shows nothing for a failed 040f/0410 reply (004be8c0 ignores it); say why in the log.
                            val notice=if(result.player==null)TacticalGroundNotices.text(opcode==0x040f,result.rejection)?.let {
                                listOf(org.logh7.protocol.ChatMessages.spot(org.logh7.protocol.ChatMessages.Spot(character.id.toInt(),it),now))} else null
                            listOf(org.logh7.protocol.TacticalGroundMessages.reply(command,now,
                                result.groundReadyAt?.let {(it-now).coerceAtLeast(0)} ?: 0,result.player!=null))+notice.orEmpty()+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0412 -> {
                        check(gameStarted && worldInitialized && navigation != null && world != null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val command=org.logh7.protocol.TacticalGroundMessages.command(body)
                        require(command.character==character.id.toInt())
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(),if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),shipKind=character.shipKind,commandAbility=character.abilities[4],
                            tacticalPosture=org.logh7.engine.TacticalPostureChange(command.units,mode,command.base))).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            if(result.player==null)println("tactical posture rejected account=${identity.account} units=${command.units} mode=$mode base=${command.base} reason=${result.rejection}")
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld = navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        val command=org.logh7.protocol.TacticalCommandMessages.command(body).let {
                            if(it.opcode==0x041f)org.logh7.protocol.TacticalCommandMessages.withFortressVelocity(it,
                                org.logh7.engine.TacticalFortressMotion.VELOCITY) else it}
                        require(command.character==character.id.toInt() && command.points.all {it.z==0f})
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,character.id.toInt(),
                            if(character.power==2)org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection,checkNotNull(generation),captainCard=if(character.power==2)59 else 195,
                            shipKind=character.shipKind,rank=character.rank,commandAbility=character.abilities[4],
                            tacticalRanks=navigation.snapshot()?.navigators?.values.orEmpty().filter {it.grid==navigation.snapshot()?.navigators?.get(identity.account)?.grid}
                                .mapNotNull {p->characters.characterById(p.characterId)?.let {p.characterId to it.rank}}.toMap(),
                            tacticalCommand=org.logh7.engine.TacticalCommand(command.opcode,command.units,command.base,command.kind,
                                command.targetKind,command.target,command.velocity,command.direction,
                                command.points.map {org.logh7.engine.TacticalPoint(it.x,it.y)},command.payload))).thenApply {result->
                            val player=checkNotNull(result.player ?: result.previous)
                            if(traceOpcodes && result.player==null)System.err.println("trace ${identity.account} 0x%04x rejected: ${result.rejection}".format(command.opcode))
                            publication=Publication(character,player,false)
                            // The CD shows nothing for a refused 041f; name the cause when the fortress lost its propulsion (CD p.46).
                            val broken=command.opcode==0x041f && result.player==null &&
                                navigation.snapshot()?.planetStocks?.tacticalCommands?.brokenPropulsion?.contains(command.base)==true
                            val notice=if(broken)listOf(org.logh7.protocol.ChatMessages.spot(org.logh7.protocol.ChatMessages.Spot(
                                character.id.toInt(),FORTRESS_PROPULSION_LOST),gameSeconds())) else emptyList()
                            listOf(org.logh7.protocol.TacticalCommandMessages.reply(command,gameSeconds(),
                                (result.commandReadyAt?.minus(gameSeconds()) ?: 0).coerceAtLeast(0),result.player!=null))+notice+
                                characterMessage(character,player,true)+commandCorps(identity.account)
                        }
                        return null
                    }
                    0x0401,0x0403 -> {
                        check(gameStarted && worldInitialized && navigation!=null && world!=null)
                        val character=checkNotNull(currentCharacter(identity.account))
                        val turn=org.logh7.protocol.TacticalTurnMessages.command(body)
                        require(turn.character==character.id.toInt())
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                                println("tactical move rejected account=${identity.account} units=${move.units.map {it.id}} reason=${result.rejection} velocity=${move.velocity} engine=${player.energy?.values?.get(0)}")
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld=navigate(org.logh7.engine.NavigationRequest(identity.account,settings.character,
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
                        pendingWorld = navigate(org.logh7.engine.NavigationRequest(identity.account,
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
                        pendingWorld = navigate(org.logh7.engine.NavigationRequest(identity.account, id,
                            if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = id + 1_000_000, captainCard = command.card,
                            place = org.logh7.engine.PlaceMove.SwitchMode(command.mode), rank = character.rank,
                            politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous) { "Departure lost its authenticated session" }
                            val accepted = result.player != null
                            publication = Publication(character, player, false)
                            val seconds = gameSeconds()
                            // The default native chat pane exposes one line; keep the refusal short.
                            val notice = if (result.rejection == org.logh7.engine.NavigationRejection.WOUNDED)
                                listOf(org.logh7.protocol.ChatMessages.spot(org.logh7.protocol.ChatMessages.Spot(id,
                                    "[부상] 출항·승하선 불가"), seconds))
                                else emptyList()
                            listOf(org.logh7.protocol.PlaceMessages.switched(command, seconds, player.pcp, player.mcp,
                                player.mode, player.spotId, if (player.spotId == 0) id else player.spotOwner, accepted),
                                characterMessage(character, player, true),
                                unitMessage(character, player)) +
                                (if (accepted && !result.completedDock) listOf(org.logh7.protocol.PlaceMessages.dockNotification(id,
                                    player.baseId, player.mode, seconds)) else emptyList()) + completedWork(result, character) + notice
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
                        pendingWorld = navigate(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = character.id.toInt() + 1_000_000,
                            captainCard = dock?.card, place = place, rank = character.rank,
                            politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
                            val player = checkNotNull(result.player ?: result.previous) { "Place request lost its authenticated session" }
                            val accepted = result.player != null
                            if(traceOpcodes && !accepted && move!=null)System.err.println("trace ${identity.account} spot ${move.spot} owner ${move.owner} rejected: ${result.rejection}")
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
                            // The CD waits for 0b0b after sending a dock command, even on denial.
                            // Restore only this client's real position, keeping an earlier dock in progress.
                            val reportDockPosition = dock != null && player.dockTarget == null && !result.completedDock &&
                                (accepted || dock.base != 0)
                            listOf(ack,
                                characterMessage(character, player, true),
                                unitMessage(character, player)) +
                                (if (reportDockPosition) listOf(org.logh7.protocol.PlaceMessages.dockNotification(player.characterId,
                                    player.baseId, player.mode, seconds, player.spotId,
                                    if (player.spotId == 0) player.characterId else player.spotOwner)) else emptyList()) + completedWork(result, character) +
                                (if (dock != null && dock.base != 0 && !accepted)
                                    listOf(dockRefusal(player.characterId, result.rejection, seconds)) else emptyList())
                        }
                        return null
                    }
                    0x0709 -> {
                        check(gameStarted && administration!=null && world!=null)
                        val query=org.logh7.protocol.ResignationMessages.query(body)
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(query.character==character.id.toInt())
                        pendingWorld=administration.resignOffice(org.logh7.engine.OfficeResignationRequest(
                            identity.account,connection,query.character,currentProfile(identity.account,query.character).country,
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
                        // Native institution 17 sends base zero. Let the engine resolve the actor country's
                        // current capital in the same transaction that checks authority and saves the appointment.
                        val query=org.logh7.protocol.AppointmentMessages.query(body)
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(query.character==character.id.toInt())
                        val target=currentCharacterById(query.target)
                        pendingWorld=administration.appointGarrison(org.logh7.engine.GarrisonAppointmentRequest(
                            identity.account,connection,character.id.toInt(),currentProfile(identity.account,character.id.toInt()).country,
                            checkNotNull(generation),query.base,query.target,query.card,character.rank,target?.rank ?: -1,
                            WorldCharacters.identities(characters.profiles(),navigation?.snapshot()).singleOrNull {it.id==query.target}?.country,
                            character.abilities[1],character.abilities[2],query.outfit)).thenApply { result ->
                            val player=checkNotNull(result.player) { "Appointment lost its authenticated actor" }
                            publication=Publication(character,player,false)
                            if (result.rejection==null && target!=null) result.targetPlayer?.let { targetPlayer ->
                                presence?.refreshOffices(target.id.toInt(),targetPlayer.generation,offices(target,targetPlayer),gameSeconds(),
                                    administration.stationedOutfit(targetPlayer))
                            }
                            val resolved=if(result.rejection!=null)query else if(query.outfit>0)query.copy(
                                base=checkNotNull(result.stocks).outfits.getValue(query.outfit).baseId)
                            else if(query.base==0)query.copy(base=checkNotNull(org.logh7.engine.OrganizationalOfficePolicy(world.galaxy)
                                .capital(checkNotNull(result.stocks),checkNotNull(player.country)))) else query
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
                            identity.account,connection,query.character,currentProfile(identity.account,query.character).country,
                            checkNotNull(generation),query.target,query.card,character.rank,
                            WorldCharacters.identities(characters.profiles(),navigation?.snapshot()).singleOrNull {it.id==query.target}?.country,
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
                            currentProfile(identity.account,query.character).country,
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
                            currentProfile(identity.account,character.id.toInt()).country,
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
                                    world.outfitWarehouse(query.base,query.outfit,character.id.toInt(),checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value,checkNotNull(result.stocks)))
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
                            connection, character.id.toInt(), currentProfile(identity.account,character.id.toInt()).country,
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
                                    checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value, stored, characterById = ::currentCharacterById),
                                    world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(query.to, 0, 0),
                                        checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value, stored, characterById = ::currentCharacterById)) else emptyList()) +
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
                            character.id.toInt(),currentProfile(identity.account,character.id.toInt()).country,
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
                                    query.outfit,query.base,0),checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value,stored,characterById=::currentCharacterById),
                                    world.outfitWarehouse(query.base,query.outfit,character.id.toInt(),checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value,stored)) else emptyList())+
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
                            currentProfile(identity.account,character.id.toInt()).country,
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
                                    query.outfit, query.base, 0), checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value, checkNotNull(result.stocks), characterById=::currentCharacterById),
                                    world.outfitWarehouse(query.base, query.outfit, character.id.toInt(), checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value, checkNotNull(result.stocks)))
                                else emptyList()) + result.recovery?.let { completedWork(it.copy(player = player), character) }.orEmpty()
                        }
                        return null
                    }
                    0x032e, 0x0330 -> {
                        if(opcode==0x032e && body.size==11 && org.logh7.protocol.CountrySelectionMessages.matches(body[10].toInt() and 255)) {
                            check(gameLoggedIn && !gameStarted && !joining)
                            val request=org.logh7.protocol.CountrySelectionMessages.request(org.logh7.protocol.OutfitPartyMessages.query(body))
                            var accepted=false
                            var selected:CharacterCreation.CountrySelection?=null
                            if(request.sessionId==sessionId) {
                                if(request.query.mode==org.logh7.protocol.CountrySelectionMessages.CANCEL) {
                                    characters.cancelCreation(identity.account,connection)
                                    countrySelection=null;countrySelectionMode=0;accepted=true
                                } else if(characters.character(identity.account)==null) {
                                    val catalog=world?.lobbyCountryDefinitions()?.first ?: org.logh7.engine.CountryCatalogLoader.current()
                                    selected=LobbyCountryCatalogue.selection(request.countryId,catalog,
                                        (world?.galaxy?.countryStarts?.keys ?: setOf(org.logh7.engine.CountryId(2),org.logh7.engine.CountryId(3)))-
                                            navigation?.snapshot()?.session?.eliminated.orEmpty().keys)
                                    val entries=characters.entries(identity.account)
                                    if (entries.isNotEmpty()) {
                                        val available = selected
                                        selected = characters.applicationSelection(identity.account)?.takeIf {
                                            available != null && request.query.mode == org.logh7.protocol.CountrySelectionMessages.LOTTERY &&
                                                it.country == available.country && it.nativePower == available.nativePower
                                        }
                                    }
                                    if(selected!=null) {
                                        characters.cancelCreation(identity.account,connection)
                                        countrySelection=selected;countrySelectionMode=request.query.mode;accepted=true
                                    } else selected=null
                                }
                            }
                            // Declining a replacement keeps the preceding reservation until explicit cancel/disconnect.
                            return handshake.stageAll(listOf(LoginMessages.sessionMessage(0x032f,
                                org.logh7.protocol.CountrySelectionMessages.reply(request,accepted,selected?.creationPower ?: 0,selected?.shipStyle?.value ?: ""))),clearHeader=0)
                        }
                        if(opcode==0x032e && body.size==11 && (body[10].toInt() and 255)==org.logh7.protocol.LobbyCountryMessages.MODE) {
                            // Before creation/lottery a game connection may read only its own world's catalogue.
                            val request=org.logh7.protocol.LobbyCountryMessages.request(org.logh7.protocol.OutfitPartyMessages.query(body))
                            val frames=if(request.sessionId==sessionId)lobbyCountries?.invoke(request) else null
                            return handshake.stageAll(frames ?: LobbyCountryCatalogue.empty(request),clearHeader=0)
                        }
                        if (opcode == 0x032e && body.size == 11 && (body[10].toInt() and 255) == CountryImageMessages.LOBBY_MODE) {
                            val request = CountryImageMessages.LobbyRequest(org.logh7.protocol.OutfitPartyMessages.query(body))
                            val frames = if (request.sessionId == sessionId && admission?.running != false) world?.lobbyCountryEmblem(request) else null
                            return handshake.stageAll(frames ?: CountryImageMessages.lobbyReply(request, 0, 0, null), clearHeader = 0)
                        }
                        check(gameStarted && world != null && stock != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val party = if (opcode == 0x032e) org.logh7.protocol.OutfitPartyMessages.query(body) else null
                        val unit = if (opcode == 0x0330) org.logh7.protocol.OutfitPartyMessages.unitQuery(body) else null
                        if(party?.mode==org.logh7.protocol.OutfitAffiliationMessages.MODE) {
                            check(worldInitialized && navigation!=null)
                            require(party.outfit in 1..65535 && party.base>=0) {"Visible outfit affiliation page only"}
                            pendingWorld=stocks().thenApply {stored->
                                val player=checkNotNull(navigation.snapshot()?.navigators?.get(identity.account))
                                require(player.generation==generation) {"Stale outfit affiliation viewer"}
                                world.outfitAffiliations(party,checkNotNull(stored),player)
                            }
                            return null
                        }
                        if(party?.mode==org.logh7.protocol.TacticalCampMessages.MODE) {
                            require(party.outfit in 1..65535)
                            val snapshot=navigation?.snapshot() ?: return null
                            val player=snapshot.navigators[identity.account] ?: return null
                            val currentGeneration=checkNotNull(generation)
                            // A poll in flight when leaving tactics must not close the connection.
                            if(!worldInitialized || !player.cpPaused || player.baseId!=0 || player.characterId!=character.id.toInt() ||
                                player.generation!=currentGeneration || snapshot.session?.generation!=currentGeneration ||
                                snapshot.planetStocks?.generation!=currentGeneration)return null
                            return handshake.stageAll(TacticalCampReport.reply(party,snapshot,world.galaxy,player,
                                currentGeneration,::currentCharacterById),clearHeader=0)
                        }
                        if(party?.mode==org.logh7.protocol.BaseAffiliationMessages.MODE) {
                            check(worldInitialized)
                            require(party.outfit==0 && party.base in 1..65535) {"Public base ownership snapshot only"}
                            pendingWorld=stocks().thenApply {world.baseAffiliations(party,checkNotNull(it))}
                            return null
                        }
                        if(party?.mode==org.logh7.protocol.CountryIdentityMessages.MODE) {
                            check(worldInitialized);require(party.outfit==character.id.toInt()) {"Own country identity only"}
                            return handshake.stageAll(listOf(world.countryIdentity(party,
                                currentProfile(identity.account,character.id.toInt()).country?.value,character.power)),clearHeader=0)
                        }
                        // Display queries echo the viewer's actual country as its display code; no membership, no reply.
                        val viewerCountry by lazy {checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value}
                        if(party?.mode==4)return handshake.stageAll(listOf(world.countryColors(party,viewerCountry)),clearHeader=0)
                        if(party?.mode==5 || party?.mode==org.logh7.protocol.CountryMetadataMessages.EMBLEM_MODE)
                            return handshake.stageAll(listOf(world.countryMetadata(party,viewerCountry)),clearHeader=0)
                        if(party?.mode==org.logh7.protocol.CountryImageMessages.MODE) {
                            if(!worldInitialized)return null
                            return handshake.stageAll(world.countryImageFrames(party),clearHeader=0)
                        }
                        if(party?.mode==6) {
                            pendingWorld=stocks().thenApply {listOf(world.facilityGuide(party,viewerCountry,checkNotNull(it)))}
                            return null
                        }
                        pendingWorld = stocks().thenApply { stored ->
                            // Ground, battle and unit views act for the profile's actual country, as outfitParty does.
                            val country=checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value
                            if(party?.mode==2)world.outfitGround(party,country,checkNotNull(stored))
                            else if(party?.mode==3) {
                                val snapshot=navigation?.snapshot()
                                val countries=WorldCharacters.profiles(characters.profiles(),snapshot).values
                                    .associate {it.character.id.toInt() to it.country}
                                world.outfitBattle(party,country,checkNotNull(stored),
                                    snapshot?.navigators?.get(identity.account)?.takeIf {it.cpPaused && it.baseId==0}?.grid,
                                    characterCountryById={countries[it]},characterById=::currentCharacterById)
                            }
                            else listOf(if(party!=null)world.outfitParty(party,country,checkNotNull(stored),character.id.toInt(),::currentCharacterById)
                                else world.outfitUnit(checkNotNull(unit).id,country,checkNotNull(stored),unit.base))
                        }
                        return null
                    }
                    0x032a, 0x032c -> {
                        check(gameStarted && world != null && stock != null)
                        val ids = if (opcode == 0x032a) org.logh7.protocol.OutfitMessages.query(body) else null
                        val query = if (opcode == 0x032c) org.logh7.protocol.OutfitMessages.gridQuery(body) else null
                        pendingWorld = stocks().thenApply { stored ->
                            val character=checkNotNull(currentCharacter(identity.account))
                            val country=currentProfile(identity.account,character.id.toInt()).country?.takeIf {character.power==query?.power}
                            listOf(if (ids != null) world.outfits(ids,checkNotNull(stored))
                                else world.gridOutfits(checkNotNull(query),checkNotNull(stored),country))
                        }
                        return null
                    }
                    0x0322 -> {
                        check(gameStarted && worldInitialized && administration != null)
                        require(body.size == 6)
                        val id = java.nio.ByteBuffer.wrap(body).getInt(2)
                        val actor = checkNotNull(currentCharacter(identity.account))
                        val target = checkNotNull(currentCharacterById(id))
                        val profiles=WorldCharacters.profiles(characters.profiles(),navigation?.snapshot()).values.associateBy {it.character.id.toInt()}
                        val viewer=profiles[actor.id.toInt()];val subject=profiles[id]
                        require(WorldCharacters.mayReadPrivateDetail(viewer,subject)) { "Character detail requires friendly affiliation" }
                        val country=WorldCharacters.friendlyCountry(viewer,subject)
                        BootstrapMessages.character(target,0,0,
                            offices=country?.let {administration.offices(id,it.value,checkNotNull(generation))}.orEmpty())
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
                                val outfit = stored?.outfits?.get(member.outfit)?.takeIf {
                                    player.country!=null && it.country==player.country }
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
                        pendingWorld = stocks().thenApply { stored ->
                            // Each holder's actual country (current profile), not the display code.
                            val countryOf=WorldCharacters.identities(characters.profiles(),navigation?.snapshot()).associate {it.id to it.country}
                            listOf(world.cardCharacters(card,checkNotNull(stored),{countryOf[it]},::currentCharacterById)) }
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
                                world.outfitWarehouse(base,outfit,character.id.toInt(),checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value,checkNotNull(it))) }
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
                            return handshake.stageAll(listOf(org.logh7.protocol.GroundTransferMessages.reply(command,gameSeconds(),p.pcp,p.mcp,false,
                                org.logh7.engine.LogisticsRejection.MODE.ordinal+1)),clearHeader=0)
                        }
                        pendingWorld=administration.transferGround(org.logh7.engine.GroundTransferRequest(identity.account,connection,
                            character.id.toInt(),currentProfile(identity.account,character.id.toInt()).country,
                            checkNotNull(generation),command.outfit,
                            if(opcode==0x0b05)org.logh7.engine.GroundTransferAction.LOAD else org.logh7.engine.GroundTransferAction.UNLOAD,
                            character.abilities[1],character.abilities[2])).thenApply {result->
                            val player=checkNotNull(result.player ?: result.recovery?.previous)
                            publication=Publication(character,player,false)
                            val accepted=result.rejection==null
                            val stored=checkNotNull(result.stocks ?: navigation?.snapshot()?.planetStocks)
                            val outfit=stored.outfits[command.outfit]
                            // Reason = rejection ordinal+1; an accepted request that moved nothing reports NO_CHANGE.
                            val reason=result.rejection?.let {it.ordinal+1} ?: if(result.changed)0 else org.logh7.protocol.GroundTransferMessages.NO_CHANGE
                            listOf(org.logh7.protocol.GroundTransferMessages.reply(command,gameSeconds(),player.pcp,player.mcp,accepted,reason),
                                characterMessage(character,player,true))+
                                (if(accepted && outfit!=null)listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(
                                    outfit.id,0,0),checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value,stored,characterById=::currentCharacterById)) else emptyList())+
                                result.recovery?.let {completedWork(it.copy(player=player),character)}.orEmpty()
                        }
                        return null
                    }
                    0x0b02 -> {
                        check(gameStarted && navigation != null)
                        val character = checkNotNull(currentCharacter(identity.account))
                        val command = org.logh7.protocol.FuelMessages.supply(body)
                        require(command.characterId == character.id.toInt())
                        pendingWorld = navigate(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), unitId = character.id.toInt() + 1_000_000,
                            captainCard = command.card, politics = character.abilities[1], administration = character.abilities[2],
                            supplyFuel = true)).thenApply { result ->
                            if(traceOpcodes && result.player==null)System.err.println("trace ${identity.account} fuel card ${command.card} rejected: ${result.rejection}")
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
                        pendingWorld = navigate(org.logh7.engine.NavigationRequest(identity.account,
                            character.id.toInt(), if (character.power == 2) org.logh7.engine.Power.EMPIRE else org.logh7.engine.Power.ALLIANCE,
                            connection, checkNotNull(generation), command.grid, character.id.toInt() + 1_000_000,
                            command.card, politics = character.abilities[1], administration = character.abilities[2])).thenApply { result ->
                            val seconds = gameSeconds()
                            val player = result.player ?: run {
                                val origin = checkNotNull(result.previous) { "Warp lost its authenticated session" }
                                if(traceOpcodes)System.err.println("trace ${identity.account} warp ${command.grid} card ${command.card} rejected: ${result.rejection}")
                                publication = Publication(character, origin, false)
                                return@thenApply listOf(org.logh7.protocol.NavigationMessages.rejected(
                                    command.copy(grid = origin.grid, base = origin.baseId, mode = origin.mode),
                                    seconds, origin.pcp, origin.mcp)) +
                                    // The CD's warp ACK handler is empty. Its command chain
                                    // waits for 0b07 even when entry fails; restore the actual
                                    // position so the next card command can be selected.
                                    (if (origin.baseId == 0 && origin.mode == 5) listOf(
                                        characterMessage(character, origin, true), unitMessage(character, origin),
                                        org.logh7.protocol.NavigationMessages.grid(origin.grid,origin.cpPaused),
                                        org.logh7.protocol.NavigationMessages.moved(command.copy(grid = origin.grid),
                                            seconds, origin.unitId, origin.cruise)) else emptyList()) +
                                    completedWork(result, character, warpNotified = origin.baseId == 0 && origin.mode == 5) +
                                    warpRefusal(origin.characterId, result.rejection, seconds)
                            }
                            publication = Publication(character, player, false)
                            player.warpReadyAt?.let { readyAt ->
                                // Reserved: keep the origin, report CP and the wait; 0b07 is sent on execution.
                                return@thenApply completedWork(result.copy(player = result.previous), character) +
                                    listOf(org.logh7.protocol.NavigationMessages.reserved(command,
                                    seconds, player.pcp, player.mcp, maxOf(0, readyAt - seconds)),
                                    characterMessage(character, player, true))
                            }
                            // A long warp may land on a neighbouring space grid; report the server's arrival.
                            val arrived = command.copy(grid = player.grid)
                            listOf(org.logh7.protocol.NavigationMessages.accepted(arrived, seconds, player.pcp, player.mcp),
                                characterMessage(character, player, true),
                                unitMessage(character, player),
                                org.logh7.protocol.NavigationMessages.grid(player.grid,player.cpPaused),
                                org.logh7.protocol.NavigationMessages.moved(arrived, seconds, player.unitId, player.cruise)) + completedWork(result, character, warpNotified = true)
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
                    0x0f13 -> {
                        // The CD's own proposal send needs the 命令/提案 tab rows the CD never draws; the compatibility
                        // window sends 0x7f00 command 98 instead (design-proposals-orders.md).
                        check(gameStarted && worldInitialized)
                        return null
                    }
                    0x0f14 -> {
                        check(gameStarted && worldInitialized && administration!=null)
                        val reply=org.logh7.protocol.MailMessages.reply(body)
                        val character=checkNotNull(currentCharacter(identity.account))
                        require(reply.sender.id==character.id.toInt()) {"Proposal reply owner mismatch"}
                        val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                            character.id.toInt(),currentProfile(identity.account,character.id.toInt()).country,
                            checkNotNull(generation),org.logh7.engine.ProposalActions.REPLY,0,character.rank,target=reply.refer,value=reply.value))
                        pendingWorld=administration.social(request).thenApply {result ->
                            val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                            publication=Publication(updated,player,false)
                            refreshPaid(result.navigators)
                            listOf(characterMessage(updated,player,true))
                        }
                        return null
                    }
                    0x0f04,0x0f06,0x0f0b,0x0f0c,0x0f0d,0x0f0e,0x0f0f,0x0f10,0x0f11,0x0f12 -> {
                        if(opcode==0x0f06 && !worldInitialized) {
                            check(gameStarted)
                            return handshake.stageAll(listOf(checkNotNull(BootstrapMessages.response(body))),clearHeader=0)
                        }
                        if(opcode in listOf(0x0f04,0x0f06) && communication==null) {
                            check(gameStarted)
                            return handshake.stageAll(listOf(checkNotNull(BootstrapMessages.response(body))),clearHeader=0)
                        }
                        check(gameStarted && communication!=null)
                        if(opcode !in listOf(0x0f04,0x0f06))check(worldInitialized)
                        val owner=checkNotNull(communicationIdentity(characters,checkNotNull(currentCharacter(identity.account)).id.toInt(),navigation?.snapshot()))
                        require(owner.account==identity.account)
                        pendingWorld=communication.submit(connection,owner,checkNotNull(generation),body,gameSeconds())
                        return null
                    }
                    0x0f08 -> {
                        check(gameStarted)
                        if(communication!=null) {
                            check(worldInitialized)
                            val owner=checkNotNull(communicationIdentity(characters,checkNotNull(currentCharacter(identity.account)).id.toInt(),navigation?.snapshot()))
                            require(owner.account==identity.account)
                            pendingWorld=communication.submit(connection,owner,checkNotNull(generation),body,gameSeconds())
                            return null
                        }
                        require(body.size==28)
                        // This local world has no mail yet; finish the client's periodic inbox query.
                        LoginMessages.sessionMessage(0x0f09, byteArrayOf(0))
                    }
                    0x0200 -> { check(!gameLoggedIn)
                        refuseGameAdmissionForMaintenance()?.let { return it }
                        gameLoggedIn = true
                        countrySelection=characters.applicationSelection(identity.account)
                        if(countrySelection!=null)countrySelectionMode=org.logh7.protocol.CountrySelectionMessages.LOTTERY
                        LobbyMessages.gameLoginOk() }
                    0x1008 -> { check(gameLoggedIn && !gameStarted && countrySelectionMode!=org.logh7.protocol.CountrySelectionMessages.LOTTERY)
                        characters.accept(identity.account, CharacterMessages.generate(body),selectedCountry(),connection) }
                    0x1000 -> { check(gameLoggedIn && !gameStarted && body.size == 2); OriginalCharacterMessages.account(characters.fame(identity.account), characters.entries(identity.account)) }
                    0x1200 -> {
                        if(gameStarted) {
                            check(worldInitialized && world!=null && stock!=null)
                            if(org.logh7.protocol.GatheringMessages.matches(body)) {
                                check(administration!=null)
                                val q=org.logh7.protocol.GatheringMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account));require(q.character==character.id.toInt())
                                val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    q.character,currentProfile(identity.account,q.character).country,
                                    checkNotNull(generation),q.command,0,character.rank,target=q.target,value=q.value,
                                    identities=WorldCharacters.identities(characters.profiles(),navigation?.snapshot())),q.phase,q.ticket)
                                pendingWorld=administration.social(request).thenApply {result ->
                                    val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    val now=gameSeconds()
                                    val view=result.gathering?.let {g->org.logh7.protocol.GatheringMessages.View(g.ticket,g.target,g.friendship,g.hostWait,
                                        g.invite?.host ?: 0,g.invite?.let {(it.expiresAt-now).coerceAtLeast(0)} ?: 0,
                                        g.grade?.let {it.ordinal+1} ?: 0,g.hostInfluence,g.guestInfluence,g.friendshipDelta)}
                                    // A new invitation reaches the guest's live connections at once.
                                    val opened=result.gathering?.invite?.takeIf {result.changed && q.value==0}
                                    // The host paid on acceptance; refresh its own CP display.
                                    result.navigators.values.forEach {host->currentCharacterById(host.characterId)?.let {c->
                                        presence?.refreshCharacter(c,host,offices(c,host),now,administration.stationedOutfit(host))}}
                                    if(opened!=null)presence?.toCharacter(opened.guest,checkNotNull(generation),
                                        listOf(org.logh7.protocol.GatheringMessages.invited(opened.guest,opened.command,opened.host,(opened.expiresAt-now).coerceAtLeast(0))))
                                    org.logh7.protocol.GatheringMessages.response(q,result.rejection?.let {it.ordinal+1} ?: 0,result.changed,view)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.MeetingMessages.matches(body)) {
                                check(administration!=null)
                                val q=org.logh7.protocol.MeetingMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account));require(q.character==character.id.toInt())
                                val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    q.character,currentProfile(identity.account,q.character).country,
                                    checkNotNull(generation),org.logh7.engine.MeetingActions.MEETING,0,character.rank,target=q.target),q.phase,q.ticket)
                                pendingWorld=administration.social(request).thenApply {result ->
                                    val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    val view=result.meeting?.let {org.logh7.protocol.MeetingMessages.View(it.ticket,it.target,it.friendship,it.wait,it.gained)}
                                    org.logh7.protocol.MeetingMessages.response(q,result.rejection?.let {it.ordinal+1} ?: 0,result.changed,view)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.AcademyMessages.matches(body)) {
                                check(administration!=null)
                                val q=org.logh7.protocol.AcademyMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account));require(q.character==character.id.toInt())
                                val profile=org.logh7.engine.StrategicPerson(rank=character.rank,
                                    peerage=org.logh7.engine.Peerage.entries.getOrNull(character.title-1),merit=character.merit,
                                    influence=character.influence,evaluation=character.evaluation,money=character.money,
                                    abilities=character.abilities,decorations=character.decorations)
                                val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    q.character,currentProfile(identity.account,q.character).country,
                                    checkNotNull(generation),q.command,q.card,character.rank,argument=q.argument),q.phase,q.ticket,profile)
                                pendingWorld=administration.social(request).thenApply {result ->
                                    val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    val view=result.academy?.let {org.logh7.protocol.AcademyMessages.View(it.ticket,it.base,it.content,
                                        it.lectureRemaining,it.usesLeft,it.ability,it.value,it.experience,it.wait,it.gained)}
                                    org.logh7.protocol.AcademyMessages.response(q,result.rejection?.let {it.ordinal+1} ?: 0,result.changed,view)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.SocialMessages.matches(body)) {
                                check(administration!=null)
                                val q=org.logh7.protocol.SocialMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account));require(q.character==character.id.toInt())
                                val profile=org.logh7.engine.StrategicPerson(rank=character.rank,
                                    peerage=org.logh7.engine.Peerage.entries.getOrNull(character.title-1),merit=character.merit,
                                    influence=character.influence,evaluation=character.evaluation,money=character.money,
                                    abilities=character.abilities,decorations=character.decorations)
                                val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    q.character,currentProfile(identity.account,q.character).country,
                                    checkNotNull(generation),14,0,character.rank,politics=character.abilities[1],administration=character.abilities[2]),
                                    q.phase,q.ticket,profile)
                                pendingWorld=administration.social(request).thenApply {result ->
                                    val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    val v=result.view;val last=v?.last
                                    val view=v?.let {org.logh7.protocol.SocialMessages.View(it.ticket,it.base,it.influence,it.approval,it.wait,
                                        last?.id ?: 0,last?.at ?: 0,last?.base ?: 0,last?.influenceDelta ?: 0,last?.approvalDelta ?: 0)}
                                    if(result.changed && v!=null)presence?.refreshSocialBase(world,checkNotNull(result.stocks),v.base)
                                    org.logh7.protocol.SocialMessages.response(q,result.rejection?.let {it.ordinal+1} ?: 0,result.changed,view)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        (if(v!=null && v.base>0)listOf(world.conditions(listOf(v.base),result.stocks)) else emptyList())+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.BudgetMessages.matches(body)) {
                                check(administration!=null)
                                val q=org.logh7.protocol.BudgetMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account));require(q.character==character.id.toInt())
                                val request=org.logh7.engine.BudgetRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    q.character,currentProfile(identity.account,q.character).country,
                                    checkNotNull(generation),q.command,q.card,character.rank,
                                    politics=character.abilities[1],administration=character.abilities[2]),q.phase,q.base,q.rate,q.quote)
                                pendingWorld=administration.budget(request).thenApply {result ->
                                    val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    val v=result.view;val quote=v?.quote
                                    val view=v?.let {org.logh7.protocol.BudgetMessages.View(it.base,it.national,it.local,it.remittance,
                                        quote?.id ?: 0,quote?.amount ?: 0,quote?.phase?.ordinal?.plus(1) ?: 0,quote?.rate ?: 0)}
                                    org.logh7.protocol.BudgetMessages.response(q,result.rejection?.let {it.ordinal+1} ?: 0,view)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        (if(v!=null)listOf(world.conditions(listOf(v.base),result.stocks)) else emptyList())+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.OutfitFormationMessages.matches(body)) {
                                check(administration!=null)
                                val q=org.logh7.protocol.OutfitFormationMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account))
                                require(q.character==character.id.toInt())
                                val request=org.logh7.engine.OutfitFormationRequest(org.logh7.engine.StrategyActionRequest(
                                    identity.account,connection,q.character,currentProfile(identity.account,q.character).country,
                                    checkNotNull(generation),q.command,q.card,character.rank,
                                    politics=character.abilities[1],administration=character.abilities[2]),
                                    org.logh7.engine.FormationPhase.entries[q.phase],q.base,
                                    org.logh7.engine.OutfitKind.entries.single {it.wire==q.kind},q.target,q.unit,q.amount)
                                pendingWorld=administration.formOutfit(request).thenApply {result ->
                                    val player=checkNotNull(result.player);val inventory=checkNotNull(result.stocks)
                                    val updated=checkNotNull(currentCharacter(identity.account))
                                    publication=Publication(updated,player,false)
                                    if(result.changed) {
                                        for(id in result.affected.filter {it!=q.character})currentCharacterById(id)?.let {target ->
                                            navigation?.snapshot()?.navigators?.values?.singleOrNull {it.characterId==id}?.let {targetPlayer ->
                                                presence?.refreshCharacter(target,targetPlayer,offices(target,targetPlayer),gameSeconds(),
                                                    administration.stationedOutfit(targetPlayer))
                                            }
                                        }
                                        presence?.refreshOutfits(world,inventory,player.grid,connection)
                                    }
                                    val directory=result.rejection==null && !result.changed && q.phase !in setOf(3,4,6)
                                    val rows=if(directory)result.items.map {item ->
                                        val name=if(q.command==26)inventory.outfits.getValue(item.code).let {
                                            "${when(it.kind){org.logh7.engine.OutfitKind.FLEET->"함대";org.logh7.engine.OutfitKind.TRANSPORT->"수송";
                                                org.logh7.engine.OutfitKind.PATROL->"순찰";org.logh7.engine.OutfitKind.GROUND->"육전"}} ${it.number}"
                                        } else org.logh7.engine.OutfitFormationPolicy.stack(item.code).let {(troop,stack) ->
                                            val label=if(troop)org.logh7.engine.UnitSpecifications.crews.single {it.kind==stack.kind}.manual.unit
                                                else org.logh7.engine.UnitSpecifications.ships.single {it.kind==stack.kind}.name
                                            "${KoreanDisplayNames.catalogue(label).take(8)} ${stack.level}급"
                                        }
                                        org.logh7.protocol.OutfitFormationMessages.Row(item.code,name.take(12),item.available,item.selected)
                                    } else null
                                    org.logh7.protocol.OutfitFormationMessages.response(q,result.rejection?.let {it.ordinal+1} ?: 0,
                                        result.changed,if(result.changed)result.outfit else result.lease,result.base,result.supplies,result.crew,rows)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        (if(result.changed)inventory.outfits.values.filter {it.country==player.country &&
                                                it.positionIn(world.galaxy).grid==player.grid}.map {it.id}.sorted().chunked(100)
                                                .map {world.outfits(it,inventory)}.ifEmpty {listOf(world.outfits(listOf(result.outfit),inventory))}+
                                            listOf(world.gridOutfits(org.logh7.protocol.OutfitMessages.GridQuery(player.grid,character.power,0),inventory,player.country),
                                            world.warehouse(result.base,inventory))+presence?.outfitListRefresh(connection).orEmpty() else emptyList())+
                                        result.recovery?.let {completedWork(it.copy(player=player),updated)}.orEmpty()
                                }
                                return null
                            }
                            if(org.logh7.protocol.StrategyActionMessages.matches(body)) {
                                check(administration!=null)
                                val query=org.logh7.protocol.StrategyActionMessages.query(body)
                                val character=checkNotNull(currentCharacter(identity.account))
                                require(query.character==character.id.toInt())
                                if(query.preview) {
                                    pendingWorld=stocks().thenApply {stored ->
                                        val inventory=checkNotNull(stored)
                                        val policy=org.logh7.engine.OrganizationalOfficePolicy(world.galaxy)
                                        if(query.command==87) {
                                            // 会見 directory: people whose saved place is the requester's exact spot (online or not).
                                            val players=navigation?.snapshot()?.navigators.orEmpty()
                                            val me=checkNotNull(players[identity.account])
                                            val here=players.values.filter {it.generation==me.generation && it.characterId!=me.characterId &&
                                                it.characterId>query.target && me.spotId!=0 && it.grid==me.grid && it.baseId==me.baseId &&
                                                it.spotId==me.spotId && it.spotOwner==me.spotOwner}.map {it.characterId}.toSet()
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,
                                                characters.registeredCharacters().filter {it.id.toInt() in here}.map {currentCharacterById(it.id.toInt()) ?: it}
                                                    .sortedBy {it.id}.take(200).map {org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)})
                                        }
                                        if(query.command in 10..13) {
                                            // 会談/談話 directory: same-side people stationed at the requester's base (online or not).
                                            val players=navigation?.snapshot()?.navigators.orEmpty()
                                            val me=checkNotNull(players[identity.account])
                                            val here=players.values.filter {it.generation==me.generation && it.characterId!=me.characterId &&
                                                it.characterId>query.target && me.baseId!=0 && me.country!=null && it.country==me.country && it.grid==me.grid &&
                                                it.baseId==me.baseId}.map {it.characterId}.toSet()
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,
                                                characters.registeredCharacters().filter {it.id.toInt() in here}.map {currentCharacterById(it.id.toInt()) ?: it}
                                                    .sortedBy {it.id}.take(200).map {org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)})
                                        }
                                        if(query.command==org.logh7.engine.SuccessionActions.DESIGNATE) {
                                            // 後継指名: living empire characters other than the emperor, in the current line of succession.
                                            val ids=WorldCharacters.identities(characters.profiles(),navigation?.snapshot())
                                            val country=currentProfile(identity.account,character.id.toInt()).country
                                            val line=country?.let {org.logh7.engine.SuccessionActions.order(inventory,ids,it)}.orEmpty()
                                                .filter {it!=character.id.toInt() && it>query.target}
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,line.take(200).mapNotNull {id->
                                                currentCharacterById(id)?.let {org.logh7.protocol.StrategyActionMessages.Selection(id,it.surname.take(12))}})
                                        }
                                        if(query.command==org.logh7.engine.ProductionActions.SHIP || query.command==org.logh7.engine.ProductionActions.RECRUIT) {
                                            // U-08: the unit kinds this side may order (ships for 35, troops for 36).
                                            val country=checkNotNull(WorldCharacters.profiles(characters.profiles(),navigation?.snapshot()).getValue(identity.account).country)
                                            val kinds=org.logh7.engine.ProductionActions.kinds(country,query.command==org.logh7.engine.ProductionActions.SHIP)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,kinds.filter {it.kind+1>query.target}.take(200)
                                                .map {org.logh7.protocol.StrategyActionMessages.Selection(it.kind+1,org.logh7.engine.ProductionActions.koreanName(it.name))})
                                        }
                                        if(query.command==org.logh7.engine.TradeActions.ROUTE) {
                                            // 航路貿易: own regular bases within 10 cells of the requester's base.
                                            val snapshot=navigation?.snapshot();val stocks=checkNotNull(snapshot?.planetStocks)
                                            val me=checkNotNull(snapshot?.navigators?.get(identity.account))
                                            val ids=org.logh7.engine.TradeActions.routeTargets(world.galaxy,stocks,me)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,ids.filter {it>query.target}.sorted().take(200)
                                                .map {id->org.logh7.protocol.StrategyActionMessages.Selection(id,world.galaxy.bases.singleOrNull {it.id==id}?.name?.take(12) ?: "$id")})
                                        }
                                        if(query.command==org.logh7.engine.TradeActions.CONVOY ||
                                            query.command==org.logh7.engine.TradeActions.DIPLOMACY && query.value==org.logh7.engine.TradeActions.INTELLIGENCE) {
                                            val snapshot=navigation?.snapshot()
                                            val country=currentProfile(identity.account,query.character).country
                                            val catalog=org.logh7.engine.CountryCatalogLoader.current()
                                            val ids=country?.let {org.logh7.engine.TradeActions.foreignTargets(world.galaxy,inventory,it,catalog,
                                                snapshot?.session?.eliminated?.keys.orEmpty())}.orEmpty()
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,ids.filter {it.value>query.target}.take(200)
                                                .map {org.logh7.protocol.StrategyActionMessages.Selection(it.value,catalog.countries.getValue(it.value).name.take(12))})
                                        }
                                        if(query.command in org.logh7.engine.GoalActions.commands) {
                                            // 国家目標: enemy-held bases; 統治目標: the planets this character governs.
                                            val stocks=checkNotNull(navigation?.snapshot()?.planetStocks)
                                            val country=currentProfile(identity.account,query.character).country
                                            val ids=if(query.command==org.logh7.engine.GoalActions.NATIONAL)
                                                stocks.bases.values.filter {it.country!=null && it.country!=country}.map {it.baseId}
                                                else org.logh7.engine.FundActions.governed(world.galaxy,stocks,character.id.toInt(),country,card=query.card)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,ids.filter {it>query.target}.sorted().take(200)
                                                .map {id->org.logh7.protocol.StrategyActionMessages.Selection(id,world.galaxy.bases.singleOrNull {it.id==id}?.name?.take(12) ?: "$id")})
                                        }
                                        if(query.command==org.logh7.engine.FundActions.FUNDS) {
                                            // 資金投入 targets: governed planets, the two confidence choices, or same-side candidates.
                                            val stocks=checkNotNull(navigation?.snapshot()?.planetStocks)
                                            val country=currentProfile(identity.account,query.character).country
                                            val peers=WorldCharacters.identities(characters.profiles(),navigation?.snapshot())
                                                .filter {country!=null && it.country==country}.map {it.id}.toSet()
                                            val rows=when(query.value) {
                                                0 -> org.logh7.engine.FundActions.governed(world.galaxy,stocks,character.id.toInt(),country).filter {it>query.target}
                                                    .map {id->org.logh7.protocol.StrategyActionMessages.Selection(id,world.galaxy.bases.singleOrNull {it.id==id}?.name?.take(12) ?: "$id")}
                                                1 -> listOf(1 to "지지",2 to "불지지").filter {it.first>query.target}
                                                    .map {org.logh7.protocol.StrategyActionMessages.Selection(it.first,it.second)}
                                                else -> characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                                    .filter {it.id!=character.id && it.id>query.target && it.id.toInt() in peers}.sortedBy {it.id}
                                                    .map {org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)}
                                            }.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows)
                                        }
                                        if(query.command==org.logh7.engine.CoupActions.CONSPIRACY) {
                                            // 謀議: same-side people in the requester's spot who are not in a plot.
                                            val snapshot=navigation?.snapshot();val players=snapshot?.navigators.orEmpty()
                                            val me=checkNotNull(players[identity.account]);val coup=snapshot?.planetStocks?.social?.coup
                                            val here=players.values.filter {it.generation==me.generation && it.characterId!=me.characterId &&
                                                it.characterId>query.target && me.baseId!=0 && me.country!=null && it.country==me.country && it.baseId==me.baseId &&
                                                it.spotId==me.spotId && coup?.plotOf(it.characterId)==null}.map {it.characterId}.toSet()
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,
                                                characters.registeredCharacters().filter {it.id.toInt() in here}.map {currentCharacterById(it.id.toInt()) ?: it}
                                                    .sortedBy {it.id}.take(200).map {org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)})
                                        }
                                        if(query.command==org.logh7.engine.EspionageActions.INTRUDE || query.command==org.logh7.engine.EspionageActions.RETURN) {
                                            // U-17: bases within 10 grid cells, of another side (侵入) or this side (帰還).
                                            val me=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                                            val here=world.galaxy.bases.singleOrNull {it.id==me.baseId}
                                            val own=me.country
                                            val rows=if(here==null)emptyList() else world.galaxy.bases.filter {b->b.id!=here.id && b.id>query.target &&
                                                org.logh7.engine.EspionageActions.cells(here.grid,b.grid)<=org.logh7.engine.EspionageActions.RANGE_CELLS &&
                                                inventory.bases[b.id]?.country?.let {if(query.command==org.logh7.engine.EspionageActions.INTRUDE)own!=null && it!=own else it==own}==true}
                                                .sortedBy {it.id}.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows.map {
                                                org.logh7.protocol.StrategyActionMessages.Selection(it.id,it.name.take(12))})
                                        }
                                        if(query.command==org.logh7.engine.EspionageActions.INFILTRATE) {
                                            // U-17: the spots of this base's facilities (row id = spot, name = facility/room numbers).
                                            val me=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                                            val spots=if(me.baseId==0)emptyList() else world.galaxy.facilitiesAt(me.baseId,inventory)
                                                .flatMap {f->f.spots.map {sp->Triple(sp.id,f.kind,sp.kind)}}.filter {it.first>query.target && it.first!=me.spotId}
                                                .sortedBy {it.first}.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,spots.map {(id,kind,room)->
                                                org.logh7.protocol.StrategyActionMessages.Selection(id,"F$kind R$room")})
                                        }
                                        if(query.command in org.logh7.engine.Justice.commands) {
                                            // U-25 directories: 69 same-side people in the card's scope, 70 value 0 the arrest list /
                                            // value 1 same-side grantees, 71 listed people this character may arrest, 19 detainees in scope.
                                            val justice=inventory.social.justice;val now=gameSeconds()
                                            val snapshot=navigation?.snapshot()
                                            val own=currentProfile(identity.account,character.id.toInt()).country
                                                ?: return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,emptyList())
                                            val memberships=WorldCharacters.identities(characters.profiles(),snapshot).associate {it.id to it.country}
                                            val people=characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                                .filter {(if(query.command==org.logh7.engine.Justice.JUDGMENT)justice.detentions[it.id.toInt()]?.country==own
                                                    else memberships[it.id.toInt()]==own || query.command in setOf(68,73,74)) &&
                                                    it.id.toInt()!=character.id.toInt() && it.id.toInt()>query.target}
                                            fun rankOf(id:Int,fallback:Int)=inventory.strategic.people[id]?.rank ?: fallback
                                            val rows=when(query.command) {
                                                org.logh7.engine.Justice.PERMIT -> people.filter {org.logh7.engine.Justice.arrests(query.card,rankOf(it.id.toInt(),it.rank)) &&
                                                    !justice.detained(it.id.toInt())}
                                                org.logh7.engine.Justice.EXECUTION -> if(query.value==0)people.filter {justice.permits[it.id.toInt()]?.let {p->p.country==own && p.until>now}==true}
                                                    else people.filter {!justice.detained(it.id.toInt()) && it.id.toInt()!=query.base}
                                                org.logh7.engine.Justice.ARREST -> people.filter {p->justice.permits[p.id.toInt()]?.let {it.country==own && it.until>now}==true &&
                                                    (justice.rights[character.id.toInt()]?.let {it.target==p.id.toInt() && it.until>now &&
                                                        (it.country==own || it.country==null && own.value in 2..3)}==true ||
                                                        org.logh7.engine.Justice.arrests(query.card,rankOf(p.id.toInt(),p.rank)))}
                                                org.logh7.engine.Justice.SEARCH -> people
                                                org.logh7.engine.Justice.WATCH -> {
                                                    // The current watch target first (asking again reports its place), then the same spot.
                                                    val me=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                                                    val watched=justice.watches[character.id.toInt()]?.takeIf {it.country==own || it.country==null && own.value in 2..3}?.target
                                                    val here=navigation?.snapshot()?.navigators.orEmpty().values.filter {me.spotId!=0 && it.baseId==me.baseId &&
                                                        it.spotId==me.spotId && it.spotOwner==me.spotOwner}.map {it.characterId}.toSet()
                                                    people.filter {it.id.toInt()==watched || it.id.toInt() in here}
                                                }
                                                org.logh7.engine.Justice.RAID -> {
                                                    val me=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                                                    val here=navigation?.snapshot()?.navigators.orEmpty().values.filter {me.spotId!=0 && it.baseId==me.baseId &&
                                                        it.spotId==me.spotId && it.spotOwner==me.spotOwner && it.country!=own}.map {it.characterId}.toSet()
                                                    people.filter {it.id.toInt() in here}
                                                }
                                                else -> people.filter {p->justice.detentions[p.id.toInt()]?.let {!it.severe}==true &&
                                                    org.logh7.engine.Justice.judges(query.card,rankOf(p.id.toInt(),p.rank))}
                                            }.sortedBy {it.id}.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows.map {
                                                org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,minOf(20,rankOf(it.id.toInt(),it.rank)))})
                                        }
                                        if(query.command==org.logh7.engine.ProposalActions.OPEN) {
                                            val me=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                                            val recipientCountry=WorldCharacters.identities(characters.profiles(),navigation?.snapshot())
                                                .singleOrNull {it.id==query.base}?.country
                                            val sameCountry=me.country!=null && recipientCountry==me.country
                                            if(query.value==org.logh7.engine.ProposalActions.DISMISSAL) {
                                                // Seats the chosen recipient may dismiss; row id = person*512+card, name = surname.
                                                val recipient=currentCharacterById(query.base)
                                                val seats=if(recipient==null || !sameCountry)emptyList() else org.logh7.engine.OfficeDismissalPolicy(world.galaxy)
                                                    .available(inventory,query.base,checkNotNull(me.country).value,
                                                        org.logh7.engine.ProposalActions.rank(inventory,query.base,checkNotNull(me.country),recipient.rank))
                                                    .filter {it.target*512+it.card>query.target}.take(200)
                                                return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,seats.mapNotNull {seat->
                                                    currentCharacterById(seat.target)?.let {org.logh7.protocol.StrategyActionMessages.Selection(seat.target*512+seat.card,it.surname,
                                                        minOf(20,it.rank))}})
                                            }
                                            if(query.value==org.logh7.engine.ProposalActions.APPOINTMENT) {
                                                // Vacancies the chosen recipient may fill with this sender; row id = seat*512+card, name = seat base.
                                                val recipient=currentCharacterById(query.base)
                                                val rows=if(recipient==null || !sameCountry)emptyList() else org.logh7.engine.ProposalActions.vacancies(world.galaxy,inventory,
                                                    query.base,checkNotNull(me.country).value,org.logh7.engine.ProposalActions.rank(inventory,query.base,checkNotNull(me.country),recipient.rank),
                                                    me.characterId,org.logh7.engine.ProposalActions.rank(inventory,me.characterId,checkNotNull(me.country),character.rank))
                                                    .filter {it.second*512+it.first>query.target}.take(200)
                                                return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows.map {(card,seat)->
                                                    val baseId=inventory.outfits[seat]?.baseId ?: seat
                                                    org.logh7.protocol.StrategyActionMessages.Selection(seat*512+card,
                                                        (world.galaxy.bases.singleOrNull {it.id==baseId}?.name ?: "#$seat").take(12))})
                                            }
                                            val pairs=proposalPairs(inventory,me).filter {it.first>query.target}.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,pairs.mapNotNull {(id,_,_)->
                                                currentCharacterById(id)?.let {org.logh7.protocol.StrategyActionMessages.Selection(id,it.surname,it.rank)}})
                                        }
                                        val country=currentProfile(identity.account,query.character).country
                                        require(query.command==96 && query.card==0 || policy.held(inventory,character.id.toInt()).any {
                                            it.card==query.card && it.country==country}) {"Strategy directory requires a held office"}
                                        if(query.command==96) {
                                            val me=checkNotNull(navigation?.snapshot()?.navigators?.get(identity.account))
                                            if(query.argument==0)return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,
                                                org.logh7.engine.FlagshipMarketCatalog.options(me.shipStyle).map {option->
                                                    org.logh7.protocol.StrategyActionMessages.Selection(option.index+1,
                                                        "${checkNotNull(org.logh7.engine.Flagships.find(option.kind)).name} ${option.price}",option.maximumRank)})
                                            // U-13 guard ships: row id = catalogue index + 1, shown as "code price".
                                            if(query.argument==4)return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,
                                                org.logh7.engine.GuardShips.forStyle(org.logh7.engine.GuardShips.styleFor(inventory,character.id.toInt(),checkNotNull(me.country)))
                                                    .filter {it.index+1>query.target}.map {option->
                                                    org.logh7.protocol.StrategyActionMessages.Selection(option.index+1,option.label,option.maximumRank)})
                                            val eligible=if(query.argument==2)inventory.flagshipMarket.offers.filter {
                                                it.receiver==character.id.toInt() && it.expiresAt>gameSeconds()}.map {it.giver}.toSet() else null
                                            val candidates=WorldCharacters.profiles(characters.profiles(),navigation?.snapshot()).values
                                                .filter {country!=null && it.country==country}.map {currentCharacterById(it.character.id.toInt()) ?: it.character}.filter {
                                                it.id!=character.id && it.id>query.target && (it.rank==0 || it.rank in 2..20) &&
                                                    (eligible==null || it.id.toInt() in eligible)}.sortedBy {it.id}.take(200)
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,candidates.map {
                                                org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)})
                                        }
                                        if(query.command in setOf(29,65)) {
                                            // U-15 package rows: name "destination/supplies", "+" with units, "*" aboard.
                                            val outfit=if(query.command==65)inventory.outfits[query.outfit]?.takeIf {
                                                country!=null && it.country==country && it.commanderId==character.id.toInt()} else null
                                            val here=outfit?.positionIn(world.galaxy)?.baseId ?: 0
                                            val rows=inventory.strategic.transports.filter {it.country==country && it.id>query.target &&
                                                (if(query.command==29)it.carrier==0 else outfit!=null &&
                                                    (it.carrier==outfit.id || it.carrier==0 && it.source==here && here!=0))}
                                                .sortedBy {it.id}.take(200).map {p ->
                                                    org.logh7.protocol.StrategyActionMessages.Selection(p.id,("${p.destination}/${p.supplies}"+
                                                        (if(p.units.ships.isEmpty() && p.units.troops.isEmpty())"" else "+")+
                                                        (if(p.carrier!=0)"*" else "")).take(12))}
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows)
                                        }
                                        if(query.command==24) {
                                            val force=when(query.card) {
                                                28,169->0;29,170->1;30,171->2;else->error("Not an operation issuing office")
                                            }
                                            val solo=navigation?.snapshot()?.navigators.orEmpty().values.filter {
                                                it.generation==generation && it.country==country && it.hull?.hp?.let {hp->hp>0}==true &&
                                                    inventory.outfits.values.none {o->it.characterId in o.members()}}.map {it.characterId}.toSet()
                                            val rows=if(force==2)characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                                .filter {it.id.toInt() in solo && it.rank in 2..20}
                                                .map {org.logh7.protocol.StrategyActionMessages.Selection(it.id.toInt(),it.surname,it.rank)}
                                            else inventory.outfits.values.filter {it.country==country &&
                                                (if(force==0)it.kind==org.logh7.engine.OutfitKind.FLEET else it.kind in setOf(
                                                    org.logh7.engine.OutfitKind.TRANSPORT,org.logh7.engine.OutfitKind.PATROL))}
                                                .map {org.logh7.protocol.StrategyActionMessages.Selection(it.id,"부대 ${it.id}")}
                                            return@thenApply org.logh7.protocol.StrategyActionMessages.selections(query,rows
                                                .filter {it.id>query.target}.sortedBy {it.id}.take(200))
                                        }
                                        val peers=WorldCharacters.identities(characters.profiles(),navigation?.snapshot())
                                            .filter {country!=null && it.country==country}.map {it.id}.toSet()
                                        val candidates=characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                            .filter {it.id.toInt() in peers && it.id>query.target && it.id!=character.id}.sortedBy {it.id}.take(200)
                                        org.logh7.protocol.StrategyActionMessages.candidates(query,candidates.map {target ->
                                            org.logh7.protocol.AppointmentDirectoryMessages.Person(target,
                                                listOf(0,if(target.power==2)59 else 195)+administration.offices(target.id.toInt(),checkNotNull(country).value,
                                                    checkNotNull(generation)).map {it.kind})})
                                    }
                                    return null
                                }
                                if(query.command in org.logh7.engine.Justice.commands || query.command in org.logh7.engine.EspionageActions.commands ||
                                    query.command in org.logh7.engine.CoupActions.commands || query.command==org.logh7.engine.FundActions.FUNDS ||
                                    query.command in org.logh7.engine.GoalActions.commands || query.command in org.logh7.engine.TradeActions.commands ||
                                    query.command in org.logh7.engine.ProductionActions.commands || query.command in org.logh7.engine.OrbitalStrikeActions.commands ||
                                    query.command in org.logh7.engine.SuccessionActions.commands) {
                                    val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                        query.character,currentProfile(identity.account,query.character).country,
                                        checkNotNull(generation),query.command,query.card,character.rank,target=query.target,value=query.value,
                                        base=query.base,amount=query.amount.toLong(),identities=WorldCharacters.identities(characters.profiles(),navigation?.snapshot())))
                                    pendingWorld=administration.social(request).thenApply {result ->
                                        val player=checkNotNull(result.player)
                                        val destination=result.defected
                                        if(destination!=null) {
                                            // U-25 defection: the registered character and its mail identity follow the committed
                                            // world and session; this connection then ends so the client reconnects on the new side.
                                            check(characters.defect(identity.account,character.id,destination,player.shipStyle))
                                            communicationIdentity(characters,character.id.toInt(),navigation?.snapshot())?.let {communication?.defect(it,checkNotNull(generation))?.join()}
                                            defectedLogout=true
                                        }
                                        if(query.command==org.logh7.engine.CoupActions.CONSPIRACY && result.outcome==3 && result.found>0) {
                                            // 謀議 refused: the target learns who asked (user decision 2026-11-01).
                                            val from=communicationIdentity(characters,character.id.toInt(),navigation?.snapshot());val to=communicationIdentity(characters,result.found,navigation?.snapshot())
                                            if(from!=null && to!=null)communication?.notice(from,to,"모의 제안",
                                                "${from.name}이(가) 쿠데타 참가를 제안했습니다. 거절했습니다.",checkNotNull(generation),gameSeconds())
                                        }
                                        val updated=checkNotNull(currentCharacter(identity.account))
                                        publication=Publication(updated,player,false)
                                        refreshPaid(result.navigators)
                                        // U-31: the orbital ground attack button has no window, so its result is a tactical chat line.
                                        val strike=if(query.command!=org.logh7.engine.OrbitalStrikeActions.STRIKE)emptyList() else listOf(
                                            org.logh7.protocol.ChatMessages.grid(org.logh7.protocol.ChatMessages.Grid(character.id.toInt(),1,
                                                // The CD's latest-line box shows one short line only.
                                                (if(result.outcome>=1)"[대지] "+KoreanDisplayNames.base(checkNotNull(world).galaxy.bases.single {it.id==result.found}.name).take(6)+
                                                    " 수비 -${org.logh7.engine.OrbitalStrikeActions.DAMAGE*result.outcome}"+(if(result.report>0)" 격멸${result.report}" else "")+
                                                    // CD p.46: a fortress also shows its remaining armor (FortressArmor).
                                                    (result.stocks?.takeIf {checkNotNull(world).galaxy.bases.single {it.id==result.found}.kind==1}?.let {
                                                        " 장갑 ${org.logh7.engine.FortressArmor.remaining(it,result.found)}"} ?: "")
                                                else "[대지] "+when(result.rejection) {
                                                    org.logh7.engine.SocialRejection.LOCATION->"전술 중만"
                                                    org.logh7.engine.SocialRejection.TARGET->"사정 밖"
                                                    org.logh7.engine.SocialRejection.MAXIMUM->"물자 부족"
                                                    org.logh7.engine.SocialRejection.COOLDOWN->"지상전 중"
                                                    else->"불가"}).take(65)),gameSeconds()))
                                        if(strike.isNotEmpty() && result.outcome>=1)navigation?.snapshot()?.let {snapshot->
                                            presence?.groundRefresh(checkNotNull(generation),player.grid,result.found,gameSeconds(),snapshot,
                                                checkNotNull(world).galaxy,::currentCharacterById)}
                                        // The strike's outcome is its attacker count (1..10); the native reply only carries success.
                                        org.logh7.protocol.StrategyActionMessages.response(query,result.rejection?.let {it.ordinal+1} ?: 0,result.changed,
                                            outcome=if(strike.isNotEmpty())minOf(result.outcome,1) else result.outcome,found=result.found,report=result.report)+
                                            listOf(characterMessage(updated,player,true))+strike
                                    }
                                    return null
                                }
                                if(query.command==org.logh7.engine.ProposalActions.OPEN) {
                                    // The compatibility window may leave both cards 0: use the target's pair.
                                    val pair=if(query.card!=0 && query.argument!=0)Triple(query.target,query.card,query.argument) else
                                        navigation?.snapshot()?.let {s->proposalPairs(checkNotNull(s.planetStocks),checkNotNull(s.navigators[identity.account]))}
                                            ?.firstOrNull {it.first==query.target} ?: Triple(query.target,0,0)
                                    val request=org.logh7.engine.SocialRequest(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                        query.character,currentProfile(identity.account,query.character).country,
                                        checkNotNull(generation),query.command,pair.second,character.rank,target=query.target,value=query.value,
                                        argument=pair.third,base=query.base,amount=query.amount.toLong()))
                                    pendingWorld=administration.social(request).thenApply {result ->
                                        val player=checkNotNull(result.player);val updated=checkNotNull(currentCharacter(identity.account))
                                        publication=Publication(updated,player,false)
                                        refreshPaid(result.navigators)
                                        // The CD has no closed-mailbox alert for office rows (only opening re-queries them):
                                        // tell an online recipient in the chat log (design-proposals-orders.md, evidence:guess).
                                        if(result.rejection==null && result.changed)presence?.toCharacter(query.target,checkNotNull(generation),
                                            listOf(org.logh7.protocol.ChatMessages.spot(org.logh7.protocol.ChatMessages.Spot(query.target,
                                                ProposalNotice.arrived(character.surname)),gameSeconds())))
                                        org.logh7.protocol.StrategyActionMessages.response(query,result.rejection?.let {it.ordinal+1} ?: 0,result.changed)+
                                            listOf(characterMessage(updated,player,true))
                                    }
                                    return null
                                }
                                pendingWorld=administration.strategyAction(org.logh7.engine.StrategyActionRequest(identity.account,connection,
                                    query.character,currentProfile(identity.account,query.character).country,
                                    checkNotNull(generation),query.command,query.card,character.rank,query.outfit,
                                    character.abilities[1],character.abilities[2],query.target,query.base,query.value,
                                    identities=WorldCharacters.identities(characters.profiles(),navigation?.snapshot()),amount=query.amount.toLong(),argument=query.argument,
                                    requestToken=java.nio.ByteBuffer.wrap(query.raw).getShort(27).toInt() and 65535)).thenApply {result ->
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
                                    val civic=if(query.command in org.logh7.engine.CivicActions.supported)
                                        org.logh7.protocol.StrategyActionMessages.CivicStatus(result.civic?.phase?.ordinal ?: -1,
                                            result.civic?.outfit ?: 0,result.civic?.base ?: player.baseId,
                                            result.civic?.readyAt ?: 0,result.civic?.effect ?: 0) else null
                                    val civicMembers=if(civic==null)emptyMap() else
                                        WorldCharacters.identities(characters.profiles(),navigation?.snapshot()).associateBy {it.id}
                                    val civicOutfit=result.civic?.outfit?.let {result.stocks?.outfits?.get(it)}?.takeIf {outfit ->
                                        player.country!=null && outfit.country==player.country && outfit.members().all {civicMembers[it]?.country==player.country}}
                                    org.logh7.protocol.StrategyActionMessages.response(query,result.rejection?.let {it.ordinal+1} ?: 0,result.changed,civic)+
                                        listOf(characterMessage(updated,player,true),unitMessage(updated,player))+
                                        (if(civic!=null && civic.base>0)listOf(world.conditions(listOf(civic.base),result.stocks))+
                                            (if(result.stocks?.bases?.get(civic.base)?.let {b->player.country?.let(b::belongsTo)==true && b.camp==0}==true)
                                                listOf(world.warehouse(civic.base,result.stocks)) else emptyList())+
                                            (if(civicOutfit!=null)
                                                listOf(world.outfitParty(org.logh7.protocol.OutfitPartyMessages.Query(civicOutfit.id,0,0),
                                                    checkNotNull(currentProfile(identity.account,character.id.toInt()).country).value,checkNotNull(result.stocks),character.id.toInt(),::currentCharacterById)) else emptyList()) else emptyList())+
                                        // U-13: guard purchase/disband changes this grid's outfits; refresh like outfit formation.
                                        (if(query.command==96 && query.argument>=4 && result.changed)result.stocks?.let {inventory ->
                                            inventory.outfits.values.filter {it.country==player.country && it.positionIn(world.galaxy).grid==player.grid}
                                                .map {it.id}.sorted().chunked(100).map {world.outfits(it,inventory)}+
                                            listOf(world.gridOutfits(org.logh7.protocol.OutfitMessages.GridQuery(player.grid,character.power,0),inventory,player.country))+
                                            presence?.outfitListRefresh(connection).orEmpty()}.orEmpty() else emptyList())+
                                        // U-15: refresh the warehouse a transport command changed.
                                        (if(query.command in org.logh7.engine.TransportActions.supported && result.changed)result.stocks?.let {inventory ->
                                            listOf(result.warehouseBase).filter {
                                                inventory.bases[it]?.let {b->player.country?.let(b::belongsTo)==true && b.camp==0}==true}.map {world.warehouse(it,inventory)}}.orEmpty()
                                        else emptyList())+
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
                                    val scope=SupplyDirectoryScope(world.galaxy,currentProfile(identity.account,character.id.toInt()).country,
                                        world.lobbyCountryDefinitions().first)
                                    val units=scope.supplement(inventory,character.id.toInt(),query.kind)
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
                                    val scope=SupplyDirectoryScope(world.galaxy,currentProfile(identity.account,character.id.toInt()).country,
                                        world.lobbyCountryDefinitions().first)
                                    val allowed=scope.supply(inventory,character.id.toInt(),query.grid)
                                            .map { outfit -> org.logh7.protocol.SupplyDirectoryMessages.Outfit(outfit.id,
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
                                    val country=currentProfile(identity.account,character.id.toInt()).country
                                    val offices=org.logh7.engine.LogisticsOfficePolicy(world.galaxy)
                                    val allowed=inventory.outfits.values.filter {outfit ->
                                        val base=inventory.bases[outfit.baseId]
                                        country!=null && outfit.country==country && base?.belongsTo(country)==true && base.camp==0 &&
                                            offices.permits(inventory,character.id.toInt(),country,outfit.kind,
                                                if(query.search==32)null else query.card)
                                    }
                                    if(query.search==32) {
                                        val bases=allowed.map { it.baseId }.distinct().sorted()
                                            .map { id -> world.baseWire(world.galaxy.bases.single { it.id==id }) }
                                        org.logh7.protocol.LogisticsDirectoryMessages.bases(query,bases)
                                    } else {
                                        val outfits=allowed.filter { it.baseId==query.base }.sortedBy { it.id }
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
                                // The actual profile country and its rank system, not the native display code.
                                val scope=AppointmentDirectoryScope(world.galaxy,currentProfile(identity.account,character.id.toInt()).country)
                                val policy=scope.policy
                                val actorRank=scope.rank(inventory,character.id.toInt(),character.rank)
                                val available=scope.available(inventory,character.id.toInt(),character.rank)
                                val role=scope.role(query.role)
                                val selected=available[query.role].orEmpty()
                                val dismissals=scope.dismissals(inventory,character.id.toInt(),character.rank)
                                val countryOf by lazy {
                                    WorldCharacters.identities(characters.profiles(),navigation?.snapshot()).associate {it.id to it.country}
                                }
                                when(query.search) {
                                    // The new-mail office tab: the CD's office mailbox shows only 命令/提案 mail (0f13/0f14), so
                                    // ordinary mail to an office address could never be read there. Answer an empty directory
                                    // instead of the old unsupported-search disconnect (design-proposals-orders.md).
                                    15 -> org.logh7.protocol.AppointmentDirectoryMessages.orderSuggest(query,emptyList())
                                    // 命令/提案 targets (CD p.28): same side; same spot inside a facility, else the same grid.
                                    // One group per own office card; targets hold a card of the same institution. A higher
                                    // rank receives a proposal (1), others an order (0); the emperor orders everyone (evidence:guess).
                                    29 -> {
                                        val players=navigation?.snapshot()?.navigators.orEmpty()
                                        val me=checkNotNull(players[identity.account])
                                        val country=me.country
                                        val here=players.values.filter {it.generation==me.generation && it.characterId!=me.characterId &&
                                            country!=null && it.country==country && !it.cpPaused && it.grid==me.grid &&
                                            (me.spotId==0 || it.baseId==me.baseId && it.spotId==me.spotId && it.spotOwner==me.spotOwner)}
                                            .map {it.characterId}.toSet()
                                        fun cards(id:Int)=country?.let {administration!!.offices(id,it.value,checkNotNull(generation)).map {it.kind}}.orEmpty()
                                        fun institution(card:Int)=country?.let {org.logh7.engine.OrganizationOffices.of(card,it)?.institution}
                                        val people=characters.registeredCharacters().filter {it.id.toInt() in here}.map {currentCharacterById(it.id.toInt()) ?: it}
                                            .sortedBy {it.id}.map {it to cards(it.id.toInt())}
                                        val own=cards(character.id.toInt()).filter {institution(it)!=null}.distinct()
                                        org.logh7.protocol.AppointmentDirectoryMessages.orderSuggest(query,own.take(10).map {card ->
                                            val group=institution(card)
                                            org.logh7.protocol.AppointmentDirectoryMessages.OrderSuggest(card,people.filter {(_,held)->
                                                card==1 || held.any {institution(it)==group}}.take(10).map {(target,held)->
                                                val targetRank=org.logh7.engine.ProposalActions.rank(inventory,target.id.toInt(),checkNotNull(country),target.rank)
                                                val actorRank=org.logh7.engine.ProposalActions.rank(inventory,character.id.toInt(),checkNotNull(country),character.rank)
                                                val proposal=card!=1 && targetRank in 1..20 && actorRank in 1..20 && targetRank<actorRank
                                                (if(proposal)1 else 0) to org.logh7.protocol.AppointmentDirectoryMessages.Person(target,
                                                    listOf(0,if(target.power==2)59 else 195)+held)})
                                        }.filter {it.targets.isNotEmpty()})
                                    }
                                    18 -> {
                                        val vacancies=scope.vacancies(inventory,available)
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
                                        val country=checkNotNull(scope.country)
                                        val eligible=characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}.filter {target ->
                                            target.id!=character.id && countryOf[target.id.toInt()]==country &&
                                                role.eligible(actorRank,scope.rank(inventory,target.id.toInt(),target.rank)) &&
                                                policy.cardCount(inventory,target.id.toInt())<14
                                        }
                                        org.logh7.protocol.AppointmentDirectoryMessages.people(query,eligible.map {target ->
                                            org.logh7.protocol.AppointmentDirectoryMessages.Person(target,listOf(0,scope.captainCard())+
                                                administration!!.offices(target.id.toInt(),country.value,checkNotNull(generation)).map {it.kind})
                                        })
                                    }
                                    5 -> {
                                        val ids=dismissals.filter {it.card==query.role}.map {it.target}.toSet()
                                        val country=scope.country
                                        org.logh7.protocol.AppointmentDirectoryMessages.people(query,
                                            characters.registeredCharacters().map {currentCharacterById(it.id.toInt()) ?: it}
                                                .filter {it.id.toInt() in ids && country!=null && countryOf[it.id.toInt()]==country}.map {target ->
                                                org.logh7.protocol.AppointmentDirectoryMessages.Person(target,listOf(0,scope.captainCard())+
                                                    administration!!.offices(target.id.toInt(),checkNotNull(country).value,checkNotNull(generation)).map {it.kind})
                                            })
                                    }
                                    else -> error("Unsupported appointment directory")
                                }
                            }
                            return null
                        }
                        if(traceOpcodes && !OriginalCharacterMessages.catalogueRequest(body))System.err.println("trace ${identity.account} 1200 body ${java.util.HexFormat.of().formatHex(body)}")
                        check(gameLoggedIn && OriginalCharacterMessages.catalogueRequest(body))
                        val messages = listOf(LoginMessages.sessionMessage(0x1200, body.copyOfRange(2, body.size)),
                            OriginalCharacterMessages.catalogue(characters.candidates(identity.account,selectedCountry())), LoginMessages.sessionMessage(0x1201, byteArrayOf()))
                        return handshake.stageAll(messages, clearHeader = 0)
                    }
                    0x1004 -> {
                        check(gameLoggedIn && !gameStarted)
                        val id = OriginalCharacterMessages.entryId(body)
                        characters.entryState(identity.account, id,selectedCountry())
                    }
                    0x1006 -> { check(gameLoggedIn && !gameStarted && countrySelectionMode!=org.logh7.protocol.CountrySelectionMessages.CREATE)
                        characters.apply(identity.account, OriginalCharacterMessages.application(body),selectedCountry()) }
                    0x0205 -> {
                        check(gameLoggedIn && !gameStarted && !joining && body.size == 2)
                        // A game connection can authenticate before maintenance and request entry afterwards.
                        refuseGameAdmissionForMaintenance()?.let { return it }
                        val character = checkNotNull(currentCharacter(identity.account))
                        val profile=WorldCharacters.profiles(characters.profiles(),navigation?.snapshot()).getValue(identity.account)
                        if(!profile.canEnterNativeCountry(world?.lobbyCountryDefinitions()?.first ?: org.logh7.engine.CountryCatalogLoader.current())) {
                            state=State.REJECTED
                            return handshake.stageAll(listOf(LoginMessages.sessionRejection()),clearHeader=0)
                        }
                        if (admission != null) {
                            joining = true; joinRequested = true
                            pendingJoin = admission.join(identity.account, profile.copy(character=character), connection, checkNotNull(generation))
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
                            pendingWorld = stocks().thenCompose { stored -> navigate(request).thenApply { result ->
                                val player = checkNotNull(result.player) { "Navigation initialization rejected: ${result.rejection}" }
                                val captured=navigation.snapshot()
                                publication = Publication(character, player, true,opcode,captured?.revision)
                                val tactical=if(player.cpPaused && world!=null)TacticalBootstrap(checkNotNull(captured),
                                    world.galaxy,player,::currentCharacterById) else null
                                tacticalResumeGrid=tactical?.grid
                                reservedWarpNotice(result.warpCancellation, player.characterId, gameSeconds()).singleOrNull()?.let { startupWarpNotice = it }
                                if (opcode == 0x0f02) startupGridReady = true
                                listOf(BootstrapMessages.characterId(character),
                                    characterMessage(character, player),
                                    unitMessage(character, player),
                                    org.logh7.protocol.NavigationMessages.grid(player.grid,player.cpPaused)) +
                                    world?.initialize(player.grid, directory = opcode == 0x0f00, stocks = stored).orEmpty() +
                                    tactical?.initialize(::offices).orEmpty() +
                                    listOf(checkNotNull(BootstrapMessages.response(body))) + pendingNavigation(player) + tactical?.resume().orEmpty()
                            } }.let {initialized ->
                                val owner=if(opcode==0x0f00)communicationIdentity(characters,character.id.toInt(),navigation?.snapshot()) else null
                                if(communication==null || owner==null)initialized
                                else initialized.thenCompose {messages ->
                                    communication.unreadInbox(owner,checkNotNull(generation)).thenApply {messages+it}}
                            }
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
                    if(sessionDirectory!=null) {
                        val entries=sessionDirectory.characters(identity.account)
                        // With no charged character, retain the default world's information for the native empty state.
                        val response=if(entries.isNotEmpty())LobbyMessages.characters(entries)
                            else LobbyMessages.characters(null,sessionDirectory.sessions().firstOrNull())
                        return handshake.stageAll(listOf(response),clearHeader=0)
                    }
                    val snapshot=navigation?.snapshot()
                    val current=characterProvider()
                    // U-11: a character killed in battle is removed here; the lobby then offers creation.
                    characters.character(identity.account)?.takeIf {characters===current && snapshot?.session?.generation==admission?.generation &&
                        org.logh7.engine.BattleDeath.deceased(snapshot?.planetStocks,it.id.toInt())}?.let {characters.retire(identity.account,it.id)}
                    val character=characters.character(identity.account)
                    // Lobby accounts may inspect their own ended character without joining,
                    // changing CP/navigation, or obtaining a playable ended-session ticket.
                    if(characters!==current)LobbyMessages.characters(null,
                        SessionEndingReport.information(snapshot,current.registeredCharacters(),sessionId,sessionName,
                            admission?.generation ?: 1L))
                    else if(character!=null && snapshot?.result!=null && snapshot.session?.generation==admission?.generation)
                        LobbyMessages.characters(character,SessionEndingReport.session(snapshot,characters.registeredCharacters()),
                            if(fame==null)org.logh7.protocol.SessionEndingMessages.Evaluation(0,0,0,0,0)
                            else fame.evaluation(snapshot,characters,identity.account))
                    else LobbyMessages.characters(if(admission?.running==false)null else character,
                        SessionEndingReport.information(snapshot,characters.registeredCharacters(),sessionId,sessionName,
                            admission?.generation ?: 1L))
                }
                0x2005 -> { check(lobbyLoggedIn && body.size == 3 && body[2].toInt() in 0..2)
                    sessionDirectory?.let {LobbyMessages.sessions(it.sessions())} ?: LobbyMessages.sessions(
                        admission?.running != false,
                        SessionEndingReport.information(navigation?.snapshot(),characterProvider().registeredCharacters(),
                            sessionId,sessionName,admission?.generation ?: 1L)) }
                0x032e -> {
                    if(body.size==11 && (body[10].toInt() and 255)==org.logh7.protocol.CountryEndingMessages.MODE) {
                        check(lobbyLoggedIn)
                        val request=org.logh7.protocol.CountryEndingMessages.Request(org.logh7.protocol.OutfitPartyMessages.query(body))
                        val frames=if(sessionDirectory!=null)sessionDirectory.endings(request,identity.account)
                            else if(request.session==sessionId)countryEndings?.invoke(request,identity.account) else null
                        return handshake.stageAll(frames ?: org.logh7.protocol.CountryEndingMessages.empty(request).map {
                            LoginMessages.sessionMessage(0x032f,it)},clearHeader=0)
                    }
                    check(lobbyLoggedIn)
                    if (body.size == 11 && (body[10].toInt() and 255) == CountryImageMessages.LOBBY_MODE) {
                        val request = CountryImageMessages.LobbyRequest(org.logh7.protocol.OutfitPartyMessages.query(body))
                        val frames = if (sessionDirectory != null) sessionDirectory.countryEmblem(request)
                            else if (request.sessionId == sessionId && admission?.running != false) world?.lobbyCountryEmblem(request) else null
                        return handshake.stageAll(frames ?: CountryImageMessages.lobbyReply(request, 0, 0, null), clearHeader = 0)
                    }
                    val request=org.logh7.protocol.LobbyCountryMessages.request(org.logh7.protocol.OutfitPartyMessages.query(body))
                    val frames=if(sessionDirectory!=null)sessionDirectory.countries(request)
                        else if(request.sessionId==sessionId && admission?.running!=false)lobbyCountries?.invoke(request) else null
                    return handshake.stageAll(frames ?: LobbyCountryCatalogue.empty(request),clearHeader=0)
                }
                0x2009 -> {
                    check(lobbyLoggedIn)
                    val selected=LobbyMessages.select(body)
                    val selection = sessionDirectory?.select(selected, bindHost) ?: when {
                        selected != sessionId || admission?.running == false ->
                            GameSessionDirectory.Refused(LobbyMessages.SelectionFailure.UNAVAILABLE)
                        maintenance?.closed() == true ->
                            GameSessionDirectory.Refused(LobbyMessages.SelectionFailure.MAINTENANCE)
                        else -> GameSessionDirectory.Target(sessionId, sessionAddress, sessionPort, admission?.generation ?: 1)
                    }
                    when (selection) {
                        is GameSessionDirectory.Target -> LobbyMessages.selected(selection.address, selection.port,
                            tickets.issue(identity.account, SessionTickets.Purpose.GAME, selection.id, selection.generation))
                        is GameSessionDirectory.Refused -> LobbyMessages.selectionFailed(selection.reason)
                    }
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
                // A maintenance refusal is counted by the gate only, so a planned closure does not trip the login-failure alert.
                val closed = maintenance?.refuses() == true
                val accepted = !closed && expected != null && AccountPassword.matches(expected, request.credential)
                if (!closed) (if (accepted) counters?.loginAccepted else counters?.loginRejected)?.incrementAndGet()
                val reply =if (accepted) LoginMessages.success(sessionAddress, sessionPort, tickets.issue(request.account,sessionId=sessionId)) else LoginMessages.failure()
                state = if (accepted) State.AUTHENTICATED else State.REJECTED
                handshake.stageAll(listOf(reply))
            }
            Role.SESSION -> {
                val taken = tickets.take(LoginMessages.sessionToken(body))
                if (taken == null || taken.sessionId != sessionId || (taken.generation != null && taken.generation != (generation ?: 1L)))
                    counters?.sessionTicketRejected?.incrementAndGet()
                ticket = requireNotNull(taken) { "Invalid or expired session token" }
                require(ticket!!.sessionId==sessionId && (ticket!!.generation==null || ticket!!.generation==(generation ?: 1L))) {"Session ticket destination or generation mismatch"}
                // The destination world's own gate (PR #283 review): a ticket issued by another world's open login
                // listener must not admit a new player here while this world is closed. Open connections stay.
                // A valid game ticket reaches SSLogin so a client can receive its maintenance reason.
                // No game request may run before that check. Lobby tickets keep their existing gate.
                if (taken.purpose == SessionTickets.Purpose.LOBBY)
                    check(maintenance?.refuses() != true) { "World closed for maintenance" }
                state = State.AUTHENTICATED
                null // No invented response: session acceptance opcode is not established yet.
            }
        }
    }
    fun close() {
        if (state == State.CLOSED) return
        if(ticket?.purpose==SessionTickets.Purpose.GAME)characters.cancelCreation(checkNotNull(ticket).account,connection)
        countrySelection=null;countrySelectionMode=0
        handshake.close(); state = State.CLOSED
        presence?.remove(connection)
        communication?.disconnected(connection)
        if (joinRequested) admission?.disconnect(checkNotNull(ticket).account, connection)
    }
}

fun loadTestAccounts(path: Path?): Map<String, String> {
    if (path == null) return emptyMap()
    val properties = Properties().also { Files.newBufferedReader(path).use(it::load) }
    return properties.stringPropertyNames().associateWith { properties.getProperty(it) }.also { accounts ->
        require(accounts.all { (name, password) -> name.length in 1..30 && !name.startsWith('@') &&
            (password.length <= 10 || AccountPassword.isHashed(password)) })
    }
}
