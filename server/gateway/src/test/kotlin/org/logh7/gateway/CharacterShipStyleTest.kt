package org.logh7.gateway

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.OriginalCharacterMessages
import kotlin.test.*

class CharacterShipStyleTest {
    @TempDir lateinit var dir:Path
    private val swapped=mapOf(2 to ShipStyleId("alliance"),3 to ShipStyleId("empire"))
    private fun decode(bytes:ByteArray)=CharacterMessages.generate(bytes.copyOfRange(4,bytes.size))
    @Test fun creationUsesTheConfiguredModelFamilyWhileKeepingFactionOriginsAndPortraits() {
        val file=dir.resolve("characters.properties")
        val store=CharacterCreation(file,shipStyles=swapped)
        for(power in 2..3) {
            val account="pilot$power"
            var c=CharacterMessages.Generate(0,0,power,if(power==2)0 else 3,0,"Style$power","Pilot",0,0,0,0,List(8){0},0,0,0,0,0,0,"",0)
            c=decode(store.accept(account,c))
            c=decode(store.accept(account,c.copy(category=1,age=30,birthMonth=1,birthDay=1,face=1000001)))
            assertEquals(if(power==2)89 else 0,c.shipKind)
            c=decode(store.accept(account,c.copy(category=2)))
            c=decode(store.accept(account,c.copy(category=3,shipName="Style$power")))
            c=decode(store.accept(account,c.copy(category=4)))
            val restored=CharacterCreation(file).profiles().getValue(account)
            assertEquals(c,restored.character);assertEquals(CountryId(power),restored.country)
            assertEquals(swapped.getValue(power),restored.shipStyle)
            assertEquals(if(power==2)0 else 3,c.origin);assertEquals(1000001,c.face)
        }
        assertFails {CharacterCreation(shipStyles=swapped,starterKinds=mapOf(2 to listOf(0),3 to listOf(89)))}
    }
    @Test fun originalLotteryAndDefectionUseTheDestinationStyleAndSurviveRestart() {
        val file=dir.resolve("originals.properties")
        val start=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC)
        val candidate=OriginalCharacterMessages.localCandidates.first {it.character.power==2}
        val store=CharacterCreation(file,clock=start,lotteryWindowMillis=10,originals=listOf(candidate),shipStyles=swapped,
            accountFame=mapOf("pilot" to candidate.minimumFame))
        store.apply("pilot",listOf(candidate.character.id))
        val later=CharacterCreation(file,clock=Clock.offset(start,java.time.Duration.ofMillis(10)),originals=listOf(candidate),shipStyles=swapped)
        val c=assertNotNull(later.character("pilot"))
        assertFalse(c.generated);assertEquals(2,c.power)
        assertEquals(Flagships.forType(3,c.shipType).kind,c.shipKind)
        assertEquals(ShipStyleId("alliance"),CharacterCreation(file).profiles().getValue("pilot").shipStyle)
        assertTrue(later.defect("pilot",c.id,3))
        val defected=CharacterCreation(file).profiles().getValue("pilot")
        assertEquals(CountryId(3),defected.country);assertEquals(ShipStyleId("empire"),defected.shipStyle)
        assertEquals(Flagships.forType(2,c.shipType).kind,defected.character.shipKind)
    }
}
