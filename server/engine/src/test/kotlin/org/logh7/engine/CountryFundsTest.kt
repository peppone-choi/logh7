package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryFundsTest {
    private val country=CountryId(42)
    private val neighbor=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1002).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id")})}
    private val session=SessionSimulation(SessionRules.load()).also {it.join("a",country,false,"c")}.exportState()
    private val player=Navigator(1,10000,country,2487,1000,1000,1000,baseId=1000,mode=4,shipStyle=ShipStyleId("empire"))
    private val stock=PlanetStocks(1,0,(1000..1002).associate {id->id to PlanetStock(id,
        Allegiance.Country(if(id==1002)neighbor else country),0,0,0,0,treasury=100)},
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(money=5000)),
            offices=listOf(OfficeHolding(54,1001,10000,country)),fiefs=mapOf(1000 to 10000,1002 to 10000)))
    private fun act(value:Int,target:Int,s:PlanetStocks=stock,targetCountry:CountryId?=country)=FundActions.evaluate(galaxy,
        SocialRequest(StrategyActionRequest("a","c",10000,country,1,95,0,1,target=target,value=value,amount=1000,
            identities=listOf(StrategicIdentity(10001,targetCountry,5,0,List(8){50})))),session,player,s,catalog)

    @Test fun governedAndFiefMoneyCanOnlyReachTheActualCountrysBases() {
        assertEquals(listOf(1000,1001),FundActions.governed(galaxy,stock,10000,country,catalog))
        for(base in 1000..1001) {
            val result=act(FundActions.LOCAL,base)
            assertNull(result.rejection);assertEquals(920,result.player?.pcp)
            assertEquals(4000L,result.stocks?.strategic?.people?.get(10000)?.money)
            assertEquals(1100L,result.stocks?.bases?.get(base)?.treasury)
        }
        val denied=act(FundActions.LOCAL,1002)
        assertEquals(SocialRejection.TARGET,denied.rejection);assertEquals(stock,denied.stocks)
        val borrowed=stock.copy(strategic=stock.strategic.copy(offices=listOf(OfficeHolding(54,1001,10000,neighbor))))
        assertEquals(SocialRejection.TARGET,act(FundActions.LOCAL,1001,borrowed).rejection)
    }

    @Test fun confidenceAndSupportKeepCountryAndCandidateMembershipSeparate() {
        val policy=OrganizationalOfficePolicy(galaxy,catalog)
        val capitals=stock.copy(strategic=stock.strategic.copy(capitals=mapOf(country to 1000,neighbor to 1002)))
        val withEmperors=policy.set(policy.set(capitals,OfficeHolding(1,1000,10002,country)),
            OfficeHolding(1,1002,10003,neighbor))
        val boxes=withEmperors.copy(social=withEmperors.social.copy(funds=FundState(confidence=mapOf(43 to (70L to 80L)))))
        val result=act(FundActions.CONFIDENCE,2,boxes)
        assertNull(result.rejection)
        assertEquals(mapOf(42 to (0L to 1000L),43 to (70L to 80L)),result.stocks?.social?.funds?.confidence)
        assertNull(act(FundActions.SUPPORT,10001).rejection)
        for(other in listOf(neighbor,null)) {
            val denied=act(FundActions.SUPPORT,10001,targetCountry=other)
            assertEquals(SocialRejection.TARGET,denied.rejection);assertEquals(stock,denied.stocks)
        }
    }
}
