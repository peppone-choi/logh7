package org.logh7.engine

import kotlin.test.*

class FacilityDefinitionsTest {
    @Test fun defaultsCoverEveryClientKindWithKoreanNamesAndShortDescriptions() {
        val defaults=FacilityDefinitionDefaults.snapshot()
        assertEquals(39,FacilityDefinitionDefaults.kindCount)
        assertEquals((0..38).toSet(),defaults.kinds.keys)
        assertEquals(0,defaults.revision)
        for(kind in 0..38) {
            val id=FacilityDefinitionSnapshot.facilityId(1049,kind)
            assertNull(defaults.name(id),"defaults keep the server name")
            assertEquals(FacilityDefinitionDefaults.name(kind),defaults.displayName(id))
            assertTrue(defaults.purpose(id).isNotBlank() && defaults.purpose(id).length<=200)
            assertTrue(defaults.usage(id).isNotBlank() && defaults.usage(id).length<=200)
            assertTrue(FacilityDefinitionDefaults.name(kind).length<=24)
        }
        assertEquals("황궁",FacilityDefinitionDefaults.name(22))
        assertEquals("내각부",FacilityDefinitionDefaults.name(38))
    }

    @Test fun overridesWinOverKindDefinitionsAndIdsMatchThePlacesModel() {
        val base=GalaxyBase(1049,2487,0,0,"オーディン",0,1f,0,0,0f,1f)
        val palace=Places.forBase(base,2).first {it.kind==22}
        assertEquals(FacilityDefinitionSnapshot.facilityId(1049,22),palace.id)
        val kindEdit=FacilityDefinitionDefaults.snapshot().applied(FacilityDefinitionEdit.Kind(FacilityKindDefinition(22,"신궁전","황제의 궁전.","소속 판정.")))!!
        assertEquals(1,kindEdit.revision)
        assertEquals("신궁전",kindEdit.name(palace.id))
        val overridden=kindEdit.applied(FacilityDefinitionEdit.Override(FacilityOverride(palace.id,"노이에 상수시",null)))!!
        assertEquals(2,overridden.revision)
        assertEquals("노이에 상수시",overridden.displayName(palace.id))
        assertEquals("황제의 궁전.",overridden.purpose(palace.id),"a null override field inherits the kind")
        assertEquals("신궁전",overridden.name(FacilityDefinitionSnapshot.facilityId(1050,22)),"other bases keep the kind name")
        assertSame(overridden,overridden.applied(FacilityDefinitionEdit.Override(FacilityOverride(palace.id,"노이에 상수시",null))))
        val cleared=overridden.applied(FacilityDefinitionEdit.ClearOverride(palace.id))!!
        assertEquals("신궁전",cleared.name(palace.id))
        assertSame(cleared,cleared.applied(FacilityDefinitionEdit.ClearOverride(palace.id)))
    }

    @Test fun invalidTextAndIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {FacilityKindDefinition(-1,null,"","")}
        assertFailsWith<IllegalArgumentException> {FacilityKindDefinition(3,"가".repeat(25),"","")}
        assertFailsWith<IllegalArgumentException> {FacilityKindDefinition(3,null,"가".repeat(201),"")}
        assertFailsWith<IllegalArgumentException> {FacilityKindDefinition(3," ","","")}
        assertFailsWith<IllegalArgumentException> {FacilityKindDefinition(3,"줄\n바꿈","","")}
        assertFailsWith<IllegalArgumentException> {FacilityOverride(104900,"이름",null)}
        assertFailsWith<IllegalArgumentException> {FacilityOverride(104923,null,null)}
        assertFailsWith<IllegalArgumentException> {FacilityDefinitionEdit.ClearOverride(50)}
    }

    @Test fun publishingNeverMovesBackToAnOlderRevision() {
        val published=PublishedFacilityDefinitions()
        val newer=published.current().applied(FacilityDefinitionEdit.Kind(FacilityKindDefinition(3,"정원","쉼터.","공공.")))!!
        assertTrue(published.publish(newer))
        assertFalse(published.publish(FacilityDefinitionDefaults.snapshot()),"a stale reload is ignored")
        assertEquals("정원",published.current().name(FacilityDefinitionSnapshot.facilityId(1049,3)))
        assertTrue(published.publish(newer),"the same revision may be republished")
        assertEquals(emptyList(),FacilityCommandCatalog.UNCONNECTED.commands(22))
    }
}
