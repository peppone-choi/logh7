package org.logh7.gateway

import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** 실제 암호화 봉투→저장·두 접속 조건 갱신·취소/이탈·서버 재시작. 작성: Peppone Choi. 실행은 리드 담당. */
class CivicActionsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2200,4,"Foreign",3)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2200,80,0,"Foreign",0,30f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2200))
    private val now=SessionRules.load().startSeconds
    private val facility=galaxy.facilities.getValue(1049).first {it.spots.any {s->s.access==SpotAccess.GUARDED} &&
        it.spots.any {s->s.access==SpotAccess.PUBLIC}}
    private val guarded=facility.spots.first {it.access==SpotAccess.GUARDED}.id
    private val publicSpot=facility.spots.first {it.access==SpotAccess.PUBLIC}.id
    private val ship=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 &&
        UnitSpecifications.ships.single {s->s.kind==it.kind}.category==3}.kind)
    private val troop=UnitStack(UnitSpecifications.crews.first {it.power==2 && it.type!=3}.kind)
    private inner class State(val base:Int=1049):WorldStateCommitter {
        private val grid=galaxy.bases.single {it.id==base}.grid
        var players=(0..1).associate {i->"load-%03d".format(i) to Navigator(1,10000+i,Power.EMPIRE,grid,1000,1000,1000,
            baseId=base,mode=if(base==1049)1 else 4,spotId=if(base==1049)guarded else 0,cpRecoveryAt=now)}
        private val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,ship,supplies=100)))
        private val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(2,troop,base=base)))
        var stocks=PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,0,2000,200,300,PlanetEconomy(1000,600,70,80))},
            mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,10000,UnitInventory(ships.quantities(),ground.quantities()),
                supplies=100,activeShips=ships,activeGround=ground,position=OutfitPosition(grid,base))),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),deploymentInitialized=true,nextShipId=3,
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=5))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(command:Int=52,mode:Int=0,token:Int=37)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f00).putShort(command.toShort()).putShort(59).putShort(mode.toShort()).putInt(if(command==55)0 else 200)
        .put(0).putInt(0).putShort(0).putInt(0).putShort(token.toShort()).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,body:ByteArray,status:Int=0):ByteArray {
        client.send(body);val packet=next(client,0x1200);assertEquals(status,ByteBuffer.wrap(packet).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356);return packet
    }
    private fun advance(server:LoadFixture) = runBlocking {
        server.engine.submit(WorldCommand.AdvanceTo(CivicActions.DAY*1000/SessionRules.load().speed))
        val stock=CompletableDeferred<PlanetStocks>();server.engine.submit(WorldCommand.Stock(1,stock));withTimeout(5000){stock.await()}
    }
    @Test fun waitingDutySurvivesRestartAndCompletesOnceThenParadeCancellationAndGuardDepartureAreVisible() {
        val state=State();var deadline=0L
        LoadFixture(directory.resolve("first"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            val observer=server.join(server.account(1),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            val started=action(actor,request());assertEquals(10,started[22].toInt())
            deadline=ByteBuffer.wrap(started).getInt(23).toLong() and 0xffffffffL
            assertEquals(now+86400,deadline);assertEquals(840,state.players.getValue("load-000").mcp)
            assertEquals(80,state.stocks.bases.getValue(1049).economy?.peace)
            action(actor,request());assertEquals(840,state.players.getValue("load-000").mcp)
            action(actor,request(53),StrategyActionRejection.BUSY.ordinal+1)
            assertEquals(10,action(actor,request(mode=2,token=38))[22].toInt())
        }
        LoadFixture(directory.resolve("restart"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            val observer=server.join(server.account(1),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            assertEquals(deadline,state.stocks.civic.orders.getValue(10000).readyAt)
            advance(server);assertEquals(CivicPhase.COMPLETED,state.stocks.civic.orders.getValue(10000).phase)
            assertEquals(85,state.stocks.bases.getValue(1049).economy?.peace)
            val complete=action(actor,request(mode=2,token=39));assertEquals(12,complete[22].toInt())
            assertEquals(5,ByteBuffer.wrap(complete).getShort(27).toInt())
            var seen=false
            repeat(30){if(!seen){val b=ByteBuffer.wrap(next(observer,0x031f));seen=b.get(6).toInt()>0 && b.getInt(7)==1049 && b.getShort(93).toInt()==85}}
            assertTrue(seen)
            val before=state.players.getValue("load-000").mcp
            assertEquals(10,action(actor,request(54,token=40))[22].toInt())
            assertEquals(before-160,state.players.getValue("load-000").mcp)
            assertEquals(13,action(actor,request(54,1,41))[22].toInt());assertEquals(before-160,state.players.getValue("load-000").mcp)
            assertEquals(70,state.stocks.bases.getValue(1049).economy?.approval)
            assertEquals(11,action(actor,request(55,token=42))[22].toInt())
            assertEquals(2,CivicActions.guardStrength(state.stocks,1049,guarded,now+86400))
            actor.send(org.logh7.protocol.PlaceMessages.moved(org.logh7.protocol.PlaceMessages.Move(10000,publicSpot,0),0,0,0).copyOfRange(4,30))
            assertEquals(0,ByteBuffer.wrap(next(actor,0x0e00)).getInt(0));next(actor,0x0356)
            assertEquals(14,action(actor,request(55,2,43))[22].toInt())
            assertEquals(1,CivicActions.guardStrength(state.stocks,1049,guarded,now+86400))
            assertEquals(CivicPhase.INVALIDATED,state.stocks.civic.orders.getValue(10000).phase)
        }
    }
    @Test fun capturedBaseRequisitionUsesTheSameEncryptedEditorAndUpdatesTheStationedObserver() {
        val state=State(1050)
        LoadFixture(directory.resolve("captured"),2,LoadMetrics(),galaxy,ticking=false,navigationStore=state.navigation,
            stockStore=state.inventory,worldStateCommitter=state).use {server ->
            val actor=server.join(server.account(0),true,false).client
            val observer=server.join(server.account(1),true,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            assertEquals(10,action(actor,request(56))[22].toInt())
            advance(server)
            val complete=action(actor,request(56,2,38));assertEquals(12,complete[22].toInt())
            assertEquals(1000,ByteBuffer.wrap(complete).getShort(27).toInt())
            assertEquals(1100,state.stocks.outfits.getValue(200).supplies)
            assertEquals(1100,state.stocks.outfits.getValue(200).activeShips!!.values.sumOf {it.supplies ?: 0})
            // Daily production and requisition settle together. The transfer itself is exactly 1000.
            assertEquals(1000,state.stocks.civic.orders.getValue(10000).effect)
            assertEquals(200,state.stocks.outfits.getValue(200).id)
            next(observer,0x0327)
        }
    }
}
