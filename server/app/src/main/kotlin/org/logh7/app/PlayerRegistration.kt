package org.logh7.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.logh7.gateway.FileAccounts
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Loopback API for the player portal. Author: Peppone Choi (peppone-choi). */
internal fun startPlayerRegistration(port: Int, accounts: FileAccounts): AutoCloseable {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 16)
    server.createContext("/register") { exchange ->
        exchange.use {
            val (status, result) = when {
                exchange.requestURI.toString() != "/register" -> 404 to "not_found"
                exchange.requestMethod != "POST" -> 405 to "method"
                exchange.requestHeaders.getFirst("Host") != "127.0.0.1:${server.address.port}" ||
                    exchange.requestHeaders.containsKey("Origin") ||
                    exchange.requestHeaders.getFirst("X-Logh7-Portal") != "1" -> 403 to "forbidden"
                exchange.requestHeaders.getFirst("Content-Type") != "application/x-www-form-urlencoded" ->
                    415 to "content_type"
                else -> register(exchange, accounts)
            }
            val body = result.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            if (status == 405) exchange.responseHeaders.set("Allow", "POST")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }
    server.start()
    return AutoCloseable { server.stop(0) }
}

private fun register(exchange: HttpExchange, accounts: FileAccounts): Pair<Int, String> {
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
    if (fields.size != 2 || values.keys != setOf("account", "password")) return 400 to "invalid_request"
    val account = values.getValue("account")
    val password = values.getValue("password")
    if (!FileAccounts.validName(account)) return 422 to "invalid_account"
    if (!FileAccounts.validPassword(password)) return 422 to "invalid_password"
    return try {
        if (accounts.create(account, password)) 201 to "created" else 409 to "duplicate"
    } catch (_: IOException) {
        System.err.println("Player registration could not write the account file")
        503 to "unavailable"
    } catch (_: IllegalArgumentException) {
        System.err.println("Player registration could not read the account file")
        503 to "unavailable"
    }
}
