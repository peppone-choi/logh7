package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

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

    @Test fun forgedSessionIdentityAuthorityAndLocationCannotMoveOrCharge() {
        val stock = stocks()
        for ((q, reason) in listOf(request.copy(connection = "other") to LogisticsRejection.SESSION,
            request.copy(generation = 2) to LogisticsRejection.SESSION,
            request.copy(characterId = 10001) to LogisticsRejection.IDENTITY,
            request.copy(power = Power.ALLIANCE) to LogisticsRejection.IDENTITY,
            request.copy(from = 2, to = 1) to LogisticsRejection.ACCESS,
            request.copy(to = 1) to LogisticsRejection.OUTFIT,
            request.copy(to = 999) to LogisticsRejection.OUTFIT)) {
            val result = CompletenessSupply.evaluate(q, session, player, stock)
            assertEquals(reason, result.rejection); assertEquals(player, result.player); assertEquals(stock, result.stocks)
        }
        val away = player.copy(baseId = 1050)
        assertEquals(LogisticsRejection.LOCATION, CompletenessSupply.evaluate(request, session, away, stock).rejection)
        val captured = stock.copy(bases = stock.bases + (1049 to stock.bases.getValue(1049).copy(power = 3)))
        assertEquals(LogisticsRejection.LOCATION, CompletenessSupply.evaluate(request, session, player, captured).rejection)
        val wrongRole = stock.copy(outfits=stock.outfits+(1 to stock.outfits.getValue(1).copy(kind=OutfitKind.FLEET)),
            warehouses=stock.warehouses+(101 to stock.warehouses.getValue(101).copy(kind=OutfitKind.FLEET)))
        assertEquals(LogisticsRejection.ACCESS,CompletenessSupply.evaluate(request,session,player,wrongRole).rejection)
        val enemy = stock.copy(outfits=stock.outfits+(2 to stock.outfits.getValue(2).copy(power=3)),
            warehouses=stock.warehouses+(102 to stock.warehouses.getValue(102).copy(power=3)))
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

    @Test fun perUnitProjectionIsStableConservesBulkCargoAndNeverExceedsCapacity() {
        val outfit = stocks(destination = capacity + 7).outfits.getValue(2)
        val result = OutfitSupplies.distribution(outfit)
        assertEquals(listOf(capacity, 7), result.values.toList())
        assertEquals(outfit.supplies, result.values.sum())
        assertEquals(result, OutfitSupplies.distribution(outfit.copy(activeShips = ActiveShipUnits(outfit.activeShips!!.values.reversed()))))
        assertFails { OutfitSupplies.distribution(outfit.copy(supplies = capacity * 2 + 1)) }
    }
}
