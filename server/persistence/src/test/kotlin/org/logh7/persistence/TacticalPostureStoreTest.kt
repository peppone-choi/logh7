package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class TacticalPostureStoreTest {
    @TempDir lateinit var directory:Path
    private val pending=TacticalPosture(2017,TacticalShipMode.ANCHORED,1049,
        TacticalPostureOrder(10000,TacticalShipMode.STATIONED,1049,100,340))
    @Test fun currentAndPendingPosturesReopenForPersonalAndCommissionedShipsWithoutAdvancingTheOrder() {
        val player=Navigator(1,10000,Power.EMPIRE,2017,1000,960,900,cpPaused=true,posture=pending)
        val nav=directory.resolve("nav")
        FileNavigationStore(nav).use {it.save(mapOf("pilot" to player,"legacy" to player.copy(characterId=10001,posture=null)))}
        assertEquals("LOGH7-NAV-17",Files.readAllLines(nav).first())
        FileNavigationStore(nav).use {assertEquals(player,it.load().getValue("pilot"));assertNull(it.load().getValue("legacy").posture)}
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),posture=pending),ActiveShipUnit(2,UnitStack(32))))
        val outfit=OutfitStock(1,2,OutfitKind.PATROL,1049,10,10000,UnitInventory(ships=ships.quantities()),activeShips=ships)
        val stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0)),mapOf(1 to outfit),
            mapOf(10 to OutfitWarehouse(10,2,OutfitKind.PATROL,1049)),nextShipId=3)
        val path=directory.resolve("stock")
        FilePlanetStockStore(path).use {it.save(stock)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-23\t"))
        FilePlanetStockStore(path).use {assertEquals(stock,it.load())}
        // Old writers must not reinterpret the extra field as cargo, attack, or reload state.
        assertFailsWith<IllegalArgumentException>{ActiveShipUnitCodec.decode(ActiveShipUnitCodec.encode(ships),reloads=true)}
        val rows=Files.readAllLines(nav)
        Files.writeString(nav,"LOGH7-NAV-16\n"+rows.drop(1).joinToString("\n"){it.substringBeforeLast('\t')}+"\n")
        FileNavigationStore(nav).use {assertEquals(player.copy(posture=null),it.load().getValue("pilot"))}
    }
    @Test fun postureExtensionPreservesExistingMotionCargoDamageSupportAttackShotAndReloadFields() {
        val point=TacticalPoint(1f,2f)
        val state=NpcTacticalState(TacticalMotion(2017,100,TacticalPose(point,0.4f),0f,0.4f,listOf(point)),100,100)
        val base=ActiveShipUnit(1,UnitStack(32),250,state,2000,10,
            support=TacticalSupportOrder(TacticalSupportAction.REPAIR,10000,2,2017,100,220),motionCommander=10000,
            reload=TacticalReload(500,600,700))
        val cases=listOf(base.copy(reload=null),base,
            base.copy(attack=TacticalUnitAttack(10000,TacticalAttackOrder(1,100,220,460))),
            base.copy(shot=TacticalUnitShot(10000,TacticalWeapon.BEAM,2,100,220)))
        for(unit in cases) {
            val roster=ActiveShipUnits(listOf(unit.copy(posture=pending)))
            val encoded=ActiveShipUnitCodec.encode(roster)
            assertEquals(roster,ActiveShipUnitCodec.decode(encoded,tactics=true,cargo=true,support=true,commanded=true,
                attacks=true,shots=true,reloads=true,postures=true))
        }
        val old=ActiveShipUnits(listOf(base))
        assertEquals(old,ActiveShipUnitCodec.decode(ActiveShipUnitCodec.encode(old),tactics=true,cargo=true,support=true,
            commanded=true,attacks=true,shots=true,reloads=true,postures=true))
    }
    @Test fun modeBerthClockAndCanonicalEncodingCannotBeConfused() {
        for(mode in TacticalShipMode.entries) {
            val value=TacticalPosture(2017,mode,if(mode.requiresBase)1049 else 0)
            assertEquals(value,TacticalPostureCodec.decode(TacticalPostureCodec.encode(value)))
            assertFailsWith<IllegalArgumentException>{value.copy(base=if(mode.requiresBase)0 else 1049)}
        }
        assertEquals(ShipBerth.STATIONED,TacticalShipMode.STATIONED.berth)
        assertEquals(ShipBerth.ANCHORED,TacticalShipMode.ANCHORED.berth)
        for(text in listOf("2017~3~0","2017~6~1049","2017~5~0","5000~6~0","2017~6~0~1", "02017~6~0",
            "2017~5~1049~10000~4~1049~340~100","2017~5~1049~0~4~1049~100~340",
            "2017~5~1049~10000~4~1049~100~4294967296",TacticalPostureCodec.encode(pending)+"~0"))
            assertFailsWith<IllegalArgumentException>(text){TacticalPostureCodec.decode(text)}
    }
}
