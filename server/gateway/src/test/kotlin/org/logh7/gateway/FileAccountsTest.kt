package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileAccountsTest {
    @TempDir lateinit var directory: Path

    @Test fun editsApplyToTheLiveMapAndSurviveAReload() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "multi0=dummy\n")
        val accounts = FileAccounts(file)
        assertEquals("dummy", accounts["multi0"])
        accounts.set("pilot.1", "s3cret")
        assertEquals(listOf("multi0", "pilot.1"), accounts.names())
        val stored = checkNotNull(FileAccounts(file)["pilot.1"]) { "written to the file, not only memory" }
        assertTrue(AccountPassword.isHashed(stored) && "s3cret" !in Files.readString(file))
        assertTrue(AccountPassword.matches(stored, "s3cret"));assertFalse(AccountPassword.matches(stored, "s3creT"))
        assertTrue(AccountPassword.matches("dummy", "dummy"), "plain test entries keep working")
        assertTrue(accounts.remove("multi0"));assertFalse(accounts.remove("multi0"))
        assertNull(accounts["multi0"])
        assertFalse(Files.exists(directory.resolve("accounts.properties.tmp")))
        Files.writeString(file, "outside=edit\n")
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
        assertEquals(listOf("outside"), accounts.names(), "an outside edit is picked up on the next read")
    }

    @Test fun loginRulesAreEnforced() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "")
        val accounts = FileAccounts(file)
        for ((name, password) in listOf("" to "x", "@admin" to "x", "a".repeat(31) to "x", "ok" to "", "ok" to "elevenchars", "ok" to "sp ace", "a=b" to "x"))
            assertFailsWith<IllegalArgumentException>("$name/$password") { accounts.set(name, password) }
        assertTrue(accounts.isEmpty())
    }

    @Test fun registrationNeverReplacesLiveOrRemovedAccounts() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "existing=original\n")
        val accounts = FileAccounts(file, reserveRemovedNames = true)
        assertFalse(accounts.create("existing", "other"))
        assertEquals("original", accounts["existing"])

        java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            val attempts = listOf("first", "second").associateWith { password ->
                pool.submit<Boolean> { accounts.create("new", password) }
            }
            val winner = attempts.entries.single { it.value.get() }.key
            assertTrue(AccountPassword.matches(checkNotNull(accounts["new"]), winner))
        }
        assertTrue(accounts.remove("new"))
        val reloaded = FileAccounts(file)
        assertNull(reloaded["new"])
        assertEquals(listOf("existing"), reloaded.names())
        assertFalse(reloaded.create("new", "newpass"))
        reloaded.set("new", "reset")
        assertTrue(AccountPassword.matches(checkNotNull(reloaded["new"]), "reset"))
    }
}
