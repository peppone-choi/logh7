package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.CharacterMessages
import java.nio.ByteBuffer
import java.nio.file.*
import java.util.*
import kotlin.test.*

/** 퇴역 직후 같은 접속의 0356이 정치가 계급과 줄어든 카드 목록을 싣는다. 작성: Peppone Choi. */
class RetirementWireTest {
    @TempDir lateinit var directory:Path
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b}
        error("Missing opcode $opcode")
    }
    /** Card rows close the record: count, (kind u16, target u32)*, together u8. */
    private fun cards(record:ByteArray):List<Int> {
        for(count in 2..16) {
            val start=record.size-1-6*count
            if(record[start-1].toInt()==count && ByteBuffer.wrap(record).getShort(start).toInt()==0)
                return List(count) {ByteBuffer.wrap(record).getShort(start+6*it).toInt()}
        }
        error("No card rows")
    }
    @Test fun retiringDropsTheCommandCardAndRankInTheSameConnection() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
        val properties=Properties()
        val person=CharacterMessages.Generate(4,10000L,2,0,0,"Pilot","Test",30,1,1,1,List(8){50},0,0,0,5,0,0,"Ship",0)
        val bytes=CharacterMessages.generateReply(person)
        properties.setProperty("load-000",HexFormat.of().formatHex(bytes.copyOfRange(4,bytes.size)))
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var players=mapOf("load-000" to Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
            baseId=1049,mode=4,cpRecoveryAt=SessionRules.load().startSeconds))
        var stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,10000,number=1)),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),deploymentInitialized=true)
        val navStore=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val stockStore=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        val commit=WorldStateCommitter {nav,inventory ->players=nav;stocks=checkNotNull(inventory)}
        LoadFixture(directory,1,LoadMetrics(),galaxy,ticking=false,stockStore=stockStore,navigationStore=navStore,
            worldStateCommitter=commit).use {fixture ->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            actor.send(ByteBuffer.allocate(31).putShort(0x1200).putInt(10000).putShort(0x7f00).putShort(84).putShort(0).putShort(0)
                .putInt(0).put(0).putInt(0).putShort(0).putInt(0).putShort(41).array())
            next(actor,0x1200)
            val updated=next(actor,0x0356)
            assertEquals(listOf(0,59),cards(updated))
            assertEquals(0,stocks.outfits.getValue(200).commanderId)
            assertEquals(1,stocks.strategic.people.getValue(10000).rank)
        }
    }
}
