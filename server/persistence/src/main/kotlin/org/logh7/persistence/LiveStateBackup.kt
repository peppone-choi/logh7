package org.logh7.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.name

/**
 * Backups of the running server's file state (docs/ops/backup.md), in tools/ops/state_backup.py's
 * `LOGH7-STATE-BACKUP-1` layout so its verify/restore/export/prune work on them. [copy] must run while the
 * world actor holds every commit (the app runs it as an engine Quiesce command); every store replaces its
 * files atomically or appends to a journal the actor alone writes, so the copy equals a crash at that
 * point between commits. Hashing and the manifest happen after the actor resumes; the archive gets its
 * final name only when complete. Author: Peppone Choi.
 */
class LiveStateBackup(characters: Path, world: Path, session: Path, private val root: Path, private val keep: Int) {
    private val characters = characters.toAbsolutePath().normalize()
    private val world = world.toAbsolutePath().normalize()
    private val session = session.toAbsolutePath().normalize()
    init { require(keep >= 1) { "backup keep must be at least 1" } }

    class Pending internal constructor(val partial: Path, val target: Path, val created: String)

    private fun files(): Map<String, List<Pair<Path, String>>> {
        fun tree(directory: Path) = Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && !it.name.endsWith(".lock") }
                .map { it to directory.relativize(it).toString().replace('\\', '/') }.toList().sortedBy { it.second }
        }
        val siblings = Files.list(characters.parent).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.name.startsWith("${characters.name}.") && !it.name.endsWith(".lock") }.toList()
        }.sortedBy { it.name }
        return linkedMapOf("characters" to (listOf(characters) + siblings).map { it to it.name },
            "world" to tree(world), "session" to tree(session))
    }

    /** Copies the stores into a `.partial` directory; call only while the world actor is quiesced. */
    fun copy(now: LocalDateTime = LocalDateTime.now()): Pending {
        Files.createDirectories(root)
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = root.resolve("state-$stamp")
        require(!Files.exists(target)) { "backup target already exists: $target" }
        val partial = root.resolve("state-$stamp.partial")
        if (Files.exists(partial)) partial.toFile().deleteRecursively()
        for ((group, entries) in files()) for ((source, name) in entries) {
            val copy = partial.resolve(group).resolve(name)
            Files.createDirectories(copy.parent)
            // A store may swap a file in by atomic rename on Windows between listing and copying; retry briefly.
            var attempt = 0
            while (true) {
                try { Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES); break }
                catch (failure: java.nio.file.NoSuchFileException) { break }
                catch (failure: java.io.IOException) { if (++attempt >= 5) throw failure; Thread.sleep(20) }
            }
        }
        val created = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + ".%09d".format(now.nano)
        return Pending(partial, target, created)
    }

    /** Hashes the copy, writes the manifest, renames it final and prunes older live backups. */
    fun finish(pending: Pending): Path {
        fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val rows = mutableListOf<String>()
        for (group in listOf("characters", "world", "session")) {
            val base = pending.partial.resolve(group)
            if (!Files.isDirectory(base)) continue
            Files.walk(base).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
                .map { it to base.relativize(it).toString().replace('\\', '/') }.sortedBy { it.second }
                .forEach { (file, name) ->
                    rows += "  {\"group\": ${json(group)}, \"name\": ${json(name)}, \"size\": ${Files.size(file)}, \"sha256\": ${json(sha256(file))}}"
                }
        }
        val manifest = buildString {
            append("{\n \"format\": \"LOGH7-STATE-BACKUP-1\",\n \"created\": ").append(json(pending.created)).append(",\n")
            append(" \"roots\": {\"characters\": ").append(json(characters.parent.toString())).append(", \"world\": ")
                .append(json(world.toString())).append(", \"session\": ").append(json(session.toString())).append("},\n")
            append(" \"characters\": ").append(json(characters.name)).append(",\n \"live\": true,\n \"files\": [\n")
            append(rows.joinToString(",\n")).append("\n ]\n}\n")
        }
        Files.writeString(pending.partial.resolve("manifest.json"), manifest)
        Files.move(pending.partial, pending.target, StandardCopyOption.ATOMIC_MOVE)
        prune()
        return pending.target
    }

    private fun prune() {
        val finished = Files.list(root).use { paths ->
            paths.filter { Files.isDirectory(it) && Regex("state-\\d{8}-\\d{6}").matches(it.name) && Files.exists(it.resolve("manifest.json")) }.toList()
        }.sortedByDescending { it.name }
        finished.drop(keep).forEach { it.toFile().deleteRecursively() }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
