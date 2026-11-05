package org.logh7.persistence

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class FileWorldIdentityTest {
    @TempDir lateinit var directory:Path
    @Test fun identitySurvivesReopenAndRestoreAndNewWorldsHaveDifferentIds() {
        val a=Files.createDirectory(directory.resolve("a"));val b=Files.createDirectory(directory.resolve("b"))
        val sync=DirectorySync.FILE_CONTENTS_ONLY
        val identity=FileWorldIdentity.loadOrCreate(a,sync)
        assertEquals(identity,FileWorldIdentity.loadOrCreate(a,sync))
        assertNotEquals(identity,FileWorldIdentity.loadOrCreate(b,sync))
        val restored=Files.createDirectory(directory.resolve("restored"))
        Files.copy(a.resolve("world.identity"),restored.resolve("world.identity"))
        assertEquals(identity,FileWorldIdentity.loadOrCreate(restored,sync))
    }
    @Test fun damagedIdentityBlocksBootAndIsNeverSilentlyRegenerated() {
        val file=directory.resolve("world.identity")
        for(value in listOf("","invalid", "00000000-0000-0000-0000-000000000000X")) {
            Files.writeString(file,value)
            assertFails {FileWorldIdentity.loadOrCreate(directory,DirectorySync.FILE_CONTENTS_ONLY)}
            assertEquals(value,Files.readString(file))
        }
    }
}
