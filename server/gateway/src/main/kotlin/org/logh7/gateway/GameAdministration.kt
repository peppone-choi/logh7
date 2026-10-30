package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameAdministration {
    fun tax(request: TaxRequest): CompletableFuture<TaxResult>
    fun budget(request:BudgetRequest):CompletableFuture<BudgetResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Budget is unavailable"))
    fun social(request:SocialRequest):CompletableFuture<SocialResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Social command is unavailable"))
    fun strategyAction(request:StrategyActionRequest):CompletableFuture<StrategyActionResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Strategy action is unavailable"))
    fun formOutfit(request:OutfitFormationRequest):CompletableFuture<OutfitFormationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Formation is unavailable"))
    fun appointGarrison(request: GarrisonAppointmentRequest): CompletableFuture<GarrisonAppointmentResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Appointment is unavailable"))
    fun dismissOffice(request:OfficeDismissalRequest):CompletableFuture<OfficeDismissalResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Dismissal is unavailable"))
    fun resignOffice(request:OfficeResignationRequest):CompletableFuture<OfficeResignationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Resignation is unavailable"))
    fun reorganize(request: ReorganizationRequest): CompletableFuture<ReorganizationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Reorganization is unavailable"))
    fun transferGround(request:GroundTransferRequest):CompletableFuture<GroundTransferResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Ground transfer is unavailable"))
    fun supplementOutfit(request: SupplementRequest): CompletableFuture<SupplementResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Supplement is unavailable"))
    fun supplyOutfit(request: CompletenessSupplyRequest): CompletableFuture<CompletenessSupplyResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Supply is unavailable"))
    fun assignStock(request: StockAssignmentRequest): CompletableFuture<StockAssignmentResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Stock assignment is unavailable"))
    fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> = emptyList()
    fun stationedOutfit(player: Navigator): Int = 0
}
class EngineGameAdministration(private val engine: WorldEngine, private val scope: CoroutineScope, private val galaxy: Galaxy) : GameAdministration {
    override fun social(request:SocialRequest):CompletableFuture<SocialResult> {
        val future=CompletableFuture<SocialResult>()
        scope.launch {try {
            val result=CompletableDeferred<SocialResult>();engine.submit(WorldCommand.Social(request,result));future.complete(result.await())
        } catch(failure:Throwable){future.completeExceptionally(failure)}}
        return future
    }
    override fun budget(request:BudgetRequest):CompletableFuture<BudgetResult> {
        val future=CompletableFuture<BudgetResult>()
        scope.launch {try {
            val result=CompletableDeferred<BudgetResult>();engine.submit(WorldCommand.Budget(request,result));future.complete(result.await())
        } catch(failure:Throwable){future.completeExceptionally(failure)}}
        return future
    }
    private val baseOffices=BaseOfficeCards(galaxy)
    private val nationalOffices=NationalOfficePolicy(galaxy)
    private val logisticsOffices=LogisticsOfficePolicy(galaxy)
    override fun formOutfit(request:OutfitFormationRequest):CompletableFuture<OutfitFormationResult> {
        val future=CompletableFuture<OutfitFormationResult>()
        scope.launch {
            try {
                val result=CompletableDeferred<OutfitFormationResult>()
                engine.submit(WorldCommand.FormOutfit(request,result));future.complete(result.await())
            } catch(failure:Throwable){future.completeExceptionally(failure)}
        }
        return future
    }
    override fun strategyAction(request:StrategyActionRequest):CompletableFuture<StrategyActionResult> {
        val future=CompletableFuture<StrategyActionResult>()
        scope.launch {
            try {
                val result=CompletableDeferred<StrategyActionResult>()
                engine.submit(WorldCommand.StrategyAction(request,result));future.complete(result.await())
            } catch(failure:Throwable){future.completeExceptionally(failure)}
        }
        return future
    }
    override fun resignOffice(request:OfficeResignationRequest):CompletableFuture<OfficeResignationResult> {
        val future=CompletableFuture<OfficeResignationResult>()
        scope.launch {
            try {
                val result=CompletableDeferred<OfficeResignationResult>()
                engine.submit(WorldCommand.ResignOffice(request,result));future.complete(result.await())
            } catch(failure:Throwable) {future.completeExceptionally(failure)}
        }
        return future
    }
    override fun appointGarrison(request: GarrisonAppointmentRequest): CompletableFuture<GarrisonAppointmentResult> {
        val future=CompletableFuture<GarrisonAppointmentResult>()
        scope.launch {
            try {
                val result=CompletableDeferred<GarrisonAppointmentResult>()
                engine.submit(WorldCommand.AppointGarrison(request,result));future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun dismissOffice(request: OfficeDismissalRequest): CompletableFuture<OfficeDismissalResult> {
        val future=CompletableFuture<OfficeDismissalResult>()
        scope.launch {
            try {
                val result=CompletableDeferred<OfficeDismissalResult>()
                engine.submit(WorldCommand.DismissOffice(request,result));future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun stationedOutfit(player: Navigator): Int {
        if (player.mode !in 4..5) return 0
        val stocks = engine.snapshot().planetStocks ?: return 0
        if (stocks.generation != player.generation) return 0
        // One unambiguous fleet office at the actual deployment, including space.
        return stocks.outfits.values.singleOrNull { player.characterId in it.members() &&
            it.power == (if (player.power == Power.EMPIRE) 2 else 3) &&
                it.positionIn(galaxy).let { position -> position.grid == player.grid && position.baseId == player.baseId } }?.id ?: 0
    }
    override fun transferGround(request: GroundTransferRequest): CompletableFuture<GroundTransferResult> {
        val future = CompletableFuture<GroundTransferResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<GroundTransferResult>()
                engine.submit(WorldCommand.TransferGround(request, result)); future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun supplementOutfit(request: SupplementRequest): CompletableFuture<SupplementResult> {
        val future = CompletableFuture<SupplementResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<SupplementResult>()
                engine.submit(WorldCommand.SupplementOutfit(request, result)); future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun supplyOutfit(request: CompletenessSupplyRequest): CompletableFuture<CompletenessSupplyResult> {
        val future = CompletableFuture<CompletenessSupplyResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<CompletenessSupplyResult>()
                engine.submit(WorldCommand.SupplyOutfit(request, result)); future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun reorganize(request: ReorganizationRequest): CompletableFuture<ReorganizationResult> {
        val future = CompletableFuture<ReorganizationResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<ReorganizationResult>()
                engine.submit(WorldCommand.Reorganize(request, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun assignStock(request: StockAssignmentRequest): CompletableFuture<StockAssignmentResult> {
        val future = CompletableFuture<StockAssignmentResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<StockAssignmentResult>()
                engine.submit(WorldCommand.AssignStock(request, result)); future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
    override fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> {
        val stocks = engine.snapshot().planetStocks ?: return emptyList()
        if (stocks.generation != generation) return emptyList()
        val policy=OrganizationalOfficePolicy(galaxy)
        val cards=(policy.held(stocks,characterId).filter {OrganizationOffices.of(it.card)?.power==power}.map {holding ->
            org.logh7.protocol.BootstrapMessages.OfficeCard(holding.card,policy.target(checkNotNull(OrganizationOffices.of(holding.card)),holding.seat))
        }+stocks.strategic.people[characterId]?.peerage?.let {title->
            listOf(org.logh7.protocol.BootstrapMessages.OfficeCard(60+title.ordinal,0))}.orEmpty()+
            stocks.strategic.fiefs.filterValues {it==characterId}.keys.map {org.logh7.protocol.BootstrapMessages.OfficeCard(66,it)})
            .distinct().sortedWith(compareBy({it.kind},{it.target}))
        require(cards.size<=14) {"Stored offices exceed character card capacity"}
        return cards
    }
    override fun tax(request: TaxRequest): CompletableFuture<TaxResult> {
        val future = CompletableFuture<TaxResult>()
        scope.launch {
            try {
                val result = CompletableDeferred<TaxResult>()
                engine.submit(WorldCommand.Tax(request, result))
                future.complete(result.await())
            } catch (failure: Throwable) { future.completeExceptionally(failure) }
        }
        return future
    }
}
