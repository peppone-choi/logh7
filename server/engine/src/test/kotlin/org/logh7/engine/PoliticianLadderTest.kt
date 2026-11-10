package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    @Test fun foreignAndUnattachedPoliticiansNeitherInheritNorChangeRankOrAbilities() {
        val foreign=listOf(CountryId(1),CountryId(4),CountryId(42),null).mapIndexed {index,country->
            StrategicIdentity(100+index,country,1,0,List(8){99})
        }
        val people=foreign.associate {it.id to StrategicPerson()}
        val before=stocks.copy(strategic=StrategicState(people=people))
        val after=PoliticianLadder.monthly(galaxy,before,foreign,1)
        assertEquals(people,after.strategic.people)
        assertTrue(OrganizationalOfficePolicy(galaxy).holders(after,1).isEmpty())
        assertTrue(OrganizationalOfficePolicy(galaxy).holders(after,132).isEmpty())
        assertEquals(listOf(2),SuccessionActions.order(before,foreign+id(2,2)))
    }
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
    @Test fun aVacantChairmanSeatGoesToTheLargestSupportBoxThenTheLadder() {
        val people=mapOf(11 to StrategicPerson(merit=90),12 to StrategicPerson(merit=10))
        val ids=listOf(id(11,3),id(12,3),id(13,2))
        val backed=stocks.copy(strategic=StrategicState(people=people),social=stocks.social.copy(funds=FundState(support=mapOf(12 to 5000L,13 to 100L))))
        val elected=PoliticianLadder.election(galaxy,backed,ids)
        assertEquals(listOf(12),OrganizationalOfficePolicy(galaxy).holders(elected,132).map {it.character},"the support box decides")
        assertEquals(mapOf(13 to 100L),elected.social.funds.support,"the alliance support box is emptied")
        val ladder=PoliticianLadder.election(galaxy,stocks.copy(strategic=StrategicState(people=people)),ids)
        assertEquals(listOf(11),OrganizationalOfficePolicy(galaxy).holders(ladder,132).map {it.character},"no support: the ladder top")
        assertSame(ladder,PoliticianLadder.election(galaxy,ladder,ids),"a filled seat stays")
    }
    @Test fun detentionExcludesTheTopChairCandidateUntilRelease() {
        val policy = OrganizationalOfficePolicy(galaxy)
        val country = Power.ALLIANCE.country
        val people = mapOf(11 to StrategicPerson(rank = 1, merit = 90), 12 to StrategicPerson(rank = 1, merit = 10))
        val support = mapOf(11 to 5000L, 12 to 500L)
        val occupied = policy.set(stocks.copy(strategic = StrategicState(people = people),
            social = stocks.social.copy(funds = FundState(support = support))), OfficeHolding(132, 1177, 11))
        val players = mutableMapOf("held" to Navigator(1, 11, Power.ALLIANCE, 2015, 1000, 1000, 1000, baseId = 1177, mode = 4))
        val now = SessionRules.load().startSeconds + 30 * Justice.DAY
        val held = assertNotNull(JusticeActions.detain(galaxy, occupied, players, 11, country, now))
        assertTrue(policy.holders(held, 132).isEmpty())

        val elected = PoliticianLadder.monthly(galaxy, held, listOf(id(11, 3), id(12, 3)), 1)
        assertEquals(listOf(12), policy.holders(elected, 132).map { it.character })
        assertTrue(elected.social.funds.support.isEmpty(), "a completed election still clears the support box")
        val vacant = PoliticianLadder.election(galaxy, held, listOf(id(11, 3)))
        assertEquals(held, vacant, "a failed election leaves the seat vacant and preserves support")

        val releasedAt = held.social.justice.detentions.getValue(11).releaseAt
        val session = SessionSimulation(SessionRules.load()).exportState().copy(gameMillis = releasedAt * 1000)
        val released = JusticeActions.settle(galaxy, session, players, vacant)
        assertFalse(released.social.justice.detained(11))
        val afterRelease = PoliticianLadder.election(galaxy, released, listOf(id(11, 3)))
        assertEquals(listOf(11), policy.holders(afterRelease, 132).map { it.character })
    }

    @Test fun forcedMilitaryConversionVacatesTheChairAndElectsAnEligiblePolitician() {
        val policy=OrganizationalOfficePolicy(galaxy)
        val before=stocks.copy(strategic=StrategicState(people=mapOf(11 to StrategicPerson(rank=1),
            12 to StrategicPerson(rank=1,merit=10))))
        val seated=policy.set(before,OfficeHolding(132,1177,11))
        val result=PoliticianLadder.monthly(galaxy,seated,listOf(id(11,3),id(12,3)),1)
        assertEquals(10,result.strategic.people.getValue(11).rank)
        assertEquals(listOf(12),policy.holders(result,132).map {it.character})
    }
    @Test fun aLostConfidenceVoteRemovesTheChairmanAndElectsAnother() {
        val people=mapOf(11 to StrategicPerson(merit=90),12 to StrategicPerson(merit=10))
        val ids=listOf(id(11,3),id(12,3))
        val seated=OrganizationalOfficePolicy(galaxy).set(stocks.copy(strategic=StrategicState(people=people)),OfficeHolding(132,1177,11))
        val otherBoxes=mapOf(2 to (30L to 40L),42 to (50L to 60L))
        val distrust=seated.copy(social=seated.social.copy(funds=FundState(confidence=otherBoxes+(3 to (100L to 500L)),support=mapOf(12 to 50L))))
        val after=PoliticianLadder.monthly(galaxy,distrust,ids,1)
        assertEquals(listOf(12),OrganizationalOfficePolicy(galaxy).holders(after,132).map {it.character})
        assertEquals(otherBoxes,after.social.funds.confidence)
        val heard=SuccessionActions.notices(distrust,after,7).filter {it.kind!=SuccessionNotice.Kind.ACCESSION}
        assertEquals(listOf(SuccessionNotice(after.generation,11,SuccessionNotice.Kind.CHAIRMAN_DISMISSED,7),
            SuccessionNotice(after.generation,12,SuccessionNotice.Kind.CHAIRMAN_ELECTED,7)),heard)
        val trusted=seated.copy(social=seated.social.copy(funds=FundState(confidence=mapOf(3 to (500L to 100L)))))
        assertEquals(listOf(11),OrganizationalOfficePolicy(galaxy).holders(PoliticianLadder.monthly(galaxy,trusted,ids,1),132).map {it.character})
    }
    @Test fun aDeadPoliticianNeitherInheritsNorIsElected() {
        val people=mapOf(4 to StrategicPerson(merit=90,deceasedAt=5),2 to StrategicPerson(merit=10),
            11 to StrategicPerson(merit=90,deceasedAt=5),12 to StrategicPerson(merit=1))
        val result=PoliticianLadder.monthly(galaxy,stocks.copy(strategic=StrategicState(people=people)),
            listOf(id(2,2),id(4,2),id(11,3),id(12,3)),1)
        assertEquals(listOf(2),OrganizationalOfficePolicy(galaxy).holders(result,1).map {it.character})
        assertEquals(listOf(12),OrganizationalOfficePolicy(galaxy).holders(result,132).map {it.character})
    }
    @Test fun aRevoltKeepsTheThroneForTheCoupsOutcome() {
        // CD p.38: succession applies to a vacancy other than by coup.
        val people=mapOf(4 to StrategicPerson(merit=90))
        val revolt=CoupState(mapOf(9 to Plot(9,CountryId(2),0,revolt=1)))
        val s=stocks.copy(strategic=StrategicState(people=people),social=stocks.social.copy(coup=revolt))
        val result=PoliticianLadder.monthly(galaxy,s,listOf(id(4,2),id(9,2)),1)
        assertTrue(OrganizationalOfficePolicy(galaxy).holders(result,1).isEmpty())
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
    @Test fun monthlySettlementRefreshesChangedRankOfficesAndAbilitiesOnce()=runBlocking {
        val rules=SessionRules.load()
        val policy=OrganizationalOfficePolicy(galaxy)
        val country=CountryId(3)
        var saved=PlanetStocks(1,0,mapOf(1177 to PlanetStock(1177,3,0,100,200,300)),
            strategic=StrategicState(people=mapOf(11 to StrategicPerson(rank=1),12 to StrategicPerson(rank=1,merit=10))))
        saved=policy.set(saved,OfficeHolding(132,1177,11,country))
        val store=object:PlanetStockStore {
            override fun load()=saved
            override fun save(stocks:PlanetStocks){saved=stocks}
        }
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,stockStore=store)
        val identities=engine.provideIdentities {
            listOf(11,12).map {StrategicIdentity(it,3,1,0,List(8){50})}+
                StrategicIdentity(10001,3,10,0,List(8){50},18)
        }
        val refreshes=mutableListOf<Pair<Set<Int>,PlanetStocks>>()
        val subscription=engine.observeCharacterRefresh {ids->refreshes+=ids.toSet() to saved}
        try {
            for(day in listOf(29L,30L,31L)) {
                engine.submit(WorldCommand.AdvanceTo(day*86400*1000/rules.speed))
                val barrier=CompletableDeferred<PlanetStocks>()
                engine.submit(WorldCommand.Stock(1,barrier))
                withTimeout(5000){barrier.await()}
            }
            val (ids,committed)=refreshes.single()
            assertEquals(setOf(11,12,10001),ids)
            assertEquals(listOf(50,50,50,50,50,50,50,51),committed.strategic.people.getValue(10001).abilities)
            assertEquals(10,committed.strategic.people.getValue(11).rank)
            assertEquals(listOf(12),policy.holders(committed,132,country).map {it.character})
        } finally {
            subscription.close()
            identities.close()
            engine.drainAndClose()
        }
    }
    @Test fun anUnwiredSupplierHoldsTheBoundaryUntilCharactersArrive() {
        // Startup catch-up runs before the gateway wires identities; it must not consume month 1.
        val start=SessionRules.load().startSeconds
        val production=PlanetProduction(galaxy,null,start,PlanetProductionPolicy())
        production.identities=null
        val held=production.settle(1,start+45*86400)
        assertEquals(29,held.producedDays);assertNull(held.strategic.people[9])
        production.identities={listOf(StrategicIdentity(9,3,1,0,List(8){50}))}
        val crossed=production.settle(1,start+45*86400)
        assertEquals(45,crossed.producedDays);assertEquals(10,crossed.strategic.people.getValue(9).rank)
    }
}
