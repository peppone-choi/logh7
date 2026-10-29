package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

class TacticalCommandDeliveryTest {
    private val base=GalaxyBase(1000,2487,80,1,"Base",0,0f,1000,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private val person=CharacterMessages.Generate(0,10000,2,0,0,"Alpha","Pilot",30,1,1,0,List(8){50},0,0,0,10,0,0,"Ship",0)
    private val player=Navigator(1,10000,Power.EMPIRE,2487,100,100,1000,cpPaused=true)
    private fun snapshot(state:TacticalCommandState):WorldSnapshot {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        val outfit=OutfitStock(1,2,OutfitKind.PATROL,1000,10,10000,UnitInventory(ships=ships.quantities()),
            activeShips=ships,position=OutfitPosition(2487))
        return WorldSnapshot(0,0,SessionSnapshot(1,110,0,0,emptyMap(),1,1,null),navigators=mapOf("pilot" to player),
            planetStocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),mapOf(1 to outfit),
                mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1000)),nextShipId=2,tacticalCommands=state))
    }
    private fun order():TacticalQueuedCommand {
        val payload=ByteBuffer.allocate(21).putInt(100).putInt(20).putInt(10000).put(1).putInt(1).putInt(1010000).array()
        return TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x420,listOf(1),target=1010000,payload=payload.map {it.toInt() and 255}))
    }
    private fun opcode(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    @Test fun pendingAaReplaysNativeCompatibleMissileSlotsWithoutLosingItsServerMarker() {
        val payload=ByteBuffer.allocate(23).putInt(100).putInt(20).putInt(10000).put(1).putInt(1010000)
            .put(2).put(0x81.toByte()).putInt(99).array().map {it.toInt() and 255}
        val queued=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x406,listOf(1010000),kind=3,targetKind=0x81,target=99,payload=payload))
        val boot=TacticalBootstrap(snapshot(TacticalCommandState(orders=listOf(queued))),galaxy,player){person}
        val reply=boot.resume().single {opcode(it)==0x406}
        assertEquals(10,ByteBuffer.wrap(reply).getInt(10));assertEquals(1,reply[24].toInt())
        assertEquals(0x81,TacticalCommandWire.command(queued)!!.targetKind)
    }
    @Test fun reconnectRestoresRemainingDelayAndSavedAuthorityMoraleAndMission() {
        val state=TacticalCommandState(orders=listOf(order()),authority=mapOf(1 to 10001),morale=mapOf(1 to 15),formations=mapOf(10000 to 6),
            missions=mapOf(10000 to TacticalMissionState(10000,1,0,1000,100)))
        val boot=TacticalBootstrap(snapshot(state),galaxy,player){person}
        val reply=boot.resume().single {opcode(it)==0x420}
        assertEquals(10,ByteBuffer.wrap(reply).getInt(10))
        assertEquals(10001,boot.units.single {it.id==1}.character)
        assertEquals(15,boot.units.single {it.id==1}.morale);assertTrue(boot.units.single {it.id==1}.confused)
        assertEquals(1,boot.corps.single().mission);assertEquals(6,boot.corps.single().formation)
    }
    @Test fun completionRefreshesOnlyItsAuthenticatedBattlefieldAndCancellationIsRejected() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        val viewer=GamePresence.Member("one",person,player,received::add)
        hub.update(viewer);received.clear()
        val result=TacticalCommandResult(1,order(),120,mapOf(10000 to "one"))
        val s=snapshot(TacticalCommandState(authority=mapOf(1 to 10000)))
        hub.tacticalCommand(result,s,galaxy){person}
        assertTrue(received.flatten().any {opcode(it)==0x033b})
        assertFalse(received.flatten().any {opcode(it)==0x420},"A state update is not an extra successful command ACK")
        received.clear();hub.tacticalCommand(result.copy(cancelled=true),s,galaxy){person}
        assertEquals(1,ByteBuffer.wrap(received.flatten().single {opcode(it)==0x420}).int)
        hub.update(viewer.copy(player=player.copy(grid=2488)));received.clear()
        hub.tacticalCommand(result,s,galaxy){person};assertTrue(received.isEmpty())
        hub.update(viewer.copy(connection="new"));received.clear()
        hub.tacticalCommand(result,s,galaxy){person};assertTrue(received.isEmpty())
    }
    @Test fun mobileFortressUsesTheStoredPositionAndItsOwnCompletionOpcode() {
        val point=TacticalPoint(15f,20f)
        val motion=TacticalMotion(2487,100,TacticalPose(point,0f),0f,0f,listOf(point))
        val s=snapshot(TacticalCommandState(fortresses=mapOf(1000 to TacticalFortressMotion(2487,motion))))
        val boot=TacticalBootstrap(s,galaxy,player){person}
        assertEquals(15f,boot.bases.single().x);assertEquals(20f,boot.bases.single().y)
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add));received.clear()
        val moved=TacticalQueuedCommand(10000,2487,110,110,TacticalCommand(0x41f,base=1000))
        hub.tacticalCommand(TacticalCommandResult(1,moved,110,mapOf(10000 to "one")),s,galaxy){person}
        val packet=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x435})
        assertEquals(15f,packet.getFloat(14));assertEquals(20f,packet.getFloat(18))
    }
}
