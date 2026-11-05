package org.logh7.engine

import kotlin.test.*
import org.junit.jupiter.api.Test

class CountryCatalogTest {
    private val catalog=CountryCatalogLoader.load()
    @Test fun calendarFollowsFamilyInsteadOfYearOrNativeAppearance() {
        assertEquals(486,catalog.year(2,795))
        assertEquals(491,catalog.year(2,800))
        assertEquals(800,catalog.year(3,800))
        val newCountry=CountryDefinition(100,"test_new_empire","시험 신제국","new_empire",2,0,"empire")
        val extended=CountryCatalog(catalog.calendars.values.toList(),catalog.families.values.toList(),catalog.countries.values.toList()+newCountry)
        assertEquals(1,extended.year(100,799))
        assertEquals(2,extended.year(100,800))
        assertEquals(491,extended.year(2,800))
        assertNull(extended.calendar(0)) // Unaffiliated is a membership state, not a sovereign country.
    }
    @Test fun unknownFamilyAndDuplicateIdentityAreRejected() {
        val countries=catalog.countries.values.toList()
        fun changed(values:List<CountryDefinition>)=CountryCatalog(catalog.calendars.values.toList(),catalog.families.values.toList(),values)
        assertFailsWith<IllegalArgumentException> {changed(countries+countries.first())}
        assertFailsWith<IllegalArgumentException> {changed(countries+countries.first().copy(id=100,key="unknown",family="missing"))}
    }
    @Test fun publishedDefinitionMapsCannotBeMutated() {
        assertFailsWith<UnsupportedOperationException> {(catalog.countries as MutableMap).clear()}
        assertEquals(setOf(2,3),catalog.countries.keys)
    }
}
