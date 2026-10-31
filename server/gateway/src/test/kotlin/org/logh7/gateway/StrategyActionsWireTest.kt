package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

/** 암호화 송수신→월드 액터→CP/병력/예약 저장 경로. 실행은 리드 담당. 작성: Peppone Choi. */
class StrategyActionsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private class State:WorldStateCommitter {
        var saves=0
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        private val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(0),base=1049)))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,
            PlanetEconomy(1000,600,70,80))),mapOf(200 to OutfitStock(200,2,OutfitKind.GROUND,1049,300,
            10000,UnitInventory(troops=troops.quantities()),activeGround=troops)),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.PATROL,1049)),deploymentInitialized=true,nextShipId=2)
        val navigation=object:NavigationStore {
            override fun load()=players
            override fun save(value:Map<String,Navigator>){players=value}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(value:PlanetStocks){stocks=value}
        }
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?) {
            saves++;players=navigation;stocks=checkNotNull(inventory)
        }
    }
    private fun request(command:Int,card:Int=59)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f00).putShort(command.toShort()).putShort(card.toShort()).putShort(0).putInt(200)
        .put(0).putInt(0).putShort(0).putInt(0).putShort(0).array()
    private fun next(c:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=c.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    @Test fun twoTrainingCommandsAndSuppressionCommitRealEffectsAndReplayBusyWithoutASecondCharge() {
        val state=State()
        LoadFixture(directory.resolve("first"),1,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val client=server.join(server.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            fun action(command:Int,status:Int,cp:Int,card:Int=59) {
                client.send(request(command,card))
                assertEquals(status,ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
                next(client,0x1201);next(client,0x0356)
                assertEquals(cp,state.players.getValue("load-000").mcp)
            }
            action(48,0,920)
            assertEquals(1,state.stocks.outfits.getValue(200).activeGround!!.values.single().stack.level)
            action(50,0,840)
            assertEquals(1,state.stocks.strategic.people.getValue(10000).groundTactics)
            action(53,0,680)
            assertEquals(1,state.stocks.strategic.suppressions.size)
            val commits=state.saves
            action(53,StrategyActionRejection.BUSY.ordinal+1,680)
            action(48,StrategyActionRejection.CARD.ordinal+1,680,195)
            assertEquals(commits,state.saves)
        }
        LoadFixture(directory.resolve("restart"),1,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state).use {server->
            val client=server.join(server.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            assertEquals(1,state.stocks.strategic.people.getValue(10000).groundTactics)
            assertEquals(1,state.stocks.strategic.suppressions.size)
            client.send(request(50));assertEquals(StrategyActionRejection.BUSY.ordinal+1,
                ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
            assertEquals(680,state.players.getValue("load-000").mcp)
        }
    }
}
