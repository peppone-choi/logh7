package org.logh7.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.gateway.AccountPassword
import org.logh7.gateway.FileAccounts
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerRegistrationTest {
    @TempDir lateinit var directory: Path

    @Test fun portalCreatesOnlyNewAccountsThroughTheBoundedRegistrationRoute() {
        val path = directory.resolve("accounts.properties")
        Files.writeString(path, "existing=original\n")
        val accounts = FileAccounts(path)
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        startPlayerRegistration(port, accounts).use {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
                fun post(body: String, route: String = "/register", origin: String? = null, portal: Boolean = true): Pair<Int, String> {
                    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$route"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                    if (portal) builder.header("X-Logh7-Portal", "1")
                    if (origin != null) builder.header("Origin", origin)
                    val response = client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString())
                    return response.statusCode() to response.body()
                }
                val valid = "account=pilot.1&password=A%2B%26%3D%25"
                assertEquals(403 to "forbidden", post(valid, portal = false))
                assertEquals(403 to "forbidden", post(valid, origin = "http://example.test"))
                assertEquals(404 to "not_found", post(valid, route = "/register/admin/account/set"))
                assertEquals(413 to "too_large", post("x".repeat(513)))
                assertEquals(400 to "invalid_request", post("account=x&account=y"))
                assertEquals(422 to "invalid_account", post("account=%40ops&password=abc"))
                assertEquals(422 to "invalid_password", post("account=pilot.1&password=too+long"))
                assertEquals(409 to "duplicate", post("account=existing&password=other"))
                assertEquals(201 to "created", post(valid))
                assertEquals(409 to "duplicate", post("account=pilot.1&password=changed"))
            }
        }
        assertEquals("original", accounts["existing"])
        val stored = checkNotNull(FileAccounts(path)["pilot.1"])
        assertTrue(AccountPassword.isHashed(stored))
        assertTrue(AccountPassword.matches(stored, "A+&=%"))
        assertFalse(AccountPassword.matches(stored, "changed"))
    }

    @Test fun portalVerifiesCurrentPasswordAndLimitsRetriesWithoutRevivingDeletedAccounts() {
        val path = directory.resolve("accounts.properties")
        Files.writeString(path, "pilot=original\n")
        val accounts = FileAccounts(path, reserveRemovedNames = true)
        accounts.set("deleted", "original")
        assertTrue(accounts.remove("deleted"))
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val elapsed = java.util.concurrent.atomic.AtomicLong()
        startPlayerRegistration(port, accounts, elapsed::get).use {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().use { client ->
                fun change(account: String, current: String, next: String = "changed", origin: String? = null): HttpResponse<String> {
                    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/password"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("X-Logh7-Portal", "1")
                    if (origin != null) request.header("Origin", origin)
                    return client.send(request.POST(HttpRequest.BodyPublishers.ofString(
                        "account=$account&currentPassword=$current&newPassword=$next",
                    )).build(), HttpResponse.BodyHandlers.ofString())
                }
                val rejected = 401 to "invalid_credentials"
                fun HttpResponse<String>.result() = statusCode() to body()
                assertEquals(403 to "forbidden", change("pilot", "original", origin = "http://other.test").result())
                assertEquals(422 to "invalid_password", change("pilot", "original", "bad+space").result())
                assertEquals(rejected, change("unknown", "original").result())
                assertEquals(rejected, change("deleted", "original").result())
                assertEquals(rejected, change("pilot", "wrong").result())
                assertEquals("original", accounts["pilot"])
                assertEquals(200 to "changed", change("pilot", "original", "A%2B%26%3D%25").result())
                repeat(3) { assertEquals(rejected, change("pilot", "original").result()) }
                val limited = change("pilot", "A%2B%26%3D%25")
                assertEquals(429 to "retry_later", limited.result())
                assertEquals("60", limited.headers().firstValue("Retry-After").orElseThrow())
                elapsed.set(60_000_000_000L)
                assertEquals(200 to "changed", change("pilot", "A%2B%26%3D%25").result())
            }
        }
        val stored = checkNotNull(FileAccounts(path)["pilot"])
        assertTrue(AccountPassword.isHashed(stored))
        assertTrue(AccountPassword.matches(stored, "changed"))
        assertFalse(AccountPassword.matches(stored, "original"))
        assertFalse(accounts.containsKey("deleted"))
    }
}
