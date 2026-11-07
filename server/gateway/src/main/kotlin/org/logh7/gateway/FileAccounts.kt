package org.logh7.gateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The local account file as a live map (2026-11-03 account management, file storage chosen as the default):
 * a login reads the current file, so accounts added, reset or removed by the operator page apply without a
 * restart. Writes go to a sibling temp file and replace the original atomically. Passwords are stored hashed
 * ([AccountPassword]) and never listed.
 * Author: Peppone Choi.
 */
class FileAccounts(private val path: Path, private val reserveRemovedNames: Boolean = false) : AbstractMap<String, String>() {
    private var loadedAt: java.nio.file.attribute.FileTime? = null
    private var current: Map<String, String> = emptyMap()

    companion object {
        // Older readers accept this hash prefix and reject the deliberately incomplete hash.
        private const val REMOVED = "pbkdf2-sha256:removed"
        // Keep missing and reserved accounts on the same verification path.
        private const val UNAVAILABLE_PASSWORD =
            "pbkdf2-sha256:600000:AAAAAAAAAAAAAAAAAAAAAA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

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
    override val entries: Set<Map.Entry<String, String>> get() = fresh().filterValues { it != REMOVED }.entries
    override fun get(key: String): String? = fresh()[key]?.takeUnless { it == REMOVED }
    override fun containsKey(key: String): Boolean = get(key) != null

    @Synchronized fun names(): List<String> = keys.sorted()

    @Synchronized fun set(account: String, password: String) {
        require(validName(account) && validPassword(password)) { "invalid account or password" }
        write(fresh() + (account to AccountPassword.hash(password)))
    }

    @Synchronized fun create(account: String, password: String): Boolean {
        require(validName(account) && validPassword(password)) { "invalid account or password" }
        val accounts = fresh()
        if (account in accounts) return false
        write(accounts + (account to AccountPassword.hash(password)))
        return true
    }

    @Synchronized fun changePassword(account: String, currentPassword: String, newPassword: String): Boolean {
        require(validName(account) && validPassword(currentPassword) && validPassword(newPassword)) { "invalid account or password" }
        val accounts = fresh()
        val stored = accounts[account]?.takeUnless { it == REMOVED }
        if (stored == null || !AccountPassword.isHashed(stored)) {
            AccountPassword.matches(UNAVAILABLE_PASSWORD, currentPassword)
        }
        val matches = stored != null && AccountPassword.matches(stored, currentPassword)
        if (!matches) return false
        write(accounts + (account to AccountPassword.hash(newPassword)))
        return true
    }

    @Synchronized fun remove(account: String): Boolean {
        val accounts = fresh()
        val stored = accounts[account] ?: return false
        if (stored == REMOVED) return false
        write(if (reserveRemovedNames) accounts + (account to REMOVED) else accounts - account)
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
