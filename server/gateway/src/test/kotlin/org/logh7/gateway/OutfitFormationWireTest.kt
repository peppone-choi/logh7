package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** 암호화 편집→확정→관찰자 목록/카드→해산→재시작. 작성: Peppone Choi. 실행은 리드 담당. */
class OutfitFormationWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val ship=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 &&
        UnitSpecifications.ships.single {s->s.kind==it.kind}.category==3}.kind,2)
    private val crew=UnitStack(UnitSpecifications.crews.first {it.power==2 && it.type==3}.kind)
    private inner class State:WorldStateCommitter {
        var saves=0
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
            cpRecoveryAt=SessionRules.load().startSeconds),"load-001" to Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,2000,200,300,PlanetEconomy(1000,600,70,80),
            UnitInventory(mapOf(ship to 2),mapOf(crew to 1000)),spareShips=SpareShipBoats(mapOf(ship to 137)))),
            deploymentInitialized=true,strategic=StrategicState(
                offices=listOf(OfficeHolding(24,1049,10000)),people=mapOf(10000 to StrategicPerson(rank=OrganizationOffices.of(24)!!.ranks.first()))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){saves++;players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(command:Int=25,phase:Int=0,target:Int=0,unit:Int=0,amount:Int=0)=ByteBuffer.allocate(31)
        .putShort(0x1200).putInt(10000).putShort(0x7f01).putShort(command.toShort()).putShort(24).putShort(0)
        .putInt(target).put(phase.toByte()).putInt(unit).putShort(amount.toShort()).putInt(1049).putShort(37).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0,directory:Boolean=false):ByteArray {
        client.send(body);val result=next(client,0x1200)
        assertEquals(status,ByteBuffer.wrap(result).getShort(16).toInt())
        if(directory) {
            val rows=next(client,0x1202)
            if(rows[6].toInt() and 255>0) {
                val length=rows[11].toInt() and 255
                val name=String(rows,12,(length-1)*2,Charsets.UTF_16BE)
                assertTrue(name.any {it in '\uac00'..'\ud7a3'},name)
                assertTrue(name.none {it in '\u3040'..'\u9fff'},name)
            }
        }
        next(client,0x1201);next(client,0x0356);return result
    }
    @Test fun encryptedFormationAndDissolutionUpdateObserversAndRestoredIdsWithoutDuplicateCharges() {
        val state=State();var firstId=0
        LoadFixture(directory.resolve("first"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            val observer=server.join(server.account(1),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            val before=state.stocks;val saves=state.saves
            val lease=ByteBuffer.wrap(action(actor,request(),directory=true)).getInt(18);assertTrue(lease>0)
            val row=OutfitFormationPolicy.code(ship,false)
            action(actor,request(phase=1,target=lease,unit=row,amount=3),directory=true)
            action(actor,request(phase=1,target=lease,unit=row,amount=3),directory=true)
            action(actor,request(phase=2,target=lease,unit=100),directory=true)
            assertEquals(saves,state.saves);assertEquals(before,state.stocks);assertEquals(1000,state.players.getValue("load-000").mcp)
            val formed=action(actor,request(phase=3,target=lease));firstId=ByteBuffer.wrap(formed).getInt(18)
            assertEquals(2,formed[22].toInt());assertTrue(firstId>0);assertEquals(680,state.players.getValue("load-000").mcp)
            val outfit=state.stocks.outfits.getValue(firstId);assertEquals(listOf(300,300,137),outfit.activeShips!!.values.map {it.boats})
            assertEquals(100,outfit.supplies);assertEquals(0,outfit.commanderId)
            var seenDefinition=false
            repeat(20){if(!seenDefinition){val packet=next(observer,0x032b)
                seenDefinition=(packet[6].toInt() and 255)>0 && ByteBuffer.wrap(packet).getInt(7)==firstId}}
            assertTrue(seenDefinition)
            var seenGrid=false
            repeat(20){if(!seenGrid)seenGrid=ByteBuffer.wrap(next(observer,0x032d)).getShort(6).toInt()==1}
            assertTrue(seenGrid)
            val commits=state.saves;action(actor,request(phase=3,target=lease),StrategyActionRejection.TARGET.ordinal+1)
            assertEquals(commits,state.saves);assertEquals(680,state.players.getValue("load-000").mcp)
        }
        // Fixture for the next server start; formation itself did not appoint the observer.
        state.stocks=state.stocks.copy(outfits=state.stocks.outfits+(firstId to state.stocks.outfits.getValue(firstId).copy(commanderId=10001)))
        LoadFixture(directory.resolve("restart"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            val observer=server.join(server.account(1),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            assertEquals(680,state.players.getValue("load-000").mcp);assertEquals(OutfitSequence(2,2),state.stocks.outfitSequence)
            action(actor,request(26),directory=true)
            action(actor,request(26,6,firstId))
            assertEquals(520,state.players.getValue("load-000").mcp);assertTrue(state.stocks.outfits.isEmpty())
            assertEquals(2,state.stocks.bases.getValue(1049).units.ships[ship]);assertEquals(137,state.stocks.bases.getValue(1049).spareShips.values[ship])
            assertEquals(1000,state.stocks.bases.getValue(1049).units.troops[crew]);assertEquals(2000,state.stocks.bases.getValue(1049).supplies)
            var cleared=false
            repeat(20){if(!cleared)cleared=ByteBuffer.wrap(next(observer,0x032d)).getShort(6).toInt()==0}
            assertTrue(cleared)
            // A stale open roster/stock window after deletion receives empty records, not a disconnect.
            actor.send(ByteBuffer.allocate(11).putShort(0x032e).putInt(firstId).putInt(1049).put(0).array())
            next(actor,0x032f)
            val commits=state.saves;action(actor,request(26,6,firstId),StrategyActionRejection.TARGET.ordinal+1)
            assertEquals(commits,state.saves)
            val lease=ByteBuffer.wrap(action(actor,request(),directory=true)).getInt(18)
            action(actor,request(phase=1,target=lease,unit=OutfitFormationPolicy.code(ship,false),amount=1),directory=true)
            val second=ByteBuffer.wrap(action(actor,request(phase=3,target=lease))).getInt(18);assertTrue(second>firstId)
        }
        LoadFixture(directory.resolve("third"),2,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            assertEquals(200,state.players.getValue("load-000").mcp);assertEquals(3,state.stocks.outfitSequence.nextOutfit)
            assertTrue(state.stocks.outfits.keys.single()>firstId)
        }
    }
}
