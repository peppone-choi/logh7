package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlinx.coroutines.*

class OutfitLogisticsTest {
    @Test fun assignmentPreviewRevalidatesTheChosenOfficeAndMatchesDirectAllocationForAllRows() {
        val f = Fixture()
        val returningShip = UnitStack(40)
        val returningCrew = UnitStack(3, 1)
        val initial = f.store.saved
        val warehouse = initial.warehouses.getValue(101)
        f.store.saved = initial.copy(warehouses = initial.warehouses + (101 to warehouse.copy(
            units = UnitInventory(warehouse.units.ships + (returningShip to 2), warehouse.units.troops + (returningCrew to 3)),
        )))
        f.production = PlanetProduction(f.galaxy, f.store, f.rules.startSeconds, PlanetProductionPolicy())
        val before = f.production.settle(f.session)
        val actor = f.player(10)
        val request = StockAssignmentRequest("p10", "c10", 10, Power.EMPIRE, 1, 1000, 1, OutfitKind.FLEET.wire,
            listOf(ReorganizationShip(ship.kind, 2, 0), ReorganizationShip(returningShip.kind, -1, 0)),
            listOf(ReorganizationTroop(1000 + crew.kind, crew.level, 5),
                ReorganizationTroop(1000 + returningCrew.kind, returningCrew.level, -2)),
            supplies = 500)
        val card = LogisticsOffice.EMPIRE_FIRST.card
        fun preview(stocks: PlanetStocks = before, requiredCard: Int = card, busy: Boolean = false) =
            StockAssignment.preview(request, actor, stocks, f.galaxy, requiredCard) { busy }

        assertEquals(LogisticsRejection.ACCESS, preview(requiredCard = LogisticsOffice.EMPIRE_SECOND.card).rejection)
        assertEquals(LogisticsRejection.ACCESS, preview(before.copy(logisticsOfficers = before.logisticsOfficers - 10)).rejection)
        assertEquals(LogisticsRejection.BUSY, preview(busy = true).rejection)
        val withoutShips = before.copy(bases = before.bases + (1000 to before.bases.getValue(1000).copy(units = UnitInventory())))
        assertEquals(LogisticsRejection.STOCK, preview(withoutShips).rejection)
        val result = preview()
        assertTrue(result.changed)
        assertEquals(actor, result.player)
        assertEquals(before, f.store.saved)
        val after = assertNotNull(result.stocks)
        val nextWarehouse = after.warehouses.getValue(101)
        assertEquals(mapOf(ship to 12, returningShip to 1), nextWarehouse.units.ships)
        assertEquals(mapOf(crew to 55, returningCrew to 1), nextWarehouse.units.troops)
        assertEquals(500, nextWarehouse.supplies)

        val prepared = f.prepare(f.request(10, mode = LogisticsMode.ALLOCATION))
        val direct = f.act(prepared.copy(transfer = StockAssignment.transfer(request, before), supplies = request.supplies))
        assertEquals(after, direct.stocks)
        assertEquals(after, f.store.saved)
    }

