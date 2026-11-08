package org.logh7.gateway

import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.OriginalCharacterMessages
import kotlin.test.*

/** Creation and lottery bound to the country chosen in the lobby. Author: Peppone Choi. */
class CharacterCreationCountryTest {
    @TempDir lateinit var dir:Path
    private val empire=ShipStyleId("empire")
    private val alliance=ShipStyleId("alliance")
    // 42: empire family displayed as 3 (alliance) with empire ships. 43: alliance family displayed as 2 with alliance ships.
    private val s42=CharacterCreation.CountrySelection(CountryId(42),3,empire)
    private val s43=CharacterCreation.CountrySelection(CountryId(43),2,alliance)
    private fun decode(bytes:ByteArray)=CharacterMessages.generate(bytes.copyOfRange(4,bytes.size))
    private fun request(power:Int,name:String)=CharacterMessages.Generate(0,0,power,if(power==2)0 else 3,0,name,"Pilot",
        0,0,0,0,List(8){0},0,0,0,0,0,0,"",0)
    private fun create(store:CharacterCreation,account:String,power:Int,selection:CharacterCreation.CountrySelection?,
        owner:String?=null):CharacterMessages.Generate {
        var c=decode(store.accept(account,request(power,"N$account"),selection,owner))
        c=decode(store.accept(account,c.copy(category=1,age=30,birthMonth=1,birthDay=1,face=1000001),selection,owner))
        c=decode(store.accept(account,c.copy(category=2),selection,owner))
        c=decode(store.accept(account,c.copy(category=3,shipName="S$account"),selection,owner))
        return decode(store.accept(account,c.copy(category=4),selection,owner))
    }

    @Test fun addedCountriesAreRegisteredWithTheirCountryAndIndependentStyleAndSurviveRestart() {
        val file=dir.resolve("c.properties")
        val store=CharacterCreation(file)
        val a=create(store,"a",3,s42)
        val b=create(store,"b",2,s43)
        assertEquals(Flagships.forType(2,0).kind,a.shipKind,"42 flies its empire style although it displays as 3")
        assertEquals(Flagships.forType(3,0).kind,b.shipKind)
        val restored=CharacterCreation(file).profiles()
        assertEquals(CharacterProfile(a,CountryId(42),empire),restored.getValue("a"))
        assertEquals(CharacterProfile(b,CountryId(43),alliance),restored.getValue("b"))
        // Replaying the final step is idempotent; a different country on replay is not.
        assertContentEquals(CharacterMessages.generateReply(a),store.accept("a",a,s42))
        assertFailsWith<IllegalArgumentException> {store.accept("a",a,s43.copy(nativePower=3))}
    }

    @Test fun imperialWizardAndLotteryCanUseAnAllianceDisplaySlotAndHull() {
        val selection = s42.copy(creationPower = 2, shipStyle = alliance)
        val file = dir.resolve("creation-preset.properties")
        val initial = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)
        val store = CharacterCreation(file, clock = initial, lotteryWindowMillis = 10)
        val generated = create(store, "captain", 2, selection)
        assertContentEquals(CharacterMessages.generateReply(generated), store.accept("captain",generated,selection))
        assertEquals(CharacterProfile(generated.copy(power = 3),CountryId(42),alliance),store.profiles().getValue("captain"))

