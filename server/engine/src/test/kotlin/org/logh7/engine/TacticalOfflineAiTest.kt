package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.math.hypot

class TacticalOfflineAiTest {
    private val baseSession=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"one")}.exportState()
    private val now=baseSession.gameMillis/1000
    private val offline=baseSession.copy(participants=baseSession.participants.mapValues {(_,p)->p.copy(online=false,connection=null)})
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Battle",2)),
        listOf(GalaxyBase(1000,2488,80,1,"Fortress",0,0f,86400,0,0f,2f)),Power.entries.associateWith {2488})
    private fun motion(x:Float=0f,y:Float=0f)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,y),0f),0f,0f,listOf(TacticalPoint(x,y)))
    private fun players()=mutableMapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,cpPaused=true,
        hull=FlagshipHull(0,now,clockScale=1),motion=motion(),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM)))
    private fun stocks(mission:Int=0):PlanetStocks {
        fun outfit(id:Int,power:Int,unit:Int,kind:Int,commander:Int,y:Float)=OutfitStock(id,power,OutfitKind.FLEET,1000,id*10,
            commander,UnitInventory(mapOf(UnitStack(kind) to 1)),supplies=100,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(unit,UnitStack(kind),
                tactical=NpcTacticalState(motion(0f,y),now,now)))),position=OutfitPosition(2488))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,0,0)),
            outfits=mapOf(1 to outfit(1,2,1,32,10000,.5f),2 to outfit(2,3,99,119,0,30f)),
            warehouses=mapOf(10 to OutfitWarehouse(10,2,OutfitKind.FLEET,1000,1),20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)),
            deploymentInitialized=true,nextShipId=100,tacticalCommands=TacticalCommandState(aiSwitches=mapOf(10000 to 16),
                missions=mapOf(10000 to TacticalMissionState(10000,mission,if(mission==1)0 else 1,if(mission==1)1000 else 99,now,grid=2488))))
    }
    @Test fun enabledOfflineAiIssuesRealOwnedMovementAndAttackWithoutSpendingCpAndHonorsCadence() {
        val players=players();val stock=stocks();val events=mutableListOf<TacticalCommandResult>()
        val next=TacticalOfflineAi.advance(offline,galaxy,players,stock,1,events)
        val p=players.getValue("pilot")
        assertNotNull(p.attack);assertEquals(TacticalPoint(0f,28f),p.motion!!.waypoints.single())
        assertTrue(p.energy!!.allocated<=Flagships.find(0)!!.number("出力:出力"))
        assertEquals(100,p.pcp);assertEquals(100,p.mcp);assertEquals(10000,p.supplies)
        val own=next.outfits.getValue(1).activeShips!!.values.single()
        assertEquals(10000,own.motionCommander);assertNotNull(own.attack)
        assertEquals(stock.outfits.getValue(2),next.outfits.getValue(2))
        assertTrue(events.single().stateOnly);assertEquals(setOf(1010000,1),events.single().motions.keys)
        val saved=players.toMap()
        assertEquals(next,TacticalOfflineAi.advance(offline.copy(gameMillis=(now+1)*1000),galaxy,players,next,1,events))
        assertEquals(saved,players);assertEquals(1,events.size)
    }
    @Test fun onlineOrDisabledCharactersAreNotDrivenAndPendingPersonalShotsArePreserved() {
        for((session,stock) in listOf(baseSession to stocks(),offline to stocks().copy(tacticalCommands=TacticalCommandState()))) {
            val p=players();val before=p.toMap()
            assertEquals(stock,TacticalOfflineAi.advance(session,galaxy,p,stock,1,mutableListOf()))
            assertEquals(before,p)
        }
        val p=players();val shot=TacticalPendingFire(1,2488,0,99,now,now+5)
        p["pilot"]=p.getValue("pilot").copy(fire=TacticalFire(1,pending=shot))
        val before=p.toMap();TacticalOfflineAi.advance(offline,galaxy,p,stocks(),1,mutableListOf())
        assertEquals(before,p)
    }
    @Test fun reconnaissanceKeepsTenUnitsAndWithdrawalStartsTheExistingWarpPreparation() {
        val scouts=players();TacticalOfflineAi.advance(offline,galaxy,scouts,stocks(4),1,mutableListOf())
        assertNull(scouts.getValue("pilot").attack)
        assertEquals(TacticalPoint(0f,20f),scouts.getValue("pilot").motion!!.waypoints.single())
        val p=players();p["pilot"]=p.getValue("pilot").copy(motion=motion(0f,260f))
        TacticalOfflineAi.advance(offline,galaxy,p,stocks(5),1,mutableListOf())
        val withdrawn=p.getValue("pilot")
        assertNotNull(withdrawn.retreat);assertEquals(50,withdrawn.energy!!.values[1]);assertNull(withdrawn.attack)
        assertEquals(now+155,withdrawn.retreat!!.completesAt)
        val defenders=players();val s=stocks(1)
        TacticalOfflineAi.advance(offline,galaxy,defenders,s,1,mutableListOf())
        val end=defenders.getValue("pilot").motion!!.waypoints.single()
        val base=TacticalCommandOperations.basePoint(galaxy.bases.single(),now,s)
        assertTrue(hypot(end.x-base.x,end.y-base.y)<=3.001f)
    }
}
