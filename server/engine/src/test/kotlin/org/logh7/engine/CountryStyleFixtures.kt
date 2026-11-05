package org.logh7.engine

internal fun countriesWithSwappedShipStyles():CountryCatalog {
    val source=CountryCatalogLoader.load()
    return CountryCatalog(source.calendars.values.toList(),source.families.values.map {
        it.copy(shipStyle=when(it.shipStyle) {"empire"->"alliance";"alliance"->"empire";else->it.shipStyle})
    },source.countries.values.toList())
}
