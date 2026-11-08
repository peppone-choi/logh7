package org.logh7.engine

import java.util.UUID
import java.security.MessageDigest
import java.util.HexFormat

/** The definition survives retries. A country's activation belongs to the world checkpoint. Author: Peppone Choi. */
class CountryFoundingRegistry(private val world:UUID,private val definitions:CountryDefinitionStore) {
    private fun key(generation:Long,characterId:Int,account:String):String {
        require(generation>0 && characterId>0 && account.isNotBlank())
        val identity="$world/$generation/$characterId/$account".toByteArray(Charsets.UTF_8)
        val digest=MessageDigest.getInstance("SHA-256").digest(identity)
        return "founded_"+HexFormat.of().formatHex(digest,0,28)
    }

    fun matches(country:CountryId,generation:Long,characterId:Int,account:String):Boolean =
        CountryCatalogLoader.current().countries[country.value]?.key==key(generation,characterId,account)

    fun reserve(generation:Long,characterId:Int,account:String,name:String,style:ShipStyleId):CountryDefinition? {
        require(CountryDefinitionSnapshot.validName(name,24))
        val key=key(generation,characterId,account)
        repeat(4) {
            val snapshot=definitions.load()
            val countries=snapshot.catalog.countries
            countries.values.singleOrNull {it.key==key}?.let {
                CountryCatalogLoader.publish(snapshot)
                return it.takeIf {row->row.nativePower==style.nativePower && row.nativeCamp==0 &&
                    snapshot.catalog.shipStyle(CountryId(row.id))==style &&
                    OrganizationOffices.system(CountryId(row.id),snapshot.catalog)==style.nativePower}
            }
            if(countries.size>=350)return null
            val family=snapshot.catalog.families[style.value] ?: return null
            if(family.creationPower!=style.nativePower || family.offices!=style.value ||
                style.value !in snapshot.references["assets"].orEmpty())return null
            val id=(100..65535).firstOrNull {it !in countries} ?: return null
            val row=CountryDefinition(id,key,name,family.key,style.nativePower,0,style.value,
                shipStyleOverride=style.value)
            when(definitions.save(snapshot.revision,CountryDefinitionEdit.Country(row,create=true))) {
                CountryDefinitionOutcome.CHANGED -> {
                    val saved=definitions.load()
                    CountryCatalogLoader.publish(saved)
                    return saved.catalog.countries[id]?.takeIf {it.key==key}
                }
                CountryDefinitionOutcome.CONFLICT -> Unit
                else -> return null
            }
        }
        return null
    }
}

object CountryFounding {
    const val COMMAND=251
    fun isFounded(definition:CountryDefinition?)=definition?.key?.startsWith("founded_")==true

    fun activeCapitals(stocks:PlanetStocks?,catalog:CountryCatalog):Map<CountryId,Int> =
        stocks?.strategic?.capitals.orEmpty().filter { (country,base) ->
            isFounded(catalog.countries[country.value]) &&
                stocks?.bases?.get(base)?.let {it.country==country && it.camp==0}==true
        }

    internal fun candidate(galaxy:Galaxy,p:Navigator,stocks:PlanetStocks):PlanetStock? {
        if(galaxy.unaffiliatedStart==null || p.country!=null || p.mode!=4 || p.cpPaused || p.navigationPending)return null
        val base=stocks.bases[p.baseId] ?: return null
        if(base.owner!=Allegiance.Independent(p.characterId) || base.baseId in stocks.strategic.capitals.values ||
            galaxy.bases.none {it.id==base.baseId && it.grid==p.grid && Capital.of(it)==null})return null
        return base
    }

    internal fun establish(galaxy:Galaxy,p:Navigator,stocks:PlanetStocks,base:PlanetStock,definition:CountryDefinition):StrategyActionResult {
        val country=CountryId(definition.id)
        require(country !in stocks.strategic.capitals && stocks.bases.values.none {it.country==country})
        val person=stocks.strategic.people[p.characterId] ?: StrategicPerson()
        val empire=definition.nativePower==2
        val founded=stocks.copy(
            bases=stocks.bases.mapValues {(_,stock)->
                if(stock.owner==base.owner)stock.copy(owner=Allegiance.Country(country)) else stock
            },
            strategic=stocks.strategic.copy(capitals=stocks.strategic.capitals+(country to base.baseId),
                people=stocks.strategic.people+(p.characterId to person.copy(rank=if(empire)0 else 1))))
        val appointed=OrganizationalOfficePolicy(galaxy).set(founded,OfficeHolding(if(empire)1 else 132,base.baseId,p.characterId,country))
        return StrategyActionResult(p.copy(country=country),appointed,changed=true)
    }
}
