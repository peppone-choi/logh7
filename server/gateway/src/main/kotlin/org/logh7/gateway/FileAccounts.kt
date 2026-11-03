package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The local account file as a live map (2026-11-03 account management, file storage chosen as the default):
 * a login reads the current file, so accounts added, reset or removed by the operator page apply without a
 * restart. Writes go to a sibling temp file and replace the original atomically. Passwords are never listed.
 * Author: Peppone Choi.
 */
class FileAccounts(private val path: Path) : AbstractMap<String, String>() {
    private var loadedAt: java.nio.file.attribute.FileTime? = null
    private var current: Map<String, String> = emptyMap()

    companion object {
        /** Native login strings: account 1..30 (no leading @), password 1..10; no separator or control characters. */
        private val name = Regex("[A-Za-z0-9._-]{1,30}")
        private val secret = Regex("[!-~]{1,10}")
        fun validName(account: String) = name.matches(account)
        fun validPassword(password: String) = secret.matches(password)
    }

    @Synchronized private fun fresh(): Map<String, String> {
        val modified = Files.getLastModifiedTime(path)
        if (modified != loadedAt) { current = loadTestAccounts(path); loadedAt = modified }
        return current
    }
    override val entries: Set<Map.Entry<String, String>> get() = fresh().entries
    override fun get(key: String): String? = fresh()[key]
    override fun containsKey(key: String): Boolean = fresh().containsKey(key)

    @Synchronized fun names(): List<String> = fresh().keys.sorted()

    @Synchronized fun set(account: String, password: String) {
        require(validName(account) && validPassword(password)) { "invalid account or password" }
        write(fresh() + (account to password))
    }

    @Synchronized fun remove(account: String): Boolean {
        val accounts = fresh()
        if (account !in accounts) return false
        write(accounts - account)
        return true
    }

    private fun write(accounts: Map<String, String>) {
        val properties = Properties().apply { accounts.forEach { (k, v) -> setProperty(k, v) } }
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temp).use { properties.store(it, "LOGH7 local accounts") }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        loadedAt = null
    }
}
