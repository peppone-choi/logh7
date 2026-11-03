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
        assertEquals("s3cret", FileAccounts(file)["pilot.1"], "written to the file, not only memory")
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
}
