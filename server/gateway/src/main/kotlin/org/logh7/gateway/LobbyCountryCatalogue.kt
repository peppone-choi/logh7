package org.logh7.gateway

import org.logh7.engine.CountryAppearance
import org.logh7.engine.CountryCatalog
import org.logh7.engine.CountryId
import org.logh7.engine.WorldSnapshot
import org.logh7.protocol.LobbyCountryMessages
import org.logh7.protocol.LoginMessages

/**
 * One world's lobby country catalogue (protocol mode 7). Every definition is listed by stable country id;
 * counts use the actual [CountryId] (a registered profile, or the same character's navigator in the current
 * generation) and never fold countries together by native power. A country is selectable only with its
 * own configured start and supported native display, ship style and office family; eliminated countries are excluded,
 * and nothing here grants entry, game data or another world's positions. Author: Peppone Choi.
 */
internal object LobbyCountryCatalogue {
    private val defaultStarts=setOf(CountryId(2),CountryId(3))
    fun selection(id:Int,catalog:CountryCatalog,starts:Set<CountryId> = defaultStarts):CharacterCreation.CountrySelection? {
        if(id<=0 || CountryId(id) !in starts)return null
        val country=catalog.countries[id] ?: return null
        val style=catalog.shipStyle(CountryId(id))
        val creationPower=catalog.families.getValue(country.family).creationPower ?: return null
        if(country.nativePower !in 2..3 || country.nativeCamp!=0 || style.value !in setOf("empire","alliance") ||
            org.logh7.engine.OrganizationOffices.system(CountryId(id),catalog) !in 2..3)return null
        return CharacterCreation.CountrySelection(CountryId(id),country.nativePower,style,creationPower)
    }

    class Source(val generation:Long,val snapshot:WorldSnapshot?,
        val profiles:Map<String,CharacterProfile>,val catalog:CountryCatalog,val revision:Long,
        val appearances:List<CountryAppearance>,val starts:Set<CountryId> = defaultStarts)

    fun reply(request:LobbyCountryMessages.Request,source:Source):List<ByteArray> {
        if (request.query.base == LobbyCountryMessages.EMBLEM_PROBE) {
            return listOf(LoginMessages.sessionMessage(0x032f, LobbyCountryMessages.emblemCapability(request)))
        }
        val countries=source.catalog.countries.values.sortedBy {it.id}.take(LobbyCountryMessages.MAX_OFFSET)
        val page=countries.drop(request.offset).take(LobbyCountryMessages.PAGE)
        val snapshot=source.snapshot?.takeIf {it.session?.generation==null || it.session?.generation==source.generation}
        val members=WorldCharacters.profiles(source.profiles,snapshot).values.mapNotNull {it.country}.groupingBy {it}.eachCount()
        val bases=snapshot?.planetStocks?.bases?.values.orEmpty().filter {it.camp==0}.mapNotNull {it.country}.groupingBy {it}.eachCount()
        val colors=source.appearances.associateBy {it.countryId}
        val totalBases=countries.sumOf {bases[CountryId(it.id)] ?: 0}.coerceAtMost(65535)
        val rows=page.map {country->
            LobbyCountryMessages.Row(country.id,country.name,colors[country.id]?.colors?.primary,
                members[CountryId(country.id)] ?: 0,bases[CountryId(country.id)] ?: 0,country.nativePower,country.nativeCamp,
                // The family key is withheld until its asset reference is confirmed (no arbitrary truncation).
                "",if(selection(country.id,source.catalog,source.starts-snapshot?.session?.eliminated.orEmpty().keys)!=null)
                    LobbyCountryMessages.CREATE or LobbyCountryMessages.LOTTERY else 0)
        }
        val header=LobbyCountryMessages.Header(countries.size,request.offset,rows.size,source.revision,
            snapshot?.session?.gameSeconds ?: 0,source.generation,totalBases)
        return LobbyCountryMessages.reply(request,header,rows).map {LoginMessages.sessionMessage(0x032f,it)}
    }

    fun empty(request:LobbyCountryMessages.Request):List<ByteArray> =
        LobbyCountryMessages.empty(request).map {LoginMessages.sessionMessage(0x032f,it)}
}
