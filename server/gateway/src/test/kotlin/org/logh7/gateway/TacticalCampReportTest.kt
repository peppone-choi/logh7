package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** 032f mode 15: exactly the viewer's battlefield unitCamps, as wire sides. Author: Peppone Choi. */
class TacticalCampReportTest {
    // 42 and 43 both display as 3; only the actual country separates them.
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"other_country","Other","alliance",3,0,"alliance")))}
    private val empire=ShipStyleId("empire")
    private val galaxy=Galaxy(listOf(GalaxySystem(3,10,4,"Here",2),GalaxySystem(4,20,4,"Away",3)),
        listOf(GalaxyBase(100,10,80,0,"Here",0,20f,86400,0,0f,2f),GalaxyBase(200,20,80,0,"Away",0,20f,86400,0,0f,2f)),
        Power.entries.associateWith {10})
    private fun pilot(id:Int,country:Int?,grid:Int=10,base:Int=0,paused:Boolean=true)=Navigator(1,id,country?.let(::CountryId),grid,
        1000,1000,1000,baseId=base,mode=if(base>0)4 else 5,cpPaused=paused,shipStyle=empire)
    private fun person(id:Int)=CharacterMessages.Generate(0,id.toLong(),3,3,0,"P$id","Q",30,1,1,0,List(8){50},0,0,0,20,0,0,"S$id",0)
    private fun fleet(id:Int,country:Int,base:Int,commander:Int,vararg ships:Int)=
        ActiveShipUnits(ships.map {ActiveShipUnit(it,UnitStack(32))}).let {active->OutfitStock(id,CountryId(country),OutfitKind.FLEET,base,
            100+id,commander,UnitInventory(active.quantities()),activeShips=active,shipStyle=empire,
            position=OutfitPosition(galaxy.bases.single {it.id==base}.grid))}
    private val viewer=pilot(10000,42)
    private val navigators=mapOf("viewer" to viewer,"other" to pilot(10001,43),"rebel" to pilot(10003,42),
        "lone" to pilot(10004,null),"away" to pilot(10006,42,grid=20))
    private fun stocks(outfits:List<OutfitStock> = listOf(fleet(1,43,100,10001,1,2),fleet(2,42,100,10003,3),fleet(3,43,200,0,4)))=
        PlanetStocks(1,0,galaxy.bases.associate {it.id to PlanetStock(it.id,2,0,0,0,0)},
            outfits.associateBy {it.id},outfits.associate {it.warehouseId to OutfitWarehouse(it.warehouseId,it.country,OutfitKind.FLEET,it.baseId,it.id,shipStyle=empire)},
            nextShipId=5,social=SocialState(pirates=PirateState(mapOf(1 to PirateFleet(1,10,0,listOf(PirateUnit(900_001,300)))),2),
                coup=CoupState(mapOf(10003 to Plot(10003,CountryId(42),1,revolt=10)),mapOf(2 to CoupActions.LOYAL))))
    private fun snapshot(s:PlanetStocks=stocks(),seconds:Long=250,generation:Long=1)=WorldSnapshot(0,0,
        SessionSnapshot(generation,seconds,0,0,emptyMap(),1,1,null),navigators=navigators,planetStocks=s)
    private val query=OutfitPartyMessages.Query(9,0,TacticalCampMessages.MODE)
    private fun reply(snap:WorldSnapshot=snapshot(),who:Navigator=viewer,generation:Long=1)=
        TacticalCampReport.reply(query,snap,galaxy,who,generation,{person(it)},catalog)
    private fun header(frames:List<ByteArray>):List<String> {
        val b=java.nio.ByteBuffer.wrap(frames.first(),6,frames.first().size-6);b.int;b.int;b.get();b.get();b.get();b.int;b.int
        return List(b.get().toInt() and 255) {b.int;b.get();b.get();val n=b.get().toInt() and 255
            String(CharArray(n) {b.char}).dropLast(if(n>0)1 else 0)}
    }

    @Test fun theReplyIsExactlyTheBattlefieldUnitCampsAsSides() {
        val camps=TacticalBootstrap(snapshot(),galaxy,viewer,catalog) {person(it)}.unitCamps
        val sides=camps.mapValues {TacticalCampReport.side(it.value)}
        assertEquals(mapOf(1 to TacticalCampMessages.Side(0,43,false),2 to TacticalCampMessages.Side(0,43,false),
            3 to TacticalCampMessages.Side(0,42,true),900_001 to TacticalCampMessages.Side(2,0,false),
            1_010_000 to TacticalCampMessages.Side(0,42,false),1_010_001 to TacticalCampMessages.Side(0,43,false),
            1_010_003 to TacticalCampMessages.Side(0,42,true),1_010_004 to TacticalCampMessages.Side(1,10004,false)),sides)
        val expected=TacticalCampMessages.reply(query,10,1,TacticalCampMessages.Side(0,42,false),sides)
            .map {LoginMessages.sessionMessage(0x032f,it)}
        val actual=reply();assertEquals(expected.size,actual.size);actual.zip(expected).forEach {(a,e)->assertContentEquals(e,a)}
        assertEquals(TacticalCampMessages.Side(3,0,false),TacticalCampReport.side(Camp.FEZZAN))
    }

    @Test fun nativeStateReportsStarCoverPerObserverAndUsesActualCombatCamp() {
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},galaxy.bases,galaxy.starts)
        fun position(p:Navigator,y:Float):Navigator {
            val point=TacticalPoint(0f,y)
            return p.copy(motion=TacticalMotion(10,250,TacticalPose(point,0f),0f,0f,listOf(point)))
        }
        val outside=position(viewer,21f)
        val enemy=position(navigators.getValue("other"),19f)
        val ally=position(pilot(10002,42),19f)
        val sample=snapshot().copy(navigators=mapOf("viewer" to outside,"other" to enemy,"ally" to ally))
        val boot=TacticalBootstrap(sample,world,outside,catalog,::person)
        val units=boot.units
        assertTrue(units.single {it.id==ally.unitId}.detected)
        assertFalse(units.single {it.id==enemy.unitId}.detected)
        val packet=TacticsMessages.states(units)
        assertEquals(0,packet[8+47*units.indexOfFirst {it.id==enemy.unitId}+46].toInt())
        val inside=position(viewer,18f)
        val changed=sample.copy(navigators=sample.navigators+("viewer" to inside))
        val hidden=units.filterNot {it.detected}.map {it.id}.toSet()
        assertTrue(boot.detectionChanges(hidden).isEmpty())
        assertFalse(boot.detectionChanges(emptySet()).single {it.id==enemy.unitId}.detected)
        val insideBoot=TacticalBootstrap(changed,world,inside,catalog,::person)
        assertTrue(insideBoot.detectionChanges(hidden).single {it.id==enemy.unitId}.detected)
    }

    @Test fun aFriendlyDefenseHeadquartersRevealsTheTargetUntilItChangesSides() {
        val base=galaxy.bases.first().copy(radius=15f)
        val world=Galaxy(galaxy.systems.map {it.copy(star=0)},listOf(base),galaxy.starts)
        fun position(p:Navigator,y:Float):Navigator {
            val point=TacticalPoint(0f,y)
            return p.copy(motion=TacticalMotion(10,250,TacticalPose(point,0f),0f,0f,listOf(point)))
        }
        val outside=position(viewer,21f)
        val enemy=position(navigators.getValue("other"),19f)
        val original=stocks(emptyList())
        val owned=PlanetStock(base.id,Allegiance.Country(CountryId(42)),0,0,0,0)
        fun detected(owner:PlanetStock):Boolean {
            val sample=snapshot(original.copy(bases=mapOf(base.id to owner)))
                .copy(navigators=mapOf("viewer" to outside,"other" to enemy))
            return TacticalBootstrap(sample,world,outside,catalog,::person).units.single {it.id==enemy.unitId}.detected
        }
        assertTrue(detected(owned))
        assertFalse(detected(owned.copy(camp=1)))
    }

    @Test fun onlyTheViewersCurrentGridAndGenerationInTacticsAreServed() {
        // The away viewer sees its own grid only: the away fleet and itself.
        val away=reply(who=navigators.getValue("away"))
        assertEquals(listOf("2","1010006"),header(away).subList(1,3))
        assertEquals(TacticalCampMessages.reply(query,20,1,TacticalCampMessages.Side(0,42,false),
            mapOf(4 to TacticalCampMessages.Side(0,43,false),1_010_006 to TacticalCampMessages.Side(0,42,false))).size,away.size)
        assertFailsWith<IllegalArgumentException>("strategic viewer") {reply(who=viewer.copy(cpPaused=false))}
        assertFailsWith<IllegalArgumentException>("docked viewer") {reply(who=viewer.copy(baseId=100,mode=4))}
        assertFailsWith<IllegalArgumentException>("stale generation") {reply(generation=2)}
        assertFailsWith<IllegalArgumentException>("stale snapshot") {reply(snap=snapshot(generation=2))}
    }

    @Test fun theStampIgnoresTimeButFollowsOwnershipChanges() {
        val base=header(reply())
        assertEquals(base.subList(7,9),header(reply(snap=snapshot(seconds=999))).subList(7,9),"clock only")
        val captured=stocks(listOf(fleet(1,42,100,10001,1,2),fleet(2,42,100,10003,3),fleet(3,43,200,0,4)))
        assertNotEquals(base.subList(7,9),header(reply(snap=snapshot(captured))).subList(7,9),"fleet 1 changed hands")
        assertEquals((42L shl 3).toString(),base[9],"viewer side code")
    }
}
