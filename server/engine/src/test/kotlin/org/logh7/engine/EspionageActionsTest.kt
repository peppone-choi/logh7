package org.logh7.engine

import java.io.IOException
import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-17 工作: 잠입 판정과 실패 시 구금, 시한폭탄 폭발과 7일 생산 정지, 선동, 위조 신분 침입·귀환. 작성: Peppone Choi. */
class EspionageActionsTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val policy=OrganizationalOfficePolicy(galaxy)
    private val session=SessionSimulation(rules).also {it.join("a",Power.EMPIRE,false,"a")}.exportState()
    private val a=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,spotId=10490001)
    private val accounts=mapOf("a" to a)
    private fun stocks()=policy.set(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,
        economy=PlanetEconomy(1000,500,60,60)))),OfficeHolding(58,1049,10000))
    private fun act(command:Int,base:Int=0,s:PlanetStocks=stocks(),p:Navigator=a,at:SessionState=session):SocialResult=
        SocialActions.evaluate(galaxy,SocialRequest(StrategyActionRequest("a","a",10000,Power.EMPIRE,1,command,58,9,base=base)),
            at,p,s,accounts.values,mapOf("a" to p))

    @Test fun anInfiltrationEntersTheSpotOrIsCaughtAndDetained() {
        val spot=galaxy.facilitiesAt(1049).flatMap {it.spots}.first {it.id!=10490001}.id
        val result=act(EspionageActions.INFILTRATE,base=spot)
        assertNull(result.rejection);assertEquals(840,result.player?.mcp)
        val ok=EspionageActions.roll(10000,spot,session.gameMillis/1000)<EspionageActions.threshold(50,1)
        val s=assertNotNull(result.stocks)
        if(ok) {assertEquals(spot,result.player?.spotId);assertEquals(spot,s.social.justice.infiltrations.getValue(10000).spot)}
        else {assertTrue(s.social.justice.detained(10000),"caught");assertEquals(10490019,result.player?.spotId)}
    }
    @Test fun aBombStopsTheBaseForSevenDaysAndEscapeNeedsAnInfiltration() {
        assertEquals(SocialRejection.STALE,act(EspionageActions.ESCAPE).rejection)
        val armed=stocks().let {it.copy(social=it.social.copy(justice=it.social.justice.copy(bombs=mapOf(10000 to Bomb(10000,1049,10490001,100)))))}
        val players=accounts.toMutableMap()
        val exploded=EspionageActions.settle(galaxy,session.copy(gameMillis=200_000),players,armed)
        assertTrue(exploded.social.justice.bombs.isEmpty())
        assertTrue(EspionageActions.disabled(exploded,1049,100+6*86400L));assertFalse(EspionageActions.disabled(exploded,1049,100+7*86400L))
    }
    @Test fun sabotageOnlyArmsBombsAtStrategicFacilities() {
        val facilities = galaxy.facilitiesAt(1049, stocks())
        for (kind in listOf(0, 10, 15)) {
            val spot = facilities.single { it.kind == kind }.spots.first { it.id != a.spotId }.id
            val seconds = (1L..100L).first { EspionageActions.roll(a.characterId, spot, it) < EspionageActions.threshold(50, 1) }
            val at = session.copy(gameMillis = seconds * 1000)
            val infiltration = act(EspionageActions.INFILTRATE, base = spot, at = at)
            val infiltrator = assertNotNull(infiltration.player)
            val entered = assertNotNull(infiltration.stocks)
            assertEquals(1, infiltration.outcome)

            val result = act(EspionageActions.SABOTAGE, s = entered, p = infiltrator, at = at)
            if (kind == 15) {
                assertEquals(SocialRejection.TARGET, result.rejection)
                assertEquals(infiltrator, result.player)
                assertEquals(entered, result.stocks)
            } else {
                assertNull(result.rejection)
                assertEquals(infiltrator.mcp - 160, result.player?.mcp)
                assertEquals(spot, assertNotNull(result.stocks).social.justice.bombs.getValue(a.characterId).spot)
            }
        }
    }
    @Test fun aFormerShipyardRoomCannotBeInfiltratedOrUsedForOperations() {
        val initial = stocks()
        val spot = galaxy.facilitiesAt(1049, initial).single { it.kind == ProductionActions.SHIPYARD }.spots.single().id
        val seconds = (1L..100L).first { EspionageActions.roll(a.characterId, spot, it) < EspionageActions.threshold(50, 1) }
        val at = session.copy(gameMillis = seconds * 1000)
        val infiltration = act(EspionageActions.INFILTRATE, base = spot, s = initial, at = at)
        assertEquals(1, infiltration.outcome)
        val entered = assertNotNull(infiltration.stocks)
        val infiltrator = assertNotNull(infiltration.player)
        val razed = entered.copy(social = entered.social.copy(production = ProductionState(destroyed = setOf(1049))))

        assertEquals(SocialRejection.TARGET, act(EspionageActions.INFILTRATE, base = spot, s = razed, at = at).rejection)
        for (command in listOf(EspionageActions.SABOTAGE, EspionageActions.INTELLIGENCE)) {
            val denied = act(command, s = razed, p = infiltrator, at = at)
            assertEquals(SocialRejection.TARGET, denied.rejection)
            assertEquals(infiltrator, denied.player)
            assertSame(razed, denied.stocks)
        }
    }

    @Test fun leavingByNormalMovementEndsInfiltrationWithoutRemovingTheBomb() {
        val room = galaxy.facilitiesAt(1049).single { it.kind == 0 }.spots.single { it.kind == 2 }.id
        val start = session.gameMillis / 1000
        val seconds = (start..start + 100).first { EspionageActions.roll(a.characterId, room, it) < EspionageActions.threshold(50, 1) }
        val at = session.copy(gameMillis = seconds * 1000)
        val entered = act(EspionageActions.INFILTRATE, base = room, s = stocks().copy(deploymentInitialized = true), at = at)
        assertEquals(1, entered.outcome)
        val armed = act(EspionageActions.SABOTAGE, s = assertNotNull(entered.stocks), p = assertNotNull(entered.player), at = at)
        assertEquals(1, armed.outcome)
        var savedStocks = assertNotNull(armed.stocks)
        var savedPlayers = mapOf("a" to assertNotNull(armed.player))
        val navigation = Navigation(galaxy, object : NavigationStore {
            override fun load() = savedPlayers
            override fun save(players: Map<String, Navigator>) { savedPlayers = players }
        }, NavigationPolicy())
        val production = PlanetProduction(galaxy, object : PlanetStockStore {
            override fun load() = savedStocks
            override fun save(stocks: PlanetStocks) { savedStocks = stocks }
        }, start, PlanetProductionPolicy())
        production.settle(at)
        val save = WorldStateCommitter { players, inventory ->
            savedPlayers = players
            savedStocks = checkNotNull(inventory)
        }
        fun move(spot: Int, committer: WorldStateCommitter = save): NavigationResult = FleetNavigation.apply(
            NavigationRequest("a", a.characterId, Power.EMPIRE, "a", 1, place = PlaceMove.Spot(spot, 0)),
            at, galaxy, navigation, production, checkNotNull(production.snapshot(1)), committer) { false }

        assertEquals(NavigationRejection.SPOT, move(room).rejection)
        val before = savedPlayers
        val inventory = savedStocks
        assertFailsWith<IOException> { move(a.spotId, WorldStateCommitter { _, _ -> throw IOException("save failed") }) }
        assertEquals(before, savedPlayers)
        assertEquals(inventory, savedStocks)
        assertEquals(before, navigation.snapshot(1))
        assertEquals(inventory, production.snapshot(1))

        assertNull(move(a.spotId).rejection)
        assertEquals(a.spotId, savedPlayers.getValue("a").spotId)
        assertEquals(inventory.social.justice.copy(infiltrations = emptyMap()), savedStocks.social.justice)
        assertNull(move(room).rejection)
        val returned = savedPlayers.getValue("a")
        for (command in listOf(EspionageActions.INTELLIGENCE, EspionageActions.SABOTAGE, EspionageActions.ESCAPE)) {
            val result = act(command, s = savedStocks, p = returned, at = at)
            assertEquals(SocialRejection.STALE, result.rejection)
            assertEquals(returned, result.player)
            assertEquals(savedStocks, result.stocks)
        }
    }

    @Test fun agitationNeedsAnotherSidesBase() {
        assertEquals(SocialRejection.TARGET,act(EspionageActions.AGITATE).rejection,"own base")
    }
    @Test fun dailyDisguiseDetectionUsesRegisteredIntelligenceWithStoredPriorityAndDefault() {
        val host=Capital.ALLIANCE
        val base=galaxy.bases.single().copy(id=1177,grid=host.grid,name=host.baseName)
        val world=Galaxy(galaxy.systems+GalaxySystem(4,host.grid,4,"Host",3),galaxy.bases+base,galaxy.starts)
        val intruder=a.copy(grid=base.grid,baseId=base.id,spotId=base.id*10_000+1)
        val start=session.gameMillis/1000+Justice.DAY
        val due=(start..start+1000).first {EspionageActions.roll(a.characterId,2,it) in 10..19}
        val disguise=Disguise(a.characterId,2,Power.ALLIANCE.country,due-Justice.DAY,due,Power.EMPIRE.country)
        val initial=stocks()
        for((registered,stored,detected) in listOf(Triple(90,null,false),Triple(null,null,true),Triple(90,10,true))) {
            fun abilities(intelligence:Int)=List(8) {if(it==3)intelligence else 100-intelligence}
            val people=stored?.let {mapOf(a.characterId to StrategicPerson(abilities=abilities(it)))}.orEmpty()
            val inventory=initial.copy(bases=initial.bases+(base.id to PlanetStock(base.id,3,0,0,0,0)),
                strategic=initial.strategic.copy(people=people),
                social=initial.social.copy(justice=initial.social.justice.copy(disguises=mapOf(a.characterId to disguise))))
            val nav=Navigation(world,object:NavigationStore {
                override fun load()=mapOf("a" to intruder)
                override fun save(players:Map<String,Navigator>)=Unit
            },NavigationPolicy())
            val production=PlanetProduction(world,null,rules.startSeconds,PlanetProductionPolicy(),restored=inventory)
            production.settle(1,session.gameMillis/1000)
            var loads=0
            production.identities={
                loads++
                listOfNotNull(registered?.let {StrategicIdentity(a.characterId,2,9,0,abilities(it))})
            }
            val commit=WorldStateCommitter { _, _ -> }
            TacticalCombat.settle(session.copy(gameMillis=(due-1)*1000),world,nav,production,commit)
            assertEquals(0,loads)
            assertEquals(disguise,production.snapshot(1)?.social?.justice?.disguises?.get(a.characterId))

            TacticalCombat.settle(session.copy(gameMillis=due*1000),world,nav,production,commit)
            val justice=assertNotNull(production.snapshot(1)).social.justice
            assertEquals(if(stored==null)1 else 0,loads)
            assertEquals(detected,justice.detained(a.characterId))
            assertEquals(if(detected)null else disguise.copy(checkAt=due+Justice.DAY),justice.disguises[a.characterId])
        }
    }

    @Test fun anIntrusionCrossesIntoANearbyEnemyBaseUnderAForgedName() {
        val g=Galaxy(galaxy.systems+GalaxySystem(4,2490,4,"Far",3)+GalaxySystem(5,2499,4,"Out",3),galaxy.bases+GalaxyBase(1050,2490,80,0,"Far",0,20f,86400,0,0f,2f)+
            GalaxyBase(1051,2499,80,0,"Out",0,20f,86400,0,0f,2f),galaxy.starts)
        val s=stocks().let {it.copy(bases=it.bases+(1050 to PlanetStock(1050,3,0,0,0,0))+(1051 to PlanetStock(1051,3,0,0,0,0)))}
        fun go(command:Int,base:Int,value:Int=0,p:Navigator=a,st:PlanetStocks=s)=SocialActions.evaluate(g,SocialRequest(
            StrategyActionRequest("a","a",10000,Power.EMPIRE,1,command,58,9,base=base,value=value)),session,p,st,accounts.values,mapOf("a" to p))
        assertEquals(SocialRejection.LOCATION,go(EspionageActions.INTRUDE,1051,2).rejection,"beyond 10 cells")
        assertEquals(SocialRejection.TARGET,go(EspionageActions.RETURN,1050).rejection,"return needs an own base")
        val result=go(EspionageActions.INTRUDE,1050,2)
        assertNull(result.rejection);assertEquals(680,result.player?.mcp)
        val next=assertNotNull(result.stocks)
        if(result.outcome==1) {
            assertEquals(1050,result.player?.baseId)
            val d=next.social.justice.disguises.getValue(10000);assertEquals(2,d.alias);assertEquals(CountryId(3),d.country)
            val back=go(EspionageActions.RETURN,1049,p=checkNotNull(result.player),st=next)
            assertEquals(1,back.outcome);assertEquals(1049,back.player?.baseId)
            assertTrue(assertNotNull(back.stocks).social.justice.disguises.isEmpty())
        } else assertTrue(next.social.justice.detained(10000),"caught at the border")
    }
}
