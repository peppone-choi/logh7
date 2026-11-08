package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryTacticalCombatTest {
    private val rules=SessionRules.load()
    private val session=SessionSimulation(rules).also {
        it.join("pilot",Power.EMPIRE,false,"one");it.join("enemy",Power.EMPIRE,false,"two")
    }.exportState().let {s->s.copy(participants=s.participants.mapValues {(name,p)->
        p.copy(country=CountryId(if(name=="pilot")42 else 43))})}
    private val now=session.gameMillis/1000
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2488,4,"Battle",2)),
        listOf(GalaxyBase(1000,2488,80,0,"Base",0,0f,86400,0,0f,2f)),
        Power.entries.associateWith {2488},countryStarts=mapOf(CountryId(42) to 2488))
    private val countries=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id",name="Country $id")})}
    private fun motion(x:Float,y:Float)=TacticalMotion(2488,now,TacticalPose(TacticalPoint(x,y),0f),0f,0f,listOf(TacticalPoint(x,y)))
    private fun players()=mapOf("pilot" to Navigator(1,10000,Power.EMPIRE,2488,100,100,1000,cpPaused=true,
        hull=FlagshipHull(0,now),motion=motion(100f,0f),commandRange=TacticalCommandRange(now,TacticalCommandRange.MAXIMUM))
        .copy(country=CountryId(42)),
        "enemy" to Navigator(1,10001,Power.EMPIRE,2488,100,100,1000,cpPaused=true,
            hull=FlagshipHull(0,now),motion=motion(100f,4f)).copy(country=CountryId(43)))
    private fun stocks():PlanetStocks {
        val outfits=listOf(42,43).mapIndexed {i,country->
            val ships=ActiveShipUnits(listOf(ActiveShipUnit(i+1,UnitStack(32),
                tactical=NpcTacticalState(motion(100f,2f+i),now,now))))
            i+1 to OutfitStock(i+1,CountryId(country),OutfitKind.FLEET,1000,10+i,10000+i,
                UnitInventory(ships.quantities()),activeShips=ships,position=OutfitPosition(2488),shipStyle=ShipStyleId("empire"))
        }.toMap()
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,Allegiance.Country(CountryId(42)),0,100,0,0)),
            outfits,outfits.values.associate {o->o.warehouseId to OutfitWarehouse(o.warehouseId,o.country,
                OutfitKind.FLEET,1000,o.id,shipStyle=o.shipStyle)},deploymentInitialized=true,nextShipId=3)
    }

    @Test fun targetsAndFriendlyObstructionUseCountryRatherThanTheirSharedModels() {
        val s=stocks();val people=players();val camp=Camp(CountryId(42))
        assertNull(TacticalCombat.target(1,2488,camp,s,galaxy,people))
        assertNotNull(TacticalCombat.target(2,2488,camp,s,galaxy,people))
        assertNotNull(TacticalCombat.target(people.getValue("enemy").unitId,2488,camp,s,galaxy,people))
        fun blocked(state:PlanetStocks)=TacticalLineOfFire.blocked(people.getValue("pilot").unitId,2488,camp,
            TacticalPoint(100f,0f),TacticalPoint(100f,4f),now,people.values,state,galaxy)
        assertTrue(blocked(s))
        assertFalse(blocked(s.copy(outfits=s.outfits+(1 to s.outfits.getValue(1).copy(country=CountryId(43))),
            warehouses=s.warehouses+(10 to s.warehouses.getValue(10).copy(country=CountryId(43))))))
        val independent=people.mapValues {(_,p)->p.copy(country=null)}
        assertNotNull(TacticalCombat.target(independent.getValue("enemy").unitId,2488,
            Camp.of(independent.getValue("pilot")),s,galaxy,independent))
    }

    @Test fun fleetOrdersRejectForeignUnitsAndPersistOwnCountryOrders() {
        var savedPlayers=players();var savedStocks=stocks()
        val store=object:PlanetStockStore {
            override fun load()=savedStocks
            override fun save(stocks:PlanetStocks){savedStocks=stocks}
        }
        val navigation=Navigation(galaxy,null,NavigationPolicy(),clockScale=1,restored=savedPlayers,configuredCountries=countries)
        val production=PlanetProduction(galaxy,store,rules.startSeconds,PlanetProductionPolicy()).also {it.settle(1,now)}
        val commit=WorldStateCommitter {p,s->savedPlayers=p.toMap();savedStocks=checkNotNull(s)}
        fun accept(unit:Int)=TacticalCommandOperations.accept(NavigationRequest("pilot",10000,CountryId(42),"one",1,
            captainCard=59,shipKind=0,tacticalCommand=TacticalCommand(0x40d,listOf(unit),kind=1)),session,galaxy,navigation,
            production,production.snapshot(1)!!,commit,1)
        assertEquals(NavigationRejection.UNIT,accept(2).rejection)
        assertTrue(savedStocks.tacticalCommands.orders.isEmpty())
        assertNull(accept(1).rejection)
        val order=savedStocks.tacticalCommands.orders.single()
        assertEquals(listOf(1),order.command.units)
        val events=mutableListOf<TacticalCommandResult>()
        val after=TacticalCommandOperations.advance(session.copy(gameMillis=order.readyAt*1000),galaxy,
            savedPlayers.toMutableMap(),savedStocks,1,events,mutableListOf())
        assertTrue(after.tacticalCommands.orders.isEmpty());assertFalse(events.single().cancelled)
        assertNotNull(after.outfits.getValue(1).activeShips!!.values.single().tactical)
    }

    @Test fun garrisonAndCaptainCardsResolveOnlyTheConfiguredCountry() {
        val s=stocks().let {it.copy(strategic=StrategicState(offices=listOf(OfficeHolding(55,1000,10000,CountryId(42)),
            OfficeHolding(55,1000,10001,CountryId(43)))))}
        assertEquals(10000,GroundCommandAuthority.garrison(1000,s,galaxy,countries))
        assertEquals(10001,GroundCommandAuthority.garrison(1000,s.copy(bases=s.bases+(1000 to
            s.bases.getValue(1000).copy(owner=Allegiance.Country(CountryId(43))))),galaxy,countries))
        assertEquals(59,TacticalAuthority.captainCard(players().getValue("pilot"),countries))
        assertNull(TacticalAuthority.captainCard(players().getValue("pilot").copy(country=CountryId(99)),countries))
    }

    @Test fun defeatReturnsToOwnCountryAndNeverToASharedDisplayCountrysBase() {
        val s=stocks();val pilot=players().getValue("pilot")
        val home=FlagshipRecovery.defeated(pilot,now,galaxy,s)
        assertEquals(1000,home.baseId);assertEquals(CountryId(42),home.country)
        val lost=s.copy(bases=s.bases+(1000 to s.bases.getValue(1000).copy(owner=Allegiance.Country(CountryId(43)))))
        assertEquals(0,FlagshipRecovery.defeated(pilot,now,galaxy,lost).baseId)
        val independent=FlagshipRecovery.defeated(pilot.copy(country=null),now,galaxy,s)
        assertEquals(0,independent.baseId);assertEquals(pilot.grid,independent.grid)
    }

    @Test fun defeatUsesTheStartingPlanetBeforeARelocatedCapitalAndClearsTheOldPosture() {
        val country=CountryId(42)
        val birth=galaxy.bases.single()
        val capital=birth.copy(id=1001,grid=2489)
        val field=Galaxy(galaxy.systems+GalaxySystem(4,2489,4,"New capital",2),
            listOf(birth,capital),galaxy.starts,countryStarts=mapOf(country to birth.grid))
        val before=stocks().copy(outfits=emptyMap(),warehouses=emptyMap())
        val inventory=before.copy(bases=before.bases+(capital.id to before.bases.getValue(birth.id).copy(baseId=capital.id)),
            strategic=before.strategic.copy(capitals=mapOf(country to capital.id)))
        val pilot=players().getValue("pilot").copy(posture=TacticalPosture(birth.grid,TacticalShipMode.ANCHORED,birth.id))
        val fallback=FlagshipRecovery.defeated(pilot,now,field,inventory)
        assertEquals(birth.id,fallback.baseId)
        assertNull(fallback.posture)
        assertEquals(capital.id,FlagshipRecovery.defeated(pilot.copy(returnBase=capital.id),now,field,inventory).baseId)
        val recorded = pilot.copy(originBase = capital.id)
        assertEquals(capital.id, FlagshipRecovery.defeated(recorded, now, field, inventory).baseId)
        val captured = inventory.copy(bases = inventory.bases + (capital.id to inventory.bases.getValue(capital.id)
            .copy(owner = Allegiance.Country(CountryId(43)))))
        assertEquals(birth.id, FlagshipRecovery.defeated(recorded, now, field, captured).baseId)
        val independent=pilot.copy(country=null,returnBase=capital.id)
        val claimed=inventory.copy(bases=inventory.bases+(capital.id to inventory.bases.getValue(capital.id)
            .copy(owner=Allegiance.Independent(pilot.characterId))))
        assertEquals(capital.id,FlagshipRecovery.defeated(independent,now,field,claimed).baseId)
    }

    @Test fun breachedFortressSinksOnlyItsActualHoldersStationedUnits() {
        val fortress=galaxy.bases.single().copy(kind=1)
        val field=Galaxy(galaxy.systems,listOf(fortress),galaxy.starts)
        val s=stocks().let {s->s.copy(bases=s.bases.mapValues {(_,b)->b.copy(owner=Allegiance.Country(CountryId(43)))},
            outfits=s.outfits.mapValues {(_,o)->o.copy(activeShips=ActiveShipUnits(o.activeShips!!.values.map {
                it.copy(posture=TacticalPosture(2488,TacticalShipMode.STATIONED,1000))}))})}
        val breached=FortressArmor.strike(fortress,s,20)
        val result=FortressArmor.advance(session,field,mutableMapOf(),breached,mutableListOf())
        assertEquals(s.outfits.getValue(1).activeShips!!.values.single().boats,
            result.outfits.getValue(1).activeShips!!.values.single().boats)
        assertEquals(0,result.outfits.getValue(2).activeShips!!.values.single().boats)
    }
}
