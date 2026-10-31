package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-13 guard purchase/disband at the capital yard. Author: Peppone Choi. */
class GuardUnitActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val yard=galaxy.facilities.getValue(1049).single {it.kind==23}.spots.single {it.kind==21}.id
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=yard,hull=FlagshipHull(0,now))
    private val identities=listOf(StrategicIdentity(10000,2,7,0,List(8){50}))
    private fun stocks(points:Long=50000)=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        warehouses=mapOf(5 to OutfitWarehouse(5,2,OutfitKind.PATROL,1049)),
        strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=7,evaluation=points))),deploymentInitialized=true,nextShipId=100)
    private fun request(argument:Int=4,value:Int=0)=StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,96,0,7,value=value,
        argument=argument,identities=identities,navigators=mapOf("actor" to player))
    private fun act(r:StrategyActionRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player)=StrategyActions.evaluate(galaxy,r,session,p,s)

    @Test fun purchaseBuildsAPatrolGuardOfThreeUnitsAndDisbandRemovesIt() {
        var s=stocks();var p=player
        for((n,index) in listOf(0,4,12).withIndex()) {
            val result=act(request(value=index),s,p);assertNull(result.rejection,"purchase $n")
            s=result.stocks!!;p=result.player!!
        }
        val guard=GuardUnitActions.guardOf(s,10000)!!
        assertEquals(OutfitKind.PATROL,guard.kind);assertEquals(10000,guard.commanderId);assertEquals(5,guard.warehouseId)
        assertEquals(mapOf(100 to 0,101 to 4,102 to 12),guard.guard)
        assertEquals(listOf(300,300,300),guard.activeShips!!.values.map {it.boats})
        assertEquals(50000L-20000-8000-500,s.strategic.people.getValue(10000).evaluation)
        assertEquals(1000-3*80,p.pcp)
        assertEquals(StrategyActionRejection.MAXIMUM,act(request(value=12),s,p).rejection)
        val disbanded=act(request(argument=5),s,p);assertNull(disbanded.rejection)
        assertNull(GuardUnitActions.guardOf(disbanded.stocks!!,10000))
        assertEquals(StrategyActionRejection.TROOPS,act(request(argument=5),disbanded.stocks!!,disbanded.player!!).rejection)
    }
    @Test fun yardRankPointsFactionAndMembershipAreChecked() {
        assertEquals(StrategyActionRejection.LOCATION,act(p=player.copy(spotId=0)).rejection)
        assertEquals(StrategyActionRejection.POINTS,act(s=stocks(points=19999)).rejection)
        val low=stocks().let {it.copy(strategic=it.strategic.copy(people=mapOf(10000 to StrategicPerson(rank=8,evaluation=50000))))}
        assertEquals(StrategyActionRejection.RANK,act(s=low).rejection)
        assertNull(act(request(value=4),low).rejection)
        assertEquals(StrategyActionRejection.COMMAND,act(request(value=13)).rejection)
        val member=stocks().let {it.copy(outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.PATROL,1049,5,10000)))}
        assertEquals(StrategyActionRejection.AUTHORITY,act(s=member).rejection)
        assertEquals(StrategyActionRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
    }
}
