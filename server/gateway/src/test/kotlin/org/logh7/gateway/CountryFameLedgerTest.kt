package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.SessionEndingMessages
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CountryFameLedgerTest {
    @TempDir lateinit var directory:Path
    private val a=CountryId(42); private val b=CountryId(43); private val c=CountryId(44)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(42,43,44).map {id->seed.countries.getValue(2).copy(id=id,key="country$id",name="C$id")})}
    private fun character(id:Long,rank:Int=8)=CharacterMessages.Generate(4,id,2,0,0,"N$id","Pilot",18,1,1,1000001,
        List(8){50},0,0,0,rank,3,0,"Ship$id",1)
    private fun world():WorldSnapshot {
        val end=SessionEnd(EndReason.CAPITAL_CAPTURED,emptySet(),123,setOf(b,c),setOf(a))
        val result=SessionResults.assessCountries(end,SessionStatistics(population=100),mapOf(
            a to SessionStatistics(population=50,shipUnits=10),b to SessionStatistics(population=40,shipUnits=1),
            c to SessionStatistics(population=10,shipUnits=1)),catalog=catalog)
        val people=(10000..10004).associateWith {id->StrategicPerson(evaluation=if(id==10004)30 else 250,deceasedAt=if(id==10004)50 else 0)}
        return WorldSnapshot(0,0,SessionSimulation(SessionRules.load()).snapshot().copy(ended=end,countries=setOf(a,b,c)),
            planetStocks=PlanetStocks(1,0,emptyMap(),strategic=StrategicState(people=people)),result=result)
    }

    @Test fun actualMembershipAndCurrentDefectionDetermineRewardsAndReceiptsSurviveRestart() {
        val people=CharacterCreation(directory.resolve("people"))
        val profiles=listOf("winner" to CharacterProfile(character(10000),a,ShipStyleId("empire")),
            "loser" to CharacterProfile(character(10001),b,ShipStyleId("empire")),
            "defected" to CharacterProfile(character(10002),b,ShipStyleId("empire")),
            "independent" to CharacterProfile(character(10003),null,ShipStyleId("empire")),
            "fallen" to CharacterProfile(character(10004,5),c,ShipStyleId("empire")))
        profiles.forEach {(account,profile)->assertTrue(people.carry(account,profile))}
        val moved=Navigator(1,10002,a,2487,0,0,0,shipStyle=ShipStyleId("empire"))
        val world=world().copy(navigators=mapOf("defected" to moved))
        val file=directory.resolve("fame")
        val bank=FameLedger(file)
        assertEquals(setOf(a),FameLedger.winners(world.result!!))
        assertEquals(mapOf("winner" to 9,"loser" to 2,"defected" to 9,"independent" to 2,"fallen" to 8),bank.settle(world,people))
        val saved=Files.readAllBytes(file)
        val reopened=FameLedger(file)
        val expected=SessionEndingMessages.Evaluation(250,933,0,9,0)
        val changed=world.copy(navigators=mapOf("defected" to moved.copy(country=b)))
        assertEquals(expected,reopened.evaluation(changed,CharacterCreation(directory.resolve("people")),"defected"))
        assertTrue(reopened.settle(changed,people).isEmpty())
        assertContentEquals(saved,Files.readAllBytes(file))
        assertEquals(SessionEndingMessages.Evaluation(250,250,0,2,0),reopened.evaluation(world,people,"independent"))
    }

    @Test fun sameAccountInDifferentWorldCountriesSharesFameWithoutSharingRewardEligibility() {
        val winner=CharacterCreation(directory.resolve("a")); val loser=CharacterCreation(directory.resolve("b"))
        assertTrue(winner.carry("same",CharacterProfile(character(10000),a,ShipStyleId("empire"))))
        assertTrue(loser.carry("same",CharacterProfile(character(10000),b,ShipStyleId("empire"))))
        val file=directory.resolve("shared")
        FameLedger.openShared(file,false).use {shared->
            val first=shared.forWorld("first"); val second=shared.forWorld("second")
            assertEquals(mapOf("same" to 9),first.settle(world(),winner))
            assertEquals(mapOf("same" to 2),second.settle(world(),loser))
            assertTrue(first.settle(world(),winner).isEmpty()); assertTrue(second.settle(world(),loser).isEmpty())
            assertEquals(11,shared.accounts["same"])
        }
        FameLedger.openShared(file,false).use {shared->
            assertEquals(SessionEndingMessages.Evaluation(250,933,0,9,0),shared.forWorld("first").evaluation(world(),winner,"same"))
            assertEquals(SessionEndingMessages.Evaluation(250,250,9,11,0),shared.forWorld("second").evaluation(world(),loser,"same"))
        }
    }
}
