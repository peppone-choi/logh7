package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileWorldStateStoreTest {
    @TempDir lateinit var directory: Path
    private val pilot = Navigator(1, 10000, Power.EMPIRE, 2487, 1000, 1000, 1000)
    private val inventory = PlanetStocks(1, 0, mapOf(1049 to PlanetStock(1049, 2, 0, 100, 200, 300)))

    @Test fun pairedPaymentAndEffectSurviveReopenWithoutReplaying() {
        val root = directory.resolve("world")
        val paid = pilot.copy(mcp = 840)
        val spent = inventory.copy(bases = mapOf(1049 to inventory.bases.getValue(1049).copy(supplies = 50)))
        FileWorldStateStore(root).use {
            it.saveWorld(mapOf("pilot" to pilot), inventory)
            it.saveWorld(mapOf("pilot" to paid), spent)
        }
        repeat(2) { FileWorldStateStore(root).use {
            assertEquals(paid, it.loadNavigation()["pilot"]); assertEquals(spent, it.loadStocks())
        } }
    }
    @Test fun failureAfterStagingKeepsOldPaymentAndEffectAndFencesWriter() {
        val root = directory.resolve("world")
        FileWorldStateStore(root).use {
            it.saveWorld(mapOf("pilot" to pilot), inventory)
            it.beforeSwitch = { throw IOException("injected commit failure") }
            assertFailsWith<IOException> { it.saveWorld(mapOf("pilot" to pilot.copy(mcp = 840)), inventory.copy(producedDays = 1)) }
            assertEquals(pilot, it.loadNavigation()["pilot"]); assertEquals(inventory, it.loadStocks())
            assertFailsWith<IllegalStateException> { it.saveWorld(emptyMap(), null) }
        }
        FileWorldStateStore(root).use {
            assertEquals(pilot, it.loadNavigation()["pilot"]); assertEquals(inventory, it.loadStocks())
            it.saveWorld(mapOf("pilot" to pilot.copy(mcp = 840)), inventory.copy(producedDays = 1))
        }
        FileWorldStateStore(root).use { assertEquals(840, it.loadNavigation().getValue("pilot").mcp); assertEquals(1, it.loadStocks()!!.producedDays) }
    }
    @Test fun legacyImportKeepsSourceBytesAndAdaptersKeepTheOtherHalf() {
        val nav = directory.resolve("old-nav"); val stock = directory.resolve("old-stock"); val root = directory.resolve("world")
        FileNavigationStore(nav).use { it.save(mapOf("pilot" to pilot)) }
        FilePlanetStockStore(stock).use { it.save(inventory) }
        val navBytes = Files.readAllBytes(nav); val stockBytes = Files.readAllBytes(stock)
        FileWorldStateStore(root, nav, stock).use {
            assertEquals(pilot, it.navigationStore.load()["pilot"])
            assertFails { FileNavigationStore(nav).close() }; assertFails { FilePlanetStockStore(stock).close() }
            it.navigationStore.save(mapOf("pilot" to pilot.copy(mcp = 840)))
            it.stockStore.save(inventory.copy(producedDays = 2))
        }
        assertContentEquals(navBytes, Files.readAllBytes(nav)); assertContentEquals(stockBytes, Files.readAllBytes(stock))
        FileWorldStateStore(root, nav, stock).use {
            assertEquals(840, it.navigationStore.load().getValue("pilot").mcp); assertEquals(2, it.stockStore.load()!!.producedDays)
        }
    }
    @Test fun missingOrCorruptCommittedHalfNeverFallsBackToLegacyOrInactiveSlot() {
        val root = directory.resolve("world")
        FileWorldStateStore(root).use { it.saveWorld(mapOf("pilot" to pilot), inventory) }
        Files.writeString(root.resolve("CURRENT"), "LOGH7-WORLD-1\t0\t0\n")
        assertFails { FileWorldStateStore(root).close() }
        Files.writeString(root.resolve("CURRENT"), "LOGH7-WORLD-1\t0\t1\n")
        Files.delete(root.resolve("slot-0/stocks.tsv"))
        assertFails { FileWorldStateStore(root).close() }
        Files.delete(root.resolve("CURRENT"))
        assertFails { FileWorldStateStore(root).close() }
    }
    @Test fun oneWriterAndColdEmptyStockAreSupported() {
        val root = directory.resolve("world")
        FileWorldStateStore(root).use {
            assertFails { FileWorldStateStore(root).close() }
            it.navigationStore.save(mapOf("pilot" to pilot))
            assertNull(it.stockStore.load())
        }
        FileWorldStateStore(root).use {
            assertEquals(pilot, it.navigationStore.load()["pilot"]); assertNull(it.loadStocks())
            it.stockStore.save(inventory)
        }
        FileWorldStateStore(root).use { assertEquals(inventory, it.loadStocks()) }
    }

    @Test fun abruptProcessExitBeforePointerKeepsBothOldHalves() = abruptExit(false)
    @Test fun abruptProcessExitAfterCommitKeepsBothNewHalves() = abruptExit(true)
    private fun abruptExit(committed: Boolean) {
        val root = directory.resolve("world")
        FileWorldStateStore(root).use { it.saveWorld(mapOf("pilot" to pilot), inventory) }
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx96m", "-cp",
            checkNotNull(System.getProperty("logh7.test.runtimeClasspath")), WorldStateCrashProcess::class.java.name,
            root.toString(), committed.toString()).redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(19, child.exitValue(), child.inputStream.bufferedReader().readText())
        } finally {
            if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) }
        }
        FileWorldStateStore(root).use {
            assertEquals(if (committed) 840 else 1000, it.loadNavigation().getValue("pilot").mcp)
            assertEquals(if (committed) 50 else 100, it.loadStocks()!!.bases.getValue(1049).supplies)
        }
    }
}

/** Synthetic JVM only: exit without running finally blocks or closing stores. */
object WorldStateCrashProcess {
    @JvmStatic fun main(args: Array<String>) {
        val store = FileWorldStateStore(Path.of(args[0]))
        if (args[1] == "false") store.beforeSwitch = { Runtime.getRuntime().halt(19) }
        val players = store.loadNavigation().mapValues { (_, player) -> player.copy(mcp = 840) }
        val stocks = store.loadStocks()!!
        store.saveWorld(players, stocks.copy(bases = stocks.bases.mapValues { (_, base) -> base.copy(supplies = 50) }))
        Runtime.getRuntime().halt(19)
    }
}
