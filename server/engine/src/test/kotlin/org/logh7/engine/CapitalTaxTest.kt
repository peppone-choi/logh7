package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CapitalTaxTest {
    private val bases=listOf(GalaxyBase(1177,2015,61,0,"ハイネセン",0,28f,259200,0,0f,2f),
        GalaxyBase(1178,2015,61,0,"Other",0,28f,259200,0,0f,2f))
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2015,4,"Home",3)),bases,
        mapOf(Power.EMPIRE to 2015,Power.ALLIANCE to 2015))
    private val session=SessionSimulation(SessionRules.load()).also { it.join("pilot",Power.ALLIANCE,false,"c") }.exportState()
    private val player=Navigator(1,10000,Power.ALLIANCE,2015,1000,1000,1000,baseId=1177,mode=4)
    private val capital=PlanetStock(1177,3,0,100,200,300,capitalAdministratorId=10000,capitalDefenseCommanderId=10001)
    private val stocks=PlanetStocks(1,0,mapOf(1177 to capital,1178 to capital.copy(baseId=1178,
        capitalAdministratorId=0,capitalDefenseCommanderId=0,governorId=10000)))
    private val query=TaxRequest("pilot","c",10000,Power.ALLIANCE,1,1177,35,192)
    private fun act(q:TaxRequest=query,p:Navigator=player,s:PlanetStocks=stocks)=PlanetTax.evaluate(galaxy,q,session,p,s)
    @Test fun storedCapitalAdministratorPaysOnceAndKeepsOtherOfficesAndPlanetUnchanged() {
        val result=act()
        assertNull(result.rejection);assertTrue(result.changed)
        assertEquals(840,result.player!!.mcp);assertEquals(1000,result.player.pcp)
        assertEquals(capital.copy(taxRate=35),result.stocks!!.bases.getValue(1177))
        assertEquals(stocks.bases.getValue(1178),result.stocks.bases.getValue(1178))
        val retry=act(p=result.player,s=result.stocks)
        assertFalse(retry.changed);assertEquals(result.player,retry.player);assertEquals(result.stocks,retry.stocks)
        val alternate=act(p=player.copy(mcp=0,pcp=320))
        assertNull(alternate.rejection);assertEquals(0,alternate.player!!.pcp)
    }
    @Test fun forgedCapitalCardLegacyGovernorMilitaryHolderAndRemoteLocationCannotChangeTax() {
        val cases=listOf(
            act(s=stocks.copy(bases=stocks.bases+(1177 to capital.copy(capitalAdministratorId=10002)))) to TaxRejection.ACCESS,
            act(s=stocks.copy(bases=stocks.bases+(1177 to capital.copy(capitalAdministratorId=0,governorId=10000,capitalDefenseCommanderId=10000)))) to TaxRejection.ACCESS,
            act(query.copy(card=190)) to TaxRejection.ACCESS,
            act(query.copy(card=193)) to TaxRejection.CARD,
            act(query.copy(base=1178)) to TaxRejection.ACCESS,
            act(p=player.copy(baseId=1178)) to TaxRejection.LOCATION,
            act(p=player.copy(mcp=159,pcp=319)) to TaxRejection.CP)
        for((result,rejection) in cases) {
            assertEquals(rejection,result.rejection);assertFalse(result.changed)
            assertNull(result.stocks!!.bases.getValue(1177).taxRate)
            assertEquals(if(rejection==TaxRejection.CP)159 else 1000,result.player!!.mcp)
            assertEquals(if(rejection==TaxRejection.CP)319 else 1000,result.player.pcp)
        }
    }
    @Test fun ordinaryGovernorCanUseTheSecondOwnedPlanetButCannotUseCapitalCardThere() {
        val q=query.copy(base=1178,card=190)
        val result=act(q,player.copy(baseId=1178))
        assertNull(result.rejection);assertEquals(35,result.stocks!!.bases.getValue(1178).taxRate)
        assertEquals(capital,result.stocks.bases.getValue(1177))
    }
}
