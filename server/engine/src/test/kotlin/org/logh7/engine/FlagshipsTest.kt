package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class FlagshipsTest {
    @Test fun factionKindsUseDistinctModelsAndReviewedManualStatistics() {
        assertEquals(listOf(0, 2, 3), Flagships.forPower(2).map { it.kind })
        assertEquals(listOf(89, 90, 93), Flagships.forPower(3).map { it.kind })
        val empire = Flagships.forType(2, 3)
        val alliance = Flagships.forType(3, 3)
        assertEquals(18, empire.model); assertEquals(1014, alliance.model)
        assertEquals(12, Flagships.forType(2, 0).model)
        assertEquals(1012, Flagships.forType(3, 0).model)
        assertEquals(200, empire.number("出力:出力")); assertEquals(220, alliance.number("出力:出力"))
        assertEquals(30_000, empire.number("最高速度（km/1G秒）:最高速度（km/1G秒）"))
        assertEquals(8, empire.number("装甲:前")); assertEquals(10, alliance.number("装甲:前"))
        assertTrue(Flagships.definitions.all { it.manual.verificationStatus == "reviewed" })
        assertNull(Flagships.find(65535))
        assertFailsWith<IllegalArgumentException> { Flagships.forPower(4) }
    }
}
