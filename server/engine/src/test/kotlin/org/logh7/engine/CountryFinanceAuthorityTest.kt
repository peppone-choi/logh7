package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryFinanceAuthorityTest {
    private val country=CountryId(42)
    private val neighbor=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),(1000..1002).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)
    },Power.entries.associateWith {2487})
    private fun catalog(family:String="empire")=CountryCatalogLoader.load().let {old->CountryCatalog(
        old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id",family=family)})}
    private fun session(id:CountryId?=country)=SessionSimulation(SessionRules.load()).also {it.join("actor",id,false,"c")}.exportState()
    private fun player(base:Int=1000,id:CountryId?=country)=Navigator(1,10000,id,2487,1000,1000,1000,
        baseId=base,mode=4,shipStyle=ShipStyleId("empire"))
    private fun stocks(finance:Int=8,tax:Int=54)=PlanetStocks(1,0,(1000..1002).associate {id->id to PlanetStock(id,
        Allegiance.Country(if(id==1002)neighbor else country),0,2000,200,300,treasury=200)},
        nationalTreasuries=mapOf(country to 5000L,neighbor to 120000L),
        strategic=StrategicState(capitals=mapOf(country to 1000,neighbor to 1002),
            offices=listOf(OfficeHolding(finance,1000,10000,country),OfficeHolding(tax,1001,10000,country)),
            people=mapOf(10000 to StrategicPerson(rank=1))))
    private fun budget(card:Int=8,id:CountryId=country,phase:Int=1,quote:Int=0)=BudgetRequest(
        StrategyActionRequest("actor","c",10000,id,1,18,card,1),phase,1001,25,quote)

    @Test fun taxUsesActualMembershipAndOfficeRatherThanSharedAppearance() {
        val p=player(1001);val s=stocks();val r=TaxRequest("actor","c",10000,country,1,1001,35,54)
        val accepted=PlanetTax.evaluate(galaxy,r,session(),p,s,catalog())
        assertNull(accepted.rejection);assertEquals(840,accepted.player?.mcp)
        assertEquals(35,accepted.stocks?.bases?.get(1001)?.taxRate)
        val again=PlanetTax.evaluate(galaxy,r,session(),accepted.player,assertNotNull(accepted.stocks),catalog())
        assertFalse(again.changed);assertEquals(accepted.player,again.player)
        assertEquals(TaxRejection.IDENTITY,PlanetTax.evaluate(galaxy,r.copy(country=neighbor),session(),p,s,catalog()).rejection)
        assertEquals(TaxRejection.ACCESS,PlanetTax.evaluate(galaxy,r.copy(base=1002),session(),player(1002),s,catalog()).rejection)
        val borrowed=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(54,1001,10000,neighbor))))
        assertEquals(TaxRejection.ACCESS,PlanetTax.evaluate(galaxy,r,session(),p,borrowed,catalog()).rejection)
    }

    @Test fun taxOfficeSystemComesFromFamilyEvenWithEmpireDisplayAndShips() {
        val definitions=catalog("alliance")
        val s=stocks(136,190);val p=player(1001)
        val r=TaxRequest("actor","c",10000,country,1,1001,35,190)
        assertNull(PlanetTax.evaluate(galaxy,r,session(),p,s,definitions).rejection)
        assertEquals(TaxRejection.CARD,PlanetTax.evaluate(galaxy,r.copy(card=54),session(),p,s,definitions).rejection)
        val capital=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(192,1000,10000,country))))
        assertNull(PlanetTax.evaluate(galaxy,r.copy(base=1000,card=192),session(),player(),capital,definitions).rejection)
        assertEquals(TaxRejection.ACCESS,PlanetTax.evaluate(galaxy,r.copy(card=192),session(),p,capital,definitions).rejection)
        val independent=player(1001,null)
        assertEquals(TaxRejection.CARD,PlanetTax.evaluate(galaxy,r.copy(country=null),session(null),independent,s,definitions).rejection)
    }

    @Test fun aidQuotesConfirmAndReplayOnlyWithinTheActualCountryForBothOfficeSystems() {
        for((family,card) in listOf("empire" to 8,"alliance" to 136)) {
            val definitions=catalog(family);val p=player();val s=stocks(card,if(card==8)54 else 190)
            val request=budget(card)
            val prepared=BudgetActions.evaluate(galaxy,request,session(),p,s,countries=definitions)
            assertNull(prepared.rejection)
            val quote=assertNotNull(prepared.view?.quote)
            assertEquals(country,quote.country);assertEquals(1250L,quote.amount)
            val confirm=request.copy(phase=2,quote=quote.id)
            val result=BudgetActions.evaluate(galaxy,confirm,session(),prepared.player,assertNotNull(prepared.stocks),countries=definitions)
            assertNull(result.rejection);assertEquals(680,result.player?.mcp)
            val after=assertNotNull(result.stocks)
            assertEquals(3750L,after.nationalTreasuries[country]);assertEquals(120000L,after.nationalTreasuries[neighbor])
            assertEquals(1450L,after.bases.getValue(1001).treasury)
            val replay=BudgetActions.evaluate(galaxy,confirm,session(),result.player,after,countries=definitions)
            assertFalse(replay.changed);assertEquals(after,replay.stocks);assertEquals(result.player,replay.player)
            assertEquals(BudgetRejection.CARD,BudgetActions.evaluate(galaxy,request.copy(identity=request.identity.copy(
                card=if(card==8)136 else 8)),session(),p,s,countries=definitions).rejection)
        }
    }

    @Test fun foreignBaseAndForeignOfficeCannotUseTheirNeighborsTreasury() {
        val s=stocks();val request=budget()
        val foreign=BudgetActions.evaluate(galaxy,request.copy(base=1002),session(),player(),s,countries=catalog())
        assertEquals(BudgetRejection.BASE,foreign.rejection);assertEquals(s,foreign.stocks)
        val borrowed=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(8,1000,10000,neighbor))))
        val denied=BudgetActions.evaluate(galaxy,request,session(),player(),borrowed,countries=catalog())
        assertEquals(BudgetRejection.AUTHORITY,denied.rejection);assertEquals(borrowed,denied.stocks)
    }

    @Test fun changingMembershipCannotReplayOrExposeTheOldCountrysQuote() {
        val prepared=BudgetActions.evaluate(galaxy,budget(),session(),player(),stocks(),countries=catalog())
        val quote=assertNotNull(prepared.view?.quote)
        val before=assertNotNull(prepared.stocks)
        val changed=before.copy(bases=before.bases+(1001 to before.bases.getValue(1001).copy(owner=Allegiance.Country(neighbor))),
            strategic=before.strategic.copy(offices=listOf(OfficeHolding(8,1002,10000,neighbor))))
        val request=budget(id=neighbor,phase=2,quote=quote.id)
        val result=BudgetActions.evaluate(galaxy,request,session(neighbor),player(1002,neighbor),changed,countries=catalog())
        assertEquals(BudgetRejection.STALE,result.rejection);assertNull(result.view?.quote)
        assertEquals(changed,result.stocks);assertEquals(1000,result.player?.mcp)
    }

    @Test fun changingOfficeSystemAllowsANewQuoteAndRefusesTheOldCardReceipt() {
        val prepared=BudgetActions.evaluate(galaxy,budget(),session(),player(),stocks(),countries=catalog())
        val old=assertNotNull(prepared.view?.quote)
        val before=assertNotNull(prepared.stocks)
        val changed=before.copy(strategic=before.strategic.copy(offices=listOf(OfficeHolding(136,1000,10000,country))))
        val definitions=catalog("alliance")
        val request=budget(136)
        val next=BudgetActions.evaluate(galaxy,request,session(),player(),changed,countries=definitions)
        assertTrue(next.changed)
        val quote=assertNotNull(next.view?.quote)
        assertEquals(136,quote.card);assertTrue(quote.id>old.id)
        val stale=BudgetActions.evaluate(galaxy,request.copy(phase=2,quote=old.id),session(),player(),
            assertNotNull(next.stocks),countries=definitions)
        assertEquals(BudgetRejection.STALE,stale.rejection)
        assertEquals(5000L,stale.stocks?.nationalTreasuries?.get(country))
    }
}
