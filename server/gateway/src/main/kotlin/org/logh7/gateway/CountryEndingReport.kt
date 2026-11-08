package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.CountryEndingMessages
import org.logh7.protocol.LoginMessages

/**
 * Public per-country session statistics and results (032f mode 14), keyed by actual country ID instead of the
 * native two display rows. No personal settlement, position or private data. Author: Peppone Choi.
 */
internal object CountryEndingReport {
    private fun countries(snapshot:WorldSnapshot,catalog:CountryCatalog):Set<CountryId> =
        snapshot.result?.countries?.keys ?: snapshot.session?.countries?.takeIf {it.isNotEmpty()}
        ?: catalog.countries.values.filterNot(CountryFounding::isFounded).map {CountryId(it.id)}.toSet()
    fun count(snapshot:WorldSnapshot,catalog:CountryCatalog)=countries(snapshot,catalog).size
    fun reply(request:CountryEndingMessages.Request,snapshot:WorldSnapshot?,profiles:Map<String,CharacterProfile>,
        catalog:CountryCatalog,appearances:List<CountryAppearance>,viewerCountry:Int=0,galaxy:Galaxy?=null):List<ByteArray> {
        // The viewer's own actual country is chosen by the caller from the authenticated profile; 0 = none.
        // An empty (no session) reply always carries 0.
        val session=snapshot?.session ?: return wrap(CountryEndingMessages.empty(request))
        val generation=session.generation
        // A stock or result from another generation must never be presented as this session's.
        require(snapshot.planetStocks?.generation?.let {it==generation} != false) { "Stale planet stocks" }
        val result=snapshot.result
        require(result==null || result.end==session.ended) { "Stale session result" }
        val end=result?.end ?: session.ended
        val status=when(end?.reason) {null->1;EndReason.CAPITAL_CAPTURED->3;EndReason.SYSTEMS_REDUCED->4;EndReason.TIME_LIMIT->5}
        val seconds=end?.gameSeconds ?: session.gameSeconds
        // Membership from the current checkpoint; an old navigator never relabels a profile (WorldCharacters).
        val members=WorldCharacters.profiles(profiles,snapshot).values.mapNotNull {it.country}.groupingBy {it}.eachCount()
        // A defeated viewer may inspect its own current forces while other countries keep playing.
        val partial=if(result==null && viewerCountry>0 && CountryId(viewerCountry) in session.eliminated && galaxy!=null)
            SessionResults.statistics(galaxy,snapshot).countries[CountryId(viewerCountry)] else null
        // The session roster is authoritative once populated: removed participants remain, while new
        // definitions do not enter this session until the engine registers them. Both are bounded at 350.
        val rows=countries(snapshot,catalog).sortedBy {it.value}.map {country->
            val registered=country.value in catalog.countries
            val appearance=appearances.firstOrNull {it.countryId==country.value}.takeIf {registered}
            val name=(if(registered)appearance?.name ?: catalog.countries.getValue(country.value).name else null)
                ?.filterNot {it.isISOControl() || it.isSurrogate()}?.trim()?.take(24)?.takeIf {it.isNotBlank()} ?: "국가 #${country.value}"
            val assessed=result?.countries?.get(country)
            val s=assessed?.statistics ?: partial?.takeIf {country.value==viewerCountry} ?: SessionStatistics()
            CountryEndingMessages.Row(country.value,name,appearance?.colors?.primary,members[country] ?: 0,
                grade(assessed?.grade ?: SessionGrade.DEFEAT.takeIf {country in session.eliminated}),flag(assessed?.populationDominance),flag(assessed?.fleetDominance),
                country in session.eliminated,s.planets,s.fortresses,s.population,s.shipUnits,s.groundUnits)
        }
        return wrap(CountryEndingMessages.reply(request,generation,seconds,status,rows,viewerCountry))
    }
    private fun wrap(bodies:List<ByteArray>)=bodies.map {LoginMessages.sessionMessage(0x032f,it)}
    /** Same presentation codes as the native ending report; 255 means not yet decided. */
    private fun grade(value:SessionGrade?)=when(value) {SessionGrade.LIMITED->0;SessionGrade.LOCAL->1;SessionGrade.DECISIVE->2;
        SessionGrade.DEFEAT->3;SessionGrade.UNRESOLVED,null->255}
    private fun flag(value:Boolean?)=when(value){true->1;false->0;null->255}
}
