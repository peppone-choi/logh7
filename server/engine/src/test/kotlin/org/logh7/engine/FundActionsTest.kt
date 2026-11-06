package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 資金投入: 통치 행성 금고, 신임·지지 박스 집계, 사적 자금 부족 거부. 작성: Peppone Choi. */
class FundActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1049..1050).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,treasury=10),1050 to PlanetStock(1050,2,0,1,1,1)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,money=7000)),fiefs=mapOf(1049 to 10000)))
    private fun act(value:Int,target:Int,amount:Long,s:PlanetStocks=stocks)=FundActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(
        "a","a",10000,Power.EMPIRE,1,FundActions.FUNDS,0,1,target=target,value=value,amount=amount,
        identities=listOf(StrategicIdentity(10001,2,5,0,listOf(50,50,50,50,50,50,50,50))))),session,a,s)

    @Test fun supportMoneyCannotBeTransferredToForeignOrUnattachedCharacters() {
        for(country in listOf(CountryId(1),CountryId(4),CountryId(42),null)) {
            val request=StrategyActionRequest("a","a",10000,Power.EMPIRE,1,FundActions.FUNDS,0,1,
                target=10001,value=FundActions.SUPPORT,amount=1000,
                identities=listOf(StrategicIdentity(10001,country,5,0,List(8){50})))
            val result=FundActions.evaluate(galaxy,SocialRequest(request),session,a,stocks)
            assertEquals(SocialRejection.TARGET,result.rejection)
            assertEquals(a,result.player);assertEquals(stocks,result.stocks)
        }
    }

    @Test fun moneyGoesToAGovernedTreasuryOrTheBoxes() {
        assertEquals(SocialRejection.TARGET,act(0,1050,1000).rejection,"not governed")
        assertEquals(SocialRejection.MAXIMUM,act(0,1049,8000).rejection)
        val local=act(0,1049,5000)
        assertNull(local.rejection);assertEquals(920,local.player?.pcp);assertEquals(2000,local.found)
        assertEquals(5010,assertNotNull(local.stocks).bases.getValue(1049).treasury)
        val boxed=assertNotNull(act(1,2,1000).stocks)
        assertEquals(0L to 1000L,boxed.social.funds.confidence.getValue(2))
        assertEquals(3000L,assertNotNull(act(2,10001,3000).stocks).social.funds.support.getValue(10001))
        assertEquals(SocialRejection.TARGET,act(2,10000,1000).rejection,"not oneself")
    }
}
