package org.logh7.gateway

import kotlinx.coroutines.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

fun interface GameAdministration {
    fun tax(request: TaxRequest): CompletableFuture<TaxResult>
    fun appointGarrison(request: GarrisonAppointmentRequest): CompletableFuture<GarrisonAppointmentResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Appointment is unavailable"))
    fun dismissOffice(request:OfficeDismissalRequest):CompletableFuture<OfficeDismissalResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Dismissal is unavailable"))
    fun resignOffice(request:OfficeResignationRequest):CompletableFuture<OfficeResignationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Resignation is unavailable"))
    fun reorganize(request: ReorganizationRequest): CompletableFuture<ReorganizationResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Reorganization is unavailable"))
    fun assignStock(request: StockAssignmentRequest): CompletableFuture<StockAssignmentResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Stock assignment is unavailable"))
    fun offices(characterId: Int, power: Int, generation: Long): List<org.logh7.protocol.BootstrapMessages.OfficeCard> = emptyList()
    fun stationedOutfit(player: Navigator): Int = 0
}
class EngineGameAdministration(private val engine: WorldEngine, private val scope: CoroutineScope, galaxy: Galaxy) : GameAdministration {
    private val baseOffices=BaseOfficeCards(galaxy)
    private val nationalOffices=NationalOfficePolicy(galaxy)
    private val logisticsOffices=LogisticsOfficePolicy(galaxy)
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
        if (player.mode != 4 || player.baseId == 0) return 0
        val stocks = engine.snapshot().planetStocks ?: return 0
        if (stocks.generation != player.generation) return 0
        // Designed affiliation: one unambiguous fleet office at this same base.
        return stocks.outfits.values.singleOrNull { player.characterId in it.members() &&
            it.power == (if (player.power == Power.EMPIRE) 2 else 3) && it.baseId == player.baseId }?.id ?: 0
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
        val appointed = stocks.bases.values.filter { it.power == power }.sortedBy { it.baseId }
            .flatMap { baseOffices.cards(it,characterId) }
        val commanders = stocks.outfits.values.filter { it.commanderId == characterId && it.power == power }
            .sortedBy { it.id }.map { outfit ->
                val kind = when (outfit.kind) {
                    OutfitKind.FLEET -> 39
                    OutfitKind.TRANSPORT -> 44
                    OutfitKind.PATROL -> 47
                    OutfitKind.GROUND -> 50
                } + if (power == 3) 136 else 0
                org.logh7.protocol.BootstrapMessages.OfficeCard(kind,outfit.id)
            }
        val national=nationalOffices.cards(stocks,characterId,power).map { (card,seat) ->
            org.logh7.protocol.BootstrapMessages.OfficeCard(card,seat) }
        val logistics=logisticsOffices.cards(stocks,characterId,power).map { (card,seat) ->
            org.logh7.protocol.BootstrapMessages.OfficeCard(card,seat) }
        val staff=stocks.outfits.values.filter {it.kind==OutfitKind.FLEET && it.power==power}.sortedBy {it.id}.flatMap {outfit ->
            FleetOffice.entries.filter {it.power==power && it.offset!=0 && characterId in it.holders(outfit)}
                .map {org.logh7.protocol.BootstrapMessages.OfficeCard(it.card,outfit.id)} }
        return (national + logistics + appointed + commanders + staff).take(14)
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
