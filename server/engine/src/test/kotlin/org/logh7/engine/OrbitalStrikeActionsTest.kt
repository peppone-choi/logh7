package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-31 궤도 대지 공격: 전술 중·사정·물자 조건과 수비대 피해·치안 하락. 작성: Peppone Choi. */
class OrbitalStrikeActionsTest {
    private val rules=SessionRules.load()
    private val base=GalaxyBase(1000,2487,80,0,"Battle",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3)),listOf(base),Power.entries.associateWith {2487})
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val now=session.gameMillis/1000
    private fun stocks(strength:Int=1000):PlanetStocks {
        val defenders=ActiveGroundUnits(listOf(ActiveGroundUnit(1,UnitStack(4),base=1000,strength=strength),ActiveGroundUnit(2,UnitStack(4),base=1000)))
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,200,300,PlanetEconomy(1000,500,50,40))),
            mapOf(1 to OutfitStock(1,3,OutfitKind.GROUND,1000,101,units=UnitInventory(troops=defenders.quantities()),activeGround=defenders)),
            mapOf(101 to OutfitWarehouse(101,3,OutfitKind.GROUND,1000)),deploymentInitialized=true,nextShipId=3)
    }
    private fun player(distance:Float,cpPaused:Boolean=true):Navigator {
        val centre=TacticalBasePosition.at(base,now,stocks())
        val at=TacticalPoint(centre.x+base.diameter+distance,centre.y)
        return Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,mode=5,cpPaused=cpPaused,
            motion=TacticalMotion(2487,now,TacticalPose(at,0f),0f,0f,listOf(at)))
    }
    private fun act(p:Navigator,s:PlanetStocks=stocks())=SocialActions.evaluate(galaxy,SocialRequest(StrategyActionRequest(
        "a","a",10000,Power.EMPIRE,1,OrbitalStrikeActions.STRIKE,0,1)),session,p,s,listOf(p))

    @Test fun aFlagshipInRangeBombardsTheEnemyGarrisonAndOrder() {
        val r=act(player(10f),stocks(strength=100))
        assertNull(r.rejection,"${r.rejection}");assertEquals(1,r.outcome);assertEquals(1000,r.found);assertEquals(1,r.report)
        val s=assertNotNull(r.stocks)
        val left=s.outfits.getValue(1).activeGround!!.values.single()
        assertEquals(2,left.id);assertEquals(1000-OrbitalStrikeActions.DAMAGE,left.strength)
        assertEquals(1,s.outfits.getValue(1).units.troops[UnitStack(4)])
        assertEquals(38,s.bases.getValue(1000).economy?.peace)
        assertEquals(10_000-OrbitalStrikeActions.SUPPLIES,r.player?.supplies)
    }
    @Test fun outOfRangeOutsideTacticsOrWithoutSuppliesIsRefused() {
        assertEquals(SocialRejection.TARGET,act(player(OrbitalStrikeActions.RANGE+5f)).rejection)
        assertEquals(SocialRejection.LOCATION,act(Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,mode=5)).rejection)
        assertEquals(SocialRejection.MAXIMUM,act(player(10f).copy(supplies=50)).rejection)
        val own=stocks().let {it.copy(bases=it.bases+(1000 to it.bases.getValue(1000).copy(power=2)))}
        assertEquals(SocialRejection.TARGET,act(player(10f),own).rejection,"own planets are not targets")
    }
}
