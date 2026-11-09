package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class OriginalCandidateProfileTest {
    @Test fun aPublishedStartingOfficeIsUniqueAndCannotBeClearedOrReassigned() {
        val office=OriginalStartingOffice.REINHARD_FLEET_2
        val profile=OriginalCandidateProfile("Lohengramm","Reinhard",112,5,List(8){50},startingOffice=office)
        val empire=Power.EMPIRE.country
        val published=PublishedOriginalCandidates()
        val first=OriginalCandidateEdit(1003,1,profile,empire)
        val second=first.copy(id=1004)
        assertFalse(published.accepts(1001,profile,empire))
        assertFalse(published.accepts(first.id,profile,CountryId(42)))
        assertFalse(published.accepts(first.id,profile.copy(rank=6),empire))
        assertFailsWith<IllegalArgumentException> {published.publish(listOf(first,second))}
        assertFailsWith<IllegalArgumentException> {published.publish(listOf(first,first))}
        assertTrue(published.current().isEmpty())
        published.publish(listOf(first))
        assertFalse(published.accepts(second.id,profile,empire))
        val cleared=first.copy(revision=2,profile=profile.copy(startingOffice=null))
        assertFailsWith<IllegalArgumentException> {published.publish(listOf(cleared))}
        assertFailsWith<IllegalArgumentException> {published.publish(listOf(second))}
        assertEquals(mapOf(first.id to first),published.current())
        val revised=first.copy(revision=2,profile=profile.copy(abilities=List(8){51}))
        published.publish(listOf(revised))
        published.publish(listOf(cleared.copy(revision=1)))
        assertEquals(revised,published.current()[first.id])
    }
}
