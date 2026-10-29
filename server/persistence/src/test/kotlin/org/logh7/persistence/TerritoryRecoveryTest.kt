package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.io.IOException
import java.nio.file.Path
import kotlin.test.*

/** Exercise the real world/session files on both sides of the cross-store force boundary. */
class TerritoryRecoveryTest {
    @TempDir lateinit var directory:Path
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(GalaxyBase(1000,2487,80,0,"オーディン",0,20f,86400,0,0f,2f),
            GalaxyBase(1001,2015,80,0,"ハイネセン",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private fun original():PlanetStocks {
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(0),base=1001)))
        val outfit=OutfitStock(1,2,OutfitKind.GROUND,1000,101,
            units=UnitInventory(troops=troops.quantities()),activeGround=troops)
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,100,100),1001 to PlanetStock(1001,3,0,100,100,100)),
            mapOf(1 to outfit),mapOf(101 to OutfitWarehouse(101,2,OutfitKind.PATROL,1000)),
            deploymentInitialized=true,nextShipId=2)
    }
    @Test fun worldCommitBeforeEndingJournalIsRecoveredBeforeAnyNewAdmission()=runBlocking {
        recoverGap(StorePoint.BEFORE_APPEND)
    }
    @Test fun endingForcedButNotPublishedIsReplayedWithoutDuplicateOccupation()=runBlocking {
        recoverGap(StorePoint.AFTER_FORCE)
    }
    private suspend fun CoroutineScope.recoverGap(point:StorePoint) {
        val worldRoot=directory.resolve("world");val sessionRoot=directory.resolve("session")
        var committed=false;var worldSaves=0;val events=mutableListOf<WorldEvent>()
        var captured:PlanetStocks?=null
        FileWorldStateStore(worldRoot).use {world->
            world.saveWorld(emptyMap(),original())
            val journal=FileSessionStore(sessionRoot,rules,directorySync=DirectorySync.FILE_CONTENTS_ONLY,
                failPoint={at->if(committed && at==point)throw IOException("injected between world and ending publication")})
            val engine=WorldEngine(this,rules,ticking=false,durability=journal,galaxy=galaxy,
                navigationStore=world.navigationStore,stockStore=world.stockStore,
                worldStateCommitter=WorldStateCommitter {players,stocks->
                    world.saveWorld(players,stocks);committed=true;worldSaves++
                },onEvent=events::add)
            assertFailsWith<IllegalStateException> {engine.synchronizeTime()}
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertTrue(events.filterIsInstance<WorldEvent.Ended>().isEmpty())
            captured=world.loadStocks();assertEquals(2,captured!!.bases.getValue(1001).power)
            assertEquals(1,worldSaves)
            engine.drainAndClose()
        }
        FileWorldStateStore(worldRoot).use {world->
            val journal=FileSessionStore(sessionRoot,rules,directorySync=DirectorySync.FILE_CONTENTS_ONLY)
            val before=journal.load()
            assertEquals(point==StorePoint.AFTER_FORCE,before.session.ended!=null)
            val engine=WorldEngine(this,rules,ticking=false,durability=journal,galaxy=galaxy,
                navigationStore=world.navigationStore,stockStore=world.stockStore,
                worldStateCommitter=WorldStateCommitter {players,stocks->world.saveWorld(players,stocks);worldSaves++})
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("late",Power.EMPIRE,false,joined,"new",1))
            assertEquals(Admission.ENDED,joined.await())
            assertEquals(EndReason.CAPITAL_CAPTURED,engine.snapshot().session!!.ended!!.reason)
            assertEquals(setOf(Power.ALLIANCE),engine.snapshot().session!!.ended!!.affected)
            val report=assertNotNull(engine.snapshot().result)
            assertEquals(SessionStatistics(2,0,2_000_000_000L,0,1),report.total)
            assertEquals(2,report.powers.getValue(Power.EMPIRE).statistics.planets)
            assertEquals(SessionGrade.LIMITED,report.powers.getValue(Power.EMPIRE).grade)
            assertEquals(SessionGrade.DEFEAT,report.powers.getValue(Power.ALLIANCE).grade)
            assertEquals(3,journal.load().revision)
            assertTrue(journal.load().session.participants.isEmpty())
            assertEquals(captured,world.loadStocks());assertEquals(1,worldSaves)
            engine.drainAndClose()
        }
        FileSessionStore(sessionRoot,rules,directorySync=DirectorySync.FILE_CONTENTS_ONLY).use {
            assertEquals(EndReason.CAPITAL_CAPTURED,it.load().session.ended!!.reason)
            assertEquals(3,it.load().revision)
        }
    }
}
