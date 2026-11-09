package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

class TransportActionsWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2487,80,0,"Destination",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val hull=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind)
    private inner class State:WorldStateCommitter {
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        private val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,hull)))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,5000,200,300),
            1050 to PlanetStock(1050,2,0,100,200,300)),
            mapOf(200 to OutfitStock(200,2,OutfitKind.TRANSPORT,1049,300,10000,
                UnitInventory(ships=ships.quantities()),activeShips=ships)),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.TRANSPORT,1049,200)),
            logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_SECOND),nextShipId=2,
            strategic=StrategicState(transports=listOf(TransportPackage(1,CountryId(43),1049,1050,700))))
        val navigation=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        override fun saveWorld(navigation:Map<String,Navigator>,inventory:PlanetStocks?){players=navigation;stocks=checkNotNull(inventory)}
    }
    private fun request(command:Int,preview:Boolean=false,target:Int=0,intent:Int=0)=ByteBuffer.allocate(31).putShort(0x1200).putInt(10000)
        .putShort(0x7f00).putShort(command.toShort()).putShort(if(command==65)44 else 25)
        .putShort(if(command==28)0xffff.toShort() else intent.toShort()).putInt(if(command==28)1050 else target)
        .put(if(preview)1 else 0).putInt(if(command==65)200 else 0).putShort(if(command==28)1000 else 0)
        .putInt(if(command==28)1049 else 0).putShort(0).array()
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(200){val packet=client.receive();if(ByteBuffer.wrap(packet).getShort(4).toInt()==opcode)return packet}
        error("Missing response $opcode")
    }
    private fun action(client:SyntheticClient,command:Int,target:Int=0,status:Int=0,warehouse:Int=0,intent:Int=0) {
        client.send(request(command,target=target,intent=intent))
        assertEquals(status,ByteBuffer.wrap(next(client,0x1200)).getShort(16).toInt())
        next(client,0x1201);next(client,0x0356)
        if(warehouse>0)assertEquals(warehouse,ByteBuffer.wrap(next(client,0x0327)).getInt(6))
    }
    private fun listed(client:SyntheticClient,command:Int):List<Int> = listedRows(client,command).map {it.first}
    private fun listedRows(client:SyntheticClient,command:Int,expectedIntent:Int=0):List<Pair<Int,String>> {
        client.send(request(command,preview=true));next(client,0x1200)
        val rows=ByteBuffer.wrap(next(client,0x1202));val count=rows.get(6).toInt() and 255
        rows.position(7)
        val selections=List(count){
            val id=rows.int;val length=rows.get().toInt() and 255
            val name=CharArray(length){rows.char}.concatToString().removeSuffix("\u0000")
            assertEquals(expectedIntent,rows.short.toInt())
            rows.position(rows.position()+13);id to name
        }
        next(client,0x1201);return selections
    }
    @Test fun encryptedPlanListsOnlyOwnCargoAndRestoresLoadedCargoForDeliveryAndCancellation() {
        val state=State()
        fun server(name:String)=LoadFixture(directory.resolve(name),1,LoadMetrics(),galaxy,ticking=false,
            navigationStore=state.navigation,stockStore=state.inventory,worldStateCommitter=state)
        server("first").use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            assertEquals(emptyList(),listed(client,29))
            action(client,28,warehouse=1049);assertEquals(920,state.players.getValue("load-000").mcp)
            assertEquals(CountryId(2),state.stocks.strategic.transports.single {it.id==2}.country)
            assertEquals(listOf(2),listed(client,29));assertEquals(listOf(2),listedRows(client,65,expectedIntent=1).map {it.first})
            action(client,29,1,StrategyActionRejection.TARGET.ordinal+1)
            assertEquals(920,state.players.getValue("load-000").mcp)
            action(client,65,2,warehouse=1049,intent=1);assertEquals(760,state.players.getValue("load-000").mcp)
            assertEquals(200,state.stocks.strategic.transports.single {it.id==2}.carrier)
            assertEquals(emptyList(),listed(client,29))
        }
        val packageWithLoss=state.stocks.strategic.transports.single {it.id==2}.let {
            it.copy(survivingBoats=it.carrierBoats/2)
        }
        state.stocks=state.stocks.copy(strategic=state.stocks.strategic.copy(
            transports=state.stocks.strategic.transports.map {if(it.id==2)packageWithLoss else it}))
        server("restart").use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            assertEquals(listOf(2 to "1050/500*"),listedRows(client,65,expectedIntent=2))
            action(client,29,2,StrategyActionRejection.BUSY.ordinal+1)
            assertEquals(760,state.players.getValue("load-000").mcp)
            action(client,65,1,StrategyActionRejection.TARGET.ordinal+1)
            assertEquals(700,state.stocks.strategic.transports.single {it.id==1}.supplies)
        }
        // Resume a carrier that has arrived at the destination, preserving its original depot.
        state.players=state.players.mapValues {(_,p)->p.copy(baseId=1050)}
        state.stocks=state.stocks.copy(outfits=state.stocks.outfits.mapValues {(_,o)->o.copy(position=OutfitPosition(2487,1050))})
        server("arrival").use {fixture->
            val client=fixture.join(fixture.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00));next(client,0x0f01)
            action(client,65,2,StrategyActionRejection.QUOTE_CHANGED.ordinal+1,intent=1)
            assertEquals(760,state.players.getValue("load-000").mcp)
            assertEquals(200,state.stocks.strategic.transports.single {it.id==2}.carrier)
            action(client,65,2,warehouse=1050,intent=2);assertEquals(600,state.players.getValue("load-000").mcp)
            assertEquals(600,state.stocks.bases.getValue(1050).supplies)
            assertEquals(listOf(1),state.stocks.strategic.transports.map {it.id})
            action(client,28,warehouse=1049);assertEquals(520,state.players.getValue("load-000").mcp)
            action(client,29,2,warehouse=1049);assertEquals(440,state.players.getValue("load-000").mcp)
            assertEquals(4000,state.stocks.bases.getValue(1049).supplies)
            assertEquals(700,state.stocks.strategic.transports.single().supplies)
        }
    }
}
