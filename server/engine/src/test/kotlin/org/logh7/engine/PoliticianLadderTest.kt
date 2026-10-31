package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-28 CD p.38 politician ladder rules. Author: Peppone Choi. */
class PoliticianLadderTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
            GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val stocks=PlanetStocks(1,30,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1177 to PlanetStock(1177,3,0,100,200,300)))
    private fun id(id:Int,power:Int,rank:Int=1)=StrategicIdentity(id,power,rank,0,List(8){50})
    @Test fun zeroMeritPoliticiansAreConvertedOrWeakenedByFaction() {
        val people=mapOf(3 to StrategicPerson(merit=5),4 to StrategicPerson(merit=7))
        val s=stocks.copy(strategic=StrategicState(people=people))
        val result=PoliticianLadder.monthly(galaxy,s,listOf(id(1,3),id(2,2),id(3,3),id(4,2),id(5,3,rank=8)),1)
        val p=result.strategic.people
        assertEquals(10,p.getValue(1).rank)
        assertEquals(399,p.getValue(2).abilities!!.sum());assertNull(p.getValue(2).rank)
        assertEquals(people.getValue(3),p.getValue(3))
        assertNull(p[5])
    }
    @Test fun aVacantThroneGoesToTheTopEmpirePolitician() {
        val people=mapOf(2 to StrategicPerson(merit=40),4 to StrategicPerson(merit=90))
        val result=PoliticianLadder.monthly(galaxy,stocks.copy(strategic=StrategicState(people=people)),
            listOf(id(2,2),id(4,2),id(6,3)),1)
        assertEquals(listOf(4),OrganizationalOfficePolicy(galaxy).holders(result,1).map {it.character})
        val again=PoliticianLadder.monthly(galaxy,result,listOf(id(2,2),id(4,2),id(7,2)),2)
        assertEquals(listOf(4),OrganizationalOfficePolicy(galaxy).holders(again,1).map {it.character})
    }
}

/** The daily settlement runs the ladder rules once per crossed 30-day boundary. Author: Peppone Choi. */
class PoliticianLadderSettlementTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(4,2015,4,"Alliance",3),GalaxySystem(3,2487,4,"Empire",2)),
        listOf(GalaxyBase(1177,2015,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    @Test fun dayThirtyConvertsAZeroMeritAlliancePoliticianOnce() {
        val start=SessionRules.load().startSeconds
        val production=PlanetProduction(galaxy,null,start,PlanetProductionPolicy())
        production.identities={listOf(StrategicIdentity(9,3,1,0,List(8){50}))}
        assertNull(production.settle(1,start+29*86400).strategic.people[9])
        assertEquals(10,production.settle(1,start+30*86400).strategic.people.getValue(9).rank)
        assertEquals(10,production.settle(1,start+31*86400).strategic.people.getValue(9).rank)
    }
}
