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
    private val opposing=player.copy(characterId=10001,country=Power.ALLIANCE.country,shipStyle=ShipStyleId.fromLegacy(Power.ALLIANCE))
    private fun character(id:Int)=if(id==10000)person else enemy.takeIf {id==10001}
    private fun opcode(packet:ByteArray)=ByteBuffer.wrap(packet).getShort(4).toInt() and 65535
    private fun snapshot(state:TacticalCommandState,available:Int=321,supplies:Int=0,damaged:Int=0):WorldSnapshot {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),supplies=supplies,damaged=damaged)))
        val outfit=OutfitStock(1,2,OutfitKind.PATROL,1000,10,10000,UnitInventory(ships.quantities()),
            supplies=supplies,activeShips=ships,position=OutfitPosition(2487))
        return WorldSnapshot(0,0,SessionSnapshot(1,120,0,0,emptyMap(),1,1,null),
            navigators=mapOf("pilot" to player,"enemy" to opposing),planetStocks=PlanetStocks(1,0,
                mapOf(1000 to PlanetStock(1000,2,0,available,0,0)),mapOf(1 to outfit),
                mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1000)),nextShipId=2,tacticalCommands=state))
    }
    @Test fun reconnectOnlyReplaysSupportWithItsActiveCommander() {
        val original=snapshot(TacticalCommandState())
        val stocks=checkNotNull(original.planetStocks)
        val outfit=stocks.outfits.getValue(1)
        val transport=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}
        val ship=outfit.activeShips!!.values.single().copy(
            stack=UnitStack(transport.kind),
            support=TacticalSupportOrder(TacticalSupportAction.SUPPLY,10000,1010000,2487,100,140))
        val ships=ActiveShipUnits(listOf(ship))
        val pending=original.copy(navigators=original.navigators+("pilot" to player.copy(supplies=1000)),
            planetStocks=stocks.copy(
            outfits=mapOf(1 to outfit.copy(activeShips=ships,units=UnitInventory(ships.quantities())))))
        fun replies(world:WorldSnapshot)=TacticalBootstrap(world,galaxy,opposing,::character).resume().filter {opcode(it)==0x0414}

        assertEquals(20,ByteBuffer.wrap(replies(pending).single()).getInt(10))
        assertTrue(replies(pending.copy(navigators=pending.navigators+("pilot" to player.copy(cpPaused=false)))).isEmpty())
        assertTrue(replies(pending.copy(navigators=pending.navigators+
            ("pilot" to player.copy(hull=FlagshipHull(0,100,woundedUntil=130))))).isEmpty())
        val changed=checkNotNull(pending.planetStocks)
        assertTrue(replies(pending.copy(planetStocks=changed.copy(
            strategic=StrategicState(people=mapOf(10000 to StrategicPerson(deceasedAt=110)))))).isEmpty())
        assertTrue(replies(pending.copy(planetStocks=changed.copy(
            outfits=mapOf(1 to changed.outfits.getValue(1).copy(commanderId=10001))))).isEmpty())
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
    @Test fun committedBaseSupportPublishesAbsoluteResultsToBothObserversButCancellationDoesNot() {
        for(command in listOf(0x41b,0x41c)) {
            val hub=GamePresence();val own=mutableListOf<List<ByteArray>>();val peer=mutableListOf<List<ByteArray>>()
            val tacticalGrids=mutableListOf<Int>()
            hub.update(GamePresence.Member("one",person,player,{error("Expected battlefield-scoped delivery")}) {grid,packets->
                tacticalGrids+=grid
                own+=packets
            })
            val docked=opposing.copy(baseId=1000,mode=4,cpPaused=false)
            hub.update(GamePresence.Member("two",enemy,docked,peer::add) {_,_->error("Expected docked delivery")})
            own.clear();peer.clear()
            val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(command,listOf(1),base=1000))
            val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one",10001 to "two"))
            val initial=snapshot(TacticalCommandState(),200,121,4)
            val state=initial.copy(navigators=initial.navigators+("enemy" to docked))
            hub.tacticalCommand(event,state,galaxy,::character)
            val code=if(command==0x41b)0x433 else 0x434
            val expected=if(command==0x41b)TacticalBaseMessages.repaired(1000,1,200,4)
                else TacticalBaseMessages.supplied(1000,1,200,121)
            for(packets in listOf(own,peer))assertContentEquals(expected,packets.flatten().single {opcode(it)==code})
            assertEquals(listOf(2487),tacticalGrids)
            own.clear();peer.clear();hub.tacticalCommand(event.copy(cancelled=true),state,galaxy,::character)
            assertTrue((own+peer).flatten().none {opcode(it) in setOf(0x433,0x434)})
        }
    }
    @Test fun supremeCommanderMissionAlsoReachesTheSubordinateAsATacticalChatLine() {
        val ally=player.copy(characterId=10002);val allyPerson=person.copy(id=10002)
        val hub=GamePresence();val own=mutableListOf<List<ByteArray>>();val sub=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,own::add))
        hub.update(GamePresence.Member("three",allyPerson,ally,sub::add));own.clear();sub.clear()
        val payload=ByteBuffer.allocate(23).putLong(0).putInt(10000).put(1).putInt(player.unitId).put(5).put(0).putInt(0).array()
            .map {it.toInt() and 255}
        val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x421,listOf(player.unitId),kind=5,payload=payload))
        val missions=listOf(10000,10002).associateWith {TacticalMissionState(it,5,0,0,120,grid=2487)}
        val state=snapshot(TacticalCommandState(missions=missions)).let {it.copy(navigators=it.navigators+("ally" to ally))}
        hub.tacticalCommand(TacticalCommandResult(1,order,120,mapOf(10000 to "one",10002 to "three")),state,galaxy,
            {id->if(id==10002)allyPerson else character(id)})
        val line=ChatMessages.grid(ChatMessages.Grid(10000,1,"[임무] 철수 임무를 지시합니다."),120)
        assertTrue(sub.flatten().any {it.contentEquals(line)})
        assertEquals(10002,ByteBuffer.wrap(sub.flatten().single {opcode(it)==0x421}).getInt(14))
        assertTrue(own.flatten().none {it.contentEquals(line)})
    }
    @Test fun subordinateProposalReachesTheChiefAsATacticalChatLine() {
        // Equal rank and merit: the lower character id (10000) is the chief, 10002 proposes.
        val ally=player.copy(characterId=10002);val allyPerson=person.copy(id=10002)
        val hub=GamePresence();val chief=mutableListOf<List<ByteArray>>();val proposer=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,chief::add))
        hub.update(GamePresence.Member("three",allyPerson,ally,proposer::add));chief.clear();proposer.clear()
        val payload=ByteBuffer.allocate(23).putLong(0).putInt(10002).put(1).putInt(player.unitId).put(5).put(0).putInt(0).array()
            .map {it.toInt() and 255}
        val order=TacticalQueuedCommand(10002,2487,100,120,TacticalCommand(0x421,listOf(player.unitId),kind=5,payload=payload))
        val state=snapshot(TacticalCommandState(proposals=mapOf(10002 to TacticalMissionState(10002,5,0,0,120,grid=2487))))
            .let {it.copy(navigators=it.navigators+("ally" to ally))}
        hub.tacticalCommand(TacticalCommandResult(1,order,120,mapOf(10000 to "one",10002 to "three")),state,galaxy,
            {id->if(id==10002)allyPerson else character(id)})
        val line=ChatMessages.grid(ChatMessages.Grid(10002,1,"[구신] 철수 임무를 구신합니다."),120)
        assertTrue(chief.flatten().any {it.contentEquals(line)})
        assertTrue(proposer.flatten().none {it.contentEquals(line)})
    }
    @Test fun finishedMissionIsToldOnlyToItsHolder() {
        val hub=GamePresence();val own=mutableListOf<List<ByteArray>>();val peer=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,own::add))
        hub.update(GamePresence.Member("two",enemy,opposing,peer::add));own.clear();peer.clear()
        val order=TacticalQueuedCommand(10000,2487,120,120,TacticalCommand(0x421))
        val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one",10001 to "two"),stateOnly=true,
            finishedMissions=mapOf(10000 to (5 to 10L)))
        hub.tacticalCommand(event,snapshot(TacticalCommandState()),galaxy,::character)
        val line=ChatMessages.grid(ChatMessages.Grid(10000,1,"[임무] 철수 임무 완료(공적 +10)."),120)
        assertTrue(own.flatten().any {it.contentEquals(line)});assertTrue(peer.flatten().none {it.contentEquals(line)})
        own.clear();hub.tacticalCommand(event.copy(finishedMissions=mapOf(10000 to (1 to 0L))),snapshot(TacticalCommandState()),galaxy,::character)
        assertTrue(own.flatten().any {it.contentEquals(ChatMessages.grid(ChatMessages.Grid(10000,1,"[임무] 방위 임무 종료."),120))})
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
    @Test fun reconnectShowsCannonReservationOnlyWhileItsCommanderIsPresent() {
        val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x419,base=1000,direction=.5f))
        val active=snapshot(TacticalCommandState(orders=listOf(order)))
        val docked=player.copy(baseId=1000,mode=4,cpPaused=false)
        fun cannon(commander:Navigator)=TacticalBootstrap(
            active.copy(navigators=active.navigators+("pilot" to commander)),galaxy,opposing,::character)
            .baseInformation.single().cannonStart

        assertEquals(100L,cannon(docked))
        assertEquals(0L,cannon(docked.copy(grid=2488)))
    }

    @Test fun reconnectDoesNotReplayCannonAfterItsCommanderIsDetained() {
        val payload = ByteBuffer.allocate(20).putInt(100).putInt(40).putInt(10000).putInt(1000).putFloat(.5f)
            .array().map { it.toInt() and 255 }
        val order = TacticalQueuedCommand(10000, 2487, 100, 140,
            TacticalCommand(0x419, base = 1000, direction = .5f, payload = payload))
        val pending = snapshot(TacticalCommandState(orders = listOf(order)))
        val actor = player.copy(baseId = 1000, mode = 4, cpPaused = false)
        val present = pending.copy(navigators = pending.navigators + ("pilot" to actor))
        fun bootstrap(world: WorldSnapshot) = TacticalBootstrap(world, galaxy, opposing, ::character)

        assertEquals(100L, bootstrap(present).baseInformation.single().cannonStart)
        assertTrue(bootstrap(present).resume().any { opcode(it) == 0x0419 })

        val stocks = checkNotNull(present.planetStocks)
        val detention = Detention(actor.characterId, Power.EMPIRE.country, 115, 115 + Justice.DAY, base = 1000)
        val detained = present.copy(planetStocks = stocks.copy(social = stocks.social.copy(
            justice = JusticeState(detentions = mapOf(actor.characterId to detention)))))
        assertEquals(0L, bootstrap(detained).baseInformation.single().cannonStart)
        assertFalse(bootstrap(detained).resume().any { opcode(it) == 0x0419 })
    }

    @Test fun committedCannonStartsItsBeamAndNotifiesOnlyTheActualDestructionList() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add));received.clear()
        val payload=ByteBuffer.allocate(20).putInt(100).putInt(20).putInt(10000).putInt(1000).putFloat(.5f).array().map {it.toInt() and 255}
        val order=TacticalQueuedCommand(10000,2487,100,120,TacticalCommand(0x419,base=1000,direction=.5f,payload=payload))
        val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one"),destroyedUnits=listOf(1,2))
        val state=snapshot(TacticalCommandState())
        hub.tacticalCommand(event,state,galaxy,::character)
        val fired=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x419})
        assertEquals(0,fired.getInt(10));assertEquals(Math.toDegrees(.5).toFloat(),fired.getFloat(22),.00001f)
        val kills=received.flatten().filter {opcode(it)==0x436}
        assertEquals(2,kills.size)
        assertEquals(listOf(1,2),kills.map {assertEquals(1,it[14].toInt());ByteBuffer.wrap(it).getInt(15)})
        received.clear();hub.tacticalCommand(event.copy(cancelled=true),state,galaxy,::character)
        assertFalse(received.flatten().any {opcode(it)==0x436 || (opcode(it)==0x419 && ByteBuffer.wrap(it).getInt(0)==0)})
        assertEquals(1,ByteBuffer.wrap(received.flatten().single {opcode(it)==0x419}).getInt(0))
        val stopped=ByteBuffer.wrap(received.flatten().single {opcode(it)==0x41e})
        assertEquals(0,stopped.getInt(0));assertEquals(120,stopped.getInt(6))
        assertEquals(0,stopped.getInt(10));assertEquals(10000,stopped.getInt(14));assertEquals(1000,stopped.getInt(18))
    }
    @Test fun committedStopReachesBothSidesButFailedStopsAndStaleConnectionsDoNotClearCharges() {
        val hub=GamePresence();val received=mutableListOf<List<ByteArray>>();val other=mutableListOf<List<ByteArray>>()
        val viewer=GamePresence.Member("one",person,player,received::add)
        val observer=GamePresence.Member("two",enemy,opposing,other::add)
        hub.update(viewer);hub.update(observer);received.clear();other.clear()
        val payload=ByteBuffer.allocate(16).putInt(120).putInt(0).putInt(10000).putInt(1000).array().map {it.toInt() and 255}
        val order=TacticalQueuedCommand(10000,2487,120,120,TacticalCommand(0x41e,base=1000,payload=payload))
        val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one",10001 to "two"))
        val state=snapshot(TacticalCommandState())
        hub.tacticalCommand(event,state,galaxy,::character)
        val ownerStop=received.flatten().single {opcode(it)==0x41e}
        assertContentEquals(ownerStop,other.flatten().single {opcode(it)==0x41e})
        assertFalse((received+other).flatten().any {opcode(it) in setOf(0x419,0x436)})
        received.clear();other.clear()
        hub.tacticalCommand(event.copy(cancelled=true),state,galaxy,::character)
        for(packets in listOf(received,other))assertEquals(1,ByteBuffer.wrap(packets.flatten().single {opcode(it)==0x41e}).getInt(0))
        hub.remove("two");hub.update(observer.copy(connection="replacement"));received.clear();other.clear()
        hub.tacticalCommand(event,state,galaxy,::character)
        assertTrue(received.flatten().any {opcode(it)==0x41e});assertTrue(other.isEmpty())
        hub.update(viewer.copy(player=player.copy(grid=2488)));received.clear();other.clear()
        hub.tacticalCommand(event,state,galaxy,::character)
        assertTrue(received.isEmpty());assertTrue(other.isEmpty())
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

        val stocks=checkNotNull(s.planetStocks)
        val coup=CoupState(mapOf(10000 to Plot(10000,Power.EMPIRE.country,0,revolt=5)))
        val rebelViewer=stocks.copy(social=stocks.social.copy(coup=coup))
        val capturedBase=stocks.copy(bases=stocks.bases.mapValues {(_,base)->base.copy(camp=1)})
        for(currentStocks in listOf(rebelViewer,capturedBase)) {
            val current=s.copy(planetStocks=currentStocks)
            received.clear()
            hub.tacticalCommand(TacticalCommandResult(1,order,120,mapOf(10000 to "one")),current,galaxy,::character)
            assertFalse(received.flatten().any {opcode(it)==0x438})
            assertFalse(TacticalBootstrap(current,galaxy,player,::character).initialize().any {opcode(it)==0x438})
        }
    }
    @Test fun airWingMotionRefreshSendsSpeedAndRouteWithoutACommandReply() {
        val hub=GamePresence()
        val received=mutableListOf<List<ByteArray>>()
        hub.update(GamePresence.Member("one",person,player,received::add))
        received.clear()
        val order=TacticalQueuedCommand(10000,2487,120,120,TacticalCommand(0x40e,listOf(1),kind=1,target=player.unitId))
        for(speed in listOf(.25f,.5f)) {
            val pose=TacticalPose(TacticalPoint(3f,4f),0f)
            val motion=TacticalMotion(2487,120,pose,speed,0f,listOf(TacticalPoint(30f,40f)),waitSeconds=0)
            val event=TacticalCommandResult(1,order,120,mapOf(10000 to "one"),motions=mapOf(player.unitId to motion),stateOnly=true)
            val original=snapshot(TacticalCommandState())
            val saved=original.copy(navigators=original.navigators+("pilot" to player.copy(motion=motion,energy=TacticalEnergy(listOf(50)+List(10){0}))))
            hub.tacticalCommand(event,saved,galaxy,::character)
            val packets=received.flatten()
            val packet=packets.single {opcode(it)==0x0400}
            val move=TacticalMoveMessages.move(packet.copyOfRange(4,packet.size))
            assertEquals(speed,move.velocity)
            assertEquals(player.unitId,move.units.single().id)
            assertEquals(listOf(TacticalMoveMessages.Point(30f,40f,0f)),move.waypoints)
            assertFalse(packets.any {opcode(it)==0x40e})
            received.clear()
            hub.tacticalCommand(event,original,galaxy,::character)
            assertFalse(received.flatten().any {opcode(it)==0x0400})
            received.clear()

            val stock=checkNotNull(saved.planetStocks)
            val outfit=stock.outfits.getValue(1)
            val ship=outfit.activeShips!!.values.single().copy(tactical=NpcTacticalState(motion,120,120))
            val shipEvent=event.copy(motions=mapOf(1 to motion))
            for(boats in listOf(ship.boats,0)) {
                val fleet=outfit.copy(activeShips=ActiveShipUnits(listOf(ship.copy(boats=boats))))
                val latest=saved.copy(planetStocks=stock.copy(outfits=mapOf(1 to fleet)))
                hub.tacticalCommand(shipEvent,latest,galaxy,::character)
                assertEquals(boats>0,received.flatten().any {opcode(it)==0x0400})
                received.clear()
            }
        }
    }

}
