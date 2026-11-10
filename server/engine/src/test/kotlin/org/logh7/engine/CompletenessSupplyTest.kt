package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*
import org.logh7.engine.strategy.CpPool

class CompletenessSupplyTest {
    private val request = CompletenessSupplyRequest("pilot", "c", 10000, Power.EMPIRE, 1, 1, 2)
    private val player = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000, baseId = 1049, mode = 4)
    private val session = SessionSimulation(SessionRules.load()).also { it.join("pilot", Power.EMPIRE, false, "c") }.exportState()
    private val capacity = OutfitSupplies.unitCapacity(32)
    private fun stocks(source: Int = capacity, destination: Int = 0): PlanetStocks = ActiveShips.initialize(PlanetStocks(1, 0,
        mapOf(1049 to PlanetStock(1049, 2, 0, 0, 0, 0), 1050 to PlanetStock(1050, 2, 0, 0, 0, 0)),
        mapOf(1 to OutfitStock(1, 2, OutfitKind.TRANSPORT, 1049, 101, 10000,
            UnitInventory(mapOf(UnitStack(32) to 1)), supplies = source),
            2 to OutfitStock(2, 2, OutfitKind.FLEET, 1049, 102, 10001,
                UnitInventory(mapOf(UnitStack(32) to 2)), supplies = destination)),
        mapOf(101 to OutfitWarehouse(101, 2, OutfitKind.TRANSPORT, 1049, 1),
            102 to OutfitWarehouse(102, 2, OutfitKind.FLEET, 1049, 2)), deploymentInitialized = true))

    @Test fun defeatAfterQuotePreventsCompletenessSupplyWithoutChargingOrMovingCargo() {
        val before=stocks()
        val cost=assertNotNull(CompletenessSupply.quote(player,before.outfits.getValue(1),before.outfits.getValue(2),before).cost)
        val confirm=request.copy(expected=cost)
        assertTrue(CompletenessSupply.evaluate(confirm,session,player,before).changed)
        val defeated=session.copy(eliminated=mapOf(Power.EMPIRE.country to
            CountryDefeat(EndReason.CAPITAL_CAPTURED,session.gameMillis/1000,Power.ALLIANCE.country)))
        val result=CompletenessSupply.evaluate(confirm,defeated,player,before)
        assertEquals(LogisticsRejection.SESSION,result.rejection)
        assertFalse(result.changed)
        assertSame(player,result.player)
        assertSame(before,result.stocks)
    }

    @Test fun detentionBlocksTheQuoteAndAnEarlierConfirmationUntilRelease() {
        val original = stocks()
        val source = original.outfits.getValue(request.from)
        val target = original.outfits.getValue(request.to)
        val previousCost = assertNotNull(CompletenessSupply.quote(player, source, target, original).cost)
        val confirmation = request.copy(expected = previousCost)
        val holdingRoom = player.copy(mode = 1, spotId = player.baseId * 10_000 + 19)
        val detention = Detention(player.characterId, Power.EMPIRE.country, 0, Justice.DAY, base = player.baseId)
        val held = original.copy(social = original.social.copy(justice = original.social.justice.copy(
            detentions = mapOf(player.characterId to detention))))

        val quote = CompletenessSupply.quote(holdingRoom, source, target, held)
        assertEquals(LogisticsRejection.BUSY, quote.rejection)
        assertNull(quote.cost)
        val denied = CompletenessSupply.evaluate(confirmation, session, holdingRoom, held)
        assertEquals(LogisticsRejection.BUSY, denied.rejection)
        assertFalse(denied.changed)
        assertSame(holdingRoom, denied.player)
        assertSame(held, denied.stocks)

        val supplied = CompletenessSupply.evaluate(confirmation, session, player, original)
        assertNull(supplied.rejection)
        assertEquals(capacity, supplied.transferred)
        assertEquals(840, supplied.player?.mcp)
        val after = assertNotNull(supplied.stocks)
        assertEquals(0, after.outfits.getValue(source.id).supplies)
        assertEquals(capacity, after.outfits.getValue(target.id).supplies)
    }

    @Test fun rebelSupplyCannotCrossCampOrContinueAfterThePortIsRetaken() {
        val initial=stocks()
        val country=Power.EMPIRE.country
        val plot=Plot(10000,country,0,members=listOf(10001),revolt=5,bases=setOf(1049))
        val coup=CoupState(mapOf(10000 to plot),loyalty=mapOf(1 to 100,2 to 100))
        val rebel=initial.copy(bases=initial.bases+(1049 to initial.bases.getValue(1049).copy(camp=1)),
            social=initial.social.copy(coup=coup))
        val result=CompletenessSupply.evaluate(request,session,player,rebel)
        assertEquals(capacity,result.transferred)
        assertEquals(840,result.player?.mcp)
        val opposing=rebel.copy(social=rebel.social.copy(coup=coup.copy(loyalty=mapOf(1 to 100))))
        val retaken=rebel.copy(bases=initial.bases)
        for((state,reason) in listOf(opposing to LogisticsRejection.ACCESS,retaken to LogisticsRejection.LOCATION)) {
            val denied=CompletenessSupply.evaluate(request,session,player,state)
            assertEquals(reason,denied.rejection)
            assertSame(player,denied.player)
            assertSame(state,denied.stocks)
        }
    }

    @Test fun partialDeliveryConservesCargoChargesOnceAndLeavesWarehousesAndShipsIntact() {
        val before = stocks()
        val result = CompletenessSupply.evaluate(request, session, player, before)
        assertNull(result.rejection); assertEquals(capacity, result.transferred)
        assertEquals(840, result.player!!.mcp); assertEquals(1000, result.player.pcp)
        val after = result.stocks!!
        assertEquals(0, after.outfits.getValue(1).supplies)
        assertEquals(capacity, after.outfits.getValue(2).supplies)
        assertEquals(before.warehouses, after.warehouses)
        assertEquals(before.outfits.mapValues { it.value.units }, after.outfits.mapValues { it.value.units })
        assertEquals(before.outfits.mapValues { it.value.activeShips }, after.outfits.mapValues { it.value.activeShips })
        val replay = CompletenessSupply.evaluate(request, session, result.player, after)
        assertNull(replay.rejection); assertFalse(replay.changed)
        assertEquals(result.player, replay.player); assertEquals(after, replay.stocks)
    }

    @Test fun fullTargetAndEmptyCargoDoNotChargeAndLastGapDoesNotOverflow() {
        for (stock in listOf(stocks(0), stocks(capacity, capacity * 2))) {
            val result = CompletenessSupply.evaluate(request, session, player, stock)
            assertNull(result.rejection); assertFalse(result.changed)
            assertEquals(player, result.player); assertEquals(stock, result.stocks)
        }
        val result = CompletenessSupply.evaluate(request, session, player, stocks(capacity, capacity * 2 - 7))
        assertEquals(7, result.transferred)
        assertEquals(capacity - 7, result.stocks!!.outfits.getValue(1).supplies)
        assertEquals(capacity * 2, result.stocks!!.outfits.getValue(2).supplies)
    }

    @Test fun transportQuoteAndExecutionShareCapacityWithSurvivingPackages() {
        val before = stocks(destination = capacity)
        val source = before.outfits.getValue(1)
        val target = before.outfits.getValue(2).copy(kind = OutfitKind.TRANSPORT)
        val cargo = TransportPackage(1, Power.EMPIRE.country, 1049, 1050, (capacity - 7) * 2,
            carrier = target.id, carrierBoats = 600, survivingBoats = 300)
        val stock = before.copy(outfits = before.outfits + (target.id to target),
            warehouses = before.warehouses + (102 to before.warehouses.getValue(102).copy(kind = OutfitKind.TRANSPORT)),
            strategic = before.strategic.copy(transports = listOf(cargo, cargo.copy(id = 2, carrier = 0, carrierBoats = 0, survivingBoats = 0))))

        val quote = CompletenessSupply.quote(player, source, target, stock)
        assertEquals(SupplyCost(7, CpPool.MCP), quote.cost)
        val confirm = request.copy(expected = quote.cost)
        val stale = CompletenessSupply.evaluate(confirm, session, player, stock.copy(strategic = before.strategic))
        assertEquals(LogisticsRejection.QUOTE_CHANGED, stale.rejection)
        assertEquals(player, stale.player)

        val supplied = CompletenessSupply.evaluate(confirm, session, player, stock)
        assertEquals(7, supplied.transferred)
        assertEquals(840, supplied.player!!.mcp)
        val after = supplied.stocks!!
        assertEquals(capacity - 7, after.outfits.getValue(1).supplies)
        assertEquals(capacity + 7, after.outfits.getValue(2).supplies)
        assertEquals(stock.strategic.transports, after.strategic.transports)

        val full = CompletenessSupply.evaluate(request, session, supplied.player, after)
        assertNull(full.rejection)
        assertFalse(full.changed)
        assertSame(supplied.player, full.player)
        assertSame(after, full.stocks)
    }

    @Test fun forgedSessionIdentityAuthorityAndLocationCannotMoveOrCharge() {
        val stock = stocks()
        for ((q, reason) in listOf(request.copy(connection = "other") to LogisticsRejection.SESSION,
            request.copy(generation = 2) to LogisticsRejection.SESSION,
            request.copy(characterId = 10001) to LogisticsRejection.IDENTITY,
            request.copy(country = Power.ALLIANCE.country) to LogisticsRejection.IDENTITY,
            request.copy(from = 2, to = 1) to LogisticsRejection.ACCESS,
            request.copy(to = 1) to LogisticsRejection.OUTFIT,
            request.copy(to = 999) to LogisticsRejection.OUTFIT)) {
            val result = CompletenessSupply.evaluate(q, session, player, stock)
            assertEquals(reason, result.rejection); assertEquals(player, result.player); assertEquals(stock, result.stocks)
        }
        val away = player.copy(baseId = 1050)
        assertEquals(LogisticsRejection.LOCATION, CompletenessSupply.evaluate(request, session, away, stock).rejection)
        val captured = stock.copy(bases = stock.bases + (1049 to stock.bases.getValue(1049).copy(owner=Allegiance.fromLegacy(3))))
        assertEquals(LogisticsRejection.LOCATION, CompletenessSupply.evaluate(request, session, player, captured).rejection)
        val wrongRole = stock.copy(outfits=stock.outfits+(1 to stock.outfits.getValue(1).copy(kind=OutfitKind.FLEET)),
            warehouses=stock.warehouses+(101 to stock.warehouses.getValue(101).copy(kind=OutfitKind.FLEET)))
        assertEquals(LogisticsRejection.ACCESS,CompletenessSupply.evaluate(request,session,player,wrongRole).rejection)
        val enemy = stock.copy(outfits=stock.outfits+(2 to stock.outfits.getValue(2).copy(country=CountryId(3),shipStyle=ShipStyleId.fromLegacy(3))),
            warehouses=stock.warehouses+(102 to stock.warehouses.getValue(102).copy(country=CountryId(3),shipStyle=ShipStyleId.fromLegacy(3))))
        assertEquals(LogisticsRejection.ACCESS,CompletenessSupply.evaluate(request,session,player,enemy).rejection)
        assertEquals(LogisticsRejection.BUSY, CompletenessSupply.evaluate(request, session, player.copy(cpPaused = true), stock).rejection)
    }

    @Test fun insufficientCpIsAtomicAndPcpSubstitutionUsesTheSharedRate() {
        val stock = stocks()
        val poor = player.copy(pcp = 319, mcp = 0)
        val rejected = CompletenessSupply.evaluate(request, session, poor, stock)
        assertEquals(LogisticsRejection.CP, rejected.rejection)
        assertEquals(poor, rejected.player); assertEquals(stock, rejected.stocks)
        val result = CompletenessSupply.evaluate(request, session, player.copy(pcp = 320, mcp = 0), stock)
        assertNull(result.rejection); assertEquals(0, result.player!!.pcp); assertEquals(0, result.player.mcp)
    }

    @Test fun aFriendlyPortCanSupplyVisitingOutfitsButNotAnOutfitThatDeparted() {
        val atPort = OutfitPosition(2488, 1050)
        val stock = stocks().let { it.copy(outfits = it.outfits.mapValues { (_, outfit) -> outfit.copy(position = atPort) }) }
        val visitor = player.copy(grid = 2488, baseId = 1050)
        val supplied = CompletenessSupply.evaluate(request, session, visitor, stock)
        assertNull(supplied.rejection)
        assertEquals(capacity, supplied.transferred)
        assertEquals(atPort, supplied.stocks!!.outfits.getValue(2).position)
        val departed = stock.copy(outfits = stock.outfits + (2 to stock.outfits.getValue(2).copy(position = OutfitPosition(2488))))
        assertEquals(LogisticsRejection.LOCATION, CompletenessSupply.evaluate(request, session, visitor, departed).rejection)
    }

    @Test fun omittedSourceRequiresExactlyOneCommandedTransportAtThePort() {
        val request = request.copy(from = 0)
        val stock = stocks()
        val second = OutfitStock(3, 2, OutfitKind.TRANSPORT, 1049, 103, player.characterId)
        val ambiguous = stock.copy(outfits = stock.outfits + (3 to second),
            warehouses = stock.warehouses + (103 to OutfitWarehouse(103, 2, OutfitKind.TRANSPORT, 1049, 3)))
        val refused = CompletenessSupply.evaluate(request, session, player, ambiguous)
        assertEquals(LogisticsRejection.OUTFIT, refused.rejection)
        assertEquals(player, refused.player)
        assertEquals(ambiguous, refused.stocks)
        assertEquals(1, CompletenessSupply.evaluate(request, session, player, stock).source)
    }

    @Test fun boundTransferRejectsChangedCargoOrPaymentWithoutMutating() {
        val stock = stocks(source = 1000)
        val alternate = player.copy(mcp = 0)
        val quote = CompletenessSupply.quote(alternate, stock.outfits.getValue(1), stock.outfits.getValue(2), stock)
        assertEquals(SupplyCost(1000, CpPool.PCP), quote.cost)
        val confirm = request.copy(expected = quote.cost)
        val consumed = stocks(source = 700)
        for ((inventory, pilot) in listOf(consumed to alternate, stock to player)) {
            val refused = CompletenessSupply.evaluate(confirm, session, pilot, inventory)
            assertEquals(LogisticsRejection.QUOTE_CHANGED, refused.rejection)
            assertEquals(inventory, refused.stocks)
            assertEquals(pilot, refused.player)
        }
        val accepted = CompletenessSupply.evaluate(confirm, session, alternate, stock)
        assertEquals(1000, accepted.transferred)
        assertEquals(680, accepted.player?.pcp)
    }

    @Test fun perUnitProjectionIsStableConservesBulkCargoAndNeverExceedsCapacity() {
        val outfit = stocks(destination = capacity + 7).outfits.getValue(2)
        val result = OutfitSupplies.distribution(outfit)
        assertEquals(listOf(capacity, 7), result.values.toList())
        assertEquals(outfit.supplies, result.values.sum())
        assertEquals(result, OutfitSupplies.distribution(outfit.copy(activeShips = ActiveShipUnits(outfit.activeShips!!.values.reversed()))))
        assertFails { OutfitSupplies.distribution(outfit.copy(supplies = capacity * 2 + 1)) }
    }
}
