package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.CountryId
import org.logh7.engine.ShipStyleId
import org.logh7.gateway.load.LoadMetrics
import org.logh7.gateway.load.SyntheticClient
import org.logh7.protocol.CharacterMessages
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileAccountsTest {
    @TempDir lateinit var directory: Path

    @Test fun editsApplyToTheLiveMapAndSurviveAReload() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "multi0=dummy\n")
        val accounts = FileAccounts(file)
        assertEquals("dummy", accounts["multi0"])
        accounts.set("pilot.1", "s3cret")
        assertEquals(listOf("multi0", "pilot.1"), accounts.names())
        val stored = checkNotNull(FileAccounts(file)["pilot.1"]) { "written to the file, not only memory" }
        assertTrue(AccountPassword.isHashed(stored) && "s3cret" !in Files.readString(file))
        assertTrue(AccountPassword.matches(stored, "s3cret"));assertFalse(AccountPassword.matches(stored, "s3creT"))
        assertTrue(AccountPassword.matches("dummy", "dummy"), "plain test entries keep working")
        assertTrue(accounts.remove("multi0"));assertFalse(accounts.remove("multi0"))
        assertNull(accounts["multi0"])
        assertFalse(Files.exists(directory.resolve("accounts.properties.tmp")))
        Files.writeString(file, "outside=edit\n")
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
        assertEquals(listOf("outside"), accounts.names(), "an outside edit is picked up on the next read")
    }

    @Test fun loginRulesAreEnforced() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "")
        val accounts = FileAccounts(file)
        for ((name, password) in listOf("" to "x", "@admin" to "x", "a".repeat(31) to "x", "ok" to "", "ok" to "elevenchars", "ok" to "sp ace", "a=b" to "x"))
            assertFailsWith<IllegalArgumentException>("$name/$password") { accounts.set(name, password) }
        assertTrue(accounts.isEmpty())
    }

    @Test fun registrationNeverReplacesLiveOrRemovedAccounts() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "existing=original\n")
        val accounts = FileAccounts(file, reserveRemovedNames = true)
        assertFalse(accounts.create("existing", "other"))
        assertEquals("original", accounts["existing"])

        java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            val attempts = listOf("first", "second").associateWith { password ->
                pool.submit<Boolean> { accounts.create("new", password) }
            }
            val winner = attempts.entries.single { it.value.get() }.key
            assertTrue(AccountPassword.matches(checkNotNull(accounts["new"]), winner))
        }
        assertTrue(accounts.remove("new"))
        val reloaded = FileAccounts(file)
        assertNull(reloaded["new"])
        assertEquals(listOf("existing"), reloaded.names())
        assertFalse(reloaded.create("new", "newpass"))
        reloaded.set("new", "reset")
        assertTrue(AccountPassword.matches(checkNotNull(reloaded["new"]), "reset"))
    }

    @Test fun recoveryRotatesAndConsumesCodesAtomicallyAndSurvivesReopening() {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "pilot=original\nother=original\n")
        val accounts = FileAccounts(file, reserveRemovedNames = true)
        assertNull(accounts.issueRecoveryCode("pilot", "wrong"))
        val first = assertNotNull(accounts.issueRecoveryCode("pilot", "original"))
        val code = assertNotNull(accounts.issueRecoveryCode("pilot", "original"))
        assertNotEquals(first, code)
        assertFalse(accounts.recoverPassword("pilot", first, "wrong"))
        assertFalse(accounts.recoverPassword("other", code, "wrong"))
        assertFalse(Files.readString(file).contains(code))
        val reloaded = FileAccounts(file, reserveRemovedNames = true)
        assertTrue(AccountPassword.matches(assertNotNull(reloaded["pilot"]), "original"))

        val winner = java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            listOf("first", "second").associateWith { password ->
                pool.submit<Boolean> { reloaded.recoverPassword("pilot", code, password) }
            }.entries.single { it.value.get() }.key
        }
        assertTrue(AccountPassword.matches(assertNotNull(FileAccounts(file)["pilot"]), winner))
        assertFalse(reloaded.recoverPassword("pilot", code, "another"))
        val next = assertNotNull(reloaded.issueRecoveryCode("pilot", winner))
        assertTrue(reloaded.remove("pilot"))
        assertFalse(reloaded.recoverPassword("pilot", next, "another"))
        reloaded.set("pilot", "restored")
        assertFalse(reloaded.recoverPassword("pilot", next, "another"))
        val afterRestore = assertNotNull(reloaded.issueRecoveryCode("pilot", "restored"))
        assertTrue(reloaded.changePassword("pilot", "restored", "changed"))
        assertFalse(reloaded.recoverPassword("pilot", afterRestore, "another"))
    }

    private enum class CredentialChange { RECOVERY, PASSWORD, OPERATOR_RESET, OPERATOR_REMOVE }

    @Test fun recoveryClosesTheOldLobbyAndRejectsAnIssuedTicketWithoutClosingOtherAccounts() =
        exerciseCredentialRevocation(CredentialChange.RECOVERY)

    @Test fun passwordChangeRevokesLiveConnectionsAndUnusedTicketsOnlyAfterThePasswordWasStored() =
        exerciseCredentialRevocation(CredentialChange.PASSWORD)

    @Test fun operatorResetRevokesLiveConnectionsAndUnusedTickets() =
        exerciseCredentialRevocation(CredentialChange.OPERATOR_RESET)

    @Test fun operatorRemovalRevokesLiveConnectionsAndUnusedTickets() =
        exerciseCredentialRevocation(CredentialChange.OPERATOR_REMOVE)

    private fun exerciseCredentialRevocation(change: CredentialChange) {
        val file = directory.resolve("accounts.properties")
        Files.writeString(file, "pilot=original\nother=original\n")
        val accounts = FileAccounts(file)
        val code = if (change == CredentialChange.RECOVERY) assertNotNull(accounts.issueRecoveryCode("pilot", "original")) else null
        val characterFile = directory.resolve("characters.properties")
        val pilot = CharacterMessages.Generate(4, 10000, 2, 0, 0, "Existing", "Pilot", 30, 1, 1,
            1000001, List(8) { 50 }, 0, 0, 0, 9, 3, 3, "ExistingShip", 1)
        assertTrue(CharacterCreation(characterFile).carry("pilot", CharacterProfile(pilot, CountryId(2), ShipStyleId("empire"))))
        val savedCharacter = Files.readAllBytes(characterFile)
        val reservations = List(3) { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        val (loginPort, updatePort, sessionPort) = reservations.map { it.localPort }
        reservations.forEach { it.close() }
        Gateway(accounts = accounts, characterFile = characterFile).use { gateway ->
            gateway.start(loginPort, updatePort, sessionPort = sessionPort)
            fun login(account: String, password: String, expected: Int = 0x7001): Long =
                SyntheticClient(loginPort, LoadMetrics()).use { client ->
                    client.send(SyntheticClient.login(account, password))
                    val reply = ByteBuffer.wrap(client.receive(false))
                    assertEquals(expected, reply.short.toInt() and 65535)
                    if (expected == 0x7001) reply.getInt(14).toLong() and 0xffffffffL else 0
                }
            fun enter(token: Long, lobby: SyntheticClient) {
                lobby.send(SyntheticClient.token(token), SyntheticClient.opcode(0x2000))
                lobby.expect(0x2001)
            }
            fun gameTicket(lobby: SyntheticClient): Long {
                lobby.send(ByteBuffer.allocate(4).putShort(0x2009).putShort(1).array())
                return ByteBuffer.wrap(lobby.expect(0x200a)).getInt(12).toLong() and 0xffffffffL
            }
            fun startGame(token: Long, game: SyntheticClient) {
                game.send(SyntheticClient.token(token), SyntheticClient.opcode(0x0200))
                game.expect(0x0201)
                game.send(SyntheticClient.opcode(0x0205))
                game.expect(0x0206)
            }
            fun assertExistingPilot(game: SyntheticClient) {
                game.send(SyntheticClient.opcode(0x0203))
                assertEquals(pilot.id, ByteBuffer.wrap(game.expect(0x0204)).getInt(6).toLong())
            }
            SyntheticClient(sessionPort, LoadMetrics()).use { old ->
                SyntheticClient(sessionPort, LoadMetrics()).use { other ->
                    enter(login("pilot", "original"), old)
                    enter(login("other", "original"), other)
                    val unused = login("pilot", "original")
                    val unusedGame = gameTicket(old)
                    SyntheticClient(sessionPort, LoadMetrics()).use { game ->
                        startGame(gameTicket(old), game)
                        assertExistingPilot(game)
                        if (change == CredentialChange.RECOVERY) {
                            assertTrue(accounts.recoverPassword("pilot", checkNotNull(code), "changed"))
                        } else if (change == CredentialChange.OPERATOR_RESET) {
                            accounts.set("pilot", "changed")
                        } else if (change == CredentialChange.OPERATOR_REMOVE) {
                            val blockedWrite = Files.createDirectory(directory.resolve("accounts.properties.tmp"))
                            try {
                                assertFailsWith<IOException> { accounts.remove("pilot") }
                            } finally {
                                Files.delete(blockedWrite)
                            }
                            old.send(SyntheticClient.opcode(0x2003)); old.expect(0x2004)
                            assertExistingPilot(game)
                            assertTrue(accounts.remove("pilot"))
                        } else {
                            assertFalse(accounts.changePassword("pilot", "wrong", "changed"))
                            old.send(SyntheticClient.opcode(0x2003)); old.expect(0x2004)
                            assertExistingPilot(game)

                            val blockedWrite = Files.createDirectory(directory.resolve("accounts.properties.tmp"))
                            try {
                                assertFailsWith<IOException> { accounts.changePassword("pilot", "original", "changed") }
                            } finally {
                                Files.delete(blockedWrite)
                            }
                            old.send(SyntheticClient.opcode(0x2003)); old.expect(0x2004)
                            assertExistingPilot(game)
                            login("pilot", "original")
                            login("pilot", "changed", 0x7002)
                            assertTrue(accounts.changePassword("pilot", "original", "changed"))
                        }
                        old.expectClosed()
                        game.expectClosed()
                        for (token in listOf(unused, unusedGame)) {
                            SyntheticClient(sessionPort, LoadMetrics()).use { stale ->
                                stale.send(SyntheticClient.token(token))
                                stale.expectClosed()
                            }
                        }
                        other.send(SyntheticClient.opcode(0x2003))
                        other.expect(0x2004)
                        login("pilot", "original", 0x7002)
                        if (change == CredentialChange.OPERATOR_REMOVE) {
                            login("pilot", "changed", 0x7002)
                            accounts.set("pilot", "changed")
                        }
                        SyntheticClient(sessionPort, LoadMetrics()).use { fresh ->
                            enter(login("pilot", "changed"), fresh)
                            SyntheticClient(sessionPort, LoadMetrics()).use { rejoined ->
                                startGame(gameTicket(fresh), rejoined)
                                assertExistingPilot(rejoined)
                            }
                        }
                        assertContentEquals(savedCharacter, Files.readAllBytes(characterFile))
                    }
                }
            }
        }
    }
}
