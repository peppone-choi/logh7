package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class SharedFameLedgerTest {
    @TempDir lateinit var directory:Path
    private fun character()=CharacterMessages.Generate(4,10000,2,0,0,"Alpha","Pilot",18,1,1,1000001,
        List(8){50},0,0,0,8,3,0,"Ship",1)
    private fun characters()=CharacterCreation().also {assertTrue(it.carry("winner",character()))}
    private fun world(generation:Long=1):WorldSnapshot {
        val end=SessionEnd(EndReason.CAPITAL_CAPTURED,setOf(Power.ALLIANCE),123)
        val result=SessionResults.assess(end,SessionStatistics(4,2,100_000_000_001,1010,66),mapOf(
            Power.EMPIRE to SessionStatistics(2,1,90_000_009_999,1000,33),Power.ALLIANCE to SessionStatistics(2,1,9_999_990_002,10,33)),
            VictoryConditions(true,true))
        return WorldSnapshot(0,0,SessionSimulation(SessionRules.load()).snapshot().copy(generation=generation,ended=end),
            planetStocks=PlanetStocks(generation,0,emptyMap(),strategic=StrategicState(people=mapOf(10000 to StrategicPerson(evaluation=250)))),result=result)
    }
    @Test fun concurrentWorldsInTheSameGenerationGrantOnceAndKeepTheirOwnStatementsAcrossReboot() {
        val file=directory.resolve("fame.properties")
        val people=characters();val snapshot=world()
        FameLedger.openShared(file,false).use {root->
            val a=root.forWorld("alpha");val b=root.forWorld("beta")
            val cached=CharacterStores(null,b).get(1)
            val lottery=CharacterCreation(originals=listOf(OriginalCharacterMessages.Candidate(
                character().copy(id=1001,generated=false),minimumFame=10)),accountFame=b.accounts)
            assertEquals(0,cached.fame("winner"))
            assertTrue(lottery.candidates("winner").isEmpty())
            val pool=Executors.newFixedThreadPool(4)
            try {
                val jobs=(0..19).map {index->pool.submit<Map<String,Int>> {if(index%2==0)a.settle(snapshot,people) else b.settle(snapshot,people)}}
                assertEquals(2,jobs.map {it.get(10,TimeUnit.SECONDS)}.count {it.isNotEmpty()})
            } finally {pool.shutdownNow()}
            assertEquals(mapOf("winner" to 18),root.fame());assertEquals(18,cached.fame("winner"))
            assertEquals(1001L,lottery.candidates("winner").single().character.id)
            val statements=listOf(a.evaluation(snapshot,people,"winner")!!,b.evaluation(snapshot,people,"winner")!!)
            assertEquals(setOf(0L,9L),statements.map {it.previousFame}.toSet())
            assertEquals(setOf(9L,18L),statements.map {it.finalFame}.toSet())
            assertTrue(statements.all {it.previousEvaluation==250L && it.finalEvaluation==933L})
            assertNull(a.evaluation(snapshot,people,"stranger"))
            assertFails {FameLedger.openShared(file,false).close()}
        }
        FameLedger.openShared(file,false).use {root->
            assertEquals(18,root.fame().getValue("winner"))
            assertTrue(root.forWorld("alpha").settle(snapshot,people).isEmpty())
            assertTrue(root.forWorld("beta").settle(snapshot,people).isEmpty())
            assertEquals(mapOf("winner" to 9),root.forWorld("alpha").settle(world(2),people))
            assertEquals(27,root.fame().getValue("winner"))
        }
    }
    @Test fun legacyMigrationIsAtomicIdempotentAndDoesNotChangeOldFilesOrHistoricalStatements() {
        val old=directory.resolve("legacy.fame");val snapshot=world();val people=characters()
        FameLedger(old).settle(snapshot,people)
        val original=Files.readAllBytes(old)
        FameLedger.openShared(directory.resolve("common.fame"),false).use {root->
            val a=root.forWorld("alpha");a.importLegacy(old)
            assertEquals(9,root.fame().getValue("winner"))
            root.forWorld("beta").settle(snapshot,people)
            a.importLegacy(old)
            assertEquals(18,root.fame().getValue("winner"));assertTrue(a.settle(snapshot,people).isEmpty())
            assertEquals(SessionEndingMessages.Evaluation(250,933,0,9,0),a.evaluation(snapshot,people,"winner"))
            assertContentEquals(original,Files.readAllBytes(old))
        }
    }
    @Test fun legacyMissingStatementsUseCapturedLegacyTotalsAfterOtherWorldAndNewGenerationAwards() {
        val old=directory.resolve("legacy.fame");val snapshot=world();val people=characters()
        FameLedger(old).settle(snapshot,people)
        val values=Properties();Files.newBufferedReader(old).use {values.load(it)}
        values.remove("@evaluation/1/winner");Files.newBufferedWriter(old).use {values.store(it,null)}
        FameLedger.openShared(directory.resolve("common.fame"),false).use {root->
            val a=root.forWorld("alpha");a.importLegacy(old)
            root.forWorld("beta").settle(snapshot,people);a.settle(world(2),people)
            assertEquals(27,root.fame().getValue("winner"))
            assertEquals(SessionEndingMessages.Evaluation(250,933,0,9,0),a.evaluation(snapshot,people,"winner"))
        }
    }
    @Test fun badMigrationCannotPartiallyGrantOrMarkAWorldAsImported() {
        val old=directory.resolve("broken.fame");Files.writeString(old,"winner=9\n@unknown=1\n")
        val file=directory.resolve("common.fame")
        FameLedger.openShared(file,false).use {root->
            val before=Files.readAllBytes(file)
            assertFails {root.forWorld("alpha").importLegacy(old)}
            assertTrue(root.fame().isEmpty());assertContentEquals(before,Files.readAllBytes(file))
            FameLedger(old).settle(world(),characters())
            // Remove the deliberately invalid metadata; the failed import left no completion receipt.
            val valid=Properties();Files.newBufferedReader(old).use {valid.load(it)};valid.remove("@unknown")
            Files.newBufferedWriter(old).use {valid.store(it,null)}
            root.forWorld("alpha").importLegacy(old)
            assertEquals(18,root.fame().getValue("winner"))
        }
    }
    @Test fun damagedSharedLedgerBlocksBootAndReleasesItsWriterLockWithoutReplacingData() {
        val file=directory.resolve("broken-common.fame")
        for(content in listOf("","winner=9\n","@format=LOGH7-ACCOUNT-FAME-1\nwinner=-1\n")) {
            Files.writeString(file,content)
            repeat(2){assertFails {FameLedger.openShared(file,false).close()}}
            assertEquals(content,Files.readString(file))
        }
    }
    @Test fun missingLedgerCannotResetExistingWorldsOrAnAlreadyOpenBank() {
        val file=directory.resolve("required.fame")
        assertFails {FameLedger.openShared(file,false,true).close()}
        assertFalse(Files.exists(file))
        FameLedger.openShared(file,false).use {root->
            root.forWorld("alpha").settle(world(),characters())
            Files.delete(file)
            assertFails {root.fame()}
            assertFails {root.forWorld("beta").settle(world(),characters())}
            assertFalse(Files.exists(file),"A missing bank is not recreated with zero totals")
        }
    }
}
