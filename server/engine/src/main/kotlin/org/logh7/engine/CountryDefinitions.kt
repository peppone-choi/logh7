package org.logh7.engine

data class CountryCurrency(val key: String, val name: String, val symbol: String, val minorDigits: Int = 0) {
    companion object {
        const val NAME_LIMIT = 12
        const val SYMBOL_LIMIT = 4
    }

    fun validForEditing(): Boolean = key.length <= 64 && key.matches(Regex("[a-z][a-z0-9_]*")) &&
        CountryDefinitionSnapshot.validName(name, NAME_LIMIT) && symbol.length <= SYMBOL_LIMIT &&
        symbol.none { it.isISOControl() || it.isSurrogate() } && minorDigits in 0..9
}

/** Shared definitions exclude session membership, territory and money. Author: Peppone Choi. */
data class CountryDefinitionSnapshot(val revision:Long,val catalog:CountryCatalog,
    val references:Map<String,List<String>>,val familyCurrencies:Map<String,String>,
    val currencies:Map<String,CountryCurrency> = emptyMap()) {
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
    fun currency(country: CountryId): CountryCurrency? {
        val definition = catalog.countries[country.value] ?: return null
        return currencies[definition.currencyOverride ?: familyCurrencies[definition.family]]
    }
    companion object {
        fun validName(value:String,limit:Int)=value.isNotBlank() && value.length<=limit && value.none {it.isISOControl() || it.isSurrogate()}
    }
}

sealed interface CountryDefinitionEdit {
    data class Country(val value:CountryDefinition,val create:Boolean=false,val keepCurrencyOverride:Boolean=false):CountryDefinitionEdit
    data class Family(val value:CountryFamily,val currency:String,val create:Boolean=false,
        val keepCreationStyle:Boolean=false):CountryDefinitionEdit
    data class Calendar(val value:CountryCalendar,val create:Boolean=false):CountryDefinitionEdit
    data class Currency(val value:CountryCurrency,val create:Boolean=false):CountryDefinitionEdit
}
enum class CountryDefinitionOutcome { CHANGED, UNCHANGED, CONFLICT, INVALID }
interface CountryDefinitionStore {
    fun load():CountryDefinitionSnapshot
    fun save(expectedRevision:Long,edit:CountryDefinitionEdit):CountryDefinitionOutcome
}
