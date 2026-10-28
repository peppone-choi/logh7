package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

data class WorldSnapshot(val revision: Long, val acceptedCommands: Long, val session: SessionSnapshot? = null, val rejectedCommands: Long = 0,
    val navigators: Map<String, Navigator> = emptyMap(), val planetStocks: PlanetStocks? = null)
sealed interface WorldCommand {
    data object Probe : WorldCommand
    data class AdvanceTo(val elapsedRealMillis: Long) : WorldCommand
    data class Join(val account: String, val power: Power, val original: Boolean, val result: CompletableDeferred<Admission>, val connection: String? = null, val generation: Long? = null) : WorldCommand
    data class Disconnect(val account: String, val connection: String? = null) : WorldCommand
    data class Exclude(val account: String) : WorldCommand
    data class OpenBattle(val id: String) : WorldCommand
    data class CloseBattle(val id: String) : WorldCommand
    data class Territory(val systems: Map<Power, Int>, val capturedCapitals: Set<Power>) : WorldCommand
    data object Restart : WorldCommand
    data class RequestRestart(val result: CompletableDeferred<Boolean>) : WorldCommand
    data class Navigate(val request: NavigationRequest, val result: CompletableDeferred<NavigationResult>) : WorldCommand
    data class Stock(val generation: Long, val result: CompletableDeferred<PlanetStocks>) : WorldCommand
    data class Logistics(val request: LogisticsRequest, val result: CompletableDeferred<LogisticsResult>) : WorldCommand
    data class AppointGarrison(val request: GarrisonAppointmentRequest,val result: CompletableDeferred<GarrisonAppointmentResult>) : WorldCommand
    data class DismissOffice(val request:OfficeDismissalRequest,val result:CompletableDeferred<OfficeDismissalResult>) : WorldCommand
    data class ResignOffice(val request:OfficeResignationRequest,val result:CompletableDeferred<OfficeResignationResult>) : WorldCommand
    data class Tax(val request: TaxRequest, val result: CompletableDeferred<TaxResult>) : WorldCommand
    data class Reorganize(val request: ReorganizationRequest, val result: CompletableDeferred<ReorganizationResult>) : WorldCommand
    data class SupplyOutfit(val request: CompletenessSupplyRequest, val result: CompletableDeferred<CompletenessSupplyResult>) : WorldCommand
    data class TransferGround(val request: GroundTransferRequest, val result: CompletableDeferred<GroundTransferResult>) : WorldCommand
    data class SupplementOutfit(val request: SupplementRequest, val result: CompletableDeferred<SupplementResult>) : WorldCommand
    data class AssignStock(val request: StockAssignmentRequest, val result: CompletableDeferred<StockAssignmentResult>) : WorldCommand
}
interface CommandSink { suspend fun submit(command: WorldCommand) }
enum class EngineHealth { RUNNING, STORAGE_FAILED, STOPPED }
interface SnapshotSource {
    fun snapshot(): WorldSnapshot
    fun health(): EngineHealth = EngineHealth.RUNNING
}