    @Test fun understrengthWarehouseUnitNeedsOnlyItsActualCrewAndConservesBoatsOnCommit() {
        val f=Fixture()
        val warehouse=f.store.saved.warehouses.getValue(101)
        f.store.saved=f.store.saved.copy(warehouses=f.store.saved.warehouses+(101 to warehouse.copy(
            units=UnitInventory(troops=mapOf(crew to 50)),spareShips=SpareShipBoats(mapOf(ship to 123)))))
        f.production=PlanetProduction(f.galaxy,f.store,f.rules.startSeconds,PlanetProductionPolicy())
        val request=f.prepare(f.request())
        val before=f.store.saved
        val transfer=UnitTransfer(mapOf(ship to 1),mapOf(crew to 5))
        assertEquals(LogisticsRejection.CREW,f.act(request.copy(transfer=transfer)).rejection)
        assertEquals(before,f.store.saved)
        val result=f.act(request.copy(transfer=UnitTransfer(mapOf(ship to 1),mapOf(crew to 3))))
        assertNull(result.rejection)
        val after=assertNotNull(result.stocks)
        assertEquals(123,after.outfits.getValue(1).activeShips!!.values.single().boats)
        assertEquals(3,after.outfits.getValue(1).units.troops[crew])
        assertEquals(47,after.warehouses.getValue(101).units.troops[crew])
        assertTrue(after.warehouses.getValue(101).spareShips.values.isEmpty())
    }
    @Test fun supplyRespectsPreparedWarehouseLocksAndReleasesDisconnectedOrExpiredOwners() {
        val f=Fixture()
        val prepared=f.prepare(f.request())
        assertTrue(f.logistics.blocksMovement(f.session,1))
        assertFalse(f.logistics.blocksMovement(f.session,4))
        assertTrue(f.logistics.blocksSupply(f.session,f.store.saved,setOf(1)))
        assertFalse(f.logistics.blocksSupply(f.session,f.store.saved,setOf(4)))
        assertNull(f.act(prepared.copy(phase=LogisticsPhase.CANCEL)).rejection)
        assertFalse(f.logistics.blocksMovement(f.session,1))
        assertFalse(f.logistics.blocksSupply(f.session,f.store.saved,setOf(1)))
        f.prepare(f.request(10,mode=LogisticsMode.ALLOCATION))
        assertFalse(f.logistics.blocksMovement(f.session,1))
        assertTrue(f.logistics.blocksSupply(f.session,f.store.saved,setOf(4)))
        f.now=120_001
        assertFalse(f.logistics.blocksSupply(f.session,f.store.saved,setOf(4)))
        f.prepare(f.request())
        val offline=f.session.copy(participants=f.session.participants.mapValues { (_,p)->p.copy(online=false) })
        assertFalse(f.logistics.blocksMovement(offline,1))
        assertFalse(f.logistics.blocksSupply(offline,f.store.saved,setOf(1)))
    }
    @Test fun onlyCommittedCommanderChangesReleaseReorganizationReservations() {
        for (checkMovementFirst in listOf(true, false)) {
            val f = Fixture()
            val previous = f.prepare(f.request())
            val before = assertNotNull(f.production.snapshot(1))
            val office = OrganizationalOfficePolicy(f.galaxy)
            val seat = OfficeHolding(FleetOffice.EMPIRE_COMMANDER.card, 1, 11, Power.EMPIRE.country)
            val dismissed = office.set(before, seat, remove = true)
            val appointed = office.set(dismissed, seat.copy(character = 12))
            assertEquals(0, dismissed.outfits.getValue(1).commanderId)
            assertEquals(12, appointed.outfits.getValue(1).commanderId)

            f.store.fail = true
            assertFailsWith<java.io.IOException> { f.production.replace(before, appointed) }
            assertEquals(before, f.production.snapshot(1))
            assertTrue(f.logistics.blocksMovement(f.session, 1, f.production.snapshot(1)))
            assertEquals(previous.reservation, f.act(f.request()).reservation)

            f.store.fail = false
            f.production.replace(before, appointed)
            assertEquals(f.store.saved, f.production.snapshot(1))
            if (checkMovementFirst) {
                assertFalse(f.logistics.blocksMovement(f.session, 1, f.production.snapshot(1)))
            }
            val next = f.prepare(f.request(12))
            assertNotEquals(previous.reservation, next.reservation)
            assertTrue(f.logistics.blocksMovement(f.session, 1, f.production.snapshot(1)))
            assertEquals(LogisticsRejection.ACCESS, f.act(previous).rejection)
            assertNull(f.act(next.copy(phase = LogisticsPhase.CANCEL)).rejection)
            assertFalse(f.logistics.blocksMovement(f.session, 1, f.production.snapshot(1)))
        }
    }

