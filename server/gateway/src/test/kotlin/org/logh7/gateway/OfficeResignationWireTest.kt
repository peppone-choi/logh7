package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.gateway.load.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import java.nio.file.*
import java.util.*
import kotlin.test.*

class OfficeResignationWireTest {
    @TempDir lateinit var directory:Path
    private fun next(client:SyntheticClient,opcode:Int):ByteArray {
        repeat(100) {val b=client.receive();if(ByteBuffer.wrap(b).getShort(4).toInt()==opcode)return b}
        error("Missing opcode $opcode")
    }
    @Test fun capitalMilitaryResignationUpdatesSelfObserverRosterAndRejectsReplay()=exercise(57,2,3)
    @Test fun capitalCivilResignationRevokesTaxAuthority()=exercise(192,3,1)
    @Test fun nationalResignationRevokesCapitalAppointmentAuthority()=exercise(17,2,2)
    private fun exercise(card:Int,power:Int,rank:Int) {
        val faction=if(power==2)Power.EMPIRE else Power.ALLIANCE
        val base=if(power==2)1049 else 1177;val grid=if(power==2)2487 else 2015
        val galaxy=Galaxy(listOf(GalaxySystem(3,grid,4,"Home",power)),listOf(GalaxyBase(base,grid,80,0,
            if(power==2)"オーディン" else "ハイネセン",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to grid,Power.ALLIANCE to grid))
        val people=(0..1).map {i->CharacterMessages.Generate(4,10000L+i,power,0,0,"Pilot$i","Test",30,1,1,1,List(8){50},0,0,0,if(i==0)rank else 3,0,0,"Ship$i",0)}
        val properties=Properties()
        people.forEachIndexed {i,c->properties.setProperty("load-%03d".format(i),HexFormat.of().formatHex(CharacterMessages.generateReply(c).copyOfRange(4,CharacterMessages.generateReply(c).size)))}
        Files.newBufferedWriter(directory.resolve("characters.properties")).use {properties.store(it,null)}
        var players=(0..1).associate {"load-%03d".format(it) to Navigator(1,10000+it,faction,grid,1000,1000,1000,cpRecoveryAt=SessionRules.load().startSeconds)}
        val held=PlanetStock(base,power,0,100,200,300)
        val national=NationalOffice.of(card)
        var stocks=PlanetStocks(1,0,mapOf(base to (CapitalAppointmentPolicy.rule(card)?.assign(held,10000) ?: held)),
            deploymentInitialized=true,nationalOffices=if(national==null)emptyMap() else mapOf(national to 10000))
        val nav=object:NavigationStore {override fun load()=players;override fun save(value:Map<String,Navigator>){players=value}}
        val inventory=object:PlanetStockStore {override fun load()=stocks;override fun save(value:PlanetStocks){stocks=value}}
        LoadFixture(directory,2,LoadMetrics(),galaxy,ticking=false,navigationStore=nav,stockStore=inventory,
            worldStateCommitter=WorldStateCommitter {p,s->players=p;stocks=checkNotNull(s)}).use {fixture->
            val actor=fixture.join(fixture.account(0),false,false).client
            actor.send(SyntheticClient.opcode(0x0f00));next(actor,0x0f01)
            val observer=fixture.join(fixture.account(1),false,false).client
            observer.send(SyntheticClient.opcode(0x0f00));next(observer,0x0f01)
            fun roster() {observer.send(ByteBuffer.allocate(4).putShort(0x034e).putShort(card.toShort()).array())}
            roster();assertEquals(1,next(observer,0x034f)[8].toInt())
            val query=ByteBuffer.allocate(27).putShort(0x709).putInt(-1).putInt(10000).putInt(-1).putInt(-1).putInt(card).putInt(0).put(0).array()
            repeat(2) {attempt->
                actor.send(query);val ack=ByteBuffer.wrap(next(actor,0x709))
                assertEquals(if(attempt==0)0 else 1,ack.int);assertEquals(1000,ack.getInt(14));assertEquals(920,ack.getInt(18))
                assertContentEquals(BootstrapMessages.changedCharacter(people[0],1000,920),next(actor,0x0356))
                roster();assertEquals(0,next(observer,0x034f)[8].toInt())
            }
            if(card==17) {
                val forged=ByteBuffer.allocate(40).putShort(0x707).putInt(0).putInt(10000).putInt(1000).putInt(1000)
                    .putInt(10001).putInt(57).putShort(57).putInt(base).putInt(0).putInt(0).array()
                actor.send(forged);val denied=ByteBuffer.wrap(next(actor,0x707));assertEquals(1,denied.int);assertEquals(920,denied.getInt(18))
                assertEquals(0,denied.getInt(22));assertEquals(0,stocks.bases.getValue(base).guardInspectorId)
            }
            if(card==192) {
                actor.send(ByteBuffer.allocate(27).putShort(0x0a00).putInt(0).putInt(10000).put(0)
                    .putInt(1000).putInt(1000).putInt(base).put(21).putShort(192).put(0).array())
                val denied=ByteBuffer.wrap(next(actor,0x0a00));assertEquals(1,denied.int);assertEquals(920,denied.getInt(19))
                assertNull(stocks.bases.getValue(base).taxRate)
            }
            assertEquals(920,players.getValue("load-000").mcp);assertEquals(1000,players.getValue("load-001").mcp)
            assertTrue(stocks.nationalOffices.isEmpty())
            assertTrue(BaseOfficePolicy(galaxy).holders(stocks.bases.getValue(base)).isEmpty())
        }
    }
}
