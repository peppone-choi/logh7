package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

class OutfitAffiliationWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2),GalaxySystem(4,2488,4,"There",3)),
        listOf(1049,1050).mapIndexed {i,id->GalaxyBase(id,2487+i,80,0,"B$id",0,20f,86400,0,0f,2f)},
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2488))
    private fun fleet(id:Int,power:Int,base:Int)=OutfitStock(id,power,OutfitKind.FLEET,base,id+1000)
    private val outfits=(1..61).map {fleet(it,2,1049)}+listOf(fleet(700,3,1049),fleet(800,2,1050),fleet(900,3,1050))
    private val snapshot=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0),1050 to PlanetStock(1050,3,0,0,0,0)),
        outfits=outfits.associateBy {it.id},warehouses=outfits.associate {it.warehouseId to OutfitWarehouse(it.warehouseId,it.country,it.kind,it.baseId,it.id,shipStyle=it.shipStyle)})
    private fun members(frame:ByteArray,cursor:Int,kind:Int,number:Int):List<Pair<Int,String>> {
        val b=ByteBuffer.wrap(frame);assertEquals(0,b.int);assertEquals(0x032f,b.short.toInt() and 65535)
        assertEquals(65535,b.int);assertEquals(cursor,b.int);assertEquals(13,b.get().toInt());assertEquals(2,b.get().toInt());b.get()
        assertEquals(kind,b.int);assertEquals(number,b.int)
        return List(b.get().toInt() and 255) {
            val id=b.int;b.get();b.get();val n=b.get().toInt() and 255
            id to if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")
        }
    }
    @Test fun encryptedPagesExposeHereAndOwnOnlyAndRequireWorldInitialization() {
        val store=object:PlanetStockStore {
            var value=snapshot
            override fun load()=value
            override fun save(stocks:PlanetStocks) {value=stocks}
        }
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,stockStore=store).use {f->
            fun query(cursor:Int)=ByteBuffer.allocate(11).putShort(0x032e).putInt(65535).putInt(cursor).put(13).array()
            val early=f.join(f.account(1),true,false).client
            early.send(query(0));early.expectClosed()
            val client=f.join(f.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            var ready=false
            for(ignored in 0..300)if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01) {ready=true;break}
            assertTrue(ready)
            client.send(query(0))
            val first=members(client.expect(0x032f),0,0,0).map {it.second}
            assertEquals(listOf("FA1","60","60","1","2487"),first.take(5));assertEquals("1",first[5]+first[6])
            val rows=(0 until 6).flatMap {members(client.expect(0x032f),0,1,it)}
            assertEquals((1..60).map {it to "2"},rows)
            client.send(query(60));val second=members(client.expect(0x032f),60,0,0).map {it.second}
            assertEquals(listOf("FA1","3","800","0","2487"),second.take(5))
            assertEquals(listOf(61 to "2",700 to "3",800 to "2"),members(client.expect(0x032f),60,1,0))
            client.send(query(999999));assertEquals("0",members(client.expect(0x032f),999999,0,0)[1].second)
        }
    }
}
