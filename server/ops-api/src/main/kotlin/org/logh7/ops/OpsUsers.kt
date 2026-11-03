package org.logh7.ops

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Per-operator logins for the ops API (2026-11-03): a properties file of `name=pbkdf2-sha256:<iterations>:<salt>:<hash>`
 * (tools/ops/ops_user.py writes entries). The authenticated name becomes the audit operator, replacing the
 * self-reported header/form value. Re-read when the file changes. Author: Peppone Choi.
 */
class OpsUsers(private val path: Path) {
    private var loadedAt: java.nio.file.attribute.FileTime? = null
    private var users: Map<String, String> = emptyMap()

    @Synchronized private fun fresh(): Map<String, String> {
        val modified = Files.getLastModifiedTime(path)
        if (modified != loadedAt) {
            val properties = Properties().also { p -> Files.newBufferedReader(path).use(p::load) }
            users = properties.stringPropertyNames().associateWith { properties.getProperty(it) }.also { rows ->
                require(rows.all { (name, hash) -> OpsAudit.operatorPattern.matches(name) && hash.startsWith("pbkdf2-sha256:") }) {
                    "ops users must be operator names with pbkdf2-sha256 hashes"
                }
            }
            loadedAt = modified
        }
        return users
    }

    /** The operator name when [name]/[password] match an entry, else null. */
    fun authenticate(name: String, password: String): String? {
        val stored = fresh()[name] ?: return null
        val parts = stored.split(':')
        if (parts.size != 4) return null
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return null
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return null
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return null
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        val actual = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return name.takeIf { MessageDigest.isEqual(expected, actual) }
    }
}
