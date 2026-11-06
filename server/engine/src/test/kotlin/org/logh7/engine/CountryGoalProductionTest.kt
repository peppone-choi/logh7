package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryGoalProductionTest {
    private val country=CountryId(42)
    private val other=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1003).map {
        GalaxyBase(it,2487,80,0,"Base $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {it.join("a",country,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val player=Navigator(1,10000,country,2487,1000,1000,1000,baseId=1001,mode=4,shipStyle=ShipStyleId("empire"))
    private fun catalog(family:String="empire",style:String="empire")=CountryCatalogLoader.load().let {old->CountryCatalog(
        old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id",family=family,shipStyleOverride=style)})}
    private fun stock(family:String="empire")=PlanetStocks(1,0,(1000..1003).associate {id->id to PlanetStock(id,
        when(id){1002->Allegiance.Country(other);1003->Allegiance.Neutral;else->Allegiance.Country(country)},0,
        100000,100000,100000,PlanetEconomy(500000,300000,60,60),treasury=200_000_000)},
        strategic=StrategicState(capitals=mapOf(country to 1000,other to 1002),fiefs=mapOf(1000 to 10000,1002 to 10000),
            offices=listOf(OfficeHolding(if(family=="empire")5 else 133,1000,10000,country),
                OfficeHolding(if(family=="empire")54 else 190,1001,10000,country))))
    private fun request(command:Int,card:Int,target:Int=0,value:Int=0)=SocialRequest(
        StrategyActionRequest("a","c",10000,country,1,command,card,1,target=target,value=value))

    @Test fun nationalGoalsUseOwnOfficesAndAnActualForeignCountryEvenWithTheSameDisplay() {
        for(family in listOf("empire","alliance")) {
            val definitions=catalog(family);val s=stock(family);val card=if(family=="empire")5 else 133
            fun act(target:Int,source:PlanetStocks=s)=GoalActions.evaluate(galaxy,request(15,card,target),session,player,source,definitions)
            val accepted=act(1002)
            assertNull(accepted.rejection);assertEquals(680,accepted.player?.mcp)
            val pending=assertNotNull(accepted.stocks)
            assertEquals(setOf(42),pending.social.goals.national.keys)
            assertEquals(1002,pending.social.goals.national.getValue(42).base)
            assertEquals(SocialRejection.TARGET,act(1001).rejection)
            assertEquals(SocialRejection.TARGET,act(1003).rejection)
            val borrowed=s.copy(strategic=s.strategic.copy(offices=listOf(OfficeHolding(card,1002,10000,other))))
            assertEquals(SocialRejection.CARD,act(1002,borrowed).rejection)
            val both=pending.copy(social=pending.social.copy(goals=pending.social.goals.copy(
                national=pending.social.goals.national+(43 to NationalGoal(1002,10001,now)))))
            val won=GoalActions.achieved(both,1002,country,listOf(10000,10000))
            assertEquals(GoalActions.GOAL_MERIT,won.strategic.people.getValue(10000).merit)
            assertEquals(setOf(43),won.social.goals.national.keys)
        }
    }

    @Test fun aGovernorsCardDoesNotBorrowHisFiefCardOrAnotherCountrysSeat() {
        val s=stock();val definitions=catalog()
        fun act(card:Int,base:Int)=GoalActions.evaluate(galaxy,request(21,card,base,GoalActions.APPROVAL),session,player,s,definitions)
        val focus=act(54,1001)
        assertNull(focus.rejection);assertEquals(920,focus.player?.mcp)
        assertEquals(GoalActions.APPROVAL,focus.stocks?.social?.goals?.focus?.get(1001))
        assertEquals(SocialRejection.TARGET,act(54,1000).rejection,"a different card owns this fief")
        assertNull(act(66,1000).rejection)
        assertEquals(SocialRejection.TARGET,act(66,1002).rejection,"a foreign fief is not governed")
        fun produce(card:Int)=ProductionActions.evaluate(galaxy,request(34,card),session,player.copy(baseId=1000),s,catalog=definitions)
        assertEquals(SocialRejection.LOCATION,produce(54).rejection,"production also requires the selected card's own seat")
        assertNull(produce(66).rejection)
    }

    @Test fun manualShipsAndRecruitsUseCountryStyleWithoutChangingCountryOrOfficeFamily() {
        for(family in listOf("empire","alliance")) {
            val definitions=catalog(family,"alliance");val s=stock(family);val card=if(family=="empire")54 else 190
            val ship=ProductionActions.kinds(country,true,definitions).first()
            assertEquals(3,ship.power)
            val ordered=ProductionActions.evaluate(galaxy,request(35,card,value=ship.kind),session,player,s,catalog=definitions)
            assertNull(ordered.rejection);assertEquals(920,ordered.player?.mcp);assertEquals(country,ordered.player?.country)
            val pending=assertNotNull(ordered.stocks);val due=pending.social.production.orders.getValue(1001)
            assertEquals(ship.kind,due.kind)
            assertSame(pending,ProductionActions.settle(pending,due.readyAt-1))
            val ready=ProductionActions.settle(pending,due.readyAt)
            assertEquals(1,ready.bases.getValue(1001).units.ships[UnitStack(ship.kind)])
            assertEquals(country,ready.bases.getValue(1001).country);assertTrue(ready.social.production.orders.isEmpty())
            val troop=ProductionActions.kinds(country,false,definitions).first()
            val recruited=ProductionActions.evaluate(galaxy,request(36,card,value=troop.kind),session,player,s,catalog=definitions)
            assertNull(recruited.rejection);assertEquals(840,recruited.player?.mcp)
            val base=assertNotNull(recruited.stocks).bases.getValue(1001)
            assertEquals(1,base.units.troops[UnitStack(troop.kind)]);assertEquals(299000,base.economy?.adults)
            val wrong=ProductionActions.kinds(2,true).first()
            assertEquals(SocialRejection.TARGET,ProductionActions.evaluate(galaxy,request(35,card,value=wrong.kind),
                session,player,s,catalog=definitions).rejection)
        }
    }

    @Test fun shipyardLifecycleAndForeignLocationRespectActualOwnership() {
        val s=stock();val definitions=catalog()
        fun act(command:Int,state:PlanetStocks,p:Navigator=player)=ProductionActions.evaluate(galaxy,request(command,54),session,p,state,catalog=definitions)
        val razed=assertNotNull(act(34,s).stocks)
        val building=assertNotNull(act(31,razed).stocks)
        assertEquals(100_000_000L,building.bases.getValue(1001).treasury)
        assertFalse(ProductionActions.shipyard(galaxy,building,1001,now))
        assertTrue(ProductionActions.shipyard(galaxy,building,1001,now+30*86400))
        val paused=assertNotNull(act(32,building).stocks)
        assertFalse(ProductionActions.shipyard(galaxy,paused,1001,now+30*86400))
        val resumed=assertNotNull(act(33,paused).stocks)
        assertTrue(ProductionActions.shipyard(galaxy,resumed,1001,now+30*86400))
        val denied=act(34,s,player.copy(baseId=1002))
        assertEquals(SocialRejection.LOCATION,denied.rejection);assertEquals(s,denied.stocks);assertEquals(player.mcp,denied.player?.mcp)
    }
}
