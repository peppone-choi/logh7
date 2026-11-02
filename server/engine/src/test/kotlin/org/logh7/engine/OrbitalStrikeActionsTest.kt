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
    @Test fun shipUnitsTheCharacterCommandsNearThePlanetJoinTheAttack() {
        val centre=TacticalBasePosition.at(base,now,stocks())
        fun ship(id:Int,dx:Float):ActiveShipUnit {
            val at=TacticalPoint(centre.x+base.diameter+dx,centre.y)
            return ActiveShipUnit(id,UnitStack(0),tactical=NpcTacticalState(TacticalMotion(2487,now,TacticalPose(at,0f),0f,0f,listOf(at)),now,now))
        }
        val ships=ActiveShipUnits(listOf(ship(11,5f),ship(12,OrbitalStrikeActions.RANGE+30f)))
        val fleet=OutfitStock(2,2,OutfitKind.FLEET,1000,102,commanderId=10000,units=UnitInventory(ships.quantities()),activeShips=ships,
            position=OutfitPosition(2487))
        val s=stocks().let {it.copy(outfits=it.outfits+(2 to fleet),warehouses=it.warehouses+(102 to OutfitWarehouse(102,2,OutfitKind.FLEET,1000,2)),nextShipId=13)}
        val r=act(player(10f),s)
        assertNull(r.rejection,"${r.rejection}");assertEquals(2,r.outcome,"the flagship and the one unit in range")
        assertTrue(assertNotNull(r.stocks).outfits.getValue(1).activeGround!!.values.all {it.strength==1000-2*OrbitalStrikeActions.DAMAGE})
    }
    @Test fun aGarrisonSavedOnlyAsQuantitiesGetsIdentitiesAndIsDamaged() {
        val legacy=OutfitStock(3,3,OutfitKind.GROUND,1000,101,units=UnitInventory(troops=mapOf(UnitStack(4) to 2)))
        val s=stocks().let {it.copy(outfits=it.outfits-1+(3 to legacy))}
        val r=act(player(10f),s)
        assertNull(r.rejection,"${r.rejection}")
        val roster=assertNotNull(r.stocks).outfits.getValue(3).activeGround!!.values
        assertEquals(2,roster.size);assertTrue(roster.all {it.base==1000 && it.strength==1000-OrbitalStrikeActions.DAMAGE})
        assertEquals(s.nextShipId+2,r.stocks!!.nextShipId)
    }
    @Test fun aPlanetUnderGroundBattleCannotBeBombarded() {
        // User decision 2026-11-02: orbital fire prepares a landing; it does not support a running ground battle.
        val s=stocks().let {it.copy(groundBattles=mapOf(1000 to now))}
        assertEquals(SocialRejection.COOLDOWN,act(player(10f),s).rejection)
    }
    @Test fun onlyThePlanetHoldersCampDefends() {
        // A rebel-held planet (camp 1): the regular side's troops landed there are not its garrison.
        val s=stocks().let {it.copy(bases=it.bases+(1000 to it.bases.getValue(1000).copy(camp=1)))}
        val r=act(player(10f),s)
        assertNull(r.rejection,"${r.rejection}");assertEquals(0,r.report)
        assertEquals(s.outfits.getValue(1),assertNotNull(r.stocks).outfits.getValue(1))
    }
}
