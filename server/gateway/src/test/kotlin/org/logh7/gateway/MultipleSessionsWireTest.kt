package org.logh7.gateway

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.persistence.FileWorldStateStore
import org.logh7.protocol.BudgetMessages
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.StrategyActionMessages
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class MultipleSessionsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"A",0,20f,86400,0,0f,2f),GalaxyBase(1001,2015,80,0,"B",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val accounts=(0..4).associate {"load-%03d".format(it) to "fixture"}
    private fun lobby(f:LoadFixture,index:Int=0):SyntheticClient {
        val token=SyntheticClient(f.loginPort,LoadMetrics()).use {login->
            login.send(SyntheticClient.login(f.account(index),"fixture"))
            ByteBuffer.wrap(login.receive(false)).getInt(14).toLong() and 0xffffffffL
        }
        return SyntheticClient(f.sessionPort,LoadMetrics()).also {it.send(SyntheticClient.token(token),SyntheticClient.opcode(0x2000));it.expect(0x2001)}
    }
    private fun selected(lobby:SyntheticClient,id:Int):ByteArray {
        lobby.send(ByteBuffer.allocate(4).putShort(0x2009).putShort(id.toShort()).array())
        return lobby.expect(0x200a)
    }
    @Test fun officesBudgetAndPrivateFundsStayInTheSelectedWorldAcrossRestart() {
        val country = CountryId(2)
        val capital = 1049
        val province = 1050
        val characterId = 10000
        val account = "load-000"
        val financeGalaxy = Galaxy(galaxy.systems, listOf(
            galaxy.bases[0].copy(id = capital, name = Capital.EMPIRE.baseName), galaxy.bases[1],
            galaxy.bases[0].copy(id = province, name = "Province"),
        ), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val registry = GameSessionDirectory()
        fun next(client: SyntheticClient, opcode: Int): ByteBuffer {
            repeat(200) {
                val packet = ByteBuffer.wrap(client.receive())
                if (packet.getShort(4).toInt() and 0xffff == opcode) return packet
            }
            error("Missing opcode ${opcode.toString(16)}")
        }
        fun enter(lobby: SyntheticClient, world: LoadFixture, id: Int, vararg cards: Int): SyntheticClient {
            val redirect = ByteBuffer.wrap(selected(lobby, id))
            assertEquals(world.sessionPort, redirect.getShort(10).toInt() and 0xffff)
            return SyntheticClient(world.sessionPort, LoadMetrics()).also { client ->
                client.send(SyntheticClient.token(redirect.getInt(12).toLong() and 0xffffffffL), SyntheticClient.opcode(0x0200))
                client.expect(0x0201)
                client.send(SyntheticClient.opcode(0x0205))
                client.expect(0x0206)
                client.send(SyntheticClient.opcode(0x0f00))
                val profile = next(client, 0x0323)
                assertEquals(characterId, profile.getInt(6))
                val start = profile.limit() - 1 - 6 * cards.size
                assertEquals(cards.toList(), cards.indices.map { profile.getShort(start + 6 * it).toInt() })
                assertEquals(cards.size + 1, profile.get(start - 7).toInt())
                next(client, 0x0f01)
            }
        }
        fun budget(client: SyntheticClient, phase: Int = 0, quote: Int = 0, rejection: BudgetRejection? = null): List<ByteBuffer> {
            val rate = if (phase == 0) 0 else 25
            client.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(characterId).putShort(BudgetMessages.SEARCH.toShort())
                .putShort(18).putShort(8).putShort(rate.toShort()).putInt(quote).put(phase.toByte())
                .putInt(0).putShort(0).putInt(capital).putShort((phase + 1).toShort()).array())
            val records = (10..12).map { flag ->
                var record = next(client, 0x1200)
                while (record.get(22).toInt() in 14..18) {
                    assertNull(rejection, "Unauthorized sessions must not receive finance updates")
                    record = next(client, 0x1200)
                }
                assertEquals(BudgetMessages.SEARCH, record.getShort(10).toInt())
                assertEquals(rejection?.let { it.ordinal + 1 } ?: 0, record.getShort(16).toInt())
                assertEquals(flag, record.get(22).toInt())
                record
            }
            next(client, 0x1201)
            next(client, 0x0356)
            return records
        }
        fun amount(record: ByteBuffer) = (record.getInt(18).toLong() shl 32) or (record.getInt(23).toLong() and 0xffffffffL)
        fun fundsRequest(preview: Boolean) = ByteBuffer.allocate(31).putShort(0x1200).putInt(characterId)
            .putShort(StrategyActionMessages.SEARCH.toShort()).putShort(FundActions.FUNDS.toShort())
            .putShort(0).putShort(FundActions.LOCAL.toShort()).putInt(if (preview) 0 else province)
            .put(if (preview) 1 else 0).putInt(0).putShort(if (preview) 0 else 150).putInt(0).putShort(0).array()
        fun fundsView(client: SyntheticClient, balance: Int, governed: Boolean) {
            client.send(fundsRequest(preview = true))
            assertEquals(balance, next(client, 0x1200).getInt(29))
            val rows = next(client, 0x1202)
            assertEquals(if (governed) 1 else 0, rows.get(6).toInt())
            if (governed) assertEquals(province, rows.getInt(7))
            next(client, 0x1201)
        }
        fun invest(client: SyntheticClient, balance: Int? = null) {
            client.send(fundsRequest(preview = false))
            val reply = next(client, 0x1200)
            assertEquals(balance?.let { 0 } ?: (SocialRejection.TARGET.ordinal + 1), reply.getShort(16).toInt())
            if (balance != null) assertEquals(balance, reply.getInt(29))
            next(client, 0x1201)
            next(client, 0x0356)
        }
        fun world(id: Int, block: (LoadFixture, FileWorldStateStore) -> Unit) {
            val root = directory.resolve("finance-$id")
            val state = root.resolve("world")
            val fresh = java.nio.file.Files.notExists(state)
            FileWorldStateStore(state).use { store ->
                if (fresh) {
                    val profile = CharacterMessages.Generate(4, characterId.toLong(), 2, 0, 0, "Pilot", "Test", 30,
                        1, 1, 1, List(8) { 50 }, 0, 0, 0, 1, 0, 0, "Ship", 0)
                    assertTrue(CharacterStores(root.resolve("characters.properties")).get(1).carry(account, profile))
                    val pilot = Navigator(1, characterId, Power.EMPIRE, 2487, 1000, 1000, 1000,
                        baseId = capital, mode = 1, cpRecoveryAt = SessionRules.load().startSeconds)
                    val stocks = financeGalaxy.bases.associate { base ->
                        base.id to PlanetStock(base.id, if (base.id == 1001) 3 else 2, 0, 100, 200, 300, treasury = 100L * id)
                    }
                    store.saveWorld(mapOf(account to pilot), PlanetStocks(1, 0, stocks,
                        deploymentInitialized = true, nationalTreasuries = mapOf(country to 4000L * id),
                        strategic = StrategicState(
                            people = mapOf(characterId to StrategicPerson(rank = 1, money = 1000L * id)),
                            offices = if (id == 1) listOf(OfficeHolding(8, capital, characterId),
                                OfficeHolding(54, province, characterId)) else emptyList())))
                }
                LoadFixture(root, 2, LoadMetrics(), financeGalaxy, ticking = false, navigationStore = store.navigationStore,
                    stockStore = store.stockStore, worldStateCommitter = store, sessionDirectory = registry,
                    sessionId = id, sessionName = if (id == 1) "Alpha" else "Beta", accountSource = accounts).use { block(it, store) }
            }
        }
        var quote = 0
        world(1) { alpha, _ -> world(2) { beta, _ -> lobby(alpha).use { lobby ->
            enter(lobby, alpha, 1, 59, 8, 54).use { player ->
                fundsView(player, 1000, governed = true)
                invest(player, balance = 850)
                assertEquals(listOf(4000L, 100L, 0L), budget(player).map(::amount))
                val prepared = budget(player, phase = 1).last()
                assertEquals(1000L, amount(prepared))
                quote = prepared.getInt(29)
                assertTrue(quote > 0)
            }
            enter(lobby, beta, 2, 59).use { player ->
                fundsView(player, 2000, governed = false)
                invest(player)
                assertEquals(listOf(0L, 0L, 0L), budget(player, 2, quote, BudgetRejection.AUTHORITY).map(::amount))
            }
        } } }
        world(1) { alpha, a -> world(2) { beta, b -> lobby(alpha).use { lobby ->
            enter(lobby, beta, 2, 59).use { player ->
                fundsView(player, 2000, governed = false)
                assertEquals(listOf(0L, 0L, 0L), budget(player, rejection = BudgetRejection.AUTHORITY).map(::amount))
            }
            enter(lobby, alpha, 1, 59, 8, 54).use { player ->
                fundsView(player, 850, governed = true)
                assertEquals(quote, budget(player).last().getInt(29))
                repeat(2) { assertEquals(listOf(3000L, 1100L, 1000L), budget(player, 2, quote).map(::amount)) }
            }
            assertEquals(680, a.loadNavigation().getValue(account).mcp)
            assertEquals(920, a.loadNavigation().getValue(account).pcp)
            assertEquals(1000, b.loadNavigation().getValue(account).mcp)
            assertEquals(1000, b.loadNavigation().getValue(account).pcp)
            val alphaStocks = assertNotNull(a.loadStocks())
            assertEquals(250L, alphaStocks.bases.getValue(province).treasury)
            assertEquals(850L, alphaStocks.strategic.people.getValue(characterId).money)
            val betaStocks = assertNotNull(b.loadStocks())
            assertEquals(8000L, betaStocks.nationalTreasuries.getValue(country))
            assertEquals(200L, betaStocks.bases.getValue(capital).treasury)
            assertEquals(200L, betaStocks.bases.getValue(province).treasury)
            assertEquals(2000L, betaStocks.strategic.people.getValue(characterId).money)
            assertTrue(betaStocks.budget.quotes.isEmpty())
        } } }
    }
    @Test fun maintenanceExplainsAStaleSelectionAndAValidTicketWithoutClosingTheLobby() {
        val registry = GameSessionDirectory()
        LoadFixture(directory.resolve("alpha"), 2, LoadMetrics(), sessionDirectory = registry,
            sessionId = 1, sessionName = "Alpha", accountSource = accounts).use { alpha ->
            LoadFixture(directory.resolve("beta"), 2, LoadMetrics(), sessionDirectory = registry,
                sessionId = 2, sessionName = "Beta", accountSource = accounts).use { beta ->
                lobby(alpha).use { client ->
                    val issued = ByteBuffer.wrap(selected(client, 2)).getInt(12).toLong() and 0xffffffffL
                    beta.gateway.maintenance.setClosed(true)
                    client.send(SyntheticClient.opcode(0x2005) + byteArrayOf(0))
                    assertEquals(1, client.expect(0x2006)[7].toInt())
                    client.send(byteArrayOf(0x20, 0x09, 0, 2))
                    assertContentEquals(byteArrayOf(0, 0, 0, 0, 0x20, 0x0b, 2, 0), client.expect(0x200b))
                    assertEquals(alpha.sessionPort, ByteBuffer.wrap(selected(client, 1)).getShort(10).toInt() and 65535)

                    SyntheticClient(beta.sessionPort, LoadMetrics()).use { game ->
                        game.send(SyntheticClient.token(issued), SyntheticClient.opcode(0x0200))
                        assertContentEquals(byteArrayOf(0, 0, 0, 0, 2, 2, 3, 1, 0, 0), game.expect(0x0202))
                        game.expectClosed()
                    }
                    assertEquals(1, beta.gateway.maintenance.refused.get())
                    assertEquals(0, beta.gateway.playerConnections().size)
                    beta.gateway.maintenance.setClosed(false)
                    val reopened = ByteBuffer.wrap(selected(client, 2)).getInt(12).toLong() and 0xffffffffL
                    SyntheticClient(beta.sessionPort, LoadMetrics()).use { game ->
                        game.send(SyntheticClient.token(reopened), SyntheticClient.opcode(0x0200))
                        game.expect(0x0201)
                    }
                }
            }
        }
    }
    @Test fun operatorDisconnectIsScopedToOneWorldEvenWithTheSameAccountInBoth() {
        val registry = GameSessionDirectory()
        LoadFixture(directory.resolve("alpha"), 2, LoadMetrics(), sessionDirectory = registry,
            sessionId = 1, sessionName = "Alpha", accountSource = accounts).use { alpha ->
            LoadFixture(directory.resolve("beta"), 2, LoadMetrics(), sessionDirectory = registry,
                sessionId = 2, sessionName = "Beta", accountSource = accounts).use { beta ->
                val account = alpha.account(0)
                alpha.join(account, create = true).client.use { a ->
                    beta.join(account, create = true).client.use { b ->
                        val idA = alpha.gateway.playerConnections().entries.single().also { assertEquals(account, it.value) }.key
                        val idB = beta.gateway.playerConnections().entries.single().also { assertEquals(account, it.value) }.key
                        assertNotEquals(idA, idB)
                        assertFalse(beta.gateway.disconnectPlayer(idA).get(5, java.util.concurrent.TimeUnit.SECONDS))
                        assertTrue(alpha.gateway.disconnectPlayer(idA).get(5, java.util.concurrent.TimeUnit.SECONDS))
                        a.expectClosed()
                        alpha.awaitState(0, 0, 1)
                        beta.awaitState(1, 1, 1)
                        assertEquals(mapOf(idB to account), beta.gateway.playerConnections())
                        beta.clock(b)
                    }
                }
            }
        }
    }
    @Test fun `shared lobby routes to independent characters worlds and CP and rejects wrong destination and old generation tickets`()=runBlocking {
        val registry=GameSessionDirectory()
        LoadFixture(directory.resolve("a"),2,LoadMetrics(),galaxy,ticking=false,sessionDirectory=registry,
            sessionId=1,sessionName="Alpha",accountSource=accounts).use {a->
            LoadFixture(directory.resolve("b"),2,LoadMetrics(),galaxy,ticking=false,powerByIndex={3},sessionDirectory=registry,
                sessionId=2,sessionName="Beta",accountSource=accounts).use {b->
                fun emptyWorld(expected:Int) {
                    lobby(a,3).use {client->
                        client.send(SyntheticClient.opcode(0x2003));val packet=client.expect(0x2004)
                        assertEquals(1,packet[6].toInt());assertEquals(expected,ByteBuffer.wrap(packet).getShort(7).toInt())
                        assertContentEquals(byteArrayOf(0,0,0),packet.takeLast(3).toByteArray())
                        assertEquals(7+org.logh7.protocol.SessionEndingMessages.information(registry.sessions().first()).size+3,packet.size)
                    }
                }
                emptyWorld(1)
                for(fixture in listOf(a,b))fixture.join(fixture.account(0),true,false).client.use {client->
                    client.send(SyntheticClient.opcode(0x0f00))
                    while(ByteBuffer.wrap(client.receive()).getShort(4).toInt() and 65535!=0x0f01) { }
                }
                a.await("A disconnected") {a.engine.snapshot().session!!.online==0}
                b.await("B disconnected") {b.engine.snapshot().session!!.online==0}
                val ca=CharacterStores(directory.resolve("a/characters.properties")).get(1).character(a.account(0))!!
                val cb=CharacterStores(directory.resolve("b/characters.properties")).get(1).character(b.account(0))!!
                assertEquals(ca.id,cb.id);assertEquals(2,ca.power);assertEquals(3,cb.power)
                assertEquals(Power.EMPIRE,a.engine.snapshot().navigators.getValue(a.account(0)).power)
                assertEquals(Power.ALLIANCE,b.engine.snapshot().navigators.getValue(b.account(0)).power)
                val beforeB=b.engine.snapshot()
                lobby(a).use {l->
                    l.send(SyntheticClient.opcode(0x2005)+byteArrayOf(0));assertEquals(2,l.expect(0x2006)[7].toInt())
                    l.send(SyntheticClient.opcode(0x2003));assertEquals(2,l.expect(0x2004)[6].toInt())
                    val redirect=selected(l,2)
                    assertEquals(b.sessionPort,ByteBuffer.wrap(redirect).getShort(10).toInt() and 65535)
                    val token=ByteBuffer.wrap(redirect).getInt(12).toLong() and 0xffffffffL
                    SyntheticClient(a.sessionPort,LoadMetrics()).use {wrong->wrong.send(SyntheticClient.token(token));wrong.expectClosed()}
                    SyntheticClient(b.sessionPort,LoadMetrics()).use {replay->replay.send(SyntheticClient.token(token));replay.expectClosed()}
                    val next=ByteBuffer.wrap(selected(l,2)).getInt(12).toLong() and 0xffffffffL
                    SyntheticClient(b.sessionPort,LoadMetrics()).use {right->
                        right.send(SyntheticClient.token(next),SyntheticClient.opcode(0x0200));right.expect(0x0201)
                    }
                    val stale=ByteBuffer.wrap(selected(l,1)).getInt(12).toLong() and 0xffffffffL
                    a.engine.submit(WorldCommand.Territory(mapOf(Power.EMPIRE to 4,Power.ALLIANCE to 4),setOf(Power.ALLIANCE)))
                    a.await("A ended") {a.engine.snapshot().result!=null}
                    assertEquals(listOf(2),registry.sessions().map {it.id})
                    emptyWorld(2)
                    a.engine.submit(WorldCommand.Restart)
                    a.await("A restarted") {a.engine.snapshot().session!!.generation==2L}
                    SyntheticClient(a.sessionPort,LoadMetrics()).use {old->old.send(SyntheticClient.token(stale));old.expectClosed()}
                }
                assertEquals(beforeB.navigators,b.engine.snapshot().navigators)
                assertEquals(beforeB.planetStocks,b.engine.snapshot().planetStocks)
            }
            assertEquals(listOf(1),registry.sessions().map {it.id})
        }
        assertTrue(registry.sessions().isEmpty())
    }
}
