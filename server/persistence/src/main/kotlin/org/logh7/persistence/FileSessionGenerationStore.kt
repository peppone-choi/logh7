package org.logh7.persistence

import org.logh7.engine.SessionGenerationStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FileSessionGenerationStore(private val file: Path) : SessionGenerationStore {
    private var generation = if (Files.exists(file)) Files.readString(file).trim().toLong() else 1L
    init { require(generation > 0) }
    override fun current() = generation
    override fun advance(expected: Long): Long {
        check(expected == generation)
        val next = Math.addExact(generation, 1)
        val target = file.toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "session-generation-", ".tmp")
        try {
            Files.writeString(temporary, "$next\n")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        generation = next
        return next
    }
}
