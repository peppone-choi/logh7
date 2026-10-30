package org.logh7.persistence

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.*
import kotlin.test.*

class NpcTacticalStoreTest {
    @Test fun angularFleetMotionSurvivesTheNestedStockCodec() {
        val motion=TacticalMotion(2488,100,TacticalPose(TacticalPoint(0f,0f),0f),1f,0f,
            listOf(TacticalPoint(0f,10f),TacticalPoint(0f,0f)),turnRate=1f)
        val unit=ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion,100,100),motionCommander=10000)
        val encoded=ActiveShipUnitCodec.encode(ActiveShipUnits(listOf(unit)))
        val restored=ActiveShipUnitCodec.decode(encoded,tactics=true,cargo=true,support=true,commanded=true)!!.values.single()
        assertEquals(unit,restored)
        assertEquals(TacticalPoint(0f,10f),restored.tactical!!.motion.pose(116).position)
    }
    @Test fun pendingRotationSurvivesNestedStockMotion() {
        for(kind in TacticalManeuver.entries) {
            val motion=TacticalMotion(2488,100,TacticalPose(TacticalPoint(0f,1f),0f),0f,1f,
                listOf(TacticalPoint(1f,0f)),rotation=TacticalRotation(kind,2,240,listOf(2,1)))
            val unit=ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion,100,100),motionCommander=10000)
            val encoded=ActiveShipUnitCodec.encode(ActiveShipUnits(listOf(unit)))
            assertEquals(unit,ActiveShipUnitCodec.decode(encoded,tactics=true,cargo=true,support=true,commanded=true)!!.values.single())
        }
    }
    @TempDir lateinit var directory:Path
    @Test fun parallelFleetMotionSurvivesTheNestedStockCodec() {
        val point=TacticalPoint(1f,2f)
        val motion=TacticalMotion(2488,100,TacticalPose(point,0.4f),0.5f,0.4f,
            listOf(TacticalPoint(20f,2f),TacticalPoint(30f,2f)),parallel=true,reportedWaypoints=1)
        val unit=ActiveShipUnit(1,UnitStack(32),tactical=NpcTacticalState(motion,100,100),motionCommander=10000)
        val encoded=ActiveShipUnitCodec.encode(ActiveShipUnits(listOf(unit)))
        assertEquals(unit,ActiveShipUnitCodec.decode(encoded,tactics=true,cargo=true,support=true,commanded=true)!!.values.single())
    }
    @Test fun stockSixteenReopensMotionCadenceTargetRetreatAndSequenceAndOldFifteenHasNoNpc() {
        val p=TacticalPoint(180f,-95f)
        val state=NpcTacticalState(TacticalMotion(2488,100,TacticalPose(p,0f),1f,0f,listOf(TacticalPoint(-280f,0f))),
            124,220,7,1010000,TacticalRetreat(2487,100,120,3600))
        val stack=UnitStack(32)
        val expected=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,1,2,3)),
            outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,10,units=UnitInventory(mapOf(stack to 1)),supplies=1000,
                activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,70,state))),position=OutfitPosition(2488))),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1)),deploymentInitialized=true,nextShipId=2)
        val path=directory.resolve("stocks")
        FilePlanetStockStore(path).use {it.save(expected)}
        assertTrue(Files.readString(path).startsWith("LOGH7-STOCK-16\t"))
        FilePlanetStockStore(path).use {assertEquals(expected,it.load())}
        val encoded=NpcTacticalCodec.encode(state)
        assertEquals(state,NpcTacticalCodec.decode(encoded))
        val legacy=Files.readString(path).replace("LOGH7-STOCK-16","LOGH7-STOCK-15").replace(".$encoded","")
        Files.writeString(path,legacy)
        FilePlanetStockStore(path).use {assertNull(it.load()!!.outfits.getValue(1).activeShips!!.values.single().tactical)}
        assertFailsWith<IllegalArgumentException>{ActiveShipUnitCodec.decode("1.32.0.70.$encoded",legacyFull=false)}
        assertFailsWith<IllegalArgumentException>{NpcTacticalCodec.decode(encoded+"x")}
    }
}
