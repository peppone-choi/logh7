package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import java.nio.ByteBuffer
import kotlin.test.*

/** Full native 0325 replacement must keep aboard and ashore identities after every refresh. Author: 최병호. */
class GroundPresenceTest {
    @Test fun supportAndImpactRefreshRetainBothSidesOfCarrierRelationshipsForBothPeers() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),
            listOf(GalaxyBase(1049,2487,80,0,"Here",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
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
        for(action in TacticalSupportAction.entries) {
            hub.support(TacticalSupportResult(1,2487,action,1,1010000,100,200,0,audience),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
            checkDefinitions()
        }
        for(weapon in TacticalWeapon.entries) {
            hub.combat(TacticalImpact(1,1010000,weapon,TacticalHit(1,2487,250,1,1),audience),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
            checkDefinitions()
        }
        hub.support(TacticalSupportResult(1,2487,TacticalSupportAction.SUPPLY,1,1010000,100,200,0,
            mapOf(10000 to "old-connection")),snapshot,galaxy){id->people.singleOrNull {it.id==id.toLong()}}
        assertTrue(packets.all {it.isEmpty()})
    }
}
