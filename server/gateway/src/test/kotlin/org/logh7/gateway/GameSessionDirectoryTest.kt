package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.protocol.SessionEndingMessages
import kotlin.test.*

class GameSessionDirectoryTest {
    private fun entry(id:Int)=GameSessionDirectory.Entry(id,"World$id","127.0.0.1",48000+id,
        setOf("127.0.0.1","192.168.56.1"),{1},{null},
        {SessionEndingMessages.Session(id,1,"World$id","",0,
            listOf(2,3).map {SessionEndingMessages.PowerResult(it,0,0,0,null)},null)},{null})
    @Test fun sharedAccountsHostSelectionRegistrationAndCloseAreScoped() {
        val directory=GameSessionDirectory();val accounts=mapOf("fixture" to "fixture")
        val first=directory.register(entry(1),accounts)
        assertFailsWith<IllegalArgumentException> {directory.register(entry(1),accounts)}
        assertFailsWith<IllegalArgumentException> {directory.register(entry(2),accounts.toMutableMap())}
        directory.register(entry(2),accounts).use {
            assertEquals(listOf(1,2),directory.sessions().map {it.id})
            assertEquals("192.168.56.1",assertIs<GameSessionDirectory.Target>(directory.select(2,"192.168.56.1")).address)
            assertIs<GameSessionDirectory.Refused>(directory.select(2,"192.168.57.1"))
            assertIs<GameSessionDirectory.Refused>(directory.select(9,"127.0.0.1"))
        }
        first.close()
        directory.register(entry(1),accounts).use {
            first.close();assertEquals(listOf(1),directory.sessions().map {it.id},"An old registration cannot remove its replacement")
        }
    }
}