    @Test fun anExistingOversizedFormationIsPreservedAndCanReturnToItsLegalLimit() {
        val f=Fixture()
        val original=f.store.saved.outfits.getValue(1).copy(
            units=UnitInventory(mapOf(ship to 61),mapOf(crew to 305)),activeShips=null)
        f.store.saved=f.store.saved.copy(outfits=f.store.saved.outfits+(1 to original))
        f.production=PlanetProduction(f.galaxy,f.store,f.rules.startSeconds,PlanetProductionPolicy())
        val request=f.prepare(f.request())
        assertEquals(61,f.store.saved.outfits.getValue(1).units.ships.getValue(ship))
        val result=f.act(request.copy(transfer=UnitTransfer(mapOf(ship to -1),mapOf(crew to -5))))
        assertNull(result.rejection)
        assertEquals(60,result.stocks!!.outfits.getValue(1).units.ships.getValue(ship))
        assertEquals(300,result.stocks.outfits.getValue(1).units.troops.getValue(crew))
        assertEquals(11,result.stocks.warehouses.getValue(101).units.ships.getValue(ship))
    }
    @Test fun formationLimitsApplyAcrossGradesAndAnOverLimitCommitPreservesStock() {
        for ((kind,movingKind,limit) in listOf(Triple(OutfitKind.FLEET,32,60),Triple(OutfitKind.TRANSPORT,76,20),
                Triple(OutfitKind.PATROL,32,3),Triple(OutfitKind.GROUND,84,3))) {
            val f=Fixture();val moving=UnitStack(movingKind)
            val per=UnitSpecifications.ships.single { it.kind==movingKind }.number("必要乗組員ユニット数:必要乗組員ユニット数")
            fun crewMove(count:Int)=if (per==0) emptyMap() else mapOf(crew to count*per)
            val baseline=if (kind==OutfitKind.TRANSPORT) 3 else 0
            val original=f.store.saved.outfits.getValue(1).copy(kind=kind,activeShips=null,
                units=if (baseline==0) UnitInventory() else UnitInventory(mapOf(ship to baseline),mapOf(crew to 15)))
            val warehouse=f.store.saved.warehouses.getValue(101).copy(kind=kind.warehouseKind,
                outfitId=if (kind in setOf(OutfitKind.FLEET,OutfitKind.TRANSPORT)) 1 else 0,
                units=UnitInventory(mapOf(moving to limit,moving.copy(level=1) to 2),crewMove(limit+2)))
            f.store.saved=f.store.saved.copy(outfits=mapOf(1 to original),warehouses=mapOf(101 to warehouse))
            f.production=PlanetProduction(f.galaxy,f.store,f.rules.startSeconds,PlanetProductionPolicy())
            val full=f.act(f.prepare(f.request()).copy(transfer=UnitTransfer(mapOf(moving to limit),crewMove(limit))))
            assertNull(full.rejection,"$kind boundary")
            val request=f.prepare(f.request());val before=f.store.saved;val writes=f.store.writes
            val over=f.act(request.copy(transfer=UnitTransfer(mapOf(moving.copy(level=1) to 1),crewMove(1))))
            assertEquals(LogisticsRejection.STOCK,over.rejection,"$kind overflow")
            assertEquals(before,f.store.saved);assertEquals(writes,f.store.writes)
            assertNull(f.act(request.copy(transfer=UnitTransfer(mapOf(moving to -1),crewMove(-1)))).rejection)
            assertEquals(baseline+limit-1,f.store.saved.outfits.getValue(1).units.ships.values.sum())
            assertEquals(baseline+limit+2,f.store.saved.outfits.getValue(1).units.ships.values.sum()+
                f.store.saved.warehouses.getValue(101).units.ships.values.sum())
        }
    }
    @Test fun returningCarriersCannotStrandSuppliesAboveTheNewCapacity() {
        val f=Fixture()
        val original=f.store.saved.outfits.getValue(1).copy(
            units=UnitInventory(mapOf(ship to 2),mapOf(crew to 10)),supplies=400000,activeShips=null)
        f.store.saved=f.store.saved.copy(outfits=f.store.saved.outfits+(1 to original))
        f.production=PlanetProduction(f.galaxy,f.store,f.rules.startSeconds,PlanetProductionPolicy())
        val request=f.prepare(f.request())
        val before=f.store.saved;val writes=f.store.writes
        val result=f.act(request.copy(transfer=UnitTransfer(mapOf(ship to -1),mapOf(crew to -5))))
        assertEquals(LogisticsRejection.STOCK,result.rejection)
        assertEquals(before,f.store.saved);assertEquals(writes,f.store.writes)
    }
    @Test fun loadedTransportCanReorganizeWithoutReturningLiveBoats() {
        val f=Fixture()
        val hull=UnitStack(76)
        val spec=UnitSpecifications.ships.single {it.kind==hull.kind}
        val per=spec.number("必要乗組員ユニット数:必要乗組員ユニット数")
        fun crewMove(count:Int)=if(per==0)emptyMap() else mapOf(crew to count*per)
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,hull),ActiveShipUnit(2,hull),ActiveShipUnit(3,hull,boats=0)))
        val original=f.store.saved
        val outfit=original.outfits.getValue(1).copy(kind=OutfitKind.TRANSPORT,activeShips=ships,
            units=UnitInventory(ships.quantities(),crewMove(2)))
        val packageAboard=TransportPackage(1,outfit.country,1000,1001,100,carrier=1,carrierBoats=2*spec.number)
        f.store.saved=original.copy(outfits=original.outfits+(1 to outfit),
            warehouses=original.warehouses+(101 to original.warehouses.getValue(101).copy(kind=OutfitKind.TRANSPORT,
                units=UnitInventory(mapOf(hull to 1),crewMove(1)))),
            strategic=original.strategic.copy(transports=listOf(packageAboard),lastTransportId=1),nextShipId=4)
        f.production=PlanetProduction(f.galaxy,f.store,f.rules.startSeconds,PlanetProductionPolicy())
        val request=f.prepare(f.request())
        val before=f.store.saved
        val writes=f.store.writes
        val reduction=UnitTransfer(mapOf(hull to -2),crewMove(-1))
        assertFailsWith<IllegalArgumentException> {Reorganization.capacity(before,1,reduction)}
        assertEquals(LogisticsRejection.STOCK,f.act(request.copy(transfer=reduction)).rejection)
        assertSame(before,f.store.saved)
        assertEquals(writes,f.store.writes)

        val unchanged=assertNotNull(f.act(request.copy(transfer=UnitTransfer(mapOf(hull to -1)))).stocks)
        assertEquals(2,unchanged.outfits.getValue(1).activeShips!!.values.size)
        assertEquals(2*spec.number,TransportActions.boats(unchanged.outfits.getValue(1)))
        assertEquals(listOf(packageAboard),unchanged.strategic.transports)

        val growth=f.prepare(f.request()).copy(transfer=UnitTransfer(mapOf(hull to 1),crewMove(1)))
        val expanded=assertNotNull(f.act(growth).stocks)
        assertEquals(3*spec.number,TransportActions.boats(expanded.outfits.getValue(1)))
        assertEquals(listOf(packageAboard),expanded.strategic.transports)
    }
    private val ship = UnitStack(32)
    private val crew = UnitStack(3)
    private class Store(var saved: PlanetStocks) : PlanetStockStore {
        var fail = false
        var writes = 0
        override fun load() = saved
        override fun save(stocks: PlanetStocks) {
            if (fail) throw java.io.IOException("disk failure")
            writes++; saved = stocks
        }
    }
    private inner class Fixture {
        val rules = SessionRules.load()
        val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2), GalaxySystem(4,2015,4,"Alliance",3)),
            listOf(GalaxyBase(1000,2487,60,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
                GalaxyBase(1001,2015,60,0,Capital.ALLIANCE.baseName,0,20f,86400,0,0f,2f)),
            mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
        val people = galaxy.conditions.mapValues { (_, c) -> PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace) }
        val store = Store(PlanetStocks(1,0,
            mapOf(1000 to PlanetStock(1000,2,0,10000,10000,10000,people[1000],UnitInventory(mapOf(ship to 10),mapOf(crew to 100))),
                1001 to PlanetStock(1001,3,0,10000,10000,10000,people[1001])),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1000,101,11),
                2 to OutfitStock(2,2,OutfitKind.PATROL,1000,102,12),
                3 to OutfitStock(3,2,OutfitKind.PATROL,1000,102,13),
                4 to OutfitStock(4,3,OutfitKind.FLEET,1001,103,14)),
            mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1000,1,UnitInventory(mapOf(ship to 10),mapOf(crew to 50))),
                102 to OutfitWarehouse(102,2,OutfitKind.PATROL,1000,units=UnitInventory(mapOf(ship to 10),mapOf(crew to 50))),
                103 to OutfitWarehouse(103,3,OutfitKind.FLEET,1001,4)),
            mapOf(10 to LogisticsOffice.EMPIRE_FIRST,16 to LogisticsOffice.EMPIRE_SECOND,17 to LogisticsOffice.ALLIANCE_THIRD),deploymentInitialized=true))
        val powers = (10..18).associateWith { if (it in setOf(14,17)) Power.ALLIANCE else Power.EMPIRE }
        var session = SessionSimulation(rules).also { sim -> powers.forEach { (id,power) -> sim.join("p$id",power,false,"c$id") } }.exportState()
        var now = 0L
        var logistics = OutfitLogistics({ now },galaxy)
        var production = PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy())
        fun request(id: Int = 11, outfit: Int = 1, mode: LogisticsMode = LogisticsMode.REORGANIZATION) =
            LogisticsRequest("p$id","c$id",id,powers.getValue(id),1,outfit,mode,LogisticsPhase.PREPARE)
        fun player(id: Int) = Navigator(1,id,powers.getValue(id),if (powers.getValue(id) == Power.EMPIRE) 2487 else 2015,
            1000,1000,1000,baseId=if (powers.getValue(id) == Power.EMPIRE) 1000 else 1001,mode=4)
        fun act(request: LogisticsRequest, navigator: Navigator? = player(request.characterId)) =
            logistics.apply(request,session,navigator,production)
        fun prepare(request: LogisticsRequest): LogisticsRequest {
            val prepared = act(request)
            assertNull(prepared.rejection)
            return request.copy(phase=LogisticsPhase.COMMIT,reservation=assertNotNull(prepared.reservation))
        }
    }
    @Test fun allocationThenReorganizationConservesStockAndSurvivesEngineReopening() {
        val f = Fixture()
        val allocation = f.prepare(f.request(10,mode=LogisticsMode.ALLOCATION))
        val allocated = f.act(allocation.copy(transfer=UnitTransfer(mapOf(ship to 2),mapOf(crew to 10)),supplies=500))
        assertNull(allocated.rejection)
        assertEquals(8,allocated.stocks!!.bases.getValue(1000).units.ships[ship])
        assertEquals(12,allocated.stocks.warehouses.getValue(101).units.ships[ship])
        assertEquals(9500,allocated.stocks.bases.getValue(1000).supplies)
        assertEquals(500,allocated.stocks.warehouses.getValue(101).supplies)
        val reorganization = f.prepare(f.request())
        val result = f.act(reorganization.copy(transfer=UnitTransfer(mapOf(ship to 2),mapOf(crew to 10))))
        assertNull(result.rejection)
        assertEquals(2,result.stocks!!.outfits.getValue(1).units.ships[ship])
        assertEquals(10,result.stocks.outfits.getValue(1).units.troops[crew])
        assertEquals(10,result.stocks.warehouses.getValue(101).units.ships[ship])
        assertEquals(result.stocks,f.store.saved)
        f.production = PlanetProduction(f.galaxy,f.store,f.rules.startSeconds,PlanetProductionPolicy())
        f.logistics = OutfitLogistics({ f.now },f.galaxy)
        assertEquals(result.stocks,f.production.snapshot(1))
        assertEquals(LogisticsRejection.PREPARATION,f.act(reorganization).rejection)
        assertEquals(LogisticsRejection.PREPARATION,f.act(allocation).rejection)
    }
    @Test fun authenticationAndStoredRolesCannotBeChosenByTheRequest() {
        val f = Fixture()
        val r = f.request()
        assertEquals(LogisticsRejection.SESSION,f.act(r.copy(connection="forged")).rejection)
        assertEquals(LogisticsRejection.SESSION,f.act(r.copy(generation=2)).rejection)
        assertEquals(LogisticsRejection.IDENTITY,f.act(r.copy(country=Power.ALLIANCE.country)).rejection)
        assertEquals(LogisticsRejection.IDENTITY,f.act(r,navigator=null).rejection)
        assertEquals(LogisticsRejection.IDENTITY,f.act(r,navigator=f.player(12)).rejection)
        assertEquals(LogisticsRejection.ACCESS,f.act(f.request(12)).rejection)
        assertEquals(LogisticsRejection.ACCESS,f.act(f.request(11,mode=LogisticsMode.ALLOCATION)).rejection)
        assertEquals(LogisticsRejection.ACCESS,f.act(f.request(10,outfit=2,mode=LogisticsMode.ALLOCATION)).rejection)
        assertEquals(LogisticsRejection.ACCESS,f.act(f.request(16,mode=LogisticsMode.ALLOCATION)).rejection)
        assertEquals(LogisticsRejection.ACCESS,f.act(f.request(17,mode=LogisticsMode.ALLOCATION)).rejection)
        assertEquals(0,f.store.writes)
    }
    @Test fun globalAllocationAndSharedWarehouseReservationsExcludeConflictingEditors() {
        val f = Fixture()
        val shared = f.prepare(f.request(12,2))
        assertEquals(LogisticsRejection.BUSY,f.act(f.request(13,3)).rejection)
        assertEquals(LogisticsRejection.BUSY,f.act(f.request(10,mode=LogisticsMode.ALLOCATION)).rejection)
        // A separate private warehouse may be edited concurrently.
        val private = f.prepare(f.request())
        assertNull(f.act(private.copy(phase=LogisticsPhase.CANCEL)).rejection)
        assertNull(f.act(shared.copy(phase=LogisticsPhase.CANCEL)).rejection)
        val allocation = f.prepare(f.request(10,mode=LogisticsMode.ALLOCATION))
        assertEquals(LogisticsRejection.BUSY,f.act(f.request(14,4)).rejection)
        assertEquals(LogisticsRejection.BUSY,f.act(f.request(16,2,LogisticsMode.ALLOCATION)).rejection)
        assertNull(f.act(allocation.copy(phase=LogisticsPhase.CANCEL)).rejection)
        assertNull(f.act(f.request(13,3)).rejection)
    }
    @Test fun invalidCrewingStockAndLocationDoNotMoveAnyUnitsAndCanBeCorrected() {
        val f = Fixture()
        val command = f.prepare(f.request())
        val before = f.store.saved
        assertEquals(LogisticsRejection.CREW,f.act(command.copy(transfer=UnitTransfer(mapOf(ship to 2),mapOf(crew to 9)))).rejection)
        assertEquals(LogisticsRejection.STOCK,f.act(command.copy(transfer=UnitTransfer(mapOf(ship to 100)))).rejection)
        assertEquals(LogisticsRejection.STOCK,f.act(command.copy(transfer=UnitTransfer(mapOf(UnitStack(199) to 1)))).rejection)
        assertEquals(LogisticsRejection.LOCATION,f.act(command,f.player(11).copy(baseId=0,mode=5)).rejection)
        assertEquals(LogisticsRejection.LOCATION,f.act(command,f.player(11).copy(cpPaused=true)).rejection)
        assertEquals(before,f.store.saved); assertEquals(0,f.store.writes)
        assertNull(f.act(command.copy(transfer=UnitTransfer(mapOf(ship to 2),mapOf(crew to 10)))).rejection)
        assertEquals(LogisticsRejection.PREPARATION,f.act(command).rejection)
    }
    @Test fun expiryCancellationAndDisconnectionNeverLetStaleRequestsConsumeNewReservations() {
        val f = Fixture()
        val old = f.prepare(f.request())
        assertEquals(old.reservation,f.act(f.request()).reservation)
        f.now = 120000
        val fresh = f.prepare(f.request())
        assertNotEquals(old.reservation,fresh.reservation)
        assertEquals(LogisticsRejection.PREPARATION,f.act(old).rejection)
        assertEquals(LogisticsRejection.PREPARATION,f.act(old.copy(phase=LogisticsPhase.CANCEL)).rejection)
        f.session = f.session.copy(participants=f.session.participants + ("p11" to Participant(Power.EMPIRE,false,false)))
        assertNull(f.act(f.request(10,mode=LogisticsMode.ALLOCATION)).rejection)
        assertEquals(LogisticsRejection.SESSION,f.act(fresh).rejection)
        assertEquals(0,f.store.writes)
    }
    @Test fun failedDurableReplacementNeverPublishesOneSideAndRetryDoesNotDuplicate() {
        val f = Fixture()
        val command = f.prepare(f.request(10,mode=LogisticsMode.ALLOCATION))
            .copy(transfer=UnitTransfer(mapOf(ship to 2),mapOf(crew to 10)),supplies=500)
        val before = f.production.snapshot(1)
        f.store.fail = true
        assertFailsWith<java.io.IOException> { f.act(command) }
        assertEquals(before,f.production.snapshot(1)); assertEquals(before,f.store.saved)
        f.store.fail = false
        assertNull(f.act(command).rejection)
        assertEquals(LogisticsRejection.PREPARATION,f.act(command).rejection)
        assertEquals(8,f.store.saved.bases.getValue(1000).units.ships[ship]); assertEquals(1,f.store.writes)
    }
    @Test fun publishedLedgerMapsCannotBeMutatedOutsideTheActor() {
        val f = Fixture()
        val snapshot = f.act(f.request()).stocks!!
        assertFailsWith<UnsupportedOperationException> { (snapshot.outfits as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.warehouses as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.logisticsOfficers as MutableMap).clear() }
    }
    @Test fun commanderReappointmentDoesNotReviveAnOldWorldActorReservation() = runBlocking {
        val f = Fixture()
        f.store.saved = f.store.saved.copy(nationalOffices = mapOf(NationalOffice.MILITARY_MINISTER to 10))
        val navigation = object : NavigationStore {
            override fun load() = mapOf("p10" to f.player(10), "p11" to f.player(11))
            override fun save(players: Map<String, Navigator>) = Unit
        }
        val engine = WorldEngine(this, rules = f.rules, ticking = false, galaxy = f.galaxy,
            navigationStore = navigation, stockStore = f.store, worldStateCommitter = WorldStateCommitter { _, stocks ->
                f.store.save(checkNotNull(stocks))
            })
        try {
            for (id in 10..11) {
                val joined = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("p$id", Power.EMPIRE, false, joined, "c$id", 1))
                assertEquals(Admission.ACCEPTED, withTimeout(5000) { joined.await() })
            }
            suspend fun act(request: LogisticsRequest): LogisticsResult {
                val result = CompletableDeferred<LogisticsResult>()
                engine.submit(WorldCommand.Logistics(request, result))
                return withTimeout(5000) { result.await() }
            }

            val request = f.request()
            val old = assertNotNull(act(request).reservation)
            val card = FleetOffice.EMPIRE_COMMANDER.card
            val dismissed = CompletableDeferred<OfficeDismissalResult>()
            engine.submit(WorldCommand.DismissOffice(
                OfficeDismissalRequest("p10", "c10", 10, Power.EMPIRE, 1, 11, card, 2, 2), dismissed))
            assertTrue(withTimeout(5000) { dismissed.await() }.changed)
            assertEquals(0, f.store.saved.outfits.getValue(1).commanderId)

            val appointed = CompletableDeferred<GarrisonAppointmentResult>()
            engine.submit(WorldCommand.AppointGarrison(
                GarrisonAppointmentRequest("p10", "c10", 10, Power.EMPIRE, 1, 0, 11, card, 2, 5, 2, outfit = 1), appointed))
            assertTrue(withTimeout(5000) { appointed.await() }.changed)
            assertEquals(11, f.store.saved.outfits.getValue(1).commanderId)

            assertEquals(LogisticsRejection.PREPARATION, act(request.copy(phase = LogisticsPhase.COMMIT, reservation = old)).rejection)
            assertNotEquals(old, assertNotNull(act(request).reservation))
        } finally {
            engine.drainAndClose()
        }
    }

    @Test fun worldActorSerializesCompetingConnectionsAndStopsAfterStorageFailure() = runBlocking {
        val f = Fixture()
        val navigation = object : NavigationStore {
            override fun load() = f.powers.keys.associate { "p$it" to f.player(it) }
            override fun save(players: Map<String, Navigator>) = Unit
        }
        val engine = WorldEngine(this,rules=f.rules,ticking=false,galaxy=f.galaxy,
            navigationStore=navigation,stockStore=f.store)
        try {
            for ((id,power) in f.powers) {
                val joined = CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("p$id",power,false,joined,"c$id",1))
                assertEquals(Admission.ACCEPTED,withTimeout(5000) { joined.await() })
            }
            suspend fun act(request: LogisticsRequest): LogisticsResult {
                val result = CompletableDeferred<LogisticsResult>()
                engine.submit(WorldCommand.Logistics(request,result))
                return withTimeout(5000) { result.await() }
            }
            val requests = listOf(f.request(12,2),f.request(13,3))
            val results = requests.map { async { act(it) } }.awaitAll()
            assertEquals(1,results.count { it.rejection == null })
            assertEquals(1,results.count { it.rejection == LogisticsRejection.BUSY })
            val index = results.indexOfFirst { it.rejection == null }
            val commit = requests[index].copy(phase=LogisticsPhase.COMMIT,reservation=results[index].reservation,
                transfer=UnitTransfer(mapOf(ship to 2),mapOf(crew to 10)))
            val before = engine.snapshot().planetStocks
            f.store.fail = true
            assertFailsWith<java.io.IOException> { act(commit) }
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertEquals(before,engine.snapshot().planetStocks)
            assertEquals(before,f.store.saved)
            f.store.fail = false
            assertFailsWith<IllegalStateException> { act(commit) }
            assertEquals(before,f.store.saved)
        } finally { engine.drainAndClose() }
    }
}
