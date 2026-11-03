package org.logh7.gateway

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stored account passwords: `pbkdf2-sha256:<iterations>:<salt>:<hash>` (Base64), written by the operator page.
 * Older plain entries in a hand-made test file still compare as before. Iterations follow the OWASP 2023
 * PBKDF2-HMAC-SHA256 guidance (600,000). Author: Peppone Choi.
 */
object AccountPassword {
    private const val PREFIX = "pbkdf2-sha256"
    const val ITERATIONS = 600_000
    private val random = SecureRandom()

    fun hash(password: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val encoder = Base64.getEncoder()
        return "$PREFIX:$iterations:${encoder.encodeToString(salt)}:${encoder.encodeToString(derive(password, salt, iterations))}"
    }

    fun isHashed(stored: String) = stored.startsWith("$PREFIX:")

    fun matches(stored: String, presented: String): Boolean {
        if (!isHashed(stored)) return MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
        val parts = stored.split(':')
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(presented, salt, iterations))
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
