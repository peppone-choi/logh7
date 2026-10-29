package org.logh7.gateway

import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

class TacticalBaseDeliveryTest {
    private val base=GalaxyBase(1000,2487,80,1,"Base",0,0f,1000,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private val person=CharacterMessages.Generate(0,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,10,0,0,"Ship",0)
    private val enemy=person.copy(id=10001,power=3,shipKind=89)
    private val player=Navigator(1,10000,Power.EMPIRE,2487,100,100,1000,cpPaused=true)
    private val opposing=player.copy(characterId=10001,power=Power.ALLIANCE)
    private fun character(id:Int)=if(id==10000)person else enemy.takeIf {id==10001}
    private fun opcode(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun snapshot(state:TacticalCommandState,available:Int=321,supplies:Int=0):WorldSnapshot {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),supplies=supplies)))
        val outfit=OutfitStock(1,2,OutfitKind.PATROL,1000,10,10000,UnitInventory(ships.quantities()),
            supplies=supplies,activeShips=ships,position=OutfitPosition(2487))
        return WorldSnapshot(0,0,SessionSnapshot(1,120,0,0,emptyMap(),1,1,null),
            navigators=mapOf("pilot" to player,"enemy" to opposing),planetStocks=PlanetStocks(1,0,
                mapOf(1000 to PlanetStock(1000,2,0,available,0,0)),mapOf(1 to outfit),
                mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1000)),nextShipId=2,tacticalCommands=state))
    }
    @Test fun partialFortressImpactKeepsItsSourceKindAndCurrentMorale() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add));received.clear()
        val impact=TacticalImpact(1,1000,TacticalWeapon.BEAM,TacticalHit(1,2487,120,1,5,10),
            mapOf(10000 to "one"),fortress=true)
        hub.combat(impact,snapshot(TacticalCommandState(morale=mapOf(1 to 35))),galaxy,::character)
        val packet=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x426})
        assertEquals(1000,packet.getInt(10));assertEquals(0x81,packet.get(15).toInt() and 255)
        assertEquals(5,packet.getShort(22).toInt());assertEquals(35,packet.get(27).toInt())
        assertTrue(received.flatten().any {opcode(it)==0x43e})
        assertEquals(35,received.flatten().single {opcode(it)==0x440}[14].toInt())
    }
    @Test fun encouragementUpdatesTheLiveMoraleAndConfusionFlagsAfterCommit() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add));received.clear()
        val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x409,listOf(player.unitId)))
        val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one"),morale=mapOf(1 to 35))
        hub.tacticalCommand(event,snapshot(TacticalCommandState(morale=mapOf(1 to 35))),galaxy,::character)
        val recovered=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x43e})
        assertEquals(1,recovered.getInt(10))
        assertEquals(35,received.flatten().single {opcode(it)==0x440}[14].toInt())
        received.clear();hub.tacticalCommand(event.copy(cancelled=true),snapshot(TacticalCommandState()),galaxy,::character)
        assertFalse(received.flatten().any {opcode(it) in setOf(0x440,0x43d,0x43e)})
    }
    @Test fun committedCannonStartsItsBeamAndNotifiesOnlyTheActualDestructionList() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add));received.clear()
        val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x419,base=1000,direction=.5f))
        val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one"),destroyedUnits=listOf(1,2))
        val state=snapshot(TacticalCommandState())
        hub.tacticalCommand(event,state,galaxy,::character)
        val fired=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x419})
        assertEquals(0,fired.getInt(10));assertEquals(.5f,fired.getFloat(22))
        val kills=received.flatten().filter {opcode(it)==0x436}
        assertEquals(2,kills.size)
        assertEquals(listOf(1,2),kills.map {assertEquals(1,it[14].toInt());ByteBuffer.wrap(it).getInt(15)})
        received.clear();hub.tacticalCommand(event.copy(cancelled=true),state,galaxy,::character)
        assertFalse(received.flatten().any {opcode(it) in setOf(0x419,0x436)})
    }
    @Test fun emergencyBalancesReachOnlyTheSameFactionAndRestoreOnReconnect() {
        val state=TacticalCommandState(emergencySupplies=mapOf(1000 to 2))
        val s=snapshot(state)
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>();val other=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add))
        hub.update(GamePresence.Member("two",enemy,opposing,other::add));received.clear();other.clear()
        val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x422,base=1000))
        hub.tacticalCommand(TacticalCommandResult(1,order,120,mapOf(10000 to "one",10001 to "two")),s,galaxy,::character)
        val opened=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x438})
        assertEquals(1000,opened.getInt(6));assertEquals(0,opened.getInt(10));assertEquals(321,opened.getInt(14))
        assertFalse(other.flatten().any {opcode(it)==0x438})
        assertTrue(TacticalBootstrap(s,galaxy,player,::character).initialize().any {opcode(it)==0x438})
        assertFalse(TacticalBootstrap(s,galaxy,opposing,::character).initialize().any {opcode(it)==0x438})
        received.clear()
        val supply=order.copy(command=TacticalCommand(0x41c,listOf(1),base=1000))
        hub.tacticalCommand(TacticalCommandResult(1,supply,120,mapOf(10000 to "one")),snapshot(state,200,121),galaxy,::character)
        val transferred=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x438})
        assertEquals(1,transferred.getInt(10));assertEquals(200,transferred.getInt(14));assertEquals(121,transferred.getInt(18))
    }
}
