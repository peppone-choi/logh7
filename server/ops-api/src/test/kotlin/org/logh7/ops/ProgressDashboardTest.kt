package org.logh7.ops

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ProgressDashboardTest {
    @TempDir lateinit var directory:Path
    @Test fun `count documented scopes and nested items but ignore fenced examples`() {
        val parsed=ChecklistProgress.parse("## A\n- [x] done\n  - [ ] partial\n```md\n- [x] example\n```\n## B\n- [X] done\n- [ ] unknown\n")
        assertEquals(listOf(ProgressSection("A",1,2),ProgressSection("B",1,2)),parsed.sections)
        assertEquals(2,parsed.completed);assertEquals(4,parsed.total);assertEquals(50.0,parsed.percent)
    }
    @Test fun `invalid or unfinished documents never become zero percent progress`() {
        assertFailsWith<IllegalArgumentException>{ChecklistProgress.parse("# Empty")}
        assertFailsWith<IllegalArgumentException>{ChecklistProgress.parse("- [x] item\n```\nunfinished")}
    }
    @Test fun `JSON preserves source identity and escapes section names`() {
        val file=directory.resolve("checklist.md")
        Files.writeString(file,"## Quote \"title\"\n- [x] completed\n- [ ] partial\n")
        val json=progressJson(file)
        assertTrue(json.contains("\"completed\":1,\"total\":2,\"percent\":50.0"))
        assertTrue(json.contains("Quote \\\"title\\\""));assertTrue(json.contains("\"source\":\"checklist.md\""))
        assertTrue(json.contains("\"fingerprint\":"))
    }
}
