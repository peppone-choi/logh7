package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class LogisticsCountryAuthorityTest {
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1000,2487,60,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val condition=galaxy.conditions.getValue(1000)
    private val inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,100,200,300,
        PlanetEconomy(condition.population,condition.adultPopulation,condition.approval,condition.peace))),
        mapOf(1 to OutfitStock(1,2,OutfitKind.TRANSPORT,1000,101,10000,activeGround=ActiveGroundUnits(emptyList())),
            2 to OutfitStock(2,2,OutfitKind.FLEET,1000,102,10000,activeGround=ActiveGroundUnits(emptyList()))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.TRANSPORT,1000,1),102 to OutfitWarehouse(102,2,OutfitKind.FLEET,1000,2)),
        deploymentInitialized=true)
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1000,mode=4)
    private val session=SessionSimulation(rules).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()

    private fun rejected(expected:List<LogisticsRejection>,player:Navigator=pilot,state:SessionState=session,stocks:PlanetStocks=inventory) {
        val supply=CompletenessSupply.evaluate(CompletenessSupplyRequest("pilot","c",10000,Power.EMPIRE,1,1,2),state,player,stocks)
        val ground=GroundTransfer.evaluate(GroundTransferRequest("pilot","c",10000,Power.EMPIRE,1,2,GroundTransferAction.LOAD),state,player,stocks)
        val supplement=Supplement.evaluate(SupplementRequest("pilot","c",10000,Power.EMPIRE,1,1000,2,0,0,emptyMap()),state,player,stocks)
        val production=PlanetProduction(galaxy,null,rules.startSeconds,PlanetProductionPolicy(),restored=stocks)
        val logistics=OutfitLogistics({0},galaxy).apply(LogisticsRequest("pilot","c",10000,Power.EMPIRE,1,2,
            LogisticsMode.REORGANIZATION,LogisticsPhase.PREPARE),state,player,production)
        assertEquals(expected,listOf(supply.rejection,ground.rejection,supplement.rejection,logistics.rejection))
        assertEquals(player,supply.player);assertEquals(player,ground.player);assertEquals(player,supplement.player)
        assertEquals(stocks,supply.stocks);assertEquals(stocks,ground.stocks);assertEquals(stocks,supplement.stocks)
        assertEquals(stocks,production.snapshot(1))
        assertNull(logistics.reservation)
    }

    @Test fun anotherCountryOrNoMembershipIsRejectedBeforeLegacyIdentityConversion() {
        for(country in listOf(null,CountryId(1),CountryId(4),CountryId(42))) {
            rejected(List(4){LogisticsRejection.IDENTITY},player=pilot.copy(country=country))
            val member=session.participants.getValue("pilot").copy(country=country)
            rejected(List(4){LogisticsRejection.IDENTITY},state=session.copy(participants=mapOf("pilot" to member)))
        }
    }

    @Test fun sameEmpireStyleDoesNotPermitUseOfAnotherCountrysOutfits() {
        for(country in listOf(CountryId(1),CountryId(4),CountryId(42))) {
            val foreign=inventory.copy(outfits=inventory.outfits.mapValues {it.value.copy(country=country)},
                warehouses=inventory.warehouses.mapValues {it.value.copy(country=country)})
            rejected(List(4){LogisticsRejection.ACCESS},stocks=foreign)
        }
    }

    @Test fun countryOwnershipOfTheBaseIsCheckedWithoutDisplayCodeConversion() {
        val owners=listOf(Allegiance.Unspecified,Allegiance.Neutral,Allegiance.Pirates)+
            listOf(1,4,42).map {Allegiance.Country(CountryId(it))}
        for(owner in owners) {
            val foreign=inventory.copy(bases=mapOf(1000 to inventory.bases.getValue(1000).copy(owner=owner)))
            rejected(listOf(LogisticsRejection.LOCATION,LogisticsRejection.ACCESS,LogisticsRejection.ACCESS,LogisticsRejection.ACCESS),stocks=foreign)
        }
    }

    @Test fun reorganizationCrewUsesShipStyleWhileKeepingTheActualCountry() {
        val ship=UnitStack(32)
        val original=inventory.outfits.getValue(2).copy(country=CountryId(42),
            units=UnitInventory(mapOf(ship to 1),mapOf(UnitStack(3) to 5)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,ship))))
        val stocks=inventory.copy(outfits=inventory.outfits+(2 to original),
            warehouses=inventory.warehouses+(102 to inventory.warehouses.getValue(102).copy(country=CountryId(42))),nextShipId=2)
        val capacity=Reorganization.capacity(stocks,2,UnitTransfer())
        assertEquals(5,capacity.crew);assertEquals(0,capacity.crewDelta)
        assertEquals(CountryId(42),stocks.outfits.getValue(2).country)
    }
}
