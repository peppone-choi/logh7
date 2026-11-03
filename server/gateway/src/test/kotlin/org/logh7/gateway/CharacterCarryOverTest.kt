package org.logh7.gateway

import java.nio.file.Files
import java.util.HexFormat
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** U-27 이월: 평가 기준·60세·전사·원작 제외와 다음 세대 기록. 작성: Peppone Choi. */
class CharacterCarryOverTest {
    private fun character(id:Long,age:Int,generated:Boolean=true)=CharacterMessages.Generate(4,id,2,0,0,"N$id","Pilot",age,1,1,1000001,
        List(8){50},0,0,0,9,3,0,"Ship$id",1,generated=generated)
    @Test fun qualifyingGeneratedCharactersMoveToTheNextGenerationWithAgeAbilitiesAndRank() {
        val dir=Files.createTempDirectory("carry")
        val file=dir.resolve("characters.properties")
        val props=java.util.Properties()
        val rows=listOf("veteran" to character(10000,30),"novice" to character(10001,30),"elder" to character(10002,58),
            "fallen" to character(10003,30),"original" to character(10004,30,generated=false))
        for((account,c) in rows) {
            val encoded=CharacterMessages.generateReply(c)
            props.setProperty(account,(if(c.generated)"" else "original:")+HexFormat.of().formatHex(encoded.copyOfRange(4,encoded.size)))
        }
        Files.newBufferedWriter(file).use {props.store(it,null)}
        val twoYears=2L*12*30*86_400
        val end=SessionEnd(EndReason.TIME_LIMIT,emptySet(),twoYears)
        val result=SessionResults.assess(end,SessionStatistics(population=10),mapOf(
            Power.EMPIRE to SessionStatistics(population=6),Power.ALLIANCE to SessionStatistics(population=4)),VictoryConditions(true,true))
        val strong=StrategicPerson(evaluation=CharacterCarryOver.MINIMUM_EVALUATION,rank=6,abilities=List(8){70})
        val stocks=PlanetStocks(1,0,emptyMap(),strategic=StrategicState(people=mapOf(10000 to strong,
            10001 to StrategicPerson(evaluation=CharacterCarryOver.MINIMUM_EVALUATION-1),10002 to strong,
            10003 to strong.copy(deceasedAt=50),10004 to strong)))
        val session=SessionSimulation(SessionRules.load()).snapshot().copy(gameSeconds=twoYears,ended=end)
        val world=WorldSnapshot(0,0,session,planetStocks=stocks,result=result)
        val stores=CharacterStores(file)
        assertEquals(listOf("veteran"),stores.carryOver(world),"under the threshold, 60 at the end, dead or original stay behind")
        assertTrue(stores.carryOver(world).isEmpty(),"carrying twice adds nothing")
        val next=CharacterStores(file).get(2).character("veteran")!!
        assertEquals(32,next.age);assertEquals(6,next.rank);assertEquals(List(8){70},next.abilities)
        assertEquals(0L,next.evaluation);assertTrue(next.generated)
        assertNull(CharacterStores(file).get(2).character("novice"))
        assertNotNull(CharacterStores(file).get(1).character("veteran"),"the finished generation keeps its file")
    }
}
