package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryCivicTest {
    private val country=CountryId(42)
    private val other=CountryId(43)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(
        GalaxyBase(1000,2487,80,0,"Capital",0,20f,86400,0,0f,2f),
        GalaxyBase(1001,2487,80,0,"Province",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val session=SessionSimulation(SessionRules.load()).also {it.join("a",country,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val player=Navigator(1,10000,country,2487,1000,1000,1000,baseId=1001,mode=4,shipStyle=ShipStyleId("alliance"))
    private val troop=UnitStack(UnitSpecifications.crews.first {it.power==3 && it.type!=3}.kind)
    private fun catalog(family:String="empire")=CountryCatalogLoader.load().let {old->CountryCatalog(
        old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id->
            old.countries.getValue(2).copy(id=id,key="country_$id",family=family,shipStyleOverride="alliance")})}
    private fun stock(family:String="empire"):PlanetStocks {
        val ground=ActiveGroundUnits(listOf(ActiveGroundUnit(1,troop,base=1001)))
        return PlanetStocks(1,0,(1000..1001).associate {id->id to PlanetStock(id,Allegiance.Country(country),0,
            2000,200,300,PlanetEconomy(1000,600,70,80))},
            mapOf(200 to OutfitStock(200,country,OutfitKind.FLEET,1001,300,10000,
                UnitInventory(troops=ground.quantities()),supplies=100,activeGround=ground,shipStyle=ShipStyleId("alliance"))),
            mapOf(300 to OutfitWarehouse(300,country,OutfitKind.FLEET,1001,200,shipStyle=ShipStyleId("alliance"))),nextShipId=2,
            strategic=StrategicState(capitals=mapOf(country to 1000),offices=listOf(
                OfficeHolding(if(family=="empire")39 else 175,200,10000,country))))
    }
    private fun request(command:Int,card:Int=59)=StrategyActionRequest("a","c",10000,country,1,command,card,5,
        outfit=if(command==55)0 else 200,requestToken=37)
    private fun act(command:Int,s:PlanetStocks=stock(),p:Navigator=player,card:Int=59,definitions:CountryCatalog=catalog(),g:Galaxy=galaxy)=
        StrategyActions.evaluate(g,request(command,card),session,p,s,definitions)
    private fun settle(s:PlanetStocks,p:Navigator=player,definitions:CountryCatalog=catalog(),g:Galaxy=galaxy,eliminated:Set<CountryId> = emptySet())=
        CivicActions.settle(g,s,now+CivicActions.DAY,listOf(p),definitions,eliminated)

    @Test fun bothOfficeSystemsCommandOnlyTheirOwnCountrysGroundUnitsRegardlessOfModelStyle() {
        for(family in listOf("empire","alliance"))for(command in listOf(52,54)) {
            val definitions=catalog(family);val s=stock(family);val card=if(family=="empire")39 else 175
            val accepted=act(command,s,card=card,definitions=definitions)
            assertNull(accepted.rejection);assertEquals(840,accepted.player?.mcp)
            assertEquals(country,accepted.civic?.country);assertEquals(setOf(1),accepted.civic?.units)
            val complete=settle(assertNotNull(accepted.stocks),definitions=definitions)
            assertEquals(CivicPhase.COMPLETED,complete.civic.orders.getValue(10000).phase)
            assertEquals(5,complete.civic.orders.getValue(10000).effect)
            assertEquals(if(command==52)85 else 80,complete.bases.getValue(1001).economy?.peace)
            assertEquals(if(command==54)75 else 70,complete.bases.getValue(1001).economy?.approval)
            assertEquals(country,complete.outfits.getValue(200).country)
            assertEquals(ShipStyleId("alliance"),complete.outfits.getValue(200).shipStyle)
            val foreign=s.copy(outfits=s.outfits.mapValues {(_,o)->o.copy(country=other)},
                warehouses=s.warehouses.mapValues {(_,w)->w.copy(country=other)})
            assertEquals(StrategyActionRejection.AUTHORITY,act(command,foreign,definitions=definitions).rejection)
            val borrowed=s.copy(outfits=s.outfits.mapValues {(_,o)->o.copy(commanderId=10001)},
                strategic=s.strategic.copy(offices=listOf(OfficeHolding(card,200,10000,other))))
            assertEquals(StrategyActionRejection.CARD,act(command,borrowed,card=card,definitions=definitions).rejection)
        }
    }

    @Test fun completionRechecksCountryAndOfficeAfterOccupationDefectionOrRuleChanges() {
        val accepted=act(52,card=39)
        val pending=assertNotNull(accepted.stocks);assertNull(accepted.rejection)
        val occupied=pending.copy(bases=pending.bases+(1001 to pending.bases.getValue(1001).copy(owner=Allegiance.Country(other))))
        for(done in listOf(settle(occupied),settle(pending,player.copy(country=other)),settle(pending,definitions=catalog("alliance")))) {
            assertEquals(CivicPhase.INVALIDATED,done.civic.orders.getValue(10000).phase)
            assertEquals(80,done.bases.getValue(1001).economy?.peace)
        }
        assertEquals(840,accepted.player?.mcp)
    }

    @Test fun guardUsesTheNativePersonalCardButRetainsTheActualCountry() {
        val s=stock();val definitions=catalog()
        val lobby=galaxy.facilitiesAt(1001,s,definitions).flatMap {it.spots}.first {it.kind==0 && it.access==SpotAccess.GUARDED}.id
        val p=player.copy(spotId=lobby)
        val accepted=act(55,s,p,definitions=definitions);assertNull(accepted.rejection)
        val pending=assertNotNull(accepted.stocks)
        assertEquals(country,accepted.civic?.country);assertEquals(2,CivicActions.guardStrength(pending,1001,lobby,now))
        assertEquals(CivicPhase.COMPLETED,settle(pending,p).civic.orders.getValue(10000).phase)
        val invalid=settle(pending,p.copy(country=other))
        assertEquals(CivicPhase.INVALIDATED,invalid.civic.orders.getValue(10000).phase)
        assertEquals(1,CivicActions.guardStrength(invalid,1001,lobby,now))
        assertEquals(StrategyActionRejection.CARD,act(55,s,p,card=195).rejection)
    }

    @Test fun pillageDistinguishesHistoricalSeedOwnershipFromTheCurrentDisplay() {
        val accepted=act(56)
        assertNull(accepted.rejection)
        val complete=settle(assertNotNull(accepted.stocks))
        assertEquals(CivicPhase.COMPLETED,complete.civic.orders.getValue(10000).phase)
        assertEquals(900,complete.civic.orders.getValue(10000).effect,"the existing 100 supplies leave room for 900")
        assertEquals(1100,complete.bases.getValue(1001).supplies)
        assertEquals(1000,complete.outfits.getValue(200).supplies)
        assertEquals(country,complete.outfits.getValue(200).country)
    }

    @Test fun customSeedOwnersAllowForeignPillageButNeverDomesticOrNonstatePillage() {
        for(owner in listOf(Allegiance.Country(other),Allegiance.Country(country),Allegiance.Neutral,Allegiance.Unspecified,Allegiance.Pirates)) {
            val g=Galaxy(galaxy.systems,galaxy.bases,galaxy.starts,initialOwners=mapOf(1001 to owner))
            val source=stock();val result=act(56,g=g)
            if(owner.country!=other) {
                assertEquals(StrategyActionRejection.LOCATION,result.rejection);assertEquals(source,result.stocks)
                assertEquals(player,result.player);continue
            }
            assertNull(result.rejection);assertEquals(840,result.player?.mcp)
            val pending=assertNotNull(result.stocks);val done=settle(pending,g=g)
            assertEquals(900,done.civic.orders.getValue(10000).effect)
            assertEquals(CountryId(43),g.initialOwners.getValue(1001).country)
            assertEquals(country,done.bases.getValue(1001).country)
            assertEquals(done,settle(done,g=g))
            val defeated=settle(pending,g=g,eliminated=setOf(country))
            assertEquals(CivicPhase.INVALIDATED,defeated.civic.orders.getValue(10000).phase)
            assertEquals(pending.bases,defeated.bases);assertEquals(pending.outfits,defeated.outfits)
        }
    }
}
