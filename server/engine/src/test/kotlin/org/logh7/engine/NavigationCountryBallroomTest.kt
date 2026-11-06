package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** U-18 capital mansion ballroom (spot kind 14): entered only at the mover's country's current capital. Author: Peppone Choi. */
class NavigationCountryBallroomTest {
    private val rules=SessionRules.load()
    // 42: empire office family displayed as the alliance (native 3). 43: alliance family displayed as the empire (native 2).
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val odin=GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)
    private val heinessen=odin.copy(id=1177,grid=2015,name=Capital.ALLIANCE.baseName)
    private val home=odin.copy(id=1050,name="New capital")
    private val second=odin.copy(id=1051,name="Second")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Empire",2),GalaxySystem(4,2015,4,"Alliance",3)),
        listOf(odin,heinessen,home,second),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2015))
    private val style=ShipStyleId.fromLegacy(Power.EMPIRE)
    private fun lobby(base:Int)=base*10000+GatheringActions.MANSION*200+1
    private fun ballroom(base:Int)=base*10000+GatheringActions.MANSION*200+GatheringActions.BALLROOM+1
    /** Bases 1049/1177 keep their original owners; 1050/1051 belong to [owner]; [capital] is 42's stored capital. */
    private fun stocks(owner:Allegiance=Allegiance.Country(CountryId(42)),capital:Int?=1050,camp:Int=0)=PlanetStocks(1,0,mapOf(
        1049 to PlanetStock(1049,2,0,0,0,0),1177 to PlanetStock(1177,3,0,0,0,0),
        1050 to PlanetStock(1050,owner,camp,0,0,0),1051 to PlanetStock(1051,owner,0,0,0,0)),
        deploymentInitialized=true,strategic=StrategicState(capitals=capital?.let {mapOf(CountryId(42) to it)}.orEmpty()))
    private fun enter(country:CountryId,base:Int,s:PlanetStocks?,pcp:Int=1000,countries:CountryCatalog=catalog,mcp:Int=1000):NavigationResult {
        val grid=galaxy.bases.single {it.id==base}.grid
        val player=Navigator(1,10000,country,grid,pcp,mcp,1000,baseId=base,mode=1,spotId=lobby(base),
            cpRecoveryAt=rules.startSeconds,shipStyle=style)
        val store=object:NavigationStore {var saved=mapOf("host" to player)
            override fun load()=saved;override fun save(players:Map<String,Navigator>){saved=players}}
        val navigation=Navigation(galaxy,store,NavigationPolicy(),configuredCountries=countries)
        val session=SessionSimulation(rules).also {it.join("host",country,false,"h")}.exportState()
        return navigation.apply(NavigationRequest("host",10000,country,"h",1,unitId=player.unitId,
            place=PlaceMove.Spot(ballroom(base),0)),session,s)
    }
    private fun assertEntered(result:NavigationResult,base:Int,message:String) {
        val moved=assertNotNull(result.player,"$message: ${result.rejection}")
        assertEquals(ballroom(base),moved.spotId,message);assertEquals(995,moved.pcp,message)
    }
    private fun assertRefused(result:NavigationResult,message:String) {
        assertNull(result.player,message);assertNotNull(result.rejection,message)
        assertEquals(1000,result.previous?.pcp ?: 1000,"$message: no PCP")
    }

    @Test fun anAddedCountryEntersItsOwnCurrentCapitalBallroomButNotAnOriginalCapital() {
        assertEntered(enter(CountryId(42),1050,stocks()),1050,"42 at its stored capital")
        assertRefused(enter(CountryId(42),1049,stocks()),"the empire's Odin is not 42's capital despite the empire office family")
        assertRefused(enter(CountryId(42),1177,stocks()),"nor the alliance capital despite 42's alliance display code")
    }

    @Test fun aMovedLostRebelOrForeignCapitalAdmitsNoOne() {
        assertEntered(enter(CountryId(42),1051,stocks(capital=1051)),1051,"after moving the capital")
        assertRefused(enter(CountryId(42),1050,stocks(capital=1051)),"the former capital")
        assertRefused(enter(CountryId(42),1050,stocks(owner=Allegiance.Country(CountryId(2)))),"capital taken by another country")
        assertRefused(enter(CountryId(2),1050,stocks(owner=Allegiance.Country(CountryId(2)))),"the occupier is not 42")
        assertRefused(enter(CountryId(42),1050,stocks(camp=1)),"rebel-held capital")
        for(owner in listOf(Allegiance.Neutral,Allegiance.Pirates,Allegiance.Unspecified))
            assertRefused(enter(CountryId(42),1050,stocks(owner=owner)),"$owner owner")
        assertRefused(enter(CountryId(42),1050,stocks(capital=null)),"no stored capital and no original capital for 42")
    }

    @Test fun unregisteredCountriesAndShortPcpAreRefusedWithoutCharge() {
        assertRefused(enter(CountryId(42),1050,stocks(),countries=CountryCatalogLoader.load()),"42 removed from the catalogue")
        // Short PCP falls back to MCP at double cost (existing CpPayment rule); short of both, nothing is charged.
        assertEquals(990,assertNotNull(enter(CountryId(42),1050,stocks(),pcp=4).player).mcp)
        val poor=enter(CountryId(42),1050,stocks(),pcp=4,mcp=9)
        assertNull(poor.player);assertNotNull(poor.rejection)
        assertEquals(4,poor.previous?.pcp ?: 4,"no PCP charged");assertEquals(9,poor.previous?.mcp ?: 9,"no MCP charged")
    }

    @Test fun theOriginalCountriesKeepTheirCapitalBallrooms() {
        assertEntered(enter(CountryId(2),1049,stocks()),1049,"empire at Odin")
        assertEntered(enter(CountryId(3),1177,stocks()),1177,"alliance at Heinessen")
        assertRefused(enter(CountryId(2),1177,stocks()),"empire at Heinessen")
        assertRefused(enter(CountryId(43),1049,stocks()),"43 shares the empire display code but not its capital")
        // Without a stock snapshot the original capital rule is unchanged.
        assertEntered(enter(CountryId(2),1049,null),1049,"empire at Odin without stocks")
    }
}
