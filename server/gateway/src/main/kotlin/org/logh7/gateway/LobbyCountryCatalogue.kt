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
 * generation) and never fold countries together by native power. Only countries the original client can
 * already create or draw into (2 and 3) are marked selectable; added countries are shown but not offered,
 * and nothing here grants entry, game data or another world's positions. Author: Peppone Choi.
 */
internal object LobbyCountryCatalogue {
    /** Countries the native creation and lottery screens can start today. */
    private val SELECTABLE=setOf(2,3)

    class Source(val generation:Long,val snapshot:WorldSnapshot?,
        val profiles:Map<String,CharacterProfile>,val catalog:CountryCatalog,val revision:Long,
        val appearances:List<CountryAppearance>)

    fun reply(request:LobbyCountryMessages.Request,source:Source):List<ByteArray> {
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
                "",if(country.id in SELECTABLE)LobbyCountryMessages.CREATE or LobbyCountryMessages.LOTTERY else 0)
        }
        val header=LobbyCountryMessages.Header(countries.size,request.offset,rows.size,source.revision,
            snapshot?.session?.gameSeconds ?: 0,source.generation,totalBases)
        return LobbyCountryMessages.reply(request,header,rows).map {LoginMessages.sessionMessage(0x032f,it)}
    }

    fun empty(request:LobbyCountryMessages.Request):List<ByteArray> =
        LobbyCountryMessages.empty(request).map {LoginMessages.sessionMessage(0x032f,it)}
}
