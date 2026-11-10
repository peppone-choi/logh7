package org.logh7.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

class FortressArmorTest {
    private val mobile=GalaxyBase(1001,2487,112,1,"Mobile",0,12f,86400,0,0f,2f,mobile=true)
    private val fixed=GalaxyBase(1002,2487,110,1,"Fixed",0,20f,86400,0,0f,2f)
    private val planet=GalaxyBase(1003,2487,60,0,"Planet",0,28f,86400,0,0f,2f)
    private val stocks=PlanetStocks(1,0,listOf(mobile,fixed,planet).associate {it.id to PlanetStock(it.id,2,0,0,0,0)})
    @Test fun orbitalStrikeReturnsToTheSelectedPlanet() = checkOrbitalReturn(selected=true)

    @Test fun orbitalStrikeReturnsToTheOriginPlanetWhenUnset() = checkOrbitalReturn(selected=false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun checkOrbitalReturn(selected:Boolean) = runTest {
        val rules = SessionRules.load()
        val home = planet.copy(grid = 2490)
        val origin = planet.copy(id = 1004, grid = 2491, name = "Origin")
        val expected = if (selected) home else origin
        val galaxy = Galaxy(
            listOf(GalaxySystem(3,2487,4,"Battle",3),GalaxySystem(4,2490,4,"Home",3),
                GalaxySystem(5,2491,4,"Origin",3)),
            listOf(fixed,home,origin),mapOf(Power.EMPIRE to fixed.grid,Power.ALLIANCE to home.grid))
        var storedStocks = stocks.copy(
            bases = listOf(fixed,home,origin).associate { it.id to PlanetStock(it.id,3,0,0,0,0) },
            deploymentInitialized = true,
            tacticalCommands = TacticalCommandState(fortressDamage = mapOf(fixed.id to 9500)))
        val now = SessionSimulation(rules).exportState().gameMillis / 1000
        val center = TacticalBasePosition.at(fixed,now,storedStocks)
        val point = TacticalPoint(center.x+fixed.diameter+5f,center.y)
        val attacker = Navigator(1,10000,Power.EMPIRE,fixed.grid,1000,1000,900,mode=5,cpPaused=true,
            motion=TacticalMotion(fixed.grid,now,TacticalPose(point,0f),0f,0f,listOf(point)))
        val defender = Navigator(1,10001,Power.ALLIANCE.country,fixed.grid,1000,1000,900,
            baseId=fixed.id,mode=4,returnBase=if (selected) home.id else 0,originBase=origin.id)
        var storedPlayers = mapOf("attacker" to attacker,"defender" to defender)
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,
            navigationStore=object : NavigationStore {
                override fun load() = storedPlayers
                override fun save(value:Map<String,Navigator>) { storedPlayers = value }
            },
            stockStore=object : PlanetStockStore {
                override fun load() = storedStocks
                override fun save(value:PlanetStocks) { storedStocks = value }
            },
            worldStateCommitter=WorldStateCommitter { players, inventory ->
                storedPlayers = players
                storedStocks = checkNotNull(inventory)
            },
            npcPolicy=NpcTacticalPolicy(enabled=false))
        data class Delivery(val event:Navigator,val saved:Navigator,val published:Navigator)
        val deliveries = mutableListOf<Delivery>()
        val subscription = engine.observeFortressSinking { event ->
            deliveries += Delivery(event,storedPlayers.getValue("defender"),
                engine.snapshot().navigators.getValue("defender"))
        }
        suspend fun strike(request:SocialRequest):SocialResult {
            val reply = CompletableDeferred<SocialResult>()
            engine.submit(WorldCommand.Social(request,reply))
            return reply.await()
        }
        try {
            for ((account,power) in listOf("attacker" to Power.EMPIRE,"defender" to Power.ALLIANCE)) {
                val reply = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join(account,power,false,reply,account,1))
                assertEquals(Admission.ACCEPTED,reply.await())
            }
            engine.submit(WorldCommand.AdvanceTo(1000))
            runCurrent()
            assertEquals(fixed.id,engine.snapshot().navigators.getValue("defender").baseId)
            assertTrue(deliveries.isEmpty())

            val request = StrategyActionRequest("attacker","attacker",10000,Power.EMPIRE,1,OrbitalStrikeActions.STRIKE,0,1)
            val quote = strike(SocialRequest(request,phase=OrbitalStrikeActions.QUOTE_PHASE))
            assertNull(quote.rejection)
            assertEquals(fixed.id,quote.found)
            assertEquals(1,quote.outcome)
            val result = strike(SocialRequest(request.copy(base=quote.found,argument=quote.outcome,value=OrbitalStrikeActions.SUPPLIES)))
            assertNull(result.rejection)
            assertTrue(result.changed)
            assertEquals(0,FortressArmor.remaining(storedStocks,fixed.id))
            assertEquals(fixed.id,storedPlayers.getValue("defender").baseId)
            assertTrue(deliveries.isEmpty())

            engine.submit(WorldCommand.AdvanceTo(2000))
            runCurrent()
            val returned = engine.snapshot().navigators.getValue("defender")
            assertEquals(defender.characterId,returned.characterId)
            assertEquals(expected.grid,returned.grid)
            assertEquals(expected.id,returned.baseId)
            assertEquals(defender.returnBase,returned.returnBase)
            assertEquals(origin.id,returned.originBase)
            assertEquals(4,returned.mode)
            assertEquals(0,returned.supplies)
            assertTrue(assertNotNull(returned.hull).woundedUntil > engine.snapshot().session!!.gameSeconds)
            assertEquals(listOf(Delivery(returned,returned,returned)),deliveries)
            assertEquals(attacker.characterId,engine.snapshot().navigators.getValue("attacker").characterId)

            engine.submit(WorldCommand.AdvanceTo(3000))
            runCurrent()
            assertEquals(1,deliveries.size)
        } finally {
            subscription.close()
            engine.drainAndClose()
        }
    }

    @Test fun strikesWearArmorBreakMobilePropulsionAndLeavePlanetsAlone() {
        val once=FortressArmor.strike(mobile,stocks,4)
        assertEquals(FortressArmor.ARMOR-2000,FortressArmor.remaining(once,1001));assertFalse(FortressArmor.propulsion(once,1001))
        val fixedHit=FortressArmor.strike(fixed,stocks,10)
        assertTrue(FortressArmor.propulsion(fixedHit,1002),"only a mobile fortress has propulsion")
        assertFalse(FortressArmor.breached(fixedHit,1002))
        assertTrue(FortressArmor.breached(FortressArmor.strike(fixed,fixedHit,10),1002))
        assertEquals(FortressArmor.ARMOR,FortressArmor.strike(fixed,FortressArmor.strike(fixed,fixedHit,10),10).tacticalCommands.fortressDamage[1002])
        assertSame(stocks,FortressArmor.strike(planet,stocks,10))
        val repaired=FortressArmor.restore(once,1001)
        assertEquals(FortressArmor.ARMOR,FortressArmor.remaining(repaired,1001));assertTrue(FortressArmor.propulsion(repaired,1001))
    }
    @Test fun aBreachedFortressSinksItsHoldersStationedShipsAndDockedFlagshipsOnly() {
        val session=SessionSimulation(SessionRules.load()).exportState()
        val home=GalaxyBase(1004,2490,60,0,"Home",0,28f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3),GalaxySystem(4,2490,4,"Home",3)),listOf(fixed,home),Power.entries.associateWith {2487})
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(2,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1002)),
            ActiveShipUnit(3,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.ANCHORED,1002))))
        val start=PlanetStocks(1,0,mapOf(1002 to PlanetStock(1002,3,0,0,0,0),1004 to PlanetStock(1004,3,0,0,0,0)),
            mapOf(2 to OutfitStock(2,3,OutfitKind.FLEET,1002,102,units=UnitInventory(ships.quantities()),activeShips=ships)),
            mapOf(102 to OutfitWarehouse(102,3,OutfitKind.FLEET,1002,2)),nextShipId=10)
        val docked=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4)
        val enemy=Navigator(1,10002,Power.EMPIRE,2487,1000,1000,900,cpPaused=true)
        val players=mutableMapOf("a" to docked,"e" to enemy);val sunk=mutableListOf<Navigator>()
        assertSame(start,FortressArmor.advance(session,galaxy,players,start,sunk),"armor intact")
        val breached=FortressArmor.strike(fixed,start,20)
        assertTrue(FortressArmor.pending(breached,galaxy,players.values))
        val after=FortressArmor.advance(session,galaxy,players,breached,sunk)
        val units=after.outfits.getValue(2).activeShips!!.values
        assertEquals(0,units.single {it.id==2}.boats,"stationed inside");assertEquals(300,units.single {it.id==3}.boats,"anchored outside")
        assertEquals(listOf(10001),sunk.map {it.characterId});assertNotEquals(1002,players.getValue("a").baseId)
        assertEquals(enemy,players.getValue("e"))
    }
    @Test fun fortressReturnPublishesTheDesignationClearedByTheSameSettlement() {
        val session = SessionSimulation(SessionRules.load()).exportState()
        val preferred = planet.copy(grid = 2490)
        val origin = planet.copy(id = 1004, grid = 2491)
        val galaxy = Galaxy(
            listOf(GalaxySystem(3, fixed.grid, 4, "Battle", 3), GalaxySystem(4, preferred.grid, 4, "Preferred", 3),
                GalaxySystem(5, origin.grid, 4, "Origin", 3)),
            listOf(fixed, preferred, origin), Power.entries.associateWith { fixed.grid })
        val ground = ActiveGroundUnits(listOf(ActiveGroundUnit(1, UnitStack(0), base = preferred.id)))
        val before = FortressArmor.strike(fixed, PlanetStocks(1, 0,
            listOf(fixed, preferred, origin).associate { it.id to PlanetStock(it.id, 3, 0, 0, 0, 0) },
            mapOf(1 to OutfitStock(1, 2, OutfitKind.GROUND, preferred.id, 101,
                units = UnitInventory(troops = ground.quantities()), activeGround = ground)),
            mapOf(101 to OutfitWarehouse(101, 2, OutfitKind.PATROL, preferred.id)),
            deploymentInitialized = true, nextShipId = 2), 20)
        val defender = Navigator(1, 10001, Power.ALLIANCE.country, fixed.grid, 1000, 1000, 900,
            baseId = fixed.id, mode = 4, returnBase = preferred.id, originBase = origin.id)
        val navigation = Navigation(galaxy, object : NavigationStore {
            override fun load() = mapOf("defender" to defender)
            override fun save(players: Map<String, Navigator>) = error("expected a world save")
        }, NavigationPolicy())
        val production = PlanetProduction(galaxy, object : PlanetStockStore {
            override fun load() = before
            override fun save(stocks: PlanetStocks) = error("expected a world save")
        }, session.gameMillis / 1000, PlanetProductionPolicy())
        var saved: Navigator? = null
        val sinkings = mutableListOf<Navigator>()
        TacticalCombat.settle(session, galaxy, navigation, production,
            WorldStateCommitter { players, _ -> saved = players.getValue("defender") },
            onFortressSunk = sinkings::add)

        val returned = assertNotNull(saved)
        assertEquals(Power.EMPIRE.country, production.snapshot(1)!!.bases.getValue(preferred.id).country)
        assertEquals(origin.id, returned.baseId)
        assertEquals(0, returned.returnBase)
        assertEquals(returned, navigation.snapshot(1).getValue("defender"))
        assertEquals(listOf(returned), sinkings)
    }

    @Test fun staleFlagshipsAndNonFortressDamageDoNotKeepBreachSettlementPending() {
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(fixed,planet),Power.entries.associateWith {2487})
        val docked=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,900,baseId=fixed.id,mode=4)
        val breached=FortressArmor.strike(fixed,stocks,20)
        assertFalse(FortressArmor.pending(breached,galaxy,listOf(docked.copy(generation=2))))
        val invalidDamage=stocks.copy(tacticalCommands=stocks.tacticalCommands.copy(
            fortressDamage=mapOf(planet.id to FortressArmor.ARMOR)))
        assertFalse(FortressArmor.pending(invalidDamage,galaxy,listOf(docked.copy(baseId=planet.id))))
    }

    @Test fun aCoupSettlingOnTheSinkingTickReroutesTheFortressReturn() = checkCoupReturn(fortress = true)

    @Test fun aCoupSettlingOnTheSinkingTickReroutesTheTacticalImpactReturn() = checkCoupReturn(fortress = false)

    private fun checkCoupReturn(fortress: Boolean) {
        val initial = SessionSimulation(SessionRules.load()).exportState()
        val since = initial.gameMillis / 1000 + 1
        val now = since + CoupActions.CAPITAL_DAYS * Justice.DAY
        val session = initial.copy(gameMillis = now * 1000)
        val capital = planet.copy(grid = 2015, name = Capital.ALLIANCE.baseName)
        val origin = planet.copy(id = 1004, grid = 2491, name = "Origin")
        val country = Power.ALLIANCE.country
        val galaxy = Galaxy(listOf(GalaxySystem(3, fixed.grid, 4, "Fortress", 3),
            GalaxySystem(4, capital.grid, 4, "Capital", 3), GalaxySystem(5, origin.grid, 4, "Origin", 3)),
            listOf(fixed, capital, origin), mapOf(Power.EMPIRE to fixed.grid, Power.ALLIANCE to capital.grid))
        val winning = Plot(10001, country, since, revolt = since, bases = setOf(capital.id),
            capitalSince = since, capitalBase = capital.id)
        val continuing = Plot(10002, country, since, members = listOf(10003, 10004), revolt = since, bases = setOf(fixed.id, origin.id))
        val stocks = PlanetStocks(1, now / 86400,
            listOf(fixed, capital, origin).associate { it.id to PlanetStock(it.id, 3, 1, 0, 0, 0) },
            deploymentInitialized = true, social = SocialState(coup = CoupState(mapOf(winning.leader to winning,
                continuing.leader to continuing))))
        val before = if (fortress) FortressArmor.strike(fixed, stocks, 20) else stocks
        fun stationary(point: TacticalPoint) = TacticalMotion(fixed.grid, now, TacticalPose(point, 0f), 0f, 0f, listOf(point))
        val defender = Navigator(1, 10003, country, fixed.grid, 1000, 1000, 900,
            returnBase = capital.id, originBase = origin.id).let {
            if (fortress) it.copy(baseId = fixed.id, mode = 4) else it.copy(cpPaused = true,
                hull = FlagshipHull(89, now, hp = 1, shields = List(6) { 0L }), motion = stationary(TacticalPoint(180f, -95f)))
        }
        assertEquals(capital.id, FlagshipRecovery.defeated(defender, now, galaxy, before).baseId)
        val occupant = Navigator(1, 10004, country, capital.grid, 1000, 1000, 900, baseId = capital.id, mode = 4)
        val attacker = Navigator(1, 10005, Power.EMPIRE, fixed.grid, 1000, 1000, 900, cpPaused = true,
            energy = TacticalEnergy(List(11) { if (it == 3) 100 else 0 }), charge = TacticalCharge(now, 24000),
            hull = FlagshipHull(0, now), motion = stationary(TacticalPoint(180f, -97f)),
            fire = TacticalFire(1, TacticalPendingFire(1, fixed.grid, 0, defender.unitId, now, now)))
        val players = mapOf("defender" to defender, "occupant" to occupant) +
            if (fortress) emptyMap() else mapOf("attacker" to attacker)
        val navigation = Navigation(galaxy, object : NavigationStore {
            override fun load() = players
            override fun save(players: Map<String, Navigator>) = error("expected a world save")
        }, NavigationPolicy())
        val production = PlanetProduction(galaxy, object : PlanetStockStore {
            override fun load() = before
            override fun save(stocks: PlanetStocks) = error("expected a world save")
        }, now, PlanetProductionPolicy())
        var saved: Navigator? = null
        var savedStocks: PlanetStocks? = null
        val sinkings = mutableListOf<Navigator>()
        val impacts = TacticalCombat.settle(session, galaxy, navigation, production, WorldStateCommitter { next, inventory ->
            saved = next.getValue("defender")
            assertEquals(occupant, next.getValue("occupant"))
            savedStocks = inventory
        }, onFortressSunk = sinkings::add)

        val returned = assertNotNull(saved)
        val committed = assertNotNull(savedStocks)
        assertEquals(0, committed.bases.getValue(capital.id).camp)
        assertEquals(1, CoupActions.camp(committed, defender.characterId, country))
        assertEquals(origin.id, returned.baseId)
        assertEquals(origin.grid, returned.grid)
        assertEquals(origin.id, returned.originBase)
        assertEquals(0, returned.returnBase)
        assertEquals(now + Justice.DAY, returned.hull?.woundedUntil)
        assertEquals(returned, navigation.snapshot(1).getValue("defender"))
        if (fortress) {
            assertTrue(impacts.isEmpty())
            assertEquals(listOf(returned), sinkings)
        } else {
            assertTrue(sinkings.isEmpty())
            assertEquals(returned, impacts.single().departed)
        }
    }

    @Test fun duringARevoltOnlyTheHoldingCampIsSunk() {
        // PR #214 review: a rebel fortress breach must spare loyal units of the same power stationed there.
        val session=SessionSimulation(SessionRules.load()).exportState()
        val home=GalaxyBase(1004,2490,60,0,"Home",0,28f,86400,0,0f,2f)
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",3),GalaxySystem(4,2490,4,"Home",3)),listOf(fixed,home),Power.entries.associateWith {2487})
        fun ships(id:Int)=ActiveShipUnits(listOf(ActiveShipUnit(id,UnitStack(64),posture=TacticalPosture(2487,TacticalShipMode.STATIONED,1002))))
        val coup=CoupState(mapOf(10001 to Plot(10001,CountryId(3),0,revolt=5)),loyalty=mapOf(2 to 60))
        val start=PlanetStocks(1,0,mapOf(1002 to PlanetStock(1002,3,1,0,0,0),1004 to PlanetStock(1004,3,0,0,0,0)),
            mapOf(2 to OutfitStock(2,3,OutfitKind.FLEET,1002,102,10001,units=UnitInventory(ships(2).quantities()),activeShips=ships(2)),
                3 to OutfitStock(3,3,OutfitKind.FLEET,1002,103,10003,units=UnitInventory(ships(3).quantities()),activeShips=ships(3))),
            mapOf(102 to OutfitWarehouse(102,3,OutfitKind.FLEET,1002,2),103 to OutfitWarehouse(103,3,OutfitKind.FLEET,1002,3)),nextShipId=10)
            .let {it.copy(social=it.social.copy(coup=coup))}
        val rebel=Navigator(1,10001,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4)
        val loyal=Navigator(1,10003,Power.ALLIANCE,2487,1000,1000,900,baseId=1002,mode=4)
        val players=mutableMapOf("r" to rebel,"l" to loyal);val sunk=mutableListOf<Navigator>()
        val after=FortressArmor.advance(session,galaxy,players,FortressArmor.strike(fixed,start,20),sunk)
        assertEquals(0,after.outfits.getValue(2).activeShips!!.values.single().boats,"rebel outfit inside")
        assertEquals(300,after.outfits.getValue(3).activeShips!!.values.single().boats,"loyal outfit of the same power")
        assertEquals(listOf(10001),sunk.map {it.characterId});assertEquals(loyal,players.getValue("l"))
        val loyalOnly=FortressArmor.strike(fixed,start,20).let {it.copy(outfits=it.outfits-2,warehouses=it.warehouses-102)}
        assertFalse(FortressArmor.pending(loyalOnly,galaxy,listOf(loyal)),"nothing of the holder's camp is inside")
    }
}
