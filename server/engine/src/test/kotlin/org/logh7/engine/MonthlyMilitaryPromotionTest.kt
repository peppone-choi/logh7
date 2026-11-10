package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class MonthlyMilitaryPromotionTest {
    private val country=CountryId(2)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1048,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val policy=OrganizationalOfficePolicy(galaxy)
    private fun identity(id:Int,rank:Int,own:CountryId=country)=StrategicIdentity(id,own,rank,0,List(8){50})
    private fun stocks(people:Map<Int,StrategicPerson>)=PlanetStocks(1,0,
        mapOf(1048 to PlanetStock(1048,2,0,100,200,300)),deploymentInitialized=true,
        strategic=StrategicState(people=people))

    @Test fun monthlyPromotionUsesTheOpeningLaddersAndDoesNotBreakTiesOrCapacity() {
        val roster=listOf(identity(10000,8),identity(10001,9),identity(10002,9),identity(10003,9),
            identity(10004,10),identity(10005,10),identity(10006,9,CountryId(3)))+
            (20000..20079).map {identity(it,7)}
        val initial=stocks(mapOf(10000 to StrategicPerson(merit=15),10001 to StrategicPerson(merit=100),
            10002 to StrategicPerson(merit=90),10003 to StrategicPerson(merit=900,deceasedAt=1),
            10006 to StrategicPerson(merit=1000)))
        val before=initial.copy(tacticalCommands=TacticalCommandState(merits=mapOf(10000 to 6L,10001 to 5L)))
        val promoted=mutableListOf<PersonnelActions.MonthlyPromotion>()
        val after=PersonnelActions.promoteMonthly(before,roster,setOf(CountryId(3)),30*86400L,promoted)
        assertEquals(listOf(PersonnelActions.MonthlyPromotion(10001,country,8,30*86400L)),promoted)
        assertEquals(8,after.strategic.people.getValue(10001).rank)
        assertEquals(21L,TacticalPersonnel.merit(after,10001))
        assertEquals(21L,TacticalPersonnel.merit(after,10000))
        assertNull(after.strategic.people.getValue(10000).rank,"full rank 7 blocks the colonel")
    }

    @Test fun rankAndFlagshipCommitTogetherOnlyOnceAtTheMonthlyBoundary()=runBlocking {
        val rules=SessionRules.load()
        var storedStocks=policy.set(stocks(mapOf(10000 to StrategicPerson(merit=100),10001 to StrategicPerson(merit=20))),
            OfficeHolding(54,1048,10000))
        var players=mapOf("pilot" to Navigator(1,10000,country,2487,1000,1000,1000,
            hull=FlagshipHull(3,rules.startSeconds)))
        var commits=0
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,npcPolicy=NpcTacticalPolicy(enabled=false),
            navigationStore=object:NavigationStore {
                override fun load()=players
                override fun save(players:Map<String,Navigator>) {
                    check(players.getValue("pilot").hull?.kind!=Flagships.rankDefault(2,8).kind)
                }
            },
            stockStore=object:PlanetStockStore {
                override fun load()=storedStocks
                override fun save(stocks:PlanetStocks) {
                    check(stocks.strategic.people[10000]?.rank!=8)
                    storedStocks=stocks
                }
            },
            worldStateCommitter=WorldStateCommitter {next,inventory->
                players=next
                storedStocks=checkNotNull(inventory)
                commits++
            })
        val registration=engine.provideIdentities {listOf(identity(10000,9),identity(10001,8))}
        try {
            suspend fun advance(day:Long):PlanetStocks {
                engine.submit(WorldCommand.AdvanceTo(day*86400*1000/rules.speed))
                val result=CompletableDeferred<PlanetStocks>()
                engine.submit(WorldCommand.Stock(1,result))
                return withTimeout(5000){result.await()}
            }
            advance(29)
            val before=commits
            val promoted=advance(30)
            assertEquals(8,promoted.strategic.people.getValue(10000).rank)
            assertEquals(20L,promoted.strategic.people.getValue(10000).merit)
            assertEquals(0,promoted.bases.getValue(1048).governorId)
            assertEquals(Flagships.rankDefault(2,8).kind,players.getValue("pilot").hull?.kind)
            assertEquals(before+1,commits)
            assertEquals(promoted,advance(30))
            assertEquals(before+1,commits)
        } finally {
            registration.close()
            engine.drainAndClose()
        }
    }
}
