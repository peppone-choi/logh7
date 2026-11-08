package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

/** Full native 0325 replacement must keep aboard and ashore identities after every refresh. Author: Peppone Choi. */
class GroundPresenceTest {
    @Test fun supportAndImpactRefreshRetainBothSidesOfCarrierRelationshipsForBothPeers() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),
            listOf(GalaxyBase(1049,2487,80,0,"ハイネセン",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val people=(0..1).map {CharacterMessages.Generate(0,10000L+it,2,0,0,"Pilot","Name",30,1,1,0,List(8){50},0,0,0,20,0,0,"Ship",0)}
        val viewers=people.map {Navigator(1,it.id.toInt(),Power.EMPIRE,2487,1000,1000,1000,cpPaused=true)}
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0),carrier=1),ActiveGroundUnit(3,UnitStack(1),base=1049)))
        val outfit=OutfitStock(1,2,OutfitKind.FLEET,1049,10,10000,UnitInventory(ships.quantities(),ground.quantities()),
            activeShips=ships,activeGround=ground,position=OutfitPosition(2487))
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1049,1)),nextShipId=4)
        val snapshot=WorldSnapshot(0,0,SessionSnapshot(1,250,0,0,emptyMap(),1,1,null),
            navigators=viewers.mapIndexed {i,p->"pilot$i" to p}.toMap(),planetStocks=stocks)
        val hub=GamePresence();val packets=List(2){mutableListOf<List<ByteArray>>()}
        for(i in 0..1)hub.update(GamePresence.Member("connection$i",people[i],viewers[i],packets[i]::add))
        packets.forEach {it.clear()}
        val audience=(0..1).associate {10000+it to "connection$it"}
        val expected=TacticsMessages.definitions(2487,TacticalBootstrap(snapshot,galaxy,viewers[0]){id->people.singleOrNull {it.id==id.toLong()}}.units,
            listOf(TacticsMessages.GroundDefinition(2,1000,1,0,1),TacticsMessages.GroundDefinition(3,1001,1,1049)))
        fun checkDefinitions() {
            packets.forEach {batches ->
                val definitions=batches.flatten().single {ByteBuffer.wrap(it).getShort(4).toInt()==0x0325}
                assertContentEquals(expected,definitions)
            }
            packets.forEach {it.clear()}
        }
        val order=TacticalGroundOrder(TacticalGroundAction.DEPLOY,10000,2487,1,1049,200,250)
        val landed=TacticalGroundResult(1,order,listOf(3),250,audience)
        val returnedPilot=viewers[0].copy(baseId=1049,mode=4,cpPaused=false)
        val afterReturn=snapshot.copy(navigators=snapshot.navigators+("pilot0" to returnedPilot))
        for(result in listOf(landed,landed.copy(cancelled=true))) {
            hub.ground(result,afterReturn,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
            assertTrue(packets[0].isEmpty())
            assertTrue(packets[1].isNotEmpty())
            packets[1].clear()
        }
        hub.groundRefresh(1,2487,1049,250,snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
        checkDefinitions()
        val movedPilot=viewers[0].copy(grid=2991)
        for(current in listOf(returnedPilot,movedPilot,null)) {
            val updated=snapshot.copy(navigators=snapshot.navigators-"pilot0"+listOfNotNull(current?.let {"pilot0" to it}))
            hub.groundRefresh(1,2487,1049,250,updated,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
            assertTrue(packets[0].isEmpty())
            val refresh=packets[1].flatten()
            assertEquals(listOf(0x0325,0x0345,0x042a),refresh.map {ByteBuffer.wrap(it).getShort(4).toInt()})
            assertEquals(1000,ByteBuffer.wrap(refresh.last()).getShort(14).toInt())
            packets[1].clear()
        }
        for(action in TacticalSupportAction.entries) {
            hub.support(TacticalSupportResult(1,2487,action,1,1010000,100,200,0,audience),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
            checkDefinitions()
        }
        for(weapon in TacticalWeapon.entries) {
            hub.combat(TacticalImpact(1,1010000,weapon,TacticalHit(1,2487,250,1,1),audience),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
            checkDefinitions()
        }
        hub.groundBattle(GroundBattleResult(1,2487,1049,250,listOf(3),emptyList(),audience),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
        assertTrue(packets.all {batches->batches.flatten().none {ByteBuffer.wrap(it).getShort(4).toInt()==0x0f1d}})
        checkDefinitions()
        val conclusion=GroundBattleResult(1,2487,1049,250,listOf(3),listOf(3),audience,GroundBattleConclusion(Power.ALLIANCE.country))
        val changedCountry=snapshot.copy(navigators=snapshot.navigators+("pilot1" to viewers[1].copy(country=Power.ALLIANCE.country)))
        packets.forEach {it.clear()}
        hub.groundBattle(conclusion,changedCountry,galaxy,{if(it==Power.ALLIANCE.country)"시험국" else null}){id->people.singleOrNull {it.id==id.toLong()}}
        for(i in 0..1) {
            val packet=packets[i].flatten().single {ByteBuffer.wrap(it).getShort(4).toInt()==0x0f1d}
            val text=if(i==0)"[지상전] 하이네센 · 시험국 승리" else "[지상전] 하이네센 · 아군 승리"
            assertEquals(ChatMessages.Spot(10000+i,text),ChatMessages.spot(packet.copyOfRange(4,packet.size)))
        }
        packets.forEach {it.clear()}
        val returned=viewers[0].copy(cpPaused=false)
        val returnedSnapshot=snapshot.copy(navigators=snapshot.navigators+("pilot0" to returned))
        hub.groundBattle(conclusion.copy(recipients=mapOf(10000 to "connection0")),returnedSnapshot,galaxy,{"시험국"}) {null}
        assertTrue(packets[0].isEmpty())
        assertNull(hub.takeGroundResult("connection0",returned))
        hub.groundResultGridReady("connection0",returned)
        val notice=checkNotNull(hub.takeGroundResult("connection0",returned))
        assertEquals(ChatMessages.Spot(10000,"[지상전] 하이네센 · 시험국 승리"),ChatMessages.spot(notice.copyOfRange(4,notice.size)))
        assertNull(hub.takeGroundResult("connection0",returned))
        packets.forEach {it.clear()}
        val departed=viewers[0].copy(grid=2991,cpPaused=false)
        val departedSnapshot=snapshot.copy(navigators=snapshot.navigators+("pilot0" to departed))
        hub.groundBattle(conclusion.copy(recipients=mapOf(10000 to "connection0")),departedSnapshot,galaxy){null}
        assertTrue(packets[0].isEmpty())
        hub.groundResultGridReady("connection0",departed)
        assertNull(hub.takeGroundResult("connection0",departed))
        hub.groundBattle(conclusion.copy(damaged=emptyList(),destroyed=emptyList(),
            conclusion=GroundBattleConclusion(Power.ALLIANCE.country,withdrawal=true)),snapshot,galaxy){null}
        for(i in 0..1) {
            val packet=packets[i].flatten().single {ByteBuffer.wrap(it).getShort(4).toInt()==0x0f1d}
            assertEquals(ChatMessages.Spot(10000+i,"[지상전] 하이네센 · 철수로 종료"),ChatMessages.spot(packet.copyOfRange(4,packet.size)))
        }
        packets.forEach {it.clear()}
        hub.groundBattle(GroundBattleResult(1,2487,1049,250,listOf(3),emptyList(),mapOf(10000 to "old-connection")),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
        assertTrue(packets.all {it.isEmpty()})
        hub.support(TacticalSupportResult(1,2487,TacticalSupportAction.SUPPLY,1,1010000,100,200,0,
            mapOf(10000 to "old-connection")),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
        assertTrue(packets.all {it.isEmpty()})
    }
}
