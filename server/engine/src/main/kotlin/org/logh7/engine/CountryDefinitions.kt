package org.logh7.engine

/** Shared definitions exclude session membership, territory and money. Author: Peppone Choi. */
data class CountryDefinitionSnapshot(val revision:Long,val catalog:CountryCatalog,
    val references:Map<String,List<String>>,val familyCurrencies:Map<String,String>) {
    init {
        require(revision>=0 && catalog.countries.size<=350)
        require(catalog.calendars.values.all {validName(it.name,12)})
        require(catalog.families.values.all {validName(it.name,40)})
        require(catalog.countries.values.all {validName(it.name,24)})
        // Current native players need a supported model pack and their existing display identity.
        for(id in listOf(2,3)) {
            val country=requireNotNull(catalog.countries[id]) {"Missing current playable country"}
            val native=CountryCatalogLoader.load().countries.getValue(id)
            require(country.nativePower==native.nativePower && country.nativeCamp==native.nativeCamp)
            require(catalog.shipStyle(CountryId(id)).value in setOf("empire","alliance"))
        }
    }
    companion object {
        fun validName(value:String,limit:Int)=value.isNotBlank() && value.length<=limit && value.none {it.isISOControl() || it.isSurrogate()}
    }
}

sealed interface CountryDefinitionEdit {
    data class Country(val value:CountryDefinition,val create:Boolean=false):CountryDefinitionEdit
    data class Family(val value:CountryFamily,val currency:String,val create:Boolean=false):CountryDefinitionEdit
    data class Calendar(val value:CountryCalendar,val create:Boolean=false):CountryDefinitionEdit
}
enum class CountryDefinitionOutcome { CHANGED, UNCHANGED, CONFLICT, INVALID }
interface CountryDefinitionStore {
    fun load():CountryDefinitionSnapshot
    fun save(expectedRevision:Long,edit:CountryDefinitionEdit):CountryDefinitionOutcome
}