/** Only this actor owns and mutates world state. */
class WorldEngine(scope: CoroutineScope, private val rules: SessionRules = SessionRules.load(), private val ticking: Boolean = true,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (WorldEvent) -> Unit = {}, private val generationStore: SessionGenerationStore = MemorySessionGenerationStore(),
    private val durability: SessionDurability? = null,
    private val timings: ActorTimings? = null,
    private val metricNanos: () -> Long = System::nanoTime,
    private val galaxy: Galaxy? = null, private val navigationStore: NavigationStore? = null,
    navigationPolicy: NavigationPolicy = NavigationPolicy(), private val stockStore: PlanetStockStore? = null,
    productionPolicy: PlanetProductionPolicy = PlanetProductionPolicy(),
    private val worldStateCommitter: WorldStateCommitter? = null,
    private val npcPolicy:NpcTacticalPolicy=NpcTacticalPolicy()) : CommandSink, SnapshotSource, AutoCloseable {
    private data class Queued(val command: WorldCommand, val submittedNanos: Long? = null,
        val backpressured: Boolean = false, val scheduledTickNanos: Long? = null,
        val applied: CompletableDeferred<Unit>? = null, val advanceNow:Boolean=false)
    private val queue = Channel<Queued>(1024)
    private val tickPulse = Channel<Unit>(Channel.CONFLATED)
    private val tacticalTickAt = AtomicReference<Long?>(null)
    private val combatObservers = java.util.concurrent.CopyOnWriteArrayList<(TacticalImpact) -> Unit>()
    private val movementObservers = java.util.concurrent.CopyOnWriteArrayList<(TacticalMovement) -> Unit>()
    private val groundBattleObservers=java.util.concurrent.CopyOnWriteArrayList<(GroundBattleResult)->Unit>()
    fun observeGroundBattles(observer:(GroundBattleResult)->Unit):AutoCloseable {
        groundBattleObservers+=observer;return AutoCloseable {groundBattleObservers.remove(observer)}
    }
    private val groundObservers=java.util.concurrent.CopyOnWriteArrayList<(TacticalGroundResult)->Unit>()
    fun observeGround(observer:(TacticalGroundResult)->Unit):AutoCloseable {
        groundObservers+=observer;return AutoCloseable {groundObservers.remove(observer)}
    }
    private val postureObservers=java.util.concurrent.CopyOnWriteArrayList<(TacticalPostureResult)->Unit>()
    fun observePostures(observer:(TacticalPostureResult)->Unit):AutoCloseable {
        postureObservers+=observer
        return AutoCloseable {postureObservers.remove(observer)}
    }
    private val supportObservers = java.util.concurrent.CopyOnWriteArrayList<(TacticalSupportResult) -> Unit>()
    fun observeSupport(observer:(TacticalSupportResult)->Unit):AutoCloseable {
        supportObservers+=observer
        return AutoCloseable {supportObservers.remove(observer)}
    }
    private fun publishSupport(results:List<TacticalSupportResult>) {
        for(result in results)for(observer in supportObservers)try {observer(result)}
        catch(failure:Exception){System.err.println("Tactical support delivery failed: ${failure.javaClass.simpleName}")}
    }
    private val tacticalEndObservers=java.util.concurrent.CopyOnWriteArrayList<(TacticalEnd)->Unit>()
    fun observeTacticalEnds(observer:(TacticalEnd)->Unit):AutoCloseable {
        tacticalEndObservers+=observer
        return AutoCloseable {tacticalEndObservers.remove(observer)}
    }
    fun observeMovements(observer:(TacticalMovement)->Unit):AutoCloseable {
        movementObservers+=observer
        return AutoCloseable {movementObservers.remove(observer)}
    }
    /** Register before accepting gateway clients; reconnects bootstrap from durable unit state. */
    fun observeCombat(observer: (TacticalImpact) -> Unit): AutoCloseable {
        combatObservers += observer
        return AutoCloseable { combatObservers.remove(observer) }
    }
    private fun publishCombat(impacts: List<TacticalImpact>) {
        for (impact in impacts) for (observer in combatObservers) try { observer(impact) }
        catch (failure: Exception) {
            // Delivery cannot roll back an already committed shot or poison durable world storage.
            System.err.println("Combat delivery failed: ${failure.javaClass.simpleName}")
        }
    }
    private fun settleCombat(session: SessionState, base: WorldSnapshot) {
        if (navigation == null || production == null || galaxy == null) return
        val before=navigation.snapshot(session.generation)
        val movements=mutableListOf<TacticalMovement>()
        val support=mutableListOf<TacticalSupportResult>()
        val postures=mutableListOf<TacticalPostureResult>()
        val ground=mutableListOf<TacticalGroundResult>()
        val groundBattles=mutableListOf<GroundBattleResult>()
        val impacts = TacticalCombat.settle(session,galaxy,navigation,production,worldStateCommitter,
            npcPolicy.copy(enabled=npcPolicy.enabled && worldStateCommitter!=null),rules.speed,movements::add,support::add,postures::add,ground::add,groundBattles::add)
        // Publish this committed transaction before a later movement/retreat save can fail.
        publish(base.copy(navigators=navigation.snapshot(session.generation),planetStocks=production.snapshot(session.generation)))
        publishCombat(impacts)
        publishSupport(support)
        for(event in ground)for(observer in groundObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Tactical ground delivery failed: ${failure.javaClass.simpleName}")}
        for(event in groundBattles)for(observer in groundBattleObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Ground battle delivery failed: ${failure.javaClass.simpleName}")}
        for(movement in movements)for(observer in movementObservers)try {observer(movement)}
        catch(failure:Exception){System.err.println("Tactical movement delivery failed: ${failure.javaClass.simpleName}")}
        for(event in postures)for(observer in postureObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Tactical posture delivery failed: ${failure.javaClass.simpleName}")}
        // Deliver committed damage before leaving the winner's native battlefield. A defeated
        // flagship changes grid and already has its own departure/return notification.
        for((account,player) in navigation.snapshot(session.generation)) {
            val old=before[account] ?: continue
            val connection=session.participants[account]?.connection ?: continue
            if(!old.cpPaused || player.cpPaused || old.grid!=player.grid)continue
            val event=TacticalEnd(session.generation,player.characterId,player.grid,connection)
            for(observer in tacticalEndObservers)try {observer(event)}
            catch(failure:Exception){System.err.println("Tactical end delivery failed: ${failure.javaClass.simpleName}")}
        }
    }
    private val simulation = SessionSimulation(rules, generationStore.current())
    private val navigation = galaxy?.let { Navigation(it, navigationStore, navigationPolicy, rules.cpIntervalSeconds, rules.speed) }
    private var durableState = durability?.let { store ->
        try {
            val restored = store.load()
            if (restored.session.participants.values.any { it.online || it.connection != null }) {
                val recovered = transitionSession(rules, restored, SessionAction.RecoverConnections)
                store.commit(SessionAction.RecoverConnections, recovered)
                recovered.state
            } else restored
        } catch (failure: Exception) { store.close(); throw failure }
    }
    private val initialElapsed = durableState?.elapsed ?: 0L
    private val production = galaxy?.let { PlanetProduction(it, stockStore, rules.startSeconds, productionPolicy,
        durableState?.session?.generation ?: simulation.snapshot().generation) }
    private val published = AtomicReference(durableState?.snapshot(rules) ?: WorldSnapshot(0, 0, simulation.snapshot()))
    private val origin = monotonicMillis()
    private var elapsed = initialElapsed
    private val logistics = OutfitLogistics(monotonicMillis)
    /** A commit or post-commit processing failure has blocked further durable mutations. */
    @Volatile private var storageFailed = false
    private fun publish(snapshot:WorldSnapshot) {
        published.set(snapshot)
        val session=durableState?.session ?: simulation.exportState()
        val personal=snapshot.navigators.values.asSequence().filter {it.generation==session.generation && it.cpPaused && it.baseId==0 && it.hull!=null}
            .mapNotNull {it.motion?.takeIf {m->m.grid==it.grid}}
        val ships=snapshot.planetStocks?.takeIf {it.generation==session.generation}?.outfits?.values.orEmpty().asSequence()
            .flatMap {outfit->
                val position=galaxy?.let {outfit.positionIn(it)}
                if(position==null || position.baseId!=0)emptySequence() else
                    outfit.activeShips?.values.orEmpty().asSequence().filter {
                        it.boats>0 && (it.motionCommander==0 || it.motionCommander==outfit.commanderId)
                    }.mapNotNull {it.tactical?.motion?.takeIf {m->m.grid==position.grid}}
            }
        val postureDeadlines=snapshot.navigators.values.mapNotNull {it.posture?.order?.readyAt} +
            snapshot.planetStocks?.outfits?.values.orEmpty().flatMap {o->o.activeShips?.values.orEmpty().mapNotNull {it.posture?.order?.readyAt}}
        val groundDeadlines=snapshot.planetStocks?.outfits?.values.orEmpty().flatMap {o->o.activeGround?.values.orEmpty().mapNotNull {it.order?.readyAt}}
        val deadline=((personal+ships).mapNotNull {it.nextProgressAt}.toList()+postureDeadlines+groundDeadlines+snapshot.planetStocks?.groundBattles.orEmpty().values.map {it+5*rules.speed}).minOrNull()
        tacticalTickAt.set(deadline?.let {
            val remaining=maxOf(0,it*1000-session.gameMillis)
            val currentElapsed=durableState?.elapsed ?: elapsed
            Math.addExact(origin,Math.addExact(currentElapsed-initialElapsed,(remaining+rules.speed-1)/rules.speed))
        })
        tickPulse.trySend(Unit)
    }
    private val worker = scope.launch {
        var state = published.get()
        var restartAt: Long? = null
        fun restart(): List<WorldEvent> {
            val snapshot = simulation.snapshot()
            check(snapshot.ended != null)
            val generation = generationStore.advance(snapshot.generation)
            simulation.restart(generation); restartAt = null
            return listOf(WorldEvent.Restarted(generation))
        }
        var storageFailure: Exception? = null
        try { for (queued in queue) { try {
            val command = if(queued.advanceNow) WorldCommand.AdvanceTo(
                maxOf(durableState?.elapsed ?: elapsed,if(ticking)Math.addExact(initialElapsed,monotonicMillis()-origin)
                    else durableState?.elapsed ?: elapsed)) else queued.command
            if (command is WorldCommand.AssignStock) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session.generation, session.gameMillis / 1000)
                    val before = recovered?.player
                    val result = run assignment@{
                        fun reject(reason: LogisticsRejection) = StockAssignmentResult(before, inventory, reason)
                        if (before == null) return@assignment reject(LogisticsRejection.IDENTITY)
                        if (worldStateCommitter == null) return@assignment reject(LogisticsRejection.STORAGE)
                        val transfer = try { StockAssignment.transfer(request, inventory) }
                            catch (_: IllegalArgumentException) { return@assignment reject(LogisticsRejection.STOCK) }
                            catch (_: NoSuchElementException) { return@assignment reject(LogisticsRejection.OUTFIT) }
                        val prepare = LogisticsRequest(request.account, request.connection, request.characterId, request.power,
                            request.generation, request.outfit, LogisticsMode.ALLOCATION, LogisticsPhase.PREPARE)
                        val reservation = logistics.apply(prepare, session, before, production)
                        reservation.rejection?.let { return@assignment reject(it) }
                        val commit = prepare.copy(phase = LogisticsPhase.COMMIT, reservation = checkNotNull(reservation.reservation),
                            transfer = transfer, supplies = request.supplies)
                        val preview = logistics.apply(commit, session, before, production, persist = false)
                        if (preview.rejection != null) {
                            logistics.apply(commit.copy(phase = LogisticsPhase.CANCEL), session, before, production)
                            return@assignment reject(preview.rejection)
                        }
                        val next = checkNotNull(preview.stocks)
                        try {
                            StockAssignment.validateReply(next.bases.getValue(request.base).units,next.bases.getValue(request.base).spareShips)
                            StockAssignment.validateReply(next.warehouses.getValue(next.outfits.getValue(request.outfit).warehouseId).units,
                                next.warehouses.getValue(next.outfits.getValue(request.outfit).warehouseId).spareShips)
                        } catch (_: IllegalArgumentException) { return@assignment reject(LogisticsRejection.STOCK) }
                        if (next == inventory) return@assignment StockAssignmentResult(before, inventory)
                        val payment = org.logh7.engine.strategy.CpPayment.charge(
                            org.logh7.engine.strategy.CpBalance(before.pcp.toLong(), before.mcp.toLong()),
                            StockAssignment.COST.toLong(), org.logh7.engine.strategy.CpPool.MCP)
                        if (payment !is org.logh7.engine.strategy.PaymentResult.Paid) return@assignment reject(LogisticsRejection.CP)
                        val after = before.copy(pcp = Math.toIntExact(payment.balance.pcp), mcp = Math.toIntExact(payment.balance.mcp))
                        val prepared = production.prepareReplacement(inventory, next)
                        worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                        navigation.publishCommitted(request.account, before, after)
                        production.publishCommitted(inventory, prepared)
                        StockAssignmentResult(after, prepared, changed = true)
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.TransferGround) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session.generation, session.gameMillis / 1000)
                    var result = GroundTransfer.evaluate(request, session, recovered?.player, inventory)
                    if (result.rejection == null && logistics.blocksSupply(session, inventory, setOf(request.outfit)))
                        result = GroundTransferResult(recovered?.player, inventory, LogisticsRejection.BUSY)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = GroundTransferResult(recovered?.player, inventory, LogisticsRejection.STORAGE)
                        else {
                            val before = checkNotNull(recovered?.player)
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                            navigation.publishCommitted(request.account, before, after)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(stocks = prepared)
                        }
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.SupplementOutfit) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session.generation, session.gameMillis / 1000)
                    var result = Supplement.evaluate(request, session, recovered?.player, inventory)
                    if (result.rejection == null && logistics.blocksSupply(session, inventory, setOf(request.outfit)))
                        result = SupplementResult(recovered?.player, inventory, LogisticsRejection.BUSY)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = SupplementResult(recovered?.player, inventory, LogisticsRejection.STORAGE)
                        else {
                            val before = checkNotNull(recovered?.player)
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                            navigation.publishCommitted(request.account, before, after)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(stocks = prepared)
                        }
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.SupplyOutfit) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session.generation, session.gameMillis / 1000)
                    var result = CompletenessSupply.evaluate(request, session, recovered?.player, inventory)
                    if (result.rejection == null && logistics.blocksSupply(session, inventory, setOf(request.from, request.to)))
                        result = CompletenessSupplyResult(recovered?.player, inventory, LogisticsRejection.BUSY)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = CompletenessSupplyResult(recovered?.player, inventory, LogisticsRejection.STORAGE)
                        else {
                            val before = checkNotNull(recovered?.player)
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                            navigation.publishCommitted(request.account, before, after)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(stocks = prepared)
                        }
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.Reorganize) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session.generation, session.gameMillis / 1000)
                    val before = recovered?.player
                    val result = run native@{
                        fun reject(reason: LogisticsRejection) = ReorganizationResult(before, inventory, reason)
                        if (request.mode !in 0..1 || request.mode == 0 && request.troops.isNotEmpty())
                            return@native reject(LogisticsRejection.MODE)
                        if (before == null) return@native reject(LogisticsRejection.IDENTITY)
                        if (worldStateCommitter == null) return@native reject(LogisticsRejection.STORAGE)
                        val transfer = try { Reorganization.transfer(request, inventory) }
                            catch (_: IllegalArgumentException) { return@native reject(LogisticsRejection.STOCK) }
                            catch (_: NoSuchElementException) { return@native reject(LogisticsRejection.OUTFIT) }
                        val prepare = LogisticsRequest(request.account, request.connection, request.characterId, request.power,
                            request.generation, request.outfit, LogisticsMode.REORGANIZATION, LogisticsPhase.PREPARE)
                        val reservation = logistics.apply(prepare, session, before, production)
                        reservation.rejection?.let { return@native reject(it) }
                        if (request.mode == 0) {
                            try {
                                return@native ReorganizationResult(before,inventory,
                                    capacity=Reorganization.capacity(inventory,request.outfit,transfer))
                            } catch (_: IllegalArgumentException) { return@native reject(LogisticsRejection.STOCK) }
                            finally {
                                logistics.apply(prepare.copy(phase=LogisticsPhase.CANCEL,
                                    reservation=checkNotNull(reservation.reservation)),session,before,production)
                            }
                        }
                        val commit = prepare.copy(phase = LogisticsPhase.COMMIT, reservation = checkNotNull(reservation.reservation), transfer = transfer)
                        val preview = logistics.apply(commit, session, before, production, persist = false)
                        if (preview.rejection != null) {
                            logistics.apply(commit.copy(phase = LogisticsPhase.CANCEL), session, before, production)
                            return@native reject(preview.rejection)
                        }
                        val next = checkNotNull(preview.stocks)
                        if (next == inventory) return@native ReorganizationResult(before, inventory)
                        val payment = org.logh7.engine.strategy.CpPayment.charge(
                            org.logh7.engine.strategy.CpBalance(before.pcp.toLong(), before.mcp.toLong()),
                            Reorganization.COST.toLong(), org.logh7.engine.strategy.CpPool.MCP)
                        if (payment !is org.logh7.engine.strategy.PaymentResult.Paid) return@native reject(LogisticsRejection.CP)
                        val after = before.copy(pcp = Math.toIntExact(payment.balance.pcp), mcp = Math.toIntExact(payment.balance.mcp))
                        val prepared = production.prepareReplacement(inventory, next)
                        worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                        navigation.publishCommitted(request.account, before, after)
                        production.publishCommitted(inventory, prepared)
                        ReorganizationResult(after, prepared, changed = true)
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.AppointGarrison) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production) { "World production is disabled" }.settle(session.generation, session.gameMillis / 1000)
                    var result = GarrisonAppointment.evaluate(checkNotNull(galaxy), request, session, recovered?.player, inventory)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = GarrisonAppointmentResult(recovered?.player, inventory, AppointmentRejection.STORAGE)
                        else {
                            val before = checkNotNull(recovered?.player)
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                            navigation.publishCommitted(request.account, before, after)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(stocks = prepared)
                        }
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered,targetPlayer=navigation?.snapshot(session.generation)?.values?.singleOrNull { it.characterId==request.target }))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.DismissOffice) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production) { "World production is disabled" }.settle(session.generation, session.gameMillis / 1000)
                    var result = OfficeDismissal.evaluate(checkNotNull(galaxy), request, session, recovered?.player, inventory)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = OfficeDismissalResult(recovered?.player, inventory, DismissalRejection.STORAGE)
                        else {
                            val before = checkNotNull(recovered?.player)
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                            navigation.publishCommitted(request.account, before, after)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(stocks = prepared)
                        }
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered,targetPlayer=navigation?.snapshot(session.generation)?.values?.singleOrNull { it.characterId==request.target }))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.ResignOffice) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request=command.request
                    val session=durableState?.session ?: simulation.exportState()
                    val recovered=navigation?.apply(NavigationRequest(request.account,request.characterId,request.power,
                        request.connection,request.generation,politics=request.politics,administration=request.administration),session)
                    val inventory=checkNotNull(production) { "World production is disabled" }.settle(session.generation,session.gameMillis/1000)
                    var result=OfficeResignation.evaluate(checkNotNull(galaxy),request,session,recovered?.player,inventory)
                    if(result.changed) {
                        if(worldStateCommitter==null) result=OfficeResignationResult(recovered?.player,inventory,ResignationRejection.STORAGE)
                        else {
                            val before=checkNotNull(recovered?.player);val after=checkNotNull(result.player)
                            val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation)+(request.account to after),prepared)
                            navigation.publishCommitted(request.account,before,after)
                            production.publishCommitted(inventory,prepared)
                            result=result.copy(stocks=prepared)
                        }
                    }
                    state=published.get().copy(navigators=navigation?.snapshot(session.generation).orEmpty(),planetStocks=production.snapshot(session.generation))
                    publish(state);command.result.complete(result.copy(recovery=recovered))
                } catch(failure:Exception) {
                    if(failure is java.io.IOException) {storageFailed=true;storageFailure=failure}
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.Tax) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = navigation?.apply(NavigationRequest(request.account, request.characterId, request.power,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production) { "World production is disabled" }.settle(session.generation, session.gameMillis / 1000)
                    var result = PlanetTax.evaluate(checkNotNull(galaxy), request, session, recovered?.player, inventory)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = TaxResult(recovered?.player, inventory, TaxRejection.STORAGE)
                        else {
                            val before = checkNotNull(recovered?.player)
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation) + (request.account to after), prepared)
                            navigation.publishCommitted(request.account, before, after)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(stocks = prepared)
                        }
                    }
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result.copy(recovery = recovered))
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.Logistics) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val session = durableState?.session ?: simulation.exportState()
                    val result = logistics.apply(command.request, session,
                        navigation?.snapshot(session.generation)?.get(command.request.account),
                        checkNotNull(production) { "World production is disabled" })
                    state = published.get().copy(planetStocks = production.snapshot(session.generation))
                    publish(state)
                    command.result.complete(result)
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.Stock) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val session = durableState?.session ?: simulation.exportState()
                    require(session.generation == command.generation) { "Inventory query belongs to a stale session" }
                    val result = checkNotNull(production) { "World production is disabled" }.settle(session.generation, session.gameMillis / 1000)
                    state = published.get().copy(planetStocks = result)
                    publish(state)
                    command.result.complete(result)
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.Navigate) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val session = durableState?.session ?: simulation.exportState()
                    val ownership = production?.settle(session.generation, session.gameMillis / 1000)
                    settleCombat(session,published.get())
                    val result = if (navigation != null && production != null && ownership != null && galaxy != null)
                        TacticalGroundOperations.guard(command.request,session,navigation,checkNotNull(production.snapshot(session.generation))) ?:
                        TacticalPostureOperations.guard(command.request,session,navigation,
                            checkNotNull(production.snapshot(session.generation))) ?: if(command.request.tacticalGround!=null)
                            TacticalGroundOperations.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if(command.request.tacticalPosture!=null)
                            TacticalPostureOperations.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if (command.request.tacticalStop?.units?.any {it in 1..999999}==true)
                            TacticalSupportOperations.stop(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter)
                        else if(command.request.tacticalMove?.let {it.maneuver!=null || it.units.any {id->id in 1..999999}}==true)
                            TacticalFleetMotion.move(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if (command.request.tacticalSupport != null)
                            TacticalSupportOperations.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if(command.request.tacticalAttack?.units?.any {it in 1..999999}==true)
                            TacticalFleetAttack.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if (command.request.tacticalAttack != null)
                            TacticalCombat.attack(command.request,session,galaxy,navigation,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if(command.request.tacticalShoot?.units?.any {it in 1..999999}==true)
                            TacticalFleetShoot.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if (command.request.tacticalShoot != null)
                            TacticalCombat.accept(command.request,session,galaxy,navigation,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else FleetNavigation.apply(command.request, session, galaxy, navigation, production,
                            checkNotNull(production.snapshot(session.generation)), worldStateCommitter) { outfit ->
                            logistics.blocksMovement(session, outfit)
                        } else NavigationResult(null, rejection = NavigationRejection.DISABLED)
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production?.snapshot(session.generation))
                    publish(state)
                    publishSupport(result.supportNotifications)
                    result.player?.let {player ->
                        val recipients=session.participants.filter {(_,member)->member.online && member.connection!=command.request.connection}
                            .mapNotNull {(account,member)->state.navigators[account]?.takeIf {
                                it.grid==player.grid && it.baseId==0 && it.cpPaused}?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
                        val formation=result.tacticalMotions.takeIf {it.values.any {m->m.rotation!=null}}.orEmpty()
                        val deliveries=if(formation.isEmpty())result.tacticalMotions.filterKeys {it in 1..999999}
                            else formation.entries.take(1).associate {it.key to it.value}
                        for((id,motion) in deliveries) {
                            val event=TacticalMovement(session.generation,id,player.grid,session.gameMillis/1000,motion,recipients,
                                character=player.characterId,stopped=command.request.tacticalStop!=null,formation=formation)
                            for(observer in movementObservers)try {observer(event)}
                            catch(failure:Exception){System.err.println("Tactical movement delivery failed: ${failure.javaClass.simpleName}")}
                        }
                    }
                    command.result.complete(result)
                } catch (failure: Exception) {
                    if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            queued.submittedNanos?.let { submitted ->
                val dequeued = metricNanos()
                duration(ActorTiming.QUEUE_AGE, submitted, dequeued)
                if (queued.backpressured) duration(ActorTiming.BACKPRESSURED_QUEUE_AGE, submitted, dequeued)
                queued.scheduledTickNanos?.let { duration(ActorTiming.TICK_DEQUEUE_LAG, it, dequeued) }
            }
            if (durability != null) {
                try {
                    storageFailure?.let { throw java.io.IOException("Session storage is unavailable; restart and verify the journal", it) }
                    val next = transitionSession(rules, checkNotNull(durableState), command.sessionAction())
                    durability.commit(command.sessionAction(), next)
                    durableState = next.state
                    if (command is WorldCommand.AdvanceTo) {
                        production?.settle(next.state.session.generation, next.state.session.gameMillis / 1000)
                        settleCombat(next.state.session,next.state.snapshot(rules))
                        navigation?.settleRetreats(next.state.session, production?.snapshot(next.state.session.generation))
                    }
                    publish(next.state.snapshot(rules).copy(navigators = navigation?.snapshot(next.state.session.generation).orEmpty(),
                        planetStocks = production?.snapshot(next.state.session.generation)))
                    if (command is WorldCommand.Join) {
                        if (next.rejected) command.result.completeExceptionally(IllegalArgumentException("Invalid join"))
                        else command.result.complete(checkNotNull(next.admission))
                    }
                    if (command is WorldCommand.RequestRestart) command.result.complete(!next.rejected)
                    next.events.forEach(onEvent)
                } catch (failure: Exception) {
                    storageFailure = failure
                    storageFailed = true
                    if (command is WorldCommand.Join) command.result.completeExceptionally(failure)
                    if (command is WorldCommand.RequestRestart) command.result.complete(false)
                }
                continue
            }
            var admission: Admission? = null
            var restartResult: Boolean? = null
            val events = try { when (command) {
                WorldCommand.Probe -> emptyList()
                is WorldCommand.AdvanceTo -> {
                    require(command.elapsedRealMillis >= elapsed)
                    val events = if (restartAt?.let { command.elapsedRealMillis >= it } == true) restart()
                        else simulation.advance(command.elapsedRealMillis - elapsed)
                    elapsed = command.elapsedRealMillis
                    if (!storageFailed) production?.settle(simulation.snapshot().generation, simulation.exportState().gameMillis / 1000)
                    settleCombat(simulation.exportState(),published.get().copy(session=simulation.snapshot()))
                    navigation?.settleRetreats(simulation.exportState(), production?.snapshot(simulation.snapshot().generation))
                    if (events.any { it is WorldEvent.Ended }) restartAt = elapsed + rules.restartDelayRealMillis
                    events
                }
                is WorldCommand.Join -> { admission = if (command.generation != null && command.generation != simulation.snapshot().generation) Admission.STALE_SESSION else simulation.join(command.account, command.power, command.original, command.connection); emptyList() }
                is WorldCommand.Disconnect -> { simulation.disconnect(command.account, command.connection); emptyList() }
                is WorldCommand.Exclude -> { simulation.exclude(command.account); emptyList() }
                is WorldCommand.OpenBattle -> { simulation.openBattle(command.id); emptyList() }
                is WorldCommand.CloseBattle -> { simulation.closeBattle(command.id); emptyList() }
                is WorldCommand.Territory -> simulation.updateTerritory(command.systems, command.capturedCapitals).also {
                    if (it.isNotEmpty()) restartAt = elapsed + rules.restartDelayRealMillis
                }
                WorldCommand.Restart -> restart()
                is WorldCommand.RequestRestart -> restart().also { restartResult = true }
                is WorldCommand.Navigate -> error("Navigation is handled before session commands")
                is WorldCommand.Stock -> error("Production is handled before session commands")
                is WorldCommand.Logistics -> error("Logistics is handled before session commands")
                is WorldCommand.AppointGarrison -> error("Appointment is handled before session commands")
                is WorldCommand.DismissOffice -> error("Dismissal is handled before session commands")
                is WorldCommand.ResignOffice -> error("Resignation is handled before session commands")
                is WorldCommand.Tax -> error("Tax is handled before session commands")
                is WorldCommand.SupplyOutfit -> error("Supply is handled before session commands")
                is WorldCommand.TransferGround -> error("Ground transfer is handled before session commands")
                is WorldCommand.SupplementOutfit -> error("Supplement is handled before session commands")
                is WorldCommand.Reorganize -> error("Reorganization is handled before session commands")
                is WorldCommand.AssignStock -> error("Stock assignment is handled before session commands")
            } } catch (failure: Exception) {
                if (failure !is IllegalArgumentException && failure !is IllegalStateException && failure !is ArithmeticException && failure !is java.io.IOException) throw failure
                if (failure is java.io.IOException) { storageFailed = true; storageFailure = failure }
                if (command is WorldCommand.Join) command.result.completeExceptionally(failure)
                state = state.copy(rejectedCommands = state.rejectedCommands + 1)
                if (command is WorldCommand.RequestRestart) restartResult = false
                emptyList()
            }
            state = state.copy(revision = state.revision + 1,
                acceptedCommands = state.acceptedCommands + if (command == WorldCommand.Probe) 1 else 0, session = simulation.snapshot(), navigators = navigation?.snapshot(simulation.snapshot().generation).orEmpty(),
                planetStocks = production?.snapshot(simulation.snapshot().generation))
            publish(state)
            if (command is WorldCommand.Join && admission != null) command.result.complete(admission)
            if (command is WorldCommand.RequestRestart) command.result.complete(restartResult == true)
            events.forEach(onEvent)
        } finally { queued.applied?.complete(Unit) } } } finally { try { durability?.close() } finally { try { navigationStore?.close() } finally { stockStore?.close() } } }
    }
    private val ticker = if (ticking) scope.launch {
        val interval = minOf(rules.strategyTickRealMillis, rules.tacticsTickRealMillis)
        var regularAt=Math.addExact(origin,interval)
        while (isActive) {
            val now=monotonicMillis()
            val wakeAt=if(storageFailed)regularAt else minOf(regularAt,tacticalTickAt.get() ?: regularAt)
            val wait=maxOf(0,wakeAt-now)
            val scheduled = if (timings == null) null else try {
                Math.addExact(metricNanos(), Math.multiplyExact(wait, 1_000_000L))
            } catch (_: ArithmeticException) { observe { clockRegression() }; null }
            if(wait>0 && withTimeoutOrNull(wait) {tickPulse.receive();true}==true)continue
            scheduled?.let { duration(ActorTiming.TICK_WAKE_LAG, it, metricNanos()) }
            val applied=CompletableDeferred<Unit>()
            enqueue(WorldCommand.AdvanceTo(Math.addExact(initialElapsed, monotonicMillis() - origin)), scheduled,applied)
            applied.await() // A slow commit cannot queue multiple overdue deadline ticks.
            val after=monotonicMillis()
            if(after>=regularAt)regularAt=Math.addExact(after,interval-(after-origin)%interval)
        }
    } else null
    /** Serialize a fresh time sample through the same durable path as ordinary ticks. */
    suspend fun synchronizeTime():WorldSnapshot {
        val applied=CompletableDeferred<Unit>()
        enqueue(WorldCommand.AdvanceTo(0),applied=applied,advanceNow=true)
        applied.await()
        check(health()==EngineHealth.RUNNING) {"World time could not be committed"}
        return snapshot()
    }
    override suspend fun submit(command: WorldCommand) {
        enqueue(if (command is WorldCommand.Territory) command.copy(systems = command.systems.toMap(), capturedCapitals = command.capturedCapitals.toSet()) else command)
    }
    private suspend fun enqueue(command: WorldCommand, scheduledTickNanos: Long? = null, applied:CompletableDeferred<Unit>? = null, advanceNow:Boolean=false) {
        if (timings == null) { queue.send(Queued(command,applied=applied,advanceNow=advanceNow)); return }
        currentCoroutineContext().ensureActive()
        val submitted = metricNanos()
        val queued = Queued(command, submitted, scheduledTickNanos = scheduledTickNanos,applied=applied,advanceNow=advanceNow)
        val sent = queue.trySend(queued)
        if (!sent.isSuccess) queue.send(queued.copy(backpressured = !sent.isClosed))
        duration(ActorTiming.SEND_WAIT, submitted, metricNanos())
    }
    private fun duration(metric: ActorTiming, start: Long, end: Long) {
        val elapsed = try { Math.subtractExact(end, start) } catch (_: ArithmeticException) { -1L }
        if (elapsed < 0) observe { clockRegression() } else observe { record(metric, elapsed) }
    }
    private inline fun observe(record: ActorTimings.() -> Unit) {
        try { timings?.record() } catch (_: Exception) { /* Monitoring cannot affect state, ACKs or events. */ }
    }
    override fun snapshot(): WorldSnapshot = published.get()
    override fun health(): EngineHealth = when {
        worker.isCompleted || ticker?.isCompleted == true -> EngineHealth.STOPPED
        storageFailed -> EngineHealth.STORAGE_FAILED
        else -> EngineHealth.RUNNING
    }
    suspend fun drainAndClose() { ticker?.cancelAndJoin(); queue.close(); worker.join() }
    override fun close() { ticker?.cancel(); queue.close() }
}
