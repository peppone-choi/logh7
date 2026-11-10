package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 資金投入: 통치 행성 금고, 신임·지지 박스 집계, 사적 자금 부족 거부. 작성: Peppone Choi. */
class FundActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1049..1050).map {
        GalaxyBase(it,2487,80,0,if(it==1049)Capital.EMPIRE.baseName else "Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,treasury=10),1050 to PlanetStock(1050,2,0,1,1,1)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=1,money=7000)),fiefs=mapOf(1049 to 10000)))
    private fun act(value:Int,target:Int,amount:Long,s:PlanetStocks=stocks)=FundActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(
        "a","a",10000,Power.EMPIRE,1,FundActions.FUNDS,0,1,target=target,value=value,amount=amount,
        identities=listOf(StrategicIdentity(10001,2,5,0,listOf(50,50,50,50,50,50,50,50))))),session,a,s)

    private fun withEmperor(s:PlanetStocks)=OrganizationalOfficePolicy(galaxy).set(s,OfficeHolding(1,1049,10001))

    @Test fun confidenceRequiresALivingIncumbentInTheVotersCountryBeforeCharging() {
        val incumbent=withEmperor(stocks)
        val dead=incumbent.copy(strategic=incumbent.strategic.copy(people=incumbent.strategic.people+
            (10001 to StrategicPerson(deceasedAt=1))))
        val captured=incumbent.copy(bases=incumbent.bases+(1049 to incumbent.bases.getValue(1049)
            .copy(owner=Allegiance.Country(CountryId(3)))))
        for(current in listOf(stocks,dead,captured)) {
            assertFalse(FundActions.hasConfidenceIncumbent(galaxy,current,CountryId(2)))
            val rejected=act(FundActions.CONFIDENCE,2,1000,current)
            assertEquals(SocialRejection.TARGET,rejected.rejection)
            assertEquals(a,rejected.player)
            assertEquals(current,rejected.stocks)
        }
        assertTrue(FundActions.hasConfidenceIncumbent(galaxy,incumbent,CountryId(2)))
        assertFalse(FundActions.hasConfidenceIncumbent(galaxy,incumbent,CountryId(3)))
        assertFalse(FundActions.hasConfidenceIncumbent(galaxy,incumbent,null))
    }

    @Test fun confidenceUsesTheCountrysOfficeSystemEvenWhenTheDisplayMatchesTheEmpire() {
        val defaults=CountryCatalogLoader.load()
        val country=CountryId(42)
        val definition=defaults.countries.getValue(3).copy(id=42,key="confidence_fixture",name="Confidence Fixture",nativePower=2)
        val catalog=CountryCatalog(defaults.calendars.values.toList(),defaults.families.values.toList(),
            defaults.countries.values.toList()+definition)
        val outpost=stocks.bases.getValue(1050).copy(owner=Allegiance.Country(country))
        val world=stocks.copy(bases=stocks.bases+(1050 to outpost),strategic=stocks.strategic.copy(
            capitals=mapOf(country to 1050),offices=listOf(OfficeHolding(132,1050,10001,country))))

        assertTrue(FundActions.hasConfidenceIncumbent(galaxy,world,country,catalog))
        assertFalse(FundActions.hasConfidenceIncumbent(galaxy,world,CountryId(2),catalog))
    }

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

    @Test fun supportRejectsDeadCandidatesAndRechecksCampBeforeCharging() {
        val dead = stocks.copy(strategic = stocks.strategic.copy(
            people = stocks.strategic.people + (10001 to StrategicPerson(deceasedAt = 1))))
        val plot = Plot(10001, Power.EMPIRE.country, since = 1, revolt = 2)
        val revolt = stocks.copy(social = stocks.social.copy(coup = CoupState(plots = mapOf(plot.leader to plot))))
        for (current in listOf(dead, revolt)) {
            val result = act(FundActions.SUPPORT, 10001, 1000, current)
            assertEquals(SocialRejection.TARGET, result.rejection)
            assertEquals(a, result.player)
            assertEquals(current, result.stocks)
        }
        val sameCamp = revolt.copy(social = revolt.social.copy(coup =
            CoupState(plots = mapOf(plot.leader to plot.copy(members = listOf(a.characterId))))))
        assertNull(act(FundActions.SUPPORT, 10001, 1000, sameCamp).rejection)
    }

    @Test fun moneyGoesToAGovernedTreasuryOrTheBoxes() {
        assertEquals(SocialRejection.TARGET,act(0,1050,1000).rejection,"not governed")
        assertEquals(SocialRejection.MAXIMUM,act(0,1049,8000).rejection)
        val local=act(0,1049,5000)
        assertNull(local.rejection);assertEquals(920,local.player?.pcp);assertEquals(2000,local.found)
        assertEquals(5010,assertNotNull(local.stocks).bases.getValue(1049).treasury)
        val vote=act(1,2,1000,withEmperor(stocks))
        assertNull(vote.rejection)
        assertEquals(920,vote.player?.pcp)
        val boxed=assertNotNull(vote.stocks)
        assertEquals(0L to 1000L,boxed.social.funds.confidence.getValue(2))
        assertEquals(3000L,assertNotNull(act(2,10001,3000).stocks).social.funds.support.getValue(10001))
        assertEquals(SocialRejection.TARGET,act(2,10000,1000).rejection,"not oneself")
    }
}
