package org.logh7.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class RuntimeEnvironmentTest {
    @TempDir lateinit var directory:Path
    @Test fun `process overrides local secrets which override shared settings without expansion`() {
        Files.writeString(directory.resolve(".env"),"LOGH7_LLM_MODEL=shared\nKEY=shared\nEMPTY=shared\n")
        Files.writeString(directory.resolve(".env.local"),"KEY='fixture-secret'\nEMPTY=local\nLITERAL=\"\${UNCHANGED}\"\n")
        val env=RuntimeEnvironment.load(mapOf("LOGH7_LLM_MODEL" to "chosen","EMPTY" to ""),directory)
        assertEquals("chosen",env["LOGH7_LLM_MODEL"])
        assertEquals("fixture-secret",env["KEY"])
        assertEquals("",env["EMPTY"])
        assertEquals("\${UNCHANGED}",env["LITERAL"])
        assertFalse(env.toString().contains("fixture-secret"))
    }
    @Test fun `invalid and duplicate assignments do not expose values in errors`() {
        for(content in listOf("KEY=fixture-secret\nKEY=second-secret", "KEY='fixture-secret", "not an assignment fixture-secret")) {
            Files.writeString(directory.resolve(".env"),content)
            val error=assertFailsWith<IllegalArgumentException> {RuntimeEnvironment.load(emptyMap(),directory)}
            assertFalse(error.message.orEmpty().contains("fixture-secret"))
            assertFalse(error.message.orEmpty().contains("second-secret"))
        }
    }
    @Test fun `server directory and explicit file use the matching local override`() {
        Files.createDirectories(directory.resolve("server"));Files.writeString(directory.resolve("AGENTS.md"),"")
        Files.writeString(directory.resolve(".env.local"),"KEY=local\n")
        assertEquals("local",RuntimeEnvironment.load(emptyMap(),directory.resolve("server"))["KEY"])
        val selected=directory.resolve("qa.env")
        Files.writeString(selected,"KEY=qa\n");Files.writeString(directory.resolve("qa.env.local"),"KEY=qa-local\n")
        assertEquals("qa-local",RuntimeEnvironment.load(mapOf("LOGH7_ENV_FILE" to selected.toString()),directory)["KEY"])
    }
}
