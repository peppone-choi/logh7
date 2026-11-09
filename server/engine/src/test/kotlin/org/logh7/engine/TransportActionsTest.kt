package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 輸送計画→搬出入(반출)→이동→搬出入(반입)·輸送中止·격침 비율 손실·권한·한도. 작성: Peppone Choi. */
class TransportActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2),GalaxySystem(4,2488,4,"Far",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f),
        GalaxyBase(1050,2488,80,0,"Far",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also {it.join("officer",Power.EMPIRE,false,"o");it.join("captain",Power.EMPIRE,false,"c")}.exportState()
    private val officer=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1)
    private val captain=Navigator(1,10001,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=1)
    private val hull=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2 && it.name.startsWith("輸送艦")}.kind)
    private val boats=UnitSpecifications.ships.single {it.kind==hull.kind}.number
    private val cargo=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2}.kind)
    private fun stocks(alive:Int=boats,at:Int=1049):PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,hull,boats=alive)))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,5000,200,300,units=UnitInventory(mapOf(cargo to 4))),
            1050 to PlanetStock(1050,2,0,100,200,300)),
            mapOf(200 to OutfitStock(200,2,OutfitKind.TRANSPORT,1049,300,10001,UnitInventory(ships=mapOf(hull to 1)),
                activeShips=ships,position=OutfitPosition(galaxy.bases.single {it.id==at}.grid,at))),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.TRANSPORT,1049,200)),logisticsOfficers=mapOf(10000 to LogisticsOffice.EMPIRE_SECOND),
            nextShipId=2)
    }
    private fun plan(supplies:Long=1000,kind:Int=cargo.kind,count:Int=2)=StrategyActionRequest("officer","o",10000,Power.EMPIRE,1,
        TransportActions.PLAN,25,5,base=1049,target=1050,amount=supplies,value=kind,argument=count)
    private fun load(pack:Int)=StrategyActionRequest("captain","c",10001,Power.EMPIRE,1,TransportActions.LOAD,44,5,outfit=200,target=pack)
    private fun act(r:StrategyActionRequest,s:PlanetStocks)=StrategyActions.evaluate(galaxy,r,session,if(r.account=="officer")officer else
        if(s.outfits.getValue(200).position?.baseId==1050)captain.copy(grid=2488,baseId=1050) else captain,s)

    @Test fun aPlannedPackageIsLoadedCarriedAndUnloadedIntoTheDestination() {
        val planned=act(plan(),stocks());assertNull(planned.rejection);assertEquals(920,planned.player?.mcp)
        val s=assertNotNull(planned.stocks);val pack=s.strategic.transports.single()
        assertEquals(4000,s.bases.getValue(1049).supplies);assertEquals(2,s.bases.getValue(1049).units.ships[cargo])
        assertEquals(1000,pack.supplies);assertEquals(0,pack.carrier)
        val loaded=act(load(pack.id),s);assertNull(loaded.rejection);assertEquals(840,loaded.player?.mcp)
        val aboard=assertNotNull(loaded.stocks);assertEquals(200,aboard.strategic.transports.single().carrier)
        assertEquals(StrategyActionRejection.LOCATION,act(load(pack.id),aboard).rejection,"cannot unload at the source")
        val moved=aboard.copy(outfits=aboard.outfits+(200 to aboard.outfits.getValue(200).copy(position=OutfitPosition(2488,1050))))
        val done=act(load(pack.id),moved);assertNull(done.rejection)
        val after=assertNotNull(done.stocks);assertTrue(after.strategic.transports.isEmpty())
        assertEquals(1100,after.bases.getValue(1050).supplies);assertEquals(2,after.bases.getValue(1050).units.ships[cargo])
    }
    @Test fun cargoBoundForACapturedDestinationCanBeUnloadedAtAnyOwnBase() {
        val s=assertNotNull(act(plan(),stocks()).stocks);val pack=s.strategic.transports.single()
        val aboard=assertNotNull(act(load(pack.id),s).stocks)
        assertEquals(StrategyActionRejection.LOCATION,act(load(pack.id),aboard).rejection,"while the destination holds, only it accepts the cargo")
        // The alliance takes the destination while the package is aboard: the carrier unloads back at Odin instead.
        val captured=aboard.copy(bases=aboard.bases+(1050 to aboard.bases.getValue(1050).copy(owner=Allegiance.fromLegacy(3))))
        val staleLoad=act(load(pack.id).copy(value=1),captured)
        assertEquals(StrategyActionRejection.QUOTE_CHANGED,staleLoad.rejection)
        assertEquals(captured,staleLoad.stocks)
        assertEquals(captain,staleLoad.player)
        val rerouted=act(load(pack.id).copy(value=2),captured);assertNull(rerouted.rejection)
        val after=assertNotNull(rerouted.stocks);assertTrue(after.strategic.transports.isEmpty())
        assertEquals(5000,after.bases.getValue(1049).supplies);assertEquals(4,after.bases.getValue(1049).units.ships[cargo])
        assertEquals(100,after.bases.getValue(1050).supplies,"the enemy receives nothing")
    }
    @Test fun cancellingReturnsAnUnloadedPackageAndASunkCarrierLosesItsShare() {
        val s=assertNotNull(act(plan(),stocks()).stocks);val id=s.strategic.transports.single().id
        val cancel=StrategyActionRequest("officer","o",10000,Power.EMPIRE,1,TransportActions.CANCEL,25,5,target=id)
        assertEquals(920,act(cancel,s).player?.mcp,"cancelling costs MCP 80")
        val back=assertNotNull(act(cancel,s).stocks);assertEquals(stocks().bases,back.bases);assertTrue(back.strategic.transports.isEmpty())
        val aboard=assertNotNull(act(load(id),s).stocks)
        assertEquals(StrategyActionRejection.BUSY,act(cancel,aboard).rejection,"a loaded package cannot be cancelled")
        val half=aboard.copy(outfits=aboard.outfits+(200 to aboard.outfits.getValue(200).copy(position=OutfitPosition(2488,1050),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,hull,boats=boats/2))))))
        val after=assertNotNull(act(load(id),half).stocks)
        assertEquals(100+1000*(boats/2)/boats,after.bases.getValue(1050).supplies);assertEquals(1,after.bases.getValue(1050).units.ships[cargo])
    }
    @Test fun fullWarehousesRejectCancellationAndUnloadingWithoutConsumingThePackageOrCp() {
        val planned=assertNotNull(act(plan(),stocks()).stocks)
        val pack=planned.strategic.transports.single()
        val source=planned.bases.getValue(1049).copy(supplies=Int.MAX_VALUE)
        val fullSource=planned.copy(bases=planned.bases+(1049 to source))
        val cancel=StrategyActionRequest("officer","o",10000,Power.EMPIRE,1,TransportActions.CANCEL,25,5,target=pack.id)
        val cancelled=act(cancel,fullSource)
        assertEquals(StrategyActionRejection.MAXIMUM,cancelled.rejection)
        assertEquals(fullSource,cancelled.stocks)
        assertEquals(officer,cancelled.player)

        val aboard=assertNotNull(act(load(pack.id),planned).stocks)
        val destination=aboard.bases.getValue(1050).copy(units=UnitInventory(ships=mapOf(cargo to 65535)))
        val moved=aboard.copy(bases=aboard.bases+(1050 to destination),outfits=aboard.outfits+
            (200 to aboard.outfits.getValue(200).copy(position=OutfitPosition(2488,1050))))
        val unloaded=act(load(pack.id),moved)
        assertEquals(StrategyActionRejection.MAXIMUM,unloaded.rejection)
        assertEquals(moved,unloaded.stocks)
        assertEquals(captain.copy(grid=2488,baseId=1050),unloaded.player)
        val size=UnitSpecifications.ships.single {it.kind==cargo.kind}.number
        val limit=minOf(65535,255*size)
        val existing=limit/size-2
        val spare=limit%size
        val otherGrade=cargo.copy(level=2)
        val free=destination.copy(units=UnitInventory(ships=mapOf(otherGrade to existing)),
            spareShips=SpareShipBoats(if(spare>0)mapOf(otherGrade to spare) else emptyMap()))
        val justOver=free.copy(spareShips=SpareShipBoats(mapOf(otherGrade to spare+1)))
        val blocked=moved.copy(bases=moved.bases+(1050 to justOver))
        val blockedResult=act(load(pack.id),blocked)
        assertEquals(StrategyActionRejection.MAXIMUM,blockedResult.rejection)
        assertEquals(blocked,blockedResult.stocks)
        assertEquals(captain.copy(grid=2488,baseId=1050),blockedResult.player)
        val retried=assertNotNull(act(load(pack.id),moved.copy(bases=moved.bases+(1050 to free))).stocks)
        assertEquals(mapOf(otherGrade to existing,cargo to 2),retried.bases.getValue(1050).units.ships)
        assertEquals(free.spareShips,retried.bases.getValue(1050).spareShips)
        assertTrue(retried.strategic.transports.isEmpty())
    }

    @Test fun cancellationChecksShipCapacityWithoutBlockingSuppliesOnlyIntoLegacyStock() {
        val initial=stocks()
        val planned=assertNotNull(act(plan(),initial).stocks)
        val full=planned.bases.getValue(1049).copy(units=UnitInventory(ships=mapOf(cargo.copy(level=2) to 256)))
        val legacy=planned.copy(bases=planned.bases+(1049 to full))
        val pack=legacy.strategic.transports.single()
        val cancel=StrategyActionRequest("officer","o",10000,Power.EMPIRE,1,TransportActions.CANCEL,25,5,target=pack.id)
        val rejected=act(cancel,legacy)
        assertEquals(StrategyActionRejection.MAXIMUM,rejected.rejection)
        assertEquals(legacy,rejected.stocks)
        assertEquals(officer,rejected.player)
        val suppliesOnly=legacy.copy(strategic=legacy.strategic.copy(transports=listOf(pack.copy(units=UnitInventory()))))
        val accepted=act(cancel,suppliesOnly)
        assertNull(accepted.rejection)
        assertEquals(full.units,accepted.stocks?.bases?.getValue(1049)?.units)
        assertEquals(full.supplies+pack.supplies,accepted.stocks?.bases?.getValue(1049)?.supplies)
    }

    @Test fun cancelledPackageIdCannotTargetAReplacement() {
        val initial=stocks()
        val legacy=initial.copy(strategic=initial.strategic.copy(
            transports=listOf(TransportPackage(41,CountryId(2),1049,1050,1))))
        val legacyCancel=StrategyActionRequest("officer","o",10000,Power.EMPIRE,1,TransportActions.CANCEL,25,5,target=41)
        val clearedLegacy=assertNotNull(act(legacyCancel,legacy).stocks)
        assertTrue(clearedLegacy.strategic.transports.isEmpty())
        val planned=assertNotNull(act(plan(),clearedLegacy).stocks)
        val first=planned.strategic.transports.last()
        assertEquals(42,first.id)
        val cancel=StrategyActionRequest("officer","o",10000,Power.EMPIRE,1,TransportActions.CANCEL,25,5,target=first.id)
        val cancelled=assertNotNull(act(cancel,planned).stocks)
        val replacement=assertNotNull(act(plan(),cancelled).stocks)
        assertEquals(listOf(43),replacement.strategic.transports.map {it.id})
        assertEquals(StrategyActionRejection.TARGET,act(cancel,replacement).rejection)
        assertEquals(StrategyActionRejection.TARGET,act(load(first.id),replacement).rejection)

        val exhausted=initial.copy(strategic=initial.strategic.copy(lastTransportId=Int.MAX_VALUE))
        assertEquals(StrategyActionRejection.MAXIMUM,act(plan(),exhausted).rejection)
    }

    @Test fun cardsStockAndCapacityAreChecked() {
        assertEquals(StrategyActionRejection.CARD,act(plan().copy(card=24),stocks()).rejection)
        assertEquals(StrategyActionRejection.RESOURCE,act(plan(supplies=6000),stocks()).rejection)
        assertEquals(StrategyActionRejection.RESOURCE,act(plan(count=5),stocks()).rejection)
        assertEquals(StrategyActionRejection.TARGET,act(plan().copy(target=1049),stocks()).rejection)
        val big=assertNotNull(act(plan(supplies=4000,kind=TransportActions.NO_UNIT,count=0),stocks()).stocks)
        val room=OutfitSupplies.capacity(big.outfits.getValue(200))
        if(room<4000)assertEquals(StrategyActionRejection.TROOPS,act(load(big.strategic.transports.single().id),big).rejection)
        assertEquals(StrategyActionRejection.CARD,act(load(1).copy(card=39),big).rejection)
    }
    @Test fun onlyTheCurrentSourceOwnersPlansCountTowardItsQuota() {
        val historical=List(TransportActions.PER_BASE) {
            TransportPackage(it+1,Power.ALLIANCE.country,1049,1050,1)
        }
        val current=List(TransportActions.PER_BASE-1) {
            TransportPackage(historical.size+it+1,Power.EMPIRE.country,1049,1050,1)
        }
        val initial=stocks()
        val before=initial.copy(strategic=initial.strategic.copy(transports=historical+current,
            lastTransportId=historical.size+current.size))
        val request=plan(supplies=1,kind=TransportActions.NO_UNIT,count=0)
        val accepted=act(request,before)
        assertNull(accepted.rejection)
        assertEquals(officer.mcp-80,accepted.player?.mcp)
        val atLimit=assertNotNull(accepted.stocks)
        assertEquals(before.bases.getValue(1049).supplies-1,atLimit.bases.getValue(1049).supplies)
        assertEquals(historical,atLimit.strategic.transports.filter {it.country==Power.ALLIANCE.country})
        assertEquals(TransportActions.PER_BASE,atLimit.strategic.transports.count {it.country==Power.EMPIRE.country})

        val rejected=act(request,atLimit)
        assertEquals(StrategyActionRejection.MAXIMUM,rejected.rejection)
        assertEquals(atLimit,rejected.stocks)
        assertEquals(officer,rejected.player)
    }

    @Test fun shipLossIsCommittedBeforeReinforcementAndWipedCargoCannotReturn() {
        val planned=assertNotNull(act(plan(),stocks()).stocks)
        val loaded=assertNotNull(act(load(1),planned).stocks)
        val other=loaded.outfits.getValue(200).copy(id=201,warehouseId=301,commanderId=0,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(2,hull,boats=boats))))
        val otherPack=loaded.strategic.transports.single().copy(id=2,carrier=201)
        val aboard=loaded.copy(outfits=loaded.outfits+(201 to other),
            warehouses=loaded.warehouses+(301 to OutfitWarehouse(301,2,OutfitKind.TRANSPORT,1049,201)),
            nextShipId=3,strategic=loaded.strategic.copy(transports=loaded.strategic.transports+otherPack,lastTransportId=otherPack.id))
        val store=object:PlanetStockStore {
            var saved=aboard
            override fun load()=saved
            override fun save(stocks:PlanetStocks){saved=stocks}
        }
        val now=session.gameMillis/1000
        val production=PlanetProduction(galaxy,store,now,PlanetProductionPolicy())
        val initial=production.settle(1,now)
        val carrier=initial.outfits.getValue(200)
        val half=TacticalLosses.apply(carrier,1,boats/2)
        val damaged=production.replace(initial,initial.copy(outfits=initial.outfits+(200 to half)))
        assertEquals(boats-boats/2,store.saved.strategic.transports.single {it.id==1}.survivingBoats)
        val restored=production.replace(damaged,damaged.copy(outfits=damaged.outfits+
            (200 to carrier.copy(position=OutfitPosition(2488,1050)))))
        val unloaded=assertNotNull(act(load(1),restored).stocks)
        assertEquals(restored.bases.getValue(1050).supplies+1000*(boats-boats/2)/boats,
            unloaded.bases.getValue(1050).supplies)
        val secondHit=TacticalLosses.apply(restored.outfits.getValue(200),1,boats/4)
        val damagedAgain=production.replace(restored,restored.copy(outfits=restored.outfits+(200 to secondHit)))
        val remaining=(boats-boats/2)*(boats-boats/4)/boats
        assertEquals(remaining,damagedAgain.strategic.transports.single {it.id==1}.survivingBoats)
        val wiped=TacticalLosses.apply(secondHit,1,boats)
        val destroyed=production.replace(damagedAgain,damagedAgain.copy(outfits=damagedAgain.outfits+(200 to wiped)))
        assertEquals(listOf(otherPack),destroyed.strategic.transports)
        val reinforced=production.replace(destroyed,destroyed.copy(outfits=restored.outfits))
        assertEquals(StrategyActionRejection.TARGET,act(load(1),reinforced).rejection)
    }

    @Test fun lostCargoReleasesMaterialAndUnitSlotsForTheNextLoad() {
        val initial=stocks()
        val outfit=initial.outfits.getValue(200)
        val capacity=OutfitSupplies.capacity(outfit)-outfit.supplies
        val slots=TransportActions.unitSlots(outfit)
        val existing=TransportPackage(1,CountryId(2),1049,1050,capacity,
            UnitInventory(ships=mapOf(cargo to slots)),carrier=200,carrierBoats=boats)
        val pending=TransportPackage(2,CountryId(2),1049,1050,1,UnitInventory(ships=mapOf(cargo to 1)))
        fun state(carried:TransportPackage)=initial.copy(strategic=StrategicState(transports=listOf(carried,pending)))
        assertEquals(StrategyActionRejection.TROOPS,act(load(2),state(existing)).rejection)
        val loaded=assertNotNull(act(load(2),state(existing.copy(survivingBoats=boats/2))).stocks)
        assertEquals(200,loaded.strategic.transports.single {it.id==2}.carrier)
    }

    @Test fun deliveredScalesByTheSurvivingShare() {
        val p=TransportPackage(1,CountryId(2),1049,1050,1000,UnitInventory(mapOf(cargo to 3)),carrier=200,carrierBoats=100)
        assertEquals(1000 to p.units,TransportActions.delivered(p,100))
        assertEquals(0,TransportActions.delivered(p,0).first);assertTrue(TransportActions.delivered(p,0).second.ships.isEmpty())
        assertEquals(500,TransportActions.delivered(p,50).first);assertEquals(1,TransportActions.delivered(p,50).second.ships[cargo])
    }
}
