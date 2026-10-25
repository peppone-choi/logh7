package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanetTaxTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val rules = SessionRules.load()
    private val session = SessionSimulation(rules).also { it.join("pilot",Power.EMPIRE,false,"c") }.exportState()
    private val player = Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4)
    private val stocks = PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,governorId=10000)))
    private val query = TaxRequest("pilot","c",10000,Power.EMPIRE,1,1049,35,54)
    private fun act(q: TaxRequest = query, p: Navigator = player, s: PlanetStocks = stocks) = PlanetTax.evaluate(galaxy,q,session,p,s)
    @Test fun ownerPaysServerCostAndAnUnchangedRetryDoesNotPayTwice() {
        val accepted = act()
        assertNull(accepted.rejection); assertTrue(accepted.changed)
        assertEquals(680,accepted.player!!.pcp); assertEquals(1000,accepted.player.mcp)
        assertEquals(35,accepted.stocks!!.bases.getValue(1049).taxRate)
        assertEquals(stocks.bases.getValue(1049).units,accepted.stocks.bases.getValue(1049).units)
        val again = act(p=accepted.player,s=accepted.stocks)
        assertFalse(again.changed); assertEquals(accepted.player,again.player); assertEquals(accepted.stocks,again.stocks)
        val alternative = act(p=player.copy(pcp=0,mcp=640))
        assertNull(alternative.rejection); assertEquals(0,alternative.player!!.pcp); assertEquals(0,alternative.player.mcp)
        assertEquals(TaxRejection.CP,act(p=player.copy(pcp=319,mcp=639)).rejection)
    }
    @Test fun clientCardCannotGrantOwnershipAndLocationFactionAndSessionAreChecked() {
        assertEquals(TaxRejection.ACCESS,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(governorId=0)))).rejection)
        assertEquals(TaxRejection.ACCESS,act(s=stocks.copy(bases=mapOf(1049 to stocks.bases.getValue(1049).copy(power=3)))).rejection)
        assertEquals(TaxRejection.CARD,act(query.copy(card=59)).rejection)
        assertEquals(TaxRejection.LOCATION,act(p=player.copy(baseId=0,mode=5)).rejection)
        assertEquals(TaxRejection.MODE,act(query.copy(mode=1)).rejection)
        assertEquals(TaxRejection.SESSION,act(query.copy(connection="old")).rejection)
        assertEquals(TaxRejection.IDENTITY,act(query.copy(characterId=10001)).rejection)
        assertEquals(TaxRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        assertEquals(1000,player.pcp); assertNull(stocks.bases.getValue(1049).taxRate)
    }
}
