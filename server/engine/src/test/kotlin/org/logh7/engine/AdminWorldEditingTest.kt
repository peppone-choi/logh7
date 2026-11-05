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
    @Test fun `demographic edits share the base version and preserve ownership offices and unrelated bases`() {
        val original=base.copy(economy=PlanetEconomy(1000,600,80,90))
        val before=stocks.copy(bases=stocks.bases+(1001 to original))
        val people=PlanetEconomy(12345,6789,73,81)
        val request=edit.copy(expected=AdminWorldEditing.version(1,original),economy=people)
        val result=AdminWorldEditing.evaluate(before,request)
        assertEquals(AdminEditOutcome.CHANGED,result.outcome)
        val after=assertNotNull(result.stocks)
        assertEquals(original.copy(supplies=101,food=202,mineral=303,treasury=404,economy=people),after.bases[1001])
        assertEquals(before.copy(bases=after.bases),after)
        assertEquals(other,after.bases[1002]);assertEquals(original,before.bases[1001])
        assertEquals(AdminEditOutcome.CONFLICT,AdminWorldEditing.evaluate(after,request).outcome)
        val concurrent=before.copy(bases=before.bases+(1001 to original.copy(economy=people)))
        assertEquals(AdminEditOutcome.CONFLICT,AdminWorldEditing.evaluate(concurrent,request).outcome)
    }
    @Test fun `legacy resource edits retain demographics and unchanged demographic edits preserve the original state`() {
        val people=PlanetEconomy(1000,600,80,90)
        val original=base.copy(economy=people)
        val before=stocks.copy(bases=stocks.bases+(1001 to original))
        val request=edit.copy(expected=AdminWorldEditing.version(1,original))
        assertEquals(people,AdminWorldEditing.evaluate(before,request).stocks!!.bases.getValue(1001).economy)
        val noop=request.copy(supplies=100,food=200,mineral=300,treasury=400,economy=people)
        val result=AdminWorldEditing.evaluate(before,noop)
        assertEquals(AdminEditOutcome.UNCHANGED,result.outcome);assertSame(before,result.stocks)
    }
}
