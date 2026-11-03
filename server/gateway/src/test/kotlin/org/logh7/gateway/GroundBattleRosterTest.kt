package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.OutfitPartyMessages
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.*

/** Reconstructed ground battle window roster (query mode 3). Author: Peppone Choi. */
class GroundBattleRosterTest {
    private val base=GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base),Power.entries.associateWith {2487})
    private val empire=ActiveGroundUnits(listOf(ActiveGroundUnit(11,UnitStack(2),base=1049,strength=700)))
    private val alliance=ActiveGroundUnits(listOf(ActiveGroundUnit(21,UnitStack(10),base=1049),ActiveGroundUnit(22,UnitStack(5))))
    private val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,3,0,0,0,0)),mapOf(
        1 to OutfitStock(1,2,OutfitKind.GROUND,1049,1,units=UnitInventory(troops=empire.quantities()),activeGround=empire),
        2 to OutfitStock(2,3,OutfitKind.GROUND,1049,2,units=UnitInventory(troops=alliance.quantities()),activeGround=alliance)),
        mapOf(1 to OutfitWarehouse(1,2,OutfitKind.GROUND,1049),2 to OutfitWarehouse(2,3,OutfitKind.GROUND,1049)),
        tacticalCommands=TacticalCommandState(groundTargets=mapOf(11 to 21)),nextShipId=30)
    private fun rows(grid:Int?):List<Triple<Int,Int,Int>> {
        val packets=WorldBootstrap(galaxy).outfitBattle(OutfitPartyMessages.Query(0,1049,3),2,stocks,grid)
        val b=ByteBuffer.wrap(packets[1]);b.position(6)
        return List(b.get().toInt()) {
            val id=b.int;val kind=b.short.toInt();b.get();b.int;b.int;b.int;b.get();b.int;b.get();b.get()
            val strength=b.short.toInt() and 65535;b.short;val target=b.int;b.int;b.float
            Triple(id,kind,strength*1_000_000+target)
        }
    }
    @Test fun bothSidesLandedTroopsWithStrengthAndTargetOnlyForATacticalViewerOnThatGrid() {
        assertEquals(listOf(Triple(11,1002,700_000_021),Triple(21,1010,1000_000_000)),rows(2487),"the troop still aboard is left out")
        assertTrue(rows(2490).isEmpty());assertTrue(rows(null).isEmpty())
        val special=Galaxy(galaxy.systems,listOf(base.copy(model=41)),galaxy.starts)
        val packets=WorldBootstrap(special).outfitBattle(OutfitPartyMessages.Query(0,1049,3),2,stocks,2487)
        assertEquals(1,ByteBuffer.wrap(packets[1]).get(6).toInt(),"the empire's armored infantry cannot fight on a special planet")
    }
    @Test fun troopsBeyondThirtyPerSideAreMarkedAsReserve() {
        val many=ActiveGroundUnits((1..32).map {ActiveGroundUnit(100+it,UnitStack(1),base=1049)})
        val foes=ActiveGroundUnits(listOf(ActiveGroundUnit(200,UnitStack(5),base=1049)))
        val s=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,3,0,0,0,0)),mapOf(
            1 to OutfitStock(1,2,OutfitKind.GROUND,1049,1,units=UnitInventory(troops=many.quantities()),activeGround=many),
            2 to OutfitStock(2,3,OutfitKind.GROUND,1049,2,units=UnitInventory(troops=foes.quantities()),activeGround=foes)),
            mapOf(1 to OutfitWarehouse(1,2,OutfitKind.GROUND,1049),2 to OutfitWarehouse(2,3,OutfitKind.GROUND,1049)),nextShipId=300)
        val b=ByteBuffer.wrap(WorldBootstrap(galaxy).outfitBattle(OutfitPartyMessages.Query(0,1049,3),2,s,2487)[1]);b.position(6)
        val reserve=List(b.get().toInt()) {
            val id=b.int;b.short;b.get();b.int;b.int;b.int;b.get();b.int;b.get();b.get();b.short;val r=b.short.toInt();b.int;b.int;b.float;id to r
        }.filter {it.second==1}.map {it.first}
        assertEquals(listOf(131,132),reserve)
    }
    @Test fun theRosterCarriesOnlyAlliedExistingCommandersWithoutChangingAnyAppointment() {
        val s=stocks.copy(outfits=stocks.outfits.mapValues {(id,o)->o.copy(commanderId=10000+id)})
        val packets=WorldBootstrap(galaxy).outfitBattle(OutfitPartyMessages.Query(0,1049,3),2,s,2487) {id->
            org.logh7.protocol.CharacterMessages.Generate(1,id.toLong(),2,0,0,"지휘관","",20,1,1,0,List(8){50},0,0,0,20,1,0,"Ship",0)
        }
        val b=ByteBuffer.wrap(packets[1]);b.position(7)
        b.position(b.position()+34);assertEquals(10001,b.int)
        b.position(b.position()+4+34);assertEquals(0,b.int,"enemy commander remains undisclosed")
        val members=ByteBuffer.wrap(packets[0]);members.position(25)
        assertEquals(1,members.get().toInt());assertEquals(10001,members.int)
        assertEquals(10001,s.outfits.getValue(1).commanderId)
        assertEquals(7,WorldBootstrap(galaxy).outfitBattle(OutfitPartyMessages.Query(0,1049,3),2,s,null)[1].size)
    }
}
