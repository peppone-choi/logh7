package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryStrategyRoutingTest {
    private val country=CountryId(42)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private fun session(id:CountryId?=country)=SessionSimulation(SessionRules.load()).also {it.join("pilot",id,false,"c")}.exportState()
    private fun player(id:CountryId?=country,style:ShipStyleId=ShipStyleId("empire"))=
        Navigator(1,10000,id,2487,1000,1000,1000,baseId=1049,mode=4,shipStyle=style,
            hull=FlagshipHull(Flagships.forType(style.nativePower,0).kind,0,hp=50),supplies=100)
    private fun catalog(native:Int=2,family:String="empire"):CountryCatalog {
        val old=CountryCatalogLoader.load()
        return CountryCatalog(old.calendars.values.toList(),old.families.values.toList(),old.countries.values.toList()+
            CountryDefinition(42,"test_country","Test country",family,native,0,"empire"))
    }
    private fun stock(style:ShipStyleId=ShipStyleId("empire")):PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(if(style.nativePower==2)32 else 139))))
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(if(style.nativePower==2)0 else 4),base=1049)))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,Allegiance.Country(country),0,1000,200,300)),
            outfits=mapOf(1 to OutfitStock(1,country,OutfitKind.TRANSPORT,1049,101,10000,
                UnitInventory(ships.quantities(),troops.quantities()),activeShips=ships,activeGround=troops,shipStyle=style)),
            warehouses=mapOf(101 to OutfitWarehouse(101,country,OutfitKind.TRANSPORT,1049,1,shipStyle=style)),nextShipId=3)
    }
    private fun request(command:Int=47,card:Int=59,id:CountryId?=country,outfit:Int=1)=
        StrategyActionRequest("pilot","c",10000,id,1,command,card,10,outfit)

    @Test fun trainingUsesActualCountryAndPreservesModelsUnitsAndCosts() {
        for(style in listOf(ShipStyleId("empire"),ShipStyleId("alliance")))for(command in listOf(47,48,50)) {
            val before=stock(style)
            val result=StrategyActions.evaluate(galaxy,request(command),session(),player(style=style),before,catalog())
            assertNull(result.rejection);assertTrue(result.changed);assertEquals(920,result.player!!.mcp)
            val after=result.stocks!!;val outfit=after.outfits.getValue(1)
            assertEquals(country,outfit.country);assertEquals(style,outfit.shipStyle)
            assertEquals(before.warehouses,after.warehouses)
            assertEquals(listOf(1),outfit.activeShips!!.values.map {it.id})
            assertEquals(listOf(2),outfit.activeGround!!.values.map {it.id})
            when(command) {
                47->assertEquals(1,outfit.activeShips.values.single().stack.level)
                48->assertEquals(1,outfit.activeGround.values.single().stack.level)
                50->assertEquals(1,after.strategic.people.getValue(10000).groundTactics)
            }
        }
    }

    @Test fun displayedSideCannotAuthorizeAnotherCountrysArmyOrBase() {
        val before=stock();val own=player()
        for(id in listOf(CountryId(2),CountryId(43),null)) {
            val denied=StrategyActions.evaluate(galaxy,request(id=id),session(),own,before,catalog())
            assertEquals(StrategyActionRejection.IDENTITY,denied.rejection);assertEquals(before,denied.stocks);assertEquals(own,denied.player)
        }
        assertEquals(StrategyActionRejection.IDENTITY,StrategyActions.evaluate(galaxy,request(),session(CountryId(43)),own,before,catalog()).rejection)
        assertEquals(StrategyActionRejection.IDENTITY,StrategyActions.evaluate(galaxy,request(id=null),session(null),player(null),before,catalog()).rejection)
        val foreignArmy=before.copy(outfits=before.outfits.mapValues {it.value.copy(country=CountryId(2))},
            warehouses=before.warehouses.mapValues {it.value.copy(country=CountryId(2))})
        assertEquals(StrategyActionRejection.AUTHORITY,StrategyActions.evaluate(galaxy,request(),session(),own,foreignArmy,catalog()).rejection)
        val foreignBase=before.copy(bases=before.bases.mapValues {it.value.copy(owner=Allegiance.Country(CountryId(2)))})
        assertEquals(StrategyActionRejection.LOCATION,StrategyActions.evaluate(galaxy,request(61,outfit=0),session(),own,foreignBase,catalog()).rejection)
    }

    @Test fun personalRepairUsesOwnedBaseAndRetirementUsesRankFamilyIndependentlyOfDisplayAndHull() {
        for((native,family,keptRank) in listOf(Triple(2,"alliance",0),Triple(3,"empire",10))) {
            val definitions=catalog(native,family);val card=if(native==2)59 else 195
            val pilot=player(style=ShipStyleId(if(native==2)"alliance" else "empire"));val before=stock(pilot.shipStyle)
            val repaired=StrategyActions.evaluate(galaxy,request(61,card,outfit=0),session(),pilot,before,definitions)
            assertNull(repaired.rejection);assertEquals(840,repaired.player!!.mcp)
            assertEquals(100,repaired.player.hull!!.hp);assertEquals(0,repaired.player.supplies)
            assertEquals(before,repaired.stocks)
            val retired=StrategyActions.evaluate(galaxy,request(84,0,outfit=0),session(),pilot,before,definitions)
            assertNull(retired.rejection);assertEquals(keptRank,retired.stocks!!.strategic.people.getValue(10000).militaryRank)
            val veteran=retired.stocks.copy(strategic=retired.stocks.strategic.copy(people=mapOf(10000 to StrategicPerson(rank=1))))
            val returned=StrategyActions.evaluate(galaxy,request(85,card,outfit=0),session(),pilot,veteran,definitions)
            assertNull(returned.rejection);assertEquals(pilot.shipStyle,returned.player!!.shipStyle)
            assertEquals(Flagships.forType(pilot.shipStyle.nativePower,0).kind,returned.player.hull!!.kind)
        }
    }

    @Test fun originalNationOfficesCannotBeBorrowedByAnAdditionalCountry() {
        val before=stock();val pilot=player()
        for(command in PersonnelActions.supported+StrategicOperations.supported+TransportActions.supported) {
            val result=StrategyActions.evaluate(galaxy,request(command,17),session(),pilot,before,catalog())
            assertEquals(StrategyActionRejection.AUTHORITY,result.rejection,"command $command")
            assertEquals(pilot,result.player);assertEquals(before,result.stocks)
        }
        for(command in CivicActions.supported) {
            val result=StrategyActions.evaluate(galaxy,request(command,17),session(),pilot,before,catalog())
            assertEquals(StrategyActionRejection.CARD,result.rejection,"command $command requires its country's own civic card")
            assertEquals(pilot,result.player);assertEquals(before,result.stocks)
        }
        val budget=BudgetActions.evaluate(galaxy,BudgetRequest(request(16,8)),session(),pilot,before)
        assertEquals(BudgetRejection.AUTHORITY,budget.rejection);assertEquals(before,budget.stocks)
        val commands=GatheringActions.commands+Justice.commands+EspionageActions.commands+CoupActions.commands+
            GoalActions.commands+TradeActions.commands+ProductionActions.commands+SuccessionActions.commands+
            ProposalActions.commands+AcademyActions.commands+setOf(FundActions.FUNDS,14)
        for(command in commands) {
            val result=SocialActions.evaluate(galaxy,SocialRequest(request(command,0)),session(),pilot,before)
            assertEquals(SocialRejection.CARD,result.rejection,"command $command")
            assertEquals(pilot,result.player);assertEquals(before,result.stocks)
        }
        val orbital=SocialActions.evaluate(galaxy,SocialRequest(request(OrbitalStrikeActions.STRIKE,0)),session(),pilot,before)
        assertEquals(SocialRejection.LOCATION,orbital.rejection,"country bombardment still requires a tactical flagship")
        assertEquals(pilot,orbital.player);assertEquals(before,orbital.stocks)
    }
}
