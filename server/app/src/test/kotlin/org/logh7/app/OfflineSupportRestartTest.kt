package org.logh7.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.persistence.DirectorySync
import org.logh7.persistence.FileSessionStore
import org.logh7.persistence.FileWorldStateStore
import java.nio.file.Path
import kotlin.test.*

class OfflineSupportRestartTest {
    @TempDir lateinit var root: Path

    @Test fun offlineSupplyRestartsBetweenReservationAndSettlement() = runBlocking {
        val rules=SessionRules.load()
        val now=rules.startSeconds
        val grid=2488
        val galaxy=Galaxy(listOf(GalaxySystem(3,grid,4,"Battle",2)),
            listOf(GalaxyBase(1000,grid,80,1,"Fortress",0,0f,86400,0,0f,2f)),Power.entries.associateWith {grid})
        fun motion(y:Float)=TacticalMotion(grid,now,TacticalPose(TacticalPoint(0f,y),0f),0f,0f,listOf(TacticalPoint(0f,y)))
        val pilot=Navigator(1,10000,Power.EMPIRE,grid,1000,1000,900,cpPaused=true,
            hull=FlagshipHull(0,now),motion=motion(0f),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM))
        val ally=pilot.copy(characterId=10001,motion=motion(.7f),supplies=9500)
        val kind=ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind
        fun outfit(id:Int,power:Int,stack:UnitStack,y:Float,supplies:Int,commander:Int)=OutfitStock(
            id,power,OutfitKind.FLEET,1000,id*10,commander,UnitInventory(mapOf(stack to 1)),supplies=supplies,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(id,stack,tactical=NpcTacticalState(motion(y),now,now),supplies=supplies))),
            position=OutfitPosition(grid))
        val stocks=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100)),
            outfits=mapOf(1 to outfit(1,2,UnitStack(kind),.5f,500,10000),2 to outfit(2,3,UnitStack(119),30f,0,0)),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=3,tacticalCommands=TacticalCommandState(aiSwitches=mapOf(10000 to 16),
                missions=mapOf(10000 to TacticalMissionState(10000,1,0,1000,now,grid=grid))))
        val worldPath=root.resolve("world")
        val sessionPath=root.resolve("session")
        fun order(store:FileWorldStateStore)=store.loadStocks()!!.outfits.getValue(1).activeShips!!.values.single().support
        suspend fun runWorld(test:suspend (WorldEngine,FileWorldStateStore)->Unit) {
            FileWorldStateStore(worldPath).use { store ->
                val session=FileSessionStore(sessionPath,rules,directorySync=DirectorySync.FILE_CONTENTS_ONLY)
                val engine=WorldEngine(this,rules,ticking=false,durability=session,galaxy=galaxy,
                    navigationStore=store.navigationStore,stockStore=store.stockStore,worldStateCommitter=store)
                try {test(engine,store)} finally {engine.drainAndClose()}
            }
        }
        suspend fun step(engine:WorldEngine,elapsed:Long) {
            val done=CompletableDeferred<Unit>()
            engine.submit(WorldCommand.AdvanceTo(elapsed))
            engine.submit(WorldCommand.Quiesce({},done))
            withTimeout(5000) {done.await()}
        }
        FileWorldStateStore(worldPath).use {it.saveWorld(mapOf("pilot" to pilot,"ally" to ally),stocks)}
        suspend fun join(engine:WorldEngine,account:String,connection:String) {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join(account,Power.EMPIRE,false,joined,connection))
            assertEquals(Admission.ACCEPTED,withTimeout(5000) {joined.await()})
        }
        runWorld { engine,store ->
            join(engine,"pilot","pilot-connection")
            engine.submit(WorldCommand.Disconnect("pilot","pilot-connection"))
            step(engine,0)
            assertEquals(TacticalSupportOrder(TacticalSupportAction.SUPPLY,10000,ally.unitId,grid,now,now+5*rules.speed),order(store))
            assertEquals(9500,store.loadNavigation().getValue("ally").supplies)
        }
        runWorld { engine,store ->
            assertEquals(ally.unitId,order(store)?.target)
            join(engine,"ally","ally-after-restart")
            val results=mutableListOf<TacticalSupportResult>()
            engine.observeSupport(results::add).use {
                step(engine,4999)
                assertTrue(results.isEmpty())
                val before=store.loadNavigation().getValue("ally").supplies
                val cargo=store.loadStocks()!!.outfits.getValue(1).supplies
                assertTrue(cargo>0)
                step(engine,5000)
                val result=results.single()
                assertFalse(result.cancelled)
                assertEquals(mapOf(ally.characterId to "ally-after-restart"),result.recipients)
                assertEquals(before+cargo,result.targetSupplies)
                assertNull(order(store))
                assertEquals(result.targetSupplies,store.loadNavigation().getValue("ally").supplies)
                assertEquals(0,store.loadStocks()!!.outfits.getValue(1).supplies)
                step(engine,10000)
                assertEquals(1,results.size)
            }
        }
    }
}
