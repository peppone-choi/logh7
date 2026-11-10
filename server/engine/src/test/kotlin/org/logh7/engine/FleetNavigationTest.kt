package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class FleetNavigationTest {
    private val home=GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)
    private val enemy=home.copy(id=1001,grid=2488,name="Enemy")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",3),GalaxySystem(4,2488,4,"Enemy",2)),
        listOf(home,enemy),Power.entries.associateWith { 2487 })
    private val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.ALLIANCE,false,"c")}.exportState()
    private val request=NavigationRequest("pilot",10000,Power.ALLIANCE,"c",1,unitId=1010000,captainCard=195)
    private class State(initial: PlanetStocks, player: Navigator) {
        var stocks=initial
        var players=mapOf("pilot" to player)
        var fail=false
        var commits=0
        val nav=object:NavigationStore {
            override fun load()=players
            override fun save(players:Map<String,Navigator>) {this@State.players=players.toMap()}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) {this@State.stocks=stocks}
        }
        val commit=WorldStateCommitter { navigation, inventory ->
            if(fail)throw java.io.IOException("atomic save failed")
            commits++
            players=navigation.toMap();stocks=checkNotNull(inventory)
        }
    }
    private fun state():State {
        val quay=galaxy.facilities.getValue(1000).single {it.kind==4}.spots.single {it.kind==6}
        return State(PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,3,0,100,100,100),
            1001 to PlanetStock(1001,2,0,100,100,100)),
            mapOf(1 to OutfitStock(1,3,OutfitKind.FLEET,1000,10,commanderId=10000,
                units=UnitInventory(mapOf(UnitStack(89) to 10),mapOf(UnitStack(4) to 3,UnitStack(7) to 40)))),
            mapOf(10 to OutfitWarehouse(10,3,OutfitKind.FLEET,1000,1,
                units=UnitInventory(mapOf(UnitStack(89) to 200)))),deploymentInitialized=true),
            Navigator(1,10000,Power.ALLIANCE,2487,1000,1000,1000,baseId=1000,mode=4,spotId=quay.id))
    }
    private fun apply(state:State, vararg requests:NavigationRequest, blocked:Boolean=false,
        committer:WorldStateCommitter?=state.commit):List<NavigationResult> {
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        return requests.map { FleetNavigation.apply(it,session,galaxy,navigation,production,
            production.settle(1,session.gameMillis/1000),committer) {blocked} }
    }
    @Test fun departureAndFleetWarpMoveEveryDeployedUnitWithoutMovingTheDepotAndSurviveReload() {
        val state=state()
        val results=apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2488))
        assertTrue(results.all {it.rejection==null})
        assertEquals(OutfitPosition(2488),state.stocks.outfits.getValue(1).position)
        assertEquals(1000,state.stocks.warehouses.getValue(10).baseId)
        assertEquals(200,state.stocks.warehouses.getValue(10).units.ships.getValue(UnitStack(89)))
        val player=state.players.getValue("pilot")
        assertEquals(2488,player.grid);assertEquals(960,player.mcp);assertEquals(900,player.cruise)
        val forces=GridForces.collect(galaxy,1,state.players.values,state.stocks)
        assertEquals(14L,forces.getValue(2488).single {it.camp.country==Power.ALLIANCE.country}.units)
        assertEquals(0L,forces.getValue(2487).single().units)
        assertEquals(player,apply(state,request).single().player)
    }

    @Test fun leavingTheInfiltratedQuayWithAConvoyClearsOnlyDepartingInfiltrationsOnCommit() {
        val state = state()
        addAide(state)
        val captain = state.players.getValue("pilot")
        val aide = state.players.getValue("aide")
        val until = session.gameMillis / 1000 + Justice.DAY
        val captainInfiltration = Infiltration(captain.characterId, home.id, captain.spotId, until, captain.country, captain.country)
        val aideInfiltration = captainInfiltration.copy(character = aide.characterId)
        val remainingInfiltration = captainInfiltration.copy(character = 10002)
        val infiltrations = listOf(captainInfiltration, aideInfiltration, remainingInfiltration).associateBy { it.character }
        state.stocks = state.stocks.copy(social = state.stocks.social.copy(
            justice = state.stocks.social.justice.copy(infiltrations = infiltrations)))
        val navigation = Navigation(galaxy, state.nav, NavigationPolicy())
        val production = PlanetProduction(galaxy, state.inventory, SessionRules.load().startSeconds, PlanetProductionPolicy())
        val initialStocks = production.settle(1, session.gameMillis / 1000)
        val initialPlayers = state.players
        fun depart() = FleetNavigation.apply(request.copy(place = PlaceMove.SwitchMode(5)), session, galaxy, navigation,
            production, assertNotNull(production.snapshot(1)), state.commit) { false }

        state.fail = true
        assertFailsWith<java.io.IOException> { depart() }
        assertEquals(initialStocks, state.stocks)
        assertEquals(initialPlayers, state.players)
        assertEquals(initialStocks, production.snapshot(1))
        assertEquals(initialPlayers, navigation.snapshot(1))
        assertEquals(0, state.commits)

        state.fail = false
        assertNull(depart().rejection)
        assertEquals(1, state.commits)
        assertEquals(OutfitPosition(home.grid), state.stocks.outfits.getValue(1).position)
        assertTrue(state.players.values.all { it.baseId == 0 && it.spotId == 0 })
        assertEquals(mapOf(remainingInfiltration.character to remainingInfiltration), state.stocks.social.justice.infiltrations)
    }

    @Test fun directDepartureIntoBattleKeepsFlagshipAndFleetNearTheSameBerth() {
        for (command in listOf(PlaceMove.SwitchMode(5), PlaceMove.Dock(0,5))) {
            val state = state()
            if (command is PlaceMove.Dock) state.players = state.players.mapValues { (_, player) -> player.copy(spotId = 0) }
            addAide(state)
            val fleet = state.stocks.outfits.getValue(1)
            state.stocks = ActiveShips.initialize(state.stocks.copy(
                outfits = state.stocks.outfits + (2 to OutfitStock(2,2,OutfitKind.PATROL,1001,20,
                    units = UnitInventory(mapOf(UnitStack(3) to 1)), position = OutfitPosition(home.grid))),
                warehouses = state.stocks.warehouses + (20 to OutfitWarehouse(20,2,OutfitKind.PATROL,1001))))
            val now = session.gameMillis / 1000
            val anchored = state.stocks.outfits.getValue(fleet.id).activeShips!!.values.first().let { unit ->
                unit.copy(posture = TacticalPosture(home.grid,TacticalShipMode.ANCHORED,home.id))
            }
            val initialized = state.stocks.outfits.getValue(fleet.id)
            state.stocks = state.stocks.copy(outfits = state.stocks.outfits + (fleet.id to initialized.copy(
                activeShips = ActiveShipUnits(listOf(anchored) + initialized.activeShips!!.values.drop(1)))))
            val departure = apply(state, request.copy(place = command)).single()
            val captain = assertNotNull(departure.player, departure.rejection?.name)
            val aide = state.players.getValue("aide")
            assertTrue(captain.cpPaused)
            assertEquals(OutfitPosition(home.grid), state.stocks.outfits.getValue(fleet.id).position)
            val ships = state.stocks.outfits.getValue(fleet.id).activeShips!!.values
            assertEquals(anchored, ships.first())
            val points = (listOf(captain.motion, aide.motion) + ships.drop(1).map { it.tactical?.motion })
                .map { assertNotNull(it).pose(now).position }
            val centre = TacticalBasePosition.at(home, now, state.stocks)
            assertEquals(points.size, points.toSet().size)
            assertTrue(points.all { kotlin.math.hypot(it.x - centre.x, it.y - centre.y) in 5.5f..7f })
            assertEquals(captain.motion, apply(state, request).single().player?.motion)
        }
    }
    @Test fun wholeFleetCapacityIsCheckedBeforeAnyCostOrPositionChange() {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        state.stocks=ActiveShips.initialize(state.stocks.copy(outfits=state.stocks.outfits+
            (2 to OutfitStock(2,3,OutfitKind.PATROL,1001,20,
                units=UnitInventory(mapOf(UnitStack(89) to 287)))),
            warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1001))))
        val before=state.players.toMap();val inventory=state.stocks
        assertEquals(NavigationRejection.GRID,apply(state,request.copy(destination=2488)).single().rejection)
        assertEquals(before,state.players);assertEquals(inventory,state.stocks)
    }
    @Test fun atomicFailurePublishesNeitherNewFlagshipNorFleetPosition()=runBlocking {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit)
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            val ready=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request,ready));ready.await()
            val before=engine.snapshot()
            state.fail=true
            val moved=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request.copy(destination=2488),moved))
            assertFailsWith<java.io.IOException> {moved.await()}
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
        } finally {engine.drainAndClose()}
    }
    @Test fun startingPeerageCommitsWithTheFirstNavigatorAndNeverOverwritesExistingPersonnel() {
        val state=state()
        state.players=emptyMap()
        val imperial=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,true,"c")}.exportState()
        val request=NavigationRequest("pilot",1001,Power.EMPIRE,"c",1,shipKind=3)
        val titled=StrategicPerson(rank=10,peerage=Peerage.COUNT)
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        val inventory=production.settle(1,imperial.gameMillis/1000)
        state.fail=true
        assertFailsWith<java.io.IOException> {
            FleetNavigation.apply(request,imperial,galaxy,navigation,production,inventory,state.commit,titled) {false}
        }
        assertTrue(navigation.snapshot(1).isEmpty())
        assertNull(production.snapshot(1)?.strategic?.people?.get(1001))
        state.fail=false
        assertNull(FleetNavigation.apply(request,imperial,galaxy,navigation,production,inventory,state.commit,titled) {false}.rejection)
        assertEquals(titled,state.stocks.strategic.people[1001])
        assertEquals(1,state.commits)
        val cleared=state.stocks.copy(strategic=state.stocks.strategic.copy(people=mapOf(1001 to titled.copy(peerage=null))))
        val reopened=PlanetProduction(galaxy,object:PlanetStockStore {
            override fun load()=cleared
            override fun save(stocks:PlanetStocks)=Unit
        },SessionRules.load().startSeconds,PlanetProductionPolicy())
        FleetNavigation.apply(request,imperial,galaxy,navigation,reopened,cleared,state.commit,titled) {false}
        assertNull(reopened.snapshot(1)?.strategic?.people?.get(1001)?.peerage)
        assertEquals(1,state.commits)
    }
    @Test fun admissionAllowsSameCountryOriginalReplacementOnlyAfterDeceasedPredecessorDisconnects() = runBlocking {
        val state = state()
        val old = state.players.getValue("pilot")
        fun start() = WorldEngine(this, ticking = false, galaxy = galaxy, navigationStore = state.nav,
            stockStore = state.inventory, worldStateCommitter = state.commit)
        suspend fun WorldEngine.join(country: CountryId, id: Int, original: Boolean, connection: String): Admission {
            val result = CompletableDeferred<Admission>()
            submit(WorldCommand.Join("pilot", country, original, result, connection, 1, characterId = id))
            return withTimeout(5000) { result.await() }
        }
        val engine = start()
        try {
            assertEquals(Admission.ACCEPTED, engine.join(CountryId(3), old.characterId, false, "old"))
            engine.submit(WorldCommand.Disconnect("pilot", "old"))
            assertEquals(Admission.IDENTITY_CHANGED, engine.join(CountryId(3), 1002, true, "new"))
        } finally { engine.drainAndClose() }
        state.stocks = BattleDeath.apply(state.stocks, listOf(old.characterId), 1) { _, _ -> true }
        val restored = start()
        try {
            assertEquals(Admission.ACCEPTED, restored.join(CountryId(3), old.characterId, false, "old"))
            assertEquals(Admission.IDENTITY_CHANGED, restored.join(CountryId(3), 1002, true, "new"))
            restored.submit(WorldCommand.Disconnect("pilot", "old"))
            assertEquals(Admission.IDENTITY_CHANGED, restored.join(CountryId(2), 1001, true, "new"))
            assertEquals(Admission.ACCEPTED, restored.join(CountryId(3), 1002, true, "new"))
        } finally { restored.drainAndClose() }
    }

    @Test fun originalFleetAideEntryCommitsOnceWithoutReplacingAnExistingHolder() {
        val odin=home.copy(id=1049,name=Capital.EMPIRE.baseName)
        val world=Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2)),listOf(odin),Power.entries.associateWith {2487})
        val imperial=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,true,"c")}.exportState()
        val request=NavigationRequest("pilot",1001,Power.EMPIRE.country,"c",1,shipKind=3,initializeOriginal=true)
        val initial=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,100,100)),
            outfits=mapOf(2 to OutfitStock(2,2,OutfitKind.FLEET,1049,1002,number=2)),
            warehouses=mapOf(1002 to OutfitWarehouse(1002,2,OutfitKind.FLEET,1049,2)),deploymentInitialized=true)
        val predecessor=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000)
        val state=State(initial,predecessor)
        val titled=StrategicPerson(rank=10,peerage=Peerage.COUNT)
        fun enter(profile:StrategicPerson?=null):NavigationResult {
            val navigation=Navigation(world,state.nav,NavigationPolicy())
            val production=PlanetProduction(world,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
            return FleetNavigation.apply(request,imperial,world,navigation,production,
                production.settle(1,imperial.gameMillis/1000),state.commit,profile) {false}
        }
        assertEquals(NavigationRejection.IDENTITY,enter(titled).rejection)
        assertEquals(predecessor,state.players.getValue("pilot"))
        assertEquals(0,state.commits)
        state.stocks=BattleDeath.apply(initial,listOf(predecessor.characterId),imperial.gameMillis/1000) {_,_->true}

        state.fail=true
        assertFailsWith<java.io.IOException> {enter(titled)}
        assertEquals(predecessor,state.players.getValue("pilot"))
        assertNull(state.stocks.strategic.people[1001])
        assertEquals(0,state.stocks.outfits.getValue(2).aideId)

        state.fail=false
        val docked=assertNotNull(enter(titled).player)
        assertEquals(docked,state.players.getValue("pilot"))
        assertEquals(titled,state.stocks.strategic.people[1001])
        assertTrue(BattleDeath.deceased(state.stocks,predecessor.characterId))
        assertEquals(1049,docked.baseId)
        assertEquals(4,docked.mode)
        assertEquals(0,docked.spotId)
        assertEquals(1001,state.stocks.outfits.getValue(2).aideId)
        assertEquals(1,state.commits)

        val resigned=OfficeResignation.evaluate(world,OfficeResignationRequest("pilot","c",1001,Power.EMPIRE,1,43),
            imperial,docked,state.stocks)
        assertTrue(resigned.changed)
        state.stocks=assertNotNull(resigned.stocks)
        state.players=mapOf("pilot" to assertNotNull(resigned.player))
        assertEquals(resigned.player,enter().player)
        assertEquals(0,state.stocks.outfits.getValue(2).aideId)
        assertEquals(1,state.commits)

        state.players=emptyMap()
        state.stocks=initial.copy(outfits=mapOf(2 to initial.outfits.getValue(2).copy(aideId=10002)))
        assertEquals(0,assertNotNull(enter().player).baseId)
        assertEquals(10002,state.stocks.outfits.getValue(2).aideId)
        assertEquals(1,state.commits)

        state.players=emptyMap()
        val rebelFleet=initial.outfits.getValue(2).copy(commanderId=10003)
        val rebellion=CoupState(plots=mapOf(10003 to Plot(10003,Power.EMPIRE.country,0,revolt=1)),
            loyalty=mapOf(2 to CoupActions.LOYAL))
        state.stocks=initial.copy(outfits=mapOf(2 to rebelFleet),social=initial.social.copy(coup=rebellion))
        assertEquals(0,state.stocks.bases.getValue(1049).camp)
        assertEquals(1,CoupActions.outfitCamp(state.stocks,rebelFleet))
        assertEquals(0,assertNotNull(enter().player).baseId)
        assertEquals(10003,state.stocks.outfits.getValue(2).commanderId)
        assertEquals(0,state.stocks.outfits.getValue(2).aideId)
        assertEquals(1,state.commits)
    }
    @Test fun aBoundOriginalTakesTheVacantFleetCommandOnlyOnFirstEntry() {
        val vucock=OriginalStartingOffice.VUCOCK_FLEET_5
        assertEquals(listOf(3,137,5,1086,175),listOf(vucock.country.value,vucock.fleetId,vucock.fleetNumber,vucock.baseId,vucock.role.card))
        val staden=OriginalStartingOffice.STADEN_FLEET_4
        assertEquals(listOf(2,4,4,1015,39),listOf(staden.country.value,staden.fleetId,staden.fleetNumber,staden.baseId,staden.role.card))
        val paetta=OriginalStartingOffice.PAETTA_FLEET_2
        assertEquals(listOf(3,134,2,1177,175),listOf(paetta.country.value,paetta.fleetId,paetta.fleetNumber,paetta.baseId,paetta.role.card))
        val fogel=OriginalStartingOffice.FOGEL_FLEET_5
        assertEquals(listOf(2,5,5,1015,39),listOf(fogel.country.value,fogel.fleetId,fogel.fleetNumber,fogel.baseId,fogel.role.card))
        val merkatz=OriginalStartingOffice.MERKATZ_FLEET_3
        assertEquals(listOf(2,3,3,1026,39,4),listOf(merkatz.country.value,merkatz.fleetId,merkatz.fleetNumber,
            merkatz.baseId,merkatz.role.card,merkatz.rank))
        val hawood=OriginalStartingOffice.HAWOOD_FLEET_7
        assertEquals(listOf(3,139,7,1230,175,5),listOf(hawood.country.value,hawood.fleetId,hawood.fleetNumber,
            hawood.baseId,hawood.role.card,hawood.rank))
        val lefebvre=OriginalStartingOffice.LEFEBVRE_FLEET_3
        assertEquals(listOf(3,135,3,1230,175,5),listOf(lefebvre.country.value,lefebvre.fleetId,lefebvre.fleetNumber,
            lefebvre.baseId,lefebvre.role.card,lefebvre.rank))
        val fortressOffices=mapOf(
            OriginalStartingOffice.HILDESHEIM_FLEET_6 to (6 to 1221),
            OriginalStartingOffice.LANDSBERG_FLEET_8 to (8 to 1100),
            OriginalStartingOffice.FLEGEL_FLEET_10 to (10 to 1001))
        for((office,site) in fortressOffices) {
            val (number,base)=site
            assertEquals(listOf(2,number,number,base,39,5),
                listOf(office.country.value,office.fleetId,office.fleetNumber,office.baseId,office.role.card,office.rank))
        }
        val seekt=OriginalStartingOffice.SEEKT_FLEET_12
        assertEquals(listOf(2,12,12,1040,39,4),
            listOf(seekt.country.value,seekt.fleetId,seekt.fleetNumber,seekt.baseId,seekt.role.card,seekt.rank))
        val cubrusly=OriginalStartingOffice.CUBRUSLY_FLEET_1
        assertEquals(listOf(3,133,1,1177,175,5),
            listOf(cubrusly.country.value,cubrusly.fleetId,cubrusly.fleetNumber,cubrusly.baseId,cubrusly.role.card,cubrusly.rank))
        for(office in OriginalStartingOffice.entries)checkFirstFleetCommand(office)
    }

    private fun checkFirstFleetCommand(office:OriginalStartingOffice) {
        val power=if(office.country.value==2)Power.EMPIRE else Power.ALLIANCE
        val wrongPower=Power.entries.single {it!=power}
        val start=GalaxySystem(3,2487,4,"Home",office.country.value)
        val base=when(office) {
            OriginalStartingOffice.REINHARD_FLEET_2 -> home.copy(id=office.baseId,name=Capital.EMPIRE.baseName)
            OriginalStartingOffice.VUCOCK_FLEET_5 -> home.copy(id=office.baseId,name="Santa Ana")
            OriginalStartingOffice.STADEN_FLEET_4,OriginalStartingOffice.FOGEL_FLEET_5 ->
                home.copy(id=1015,grid=1260,model=51,name="Hafen")
            OriginalStartingOffice.PAETTA_FLEET_2,OriginalStartingOffice.CUBRUSLY_FLEET_1 ->
                home.copy(id=1177,grid=2015,model=61,name=Capital.ALLIANCE.baseName)
            OriginalStartingOffice.MERKATZ_FLEET_3 -> home.copy(id=1026,grid=3280,name="Neuenrade")
            OriginalStartingOffice.HAWOOD_FLEET_7,OriginalStartingOffice.LEFEBVRE_FLEET_3 ->
                home.copy(id=1230,grid=3744,model=102,name="Lusiana")
            OriginalStartingOffice.HILDESHEIM_FLEET_6 ->
                home.copy(id=1221,grid=3369,model=114,kind=1,name="Rentenberg")
            OriginalStartingOffice.LANDSBERG_FLEET_8 ->
                home.copy(id=1100,grid=4472,model=113,kind=1,name="Garmisch")
            OriginalStartingOffice.FLEGEL_FLEET_10 ->
                home.copy(id=1001,grid=3757,model=112,kind=1,name="Geiersburg")
            OriginalStartingOffice.SEEKT_FLEET_12 ->
                home.copy(id=1040,grid=1253,model=110,kind=1,name="Iserlohn")
        }
        val systems=if(base.grid==start.grid)listOf(start) else
            listOf(start,GalaxySystem(4,base.grid,4,base.name,power.country.value))
        val world=Galaxy(systems,listOf(base),Power.entries.associateWith {start.grid})
        fun admitted(original:Boolean,side:Power=power)=SessionSimulation(SessionRules.load()).also {
            it.join("pilot",side,original,"c")
        }.exportState()
        val session=admitted(true)
        val request=NavigationRequest("pilot",1003,office.country,"c",1,rank=office.rank,
            shipKind=if(power==Power.EMPIRE)3 else 93,initializeOriginal=true,startingOffice=office)
        val fleet=OutfitStock(office.fleetId,office.country,OutfitKind.FLEET,base.id,1002,number=office.fleetNumber,aideId=1005)
        val neighboringOffice=when(office) {
            OriginalStartingOffice.HAWOOD_FLEET_7 -> OriginalStartingOffice.LEFEBVRE_FLEET_3
            OriginalStartingOffice.LEFEBVRE_FLEET_3 -> OriginalStartingOffice.HAWOOD_FLEET_7
            OriginalStartingOffice.CUBRUSLY_FLEET_1 -> OriginalStartingOffice.PAETTA_FLEET_2
            OriginalStartingOffice.PAETTA_FLEET_2 -> OriginalStartingOffice.CUBRUSLY_FLEET_1
            else -> null
        }
        val neighbor=neighboringOffice?.let {
            fleet.copy(id=it.fleetId,number=it.fleetNumber,warehouseId=1003,commanderId=10006,aideId=0)
        }
        val dockedFleets=listOfNotNull(fleet,neighbor)
        val initial=PlanetStocks(1,0,mapOf(base.id to PlanetStock(base.id,office.country.value,0,100,100,100)),
            outfits=dockedFleets.associateBy {it.id},warehouses=dockedFleets.associate {
                it.warehouseId to OutfitWarehouse(it.warehouseId,office.country,OutfitKind.FLEET,base.id,it.id)
            },deploymentInitialized=true)
        val state=State(initial,Navigator(1,1003,power,2487,1000,1000,1000)).also {it.players=emptyMap()}
        fun enter(entry:NavigationRequest=request,admission:SessionState=session):NavigationResult {
            val navigation=Navigation(world,state.nav,NavigationPolicy())
            val production=PlanetProduction(world,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
            return FleetNavigation.apply(entry,admission,world,navigation,production,
                production.settle(1,admission.gameMillis/1000),state.commit) {false}
        }
        for((entry,admission) in listOf(request.copy(startingOffice=null) to session,
            request.copy(rank=if(office.rank==5)6 else 5) to session,request to admitted(false),
            request.copy(country=wrongPower.country,shipKind=if(wrongPower==Power.EMPIRE)3 else 93) to admitted(true,wrongPower))) {
            assertEquals(0,assertNotNull(enter(entry,admission).player).baseId)
            assertEquals(fleet,state.stocks.outfits.getValue(fleet.id))
            state.players=emptyMap()
        }
        val occupied=fleet.copy(commanderId=10004)
        state.stocks=initial.copy(outfits=initial.outfits+(fleet.id to occupied))
        assertEquals(0,assertNotNull(enter().player).baseId)
        assertEquals(occupied,state.stocks.outfits.getValue(fleet.id))
        state.players=emptyMap()
        state.stocks=initial
        state.fail=true
        assertFailsWith<java.io.IOException> {enter()}
        assertTrue(state.players.isEmpty())
        assertEquals(fleet,state.stocks.outfits.getValue(fleet.id))
        state.fail=false
        val docked=assertNotNull(enter().player)
        assertEquals(base.id,docked.baseId)
        assertEquals(base.grid,docked.grid)
        assertEquals(docked,state.players.getValue("pilot"))
        assertEquals(fleet.copy(commanderId=1003),state.stocks.outfits.getValue(fleet.id))
        neighbor?.let {assertEquals(it,state.stocks.outfits.getValue(it.id))}
        assertEquals(1,state.commits)
        val resigned=OfficeResignation.evaluate(world,OfficeResignationRequest("pilot","c",1003,power,1,office.role.card),
            session,docked,state.stocks)
        assertTrue(resigned.changed)
        state.stocks=assertNotNull(resigned.stocks)
        state.players=mapOf("pilot" to assertNotNull(resigned.player))
        assertEquals(resigned.player,enter().player)
        assertEquals(fleet,state.stocks.outfits.getValue(fleet.id))
        assertEquals(1,state.commits)
    }

    @Test fun reservationOrMissingAtomicWriterCannotSplitDepartureState() {
        for (locked in listOf(false,true)) {
            val state=state();val before=state.players.toMap()
            val result=apply(state,request.copy(place=PlaceMove.SwitchMode(5)),blocked=locked,
                committer=if(locked)state.commit else null).single()
            assertEquals(if(locked)NavigationRejection.BUSY else NavigationRejection.DISABLED,result.rejection)
            assertEquals(before,state.players);assertNull(state.stocks.outfits.getValue(1).position)
        }
    }
    @Test fun activeReorganizationBlocksDirectFleetDepartureFromTheCentralSquare() {
        val state = state()
        val pilot = state.players.getValue("pilot").copy(spotId = 0)
        state.players = mapOf("pilot" to pilot)
        val navigation = Navigation(galaxy, state.nav, NavigationPolicy())
        val production = PlanetProduction(galaxy, state.inventory, SessionRules.load().startSeconds, PlanetProductionPolicy())
        val logistics = OutfitLogistics({ 0L }, galaxy)
        val prepare = LogisticsRequest("pilot", "c", pilot.characterId, Power.ALLIANCE, 1, 1,
            LogisticsMode.REORGANIZATION, LogisticsPhase.PREPARE)
        val reservation = assertNotNull(logistics.apply(prepare, session, pilot, production).reservation)
        val initialStocks = state.stocks
        val move = request.copy(place = PlaceMove.Dock(0, 5))

        fun depart() = FleetNavigation.apply(move, session, galaxy, navigation, production,
            assertNotNull(production.snapshot(1)), state.commit) { outfit ->
            logistics.blocksMovement(session, outfit, production.snapshot(1))
        }

        assertEquals(NavigationRejection.BUSY, depart().rejection)
        assertEquals(mapOf("pilot" to pilot), state.players)
        assertEquals(initialStocks, state.stocks)
        assertEquals(0, state.commits)
        assertNull(logistics.apply(prepare.copy(phase = LogisticsPhase.CANCEL, reservation = reservation),
            session, pilot, production).rejection)
        assertNull(depart().rejection)
        assertEquals(0, state.players.getValue("pilot").baseId)
        assertEquals(OutfitPosition(home.grid), state.stocks.outfits.getValue(1).position)
        assertEquals(1, state.commits)
    }

    @Test fun delayedDockArrivalCommitsFleetAndFlagshipAtTheSameBase() {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(place=PlaceMove.Dock(1000,4)))
        assertEquals(840,state.players.getValue("pilot").mcp)
        assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
        val late=session.copy(gameMillis=session.gameMillis+8*3600_000L)
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        val result=FleetNavigation.apply(request,late,galaxy,navigation,production,
            production.settle(1,late.gameMillis/1000),state.commit) {false}
        assertTrue(result.completedDock);assertEquals(1000,result.player!!.baseId)
        assertEquals(OutfitPosition(2487,1000),state.stocks.outfits.getValue(1).position)
        assertEquals(result.player,state.players["pilot"])
    }
    private fun addAide(state: State, cruise: Int = 1000) {
        val fleet = state.stocks.outfits.getValue(1)
        state.stocks = state.stocks.copy(outfits = state.stocks.outfits + (1 to fleet.copy(aideId = 10001)))
        state.players = state.players + ("aide" to state.players.getValue("pilot").copy(characterId = 10001,
            pcp = 800, mcp = 600, cruise = cruise))
    }
    @Test fun sameAideRoleMovesToAnotherFleetAfterDismissalOrResignation() {
        for (resigns in listOf(false, true)) {
            val state = state()
            addAide(state)
            val newCommander = state.players.getValue("pilot").copy(characterId = 10002)
            state.players = state.players + ("successor" to newCommander)
            val second = OutfitStock(2, 3, OutfitKind.FLEET, 1000, 20, commanderId = 10002,
                units = UnitInventory(mapOf(UnitStack(89) to 1)))
            val seeded = ActiveShips.initialize(state.stocks.copy(outfits = state.stocks.outfits + (2 to second),
                warehouses = state.stocks.warehouses + (20 to OutfitWarehouse(20, 3, OutfitKind.FLEET, 1000, 2))))
            val oldShip = seeded.outfits.getValue(1).activeShips!!.values.first()
            state.stocks = seeded.copy(tacticalCommands = seeded.tacticalCommands.copy(authority = mapOf(oldShip.id to 10001)))
            val activeSession = SessionSimulation(SessionRules.load()).apply {
                join("pilot", Power.ALLIANCE, false, "c")
                join("aide", Power.ALLIANCE, false, "a")
                join("successor", Power.ALLIANCE, false, "s")
            }.exportState()
            val role = FleetOffice.ALLIANCE_AIDE.card
            val appointment = GarrisonAppointmentRequest("successor", "s", 10002, Power.ALLIANCE, 1, 1000, 10001, role, 6, 12,
                CountryId(3), outfit = 2)
            fun appoint() = GarrisonAppointment.evaluate(galaxy, appointment, activeSession, state.players.getValue("successor"), state.stocks)
            assertEquals(AppointmentRejection.TARGET, appoint().rejection)
            assertEquals(10001, TacticalAuthority.commander(state.stocks.outfits.getValue(1), oldShip, state.stocks))
            if (resigns) {
                val result = OfficeResignation.evaluate(galaxy, OfficeResignationRequest("aide", "a", 10001, Power.ALLIANCE, 1, role),
                    activeSession, state.players.getValue("aide"), state.stocks)
                assertNull(result.rejection)
                state.stocks = assertNotNull(result.stocks)
                state.players = state.players + ("aide" to assertNotNull(result.player))
            } else {
                val result = OfficeDismissal.evaluate(galaxy, OfficeDismissalRequest("pilot", "c", 10000, Power.ALLIANCE, 1, 10001, role, 6, 3),
                    activeSession, state.players.getValue("pilot"), state.stocks)
                assertNull(result.rejection)
                state.stocks = assertNotNull(result.stocks)
                state.players = state.players + ("pilot" to assertNotNull(result.player))
            }
            assertEquals(10000, TacticalAuthority.commander(state.stocks.outfits.getValue(1), oldShip, state.stocks))
            val moved = appoint()
            assertNull(moved.rejection)
            state.stocks = assertNotNull(moved.stocks)
            state.players = state.players + ("successor" to assertNotNull(moved.player))
            assertEquals(0, state.stocks.outfits.getValue(1).aideId)
            assertEquals(10001, state.stocks.outfits.getValue(2).aideId)
            val newFleet = state.stocks.outfits.getValue(2)
            val newShip = newFleet.activeShips!!.values.single()
            assertEquals(10002, TacticalAuthority.commander(newFleet, newShip, state.stocks))
            val navigation = Navigation(galaxy, state.nav, NavigationPolicy())
            val production = PlanetProduction(galaxy, state.inventory, SessionRules.load().startSeconds, PlanetProductionPolicy())
            fun depart(command: NavigationRequest) = FleetNavigation.apply(command.copy(place = PlaceMove.SwitchMode(5)),
                activeSession, galaxy, navigation, production, production.settle(1, activeSession.gameMillis/1000), state.commit) { false }
            val aide = state.players.getValue("aide")
            assertNull(depart(request).rejection)
            assertEquals(aide, state.players.getValue("aide"))
            val secondDeparture = NavigationRequest("successor", 10002, Power.ALLIANCE, "s", 1, unitId = newCommander.unitId, captainCard = 195)
            assertNull(depart(secondDeparture).rejection)
            val follower = state.players.getValue("aide")
            assertEquals(0, follower.baseId)
            assertEquals(aide.moves + 1, follower.moves)
            assertEquals(aide.mcp, follower.mcp)
        }
    }

    @Test fun fleetWarpClearsPreviousTacticalPostureForCaptainAndFollowerOnlyOnMovement() {
        val state = state()
        addAide(state)
        assertNull(apply(state, request.copy(place = PlaceMove.SwitchMode(5))).single().rejection)
        val anchored = TacticalPosture(home.grid, TacticalShipMode.ANCHORED, home.id)
        state.players = state.players.mapValues { (_, player) -> player.copy(posture = anchored) }
        val warp = request.copy(destination = enemy.grid)

        assertEquals(NavigationRejection.BUSY, apply(state, warp, blocked = true).single().rejection)
        assertTrue(state.players.values.all { it.grid == home.grid && it.posture == anchored })
        assertNull(apply(state, warp).single().rejection)
        assertTrue(state.players.values.all { it.grid == enemy.grid && it.posture == null })
        assertEquals(OutfitPosition(enemy.grid), state.stocks.outfits.getValue(1).position)
    }

    @Test fun offlineAideFollowsDepartureWarpAndReloadWithoutPayingTheCommandersCp() {
        val state = state(); addAide(state)
        val cp = state.players.getValue("aide")
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2488))
        val aide = state.players.getValue("aide")
        assertEquals(2488,aide.grid); assertEquals(0,aide.baseId); assertEquals(5,aide.mode)
        assertEquals(0,aide.spotId); assertEquals(900,aide.cruise); assertEquals(2,aide.moves)
        assertEquals(cp.pcp,aide.pcp); assertEquals(cp.mcp,aide.mcp)
        assertEquals(15L,GridForces.collect(galaxy,1,state.players.values,state.stocks)
            .getValue(2488).single {it.camp.country==Power.ALLIANCE.country}.units)
        apply(state,request)
        assertEquals(aide,state.players.getValue("aide"))
    }
    @Test fun membersOutsideTheTravelGroupAreNotTeleported() {
        for (scenario in 0..3) {
            val state = state(); addAide(state)
            val aide = state.players.getValue("aide")
            if(scenario==0) {
                val publicSpot=galaxy.facilities.getValue(1000).flatMap {it.spots}.first {it.access==SpotAccess.PUBLIC}
                state.players=state.players+("aide" to aide.copy(spotId=publicSpot.id))
            } else if(scenario==1) state.players=state.players+("aide" to aide.copy(grid=2488,baseId=0,mode=5,spotId=0))
            else if(scenario==2) state.stocks=state.stocks.copy(outfits=state.stocks.outfits+
                (2 to OutfitStock(2,3,OutfitKind.PATROL,1000,20,commanderId=10001)),
                warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1000)))
            else {
                val now=session.gameMillis/1000
                val justice=state.stocks.social.justice
                val detention=Detention(aide.characterId,CountryId(3),now,now+Justice.DAY,base=1000)
                state.stocks=state.stocks.copy(social=state.stocks.social.copy(justice=
                    justice.copy(detentions=justice.detentions+(aide.characterId to detention))))
            }
            val before=state.players.getValue("aide")
            assertNull(apply(state,request.copy(place=PlaceMove.SwitchMode(5))).single().rejection)
            assertEquals(0,state.players.getValue("pilot").baseId)
            assertEquals(before,state.players.getValue("aide"))
        }
    }
    @Test fun oldForeignOfficeDoesNotPreventAnAideFromFollowingTheirCurrentFleet() {
        val state = state(); addAide(state)
        state.stocks = state.stocks.copy(outfits = state.stocks.outfits +
            (2 to OutfitStock(2,2,OutfitKind.FLEET,1001,20,commanderId=10001)),
            warehouses = state.stocks.warehouses + (20 to OutfitWarehouse(20,2,OutfitKind.FLEET,1001,2)))
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2488))
        val aide = state.players.getValue("aide")
        assertEquals(2488,aide.grid)
        assertEquals(900,aide.cruise)
        assertEquals(1001,state.stocks.outfits.getValue(2).positionIn(galaxy).baseId)
    }

    @Test fun aRebelAideNeitherFollowsNorBlocksARegularFleet() {
        val now=session.gameMillis/1000
        for (hull in listOf(null,FlagshipHull(93,now,woundedUntil=now+1))) {
            val state=state();addAide(state)
            val aide=state.players.getValue("aide").copy(hull=hull)
            state.players=state.players+("aide" to aide)
            val coup=CoupState(mapOf(aide.characterId to Plot(aide.characterId,CountryId(3),now,revolt=now+1)))
            state.stocks=state.stocks.copy(social=state.stocks.social.copy(coup=coup))
            assertNull(apply(state,request.copy(place=PlaceMove.SwitchMode(5))).single().rejection)
            assertEquals(OutfitPosition(2487),state.stocks.outfits.getValue(1).position)
            assertEquals(aide,state.players.getValue("aide"))
        }
    }

    @Test fun aRebelCommanderCannotDepartWithAnOutfitThatStayedRegular() {
        val state=state()
        val now=session.gameMillis/1000
        val coup=CoupState(mapOf(10000 to Plot(10000,CountryId(3),now,revolt=now+1)))
        state.stocks=state.stocks.copy(social=state.stocks.social.copy(coup=coup),
            bases=state.stocks.bases+(1000 to state.stocks.bases.getValue(1000).copy(camp=1)))
        assertNull(apply(state,request.copy(place=PlaceMove.SwitchMode(5))).single().rejection)
        assertEquals(0,state.players.getValue("pilot").baseId)
        assertEquals(OutfitPosition(2487,1000),state.stocks.outfits.getValue(1).positionIn(galaxy))
    }

    @Test fun anOldOppositeCampOfficeDoesNotPreventAFollowerFromDeparting() {
        val state=state();addAide(state)
        val now=session.gameMillis/1000
        val coup=CoupState(mapOf(10002 to Plot(10002,CountryId(3),now,revolt=now+1)),loyalty=mapOf(2 to CoupActions.LOYAL))
        state.stocks=state.stocks.copy(social=state.stocks.social.copy(coup=coup),
            outfits=state.stocks.outfits+(2 to OutfitStock(2,3,OutfitKind.FLEET,1000,20,commanderId=10002,aideId=10001)),
            warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.FLEET,1000,2)))
        assertNull(apply(state,request.copy(place=PlaceMove.SwitchMode(5))).single().rejection)
        assertEquals(0,state.players.getValue("aide").baseId)
        assertEquals(OutfitPosition(2487,1000),state.stocks.outfits.getValue(2).positionIn(galaxy))
    }

    @Test fun memberFuelAndDestinationCapacityAreCheckedForTheWholeGroup() {
        for(lowFuel in listOf(false,true)) {
            val state=state();addAide(state,if(lowFuel)50 else 1000)
            apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
            if(!lowFuel) state.stocks=state.stocks.copy(outfits=state.stocks.outfits+
                (2 to OutfitStock(2,3,OutfitKind.PATROL,1001,20,units=UnitInventory(mapOf(UnitStack(89) to 286)))),
                warehouses=state.stocks.warehouses+(20 to OutfitWarehouse(20,3,OutfitKind.PATROL,1001)))
            val before=state.players.toMap()
            assertEquals(if(lowFuel)NavigationRejection.CRUISE else NavigationRejection.GRID,
                apply(state,request.copy(destination=2488)).single().rejection)
            assertEquals(before,state.players)
        }
    }
    @Test fun reservedWarpChecksMemberFuelBeforePayment() {
        val state = state()
        addAide(state, cruise = 199)
        apply(state, request.copy(place = PlaceMove.SwitchMode(5)))
        val before = state.players.toMap()
        val inventory = state.stocks
        val commits = state.commits
        val warp = request.copy(destination = 2489)

        assertEquals(NavigationRejection.CRUISE, apply(state, warp).single().rejection)
        assertEquals(before, state.players)
        assertEquals(inventory, state.stocks)
        assertEquals(commits, state.commits)

        state.players = state.players + ("aide" to state.players.getValue("aide").copy(cruise = 200))
        val accepted = apply(state, warp).single()
        assertNull(accepted.rejection)
        assertEquals(2489, state.players.getValue("pilot").warpTarget)
        assertEquals(920, state.players.getValue("pilot").mcp)
        assertEquals(200, state.players.getValue("aide").cruise)
    }
    @Test fun dueWarpCountsTheFleetOnlyOnceWhenReservingTheReturnLeg() {
        val state = state()
        addAide(state)
        apply(state, request.copy(place = PlaceMove.SwitchMode(5)))
        val fleet = state.stocks.outfits.getValue(1)
        val remaining = GridEntryRules.MAX_UNITS_PER_CAMP - GridForces.mobileUnits(fleet).toInt() - 2
        val stationed = OutfitStock(2, 3, OutfitKind.FLEET, 1000, 20,
            units = UnitInventory(mapOf(UnitStack(89) to remaining)))
        state.stocks = state.stocks.copy(outfits = state.stocks.outfits + (stationed.id to stationed),
            warehouses = state.stocks.warehouses + (20 to OutfitWarehouse(20, 3, OutfitKind.FLEET, 1000, 2)))

        val reserved = assertNotNull(apply(state, request.copy(destination = 2588)).single().player)
        val ready = assertNotNull(reserved.warpReadyAt)
        val due = session.copy(gameMillis = ready * 1000)
        val navigation = Navigation(galaxy, state.nav, NavigationPolicy())
        val production = PlanetProduction(galaxy, state.inventory, 0, PlanetProductionPolicy())
        val inventory = production.settle(1, ready)
        val beforePlayers = state.players
        val beforeStocks = state.stocks
        val commits = state.commits

        state.fail = true
        assertFailsWith<java.io.IOException> {
            FleetNavigation.apply(request.copy(destination = 2487), due, galaxy, navigation, production, inventory, state.commit) { false }
        }
        assertEquals(beforePlayers, state.players)
        assertEquals(beforeStocks, state.stocks)
        assertEquals(commits, state.commits)

        state.fail = false
        val result = FleetNavigation.apply(request.copy(destination = 2487), due, galaxy, navigation, production,
            inventory, state.commit) { false }
        assertNull(result.rejection)
        assertTrue(result.completedWarp)
        assertEquals(2487, assertNotNull(result.player).warpTarget)
        assertEquals(listOf(2588, 2588), listOf(state.players.getValue("pilot").grid, state.players.getValue("aide").grid))
        assertEquals(OutfitPosition(2588), state.stocks.outfits.getValue(1).position)
        assertEquals(commits + 1, state.commits)
    }
    @Test fun dueWarpStillArrivesWhenFollowersCannotAffordTheNextWarp() {
        for(next in listOf(2589,2689)) {
            val state=state()
            addAide(state,cruise=200)
            apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
            val before=state.players
            val first=apply(state,request.copy(destination=2588)).single()
            assertNull(first.rejection)
            val reserved=assertNotNull(first.player)
            assertEquals(920,reserved.mcp)
            val ready=assertNotNull(reserved.warpReadyAt)
            val commits=state.commits
            val due=session.copy(gameMillis=ready*1000)
            val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
            val production=PlanetProduction(galaxy,state.inventory,0,PlanetProductionPolicy())
            val result=FleetNavigation.apply(request.copy(destination=next),due,galaxy,navigation,production,
                production.settle(1,ready),state.commit) {false}

            assertEquals(NavigationRejection.CRUISE,result.rejection)
            assertTrue(result.completedWarp)
            assertNull(result.warpCancellation)
            assertNull(result.player)
            val pilot=state.players.getValue("pilot")
            val aide=state.players.getValue("aide")
            assertEquals(pilot,result.previous)
            assertEquals(OutfitPosition(2588),state.stocks.outfits.getValue(1).position)
            assertEquals(listOf(2588,2588),listOf(pilot.grid,aide.grid))
            assertEquals(listOf(before.getValue("pilot").cruise-142,58),listOf(pilot.cruise,aide.cruise))
            assertEquals(listOf(reserved.mcp,before.getValue("aide").mcp),listOf(pilot.mcp,aide.mcp))
            assertEquals(before.getValue("pilot").moves+1,pilot.moves)
            assertEquals(before.getValue("aide").moves+1,aide.moves)
            assertNull(pilot.warpTarget)
            assertTrue(pilot.strategy!!.jobs.isEmpty())
            assertEquals(commits+1,state.commits)
        }
    }

    @Test fun dockCompletionMovesOfflineMemberAndAtomicFailureMovesNobody() {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        val before=state.players.toMap();state.fail=true
        assertFailsWith<java.io.IOException> {apply(state,request.copy(destination=2488))}
        assertEquals(before,state.players)
        state.fail=false
        apply(state,request.copy(place=PlaceMove.Dock(1000,4)))
        val late=session.copy(gameMillis=session.gameMillis+8*3600_000L)
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,SessionRules.load().startSeconds,PlanetProductionPolicy())
        FleetNavigation.apply(request,late,galaxy,navigation,production,
            production.settle(1,late.gameMillis/1000),state.commit) {false}
        assertEquals(1000,state.players.getValue("aide").baseId)
        assertEquals(4,state.players.getValue("aide").mode)
        assertEquals(600,state.players.getValue("aide").mcp)
    }

    private fun reserveAide(state:State,kind:Int,at:SessionState=session):Navigator {
        val online=SessionSimulation(SessionRules.load()).also {
            it.join("pilot",Power.ALLIANCE,false,"c");it.join("aide",Power.ALLIANCE,false,"a")
        }.exportState().copy(gameMillis=at.gameMillis)
        if(kind==0)state.players=state.players+("aide" to state.players.getValue("aide").copy(cruise=900))
        val order=NavigationRequest("aide",10001,Power.ALLIANCE,"a",1,unitId=1010001,captainCard=195)
        val request=when(kind) {0->order.copy(supplyFuel=true);1->order.copy(place=PlaceMove.Dock(1000,4));else->order.copy(destination=2489)}
        val result=Navigation(galaxy,state.nav,NavigationPolicy()).apply(request,online,state.stocks)
        assertNull(result.rejection)
        return assertNotNull(result.player)
    }

    @Test fun membersWithPersonalNavigationJobsBlockFleetWarpAndDockReservationsBeforePayment() {
        val orders=listOf(request.copy(destination=2488),request.copy(destination=2489),request.copy(place=PlaceMove.Dock(1000,4)))
        for(kind in 0..2)for(order in orders) {
            val state=state();addAide(state)
            apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
            reserveAide(state,kind)
            val before=state.players.toMap();val inventory=state.stocks
            assertEquals(NavigationRejection.BUSY,apply(state,order).single().rejection,
                "personal job $kind must block fleet movement before payment")
            assertEquals(before,state.players);assertEquals(inventory,state.stocks)
        }
    }

    @Test fun aMembersLaterWarpCannotBeOverwrittenWhenTheCommandersReservationCompletes() {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2489))
        reserveAide(state,2,session.copy(gameMillis=session.gameMillis+1000))
        val before=state.players.toMap();val inventory=state.stocks
        val due=session.copy(gameMillis=session.gameMillis+3600_000)
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,0,PlanetProductionPolicy())
        val result=FleetNavigation.apply(request,due,galaxy,navigation,production,
            production.settle(1,due.gameMillis/1000),state.commit) {false}
        assertEquals(NavigationRejection.BUSY,result.rejection)
        assertEquals(before,state.players);assertEquals(inventory,state.stocks)
        assertEquals(920,state.players.getValue("pilot").mcp,"reserved payment is not repeated")
        val next=SessionSimulation(SessionRules.load()).also {
            it.join("pilot",Power.ALLIANCE,false,"c");it.join("aide",Power.ALLIANCE,false,"a")
        }.exportState().copy(gameMillis=due.gameMillis+1000)
        val aide=NavigationRequest("aide",10001,Power.ALLIANCE,"a",1,unitId=1010001,captainCard=195)
        assertNull(navigation.apply(aide,next,state.stocks).rejection)
        val completedAide=state.players.getValue("aide")
        assertNull(completedAide.warpTarget)
        val retried=FleetNavigation.apply(request,next,galaxy,navigation,production,
            production.settle(1,next.gameMillis/1000),state.commit) {false}
        assertNull(retried.rejection);assertTrue(retried.completedWarp)
        assertEquals(completedAide,state.players.getValue("aide"),"the member's completed personal warp is preserved")
        assertEquals(920,state.players.getValue("pilot").mcp)
        assertEquals(OutfitPosition(retried.player!!.grid),state.stocks.outfits.getValue(1).position)
    }

    @Test fun aWoundedAideBlocksFleetTravelUntilRecoveryWithoutAnotherPayment() {
        val state=state();addAide(state)
        val now=session.gameMillis/1000
        val recovery=now+3601
        val wound=FlagshipHull(93,now,woundedUntil=recovery)
        fun injure() {
            state.players=state.players+("aide" to state.players.getValue("aide").copy(hull=wound))
        }
        injure()
        assertEquals(NavigationRejection.BUSY,apply(state,request.copy(place=PlaceMove.SwitchMode(5))).single().rejection)
        assertEquals(1000,state.players.getValue("pilot").baseId)
        val aide=state.players.getValue("aide")
        state.players=state.players+("aide" to aide.copy(hull=null))
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)))
        val departed=state.players
        injure()
        val injured=state.players
        val warp=request.copy(destination=2489)
        assertEquals(NavigationRejection.BUSY,apply(state,warp).single().rejection)
        assertEquals(injured,state.players)
        state.players=departed
        apply(state,warp)
        injure()
        val waiting=state.players
        val inventory=state.stocks
        val navigation=Navigation(galaxy,state.nav,NavigationPolicy())
        val production=PlanetProduction(galaxy,state.inventory,0,PlanetProductionPolicy())
        fun settle(at:Long)=FleetNavigation.apply(request,session.copy(gameMillis=at*1000),galaxy,navigation,production,
            production.settle(1,at),state.commit) {false}
        assertEquals(NavigationRejection.BUSY,settle(recovery-1).rejection)
        assertEquals(waiting,state.players);assertEquals(inventory,state.stocks)
        val arrived=settle(recovery)
        assertTrue(arrived.completedWarp)
        val captain=assertNotNull(arrived.player)
        assertEquals(captain.grid,state.players.getValue("aide").grid)
        assertEquals(920,captain.mcp)
        assertEquals(waiting.getValue("aide").moves+1,state.players.getValue("aide").moves)
        assertFalse(settle(recovery).completedWarp)
    }

    @Test fun resigningAfterScheduledTravelCompletesMovesTheFleetAndItsAideBeforeRemovingTheOffice()=runBlocking {
        for(docking in listOf(false,true)) {
            val state=state();addAide(state)
            apply(state,request.copy(place=PlaceMove.SwitchMode(5)),
                if(docking)request.copy(place=PlaceMove.Dock(1000,4)) else request.copy(destination=2489))
            val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
                stockStore=state.inventory,worldStateCommitter=state.commit,npcPolicy=NpcTacticalPolicy(enabled=false))
            try {
                val joined=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));assertEquals(Admission.ACCEPTED,joined.await())
                engine.submit(WorldCommand.AdvanceTo(if(docking)1_200_000 else 150_000))
                val resigned=CompletableDeferred<OfficeResignationResult>()
                engine.submit(WorldCommand.ResignOffice(OfficeResignationRequest("pilot","c",10000,Power.ALLIANCE,1,175),resigned))
                val result=resigned.await();assertNull(result.rejection)
                val arrived=checkNotNull(result.player)
                assertEquals(OutfitPosition(arrived.grid,arrived.baseId),state.stocks.outfits.getValue(1).position,
                    "a strategic command's recovery must move the fleet before resigning")
                val aide=state.players.getValue("aide")
                assertEquals(arrived.grid,aide.grid);assertEquals(arrived.baseId,aide.baseId)
                assertEquals(0,state.stocks.outfits.getValue(1).commanderId)
                val recovery=checkNotNull(result.recovery)
                assertEquals(checkNotNull(recovery.player).mcp-80,arrived.mcp)
                assertEquals(docking,recovery.completedDock)
                assertEquals(!docking,recovery.completedWarp)
            } finally {engine.drainAndClose()}
        }
    }

    @Test fun strategicRecoveryCannotBypassAMembersBlockingStateWhenTheCommandersWarpBecomesDue()=runBlocking {
      for(lowFuel in listOf(false,true)) {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2489))
        if(lowFuel)state.players=state.players+("aide" to state.players.getValue("aide").copy(cruise=100))
        if(!lowFuel)reserveAide(state,2,session.copy(gameMillis=1000))
        val before=state.players.toMap();val inventory=state.stocks
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit,npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            engine.submit(WorldCommand.AdvanceTo(150_000))
            val resigned=CompletableDeferred<OfficeResignationResult>()
            engine.submit(WorldCommand.ResignOffice(OfficeResignationRequest("pilot","c",10000,Power.ALLIANCE,1,175),resigned))
            val result=resigned.await()
            assertEquals(ResignationRejection.BUSY,result.rejection)
            assertEquals(if(lowFuel)NavigationRejection.CRUISE else NavigationRejection.BUSY,result.recovery!!.rejection)
            assertEquals(before,state.players);assertEquals(inventory,state.stocks)
        } finally {engine.drainAndClose()}
      }
    }

    @Test fun aFailedArrivalCommitStopsTheStrategicCommandWithoutPublishingOrRemovingTheOffice()=runBlocking {
        val state=state()
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2489))
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit,npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            engine.submit(WorldCommand.AdvanceTo(150_000))
            val before=engine.snapshot();val players=state.players.toMap();val inventory=state.stocks
            state.fail=true
            val resigned=CompletableDeferred<OfficeResignationResult>()
            engine.submit(WorldCommand.ResignOffice(OfficeResignationRequest("pilot","c",10000,Power.ALLIANCE,1,175),resigned))
            assertFailsWith<java.io.IOException> {resigned.await()}
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertEquals(players,state.players);assertEquals(inventory,state.stocks)
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
        } finally {engine.drainAndClose()}
    }

    @Test fun changingAiSettingsAtArrivalCommitsTheFleetAideAndSettingsTogether()=runBlocking {
      for(docking in listOf(false,true)) {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),
            if(docking)request.copy(place=PlaceMove.Dock(1000,4)) else request.copy(destination=2489))
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit,npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            engine.submit(WorldCommand.AdvanceTo(if(docking)1_200_000 else 150_000))
            val commits=state.commits
            val changed=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request.copy(offlineDirection=21),changed))
            val result=changed.await();assertNull(result.rejection)
            val player=checkNotNull(result.player);val aide=state.players.getValue("aide")
            assertEquals(OutfitPosition(player.grid,player.baseId),state.stocks.outfits.getValue(1).position)
            assertEquals(player.grid,aide.grid);assertEquals(player.baseId,aide.baseId)
            assertEquals(docking,result.completedDock);assertEquals(!docking,result.completedWarp)
            assertEquals(21,state.stocks.tacticalCommands.aiSwitches[10000])
            assertEquals(commits+1,state.commits)
        } finally {engine.drainAndClose()}
      }
    }

    @Test fun aiSettingsCanChangeWithoutBypassingOrCancellingADeferredFleetArrival()=runBlocking {
      for(lowFuel in listOf(false,true)) {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2489))
        if(lowFuel)state.players=state.players+("aide" to state.players.getValue("aide").copy(cruise=100))
        if(!lowFuel)reserveAide(state,2,session.copy(gameMillis=1000))
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit,npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            engine.submit(WorldCommand.AdvanceTo(150_000))
            val players=state.players.toMap();val position=state.stocks.outfits.getValue(1).position;val commits=state.commits
            val changed=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request.copy(offlineDirection=21),changed))
            val result=changed.await();assertNull(result.rejection);assertFalse(result.completedWarp)
            assertEquals(players,state.players);assertEquals(position,state.stocks.outfits.getValue(1).position)
            assertEquals(21,state.stocks.tacticalCommands.aiSwitches[10000]);assertEquals(commits+1,state.commits)
        } finally {engine.drainAndClose()}
      }
    }

    @Test fun failedAiSettingsAtArrivalPublishNeitherMovementNorSettings()=runBlocking {
        val state=state();addAide(state)
        apply(state,request.copy(place=PlaceMove.SwitchMode(5)),request.copy(destination=2489))
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=state.nav,
            stockStore=state.inventory,worldStateCommitter=state.commit,npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val joined=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("pilot",Power.ALLIANCE,false,joined,"c",1));joined.await()
            engine.submit(WorldCommand.AdvanceTo(150_000))
            val before=engine.snapshot();val players=state.players.toMap();val stocks=state.stocks;val commits=state.commits
            state.fail=true
            val changed=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(request.copy(offlineDirection=21),changed))
            assertFailsWith<java.io.IOException> {changed.await()}
            assertEquals(players,state.players);assertEquals(stocks,state.stocks);assertEquals(commits,state.commits)
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
        } finally {engine.drainAndClose()}
    }
}
