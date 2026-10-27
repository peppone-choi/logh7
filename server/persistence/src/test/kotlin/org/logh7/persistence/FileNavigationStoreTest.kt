package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class FileNavigationStoreTest {
    @Test fun pendingRetreatReopensWithoutResettingItsDeadlineAndOldNineHasNoRetreat() {
        val path=directory.resolve("retreat")
        val pose=TacticalPose(TacticalPoint(-300f,0f),1f)
        val player=Navigator(1,10000,Power.ALLIANCE,2017,1000,960,900,cpPaused=true,
            energy=TacticalEnergy(listOf(100,50)+List(9){0}),
            motion=TacticalMotion(2017,100,pose,0f,1f,listOf(pose.position)),
            retreat=TacticalRetreat(2016,120,120,3600))
        FileNavigationStore(path).use {it.save(mapOf("pilot" to player))}
        val lines=java.nio.file.Files.readAllLines(path)
        assertEquals("LOGH7-NAV-10",lines.first())
        FileNavigationStore(path).use {assertEquals(player,it.load().getValue("pilot"))}
        java.nio.file.Files.writeString(path,"LOGH7-NAV-9\n"+lines[1].substringBeforeLast('\t')+"\n")
        FileNavigationStore(path).use {assertNull(it.load().getValue("pilot").retreat)}
    }
    @Test fun motionKeepsItsScheduleAndPathAcrossReopenAndLegacyEightHasNoMotion() {
        val path=directory.resolve("motion")
        val motion=TacticalMotion(2017,100,TacticalPose(TacticalPoint(-700f,-180f),0f),0.5f,1f,
            listOf(TacticalPoint(-600f,-180f),TacticalPoint(-600f,0f)))
        val player=Navigator(1,10000,Power.ALLIANCE,2017,1000,960,900,cpPaused=true,motion=motion,
            energy=TacticalEnergy(listOf(50)+List(10){0}))
        FileNavigationStore(path).use {it.save(mapOf("pilot" to player,"peer" to player.copy(characterId=10001,motion=null)))}
        val lines=java.nio.file.Files.readAllLines(path)
        assertEquals("LOGH7-NAV-9",lines.first())
        FileNavigationStore(path).use {
            val saved=it.load();assertEquals(player,saved.getValue("pilot"));assertNull(saved.getValue("peer").motion)
            assertEquals(motion.pose(120),saved.getValue("pilot").motion!!.pose(120))
        }
        java.nio.file.Files.writeString(path,"LOGH7-NAV-8\n"+lines.drop(1).joinToString("\n"){it.substringBeforeLast('\t')}+"\n")
        FileNavigationStore(path).use {assertNull(it.load().getValue("pilot").motion)}
        val encoded=TacticalMotionCodec.encode(motion)
        assertEquals(motion,TacticalMotionCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException>{TacticalMotionCodec.decode(encoded+"A")}
        assertFailsWith<IllegalArgumentException>{TacticalMotionCodec.decode("!".repeat(60))}
    }
    @Test fun tacticalEnergySurvivesReopenAndSevenDefaultsToUnset() {
        val path=directory.resolve("energy")
        val player=Navigator(1,10000,Power.ALLIANCE,2017,1000,960,900,cpPaused=true,
            energy=TacticalEnergy(listOf(40,0,20,60,20)+List(6){20}))
        FileNavigationStore(path).use {it.save(mapOf("pilot" to player))}
        val lines=java.nio.file.Files.readAllLines(path)
        assertEquals("LOGH7-NAV-8",lines.first())
        FileNavigationStore(path).use {assertEquals(player,it.load().getValue("pilot"))}
        java.nio.file.Files.writeString(path,"LOGH7-NAV-7\n"+lines[1].substringBeforeLast('\t')+"\n")
        FileNavigationStore(path).use {assertEquals(player.copy(energy=null),it.load().getValue("pilot"))}
        java.nio.file.Files.writeString(path,lines.joinToString("\n").replace(",60,",",101,")+"\n")
        FileNavigationStore(path).use {assertFailsWith<IllegalArgumentException>{it.load()}}
    }
    @Test fun returnBaseSurvivesReopenAndSixDefaultsToUnset() {
        val path = directory.resolve("return-base")
        val player = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 900, returnBase = 1048)
        FileNavigationStore(path).use { it.save(mapOf("pilot" to player)) }
        val seven = java.nio.file.Files.readAllLines(path)
        assertEquals("LOGH7-NAV-8", seven.first())
        FileNavigationStore(path).use { assertEquals(player, it.load().getValue("pilot")) }
        java.nio.file.Files.writeString(path, "LOGH7-NAV-6\n" + seven[1].split('\t').take(18).joinToString("\t") + "\n")
        FileNavigationStore(path).use { assertEquals(player.copy(returnBase = 0), it.load().getValue("pilot")) }
        assertFails { player.copy(returnBase = 65536) }
    }
    @Test fun navigationSixKeepsPendingDockTargetSeparateFromCurrentBase() {
        val path = directory.resolve("dock-job")
        val payment = org.logh7.engine.strategy.PaymentResult.Paid(org.logh7.engine.strategy.CpBalance(1000, 840),
            org.logh7.engine.strategy.CpPool.MCP, 160, 160, false)
        val job = org.logh7.engine.strategy.StrategyJob(0, "operation/星系内航行", 1000, 28801000, 28801000,
            payment, org.logh7.engine.strategy.JobState.WAITING)
        val pending = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 840, 900,
            strategy = org.logh7.engine.strategy.StrategyCheckpoint(1, 1000, listOf(job)), dockTarget = 1049)
        FileNavigationStore(path).use { it.save(mapOf("pilot" to pending)) }
        val row = java.nio.file.Files.readAllLines(path)[1].split('\t').take(18).joinToString("\t")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-6\n$row\n")
        FileNavigationStore(path).use {
            val restored = it.load().getValue("pilot")
            assertEquals(pending, restored); assertEquals(0, restored.baseId)
            assertEquals(1049, restored.dockTarget); assertEquals(28801L, restored.dockReadyAt)
        }
        assertFails { pending.copy(dockTarget = null) }
        assertFails { pending.copy(fuelReadyAt = 28801) }
    }
    @Test fun boundedCheckpointCodecRoundTripsMaximumCountAndUtf16NameLength() {
        val payment = org.logh7.engine.strategy.PaymentResult.Paid(org.logh7.engine.strategy.CpBalance(0, 0), null, 0, 0, false)
        val jobs = List(128) { org.logh7.engine.strategy.StrategyJob(it.toLong(), "艦".repeat(256),
            0, 0, 0, payment, org.logh7.engine.strategy.JobState.WAITING) }
        val state = org.logh7.engine.strategy.StrategyCheckpoint(128, 0, jobs)
        val encoded = StrategyCheckpointCodec.encode(state)
        assertTrue(encoded.length > 100_000)
        assertEquals(state, StrategyCheckpointCodec.decode(encoded))
    }
    @Test fun pendingSharedWorkAndPaymentSurviveReopenButOldAcknowledgedDeadlinesStayUnchanged() {
        val path = directory.resolve("jobs")
        val payment = org.logh7.engine.strategy.PaymentResult.Paid(org.logh7.engine.strategy.CpBalance(680, 0),
            org.logh7.engine.strategy.CpPool.PCP, 320, 0, true)
        val job = org.logh7.engine.strategy.StrategyJob(7, "operation/燃料補給", 1000, 2000, 3000,
            payment, org.logh7.engine.strategy.JobState.WAITING)
        val p = Navigator(1, 10000, Power.EMPIRE, 2488, 680, 0, 900, fuelReadyAt = 3,
            strategy = org.logh7.engine.strategy.StrategyCheckpoint(8, 1000, listOf(job)))
        FileNavigationStore(path).use { it.save(mapOf("pilot" to p)) }
        FileNavigationStore(path).use { assertEquals(p, it.load().getValue("pilot")) }
        val navSix = java.nio.file.Files.readAllLines(path)
        java.nio.file.Files.writeString(path, "LOGH7-NAV-5\n" + navSix[1].split('\t').take(17).joinToString("\t") + "\n")
        FileNavigationStore(path).use { assertEquals(p, it.load().getValue("pilot")) }
        java.nio.file.Files.writeString(path, "LOGH7-NAV-4\npilot\t1\t10000\tEMPIRE\t2488\t680\t0\t900\t0\t0\t5\t0\t0\t100\t0\t3000\n")
        FileNavigationStore(path).use {
            val old = it.load().getValue("pilot")
            assertEquals(3000, old.fuelReadyAt); assertNull(old.strategy)
            it.save(mapOf("pilot" to old))
        }
        FileNavigationStore(path).use { assertEquals(3000, it.load().getValue("pilot").fuelReadyAt) }
        assertFails { StrategyCheckpointCodec.decode(StrategyCheckpointCodec.encode(p.strategy!!) + "AAAA") }
        assertFails { StrategyCheckpointCodec.decode("AA==") }
    }
    @TempDir lateinit var directory: Path
    @Test fun migratesOldRowsAndPersistsFacilityLocationAcrossReopen() {
        val path = directory.resolve("old-navigation")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-1\npilot\t1\t10000\tEMPIRE\t2487\t1000\t960\t900\t1\n")
        lateinit var landed: Navigator
        FileNavigationStore(path).use { store ->
            val old = store.load().getValue("pilot")
            assertEquals(0, old.baseId); assertEquals(5, old.mode)
            landed = old.copy(baseId = 1049, mode = 0, spotId = 10_490_003)
            store.save(mapOf("pilot" to landed))
        }
        assertEquals("LOGH7-NAV-8", java.nio.file.Files.readAllLines(path).first())
        FileNavigationStore(path).use { assertEquals(landed, it.load().getValue("pilot")) }
    }
    @Test fun replacementSurvivesReopenAndExcludesOtherWriters() {
        val path = directory.resolve("navigation")
        val player = Navigator(1, 10000, Power.EMPIRE, 2488, 1000, 960, 900, 1, cpRecoveryAt = 12345678, cpPaused = true,
            fuelReadyAt = 12691234)
        FileNavigationStore(path).use { store ->
            assertTrue(store.load().isEmpty())
            assertFails { FileNavigationStore(path) }
            store.save(mapOf("pilot@example" to player))
        }
        FileNavigationStore(path).use { assertEquals(mapOf("pilot@example" to player), it.load()) }
    }
    @Test fun versionTwoKeepsItsLocationAndBalancesWithoutInventingARecoveryTimestamp() {
        val path = directory.resolve("version-two")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-2\npilot\t1\t10000\tEMPIRE\t2488\t123\t456\t900\t1\t1049\t4\t10490003\t0\n")
        FileNavigationStore(path).use {
            val old = it.load().getValue("pilot")
            assertEquals(123, old.pcp); assertEquals(456, old.mcp); assertEquals(10490003, old.spotId)
            assertNull(old.cpRecoveryAt); assertFalse(old.cpPaused)
        }
    }
    @Test fun truncatedStoreIsRejectedRatherThanResetToCapital() {
        val path = directory.resolve("navigation")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-1\npilot\t1\t10000\tEMPIRE\t2488")
        FileNavigationStore(path).use { assertFails { it.load() } }
    }
    @Test fun versionThreeMigratesWithoutStartingARefill() {
        val path = directory.resolve("version-three")
        java.nio.file.Files.writeString(path, "LOGH7-NAV-3\npilot\t1\t10000\tEMPIRE\t2488\t123\t456\t900\t1\t0\t5\t0\t0\t12345678\t0\n")
        FileNavigationStore(path).use {
            val old = it.load().getValue("pilot")
            assertEquals(12345678, old.cpRecoveryAt); assertNull(old.fuelReadyAt)
            it.save(mapOf("pilot" to old))
        }
        FileNavigationStore(path).use { assertEquals(900, it.load().getValue("pilot").cruise) }
    }
}