        assertEquals(listOf(1001L), store.candidates("hero",selection).map { it.character.id })
        assertFailsWith<IllegalArgumentException> { store.apply("hero",listOf(1002L),selection) }
        store.apply("hero",listOf(1001L),selection)
        val restored = CharacterCreation(file, clock = initial)
        assertEquals(selection,restored.applicationSelection("hero"))
        val settled = CharacterCreation(file, clock = Clock.offset(initial,Duration.ofMillis(20)))
        val won = settled.profiles().getValue("hero")
        assertEquals(CountryId(42),won.country)
        assertEquals(3,won.character.power)
        assertEquals(alliance,won.shipStyle)
        assertEquals("키르히아이스",settled.character("hero")?.surname)
    }

    @Test fun aWrongPowerOrAMidFlowChangeIsRejectedNeverSubstituted() {
        val store=CharacterCreation()
        assertFailsWith<IllegalArgumentException> {store.accept("x",request(2,"Wrong"),s42)}
        val c=decode(store.accept("x",request(3,"Moving"),s42,"conn1"))
        val next=c.copy(category=1,age=30,birthMonth=1,birthDay=1,face=1000001)
        assertFailsWith<IllegalArgumentException>("other country") {store.accept("x",next,s42.copy(country=CountryId(44)),"conn1")}
        assertFailsWith<IllegalArgumentException>("dropped selection") {store.accept("x",next,null,"conn1")}
        assertFailsWith<IllegalArgumentException>("other connection") {store.accept("x",next,s42,"conn2")}
        assertFailsWith<IllegalArgumentException>("restart on 2/3") {store.accept("x",request(3,"Moving"),null,"conn1")}
        assertEquals(1,decode(store.accept("x",next,s42,"conn1")).category)
    }

    @Test fun liveOriginalEditsReachTheLotteryAndOnlyFutureAwards() {
        val file=dir.resolve("edited-candidate.properties")
        val definitions=CharacterCreation.originalDefinitions()
        val published=PublishedOriginalCandidates(definitions)
        val start=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC)
        val selection=s42.copy(creationPower=2,shipStyle=alliance)
        val store=CharacterCreation(file,clock=start,lotteryWindowMillis=10,publishedOriginals=published)
        val id=1001L
        val before=store.candidates("hero",selection).single().character
        assertEquals("키르히아이스",before.surname)
        store.apply("hero",listOf(id),selection)
        val edit=definitions.single {it.id==id}.profile.copy(surname="시험함장",givenName="지크",face=112,rank=10,
            abilities=listOf(91,90,89,88,87,86,85,84))
        assertFalse(published.accepts(id,edit.copy(face=100074)))
        published.publish(listOf(OriginalCandidateEdit(id,2,edit)))
        published.publish(listOf(OriginalCandidateEdit(id,1,edit.copy(rank=20))))
        val listed=store.candidates("other",selection).single().character
        assertEquals(before.copy(surname=edit.surname,givenName=edit.givenName,face=112,rank=10,abilities=edit.abilities),listed)
        val packet=OriginalCharacterMessages.catalogue(store.candidates("other",selection))
        assertTrue(packet.toList().windowed(edit.surname.length*2).contains(edit.surname.toByteArray(Charsets.UTF_16BE).toList()))
        assertFailsWith<IllegalArgumentException> {store.accept("name",request(2,edit.surname),selection)}
        assertEquals(selection,store.applicationSelection("hero"))

        val end=Clock.offset(start,Duration.ofMillis(20))
        val settled=CharacterCreation(file,clock=end,publishedOriginals=published)
        val won=settled.profiles().getValue("hero")
        assertEquals(CountryId(42),won.country)
        assertEquals(alliance,won.shipStyle)
        assertEquals(listed.copy(power=3,generated=false,shipKind=Flagships.forType(3,listed.shipType).kind),won.character)
        published.publish(listOf(OriginalCandidateEdit(id,3,edit.copy(face=26,rank=20))))
        assertEquals(won,CharacterCreation(file,clock=end,publishedOriginals=published).profiles().getValue("hero"))
        assertEquals(before,CharacterCreation().candidates("hero",selection).single().character)
    }

    @Test fun cancelRemovesOnlyTheOwnersUnfinishedCreationAndAllowsAFreshStart() {
        val store=CharacterCreation()
        store.accept("x",request(3,"Owner"),s42,"conn1")
        assertFalse(store.cancelCreation("x","conn2"),"another connection cannot drop it")
        assertTrue(store.cancelCreation("x","conn1"))
        assertFalse(store.cancelCreation("x","conn1"))
        // After cancel the account may start again, here on the original 2/3 path.
        val c=create(store,"x",2,null,"conn2")
        assertEquals(CountryId(2),store.profiles().getValue("x").country)
        assertFalse(store.cancelCreation("x","conn2"),"registered characters are never cancelled")
        assertContentEquals(CharacterMessages.generateReply(c),store.accept("x",c))
    }

    @Test fun theOriginal23PathIsUnchanged() {
        val store=CharacterCreation()
        for(power in 2..3) {
            val c=create(store,"p$power",power,null)
            assertEquals(Flagships.forType(power,0).kind,c.shipKind)
            assertEquals(CharacterProfile.legacy(c),store.profiles().getValue("p$power"))
        }
    }

    @Test fun lotteryApplicationsKeepTheirCountryAcrossRestartAndConvertOnlyAForeignStyle() {
        val file=dir.resolve("lottery.properties")
        val start=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC)
        val empireHero=OriginalCharacterMessages.localCandidates.first {it.character.power==2}
        val allianceHero=OriginalCharacterMessages.localCandidates.first {it.character.power==3}
        val originals=listOf(empireHero,allianceHero)
        val fame=originals.maxOf {it.minimumFame}.let {mapOf("a" to it,"b" to it)}
        val store=CharacterCreation(file,clock=start,lotteryWindowMillis=10,originals=originals,accountFame=fame)
        // Candidates follow the selection's display code only.
        assertEquals(listOf(allianceHero.character.id),store.candidates("a",s42).map {it.character.id})
        assertContentEquals(OriginalCharacterMessages.entryState(empireHero.character.id,false,emptyList()),store.entryState("a",empireHero.character.id,s42))
        assertContentEquals(OriginalCharacterMessages.entryState(allianceHero.character.id,true,emptyList()),store.entryState("a",allianceHero.character.id,s42))
        assertFailsWith<IllegalArgumentException> {store.apply("a",listOf(empireHero.character.id),s42)}
        store.apply("a",listOf(allianceHero.character.id),s42)
        store.apply("b",listOf(empireHero.character.id),s43.copy(shipStyle=empire))
        // Resubmitting for another country (or dropping it) is rejected; the same country may resubmit.
        assertFailsWith<IllegalArgumentException> {store.apply("a",listOf(allianceHero.character.id),null)}
        assertFailsWith<IllegalArgumentException> {store.apply("a",listOf(allianceHero.character.id),s42.copy(country=CountryId(44)))}
        store.apply("a",listOf(allianceHero.character.id),s42)
        val later=CharacterCreation(file,clock=Clock.offset(start,Duration.ofMillis(10)),originals=originals)
        val a=later.profiles().getValue("a")
        assertEquals(CountryId(42),a.country);assertEquals(empire,a.shipStyle)
        assertEquals(3,a.character.power)
        assertEquals(Flagships.forType(2,allianceHero.character.shipType).kind,a.character.shipKind,"alliance hero converted to empire style")
        val b=later.profiles().getValue("b")
        assertEquals(CountryId(43),b.country);assertEquals(empire,b.shipStyle)
        assertEquals(empireHero.character.shipKind,b.character.shipKind,"same style keeps the hero's own model")
        assertEquals(a,CharacterCreation(file).profiles().getValue("a"))
    }

    @Test fun legacyApplicationFilesStillLoadAndResolveOnThe23Path() {
        val file=dir.resolve("legacy.properties")
        val start=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC)
        val hero=OriginalCharacterMessages.localCandidates.first {it.character.power==3}
        java.nio.file.Files.writeString(file,"@deadline=1010\n@application/old=${hero.character.id}\n")
        val later=CharacterCreation(file,clock=Clock.offset(start,Duration.ofMillis(10)),originals=listOf(hero))
        assertEquals(CountryId(3),later.profiles().getValue("old").country)
        assertEquals(hero.character.shipKind,later.profiles().getValue("old").character.shipKind)
    }

    @Test fun aFailedLotterySaveDoesNotKeepAPhantomApplicationOrCountry() {
        val blocked=dir.resolve("blocked")
        val file=blocked.resolve("characters.properties")
        val empireHero=OriginalCharacterMessages.localCandidates.first {it.character.power==2}
        val allianceHero=OriginalCharacterMessages.localCandidates.first {it.character.power==3}
        val store=CharacterCreation(file,originals=listOf(empireHero,allianceHero),
            accountFame=mapOf("a" to maxOf(empireHero.minimumFame,allianceHero.minimumFame)))
        java.nio.file.Files.writeString(blocked,"block the directory")
        assertFailsWith<java.io.IOException> {store.apply("a",listOf(allianceHero.character.id),s42)}
        assertTrue(store.entries("a").isEmpty());assertNull(store.applicationSelection("a"))
        java.nio.file.Files.delete(blocked)
        store.apply("a",listOf(empireHero.character.id),s43)
        assertEquals(s43,store.applicationSelection("a"))
        assertEquals(listOf(empireHero.character.id),CharacterCreation(file,originals=listOf(empireHero,allianceHero),
            accountFame=mapOf("a" to maxOf(empireHero.minimumFame,allianceHero.minimumFame))).candidates("a").map {it.character.id})
    }
}
