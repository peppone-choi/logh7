package org.logh7.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*

/** Pin import sources to their selected store; never silently resume stale files. */
internal object WorldStateOwnership {
    private fun marker(path: Path) = path.resolveSibling("${path.fileName}.world-owner")
    fun check(path: Path, owner: Path?) {
        val file = marker(path)
        if (!Files.exists(file)) return
        require(Files.size(file) <= 4096) { "Invalid world ownership marker" }
        val fields = Files.readString(file).trimEnd('\n').split('\t')
        require(fields.size == 2 && fields[0] == "LOGH7-WORLD-OWNER-1" && owner != null &&
            Path.of(fields[1]).toAbsolutePath().normalize() == owner.toAbsolutePath().normalize()) {
            "Legacy file belongs to a world store; configure the matching LOGH7_WORLD_STATE_DIR"
        }
    }
    fun claim(path: Path, owner: Path) {
        check(path, owner)
        val file = marker(path)
        if (Files.exists(file)) return
        val value = owner.toAbsolutePath().normalize().toString()
        require(value.none { it == '\t' || it == '\r' || it == '\n' })
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".world-owner-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use {
                val bytes = ByteBuffer.wrap("LOGH7-WORLD-OWNER-1\t$value\n".toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) it.write(bytes)
                it.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }
}
