package org.logh7.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

data class WorldSnapshot(val revision: Long, val acceptedCommands: Long, val session: SessionSnapshot? = null, val rejectedCommands: Long = 0,
    val navigators: Map<String, Navigator> = emptyMap(), val planetStocks: PlanetStocks? = null, val result:SessionResult?=null)
sealed interface WorldCommand {
    data object Probe : WorldCommand
    data class AdvanceTo(val elapsedRealMillis: Long) : WorldCommand
    data class Join(val account: String, val country: CountryId?, val original: Boolean, val result: CompletableDeferred<Admission>, val connection: String? = null, val generation: Long? = null,
        /** The character store's current profile; seeds the strategic record when CP experience first raises an ability. */
        val profile: StrategicPerson? = null) : WorldCommand {
        constructor(account:String,power:Power,original:Boolean,result:CompletableDeferred<Admission>,
            connection:String?=null,generation:Long?=null,profile:StrategicPerson?=null):
            this(account,power.country,original,result,connection,generation,profile)
    }
    data class Disconnect(val account: String, val connection: String? = null) : WorldCommand
    data class Exclude(val account: String) : WorldCommand
    data class OpenBattle(val id: String) : WorldCommand
    data class CloseBattle(val id: String) : WorldCommand
    data class Territory(val systems: Map<Power, Int>, val capturedCapitals: Set<Power>) : WorldCommand
    data object Restart : WorldCommand
    data class RequestRestart(val result: CompletableDeferred<Boolean>) : WorldCommand
    data class AdminEditBase(val request:AdminBaseEdit,val result:CompletableDeferred<AdminBaseEditResult>) : WorldCommand
    data class Navigate(val request: NavigationRequest, val result: CompletableDeferred<NavigationResult>) : WorldCommand
    data class Stock(val generation: Long, val result: CompletableDeferred<PlanetStocks>) : WorldCommand
    data class Logistics(val request: LogisticsRequest, val result: CompletableDeferred<LogisticsResult>) : WorldCommand
    data class AppointGarrison(val request: GarrisonAppointmentRequest,val result: CompletableDeferred<GarrisonAppointmentResult>) : WorldCommand
    data class DismissOffice(val request:OfficeDismissalRequest,val result:CompletableDeferred<OfficeDismissalResult>) : WorldCommand
    data class ResignOffice(val request:OfficeResignationRequest,val result:CompletableDeferred<OfficeResignationResult>) : WorldCommand
    data class Tax(val request: TaxRequest, val result: CompletableDeferred<TaxResult>) : WorldCommand
    data class Budget(val request:BudgetRequest,val result:CompletableDeferred<BudgetResult>) : WorldCommand
    data class Social(val request:SocialRequest,val result:CompletableDeferred<SocialResult>) : WorldCommand
    data class Reorganize(val request: ReorganizationRequest, val result: CompletableDeferred<ReorganizationResult>) : WorldCommand
    data class SupplyOutfit(val request: CompletenessSupplyRequest, val result: CompletableDeferred<CompletenessSupplyResult>) : WorldCommand
    data class TransferGround(val request: GroundTransferRequest, val result: CompletableDeferred<GroundTransferResult>) : WorldCommand
    data class SupplementOutfit(val request: SupplementRequest, val result: CompletableDeferred<SupplementResult>) : WorldCommand
    data class AssignStock(val request: StockAssignmentRequest, val result: CompletableDeferred<StockAssignmentResult>) : WorldCommand
    data class StrategyAction(val request:StrategyActionRequest,val result:CompletableDeferred<StrategyActionResult>) : WorldCommand
    data class FormOutfit(val request:OutfitFormationRequest,val result:CompletableDeferred<OutfitFormationResult>) : WorldCommand
    /** Runs [action] on the actor between commits (live backups); nothing commits until it returns. */
    data class Quiesce<T>(val action: () -> T, val result: CompletableDeferred<T>) : WorldCommand
}
interface CommandSink { suspend fun submit(command: WorldCommand) }
enum class EngineHealth { STARTING, RUNNING, STORAGE_FAILED, STOPPED }
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
    private val npcPolicy:NpcTacticalPolicy=NpcTacticalPolicy(),
    /** The app wires recovery and character identities after construction; hold the clock until then. */
    private val awaitIdentities:Boolean=false,
    private val founding:CountryFoundingRegistry?=null) : CommandSink, SnapshotSource, AutoCloseable {
    val foundingEnabled:Boolean get()=founding!=null && worldStateCommitter!=null
    val clockSpeed: Long get() = rules.speed
    private data class Queued(val command: WorldCommand, val submittedNanos: Long? = null,
        val backpressured: Boolean = false, val scheduledTickNanos: Long? = null,
        val applied: CompletableDeferred<Unit>? = null, val advanceNow:Boolean=false)
    private val queue = Channel<Queued>(1024)
    private val tickPulse = Channel<Unit>(Channel.CONFLATED)
    private val tacticalTickAt = AtomicReference<Long?>(null)
    private val combatObservers = java.util.concurrent.CopyOnWriteArrayList<(TacticalImpact) -> Unit>()
    private val budgetObservers=java.util.concurrent.CopyOnWriteArrayList<(BudgetChange)->Unit>()
    fun provideIdentities(supplier:()->List<StrategicIdentity>):AutoCloseable {
        production?.identities=supplier;return AutoCloseable {production?.identities={emptyList()}}
    }
    private val resultObservers=java.util.concurrent.CopyOnWriteArrayList<(WorldSnapshot)->Unit>()
    /** U-27: each published snapshot carrying a session result; observers must be idempotent per generation. */
    fun observeSessionResults(observer:(WorldSnapshot)->Unit):AutoCloseable {
        resultObservers+=observer;return AutoCloseable {resultObservers.remove(observer)}
    }
    fun observeBudget(observer:(BudgetChange)->Unit):AutoCloseable {
        budgetObservers+=observer;return AutoCloseable {budgetObservers.remove(observer)}
    }
    private val civicObservers=java.util.concurrent.CopyOnWriteArrayList<(CivicChange)->Unit>()
    fun observeCivic(observer:(CivicChange)->Unit):AutoCloseable {
        civicObservers+=observer;return AutoCloseable {civicObservers.remove(observer)}
    }
    private val successionObservers=java.util.concurrent.CopyOnWriteArrayList<(SuccessionNotice)->Unit>()
    fun observeSuccession(observer:(SuccessionNotice)->Unit):AutoCloseable {
        successionObservers+=observer;return AutoCloseable {successionObservers.remove(observer)}
    }
    private val tidalObservers=java.util.concurrent.CopyOnWriteArrayList<(TidalLoss)->Unit>()
    fun observeTides(observer:(TidalLoss)->Unit):AutoCloseable {
        tidalObservers+=observer;return AutoCloseable {tidalObservers.remove(observer)}
    }
    private val movementObservers = java.util.concurrent.CopyOnWriteArrayList<(TacticalMovement) -> Unit>()
    private val occupationObservers=java.util.concurrent.CopyOnWriteArrayList<(BaseOccupation)->Unit>()
    private val characterRefreshObservers=java.util.concurrent.CopyOnWriteArrayList<(List<Int>)->Unit>()
    /** Character IDs whose abilities/experience changed by CP-experience settlement. */
    fun observeCharacterRefresh(observer:(List<Int>)->Unit):AutoCloseable {
        characterRefreshObservers+=observer;return AutoCloseable {characterRefreshObservers.remove(observer)}
    }
    private val fortressSinkingObservers=java.util.concurrent.CopyOnWriteArrayList<(Navigator)->Unit>()
    /** The committed replacement navigator of each flagship sunk inside a breached fortress (FortressArmor). */
    fun observeFortressSinking(observer:(Navigator)->Unit):AutoCloseable {
        fortressSinkingObservers+=observer;return AutoCloseable {fortressSinkingObservers.remove(observer)}
    }
    fun observeOccupations(observer:(BaseOccupation)->Unit):AutoCloseable {
        occupationObservers+=observer;return AutoCloseable {occupationObservers.remove(observer)}
    }
    private val strategicMovementObservers=java.util.concurrent.CopyOnWriteArrayList<(NpcStrategicMovement)->Unit>()
    fun observeStrategicMovements(observer:(NpcStrategicMovement)->Unit):AutoCloseable {
        strategicMovementObservers+=observer;return AutoCloseable {strategicMovementObservers.remove(observer)}
    }
    private val groundBattleObservers=java.util.concurrent.CopyOnWriteArrayList<(GroundBattleResult)->Unit>()
    fun observeGroundBattles(observer:(GroundBattleResult)->Unit):AutoCloseable {
        groundBattleObservers+=observer;return AutoCloseable {groundBattleObservers.remove(observer)}
    }
    private val groundObservers=java.util.concurrent.CopyOnWriteArrayList<(TacticalGroundResult)->Unit>()
    fun observeGround(observer:(TacticalGroundResult)->Unit):AutoCloseable {
        groundObservers+=observer;return AutoCloseable {groundObservers.remove(observer)}
    }
    private val postureObservers=java.util.concurrent.CopyOnWriteArrayList<(TacticalPostureResult)->Unit>()
    private val commandObservers=java.util.concurrent.CopyOnWriteArrayList<(TacticalCommandResult)->Unit>()
    fun observeTacticalCommands(observer:(TacticalCommandResult)->Unit):AutoCloseable {
        commandObservers+=observer;return AutoCloseable {commandObservers.remove(observer)}
    }
    private fun publishTacticalCommands(events:List<TacticalCommandResult>) {
        for(event in events)for(observer in commandObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Tactical command delivery failed: ${failure.javaClass.simpleName}")}
    }
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
    private fun settleCombat(session: SessionState, base: WorldSnapshot, afterCommit:()->Unit = {}) {
        if (navigation == null || production == null || galaxy == null) return
        val oldStocks=published.get().planetStocks?.takeIf {it.generation==session.generation}
        val experienced=settleExperience(session)
        val before=navigation.snapshot(session.generation)
        val movements=mutableListOf<TacticalMovement>()
        val strategicMovements=mutableListOf<NpcStrategicMovement>()
        val support=mutableListOf<TacticalSupportResult>()
        val postures=mutableListOf<TacticalPostureResult>()
        val ground=mutableListOf<TacticalGroundResult>()
        val groundBattles=mutableListOf<GroundBattleResult>()
        val occupations=mutableListOf<BaseOccupation>()
        val commands=mutableListOf<TacticalCommandResult>()
        val fortressSunk=mutableListOf<Navigator>()
        val impacts = TacticalCombat.settle(session,galaxy,navigation,production,worldStateCommitter,
            npcPolicy.copy(enabled=npcPolicy.enabled && worldStateCommitter!=null),rules.speed,movements::add,support::add,postures::add,ground::add,groundBattles::add,occupations::add,strategicMovements::add,commands::add,fortressSunk::add,
            settleOfficeRecovery = { old, updated, players -> settleOfficeRecovery(session.gameMillis / 1000, old, updated, players) })
        // A later recapture must not restore a warehouse reservation from before occupation.
        occupations.forEach {logistics.occupationCommitted(it.base)}
        // Publish this committed transaction before a later movement/retreat save can fail.
        publish(base.copy(navigators=navigation.snapshot(session.generation),planetStocks=production.snapshot(session.generation)))
        // Reconcile durable capital/session endings before observers can start a native
        // forced-undock transition that cannot finish once gameplay requests are frozen.
        afterCommit()
        if(experienced.isNotEmpty())for(observer in characterRefreshObservers)try {observer(experienced)}
        catch(failure:Exception){System.err.println("Character refresh delivery failed: ${failure.javaClass.simpleName}")}
        for(event in strategicMovements)for(observer in strategicMovementObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("NPC strategic movement delivery failed: ${failure.javaClass.simpleName}")}
        publishCombat(impacts)
        publishSupport(support)
        publishTacticalCommands(commands)
        for(event in ground)for(observer in groundObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Tactical ground delivery failed: ${failure.javaClass.simpleName}")}
        for(event in groundBattles)for(observer in groundBattleObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Ground battle delivery failed: ${failure.javaClass.simpleName}")}
        for(event in occupations)for(observer in occupationObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Occupation delivery failed: ${failure.javaClass.simpleName}")}
        for(event in fortressSunk)for(observer in fortressSinkingObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Fortress sinking delivery failed: ${failure.javaClass.simpleName}")}
        for(movement in movements)for(observer in movementObservers)try {observer(movement)}
        catch(failure:Exception){System.err.println("Tactical movement delivery failed: ${failure.javaClass.simpleName}")}
        for(event in postures)for(observer in postureObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Tactical posture delivery failed: ${failure.javaClass.simpleName}")}
        // The impact already exits the defeated client's battlefield, including same-grid returns.
        // Other viewers still need the generic end after the final hit.
        for((account,player) in navigation.snapshot(session.generation)) {
            val old=before[account] ?: continue
            val connection=session.participants[account]?.connection ?: continue
            if(!old.cpPaused || player.cpPaused || old.grid!=player.grid ||
                impacts.any {it.departed==player && it.recipients[player.characterId]==connection})continue
            val event=TacticalEnd(session.generation,player.characterId,player.grid,connection)
            for(observer in tacticalEndObservers)try {observer(event)}
            catch(failure:Exception){System.err.println("Tactical end delivery failed: ${failure.javaClass.simpleName}")}
        }
        // Keep the previous battlefield membership until damage and exit notifications have been delivered.
        val changedMerit=if(oldStocks==null)emptyList() else production.snapshot(session.generation)?.strategic?.people.orEmpty()
            .filter { (id,person)->person.merit!=(oldStocks.strategic.people[id]?.merit ?: 0L) }.keys.toList()
        if(changedMerit.isNotEmpty())for(observer in characterRefreshObservers)try {observer(changedMerit)}
        catch(failure:Exception){System.err.println("Character refresh delivery failed: ${failure.javaClass.simpleName}")}
    }
    // Validate definitions before any recovery write; actual IDs never become native display identities.
    private fun validateWorldCountry(id:CountryId?) {
        if(id==null)return
        val catalog=CountryCatalogLoader.current()
        val definition=checkNotNull(catalog.countries[id.value]) {"Unknown world country ${id.value}"}
        check(definition.nativePower in 2..3 && definition.nativeCamp==0 &&
            catalog.shipStyle(id).value in setOf("empire","alliance") && OrganizationOffices.system(id,catalog) in 2..3) {
            "Unsupported world country ${id.value}"
        }
    }
    private val initialWorld = try {
        galaxy?.initialOwners?.values?.forEach {validateWorldCountry(it.country)}
        galaxy?.countryStarts?.keys?.forEach(::validateWorldCountry)
        val stocks=stockStore?.load()?.also {stocks->
            stocks.bases.values.forEach {validateWorldCountry(it.country)}
            stocks.outfits.values.forEach {validateWorldCountry(it.country);it.shipStyle.nativePower}
            stocks.warehouses.values.forEach {validateWorldCountry(it.country);it.shipStyle.nativePower}
            stocks.strategic.offices.forEach {validateWorldCountry(it.country)}
            stocks.strategic.capitals.forEach {(id,base)->validateWorldCountry(id);check(galaxy?.bases?.any {it.id==base}==true) {"Unknown country capital"}}
        }
        val players=if(galaxy==null)emptyMap() else navigationStore?.load().orEmpty()
        players.values.forEach {player->
            validateWorldCountry(player.country)
            player.shipStyle.nativePower
        }
        stocks to players
    } catch(failure:Exception) {
        listOfNotNull(durability,navigationStore,stockStore).forEach {store->
            try {store.close()} catch(closing:Exception) {failure.addSuppressed(closing)}
        }
        throw failure
    }
    private val simulation = SessionSimulation(rules, generationStore.current())
    private val navigation = galaxy?.let {
        Navigation(it, navigationStore, navigationPolicy, rules.cpIntervalSeconds, rules.speed, initialWorld.second,
            recoveryPenalty = ::officeRecoveryPenalty)
    }
    private var durableState = durability?.let { store ->
        try {
            var restored = store.load()
            // A restored membership must still reference a registered actual country before connection recovery.
            if(galaxy!=null) {
                restored.session.participants.values.forEach {validateWorldCountry(it.country)}
                restored.session.countries.forEach(::validateWorldCountry)
            }
            // A founding world checkpoint may have committed just before the participant journal.
            if(founding!=null)for((account,player) in initialWorld.second) {
                val country=player.country ?: continue
                val member=restored.session.participants[account] ?: continue
                val stocks=initialWorld.first ?: continue
                if(restored.session.ended!=null || player.generation!=restored.session.generation ||
                    stocks.generation!=player.generation || member.original || member.country!=null ||
                    country !in stocks.strategic.capitals || !founding.matches(country,player.generation,player.characterId,account))continue
                val action=SessionAction.Defect(account,country)
                val next=transitionSession(rules,restored,action)
                check(!next.rejected) {"Founding membership recovery was rejected"}
                store.commit(action,next);restored=next.state
            }
            if (restored.session.participants.values.any { it.online || it.connection != null }) {
                val recovered = transitionSession(rules, restored, SessionAction.RecoverConnections)
                store.commit(SessionAction.RecoverConnections, recovered)
                recovered.state
            } else restored
        } catch (failure: Exception) {
            listOfNotNull(store,navigationStore,stockStore).forEach {
                try {it.close()} catch(closing:Exception) {failure.addSuppressed(closing)}
            }
            throw failure
        }
    }
    private val initialElapsed = durableState?.elapsed ?: 0L
    private val production = galaxy?.let { PlanetProduction(it, stockStore, rules.startSeconds, productionPolicy,
        durableState?.session?.generation ?: simulation.snapshot().generation,initialWorld.first,
        {generation->navigation?.snapshot(generation)?.values}, ::commitProductionOfficeRecovery)
        .also {if(awaitIdentities)it.identities=null} }
    private val territory=galaxy?.let(::WorldTerritory)
    private val officePolicy = galaxy?.let { OrganizationalOfficePolicy(it) }
    private var countedOffices: PlanetStocks? = null
    private var officeCounts = emptyMap<Pair<Int, CountryId>, Int>()

    private fun officesIn(stocks: PlanetStocks): Map<Pair<Int, CountryId>, Int> {
        if (countedOffices !== stocks) {
            officeCounts = checkNotNull(officePolicy).all(stocks).groupingBy { it.character to it.country }.eachCount()
            countedOffices = stocks
        }
        return officeCounts
    }

    private fun officeRecoveryPenalty(player: Navigator): Int {
        val country = player.country ?: return 0
        val stocks = production?.snapshot(player.generation) ?: return 0
        return OfficeCpRecovery.penalty(officesIn(stocks)[player.characterId to country] ?: 0)
    }

    /** Settle whole intervals at the previous rate before publishing a new office count. */
    private fun commitProductionOfficeRecovery(old: PlanetStocks, updated: PlanetStocks, now: Long): Boolean {
        val committer = worldStateCommitter ?: return false
        val movement = navigation ?: return false
        val previous = movement.snapshot(old.generation)
        val next = settleOfficeRecovery(now, old, updated, previous)
        if (previous == next) return false
        committer.saveWorld(next, updated)
        movement.publishCommitted(previous, next, old.generation)
        return true
    }

    private fun settleOfficeRecovery(now: Long, old: PlanetStocks, updated: PlanetStocks,
        players: Map<String, Navigator>): Map<String, Navigator> {
        if (old.strategic.offices == updated.strategic.offices && old.bases == updated.bases &&
            old.outfits == updated.outfits && old.nationalOffices == updated.nationalOffices &&
            old.logisticsOfficers == updated.logisticsOfficers && old.strategic.capitals == updated.strategic.capitals) return players
        val before = officesIn(old)
        val after = officesIn(updated)
        val previousPlayers = navigation?.snapshot(old.generation).orEmpty()
        val identities by lazy { production?.identities?.invoke().orEmpty().associateBy { it.id } }
        return players.mapValues { (account, player) ->
            val oldCountry = previousPlayers[account]?.country
            val oldPenalty = OfficeCpRecovery.penalty(before[player.characterId to oldCountry] ?: 0)
            if (oldPenalty == OfficeCpRecovery.penalty(after[player.characterId to player.country] ?: 0)) return@mapValues player
            val abilities = old.strategic.people[player.characterId]?.abilities ?: profiles[account]?.abilities
                ?: identities[player.characterId]?.abilities ?: return@mapValues player
            checkNotNull(navigation).recoverCp(player, abilities[1], abilities[2], now, oldPenalty)
        }
    }
    private val published = AtomicReference(durableState?.snapshot(rules) ?: WorldSnapshot(0, 0, simulation.snapshot()))
    private val origin = monotonicMillis()
    private var elapsed = initialElapsed
    private val logistics = OutfitLogistics(monotonicMillis,galaxy)
    private val formations=OutfitFormationService(clock=monotonicMillis)
    private fun recoverNavigation(request:NavigationRequest,session:SessionState):NavigationResult? {
        val navigation=navigation ?: return null
        val player=navigation.snapshot(session.generation)[request.account]
        val now=session.gameMillis/1000
        if(player?.warpReadyAt?.let {it<=now}!=true && player?.dockReadyAt?.let {it<=now}!=true)
            return navigation.apply(request,session)
        val galaxy=galaxy ?: return navigation.apply(request,session)
        val production=production ?: return navigation.apply(request,session)
        val result=FleetNavigation.apply(request,session,galaxy,navigation,production,
            production.settle(session),worldStateCommitter) {
            logistics.blocksMovement(session,it,production.snapshot(session.generation))
        }
        // A deferred fleet arrival leaves the authenticated actor's pending work in place.
        // Let the strategic command's own policy return BUSY without charging it.
        return if(result.rejection==NavigationRejection.BUSY || result.rejection==NavigationRejection.CRUISE)
            result.copy(player=result.previous) else result
    }
    /** Due fuel/docking/warp work of an offline captain completes on the world clock (checklist §3, `evidence:guess`). */
    private fun dueOfflineWork(session:SessionState,account:String,player:Navigator):Boolean {
        val member=session.participants[account] ?: return false
        if(member.online || player.cpPaused || player.generation!=session.generation)return false
        val now=session.gameMillis/1000
        (player.dockReadyAt ?: player.warpReadyAt)?.let {return it<=now}
        return player.strategy?.jobs.orEmpty().any {it.completesAtGameMillis/1000<=now} ||
            (player.strategy==null && player.fuelReadyAt?.let {it<=now}==true)
    }
    private fun offlineAbilities(account:String,player:Navigator,stocks:PlanetStocks?,
        registered:()->List<Int>?):List<Int>? =
        (stocks?.strategic?.people?.get(player.characterId)?.abilities ?: profiles[account]?.abilities
            ?: registered())?.takeIf {it.size>2}
    /**
     * Runs the login settlement path for each offline captain whose job is due, so the fleet moves with the
     * flagship and observers see the arrival without waiting for a reconnect. A deferred fleet (a member is busy)
     * keeps its job and is retried on a later tick; only future deadlines schedule ticks, so it cannot spin.
     */
    private fun settleOfflineNavigation(session:SessionState) {
        val navigation=navigation ?: return
        if(session.ended!=null)return
        val movements=mutableListOf<NpcStrategicMovement>()
        val identities by lazy {production?.identities?.invoke().orEmpty().associateBy {it.id}}
        for((account,player) in navigation.snapshot(session.generation).toSortedMap()) {
            if(!dueOfflineWork(session,account,player))continue
            val member=session.participants.getValue(account)
            // CP recovery needs the captain's saved abilities or last join profile. Otherwise a warp waits for login:
            // arriving in contact resets the recovery clock, which would forfeit the CP earned before arrival.
            val abilities=offlineAbilities(account,player,production?.snapshot(session.generation)) {
                identities[player.characterId]?.abilities
            }
            if(abilities==null && player.warpTarget!=null)continue
            val result=recoverNavigation(NavigationRequest(account,player.characterId,player.country,member.connection.orEmpty(),
                session.generation,politics=abilities?.get(1) ?: 50,administration=abilities?.get(2) ?: 50,
                offlineSettle=true,offlineAbilities=abilities!=null),session) ?: continue
            val arrived=navigation.snapshot(session.generation)[account] ?: continue
            if(result.completedWarp && arrived.grid!=player.grid)movements+=offlineArrival(session,player,arrived)
        }
        // Same delivery as an NPC fleet arrival: grid force reports, and destination tactical viewers rebuild.
        for(event in movements)for(observer in strategicMovementObservers)try {observer(event)}
        catch(failure:Exception){System.err.println("Offline arrival delivery failed: ${failure.javaClass.simpleName}")}
    }
    private fun offlineArrival(session:SessionState,before:Navigator,after:Navigator):NpcStrategicMovement {
        val stocks=production?.snapshot(session.generation)
        val outfit=galaxy?.let {g->stocks?.outfits?.values?.singleOrNull {it.commanderId==after.characterId &&
            it.kind!=OutfitKind.GROUND && it.positionIn(g).grid==after.grid}}
        val units=outfit?.activeShips?.values.orEmpty().filter {it.boats>0}.map {it.id}.take(300)
        val audience=session.participants.mapNotNull {(account,member)->
            navigation?.snapshot(session.generation)?.get(account)?.takeIf {member.online && member.connection!=null}
                ?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
        return NpcStrategicMovement(session.generation,if(units.isEmpty())0 else outfit!!.id,before.grid,after.grid,
            session.gameMillis/1000,units,audience)
    }
    /** A commit or post-commit processing failure has blocked further durable mutations. */
    @Volatile private var storageFailed = false
    private val profiles = java.util.concurrent.ConcurrentHashMap<String, StrategicPerson>()
    /** Converts accumulated strategic CP into ability experience in one world transaction (CpExperience). */
    private fun settleExperience(session: SessionState): List<Int> {
        val committer = worldStateCommitter ?: return emptyList()
        if (navigation == null || production == null || storageFailed) return emptyList()
        val stocks = production.snapshot(session.generation) ?: return emptyList()
        val players = navigation.snapshot(session.generation)
        val (next, after) = CpExperience.settle(players, stocks) { profiles[it] } ?: return emptyList()
        val prepared = production.prepareReplacement(stocks, after)
        try { committer.saveWorld(next, prepared) }
        catch (failure: java.io.IOException) { storageFailed = true; System.err.println("CP experience commit failed: ${failure.javaClass.simpleName}"); return emptyList() }
        navigation.publishCommitted(players, next, session.generation); production.publishCommitted(stocks, prepared)
        return next.filter { (account, p) -> players[account] != p }.values.map { it.characterId }
    }
    private fun publish(snapshot:WorldSnapshot) {
        val old=published.get().planetStocks
        val result=if(snapshot.session?.ended!=null && snapshot.planetStocks!=null && galaxy!=null)
            SessionResults.fromWorld(galaxy,snapshot) else null
        val previousResult=published.get().result
        published.set(snapshot.copy(result=result))
        if(result!=null && previousResult==null)published.get().let {s->resultObservers.forEach {it(s)}}
        val inventory=snapshot.planetStocks
        // Monthly personnel changes are committed before the online character view is refreshed.
        if(inventory!=null && old!=null && old.generation==inventory.generation &&
            inventory.producedDays/PoliticianLadder.PERIOD_DAYS>old.producedDays/PoliticianLadder.PERIOD_DAYS) {
            val changedPeople=inventory.strategic.people.keys.filter {id ->
                val before=old.strategic.people[id]
                val after=inventory.strategic.people[id]
                before?.rank!=after?.rank || before?.abilities!=after?.abilities
            }
            val oldChairs=old.strategic.offices.filter {it.card==PoliticianLadder.CHAIRMAN_CARD}.toSet()
            val newChairs=inventory.strategic.offices.filter {it.card==PoliticianLadder.CHAIRMAN_CARD}.toSet()
            val changed=(changedPeople+((oldChairs-newChairs)+(newChairs-oldChairs)).map {it.character}).distinct()
            if(changed.isNotEmpty())for(observer in characterRefreshObservers)try {observer(changed)}
            catch(failure:Exception){System.err.println("Character refresh delivery failed: ${failure.javaClass.simpleName}")}
        }
        if(inventory!=null && old!=null && old.generation==inventory.generation &&
            (old.budget!=inventory.budget || old.nationalTreasuries!=inventory.nationalTreasuries ||
                old.bases.any {(id,b)->b.treasury!=inventory.bases[id]?.treasury})) {
            val bases=inventory.bases.keys.filter {old.bases[it]?.treasury!=inventory.bases[it]?.treasury ||
                old.budget.remittanceRates[it]!=inventory.budget.remittanceRates[it]}.toSet()
            val event=BudgetChange(inventory,bases,production?.defaultRemittanceRate ?: 50)
            for(observer in budgetObservers)try {observer(event)}
            catch(failure:Exception){System.err.println("Budget delivery failed: ${failure.javaClass.simpleName}")}
        }
        if(inventory!=null && old!=null && old.generation==inventory.generation && old.civic!=inventory.civic) {
            val affected=(old.civic.orders.keys+inventory.civic.orders.keys).filter {old.civic.orders[it]!=inventory.civic.orders[it]}
                .flatMap {id->listOfNotNull(old.civic.orders[id]?.base,inventory.civic.orders[id]?.base)}.toSet()
            val event=CivicChange(inventory.generation,inventory,affected)
            for(observer in civicObservers)try {observer(event)}
            catch(failure:Exception){System.err.println("Civic delivery failed: ${failure.javaClass.simpleName}")}
        }
        if(inventory!=null && old!=null)for(event in SuccessionActions.notices(old,inventory,snapshot.session?.gameSeconds ?: 0L)) {
            for(observer in successionObservers)try {observer(event)}
            catch(failure:Exception){System.err.println("Succession delivery failed: ${failure.javaClass.simpleName}")}
        }
        if(inventory!=null && old!=null)for(event in BlackHoleTides.losses(old,inventory,snapshot.session?.gameSeconds ?: 0L,galaxy)) {
            for(observer in tidalObservers)try {observer(event)}
            catch(failure:Exception){System.err.println("Tidal delivery failed: ${failure.javaClass.simpleName}")}
        }
        val session=durableState?.session ?: simulation.exportState()
        val personal=snapshot.navigators.values.asSequence().filter {it.generation==session.generation && it.cpPaused && it.baseId==0 && it.hull!=null}
            .mapNotNull {it.motion?.takeIf {m->m.grid==it.grid}}
        val ships=snapshot.planetStocks?.takeIf {it.generation==session.generation}?.outfits?.values.orEmpty().asSequence()
            .flatMap {outfit->
                val position=galaxy?.let {outfit.positionIn(it)}
                if(position==null || position.baseId!=0)emptySequence() else
                    outfit.activeShips?.values.orEmpty().asSequence().filter {
                        it.boats>0 && (it.motionCommander==0 || it.motionCommander==TacticalAuthority.commander(outfit,it,checkNotNull(snapshot.planetStocks)))
                    }.mapNotNull {it.tactical?.motion?.takeIf {m->m.grid==position.grid}}
            }
        val nowSeconds=session.gameMillis/1000
        val shipOrderDeadlines=snapshot.navigators.values.flatMap {listOfNotNull(it.posture?.order?.readyAt,
            it.fire?.pending?.firesAt,it.attack?.nextAt?.takeIf {at->at>nowSeconds})} +
            snapshot.planetStocks?.outfits?.values.orEmpty().flatMap {o->o.activeShips?.values.orEmpty().flatMap {
                listOfNotNull(it.posture?.order?.readyAt,it.support?.readyAt,it.shot?.firesAt,
                    it.attack?.order?.nextAt?.takeIf {at->at>nowSeconds})}}
        val groundDeadlines=snapshot.planetStocks?.outfits?.values.orEmpty().flatMap {o->o.activeGround?.values.orEmpty().mapNotNull {it.order?.readyAt}}
        val commands=snapshot.planetStocks?.tacticalCommands
        val commandDeadlines=commands?.orders.orEmpty().map {it.readyAt}+commands?.air.orEmpty().values.map {it.nextAt}+
            commands?.fortresses.orEmpty().values.filter {it.motion.velocity>0}.map {
                minOf(it.reportedAt+rules.speed,it.motion.arrivalAt?.takeIf {at->at>it.reportedAt} ?: Long.MAX_VALUE)}+
            commands?.missions.orEmpty().values.filter {!it.completed && it.mission!=0}.map {it.checkedAt+TacticalMissionRules.CHECK_INTERVAL_GAME_SECONDS}+
            snapshot.planetStocks?.outfits?.values.orEmpty().flatMap {o->o.activeShips?.values.orEmpty().mapNotNull {
                it.tactical?.retreat?.let {r->(if(r.membershipChecked)r.completesAt else r.startsAt).takeIf {at->at>nowSeconds}}}}
        val aiDeadlines=snapshot.planetStocks?.let {stocks->snapshot.navigators.mapNotNull {(account,p)->
            if(TacticalOfflineAi.eligible(session,account,p,stocks))maxOf(session.gameMillis/1000+rules.speed,
                stocks.tacticalCommands.aiNextAt[p.characterId] ?: 0) else null}}.orEmpty()
        val civicDeadlines=snapshot.planetStocks?.civic?.active.orEmpty().map {it.readyAt}+
            snapshot.planetStocks?.strategic?.suppressions.orEmpty().mapNotNull {it.readyAt.takeIf {at->at>nowSeconds}}
        // Offline navigation jobs: future completions only (a deferred, already-due job must not re-arm an immediate tick).
        // A ready retreat blocked by position or destination is retried by ordinary ticks.
        val retreatDeadlines=snapshot.navigators.values.mapNotNull {it.retreat?.completesAt?.takeIf {at->at>nowSeconds}}
        val identities by lazy {production?.identities?.invoke().orEmpty().associateBy {it.id}}
        val navigationDeadlines=snapshot.navigators.filter {(account,p)->p.generation==session.generation && !p.cpPaused &&
            session.participants[account]?.online==false &&
            (p.warpTarget==null || offlineAbilities(account,p,snapshot.planetStocks) {identities[p.characterId]?.abilities}!=null)}.values.flatMap {p->
            (p.dockReadyAt ?: p.warpReadyAt)?.let(::listOf) ?:
                (p.strategy?.jobs.orEmpty().map {it.completesAtGameMillis/1000} + listOfNotNull(p.fuelReadyAt.takeIf {p.strategy==null}))
        }.filter {it>nowSeconds}
        val trade=snapshot.planetStocks?.social?.trade
        val tradeDeadlines=(trade?.routes.orEmpty().values.map {minOf(it.until,it.paidThrough+Justice.DAY)}+
            trade?.convoys.orEmpty().values+trade?.pacts.orEmpty().values+trade?.passages.orEmpty().values)
            .filter {it>nowSeconds}
        val npcWarpDeadline=if(npcPolicy.enabled && npcPolicy.strategic && worldStateCommitter!=null && session.ended==null)
            snapshot.planetStocks?.outfits?.values.orEmpty().mapNotNull {it.strategy?.arrivesAt?.takeIf {at->at>nowSeconds}}.minOrNull()
            else null
        val shipProductionDeadlines=snapshot.planetStocks?.social?.production?.orders.orEmpty().values
            .map {it.readyAt}.filter {it>nowSeconds}
        val operationDeadline=snapshot.planetStocks?.strategic?.operations.orEmpty().asSequence()
            .filter {it.live}.mapNotNull {it.issuedAt}.map(OperationPlanRules::endGameSeconds)
            .filter {it>nowSeconds}.minOrNull()
        val deadline=((personal+ships).mapNotNull {it.nextProgressAt}.toList()+shipOrderDeadlines+groundDeadlines+commandDeadlines+aiDeadlines+civicDeadlines+navigationDeadlines+retreatDeadlines+shipProductionDeadlines+tradeDeadlines+listOfNotNull(npcWarpDeadline,operationDeadline)+snapshot.planetStocks?.groundBattles.orEmpty().values.map {it+5*rules.speed}).minOrNull()
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
        fun reconcileTerritory() {
            if(storageFailed)return
            val session=durableState?.session ?: simulation.exportState()
            if(session.ended!=null)return
            val inventory=production?.snapshot(session.generation) ?: return
            val action=territory?.ending(inventory,session.countries,session.eliminated.keys) ?: return
            val events=if(durability!=null) {
                val next=transitionSession(rules,checkNotNull(durableState),action)
                check(!next.rejected) {"Derived territory result was rejected"}
                // The world is already committed. A crash before this force is recovered by the
                // same derivation on the first command; no new movement/join is accepted first.
                try {durability.commit(action,next)} catch(failure:Exception) {
                    storageFailure=failure;storageFailed=true;throw failure
                }
                durableState=next.state
                state=next.state.snapshot(rules)
                next.events
            } else simulation.updateCountryTerritory(action.systems,action.capitals,action.reductionEligible).also {events->
                if(events.any {it is WorldEvent.Ended})restartAt=Math.addExact(elapsed,rules.restartDelayRealMillis)
                state=state.copy(revision=Math.addExact(state.revision,1),session=simulation.snapshot())
            }
            state=state.copy(navigators=navigation?.snapshot(session.generation).orEmpty(),planetStocks=inventory)
            publish(state)
            events.forEach {event->try {onEvent(event)} catch(failure:Exception) {
                System.err.println("Session end delivery failed: ${failure.javaClass.simpleName}")
            }}
        }
        try { for (queued in queue) { try {
            try {reconcileTerritory()} catch(failure:Exception) {
                storageFailure=failure;storageFailed=true
            }
            val command = if(queued.advanceNow) WorldCommand.AdvanceTo(
                maxOf(durableState?.elapsed ?: elapsed,if(ticking)Math.addExact(initialElapsed,monotonicMillis()-origin)
                    else durableState?.elapsed ?: elapsed)) else queued.command
            if(command is WorldCommand.AdvanceTo && awaitIdentities && production!=null && production.identities==null)continue
            if(command is WorldCommand.Quiesce<*>) {
                @Suppress("UNCHECKED_CAST") val quiesce=command as WorldCommand.Quiesce<Any?>
                try {quiesce.result.complete(quiesce.action())} catch(failure:Exception) {quiesce.result.completeExceptionally(failure)}
                continue
            }
            if(command is WorldCommand.AdminEditBase) {
                try {
                    if(storageFailed || production==null || navigation==null || worldStateCommitter==null) {
                        command.result.complete(AdminBaseEditResult(AdminEditOutcome.UNAVAILABLE));continue
                    }
                    val session=durableState?.session ?: simulation.exportState()
                    val inventory=production.settle(session)
                    var result=AdminWorldEditing.evaluate(inventory,command.request)
                    if(result.outcome==AdminEditOutcome.CHANGED) {
                        val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                        worldStateCommitter.saveWorld(navigation.snapshot(session.generation),prepared)
                        production.publishCommitted(inventory,prepared)
                        result=result.copy(stocks=prepared)
                    }
                    state=published.get().copy(planetStocks=production.snapshot(session.generation))
                    publish(state);command.result.complete(result)
                } catch(failure:Exception) {
                    if(failure is java.io.IOException){storageFailed=true;storageFailure=failure}
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if(command is WorldCommand.FormOutfit) {
                try {
                    check(!storageFailed) {"World storage is unavailable"}
                    val r=command.request;val i=r.identity
                    val session=durableState?.session ?: simulation.exportState()
                    val recovered=recoverNavigation(NavigationRequest(i.account,i.characterId,i.country,i.connection,i.generation,
                        politics=i.politics,administration=i.administration),session)
                    val inventory=checkNotNull(production).settle(session)
                    val before=recovered?.player
                    val base=r.base.takeIf {it>0} ?: before?.baseId ?: 0
                    val scope=inventory.outfits.values.filter {it.baseId==base}.map {it.id}.toSet()+
                        (if(r.phase==FormationPhase.DELETE)setOf(r.target) else emptySet())
                    var result=if(r.phase!=FormationPhase.CANCEL && logistics.blocksSupply(session,inventory,scope))
                        OutfitFormationResult(before,inventory,StrategyActionRejection.BUSY,base=base)
                    else formations.evaluate(checkNotNull(galaxy),r.copy(identity=i.copy(
                        navigators=checkNotNull(navigation).snapshot(session.generation))),session,before,inventory)
                    if(result.changed) {
                        if(worldStateCommitter==null)result=OutfitFormationResult(before,inventory,StrategyActionRejection.STORAGE,base=base)
                        else {
                            val after=checkNotNull(result.player)
                            val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation)+(i.account to after),prepared)
                            navigation.publishCommitted(i.account,checkNotNull(before),after)
                            production.publishCommitted(inventory,prepared)
                            formations.complete(r)
                            result=result.copy(stocks=prepared)
                        }
                    }
                    state=published.get().copy(navigators=navigation?.snapshot(session.generation).orEmpty(),planetStocks=production.snapshot(session.generation))
                    publish(state);command.result.complete(result.copy(recovery=recovered))
                } catch(failure:Exception) {
                    if(failure is java.io.IOException){storageFailed=true;storageFailure=failure}
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if(command is WorldCommand.StrategyAction) {
                try {
                    check(!storageFailed) {"World storage is unavailable"}
                    val request=command.request
                    val session=durableState?.session ?: simulation.exportState()
                    val isFounding=request.command==CountryFounding.COMMAND
                    val useSnapshot=request.repairPreview || request.command==IndependentBaseClaim.COMMAND || isFounding
                    val recovered=if(useSnapshot)null else recoverNavigation(NavigationRequest(request.account,request.characterId,request.country,
                        request.connection,request.generation,politics=request.politics,administration=request.administration),session)
                    val inventory=checkNotNull(production).settle(session)
                    val players=checkNotNull(navigation).snapshot(session.generation)
                    val player=if(useSnapshot)players[request.account] else recovered?.player
                    var result=if(isFounding && worldStateCommitter==null)
                        StrategyActionResult(player,inventory,StrategyActionRejection.STORAGE)
                    else StrategyActions.evaluate(checkNotNull(galaxy),request.copy(
                        navigators=players),session,player,inventory,founding=founding)
                    val civicReadOrCancel=request.command in CivicActions.supported && request.value!=0
                    if(!civicReadOrCancel && result.rejection==null && request.outfit>0 && logistics.blocksSupply(session,inventory,setOf(request.outfit)))
                        result=StrategyActionResult(player,inventory,StrategyActionRejection.BUSY)
                    if(result.rejection==null && request.command==24 && request.card !in setOf(30,171) &&
                        logistics.blocksSupply(session,inventory,setOf(request.base)))
                        result=StrategyActionResult(recovered?.player,inventory,StrategyActionRejection.BUSY)
                    if(result.changed) {
                        if(worldStateCommitter==null)result=StrategyActionResult(player,inventory,StrategyActionRejection.STORAGE)
                        else {
                            val after=checkNotNull(result.player)
                            val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                            val previous=checkNotNull(navigation).snapshot(session.generation)
                            require(result.navigators.all {(account,player)->previous[account]?.let {
                                it.generation==player.generation && it.characterId==player.characterId && it.country==player.country}==true})
                            val next=settleOfficeRecovery(session.gameMillis / 1000, inventory, prepared,
                                previous+result.navigators+(request.account to after))
                            val membership=if(isFounding)SessionAction.Defect(request.account,checkNotNull(after.country)) else null
                            val transition=if(membership!=null && durability!=null)
                                transitionSession(rules,checkNotNull(durableState),membership).also {
                                    check(!it.rejected) {"Founding membership was rejected"}
                                } else null
                            worldStateCommitter.saveWorld(next,prepared)
                            if(membership!=null) {
                                if(transition!=null) {
                                    try {checkNotNull(durability).commit(membership,transition)} catch(failure:Exception) {
                                        throw java.io.IOException("Founding membership must be recovered",failure)
                                    }
                                    durableState=transition.state
                                } else simulation.defect(membership.account,membership.country)
                            }
                            navigation.publishCommitted(previous,next,session.generation)
                            production.publishCommitted(inventory,prepared)
                            result=result.copy(player=next.getValue(request.account),stocks=prepared,
                                navigators=result.navigators.mapValues { (account, _) -> next.getValue(account) })
                            if(isFounding)reconcileTerritory()
                        }
                    }
                    state=(if(isFounding) durableState?.snapshot(rules) ?: state.copy(session=simulation.snapshot())
                        else published.get()).copy(navigators=navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks=production.snapshot(session.generation))
                    publish(state);command.result.complete(result.copy(recovery=recovered))
                } catch(failure:Exception) {
                    if(failure is java.io.IOException){storageFailed=true;storageFailure=failure}
                    if(failure is java.io.IOException &&
                        command.request.command in setOf(IndependentBaseClaim.COMMAND,CountryFounding.COMMAND))
                        command.result.complete(StrategyActionResult(null,null,StrategyActionRejection.STORAGE))
                    else command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.AssignStock) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session)
                    val before = recovered?.player
                    val result = run assignment@{
                        fun reject(reason: LogisticsRejection) = StockAssignmentResult(before, inventory, reason)
                        if(formations.blocksBase(session,request.base))return@assignment reject(LogisticsRejection.BUSY)
                        if (before == null) return@assignment reject(LogisticsRejection.IDENTITY)
                        if (worldStateCommitter == null) return@assignment reject(LogisticsRejection.STORAGE)
                        val transfer = try { StockAssignment.transfer(request, inventory) }
                            catch (_: IllegalArgumentException) { return@assignment reject(LogisticsRejection.STOCK) }
                            catch (_: NoSuchElementException) { return@assignment reject(LogisticsRejection.OUTFIT) }
                        val prepare = LogisticsRequest(request.account, request.connection, request.characterId, request.country,
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
                        val after = before.paidWith(payment.balance)
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
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session)
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
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session)
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
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session)
                    var result = CompletenessSupply.evaluate(request, session, recovered?.player, inventory)
                    if (result.rejection == null && logistics.blocksSupply(session, inventory, setOf(result.source, request.to)))
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
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production).settle(session)
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
                        val prepare = LogisticsRequest(request.account, request.connection, request.characterId, request.country,
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
                        val after = before.paidWith(payment.balance)
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
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production) { "World production is disabled" }.settle(session)
                    var result = GarrisonAppointment.evaluate(checkNotNull(galaxy), request, session, recovered?.player, inventory)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = GarrisonAppointmentResult(recovered?.player, inventory, AppointmentRejection.STORAGE)
                        else {
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            val previous = checkNotNull(navigation).snapshot(session.generation)
                            val next = settleOfficeRecovery(session.gameMillis / 1000, inventory, prepared, previous + (request.account to after))
                            worldStateCommitter.saveWorld(next, prepared)
                            navigation.publishCommitted(previous, next, session.generation)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(player = next.getValue(request.account), stocks = prepared)
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
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production) { "World production is disabled" }.settle(session)
                    var result = OfficeDismissal.evaluate(checkNotNull(galaxy), request, session, recovered?.player, inventory)
                    if (result.changed) {
                        if (worldStateCommitter == null) result = OfficeDismissalResult(recovered?.player, inventory, DismissalRejection.STORAGE)
                        else {
                            val after = checkNotNull(result.player)
                            val prepared = production.prepareReplacement(inventory, checkNotNull(result.stocks))
                            val previous = checkNotNull(navigation).snapshot(session.generation)
                            val next = settleOfficeRecovery(session.gameMillis / 1000, inventory, prepared, previous + (request.account to after))
                            worldStateCommitter.saveWorld(next, prepared)
                            navigation.publishCommitted(previous, next, session.generation)
                            production.publishCommitted(inventory, prepared)
                            result = result.copy(player = next.getValue(request.account), stocks = prepared)
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
                    val recovered=recoverNavigation(NavigationRequest(request.account,request.characterId,request.country,
                        request.connection,request.generation,politics=request.politics,administration=request.administration),session)
                    val inventory=checkNotNull(production) { "World production is disabled" }.settle(session)
                    var result=OfficeResignation.evaluate(checkNotNull(galaxy),request,session,recovered?.player,inventory)
                    if(result.changed) {
                        if(worldStateCommitter==null) result=OfficeResignationResult(recovered?.player,inventory,ResignationRejection.STORAGE)
                        else {
                            val after=checkNotNull(result.player)
                            val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                            val previous=checkNotNull(navigation).snapshot(session.generation)
                            val next=settleOfficeRecovery(session.gameMillis / 1000,inventory,prepared,previous+(request.account to after))
                            worldStateCommitter.saveWorld(next,prepared)
                            navigation.publishCommitted(previous,next,session.generation)
                            production.publishCommitted(inventory,prepared)
                            result=result.copy(player=next.getValue(request.account),stocks=prepared)
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
            if(command is WorldCommand.Social) {
                try {
                    check(!storageFailed) {"World storage is unavailable"}
                    val r=command.request;val i=r.identity;val session=durableState?.session ?: simulation.exportState()
                    val tradeQuote=TradeActions.quotes(i.command,i.value) && r.phase==TradeActions.QUOTE_PHASE
                    val productionQuote=i.command in ProductionActions.commands && r.phase==ProductionActions.QUOTE_PHASE
                    val strikeQuote=i.command==OrbitalStrikeActions.STRIKE && r.phase==OrbitalStrikeActions.QUOTE_PHASE
                    if(tradeQuote || productionQuote || strikeQuote) {
                        val inventory=production?.snapshot(session.generation)
                        val navigators=navigation?.snapshot(session.generation).orEmpty()
                        val player=navigators[i.account]
                        command.result.complete(if(inventory==null)SocialResult(player,null,SocialRejection.SESSION)
                            else SocialActions.evaluate(checkNotNull(galaxy),r,session,player,inventory,
                                if(strikeQuote)navigators.values else emptyList()))
                        continue
                    }
                    val recovered=recoverNavigation(NavigationRequest(i.account,i.characterId,i.country,i.connection,i.generation,
                        politics=i.politics,administration=i.administration),session)
                    val inventory=checkNotNull(production).settle(session)
                    val accounts=navigation?.snapshot(session.generation).orEmpty().let {s->recovered?.player?.let {s+(i.account to it)} ?: s}
                    var result=SocialActions.evaluate(checkNotNull(galaxy),r,session,recovered?.player,inventory,
                        navigation?.snapshot(session.generation)?.values.orEmpty(),accounts)
                    // Keep the startup membership fence on live defection too, until the remaining world APIs migrate.
                    if(result.defected?.value?.let {it !in 2..3}==true)
                        result=SocialResult(recovered?.player,inventory,SocialRejection.CARD)
                    if(result.changed) {
                        if(worldStateCommitter==null)result=SocialResult(recovered?.player,inventory,SocialRejection.STORAGE)
                        else {
                            val after=checkNotNull(result.player)
                            val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                            val previous=checkNotNull(navigation).snapshot(session.generation)
                            require(result.navigators.all {(account,player)->account!=i.account && previous[account]?.let {
                                it.generation==player.generation && it.characterId==player.characterId}==true})
                            val recoveredPlayers=settleOfficeRecovery(session.gameMillis / 1000,inventory,prepared,
                                previous+result.navigators+(i.account to after))
                            val next=if(inventory.social.coup!=prepared.social.coup)recoveredPlayers.mapValues {(_,player)->
                                navigation.clearInvalidReturnBase(player,prepared)
                            } else recoveredPlayers
                            worldStateCommitter.saveWorld(next,prepared)
                            navigation.publishCommitted(previous,next,session.generation)
                            production.publishCommitted(inventory,prepared)
                            result=result.copy(player=next.getValue(i.account),stocks=prepared,
                                navigators=result.navigators.mapValues { (account, _) -> next.getValue(account) })
                            // U-25 defection: the session record follows the committed world (user decision 2026-11-01).
                            if(result.defected!=null) {
                                val action=SessionAction.Defect(i.account,result.defected)
                                if(durability!=null) {
                                    val next=transitionSession(rules,checkNotNull(durableState),action)
                                    check(!next.rejected) {"Defection was rejected by the session"}
                                    durability.commit(action,next);durableState=next.state
                                } else simulation.defect(action.account,action.country)
                            }
                        }
                    }
                    state=published.get().copy(navigators=navigation?.snapshot(session.generation).orEmpty(),planetStocks=production.snapshot(session.generation))
                    publish(state);command.result.complete(result.copy(recovery=recovered))
                } catch(failure:Exception) {
                    if(failure is java.io.IOException){storageFailed=true;storageFailure=failure}
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if(command is WorldCommand.Budget) {
                try {
                    check(!storageFailed) {"World storage is unavailable"}
                    val r=command.request;val i=r.identity;val session=durableState?.session ?: simulation.exportState()
                    val recovered=recoverNavigation(NavigationRequest(i.account,i.characterId,i.country,i.connection,i.generation,
                        politics=i.politics,administration=i.administration),session)
                    val inventory=checkNotNull(production).settle(session)
                    var result=BudgetActions.evaluate(checkNotNull(galaxy),r,session,recovered?.player,inventory,production.defaultRemittanceRate)
                    if(result.changed) {
                        if(worldStateCommitter==null)result=BudgetResult(recovered?.player,inventory,BudgetRejection.STORAGE)
                        else {
                            val after=checkNotNull(result.player);val before=checkNotNull(recovered?.player)
                            val prepared=production.prepareReplacement(inventory,checkNotNull(result.stocks))
                            worldStateCommitter.saveWorld(checkNotNull(navigation).snapshot(session.generation)+(i.account to after),prepared)
                            navigation.publishCommitted(i.account,before,after);production.publishCommitted(inventory,prepared)
                            result=result.copy(stocks=prepared)
                        }
                    }
                    state=published.get().copy(navigators=navigation?.snapshot(session.generation).orEmpty(),planetStocks=production.snapshot(session.generation))
                    publish(state);command.result.complete(result.copy(recovery=recovered))
                } catch(failure:Exception) {
                    if(failure is java.io.IOException){storageFailed=true;storageFailure=failure}
                    command.result.completeExceptionally(failure)
                }
                continue
            }
            if (command is WorldCommand.Tax) {
                try {
                    check(!storageFailed) { "World storage is unavailable" }
                    val request = command.request
                    val session = durableState?.session ?: simulation.exportState()
                    val recovered = recoverNavigation(NavigationRequest(request.account, request.characterId, request.country,
                        request.connection, request.generation, politics = request.politics, administration = request.administration), session)
                    val inventory = checkNotNull(production) { "World production is disabled" }.settle(session)
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
                    val inventory=checkNotNull(production) { "World production is disabled" }
                    val base=inventory.snapshot(session.generation)?.outfits?.get(command.request.outfitId)?.baseId ?: 0
                    val result = if(command.request.mode==LogisticsMode.ALLOCATION && command.request.phase!=LogisticsPhase.CANCEL &&
                        formations.blocksBase(session,base))LogisticsResult(rejection=LogisticsRejection.BUSY)
                    else logistics.apply(command.request, session,
                        navigation?.snapshot(session.generation)?.get(command.request.account),
                        inventory)
                    state = published.get().copy(planetStocks = inventory.snapshot(session.generation))
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
                    val result = checkNotNull(production) { "World production is disabled" }.settle(session)
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
                    var session = durableState?.session ?: simulation.exportState()
                    val ownership = production?.settle(session)
                    settleCombat(session,published.get()) {reconcileTerritory()}
                    session=durableState?.session ?: simulation.exportState()
                    val result = if (navigation != null && production != null && ownership != null && galaxy != null)
                        TacticalCommandOperations.guard(command.request,session,navigation,checkNotNull(production.snapshot(session.generation))) ?:
                        TacticalGroundOperations.guard(command.request,session,navigation,checkNotNull(production.snapshot(session.generation))) ?:
                        TacticalPostureOperations.guard(command.request,session,navigation,
                            checkNotNull(production.snapshot(session.generation))) ?: if(command.request.tacticalCommand!=null)
                            TacticalCommandOperations.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if(command.request.tacticalGround!=null)
                            TacticalGroundOperations.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if(command.request.tacticalPosture!=null)
                            TacticalPostureOperations.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if(command.request.tacticalRetreat?.any {it in 1..999999}==true)
                            TacticalFleetRetreat.accept(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter,rules.speed)
                        else if (command.request.tacticalStop!=null)
                            TacticalSupportOperations.stop(command.request,session,galaxy,navigation,production,
                                checkNotNull(production.snapshot(session.generation)),worldStateCommitter)
                        else if(command.request.tacticalMove!=null)
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
                            checkNotNull(production.snapshot(session.generation)), worldStateCommitter,profiles[command.request.account]) { outfit ->
                            logistics.blocksMovement(session,outfit,production.snapshot(session.generation))
                        } else NavigationResult(null, rejection = NavigationRejection.DISABLED)
                    // Revalidate local duties immediately after committed movement, including spot
                    // departures. A failed invalidation blocks later commands; restart sees the saved departure.
                    production?.settle(session)
                    state = published.get().copy(navigators = navigation?.snapshot(session.generation).orEmpty(),
                        planetStocks = production?.snapshot(session.generation))
                    publish(state)
                    publishSupport(result.supportNotifications)
                    publishTacticalCommands(result.commandNotifications)
                    result.player?.let {player ->
                        val recipients=session.participants.filter {(_,member)->member.online && member.connection!=command.request.connection}
                            .mapNotNull {(account,member)->state.navigators[account]?.takeIf {
                                it.grid==player.grid && it.baseId==0 && it.cpPaused}?.let {it.characterId to checkNotNull(member.connection)}}.toMap()
                        val formation=result.tacticalMotions.takeIf {it.values.any {m->m.rotation!=null}}.orEmpty()
                        val stopped=command.request.tacticalStop!=null
                        val deliveries=if(formation.isEmpty())result.tacticalMotions.filterKeys {stopped || it in 1..999999}
                            else formation.entries.take(1).associate {it.key to it.value}
                        for((id,motion) in deliveries) {
                            val event=TacticalMovement(session.generation,id,player.grid,session.gameMillis/1000,motion,recipients,
                                character=player.characterId,stopped=stopped,formation=formation)
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
                        production?.settle(next.state.session)
                        settleCombat(next.state.session,next.state.snapshot(rules)) {reconcileTerritory()}
                        val current=checkNotNull(durableState).session
                        navigation?.settleRetreats(current, production?.snapshot(current.generation))
                        settleOfflineNavigation(current)
                    }
                    val current=checkNotNull(durableState)
                    publish(current.snapshot(rules).copy(navigators = navigation?.snapshot(current.session.generation).orEmpty(),
                        planetStocks = production?.snapshot(current.session.generation)))
                    if (command is WorldCommand.Join) {
                        if (next.rejected) command.result.completeExceptionally(IllegalArgumentException("Invalid join"))
                        else {
                            if (next.admission == Admission.ACCEPTED) command.profile?.let { profiles[command.account] = it }
                            command.result.complete(checkNotNull(next.admission))
                        }
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
                    if (!storageFailed) production?.settle(simulation.exportState())
                    settleCombat(simulation.exportState(),published.get().copy(session=simulation.snapshot())) {reconcileTerritory()}
                    navigation?.settleRetreats(simulation.exportState(), production?.snapshot(simulation.snapshot().generation))
                    if (!storageFailed) settleOfflineNavigation(simulation.exportState())
                    if (events.any { it is WorldEvent.Ended }) restartAt = elapsed + rules.restartDelayRealMillis
                    events
                }
                is WorldCommand.Join -> {
                    admission = if (command.generation != null && command.generation != simulation.snapshot().generation) Admission.STALE_SESSION
                        else simulation.join(command.account, command.country, command.original, command.connection)
                    if (admission == Admission.ACCEPTED) command.profile?.let { profiles[command.account] = it }
                    emptyList()
                }
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
                is WorldCommand.Budget -> error("Budget is handled before session commands")
                is WorldCommand.Social -> error("Social is handled before session commands")
                is WorldCommand.SupplyOutfit -> error("Supply is handled before session commands")
                is WorldCommand.TransferGround -> error("Ground transfer is handled before session commands")
                is WorldCommand.SupplementOutfit -> error("Supplement is handled before session commands")
                is WorldCommand.Reorganize -> error("Reorganization is handled before session commands")
                is WorldCommand.AssignStock -> error("Stock assignment is handled before session commands")
                is WorldCommand.StrategyAction -> error("Strategy action is handled before session commands")
                is WorldCommand.FormOutfit -> error("Outfit formation is handled before session commands")
                is WorldCommand.Quiesce<*> -> error("Quiesce is handled before session commands")
                is WorldCommand.AdminEditBase -> error("Operator edits are handled before session commands")
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
            enqueue(WorldCommand.AdvanceTo(0), scheduled,applied,advanceNow=true)
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
        galaxy!=null && published.get().planetStocks==null -> EngineHealth.STARTING
        else -> EngineHealth.RUNNING
    }
    suspend fun drainAndClose() { ticker?.cancelAndJoin(); queue.close(); worker.join() }
    override fun close() { ticker?.cancel(); queue.close() }
}
