package org.logh7.gateway

import java.nio.ByteBuffer
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import kotlin.test.*

/** Authenticated public ownership requests use the real encrypted session. Author: Peppone Choi. */
class BaseAffiliationWireTest {
    @TempDir lateinit var directory:Path
    private val galaxy=Galaxy((0..2).map {GalaxySystem(3+it,2487+it,4,"Home$it",2)},
        (1049..1060).map {GalaxyBase(it,2487+(it-1049)/4,80,0,"B$it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    @Test fun readySessionReceivesEveryBaseAndRejectsAnObjectTarget() {
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false).use {f->
            val client=f.join(f.account(0),true,false).client
            client.send(SyntheticClient.opcode(0x0f00))
            var ready=false
            for(ignored in 0 until 100)if(ByteBuffer.wrap(client.receive()).getShort(4).toInt()==0x0f01) {ready=true;break}
            assertTrue(ready)
            fun query(id:Int)=ByteBuffer.allocate(11).putShort(0x032e).putInt(id).putInt(65535).put(12).array()
            client.send(query(0))
            val frames=List(3) {ByteBuffer.wrap(client.expect(0x032f))}
            val ids=mutableListOf<Int>()
            for((index,b) in frames.withIndex()) {
                assertEquals(0,b.int);assertEquals(0x032f,b.short.toInt() and 65535)
                assertEquals(0,b.int);assertEquals(65535,b.int);assertEquals(12,b.get().toInt());assertEquals(2,b.get().toInt());b.get()
                assertEquals(if(index==0)0 else 1,b.int);assertEquals(if(index==0)0 else index-1,b.int)
                val count=b.get().toInt() and 255
                assertEquals(if(index==2)2 else 10,count)
                repeat(count) {row->
                    val id=b.int;val camp=b.get().toInt();val power=b.get().toInt();val n=b.get().toInt() and 255
                    val text=if(n==0)"" else String(CharArray(n){b.char}).removeSuffix("\u0000")
                    if(index==0)assertEquals(if(row==0)"BA1" else if(row==1)"12" else "",text)
                    else {ids+=id;assertEquals(0,camp);assertEquals(2,power);assertEquals("2",text)}
                }
            }
            assertEquals((1049..1060).toList(),ids)
            client.send(query(1049));client.expectClosed()
        }
    }
}
