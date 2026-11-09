package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
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
    private fun seed(path:Path) {
        Files.createDirectories(path);val store=CharacterCreation(path.resolve("characters.properties"));val ship=checkNotNull(Flagships.find(3))
        for(i in 0..1) {
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
