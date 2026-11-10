package org.logh7.gateway

import java.nio.file.Files
import java.util.HexFormat
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** 세션 결과의 명성 환산: 승리 생존자·준장 이상 전사자 보상, 세대당 1회, 다음 세대 공급. 작성: Peppone Choi. */
class FameLedgerTest {
    private fun character(id:Long,power:Int,rank:Int)=CharacterMessages.Generate(4,id,power,0,0,"N$id","Pilot",18,1,1,1000001,
        List(8){50},0,0,0,rank,3,0,"Ship$id",1)
    @Test fun retiredCasualtiesKeepTheirRewardAfterReloadAndReplacementWithoutMergingRounding() {
        val file=Files.createTempDirectory("fame-retired").resolve("characters.properties")
        val original=CharacterCreation(file)
        assertTrue(original.carry("replacement",character(10001,3,5)))
        assertTrue(original.carry("fallen",character(10002,3,5)))
        assertTrue(original.carry("unconfirmed",character(10003,3,5)))
        assertTrue(original.retire("replacement",10001))
        assertTrue(original.retire("fallen",10002))
        assertTrue(original.retire("unconfirmed",10003))
        assertTrue(CharacterCreation(file).carry("replacement",character(10004,3,9)))

        val end=SessionEnd(EndReason.CAPITAL_CAPTURED,setOf(Power.ALLIANCE),123)
        val result=SessionResults.assess(end,SessionStatistics(population=10),mapOf(
            Power.EMPIRE to SessionStatistics(population=6),Power.ALLIANCE to SessionStatistics(population=4)),
            VictoryConditions(true,true))
        val stocks=PlanetStocks(1,0,emptyMap(),strategic=StrategicState(people=mapOf(
            10001 to StrategicPerson(evaluation=1257,deceasedAt=50),
            10002 to StrategicPerson(evaluation=30,deceasedAt=80),
            10003 to StrategicPerson(evaluation=5000),
            10004 to StrategicPerson(evaluation=99))))
        val world=WorldSnapshot(0,0,SessionSimulation(SessionRules.load()).snapshot().copy(ended=end),planetStocks=stocks,result=result)
        val stores=CharacterStores(file)
        val current=stores.get(1)
        assertEquals(mapOf("replacement" to 10004L),current.profiles().mapValues {it.value.character.id})
        assertTrue(stores.carryOver(world).isEmpty())
        assertTrue(stores.get(2).profiles().isEmpty())

        // 2099 points from the casualty and 99 from the replacement are each divided by 100 first.
        val expected=mapOf("replacement" to 20,"fallen" to 8)
        assertEquals(expected,stores.fame.settle(world,current))
        val reopened=CharacterStores(file)
        assertEquals(expected,reopened.fame.fame())
        assertEquals(20,reopened.get(2).fame("replacement"))
        assertTrue(reopened.fame.settle(world,reopened.get(1)).isEmpty())
        assertEquals(SessionEndingMessages.Evaluation(99,99,0,20,0),reopened.fame.evaluation(world,reopened.get(1),"replacement"))
        assertEquals(SessionEndingMessages.Evaluation(30,872,0,8,0),reopened.fame.evaluation(world,reopened.get(1),"fallen"))
        assertNull(reopened.fame.evaluation(world,reopened.get(1),"unconfirmed"))
    }
    @Test fun aResultConvertsEvaluationAndRewardsOncePerGenerationIntoTheNextLottery() {
        val dir=Files.createTempDirectory("fame")
        val file=dir.resolve("characters.properties")
        val props=java.util.Properties()
        for((account,c) in listOf("winner" to character(10000,2,8),"fallen" to character(10001,3,5),"loser" to character(10002,3,9))) {
            val encoded=CharacterMessages.generateReply(c);props.setProperty(account,HexFormat.of().formatHex(encoded.copyOfRange(4,encoded.size)))
        }
        Files.newBufferedWriter(file).use {props.store(it,null)}
        val end=SessionEnd(EndReason.CAPITAL_CAPTURED,setOf(Power.ALLIANCE),123)
        val result=SessionResults.assess(end,SessionStatistics(4,2,100_000_000_001,1010,66),mapOf(
            Power.EMPIRE to SessionStatistics(2,1,90_000_009_999,1000,33),Power.ALLIANCE to SessionStatistics(2,1,9_999_990_002,10,33)),
            VictoryConditions(true,true))
        val stocks=PlanetStocks(1,0,emptyMap(),strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=250),
            10001 to StrategicPerson(evaluation=30,deceasedAt=50),10002 to StrategicPerson(evaluation=99))))
        val world=WorldSnapshot(0,0,SessionSimulation(SessionRules.load()).snapshot().copy(ended=end),planetStocks=stocks,result=result)
        val stores=CharacterStores(file)
        assertNull(stores.fame.evaluation(world,stores.get(1),"winner"))
        assertEquals(683L,FameLedger.reward(8));assertEquals(1000L,FameLedger.reward(2));assertEquals(50L,FameLedger.reward(20))
        assertEquals(mapOf("winner" to 9,"fallen" to 8),stores.fame.settle(world,stores.get(1)))
        assertTrue(stores.fame.settle(world,stores.get(1)).isEmpty(),"one settlement per generation")
        assertEquals(mapOf("winner" to 9,"fallen" to 8),CharacterStores(file).fame.fame())
        assertEquals(9,stores.get(2).fame("winner"))
        val winner=org.logh7.protocol.SessionEndingMessages.Evaluation(250,933,0,9,0)
        assertEquals(winner,stores.fame.evaluation(world,stores.get(1),"winner"))
        assertEquals(org.logh7.protocol.SessionEndingMessages.Evaluation(30,872,0,8,0),
            stores.fame.evaluation(world,stores.get(1),"fallen"))
        assertEquals(org.logh7.protocol.SessionEndingMessages.Evaluation(99,99,0,0,0),
            stores.fame.evaluation(world,stores.get(1),"loser"),"zero awards remain known statements")
        val reloaded=CharacterStores(file);val bytes=Files.readAllBytes(file.resolveSibling("${file.fileName}.fame"))
        repeat(2) {assertEquals(winner,reloaded.fame.evaluation(world,reloaded.get(1),"winner"))}
        assertContentEquals(bytes,Files.readAllBytes(file.resolveSibling("${file.fileName}.fame")),"queries never rewrite or grant")
        val next=world.copy(session=world.session!!.copy(generation=2),planetStocks=stocks.copy(generation=2))
        assertEquals(mapOf("winner" to 9,"fallen" to 8),reloaded.fame.settle(next,reloaded.get(1)))
        assertEquals(winner,reloaded.fame.evaluation(world,reloaded.get(1),"winner"),"a later generation cannot rewrite the earlier statement")
        assertEquals(org.logh7.protocol.SessionEndingMessages.Evaluation(250,933,9,18,0),
            reloaded.fame.evaluation(next,reloaded.get(1),"winner"))
        val fameFile=file.resolveSibling("${file.fileName}.fame")
        val legacy=java.util.Properties().also {Files.newBufferedReader(fameFile).use(it::load)}
        legacy.stringPropertyNames().filter {it.startsWith("@evaluation/")}.forEach(legacy::remove)
        Files.newBufferedWriter(fameFile).use {legacy.store(it,null)}
        assertNull(reloaded.fame.evaluation(world,reloaded.get(1),"winner"),"old missing history cannot use a newer generation's total")
        assertEquals(org.logh7.protocol.SessionEndingMessages.Evaluation(250,933,9,18,0),
            reloaded.fame.evaluation(next,reloaded.get(1),"winner"),"latest legacy result derives the unchanged award without regranting")
        assertEquals(18,reloaded.fame.fame().getValue("winner"))
    }
}
