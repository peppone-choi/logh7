package org.logh7.engine

import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.Collections
import org.logh7.engine.strategy.StrategyCatalogLoader

/** Definitions are separate from runtime membership and native display codes. Author: Peppone Choi (peppone-choi). */
data class CountryCalendar(val key:String,val name:String,val yearOffset:Int)
data class CountryFamily(val key:String,val name:String,val calendar:String,val commands:String,
    val ranks:String,val offices:String,val peerage:String,val shipStyle:String,val creationStyle:String?=null) {
    init { require(creationStyle == null || creationStyle in setOf("empire", "alliance")) }
    val creationPower: Int? get() = when (creationStyle ?: offices) {
        "empire" -> 2
        "alliance" -> 3
        else -> null
    }
}
data class CountryDefinition(val id:Int,val key:String,val name:String,val family:String,
    val nativePower:Int,val nativeCamp:Int,val assets:String,val calendarOverride:String?=null,val shipStyleOverride:String?=null)

class CountryCatalog(calendars:List<CountryCalendar>,families:List<CountryFamily>,countries:List<CountryDefinition>) {
    val calendars=Collections.unmodifiableMap(calendars.associateBy {it.key})
    val families=Collections.unmodifiableMap(families.associateBy {it.key})
    val countries=Collections.unmodifiableMap(countries.associateBy {it.id})
    init {
        val key=Regex("[a-z][a-z0-9_]*")
        require(calendars.isNotEmpty() && this.calendars.size==calendars.size)
        require(families.isNotEmpty() && this.families.size==families.size)
        require(countries.isNotEmpty() && this.countries.size==countries.size && countries.map {it.key}.distinct().size==countries.size)
        require(calendars.all {key.matches(it.key) && it.name.isNotBlank() && it.yearOffset in -10000..10000})
        require(families.all { family -> key.matches(family.key) && family.name.isNotBlank() &&
            family.calendar in this.calendars && listOf(family.commands,family.ranks,family.offices,family.peerage,family.shipStyle).all(key::matches)})
        require(countries.all {it.id>0 && key.matches(it.key) && it.name.isNotBlank() && it.family in this.families &&
            it.nativePower in 0..4 && it.nativeCamp in 0..1 && key.matches(it.assets) &&
            (it.calendarOverride==null || it.calendarOverride in this.calendars) &&
            (it.shipStyleOverride==null || key.matches(it.shipStyleOverride))})
    }
    fun calendar(countryId:Int):CountryCalendar?=countries[countryId]?.let {calendars.getValue(it.calendarOverride ?: families.getValue(it.family).calendar)}
    fun shipStyle(country:CountryId):ShipStyleId= requireNotNull(countries[country.value]) {"Unknown country definition"}.let {
        ShipStyleId(it.shipStyleOverride ?: families.getValue(it.family).shipStyle)}
    fun year(countryId:Int,spaceYear:Int):Int?=calendar(countryId)?.let {(spaceYear+it.yearOffset).also {year->require(year>0)}}
    /** Display projection only; ownership and authority compare actual country IDs. */
    fun nativePower(owner:Allegiance):Int=when(owner) {
        Allegiance.Unspecified->0
        Allegiance.Neutral->1
        Allegiance.Pirates->4
        is Allegiance.Country->requireNotNull(countries[owner.id.value]) {"Unknown country definition"}.nativePower
    }
}

object CountryCatalogLoader {
    private val bundled by lazy {load()}
    private val published=java.util.concurrent.atomic.AtomicReference<CountryDefinitionSnapshot?>()
    fun snapshot():CountryDefinitionSnapshot?=published.get()
    fun current():CountryCatalog=published.get()?.catalog ?: bundled
    /** A database read begun before a save must not overwrite its newer complete snapshot. */
    fun publish(value:CountryDefinitionSnapshot) {
        while(true) {
            val old=published.get()
            if(old!=null && old.revision>=value.revision)return
            if(published.compareAndSet(old,value))return
        }
    }
    fun load():CountryCatalog {
        fun resource(name:String)=checkNotNull(javaClass.getResourceAsStream("/countries/$name")) {"Missing country definitions: $name"}
        return resource("calendars.csv").use {calendars->resource("families.csv").use {families->
            resource("countries.csv").use {countries->read(calendars,families,countries)}}}
    }
    fun read(calendars:InputStream,families:InputStream,countries:InputStream):CountryCatalog {
        fun rows(input:InputStream,header:String):List<List<String>> {
            val text=java.io.InputStreamReader(input,Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)).readText().removePrefix("\uFEFF")
            val rows=StrategyCatalogLoader.csvRows(text)
            val columns=header.split(',')
            require(rows.firstOrNull()==columns) {"Invalid country definition columns"}
            return rows.drop(1).onEach {require(it.size==columns.size && it.all(String::isNotBlank)) {"Invalid country definition row"}}
        }
        return CountryCatalog(
            rows(calendars,"key,name,year_offset").map {CountryCalendar(it[0],it[1],it[2].toInt())},
            rows(families,"key,name,calendar,commands,ranks,offices,peerage,ship_style").map {CountryFamily(it[0],it[1],it[2],it[3],it[4],it[5],it[6],it[7])},
            rows(countries,"id,key,name,family,native_power,native_camp,assets").map {CountryDefinition(it[0].toInt(),it[1],it[2],it[3],it[4].toInt(),it[5].toInt(),it[6])})
    }
}
