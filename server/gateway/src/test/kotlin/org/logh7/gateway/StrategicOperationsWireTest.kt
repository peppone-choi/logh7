package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

class StrategicOperationsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2),GalaxySystem(4,2490,4,"There",3),
        GalaxySystem(5,2491,4,"Rebel",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2490,80,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1051,2491,80,0,"Rebel",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val now=SessionRules.load().startSeconds
    private inner class State:WorldStateCommitter {
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,2000,2000,1000,cpRecoveryAt=now))
        private val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        private val foreign=ActiveShipUnits(listOf(ActiveShipUnit(2,UnitStack(119))))
        private val rebels=ActiveShipUnits(listOf(ActiveShipUnit(3,UnitStack(32))))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300),1050 to PlanetStock(1050,3,0,100,200,300),
            1051 to PlanetStock(1051,2,1,100,200,300)),
            mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,10001,UnitInventory(ships=ships.quantities()),activeShips=ships),
                201 to OutfitStock(201,3,OutfitKind.FLEET,1050,301,10002,UnitInventory(ships=foreign.quantities()),activeShips=foreign),
                202 to OutfitStock(202,2,OutfitKind.FLEET,1051,302,10003,UnitInventory(ships=rebels.quantities()),activeShips=rebels)),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200),301 to OutfitWarehouse(301,3,OutfitKind.FLEET,1050,201),
                302 to OutfitWarehouse(302,2,OutfitKind.FLEET,1051,202)),
            social=SocialState(coup=CoupState(mapOf(10003 to Plot(10003,CountryId(2),now,revolt=maxOf(1,now),bases=setOf(1051))),
                loyalty=mapOf(202 to CoupActions.LOYAL))),
            deploymentInitialized=true,nextShipId=4,strategic=StrategicState(people=mapOf(10000 to StrategicPerson(rank=2)),
                offices=listOf(OfficeHolding(24,1049,10000),OfficeHolding(28,1049,10000)),
                operations=listOf(StrategicOperation(9,10000,24,CountryId(43),2490,StrategicForce.FLEET,StrategicPurpose.OCCUPY,999,now))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(command:Int,preview:Boolean=false,base:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f00).putShort(command.toShort()).putShort(if(command==24)28 else 24).putShort(0)
        .putInt(if(preview)0 else 2490).put(if(preview)1 else 0).putInt(0).putShort(if(command==22)1 else 0)
        .putInt(base).putShort(0).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,command:Int,base:Int=0,status:Int=0) {
        client.send(request(command,base=base))
        assertEquals(status,ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356)
    }
    @Test fun encryptedPlanningAssignmentAndRestartedWithdrawalIgnoreAnotherCountrysPlanAndArmy() {
        val state=State()
        fun server(name:String)=LoadFixture(directory.resolve(name),1,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state)
        server("first").use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            action(client,22);assertEquals(720,state.players.getValue("load-000").mcp)
            val own=state.stocks.strategic.operations.single {it.id==10}
            assertEquals(CountryId(2),own.country)
            client.send(request(24,preview=true));next(client,0x1200)
            val rows=ByteBuffer.wrap(next(client,0x1202));assertEquals(1,rows.get(6).toInt());assertEquals(200,rows.getInt(7))
            next(client,0x1201)
            action(client,24,201,StrategyActionRejection.AUTHORITY.ordinal+1)
            action(client,24,202,StrategyActionRejection.AUTHORITY.ordinal+1)
            assertEquals(720,state.players.getValue("load-000").mcp)
            action(client,24,200);assertEquals(719,state.players.getValue("load-000").mcp)
            assertEquals(setOf(200),state.stocks.strategic.operations.single {it.id==10}.outfits)
        }
        server("restart").use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            val restored=state.stocks.strategic.operations.single {it.id==10}
            assertEquals(StrategicPlanState.ISSUED,restored.state);assertEquals(setOf(200),restored.outfits)
            action(client,23);assertEquals(714,state.players.getValue("load-000").mcp)
            assertEquals(StrategicPlanState.WITHDRAWN,state.stocks.strategic.operations.single {it.id==10}.state)
            assertEquals(StrategicPlanState.PLANNED,state.stocks.strategic.operations.single {it.id==9}.state)
        }
    }
}
