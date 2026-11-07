package org.logh7.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.logh7.gateway.FileAccounts
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Loopback API for the player portal. Author: Peppone Choi (peppone-choi). */
internal fun startPlayerRegistration(port: Int, accounts: FileAccounts, nanoTime: () -> Long = System::nanoTime): AutoCloseable {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
    val registrations = PortalAttempts(nanoTime)
    val changes = PortalAttempts(nanoTime)
    val resets = PortalAttempts(nanoTime)
    fun route(path: String, handle: (HttpExchange) -> Pair<Int, String>) = server.createContext(path) { exchange ->
        exchange.use {
            val (status, result) = when {
                exchange.requestURI.toString() != path -> 404 to "not_found"
                exchange.requestMethod != "POST" -> 405 to "method"
                exchange.requestHeaders.getFirst("Host") != "127.0.0.1:${server.address.port}" ||
                    exchange.requestHeaders.containsKey("Origin") ||
                    exchange.requestHeaders.getFirst("X-Logh7-Portal") != "1" -> 403 to "forbidden"
                exchange.requestHeaders.getFirst("Content-Type") != "application/x-www-form-urlencoded" ->
                    415 to "content_type"
                else -> handle(exchange)
            }
            val body = result.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            if (status == 405) exchange.responseHeaders.set("Allow", "POST")
            if (status == 429) exchange.responseHeaders.set("Retry-After", "60")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }
    route("/register") { exchange -> accountForm(exchange, setOf("account", "password")) { values ->
        val account = values.getValue("account")
        val password = values.getValue("password")
        if (!FileAccounts.validName(account)) 422 to "invalid_account"
        else if (!FileAccounts.validPassword(password)) 422 to "invalid_password"
        else if (!registrations.allow(account)) 429 to "retry_later"
        else if (accounts.create(account, password)) 201 to "created" else 409 to "duplicate"
    } }
    route("/password") { exchange -> accountForm(exchange, setOf("account", "currentPassword", "newPassword")) { values ->
        val account = values.getValue("account")
        val currentPassword = values.getValue("currentPassword")
        val newPassword = values.getValue("newPassword")
        when {
            !FileAccounts.validPassword(newPassword) -> 422 to "invalid_password"
            !FileAccounts.validName(account) || !FileAccounts.validPassword(currentPassword) -> 401 to "invalid_credentials"
            !changes.allow(account) -> 429 to "retry_later"
            accounts.changePassword(account, currentPassword, newPassword) -> 200 to "changed"
            else -> 401 to "invalid_credentials"
        }
    } }
    route("/recovery-code") { exchange -> accountForm(exchange, setOf("account", "currentPassword")) { values ->
        val account = values.getValue("account")
        val password = values.getValue("currentPassword")
        when {
            !FileAccounts.validName(account) || !FileAccounts.validPassword(password) -> 401 to "invalid_credentials"
            !changes.allow(account) -> 429 to "retry_later"
            else -> accounts.issueRecoveryCode(account, password)?.let { 201 to "code:$it" } ?: (401 to "invalid_credentials")
        }
    } }
    route("/password-reset") { exchange -> accountForm(exchange, setOf("account", "recoveryCode", "newPassword")) { values ->
        val account = values.getValue("account")
        val code = values.getValue("recoveryCode")
        val password = values.getValue("newPassword")
        when {
            !FileAccounts.validPassword(password) -> 422 to "invalid_password"
            !FileAccounts.validName(account) || !FileAccounts.validRecoveryCode(code) -> 401 to "invalid_recovery"
            !resets.allow(account) -> 429 to "retry_later"
            accounts.recoverPassword(account, code, password) -> 200 to "changed"
            else -> 401 to "invalid_recovery"
        }
    } }
    server.start()
    return AutoCloseable { server.stop(0) }
}

private fun accountForm(exchange: HttpExchange, required: Set<String>, handle: (Map<String, String>) -> Pair<Int, String>): Pair<Int, String> {
    val bytes = exchange.requestBody.readNBytes(513)
    if (bytes.size > 512) return 413 to "too_large"
    val fields = bytes.toString(Charsets.UTF_8).split('&')
    val values = try {
        fields.associate {
            val (key, value) = it.split('=', limit = 2).takeIf { pair -> pair.size == 2 }
                ?: return 400 to "invalid_request"
            key to URLDecoder.decode(value, Charsets.UTF_8)
        }
    } catch (_: IllegalArgumentException) {
        return 400 to "invalid_request"
    }
    if (fields.size != required.size || values.keys != required) return 400 to "invalid_request"
    return try {
        handle(values)
    } catch (_: IOException) {
        System.err.println("Player portal could not write the account file")
        503 to "unavailable"
    } catch (_: IllegalArgumentException) {
        System.err.println("Player portal could not read the account file")
        503 to "unavailable"
    }
}

/** Short local limits before expensive password verification. The global limit also bounds the account map. */
private class PortalAttempts(private val nanoTime: () -> Long) {
    private var started = nanoTime()
    private var total = 0
    private val counts = mutableMapOf<String, Int>()

    @Synchronized fun allow(account: String): Boolean {
        val now = nanoTime()
        if (now - started >= 60_000_000_000L) {
            started = now
            total = 0
            counts.clear()
        }
        val count = counts.getOrDefault(account, 0)
        if (total >= 60 || count >= 5) return false
        counts[account] = count + 1
        total++
        return true
    }
}
