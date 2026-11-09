package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.LogisticsMessages
import org.logh7.protocol.MailMessages as Wire
import kotlin.test.*

/** 실제 암호화 명령→직무 우편→재시작→원본 회신의 권한과 분담 비용. 작성: Peppone Choi. */
class ProposalActionsWireTest {
    @TempDir lateinit var directory:Path
    private val now=SessionRules.load().startSeconds
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private inner class State:WorldStateCommitter {
        var players=(0..1).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,2487,1000,if(i==0)0 else 1000,1000,
            baseId=1049,mode=1,spotId=10490001,cpRecoveryAt=now)}
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300)),deploymentInitialized=true,
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=2,influence=10),10001 to StrategicPerson(rank=9,influence=10)),
                offices=listOf(OfficeHolding(17,1049,10000),OfficeHolding(19,1049,10001))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun seed(path:Path,count:Int=2) {
        Files.createDirectories(path);val store=CharacterCreation(path.resolve("characters.properties"));val ship=checkNotNull(Flagships.find(3))
        for(i in 0 until count) {
            val data=CharacterMessages.Generate(4,(10000+i).toLong(),2,0,0,"Pilot$i","Test",30,1,1,1000001,List(8){50},
                0,0,0,if(i==0)2 else 9,ship.type,ship.kind,"Ship",1)
            assertTrue(store.carry("load-%03d".format(i),CharacterProfile(data,CountryId(2),ShipStyleId("empire"))))
        }
    }
    private fun body(packet:ByteArray)=packet.copyOfRange(4,packet.size)
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val p=client.receive();if(ByteBuffer.wrap(p).getShort(4).toInt()==opcode)return p};error("Missing $opcode")
    }
    private fun query(client:SyntheticClient,id:Int,card:Int)=client.send(body(Wire.begin(Wire.Query(Wire.Peer(id,extra=listOf(0,0,card)),true,true))))
    @Test fun offlineOfficeSummaryOnlyCountsCurrentPendingMailAfterTheFirstReadyClock() {
        val state = State()
        fun row(id: Int, recipient: Int = 10001, card: Int = 19, order: Boolean = false,
            country: CountryId = Power.EMPIRE.country, reply: Int = -1, expiresAt: Long = now + 86400) =
            ProposalMail(id, 10002, recipient, 17, card, ProposalActions.RESIGNATION, order,
                now, expiresAt, reply = reply, country = country)
        val rows = listOf(row(1), row(2, order = true), row(3, card = 17), row(4, recipient = 10000, card = 17),
            row(5, country = Power.ALLIANCE.country), row(6, reply = Proposals.APPROVE), row(7, expiresAt = now))
        state.stocks = state.stocks.copy(
            social = state.stocks.social.copy(nextId = 8, proposals = ProposalState(rows)),
            tacticalCommands = state.stocks.tacticalCommands.copy(aiSwitches = mapOf(10000 to 23, 10001 to 8)))
        seed(directory)
        LoadFixture(directory, 2, LoadMetrics(), galaxy, ticking = false, navigationStore = state.navigation,
            stockStore = state.inventory, worldStateCommitter = state).use { fixture ->
            fun summaryUntil(client: SyntheticClient, end: Int): List<org.logh7.protocol.ChatMessages.Spot> {
                val notices = mutableListOf<org.logh7.protocol.ChatMessages.Spot>()
                repeat(200) {
                    val message = client.receive()
                    when (ByteBuffer.wrap(message).getShort(4).toInt()) {
                        end -> return notices
                        0x0f1d -> {
                            val notice = org.logh7.protocol.ChatMessages.spot(body(message))
                            if (notice.text.startsWith("[직무 편지] 미처리")) notices += notice
                        }
                    }
                }
                error("Missing $end")
            }
            fun clock(client: SyntheticClient) = client.send(SyntheticClient.opcode(0x0300))
            for (i in listOf(1, 0)) {
                val client = fixture.join(fixture.account(i), false, false).client
                client.send(SyntheticClient.opcode(0x0f00)); next(client, 0x0f01)
                clock(client); assertTrue(summaryUntil(client, 0x0301).isEmpty())
                client.send(SyntheticClient.opcode(0x0f02)); assertTrue(summaryUntil(client, 0x0f03).isEmpty())
                val previousMail = state.stocks.social.proposals
                val previousOffices = state.stocks.strategic.offices
                val previousBalance = state.players.getValue("load-%03d".format(i)).mcp
                clock(client); assertTrue(summaryUntil(client, 0x0301).isEmpty())
                clock(client)
                val expected = if (i == 1) listOf(org.logh7.protocol.ChatMessages.Spot(10001, ProposalNotice.pending(2))) else emptyList()
                assertEquals(expected, summaryUntil(client, 0x0301))
                clock(client); assertTrue(summaryUntil(client, 0x0301).isEmpty())
                assertEquals(previousMail, state.stocks.social.proposals)
                assertEquals(previousOffices, state.stocks.strategic.offices)
                assertEquals(previousBalance, state.players.getValue("load-%03d".format(i)).mcp)
            }
        }
    }

    @Test fun nativeResignationApprovalRefreshesTheOpenBattlefield() = officeRefresh(0x0f14)
    @Test fun compatibilityResignationApprovalRefreshesTheOpenBattlefield() = officeRefresh(0x1200)
    @Test fun absentResignationApprovalRefreshesTheOpenBattlefield() = officeRefresh(null)
    @Test fun transportAppointmentApprovalRefreshesTheOpenBattlefield() = officeRefresh(0x0f14, appointment = true)

    @Test fun markedAssignmentOnlyMovesStockOnApprovalAndDeduplicatesRetries() {
        val state=State()
        val stack=UnitStack(32)
        val stock=state.stocks.bases.getValue(1049).copy(units=UnitInventory(mapOf(stack to 1)))
        state.players=state.players.mapValues {(_,player)->player.copy(mcp=1000)}
        state.stocks=state.stocks.copy(bases=mapOf(1049 to stock),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,10,commanderId=10001)),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1)),
            strategic=state.stocks.strategic.copy(offices=listOf(OfficeHolding(25,1049,10000),OfficeHolding(24,1049,10001))))
        seed(directory)
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {fixture->
            val sender=fixture.join(fixture.account(0),false,false).client
            sender.send(SyntheticClient.opcode(0x0f00));next(sender,0x0f01)
            val executor=fixture.join(fixture.account(1),false,false).client
            executor.send(SyntheticClient.opcode(0x0f00));next(executor,0x0f01)
            val baseBefore=state.stocks.bases.getValue(1049)
            val warehouseBefore=state.stocks.warehouses.getValue(10)
            val token=70001
            fun draft(phase:Int,nonce:Int=token) = ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
                .putShort(0x7f00).putShort(98).putShort(0).putShort(0x0c0b).putInt(10001).put(phase.toByte())
                .putInt(nonce).putShort(0).putInt(0).putShort(71).array()
            fun warehouseStatus():Int {
                sender.send(ByteBuffer.allocate(10).putShort(0x0326).putInt(1049).putInt(1).array())
                return ByteBuffer.wrap(next(sender,0x0327)).getInt(14)
            }
            fun outfits(card:Int):Int {
                val wire=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(31).putShort(124)
                    .putInt(25,1049).putShort(29,card.toShort()).array()
                sender.send(wire)
                return next(sender,0x1203)[6].toInt()
            }
            assertEquals(1,warehouseStatus())
            sender.send(draft(0))
            val grant=ByteBuffer.wrap(next(sender,0x1200))
            assertEquals(0,grant.getShort(16).toInt())
            assertEquals(2,grant.get(22).toInt())
            assertEquals(token,grant.getInt(23))
            assertEquals(24,grant.getShort(27).toInt())
            assertEquals(71,grant.getShort(33).toInt())
            next(sender,0x1201)
            assertEquals(1,outfits(24))
            assertEquals(0,outfits(25))
            sender.send(draft(1,token+1))
            assertEquals(0,warehouseStatus())
            sender.send(draft(1))
            assertEquals(1,warehouseStatus())
            assertEquals(0,outfits(24))
            assertEquals(1000,state.players.getValue("load-000").mcp)
            assertTrue(state.stocks.social.proposals.mails.isEmpty())
            val offer=LogisticsMessages.AssignmentProposal(10000,10001,token,1049,1,0,
                LogisticsMessages.Moves(listOf(LogisticsMessages.ShipMove(32,1,0)),emptyList()),100)
            fun submit(value:LogisticsMessages.AssignmentProposal):Int {
                sender.send(LogisticsMessages.encode(value))
                val response=ByteBuffer.wrap(next(sender,0x1200))
                assertEquals(value.token,response.getInt(23))
                next(sender,0x1201);next(sender,0x0356)
                return response.getShort(16).toInt()
            }
            assertEquals(0,submit(offer))
            assertEquals(0,submit(offer))
            assertNotEquals(0,submit(offer.copy(moveSupplies=99)))
            val mail=state.stocks.social.proposals.mails.single()
            val baseAfterSend=state.stocks.bases.getValue(1049)
            assertEquals(baseBefore.units,baseAfterSend.units)
            assertEquals(baseBefore.supplies,baseAfterSend.supplies)
            assertEquals(warehouseBefore,state.stocks.warehouses.getValue(10))
            assertEquals(920,state.players.getValue("load-000").mcp)
            assertEquals(9,state.stocks.strategic.people.getValue(10000).influence)
            query(executor,10001,24)
            next(executor,0x0f13)
            val pair=LogisticsMessages.assignment(body(next(executor,0x0c0b)))
            assertEquals(0,pair.header.time)
            assertEquals(offer.moves,pair.moves)
            assertEquals(100,pair.moveSupplies)
            next(executor,0x0f09)
            val answer=body(Wire.reply(Wire.Reply(0,mail.id,0,Wire.Peer(10001),Wire.Peer(10000),0,0,0,Proposals.APPROVE)))
            executor.send(answer);next(executor,0x0356)
            repeat(2) {next(executor,0x0327)}
            executor.send(answer);next(executor,0x0356)
            assertEquals(920,state.players.getValue("load-001").mcp)
            assertEquals(0,state.stocks.bases.getValue(1049).units.ships.size)
            assertEquals(mapOf(stack to 1),state.stocks.warehouses.getValue(10).units.ships)
            assertEquals(100,state.stocks.warehouses.getValue(10).supplies)
            assertEquals(0,submit(offer))
            assertEquals(920,state.players.getValue("load-000").mcp)
        }
    }

    private fun officeRefresh(replyOpcode: Int?, appointment: Boolean = false) {
        val state = State()
        val world = Galaxy(galaxy.systems + GalaxySystem(4, 2015, 4, "Battle", 3),
            galaxy.bases + GalaxyBase(1177, 2015, 80, 0, Capital.ALLIANCE.baseName, 0, 20f, 86400, 0, 0f, 2f),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val viewerAccount = "load-000"
        state.players = state.players + (viewerAccount to state.players.getValue(viewerAccount)
            .copy(grid = 2015, baseId = 0, mode = 5, spotId = 0))
        val kind = if (appointment) OutfitKind.TRANSPORT else OutfitKind.FLEET
        val friendly = OutfitStock(1, 2, kind, 1049, 10, commanderId = if (appointment) 0 else 10000,
            aideId = if (appointment) 0 else 10001,
            units = UnitInventory(mapOf(UnitStack(32) to 1)), position = OutfitPosition(2015))
        val hostile = OutfitStock(2, 3, OutfitKind.FLEET, 1177, 11, commanderId = 500,
            units = UnitInventory(mapOf(UnitStack(119) to 1)), position = OutfitPosition(2015))
        val proposals = if (appointment) {
            val role = checkNotNull(OrganizationOffices.of(44))
            state.stocks = state.stocks.copy(strategic = state.stocks.strategic.copy(
                offices = listOf(OfficeHolding(checkNotNull(role.authority), 1049, 10001)),
                people = mapOf(10000 to StrategicPerson(rank = 5), 10001 to StrategicPerson(rank = 2))))
            Proposals.open(ProposalState(), 1, 10000, 10001, 17, checkNotNull(role.authority),
                ProposalActions.APPOINTMENT, order = false, now = now, pcp = 0, mcp = 80, country = CountryId(2),
                target = 10000, targetCard = 44, seat = 1).first
        } else Proposals.open(ProposalState(), 1, 10000, 10001, 39, 43, ProposalActions.RESIGNATION,
            order = true, now = now, pcp = 0, mcp = 40, country = CountryId(2)).first
        state.stocks = ActiveShips.initialize(state.stocks.copy(
            bases = state.stocks.bases + (1177 to PlanetStock(1177, 3, 0, 100, 200, 300)),
            outfits = mapOf(1 to friendly, 2 to hostile),
            warehouses = mapOf(10 to OutfitWarehouse(10, 2, kind, 1049, 1),
                11 to OutfitWarehouse(11, 3, OutfitKind.FLEET, 1177, 2)),
            social = state.stocks.social.copy(nextId = 2, proposals = proposals)))
        val ship = state.stocks.outfits.getValue(1).activeShips!!.values.single()
        state.stocks = state.stocks.copy(tacticalCommands = TacticalCommandState(authority = mapOf(ship.id to 10001)))
        seed(directory)
        LoadFixture(directory, 2, LoadMetrics(), world, ticking = false, navigationStore = state.navigation,
            stockStore = state.inventory, worldStateCommitter = state).use { fixture ->
            val viewer = fixture.join(fixture.account(0), false, false).client
            viewer.send(SyntheticClient.opcode(0x0f00)); next(viewer, 0x0f01)
            assertTrue(state.players.getValue(viewerAccount).cpPaused)
            viewer.send(SyntheticClient.opcode(0x0f02))
            fun commander(packet: ByteArray): Int {
                val states = ByteBuffer.wrap(packet)
                val row = (0 until states.getShort(6).toInt()).single { states.getInt(8 + it * 47) == ship.id }
                return states.getInt(14 + row * 47)
            }
            assertEquals(10001, commander(next(viewer, 0x033b)))
            next(viewer, 0x0f03)

            if (replyOpcode == null) {
                val delay = ProposalActions.ABSENT_ORDER_SECONDS * 1000 / SessionRules.load().speed
                runBlocking { fixture.engine.submit(WorldCommand.AdvanceTo(delay)) }
            } else {
                val aide = fixture.join(fixture.account(1), false, false).client
                aide.send(SyntheticClient.opcode(0x0f00)); next(aide, 0x0f01)
                if (replyOpcode == 0x0f14) {
                    aide.send(body(Wire.reply(Wire.Reply(0, 1, 0, Wire.Peer(10001), Wire.Peer(10000),
                        0, 0, 0, Proposals.APPROVE))))
                    next(aide, 0x0356)
                } else {
                    aide.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10001).putShort(0x7f00)
                        .putShort(99).putShort(0).putShort(Proposals.APPROVE.toShort()).putInt(1).put(0)
                        .putInt(0).putShort(0).putInt(0).putShort(17).array())
                    assertEquals(0, ByteBuffer.wrap(next(aide, 0x1200)).getShort(16).toInt())
                }
            }
            assertEquals(10000, commander(next(viewer, 0x033b)))
            assertEquals(Proposals.APPROVE, state.stocks.social.proposals.mails.single().reply)
            assertEquals(0, state.stocks.outfits.getValue(1).aideId)
            assertEquals(10000, state.stocks.outfits.getValue(1).commanderId)
            assertNull(state.stocks.tacticalCommands.authority[ship.id])
            assertEquals(if (appointment) 920 else 960, state.players.getValue("load-001").mcp)
        }
    }

    @Test fun demotionMailListsAnEligibleTargetAndNativeApprovalSplitsTheCostOnce() =
        rankChangeMail(ProposalActions.DEMOTION)

    @Test fun specialPromotionMailUpgradesTheTargetsFlagshipInTheApprovalCommit() =
        rankChangeMail(ProposalActions.SPECIAL_PROMOTION)

    private fun rankChangeMail(command:Int) {
        val promoting=command==ProposalActions.SPECIAL_PROMOTION
        val oldRank=if(promoting)11 else 9
        val newRank=10
        val mailRank=if(promoting)newRank else oldRank
        val half=if(promoting)320 else 160
        val state=State()
        val target=10002
        val destroyer=Flagships.forType(2,3).kind
        state.players=state.players.mapValues {(_,p)->p.copy(mcp=1000)}+
            ("load-002" to state.players.getValue("load-001").copy(characterId=target,hull=FlagshipHull(destroyer,now)))
        state.stocks=state.stocks.copy(strategic=state.stocks.strategic.copy(
            people=state.stocks.strategic.people+mapOf(
                10001 to StrategicPerson(rank=oldRank,merit=100), target to StrategicPerson(rank=oldRank)),
            offices=state.stocks.strategic.offices+OfficeHolding(18,1049,target)))
        seed(directory,3)
        LoadFixture(directory,3,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {fixture->
            val sender=fixture.join(fixture.account(0),false,false).client
            sender.send(SyntheticClient.opcode(0x0f00));next(sender,0x0f01)
            val executor=fixture.join(fixture.account(1),false,false).client
            executor.send(SyntheticClient.opcode(0x0f00));next(executor,0x0f01)
            fun send(preview:Boolean,target:Int,base:Int) {
                sender.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00)
                    .putShort(98).putShort(0).putShort(command.toShort()).putInt(target)
                    .put(if(preview)1 else 0).putInt(0).putShort(0).putInt(base).putShort(0).array())
            }
            send(true,0,10001)
            val rows=ByteBuffer.wrap(next(sender,0x1202))
            assertEquals(1,rows.get(6).toInt())
            assertEquals(target,rows.getInt(7))
            next(sender,0x1201)
            send(false,target,10001)
            assertNotEquals(0,ByteBuffer.wrap(next(sender,0x1200)).getShort(16).toInt())
            next(sender,0x1201);next(sender,0x0356)
            assertTrue(state.stocks.social.proposals.mails.isEmpty())
            send(false,10001,target)
            assertEquals(0,ByteBuffer.wrap(next(sender,0x1200)).getShort(16).toInt())
            next(sender,0x1201);next(sender,0x0356)
            val mail=state.stocks.social.proposals.mails.single()
            assertEquals(target,mail.target)
            assertEquals(1000-half,state.players.getValue("load-000").mcp)
            assertEquals(9,state.stocks.strategic.people.getValue(10000).influence)
            assertEquals(oldRank,state.stocks.strategic.people.getValue(target).rank)
            assertEquals(destroyer,state.players.getValue("load-002").hull?.kind)
            query(executor,10001,19)
            next(executor,0x0f13)
            fun assertPair(packet:ByteArray) {
                val pair=ByteBuffer.wrap(packet)
                assertEquals(0,pair.getInt(6));assertEquals(10001,pair.getInt(10))
                assertEquals(half,pair.getInt(18))
                assertEquals(mailRank,pair.get(if(promoting)26 else 22).toInt())
                assertEquals(target,pair.getInt(if(promoting)22 else 23))
                assertEquals(if(promoting)38 else 40,pair.limit())
                assertEquals(0,pair.get(pair.limit()-1).toInt())
                if(promoting)assertEquals(0,pair.getShort(31).toInt())
            }
            assertPair(next(executor,command))
            next(executor,0x0f09)

            val answer=body(Wire.reply(Wire.Reply(0,mail.id,0,Wire.Peer(10001),Wire.Peer(10000),0,0,0,Proposals.APPROVE)))
            repeat(2) {executor.send(answer);next(executor,0x0356)}
            assertEquals(newRank,state.stocks.strategic.people.getValue(target).rank)
            assertTrue(state.stocks.strategic.offices.none {it.character==target})
            assertEquals(1000-half,state.players.getValue("load-001").mcp)
            assertEquals(if(promoting)Flagships.rankDefault(2,newRank).kind else destroyer,state.players.getValue("load-002").hull?.kind)
            assertEquals(Proposals.APPROVE,state.stocks.social.proposals.mails.single().reply)
            assertEquals(mailRank,state.stocks.social.proposals.mails.single().rankChangeMailRank)
            query(sender,10000,17)
            next(sender,0x0f14)
            assertPair(next(sender,command))
            next(sender,0x0f09)
        }
    }

    @Test fun nativeAppointmentApprovalRefreshesTheOnlineProposersCards() {
        val state = State()
        state.stocks = state.stocks.copy(strategic = state.stocks.strategic.copy(
            people = state.stocks.strategic.people + (10001 to StrategicPerson(rank = 4, influence = 10))))
        val (card, seat) = ProposalActions.vacancies(galaxy, state.stocks, 10000, 2, 2, 10001, 4).first()
        seed(directory)
        LoadFixture(directory, 2, LoadMetrics(), galaxy, ticking = false, navigationStore = state.navigation,
            stockStore = state.inventory, worldStateCommitter = state).use { fixture ->
            val approver = fixture.join(fixture.account(0), false, false).client
            approver.send(SyntheticClient.opcode(0x0f00)); next(approver, 0x0f01)
            val proposer = fixture.join(fixture.account(1), false, false).client
            proposer.send(SyntheticClient.opcode(0x0f00)); next(proposer, 0x0f01)
            proposer.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10001).putShort(0x7f00).putShort(98).putShort(19)
                .putShort(ProposalActions.APPOINTMENT.toShort()).putInt(10000).put(0).putInt(17)
                .putShort(card.toShort()).putInt(seat).putShort(0).array())
            assertEquals(0, ByteBuffer.wrap(next(proposer, 0x1200)).getShort(16).toInt())
            next(proposer, 0x1201); next(proposer, 0x0356)
            val mail = state.stocks.social.proposals.mails.single()
            assertEquals(10001, mail.target)

            val answer = body(Wire.reply(Wire.Reply(0, mail.id, 0, Wire.Peer(10000), Wire.Peer(10001), 0, 0, 0, Proposals.APPROVE)))
            approver.send(answer)
            next(approver, 0x0356)
            val refreshed = ByteBuffer.wrap(next(proposer, 0x0356))
            assertEquals(10001, refreshed.getInt(7))
            val office = OrganizationalOfficePolicy(galaxy)
            val wireTarget = office.target(assertNotNull(office.role(card, CountryId(2))), seat, state.stocks)
            assertTrue(listOf(refreshed.limit() - 7, refreshed.limit() - 13).any { offset ->
                refreshed.getShort(offset).toInt() == card && refreshed.getInt(offset + 2) == wireTarget
            })
            assertEquals(Proposals.APPROVE, state.stocks.social.proposals.mails.single().reply)
            assertEquals(840, state.players.getValue("load-000").pcp)
            assertEquals(920, state.players.getValue("load-001").mcp)
        }
    }

    @Test fun anOfficeOrderPaidWithPcpSurvivesRestartAndItsNativeReplyChargesTheRecipientHalfOnce() {
        val state=State()
        fun server(name:String):LoadFixture {
            val path=directory.resolve(name);seed(path)
            return LoadFixture(path,2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
                stockStore=state.inventory,worldStateCommitter=state)
        }
        server("first").use {fixture->
            val sender=fixture.join(fixture.account(0),false,false).client;sender.send(SyntheticClient.opcode(0x0f00));next(sender,0x0f01)
            val recipient=fixture.join(fixture.account(1),false,false).client;recipient.send(SyntheticClient.opcode(0x0f00));next(recipient,0x0f01)
            sender.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(98).putShort(17)
                .putShort(ProposalActions.RESIGNATION.toShort()).putInt(10001).put(0).putInt(19).putShort(0).putInt(0).putShort(0).array())
            assertEquals(0,ByteBuffer.wrap(next(sender,0x1200)).getShort(16).toInt());next(sender,0x1201);next(sender,0x0356)
            assertEquals(920,state.players.getValue("load-000").pcp);assertEquals(0,state.players.getValue("load-000").mcp)
            val mail=state.stocks.social.proposals.mails.single();assertEquals(CountryId(2),mail.country);assertEquals(80,mail.pcp);assertEquals(0,mail.mcp)
            query(recipient,10001,19)
            val row=next(recipient,0x0f13);assertEquals(1,ByteBuffer.wrap(row).getInt(6))
            assertEquals(80,ByteBuffer.wrap(row).getInt(row.size-8));assertEquals(0,ByteBuffer.wrap(row).getInt(row.size-4))
            assertEquals(40,ByteBuffer.wrap(next(recipient,0x0709)).getInt(18));next(recipient,0x0f09)
        }
        server("restart").use {fixture->
            val sender=fixture.join(fixture.account(0),false,false).client;sender.send(SyntheticClient.opcode(0x0f00));next(sender,0x0f01)
            val recipient=fixture.join(fixture.account(1),false,false).client;recipient.send(SyntheticClient.opcode(0x0f00));next(recipient,0x0f01)
            val answer=body(Wire.reply(Wire.Reply(0,1,0,Wire.Peer(10001),Wire.Peer(10000),0,0,0,Proposals.APPROVE)))
            val approval=ByteBuffer.allocate(31).putShort(0x1200).putInt(10001).putShort(0x7f00).putShort(99).putShort(0)
                .putShort(3).putInt(1).put(0).putInt(0).putShort(0).putInt(0).putShort(17).array()
            fun approvalResult():ByteBuffer {
                recipient.send(approval)
                val result=ByteBuffer.wrap(next(recipient,0x1200))
                next(recipient,0x1201);next(recipient,0x0356)
                assertEquals(1,result.getInt(18));assertEquals(17,result.getShort(33).toInt())
                return result
            }
            val accepted=approvalResult()
            assertEquals(0,accepted.getShort(16).toInt());assertEquals(2,accepted.get(22).toInt())
            assertEquals(960,state.players.getValue("load-001").mcp)
            assertTrue(OrganizationalOfficePolicy(galaxy).holders(state.stocks,19,CountryId(2)).isEmpty())
            assertEquals(Proposals.APPROVE,state.stocks.social.proposals.mails.single().reply)
            val duplicate=approvalResult()
            assertNotEquals(0,duplicate.getShort(16).toInt());assertEquals(3,duplicate.get(22).toInt())
            recipient.send(answer);next(recipient,0x0356);assertEquals(960,state.players.getValue("load-001").mcp)
            query(sender,10000,17)
            val reply=next(sender,0x0f14);assertEquals(1,ByteBuffer.wrap(reply).getInt(10))
            next(sender,0x0709);next(sender,0x0f09)
            assertEquals(920,state.players.getValue("load-000").pcp);assertEquals(0,state.players.getValue("load-000").mcp)
        }
    }
}
