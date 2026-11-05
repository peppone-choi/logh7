package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class AdminWorldEditingTest {
    private val base=PlanetStock(1001,2,0,100,200,300,governorId=42,treasury=400)
    private val other=base.copy(baseId=1002)
    private val stocks=PlanetStocks(1,0,mapOf(1001 to base,1002 to other))
    private val edit=AdminBaseEdit(1,1001,AdminWorldEditing.version(1,base),101,202,303,404)

    @Test fun `candidate changes only the selected base quantities and preserves offices`() {
        val result=AdminWorldEditing.evaluate(stocks,edit)
        assertEquals(AdminEditOutcome.CHANGED,result.outcome)
        val updated=assertNotNull(result.stocks)
        assertEquals(base.copy(supplies=101,food=202,mineral=303,treasury=404),updated.bases[1001])
        assertEquals(other,updated.bases[1002])
        assertEquals(base,stocks.bases[1001])
        assertEquals(stocks.copy(bases=updated.bases),updated)
    }
    @Test fun `concurrent base changes and session replacement reject stale edits`() {
        val changed=stocks.copy(bases=stocks.bases+(1001 to base.copy(food=201)))
        assertEquals(AdminEditOutcome.CONFLICT,AdminWorldEditing.evaluate(changed,edit).outcome)
        assertEquals(AdminEditOutcome.CONFLICT,AdminWorldEditing.evaluate(stocks.copy(generation=2),edit).outcome)
        val unrelated=stocks.copy(bases=stocks.bases+(1002 to other.copy(food=999)))
        assertEquals(AdminEditOutcome.CHANGED,AdminWorldEditing.evaluate(unrelated,edit).outcome)
    }
    @Test fun `invalid amounts or versions are refused and a no-op retains the original state`() {
        for(invalid in listOf(edit.copy(supplies=-1),edit.copy(treasury=-1),edit.copy(expected="invalid")))
            assertEquals(AdminEditOutcome.INVALID,AdminWorldEditing.evaluate(stocks,invalid).outcome)
        assertEquals(AdminEditOutcome.NOT_FOUND,AdminWorldEditing.evaluate(stocks,edit.copy(baseId=9999)).outcome)
        val unchanged=AdminWorldEditing.evaluate(stocks,edit.copy(supplies=100,food=200,mineral=300,treasury=400))
        assertEquals(AdminEditOutcome.UNCHANGED,unchanged.outcome)
        assertSame(stocks,unchanged.stocks)
    }
}
