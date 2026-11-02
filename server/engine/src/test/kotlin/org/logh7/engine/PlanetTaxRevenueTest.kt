package org.logh7.engine

import java.math.BigInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanetTaxRevenueTest {
    private val base = BaseCondition(1049, 2487, 2, 0, 100_000_000, 60_000_000,
        20, 70, 80, 1, 1, 100_000, 1_000_000, 1_000_000)
    private val stock = PlanetStock(1049, 2, 0, 100_000, 1_000_000, 100_000,
        PlanetEconomy(base.population, base.adultPopulation, 70, 80))
    private val policy = PlanetTaxRevenuePolicy(incomePerAdultPerDay = 1, remittanceRate = 30)

    @Test fun storedTaxOverridesSeedAndRemittanceSplitsCollectedMoneyWithoutMutatingStock() {
        val initial = PlanetTaxRevenue.assess(stock, base, policy)
        assertEquals(PlanetTaxRevenueAssessment(60_000_000, 12_000_000, 3_600_000, 8_400_000), initial)
        val changed = stock.copy(taxRate = 35)
        val result = PlanetTaxRevenue.assess(changed, base, policy)
        assertEquals(PlanetTaxRevenueAssessment(60_000_000, 21_000_000, 6_300_000, 14_700_000), result)
        assertEquals(result, PlanetTaxRevenue.assess(changed, base, policy))
        assertNull(stock.taxRate)
        assertEquals(100_000, stock.supplies)
        assertEquals(1_000_000, stock.food)
        assertEquals(100_000, stock.mineral)
        assertEquals(60_000_000, stock.economy!!.adults)
    }

    @Test fun currentOwnerAndCurrentAdultPopulationDetermineRevenueRatherThanSeedOrWarehouses() {
        val reduced = stock.copy(power = 3, economy = stock.economy!!.copy(population = 90_000_000, adults = 50_000_000))
        assertEquals(10_000_000, PlanetTaxRevenue.assess(reduced, base, policy).collected)
        val warehouseOnly = stock.copy(supplies = 0, food = 0, mineral = 0,
            units = UnitInventory(ships = mapOf(UnitStack(32) to 1)))
        assertEquals(PlanetTaxRevenue.assess(stock, base, policy), PlanetTaxRevenue.assess(warehouseOnly, base, policy))
    }

    @Test fun zeroAndFullTaxAndRemittanceRespectEndpointsAndKeepRoundingRemaindersLocal() {
        val small = stock.copy(taxRate = 33, economy = PlanetEconomy(199, 199, 70, 80))
        val result = PlanetTaxRevenue.assess(small, base, PlanetTaxRevenuePolicy(1, 33))
        assertEquals(PlanetTaxRevenueAssessment(199, 65, 21, 44), result)
        assertEquals(0, PlanetTaxRevenue.assess(small.copy(taxRate = 0), base, policy).collected)
        val full = small.copy(taxRate = 100)
        assertEquals(PlanetTaxRevenueAssessment(199, 199, 0, 199),
            PlanetTaxRevenue.assess(full, base, PlanetTaxRevenuePolicy(1, 0)))
        assertEquals(PlanetTaxRevenueAssessment(199, 199, 199, 0),
            PlanetTaxRevenue.assess(full, base, PlanetTaxRevenuePolicy(1, 100)))
        assertEquals(PlanetTaxRevenueAssessment(0, 0, 0, 0),
            PlanetTaxRevenue.assess(full, base, PlanetTaxRevenuePolicy(0, 100)))
    }

    @Test fun neutralRebelNonhabitableAndNoAdultPopulationHaveNoTaxIncome() {
        val none = PlanetTaxRevenueAssessment(0, 0, 0, 0)
        for (power in listOf(0, 1, 4)) {
            assertEquals(none, PlanetTaxRevenue.assess(stock.copy(power = power), base, policy))
        }
        assertEquals(none, PlanetTaxRevenue.assess(stock.copy(camp = 1), base, policy))
        assertEquals(none, PlanetTaxRevenue.assess(stock, base.copy(habitability = 0), policy))
        assertEquals(none, PlanetTaxRevenue.assess(stock.copy(economy = PlanetEconomy(100, 0, 70, 80)), base, policy))
    }

    @Test fun maximumIncomeAndEveryRateMatchArbitraryPrecisionWithoutLongOverflow() {
        val maximum = stock.copy(economy = PlanetEconomy(Int.MAX_VALUE, Int.MAX_VALUE, 70, 80))
        val income = BigInteger.valueOf(Int.MAX_VALUE.toLong()).pow(2)
        val hundred = BigInteger.valueOf(100)
        for (tax in 0..100) {
            val collected = income * BigInteger.valueOf(tax.toLong()) / hundred
            for (remittance in 0..100) {
                val result = PlanetTaxRevenue.assess(maximum.copy(taxRate = tax), base,
                    PlanetTaxRevenuePolicy(Int.MAX_VALUE, remittance))
                val central = collected * BigInteger.valueOf(remittance.toLong()) / hundred
                assertEquals(income.longValueExact(), result.taxableIncome)
                assertEquals(collected.longValueExact(), result.collected)
                assertEquals(central.longValueExact(), result.central)
                assertEquals(result.collected, result.central + result.local)
            }
        }
    }

    @Test fun uninitializedEconomyWrongBaseAndInvalidPoliciesAreRejectedWithoutFallback() {
        assertFailsWith<IllegalStateException> { PlanetTaxRevenue.assess(stock.copy(economy = null), base, policy) }
        assertFailsWith<IllegalArgumentException> { PlanetTaxRevenue.assess(stock.copy(baseId = 1050), base, policy) }
        assertFailsWith<IllegalArgumentException> { PlanetTaxRevenuePolicy(-1, 0) }
        assertFailsWith<IllegalArgumentException> { PlanetTaxRevenuePolicy(1, -1) }
        assertFailsWith<IllegalArgumentException> { PlanetTaxRevenuePolicy(1, 101) }
        assertFailsWith<IllegalArgumentException> { PlanetTaxRevenueAssessment(10, 11, 0, 11) }
        assertFailsWith<IllegalArgumentException> { PlanetTaxRevenueAssessment(10, 10, 3, 8) }
    }
}
