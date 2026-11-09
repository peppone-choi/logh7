package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.logh7.engine.*
import java.util.concurrent.CompletableFuture

class GameExchangeTest {
    private fun hex(s: String) = java.util.HexFormat.of().parseHex(s)
    private val a = ByteArray(16) { it.toByte() }
    private val b = ByteArray(16) { (240 + it).toByte() }
    private val wrap = LegacyBlowfish(LegacyBlowfish.HANDSHAKE_KEY.toByteArray())
    private val initial = hex("35eb18155d09bbb52a8d5c7fcef57d7712d9f1f6a2bebede")
    private val confirm = hex("385c1e213879e5fc44d62157e541b061e556d88f3a5ae35b")
    private fun handshake() = Handshake(b, 2).also { it.accept(0x34, initial); it.accept(0x36, confirm) }
    private fun encrypted(body: ByteArray, sequence: Long = 1) = LegacyBlowfish(a).encrypt(Envelope.encode(sequence, body))
    private fun request(password: String = "pw"): ByteArray {
        val name = "user\u0000"; val secret = "$password\u0000"
        val out = ByteBuffer.allocate(13 + 2 * (name.length + secret.length))
        out.putShort(0x7000).putInt(0x47494e37).putShort(1).putShort(0).put(0).put(name.length.toByte())
        name.forEach { out.putChar(it) }; out.put(secret.length.toByte()); secret.forEach { out.putChar(it) }
        return out.array()
    }
    private fun exchange(role: GameExchange.Role, tickets: SessionTickets = SessionTickets(), counters: GatewayCounters? = null) =
        GameExchange(role, mapOf("user" to "pw"), tickets, "127.0.0.1", 47903, Handshake(b, 2), counters = counters).also {
            it.accept(0x34, initial); it.accept(0x36, confirm)
        }
    @Test fun responderMatchesSyntheticKexAndDirectionalCipher() {
        val h = Handshake(b, 2)
        assertContentEquals(hex("003200350ae5496598e865832a8d5c7fcef57d774f45662f518c4787df09d00b325f2b21d79b00d06e006dced97727f232edeec3"), h.accept(0x34, initial))
        assertEquals(Handshake.State.AWAITING_CONFIRMATION, h.state)
        assertNull(h.accept(0x36, confirm)); assertEquals(Handshake.State.ESTABLISHED, h.state)
        assertContentEquals(request(), h.receive(0x30, encrypted(request())))
        assertFailsWith<IllegalArgumentException> { h.receive(0x30, encrypted(request())) }
        val frame = h.send(byteArrayOf(0x70, 1))
        val (seq, body) = Envelope.decode(LegacyBlowfish(b).decrypt(frame.copyOfRange(4, frame.size)), 1)
        assertEquals(2L, seq); assertContentEquals(byteArrayOf(0x70, 1), body)
    }
    @Test fun rejectsBadKeyFramesAndEarlyApplication() {
        assertFails { Handshake().accept(0x30, initial) }
        assertFails { Handshake().receive(0x30, encrypted(request())) }
        assertFails { Handshake().accept(0x34, initial.dropLast(1).toByteArray()) }
        val corrupt = wrap.decrypt(initial).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { Handshake().accept(0x34, wrap.encrypt(corrupt)) }
        val h = Handshake(b, 2); h.accept(0x34, initial)
        assertFails { h.accept(0x34, initial) }; assertFails { h.accept(0x36, initial) }
        assertEquals(Handshake.State.AWAITING_CONFIRMATION, h.state)
        h.close(); assertFails { h.accept(0x36, confirm) }
    }
    @Test fun rejectsEmptyRekeyAndWrongOuterType() {
        assertFailsWith<IllegalArgumentException> { handshake().receive(0x30, encrypted(byteArrayOf(0, 0x31))) }
        assertFailsWith<IllegalArgumentException> { handshake().receive(0x31, encrypted(request())) }
        val h = handshake()
        val damaged = Envelope.encode(1, request()).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { h.receive(0x30, LegacyBlowfish(a).encrypt(damaged)) }
        assertContentEquals(request(), h.receive(0x30, encrypted(request())))
    }
    @Test fun loginRedirectAndSingleUseSessionToken() {
        val counters = GatewayCounters()
        val tickets = SessionTickets(); val login = exchange(GameExchange.Role.LOGIN, tickets, counters)
        val response = login.accept(0x30, encrypted(request()))!!
        assertEquals(1, counters.loginAccepted.get()); assertEquals(0, counters.loginRejected.get())
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(4, response.size)), 1).second
        assertContentEquals(hex("70010000000000000100007fbb1f"), body.copyOfRange(0, 14))
        val token = ByteBuffer.wrap(body, 14, 4).int
        val auth = ByteBuffer.allocate(6).putShort(0x20).putInt(token).array()
        val session = exchange(GameExchange.Role.SESSION, tickets, counters)
        assertNull(session.accept(0x30, encrypted(auth)))
        assertEquals(GameExchange.State.AUTHENTICATED, session.state)
        assertEquals(0, counters.sessionTicketRejected.get())
        assertFails { exchange(GameExchange.Role.SESSION, tickets, counters).accept(0x30, encrypted(auth)) }
        assertEquals(1, counters.sessionTicketRejected.get(), "a reused single-use token is counted")
        assertFails { session.accept(0x30, encrypted(auth, 2)) }
    }
    @Test fun maintenanceRefusesEvenACorrectLoginAndReopensAfterwards() {
        val gate = MaintenanceGate(); val counters = GatewayCounters()
        assertTrue(gate.setClosed(true)); assertFalse(gate.setClosed(true), "already closed")
        fun attempt() = GameExchange(GameExchange.Role.LOGIN, mapOf("user" to "pw"), SessionTickets(), "127.0.0.1", 47903, Handshake(b, 2),
            counters = counters, maintenance = gate).also { it.accept(0x34, initial); it.accept(0x36, confirm) }
        val closed = attempt()
        val refused = closed.accept(0x30, encrypted(request()))!!
        assertContentEquals(hex("7002000000000000010000"), Envelope.decode(LegacyBlowfish(b).decrypt(refused.copyOfRange(4, refused.size)), 1).second)
        assertEquals(GameExchange.State.REJECTED, closed.state)
        assertEquals(1, gate.refused.get()); assertEquals(0, counters.loginRejected.get(), "not a login failure")
        assertTrue(gate.setClosed(false))
        val open = attempt()
        open.accept(0x30, encrypted(request()))
        assertEquals(GameExchange.State.AUTHENTICATED, open.state)
        assertEquals(1, gate.refused.get(), "an open gate counts nothing")
    }
    @Test fun aClosedDestinationWorldRefusesANewSessionAdmissionEvenWithAValidTicket() {
        val tickets = SessionTickets()
        val login = exchange(GameExchange.Role.LOGIN, tickets)
        val response = login.accept(0x30, encrypted(request()))!!
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(4, response.size)), 1).second
        val auth = ByteBuffer.allocate(6).putShort(0x20).putInt(ByteBuffer.wrap(body, 14, 4).int).array()
        val gate = MaintenanceGate().also { it.setClosed(true) }
        val session = GameExchange(GameExchange.Role.SESSION, mapOf("user" to "pw"), tickets, "127.0.0.1", 47903, Handshake(b, 2),
            maintenance = gate).also { it.accept(0x34, initial); it.accept(0x36, confirm) }
        assertFails { session.accept(0x30, encrypted(auth)) }
        assertNotEquals(GameExchange.State.AUTHENTICATED, session.state)
        assertEquals(1, gate.refused.get())
    }
    @Test fun maintenanceRefusesFinalEntryOnAnAlreadyAuthenticatedGameConnection() {
        val gate = MaintenanceGate()
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903, Handshake(b, 2),
            maintenance = gate).also { it.accept(0x34, initial); it.accept(0x36, confirm) }
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        assertNotNull(game.accept(0x30, encrypted(hex("0200"), 2)))
        gate.setClosed(true)
        val reply = assertNotNull(game.accept(0x30, encrypted(hex("0205"), 3)))
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(reply.copyOfRange(8, reply.size)), 2).second
        assertContentEquals(hex("00000000020203010000"), body)
        assertEquals(GameExchange.State.REJECTED, game.state)
        assertNull(game.takePendingJoin())
        assertEquals(1, gate.refused.get())
        game.close()
    }
    @Test fun anOpenSingleWorldLobbyStopsIssuingEntryTicketsDuringMaintenance() {
        val gate = MaintenanceGate()
        val tickets = SessionTickets()
        val lobby = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903, Handshake(b, 2),
            maintenance = gate).also { it.accept(0x34, initial); it.accept(0x36, confirm) }
        val token = tickets.issue("user")
        lobby.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        assertNotNull(lobby.accept(0x30, encrypted(hex("2000"), 2)))
        gate.setClosed(true)
        val reply = assertNotNull(lobby.accept(0x30, encrypted(hex("20090001"), 3)))
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(reply.copyOfRange(8, reply.size)), 2).second
        assertContentEquals(LobbyMessages.selectionFailed(LobbyMessages.SelectionFailure.MAINTENANCE), body)
        gate.setClosed(false)
        val opened = assertNotNull(lobby.accept(0x30, encrypted(hex("20090001"), 4)))
        val selection = Envelope.decode(LegacyBlowfish(b).decrypt(opened.copyOfRange(8, opened.size)), 3).second
        assertEquals(0x200a, ByteBuffer.wrap(selection).getShort(4).toInt())
        lobby.close()
    }
    @Test fun badAccountGetsDirectionalFailureHeader() {
        val counters = GatewayCounters()
        val login = exchange(GameExchange.Role.LOGIN, counters = counters)
        val response = login.accept(0x30, encrypted(request("bad")))!!
        assertEquals(1, counters.loginRejected.get()); assertEquals(0, counters.loginAccepted.get())
        val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(4, response.size)), 1).second
        assertContentEquals(hex("7002000000000000010000"), body)
        assertEquals(GameExchange.State.REJECTED, login.state)
        assertFails { login.accept(0x30, encrypted(request(), 2)) }
    }
    @Test fun lobbyListSelectionAndPurposeBoundGameTicket() {
        val tickets = SessionTickets()
        val session = exchange(GameExchange.Role.SESSION, tickets)
        val token = tickets.issue("user")
        assertNull(session.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array())))
        fun request(body: ByteArray, sequence: Long): ByteArray {
            val frame = session.accept(0x30, encrypted(body, sequence))!!
            assertEquals(frame.size - 2, ByteBuffer.wrap(frame).short.toInt() and 65535)
            assertContentEquals(hex("000000000030"), frame.copyOfRange(2, 8))
            return Envelope.decode(LegacyBlowfish(b).decrypt(frame.copyOfRange(8, frame.size)), sequence - 1).second
        }
        assertContentEquals(hex("000000002001000000"), request(hex("200047494e3700040000070069006e00650069003000300000"), 2))
        val empty=request(hex("2003"),3)
        assertEquals(1,empty[6].toInt())
        assertContentEquals(hex("000000"),empty.copyOfRange(empty.size-3,empty.size))
        val list = request(hex("200501"), 4)
        assertContentEquals(hex("00000000200600010001010c"), list.copyOfRange(0, 12))
        assertTrue(list.toList().windowed(24).any { it.toByteArray().contentEquals("LOGH7 Local\u0000".toByteArray(Charsets.UTF_16BE)) })
        assertContentEquals(list, request(hex("200502"), 5))
        assertContentEquals(list, request(hex("200500"), 6))
        assertContentEquals(LobbyMessages.selectionFailed(LobbyMessages.SelectionFailure.UNAVAILABLE), request(hex("20090002"), 7))
        val selected = request(hex("20090001"), 8)
        assertContentEquals(hex("00000000200a0100007fbb1f"), selected.copyOfRange(0, 12))
        val gameToken = ByteBuffer.wrap(selected, 12, 4).int
        val game = exchange(GameExchange.Role.SESSION, tickets)
        val auth = ByteBuffer.allocate(6).putShort(0x20).putInt(gameToken).array()
        assertNull(game.accept(0x30, encrypted(auth)))
        val accepted = game.accept(0x30, encrypted(hex("0200"), 2))!!
        assertContentEquals(hex("00000000020100"), Envelope.decode(LegacyBlowfish(b).decrypt(accepted.copyOfRange(8, accepted.size)), 1).second)
        assertFails { exchange(GameExchange.Role.SESSION, tickets).accept(0x30, encrypted(auth)) }
        val wrongRole = exchange(GameExchange.Role.SESSION, tickets)
        val otherToken = tickets.issue("user", SessionTickets.Purpose.GAME)
        wrongRole.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(otherToken.toInt()).array()))
        assertFails { wrongRole.accept(0x30, encrypted(hex("2000"), 2)) }
    }
    @Test fun loginUsesActualUtf16LengthAndRejectsTruncation() {
        assertEquals(LoginMessages.Request("user", "pw"), LoginMessages.request(request()))
        assertFails { LoginMessages.request(request().dropLast(1).toByteArray()) }
        assertFails { LoginMessages.request(request() + byteArrayOf(0)) }
        assertContentEquals(hex("01020304002005"), LoginMessages.sessionMessage(0x20, byteArrayOf(5), 0x01020304))
    }
    @Test fun storedCharacterBootstrapsWorldAndGridBeforeAcknowledgements() {
        val characters = CharacterCreation()
        var data = CharacterMessages.Generate(0, 0, 2, 0, 0, "Alpha", "Pilot", 0, 0, 0, 0,
            List(8) { 0 }, 0, 0, 0, 0, 0, 0, "", 0)
        fun step(next: CharacterMessages.Generate) {
            val reply = characters.accept("user", next)
            data = CharacterMessages.generate(reply.copyOfRange(4, reply.size))
        }
        step(data); step(data.copy(category = 1, age = 18, birthMonth = 1, birthDay = 1, face = 1_000_001))
        step(data.copy(category = 2)); step(data.copy(category = 3, shipName = "AlphaShip")); step(data.copy(category = 4))
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903, Handshake(b, 2), characters, gameSeconds = { 86_400 })
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2))
        game.accept(0x30, encrypted(hex("0205"), 3))
        var serverSequence = 3L
        fun opcodes(bytes: ByteArray): List<Int> {
            val results = mutableListOf<Int>(); var offset = 0
            while (offset < bytes.size) {
                val length = (ByteBuffer.wrap(bytes, offset, 2).short.toInt() and 65535) + 2
                assertContentEquals(hex("000000000030"), bytes.copyOfRange(offset + 2, offset + 8))
                val (sequence, body) = Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset + 8, offset + length)), serverSequence)
                serverSequence = sequence
                results += ByteBuffer.wrap(body, 4, 2).short.toInt() and 65535
                offset += length
            }
            return results
        }
        assertEquals(listOf(0x204, 0x323, 0x325, 0xf01), opcodes(game.accept(0x30, encrypted(hex("0f00"), 4))!!))
        assertEquals(listOf(0x204, 0x323, 0x325, 0xf03), opcodes(game.accept(0x30, encrypted(hex("0f02"), 5))!!))
        val clockFrame = game.accept(0x30, encrypted(hex("0300"), 6))!!
        val clockBody = Envelope.decode(LegacyBlowfish(b).decrypt(clockFrame.copyOfRange(8, clockFrame.size)), serverSequence).second
        assertContentEquals(hex("00000000030100015180"), clockBody)
    }
    @Test fun clockResponseWaitsForTimeCommitBeforeRecoveryAndUsesTheFreshSample() {
        for(fail in listOf(false,true)) {
            var now=java.time.Instant.EPOCH
            val clock=object:java.time.Clock() {
                override fun getZone()=java.time.ZoneOffset.UTC
                override fun withZone(zone:java.time.ZoneId):java.time.Clock=this
                override fun instant()=now
            }
            val characters=CharacterCreation(clock=clock,lotteryWindowMillis=1)
            characters.apply("user",listOf(1001));now=now.plusMillis(1)
            val sampled=CompletableFuture<Unit>();val calls=mutableListOf<String>();var seconds=0L
            val player=org.logh7.engine.Navigator(1,1001,org.logh7.engine.Power.EMPIRE,2487,1000,1000,1000)
            val navigation=object:GameNavigation {
                override fun synchronizeTime():CompletableFuture<Unit>{calls+="clock";return sampled}
                override fun apply(request:org.logh7.engine.NavigationRequest):CompletableFuture<org.logh7.engine.NavigationResult> {
                    calls+="navigation"
                    return CompletableFuture.completedFuture(org.logh7.engine.NavigationResult(player))
                }
            }
            val tickets=SessionTickets()
            val game=GameExchange(GameExchange.Role.SESSION,emptyMap(),tickets,"127.0.0.1",47903,
                Handshake(b,2),characters,gameSeconds={seconds},expectedGeneration=1,connection="owned",navigation=navigation)
            game.accept(0x34,initial);game.accept(0x36,confirm)
            val token=tickets.issue("user",SessionTickets.Purpose.GAME)
            game.accept(0x30,encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30,encrypted(hex("0200"),2));game.accept(0x30,encrypted(hex("0205"),3))
            assertNull(game.accept(0x30,encrypted(hex("0300"),4)))
            val operation=assertNotNull(game.takePendingWorld());assertFalse(operation.isDone)
            assertEquals(listOf("clock"),calls)
            if(fail) {
                sampled.completeExceptionally(java.io.IOException("clock save failed"))
                assertTrue(operation.isCompletedExceptionally);assertEquals(listOf("clock"),calls)
            } else {
                seconds=1234;sampled.complete(Unit)
                val response=operation.join().first()
                assertEquals(0x0301,ByteBuffer.wrap(response).getShort(4).toInt())
                assertEquals(1234,ByteBuffer.wrap(response).getInt(6))
                assertEquals(listOf("clock","navigation"),calls)
            }
            game.close()
        }
    }
    @Test fun battleDeathNoticeWaitsForTheClockAfterRelocationAndIsSentOnce() {
        var now = java.time.Instant.EPOCH
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        val registered = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
        val tickets = SessionTickets()
        registered.apply("user", listOf(1001))
        now = now.plusMillis(1)
        val character = assertNotNull(registered.character("user"))
        val player = org.logh7.engine.Navigator(1, character.id.toInt(), org.logh7.engine.Power.EMPIRE, 2487, 1000, 1000, 1000)
        var current = player
        var stocks = org.logh7.engine.PlanetStocks(1, 0, emptyMap())
        val navigation = object : GameNavigation {
            override fun snapshot() = org.logh7.engine.WorldSnapshot(0, 0, planetStocks = stocks)
            override fun apply(request: org.logh7.engine.NavigationRequest) =
                CompletableFuture.completedFuture(org.logh7.engine.NavigationResult(current))
        }
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
            Handshake(b, 2), registered, expectedGeneration = 1, connection = "owned", navigation = navigation)
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2)); game.accept(0x30, encrypted(hex("0205"), 3))
        fun request(op: String, sequence: Long): List<ByteArray> {
            game.accept(0x30, encrypted(hex(op), sequence))
            val packets = assertNotNull(game.takePendingWorld()).join()
            assertNotNull(game.finishWorld(packets))
            return packets
        }
        fun notices(packets: List<ByteArray>) = packets.filter { ByteBuffer.wrap(it).getShort(4).toInt() == 0x0f1d }
        request("0f02", 4)
        assertTrue(notices(request("0300", 5)).isEmpty())
        current = player.copy(grid = 2488, baseId = 1000, mode = 4)
        stocks = stocks.copy(strategic = org.logh7.engine.StrategicState(people =
            mapOf(character.id.toInt() to org.logh7.engine.StrategicPerson(deceasedAt = 1))))
        assertTrue(notices(request("0300", 6)).isEmpty())
        val packet = notices(request("0300", 7)).single()
        assertEquals("[전사] 재접속 후 생성", org.logh7.protocol.ChatMessages.spot(packet.drop(4).toByteArray()).text)
        assertTrue(notices(request("0300", 8)).isEmpty())
        game.close()
    }

    @Test fun reinforcementAfterTacticalSnapshotCaptureRequestsAnotherInitializationButEarlierArrivalDoesNot() {
        for(late in listOf(false,true))for(peerRefresh in listOf(false,true)) {
            var now=java.time.Instant.EPOCH
            val clock=object:java.time.Clock() {
                override fun getZone()=java.time.ZoneOffset.UTC
                override fun withZone(zone:java.time.ZoneId):java.time.Clock=this
                override fun instant()=now
            }
            val characters=CharacterCreation(clock=clock,lotteryWindowMillis=1)
            characters.apply("user",listOf(1001));now=now.plusMillis(1)
            val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Battle",2)),emptyList(),Power.entries.associateWith {2487})
            val player=Navigator(1,1001,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true)
            var revision=1L
            val navigation=object:GameNavigation {
                override fun snapshot()=WorldSnapshot(revision,0,SessionSnapshot(1,0,0,0,emptyMap(),1,1,null),
                    navigators=mapOf("user" to player),planetStocks=PlanetStocks(1,0,emptyMap()))
                override fun apply(request:NavigationRequest)=CompletableFuture.completedFuture(NavigationResult(player))
            }
            val tickets=SessionTickets()
            val game=GameExchange(GameExchange.Role.SESSION,emptyMap(),tickets,"127.0.0.1",47903,
                Handshake(b,2),characters,gameSeconds={0},expectedGeneration=1,connection="owned",
                navigation=navigation,world=WorldBootstrap(galaxy))
            fun opcodes(batch:OutboundBatch):List<Int> {
                val bytes=batch.bytes;var offset=0;val result=mutableListOf<Int>()
                while(offset<bytes.size) {
                    val end=offset+2+(ByteBuffer.wrap(bytes).getShort(offset).toInt() and 65535)
                    val body=Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset+8,end)),-1).second
                    result+=ByteBuffer.wrap(body).getShort(4).toInt() and 65535;offset=end
                }
                return result
            }
            try {
                game.accept(0x34,initial);game.accept(0x36,confirm)
                val token=tickets.issue("user",SessionTickets.Purpose.GAME)
                game.accept(0x30,encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
                game.accept(0x30,encrypted(hex("0200"),2));game.accept(0x30,encrypted(hex("0205"),3))
                game.accept(0x30,encrypted(hex("0f00"),4))
                assertNotNull(game.finishWorld(assertNotNull(game.takePendingWorld()).join()))
                fun arrive() {
                    revision=2
                    val notification=if(peerRefresh)LoginMessages.sessionMessage(0x0b09,byteArrayOf()) else TacticsMessages.notify(2487,true)
                    assertNull(game.notifyWorld(listOf(notification)))
                }
                if(!late)arrive()
                game.accept(0x30,encrypted(hex("0f02"),5))
                val captured=assertNotNull(game.takePendingWorld()).join()
                if(late)arrive() // Snapshot already built, but its outbound batch has not been published.
                val delivered=opcodes(assertNotNull(game.finishWorld(captured)))
                assertEquals(if(late)1 else 0,delivered.count {it==0x0f1f})
                if(late)assertEquals(0x0f1f,delivered.last())
                game.accept(0x30,encrypted(hex("0f02"),6))
                val latest=opcodes(assertNotNull(game.finishWorld(assertNotNull(game.takePendingWorld()).join())))
                assertFalse(0x0f1f in latest,"A current snapshot must finish without an initialization loop")
            } finally {game.close()}
        }
    }
    @Test fun clockRefreshPublishesChangedDetectionAndReturnPlanetToTheCurrentViewer() {
        var lotteryTime=java.time.Instant.EPOCH
        val clock=object:java.time.Clock() {
            override fun getZone()=java.time.ZoneOffset.UTC
            override fun withZone(zone:java.time.ZoneId):java.time.Clock=this
            override fun instant()=lotteryTime
        }
        val characters=CharacterCreation(clock=clock,lotteryWindowMillis=1)
        characters.apply("user",listOf(1001));lotteryTime=lotteryTime.plusMillis(1)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,0,"Battle",2)),emptyList(),Power.entries.associateWith {2487})
        var seconds=100L
        fun at(y:Float):Navigator {
            val point=TacticalPoint(0f,y)
            return Navigator(1,1001,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,
                motion=TacticalMotion(2487,seconds,TacticalPose(point,0f),0f,0f,listOf(point)))
        }
        var player=at(21f).copy(returnBase=1048)
        val target=900001
        val stock=PlanetStocks(1,0,emptyMap(),social=SocialState(pirates=PirateState(
            mapOf(1 to PirateFleet(1,2487,seconds,listOf(PirateUnit(target,300)))),2)))
        val navigation=object:GameNavigation {
            override fun snapshot()=WorldSnapshot(0,0,SessionSnapshot(1,seconds,0,0,emptyMap(),1,1,null),
                navigators=mapOf("user" to player),planetStocks=stock)
            override fun apply(request:NavigationRequest)=CompletableFuture.completedFuture(NavigationResult(player))
        }
        val tickets=SessionTickets()
        val game=GameExchange(GameExchange.Role.SESSION,emptyMap(),tickets,"127.0.0.1",47903,
            Handshake(b,2),characters,gameSeconds={seconds},expectedGeneration=1,connection="owned",
            navigation=navigation,world=WorldBootstrap(galaxy))
        fun status(messages:List<ByteArray>):Map<Int,Int> = buildMap {
            for(packet in messages) {
                val data=ByteBuffer.wrap(packet)
                if(data.getShort(4).toInt()!=0x033b)continue
                repeat(data.getShort(6).toInt()) {i->put(data.getInt(8+i*47),packet[8+i*47+46].toInt())}
            }
        }
        try {
            game.accept(0x34,initial);game.accept(0x36,confirm)
            val token=tickets.issue("user",SessionTickets.Purpose.GAME)
            game.accept(0x30,encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30,encrypted(hex("0200"),2));game.accept(0x30,encrypted(hex("0205"),3))
            for((sequence,opcode) in listOf(4L to "0f00",5L to "0f02")) {
                game.accept(0x30,encrypted(hex(opcode),sequence))
                val packet=assertNotNull(game.takePendingWorld()).join()
                assertEquals(0,status(packet)[target])
                assertNotNull(game.finishWorld(packet))
            }
            fun refresh(y:Float):Map<Int,Int> {
                seconds+=24
                player=at(y).copy(returnBase=player.returnBase)
                val packets=assertNotNull(game.refreshWorld()).join()
                game.finishWorld(packets)
                return status(packets)
            }
            assertTrue(refresh(21f).isEmpty())
            assertEquals(mapOf(target to 1),refresh(18f))
            assertTrue(refresh(18f).isEmpty())
            assertEquals(mapOf(target to 0),refresh(21f))
            seconds+=24
            player=player.copy(returnBase=0)
            val cleared=assertNotNull(game.refreshWorld()).join()
            val changed=cleared.single {ByteBuffer.wrap(it).getShort(4).toInt()==0x0356}
            assertEquals(0,ByteBuffer.wrap(changed).getInt(27))
            assertTrue(status(cleared).isEmpty())
            assertNotNull(game.finishWorld(cleared))
            seconds+=24
            val unchanged=assertNotNull(game.refreshWorld()).join()
            assertTrue(unchanged.none {ByteBuffer.wrap(it).getShort(4).toInt()==0x0356})
        } finally {game.close()}
    }

    @Test fun tacticalPositionsWaitForCurrentTimeAndRecheckRetreatBeforeReplaying() {
        for(outcome in listOf("moving","failure","retreat","same-grid-return","malformed")) {
            var now=java.time.Instant.EPOCH
            val clock=object:java.time.Clock() {
                override fun getZone()=java.time.ZoneOffset.UTC
                override fun withZone(zone:java.time.ZoneId):java.time.Clock=this
                override fun instant()=now
            }
            val characters=CharacterCreation(clock=clock,lotteryWindowMillis=1)
            characters.apply("user",listOf(1001));now=now.plusMillis(1)
            val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Capital",2)),emptyList(),
                Power.entries.associateWith {2487})
            val motion=TacticalMotion(2487,0,TacticalPose(TacticalPoint(0f,0f),0f),1f,0f,
                listOf(TacticalPoint(0f,100f)))
            var player=Navigator(1,1001,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true,
                energy=TacticalEnergy(listOf(100)+List(10){0}),motion=motion,returnBase=1049)
            var seconds=0L;var syncs=0;var recoveries=0
            var sampled=CompletableFuture<Unit>()
            val navigation=object:GameNavigation {
                override fun synchronizeTime():CompletableFuture<Unit>{syncs++;return sampled}
                override fun snapshot()=WorldSnapshot(0,0,SessionSnapshot(1,seconds,0,0,emptyMap(),1,1,null),
                    navigators=mapOf("user" to player),planetStocks=PlanetStocks(1,0,emptyMap()))
                override fun apply(request:NavigationRequest):CompletableFuture<NavigationResult> {
                    recoveries++;return CompletableFuture.completedFuture(NavigationResult(player))
                }
            }
            val tickets=SessionTickets()
            val presence=GamePresence()
            val peer=assertNotNull(characters.character("user")).copy(id=1002)
            presence.update(GamePresence.Member("peer",peer,player.copy(characterId=1002),{}))
            val game=GameExchange(GameExchange.Role.SESSION,emptyMap(),tickets,"127.0.0.1",47903,
                Handshake(b,2),characters,gameSeconds={seconds},expectedGeneration=1,connection="owned",
                navigation=navigation,world=WorldBootstrap(galaxy),presence=presence)
            game.accept(0x34,initial);game.accept(0x36,confirm)
            val token=tickets.issue("user",SessionTickets.Purpose.GAME)
            game.accept(0x30,encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30,encrypted(hex("0200"),2));game.accept(0x30,encrypted(hex("0205"),3))
            game.accept(0x30,encrypted(hex("0f02"),4))
            game.finishWorld(assertNotNull(game.takePendingWorld()).join())
            val query=ByteBuffer.allocate(8).putShort(0x0348).putShort(1).putInt(player.unitId).array()
            if(outcome=="malformed") {
                assertFails {game.accept(0x30,encrypted(query.copyOf(7),5))}
                assertEquals(0,syncs);game.close();continue
            }
            assertNull(game.accept(0x30,encrypted(query,5)))
            val operation=assertNotNull(game.takePendingWorld())
            assertFalse(operation.isDone);assertEquals(1,syncs);assertEquals(1,recoveries)
            val queuedMovement=listOf(TacticalMoveMessages.moved(5,player.unitId,0f,
                TacticalMoveMessages.Point(0f,5f,0f),0))
            if(outcome=="failure") {
                sampled.completeExceptionally(java.io.IOException("position time save failed"))
                assertTrue(operation.isCompletedExceptionally);assertEquals(1,recoveries)
            } else {
                seconds=10
                if(outcome=="retreat") player=player.copy(grid=2488,cpPaused=false,motion=null)
                if(outcome=="same-grid-return") {
                    player=player.copy(baseId=1000,mode=4,cpPaused=false,motion=null,returnBase=0)
                    val returnUnit=BootstrapMessages.unit(assertNotNull(characters.character("user")),player.grid,
                        player.cruise,player.baseId,player.mode)
                    val batch=assertNotNull(game.notifyWorld(listOf(TacticsMessages.notify(2487,false),returnUnit)))
                    var offset=0
                    val delivered=mutableListOf<ByteArray>()
                    while(offset<batch.bytes.size) {
                        val end=offset+2+(ByteBuffer.wrap(batch.bytes).getShort(offset).toInt() and 65535)
                        delivered+=Envelope.decode(LegacyBlowfish(b).decrypt(batch.bytes.copyOfRange(offset+8,end)),-1).second
                        offset=end
                    }
                    assertEquals(listOf(0x0f1f,0x0325),delivered.map {ByteBuffer.wrap(it).getShort(4).toInt()})
                    assertContentEquals(returnUnit.copyOfRange(4,returnUnit.size),delivered.last().copyOfRange(4,delivered.last().size))
                    assertNull(game.notifyWorld(listOf(TacticsMessages.notify(2487,false))))
                }
                if(outcome=="retreat" || outcome=="same-grid-return") {
                    val queuedSupport=listOf(
                        org.logh7.protocol.TacticalSupportMessages.supplied(1,player.unitId,100,200),
                        TacticsMessages.definitions(2487,emptyList()))
                    assertNull(game.notifyWorld(queuedSupport,tacticalGrid=2487))
                    assertNull(game.notifyWorld(queuedMovement,tacticalGrid=2487))
                    val queuedImpact=listOf(TacticalAttackMessages.hit(1,1002,0,player.unitId,1))
                    assertNull(game.notifyWorld(queuedImpact,tacticalGrid=2487))
                }
                sampled.complete(Unit)
                val messages=operation.join();val position=ByteBuffer.wrap(messages.first())
                assertEquals(0x0349,position.getShort(4).toInt())
                if(outcome=="retreat" || outcome=="same-grid-return") {
                    assertEquals(0,position.getShort(6).toInt());assertEquals(2,recoveries)
                    val changed=messages.single {ByteBuffer.wrap(it).getShort(4).toInt()==0x0356}
                    assertEquals(if(outcome=="same-grid-return")0 else 1049,ByteBuffer.wrap(changed).getInt(27))
                    val bytes=assertNotNull(game.finishWorld(messages)).bytes
                    var offset=0;val opcodes=mutableListOf<Int>()
                    while(offset<bytes.size) {
                        val end=offset+2+(ByteBuffer.wrap(bytes).getShort(offset).toInt() and 65535)
                        val body=Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset+8,end)),-1).second
                        opcodes+=ByteBuffer.wrap(body).getShort(4).toInt() and 65535;offset=end
                    }
                    assertEquals(outcome=="retreat",0x0f1f in opcodes)
                    assertFalse(0x0b09 in opcodes || 0x0b0a in opcodes,"A retiring battlefield must not rebuild live native models")
                    val queuedPresence=listOf(LoginMessages.sessionMessage(0x0b09,byteArrayOf(0)),
                        LoginMessages.sessionMessage(0x0b0a,byteArrayOf(0)))
                    assertNull(game.notifyWorld(queuedPresence),"Queued presence must wait for strategic initialization")
                    game.accept(0x30,encrypted(hex("0f00"),6))
                    game.finishWorld(assertNotNull(game.takePendingWorld()).join())
                    assertNotNull(game.notifyWorld(queuedPresence),"Strategic initialization must resume normal presence updates")
                    if(outcome=="same-grid-return") {
                        player=player.copy(baseId=0,mode=5,cpPaused=true)
                        game.accept(0x30,encrypted(hex("0f02"),7))
                        assertNotNull(game.finishWorld(assertNotNull(game.takePendingWorld()).join()))
                        assertNull(game.notifyWorld(listOf(TacticsMessages.notify(2487,false))),
                            "A queued exit must not close the reentered battlefield")
                        val staleExit=TacticsMessages.notify(2488,false)
                        assertNull(game.notifyWorld(listOf(staleExit)),
                            "An exit from another grid must not reach the initialized battlefield")
                        player=player.copy(grid=2488)
                        assertNull(game.notifyWorld(listOf(TacticsMessages.notify(2487,false))),
                            "The previous grid exit must not close a committed tactical arrival")
                    }
                } else {
                    assertNotNull(game.notifyWorld(queuedMovement,tacticalGrid=2487))
                    assertEquals(1,position.getShort(6).toInt());assertEquals(player.unitId,position.getInt(8))
                    assertEquals(0f,position.getFloat(12));assertEquals(5f,position.getFloat(16))
                    val replay=messages.single {ByteBuffer.wrap(it).getShort(4).toInt()==0x0400}
                    val resumed=TacticalMoveMessages.move(replay.copyOfRange(4,replay.size))
                    assertEquals(5f,resumed.units.single().position.y)
                    assertNotNull(game.finishWorld(messages))
                    sampled=CompletableFuture.completedFuture(Unit)
                    assertNull(game.accept(0x30,encrypted(query,6)))
                    assertEquals(listOf(0x0349),assertNotNull(game.takePendingWorld()).join().map {
                        ByteBuffer.wrap(it).getShort(4).toInt()})
                }
            }
            game.close()
        }
    }
    @Test fun asynchronousBattleEntryCannotPreemptTheSendersStrategicWarp() {
        var now=java.time.Instant.EPOCH
        val clock=object:java.time.Clock() {
            override fun getZone()=java.time.ZoneOffset.UTC
            override fun withZone(zone:java.time.ZoneId):java.time.Clock=this
            override fun instant()=now
        }
        val characters=CharacterCreation(clock=clock,lotteryWindowMillis=1)
        characters.apply("user",listOf(1001));now=now.plusMillis(1)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Origin",2),GalaxySystem(4,2488,4,"Battle",3)),
            emptyList(),Power.entries.associateWith {2487})
        var player=Navigator(1,1001,Power.EMPIRE,2487,1000,1000,1000)
        val navigation=object:GameNavigation {
            override fun snapshot()=WorldSnapshot(1,0,SessionSnapshot(1,0,0,0,emptyMap(),1,1,null),
                navigators=mapOf("user" to player),planetStocks=PlanetStocks(1,0,emptyMap()))
            override fun apply(request:NavigationRequest):CompletableFuture<NavigationResult> {
                val before=player
                request.destination?.let {player=player.copy(grid=it,cpPaused=true,mcp=960,cruise=900,moves=1)}
                return CompletableFuture.completedFuture(NavigationResult(player,before))
            }
        }
        val tickets=SessionTickets()
        val game=GameExchange(GameExchange.Role.SESSION,emptyMap(),tickets,"127.0.0.1",47903,
            Handshake(b,2),characters,expectedGeneration=1,connection="owned",navigation=navigation,world=WorldBootstrap(galaxy))
        fun delivered():List<ByteArray> {
            val batch=assertNotNull(game.finishWorld(assertNotNull(game.takePendingWorld()).join()))
            val bytes=batch.bytes;var offset=0;val result=mutableListOf<ByteArray>()
            while(offset<bytes.size) {
                val end=offset+2+(ByteBuffer.wrap(bytes).getShort(offset).toInt() and 65535)
                result+=Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset+8,end)),-1).second
                offset=end
            }
            return result
        }
        fun opcode(message:ByteArray)=ByteBuffer.wrap(message).getShort(4).toInt() and 65535
        try {
            game.accept(0x34,initial);game.accept(0x36,confirm)
            val token=tickets.issue("user",SessionTickets.Purpose.GAME)
            game.accept(0x30,encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30,encrypted(hex("0200"),2));game.accept(0x30,encrypted(hex("0205"),3))
            game.accept(0x30,encrypted(hex("0f00"),4));assertFalse(delivered().any {opcode(it)==0x0f1f})
            game.accept(0x30,encrypted(hex("0f02"),5));assertFalse(delivered().any {opcode(it)==0x0f1f})
            val warp=NavigationMessages.Warp(1001,59,2488,0,5)
            game.accept(0x30,encrypted(NavigationMessages.accepted(warp,0,1000,1000).copyOfRange(4,37),6))
            // Engine/presence observers may announce the destination battle before
            // this sender has received its own persisted warp completion.
            assertNull(game.notifyWorld(listOf(TacticsMessages.notify(2488,true))))
            val arrival=delivered()
            assertEquals(listOf(0x0b01,0x0356,0x0325,0x0317,0x0b07),arrival.map(::opcode))
            assertNull(game.notifyWorld(listOf(TacticsMessages.notify(2488,true))))
            game.accept(0x30,encrypted(hex("0f02"),7))
            assertFalse(delivered().any {opcode(it)==0x0f1f},"Completed tactical initialization must not loop")
            assertNotNull(game.notifyWorld(listOf(TacticsMessages.notify(2488,true))))
        } finally {game.close()}
    }
    @Test fun warpWaitsForNavigationPersistenceThenPublishesAuthoritativeState() {
        var now = java.time.Instant.EPOCH
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        val characters = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
        characters.apply("user", listOf(1001)); now = now.plusMillis(1)
        val pending = CompletableFuture<org.logh7.engine.NavigationResult>()
        val requests = mutableListOf<org.logh7.engine.NavigationRequest>()
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
            Handshake(b, 2), characters, expectedGeneration = 1, connection = "owned",
            navigation = GameNavigation { requests += it; pending })
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2)); game.accept(0x30, encrypted(hex("0205"), 3))
        val command = org.logh7.protocol.NavigationMessages.Warp(1001, 59, 2488, 0, 5)
        val request = org.logh7.protocol.NavigationMessages.accepted(command, 123, Int.MAX_VALUE, Int.MAX_VALUE).copyOfRange(4, 37)
        assertNull(game.accept(0x30, encrypted(request, 4)))
        val operation = assertNotNull(game.takePendingWorld())
        assertFalse(operation.isDone)
        assertEquals(org.logh7.engine.NavigationRequest("user", 1001, org.logh7.engine.Power.EMPIRE,
            "owned", 1, 2488, 1001001, 59,shipStyle=org.logh7.engine.ShipStyleId("empire")), requests.single())
        val player = org.logh7.engine.Navigator(1, 1001, org.logh7.engine.Power.EMPIRE, 2488, 1000, 960, 900, 1)
        pending.complete(org.logh7.engine.NavigationResult(player))
        val messages = operation.join()
        assertEquals(listOf(0x0b01, 0x0356, 0x0325, 0x0317, 0x0b07), messages.map {
            ByteBuffer.wrap(it, 4, 2).short.toInt() and 65535
        })
        assertEquals(1000, ByteBuffer.wrap(messages.first()).getInt(20))
        assertEquals(960, ByteBuffer.wrap(messages.first()).getInt(24))
        assertNotNull(game.finishWorld(messages))
        game.close(); assertNull(game.finishWorld(messages))
    }
    @Test fun dockAndSpotMovesWaitForPersistenceAndPublishLiveCharacterUpdates() {
        var now = java.time.Instant.EPOCH
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        val characters = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
        characters.apply("user", listOf(1001)); now = now.plusMillis(1)
        val galaxy = org.logh7.engine.Galaxy(
            listOf(org.logh7.engine.GalaxySystem(3, 2487, 4, "Capital", 2)),
            listOf(org.logh7.engine.GalaxyBase(1049, 2487, 60, 0, "Home", 0, 1f, 1, 0, 0f, 1f)),
            org.logh7.engine.Power.entries.associateWith { 2487 })
        var pending = CompletableFuture<org.logh7.engine.NavigationResult>()
        val requests = mutableListOf<org.logh7.engine.NavigationRequest>()
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
            Handshake(b, 2), characters, gameSeconds = { 123 }, expectedGeneration = 1, connection = "owned",
            world = WorldBootstrap(galaxy), navigation = GameNavigation { requests += it; pending })
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2)); game.accept(0x30, encrypted(hex("0205"), 3))
        val dock = PlaceMessages.Dock(1001, 59, 1049, 4)
        val command = PlaceMessages.docked(dock, 0, Int.MAX_VALUE, Int.MAX_VALUE).copyOfRange(4, 34)
        assertNull(game.accept(0x30, encrypted(command, 4)))
        val operation = assertNotNull(game.takePendingWorld())
        assertFalse(operation.isDone)
        assertEquals(org.logh7.engine.PlaceMove.Dock(1049, 4), requests.last().place)
        assertEquals(1001001, requests.last().unitId)
        assertEquals(59, requests.last().captainCard)
        var player = org.logh7.engine.Navigator(1, 1001, org.logh7.engine.Power.EMPIRE,
            2487, 1000, 840, 1000, 1, 1049, 4)
        pending.complete(org.logh7.engine.NavigationResult(player))
        val messages = operation.join()
        fun opcodes(messages: List<ByteArray>) = messages.map { ByteBuffer.wrap(it, 4, 2).short.toInt() and 65535 }
        assertEquals(listOf(0x0b00, 0x0356, 0x0325, 0x0b0b), opcodes(messages))
        assertEquals(840, ByteBuffer.wrap(messages.first()).getInt(24))
        pending = CompletableFuture()
        val move = PlaceMessages.Move(1001, 10490003, 0)
        assertNull(game.accept(0x30, encrypted(PlaceMessages.moved(move, 0, 0, 0).copyOfRange(4, 30), 5)))
        val moving = assertNotNull(game.takePendingWorld())
        assertFalse(moving.isDone)
        assertEquals(org.logh7.engine.PlaceMove.Spot(10490003, 0), requests.last().place)
        player = player.copy(pcp = 990, spotId = 10490003, moves = 2)
        pending.complete(org.logh7.engine.NavigationResult(player))
        assertEquals(listOf(0x0e00, 0x0356, 0x0325), opcodes(moving.join()))
        assertEquals(990, ByteBuffer.wrap(moving.join().first()).getInt(14))
        pending = CompletableFuture()
        game.accept(0x30, encrypted(PlaceMessages.moved(move.copy(spot = 10490019), 0, 0, 0).copyOfRange(4, 30), 6))
        val rejected = assertNotNull(game.takePendingWorld())
        pending.complete(org.logh7.engine.NavigationResult(null, previous = player,
            rejection = org.logh7.engine.NavigationRejection.ACCESS))
        val refusal = rejected.join()
        assertEquals(listOf(0x0e00, 0x0356, 0x0325, 0x0500), opcodes(refusal))
        val denied = ByteBuffer.wrap(refusal.first())
        assertEquals(1, denied.int)
        assertEquals(player.spotId, denied.getInt(22))
        assertEquals(player.pcp, denied.getInt(14))
        val message = ByteBuffer.wrap(refusal.last()).position(6)
        assertEquals(0x0e00, message.short.toInt())
        assertEquals(0, message.get().toInt())
        assertEquals("[시설 이동] 입장 권한 없음\u0000", CharArray(message.get().toInt()) { message.char }.concatToString())
        assertFalse(message.hasRemaining())
        val departure = ByteBuffer.allocate(32).putShort(0x0b06).putInt(999).putInt(1001).putShort(59)
            .putInt(-1).putInt(-1).putShort(5).put(0).putInt(42).putInt(42).put(0).array()
        pending = CompletableFuture()
        assertNull(game.accept(0x30, encrypted(departure, 7)))
        val departing = assertNotNull(game.takePendingWorld())
        assertFalse(departing.isDone)
        assertEquals(org.logh7.engine.PlaceMove.SwitchMode(5), requests.last().place)
        assertEquals(59, requests.last().captainCard)
        assertEquals("owned", requests.last().connection)
        val inSpace = player.copy(baseId = 0, spotId = 0, spotOwner = 0, mode = 5)
        pending.complete(org.logh7.engine.NavigationResult(inSpace, player))
        assertEquals(listOf(0x0b06, 0x0356, 0x0325, 0x0b0b), opcodes(departing.join()))
        assertEquals(inSpace.pcp, ByteBuffer.wrap(departing.join().first()).getInt(16))
        pending = CompletableFuture()
        game.accept(0x30, encrypted(departure, 8))
        val repeated = assertNotNull(game.takePendingWorld())
        pending.complete(org.logh7.engine.NavigationResult(null, previous = inSpace,
            rejection = org.logh7.engine.NavigationRejection.BASE))
        assertEquals(listOf(0x0b06, 0x0356, 0x0325), opcodes(repeated.join()))
        assertEquals(1, ByteBuffer.wrap(repeated.join().first()).int)
        pending = CompletableFuture()
        game.accept(0x30, encrypted(departure, 9))
        val woundedDeparture = assertNotNull(game.takePendingWorld())
        val wounded = player.copy(spotId = 0, hull = org.logh7.engine.FlagshipHull(3, 100, woundedUntil = 999))
        pending.complete(org.logh7.engine.NavigationResult(null, previous = wounded,
            rejection = org.logh7.engine.NavigationRejection.WOUNDED))
        val woundedMessages = woundedDeparture.join()
        assertEquals(listOf(0x0b06, 0x0356, 0x0325, 0x0f1d), opcodes(woundedMessages))
        val woundedReply = ByteBuffer.wrap(woundedMessages.first())
        assertEquals(1, woundedReply.int)
        assertEquals(wounded.pcp, woundedReply.getInt(16))
        assertEquals(wounded.mcp, woundedReply.getInt(20))
        val notice = woundedMessages.last()
        assertEquals(ChatMessages.Spot(1001,
            "[부상] 출항·승하선 불가"),
            ChatMessages.spot(notice.copyOfRange(4, notice.size)))
        game.close()
    }
    @Test fun goldenCapturedInitialFrameWhenAvailable() {
        val dir = System.getenv("LOGH7_GOLDEN_DIR")
        assumeTrue(!dir.isNullOrBlank(), "LOGH7_GOLDEN_DIR not set")
        val path = Path.of(dir!!).resolve("initial-0034.bin")
        assumeTrue(Files.exists(path), "initial-0034.bin not available")
        val frame = Files.readAllBytes(path)
        require(frame.size >= 4 && (ByteBuffer.wrap(frame).short.toInt() and 65535) == frame.size - 2)
        val (type, data) = Frames.decodePayload(frame.copyOfRange(2, frame.size))
        assertNotNull(Handshake().accept(type, data))
    }
    @Test fun originalCatalogueUsesSessionFramesAndApplicationBelongsToAuthenticatedAccount() {
        val characters = CharacterCreation()
        val tickets = SessionTickets()
        val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903, Handshake(b, 2), characters)
        game.accept(0x34, initial); game.accept(0x36, confirm)
        val token = tickets.issue("user", SessionTickets.Purpose.GAME)
        game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
        game.accept(0x30, encrypted(hex("0200"), 2))
        val request = ByteBuffer.allocate(31).putShort(0x1200).putInt(0).putShort(0x13).array()
        val bytes = game.accept(0x30, encrypted(request, 3))!!
        val opcodes = mutableListOf<Int>(); var offset = 0; var serverSequence = 2L
        while (offset < bytes.size) {
            val length = (ByteBuffer.wrap(bytes, offset, 2).short.toInt() and 65535) + 2
            assertContentEquals(hex("000000000030"), bytes.copyOfRange(offset + 2, offset + 8))
            val (sequence, body) = Envelope.decode(LegacyBlowfish(b).decrypt(bytes.copyOfRange(offset + 8, offset + length)), serverSequence)
            serverSequence = sequence; opcodes += ByteBuffer.wrap(body, 4, 2).short.toInt() and 65535; offset += length
        }
        assertEquals(listOf(0x1200, 0x120f, 0x1201), opcodes)
        game.accept(0x30, encrypted(hex("100601000003e9"), 4))
        assertEquals(listOf(1001L), characters.entries("user"))
        assertTrue(characters.entries("other").isEmpty())
        assertFails { game.accept(0x30, encrypted(hex("0205"), 5)) }
    }
    @Test fun unsupportedNativeMembershipStopsBeforeAnyActorJoinEvenWithoutAnAdmissionAdapter() {
        for(country in listOf(CountryId(42),null,CountryId(3)))for(withAdapter in listOf(true,false)) {
            val c=OriginalCharacterMessages.localCandidates.first {it.character.power==2}.character.copy(id=10000,category=4,generated=true)
            val characters=CharacterCreation().also {assertTrue(it.carry("user",CharacterProfile(c,country,ShipStyleId("alliance"))))}
            var joins=0
            val access=object:GameAdmission {
                override val generation=1L
                override val running=true
                override fun join(account:String,character:CharacterMessages.Generate,connection:String,generation:Long):CompletableFuture<Admission> {
                    joins++;return CompletableFuture.completedFuture(Admission.ACCEPTED)
                }
                override fun join(account:String,profile:CharacterProfile,connection:String,generation:Long):CompletableFuture<Admission> {
                    joins++;return CompletableFuture.completedFuture(Admission.ACCEPTED)
                }
                override fun disconnect(account:String,connection:String) {}
            }
            val tickets=SessionTickets()
            val game=GameExchange(GameExchange.Role.SESSION,emptyMap(),tickets,"127.0.0.1",47903,Handshake(b,2),
                characters=characters,admission=if(withAdapter)access else null).also {it.accept(0x34,initial);it.accept(0x36,confirm)}
            val token=tickets.issue("user",SessionTickets.Purpose.GAME)
            game.accept(0x30,encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30,encrypted(hex("0200"),2))
            val response=assertNotNull(game.accept(0x30,encrypted(hex("0205"),3)))
            val body=Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(8,response.size)),2).second
            assertContentEquals(LoginMessages.sessionRejection(),body)
            assertNull(game.takePendingJoin());assertEquals(0,joins)
            assertEquals(country,characters.profiles().getValue("user").country)
            game.close()
        }
    }
    @Test fun gameLoginWaitsForActorAdmissionAndRejectsBeforeWorldInitialization() {
        for (decision in listOf(Admission.ACCEPTED, Admission.FULL, Admission.ALREADY_ONLINE)) {
            var now = java.time.Instant.EPOCH
            val clock = object : java.time.Clock() {
                override fun getZone() = java.time.ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
                override fun instant() = now
            }
            val characters = CharacterCreation(clock = clock, lotteryWindowMillis = 1)
            characters.apply("user", listOf(1001)); now = now.plusMillis(1)
            val pending = CompletableFuture<Admission>()
            val closed = mutableListOf<String>()
            val access = object : GameAdmission {
                override val generation = 1L
                override val running = true
                override fun join(account: String, character: CharacterMessages.Generate, connection: String, generation: Long): CompletableFuture<Admission> {
                    assertEquals("user", account); assertEquals(1001L, character.id); assertEquals(1L, generation)
                    return pending
                }
                override fun disconnect(account: String, connection: String) { closed += account }
            }
            val tickets = SessionTickets()
            val counters = GatewayCounters()
            val game = GameExchange(GameExchange.Role.SESSION, emptyMap(), tickets, "127.0.0.1", 47903,
                Handshake(b, 2), characters, admission = access, counters = counters)
            game.accept(0x34, initial); game.accept(0x36, confirm)
            val token = tickets.issue("user", SessionTickets.Purpose.GAME)
            game.accept(0x30, encrypted(ByteBuffer.allocate(6).putShort(0x20).putInt(token.toInt()).array()))
            game.accept(0x30, encrypted(hex("0200"), 2))
            assertNull(game.accept(0x30, encrypted(hex("0205"), 3)))
            assertSame(pending, game.takePendingJoin()); assertNull(game.takePendingJoin())
            pending.complete(decision)
            val response = game.finishJoin(decision)!!
            val body = Envelope.decode(LegacyBlowfish(b).decrypt(response.copyOfRange(8, response.size)), 2).second
            val expected = when (decision) {
                Admission.ACCEPTED -> "00000000020600"
                Admission.ALREADY_ONLINE -> "00000000020202010000"
                else -> "00000000020201010000"
            }
            assertContentEquals(hex(expected), body)
            assertEquals(if (decision == Admission.ALREADY_ONLINE) 1L else 0L,
                counters.snapshot()["duplicate_admission_rejected_total"])
            if (decision != Admission.ACCEPTED) assertFails { game.accept(0x30, encrypted(hex("0300"), 4)) }
            game.close(); game.close()
            assertEquals(listOf("user"), closed)
        }
    }
    @Test fun rejectsTruncatedSequenceAndWrongEchoWithValidChecksums() {
        fun sealed(plain: ByteArray): ByteArray {
            ByteBuffer.wrap(plain).putShort(Envelope.checksum(plain.copyOfRange(2, plain.size)).toShort())
            return wrap.encrypt(plain)
        }
        // Key length 2 leaves only two sequence bytes in an aligned eight-byte block.
        assertFails { Handshake().accept(0x34, sealed(hex("0000000201020001"))) }
        val zeroSequence = wrap.decrypt(initial).also { ByteBuffer.wrap(it).putInt(20, 0) }
        assertFails { Handshake().accept(0x34, sealed(zeroSequence)) }
        val wrongEcho = ByteBuffer.allocate(20).putShort(0).putShort(16).put(a).array()
        val h = Handshake(b, 2); h.accept(0x34, initial)
        assertFails { h.accept(0x36, sealed(wrongEcho)) }
        assertEquals(Handshake.State.AWAITING_CONFIRMATION, h.state)
        assertNull(h.accept(0x36, confirm))
    }
    @Test fun loginCarrierRemainsUnsupportedAtRekeyBoundary() {
        val h = Handshake(b, 0x7fffffff); h.accept(0x34, initial); h.accept(0x36, confirm)
        h.send(byteArrayOf(0x70, 1))
        assertFailsWith<IllegalStateException> { h.send(byteArrayOf(0x70, 1)) }
    }
    @Test fun expiredTicketsCannotAuthenticate() {
        var now = java.time.Instant.EPOCH
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        val tickets = SessionTickets(clock); val token = tickets.issue()
        now = now.plusSeconds(60)
        assertFalse(tickets.consume(token))
    }
    @Test fun goldenCompleteExchangeWhenAvailable() {
        val dir = System.getenv("LOGH7_GOLDEN_DIR")
        assumeTrue(!dir.isNullOrBlank(), "LOGH7_GOLDEN_DIR not set")
        val paths = listOf("initial-0034.bin", "reply-0035.bin", "confirm-0036.bin").map { Path.of(dir!!).resolve(it) }
        assumeTrue(paths.all { Files.exists(it) }, "Complete key exchange captures not available")
        val frames = paths.map { Files.readAllBytes(it) }
        fun payload(frame: ByteArray): Pair<Int, ByteArray> {
            require((ByteBuffer.wrap(frame).short.toInt() and 65535) == frame.size - 2)
            return Frames.decodePayload(frame.copyOfRange(2, frame.size))
        }
        val reply = payload(frames[1]); assertEquals(0x35, reply.first)
        val plain = ByteBuffer.wrap(wrap.decrypt(reply.second)); plain.short
        val aLength = plain.short.toInt() and 65535; plain.position(4 + aLength)
        val bLength = plain.short.toInt() and 65535
        val key = ByteArray(bLength).also { plain.get(it) }
        require(plain.remaining() >= 4)
        val h = Handshake(key, plain.int.toLong() and 0xffffffffL)
        val first = payload(frames[0]); assertContentEquals(frames[1], h.accept(first.first, first.second))
        val last = payload(frames[2]); assertNull(h.accept(last.first, last.second))
        assertEquals(Handshake.State.ESTABLISHED, h.state)
    }

    @Test fun capturedLoginStreamsWhenAvailable() {
        val directory = System.getenv("LOGH7_CAPTURED_LOGIN_DIR")
        assumeTrue(!directory.isNullOrBlank(), "LOGH7_CAPTURED_LOGIN_DIR not set")
        val root = Path.of(directory!!)
        val clients = Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".client-to-server.bin") }.sorted().toList()
        }
        assertTrue(clients.size >= 3, "At least three independent captured login streams required")
        fun frames(path: Path): List<Pair<Int, ByteArray>> {
            val stream = ByteBuffer.wrap(Files.readAllBytes(path))
            val result = mutableListOf<Pair<Int, ByteArray>>()
            while (stream.hasRemaining()) {
                assertTrue(stream.remaining() >= 2, "Truncated length: $path")
                val length = stream.short.toInt() and 65535
                assertTrue(length in 2..Frames.MAX_PAYLOAD && length <= stream.remaining(), "Invalid frame: $path")
                val payload = ByteArray(length).also { stream.get(it) }
                val decoded = Frames.decodePayload(payload)
                assertContentEquals(byteArrayOf((length ushr 8).toByte(), length.toByte()) + payload,
                    Frames.encode(decoded.first, decoded.second))
                result += decoded
            }
            return result
        }
        clients.forEach { clientPath ->
            val serverPath = root.resolve(clientPath.fileName.toString().replace(".client-to-server.bin", ".server-to-client.bin"))
            val client = frames(clientPath); val server = frames(serverPath)
            assertEquals(listOf(0x34, 0x36, 0x30), client.map { it.first })
            assertEquals(listOf(0x35, 0x30), server.map { it.first })
            val reply = ByteBuffer.wrap(wrap.decrypt(server[0].second))
            reply.short
            val aLength = reply.short.toInt() and 65535
            reply.position(4 + aLength)
            val bLength = reply.short.toInt() and 65535
            val key = ByteArray(bLength).also { reply.get(it) }
            val sequence = reply.int.toLong() and 0xffffffffL
            val h = Handshake(key, sequence)
            assertContentEquals(Frames.encode(server[0].first, server[0].second), h.accept(client[0].first, client[0].second))
            assertNull(h.accept(client[1].first, client[1].second))
            assertEquals(Handshake.State.ESTABLISHED, h.state)
            val login = assertNotNull(h.receive(client[2].first, client[2].second))
            assertEquals(LoginMessages.Request("ginei00", "dummy"), LoginMessages.request(login))
            // Synthetic test credentials only; retain the decoded body in the local test output.
            println("${clientPath.fileName}: login=${java.util.HexFormat.of().formatHex(login)}")
            val (replySequence, body) = Envelope.decode(LegacyBlowfish(key).decrypt(server[1].second), sequence - 1)
            assertEquals(sequence, replySequence)
            assertContentEquals(LoginMessages.failure(), body)
            assertContentEquals(Frames.encode(server[1].first, server[1].second), h.send(body))
        }
    }
}
