package org.logh7.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class SessionRuntimeConfigurationTest {
    @TempDir lateinit var directory:Path
    private fun entries(secondRoot:String="beta",secondPort:Int=48210)=listOf(
        "session.1.name=Alpha","session.1.stateDirectory=alpha","session.1.gamePort=48200",
        "session.1.updatePort=48202","session.1.sessionPort=48203","session.1.opsPort=48201",
        "session.2.name=Beta","session.2.stateDirectory=$secondRoot","session.2.gamePort=$secondPort",
        "session.2.updatePort=48212","session.2.sessionPort=48213","session.2.opsPort=48211").joinToString("\n")
    private fun environment(content:String):RuntimeEnvironment {
        Files.writeString(directory.resolve("sessions.properties"),content)
        Files.writeString(directory.resolve("galaxy.csv"),"")
        return RuntimeEnvironment.load(mapOf("LOGH7_SESSIONS_FILE" to directory.resolve("sessions.properties").toString(),
            "LOGH7_WORLD_FILE" to directory.resolve("galaxy.csv").toString(),"LOGH7_CHARACTERS_FILE" to "legacy-file",
            "LOGH7_OPS_AUDIT_FILE" to "legacy-audit","LOGH7_BACKUP_DIR" to "legacy-backups"),directory)
    }
    @Test fun `session state files and backup directories are isolated before opening runtimes`() {
        val specs=SessionRuntimeConfiguration.load(environment(entries()))
        assertEquals(listOf(1,2),specs.map {it.id});assertEquals(listOf("Alpha","Beta"),specs.map {it.name})
        for((index,spec) in specs.withIndex()) {
            val root=directory.resolve(if(index==0)"alpha" else "beta")
            assertEquals(root.resolve("characters.properties").toString(),spec.environment["LOGH7_CHARACTERS_FILE"])
            assertEquals(root.resolve("ops-audit.jsonl").toString(),spec.environment["LOGH7_OPS_AUDIT_FILE"])
            assertEquals(root.resolve("backups").toString(),spec.environment["LOGH7_BACKUP_DIR"])
            assertNull(spec.environment["LOGH7_CAPTURE_DIR"])
            assertFalse(Files.exists(root),"Validation does not open or create state")
        }
    }
    @Test fun `reject shared ports overlapping state names duplicate and unknown configuration`() {
        for(content in listOf(entries(secondPort=48200),entries(secondRoot="alpha"),entries(secondRoot="alpha/child"),
            entries()+"\nsession.2.name=Again",entries()+"\nsession.2.typo=x",entries().replace("Beta","A".repeat(13)))) {
            assertFailsWith<IllegalArgumentException> {SessionRuntimeConfiguration.load(environment(content))}
            assertFalse(Files.exists(directory.resolve("alpha")))
        }
    }
    @Test fun `shared input files cannot be overwritten by generated state paths`() {
        val base=environment(entries()).overlay(mapOf("LOGH7_ACCOUNTS_FILE" to directory.resolve("beta/characters.properties").toString()))
        assertFailsWith<IllegalArgumentException> {SessionRuntimeConfiguration.load(base)}
    }
    @Test fun `default configuration preserves single session environment`() {
        val base=RuntimeEnvironment.load(emptyMap(),directory)
        val only=SessionRuntimeConfiguration.load(base).single()
        assertEquals(1,only.id);assertSame(base,only.environment)
    }
}
