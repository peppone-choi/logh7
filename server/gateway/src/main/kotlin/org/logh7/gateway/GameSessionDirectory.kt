package org.logh7.gateway

import org.logh7.protocol.LobbyMessages
import org.logh7.protocol.SessionEndingMessages

/** One process may host several worlds; only their lobby descriptions and tickets are shared. */
class GameSessionDirectory {
    // A full charged-character list, including ending records, must fit one native encrypted frame.
    companion object { const val MAX_SESSIONS=128 }
    data class Entry(val id:Int,val name:String,val address:String,val port:Int,val bindHosts:Set<String>,
        val generation:()->Long,val available:()->Boolean,
        val information:()->SessionEndingMessages.Session,
        val character:(String)->LobbyMessages.ChargedSession?,
        /** Lobby country catalogue (mode 7) of this world; display-only, no positions or personal data. */
        val countries:((org.logh7.protocol.LobbyCountryMessages.Request)->List<ByteArray>?)?=null,
        val endings:((org.logh7.protocol.CountryEndingMessages.Request,String)->List<ByteArray>?)?=null) {
        init {
            require(id in 1..65535 && port in 1..65535 && name.length in 1..12 &&
                name.none {it.isISOControl() || it.isSurrogate()} && bindHosts.isNotEmpty())
            val octets=address.split('.')
            require(octets.size==4 && octets.all {it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255})
        }
    }
    data class Target(val id:Int,val address:String,val port:Int,val generation:Long)
    val tickets=SessionTickets()
    private val entries=mutableMapOf<Int,Entry>()
    private var accountSource:Map<String,String>?=null
    @Synchronized fun register(entry:Entry,accounts:Map<String,String> = emptyMap()):AutoCloseable {
        require(entries.size<MAX_SESSIONS && entry.id !in entries) {"Duplicate session or native session-list capacity exceeded"}
        require(entries.isEmpty() || accountSource===accounts) {"Sessions in one lobby must share the same account store"}
        accountSource=accounts
        entries[entry.id]=entry
        return AutoCloseable {synchronized(this){
            if(entries[entry.id]===entry)entries.remove(entry.id)
            if(entries.isEmpty())accountSource=null
        }}
    }
    @Synchronized private fun snapshot()=entries.toSortedMap().values.toList()
    fun sessions():List<SessionEndingMessages.Session> = snapshot().filter {it.available()}.map {entry ->
        entry.information().also {require(it.id==entry.id && it.name==entry.name)}
    }
    fun characters(account:String):List<LobbyMessages.ChargedSession> = snapshot().mapNotNull {entry ->
        entry.character(account)?.also {val info=checkNotNull(it.information);require(info.id==entry.id && info.name==entry.name)}
    }
    /** The selected world's catalogue frames, or null when that world is unknown or not offered now. */
    fun countries(request:org.logh7.protocol.LobbyCountryMessages.Request):List<ByteArray>? {
        val entry=snapshot().firstOrNull {it.id==request.sessionId} ?: return null
        if(!entry.available())return null
        return entry.countries?.invoke(request)
    }
    fun target(id:Int,bindHost:String?):Target? {
        val entry=snapshot().firstOrNull {it.id==id} ?: return null
        if(!entry.available())return null
        val address=if(bindHost==null || bindHost=="127.0.0.1")entry.address
            else if(bindHost in entry.bindHosts)bindHost else return null
        return Target(id,address,entry.port,entry.generation())
    }
    /** Reading a finished world's results never issues a playable ticket or makes the world selectable. */
    fun endings(request:org.logh7.protocol.CountryEndingMessages.Request,account:String):List<ByteArray>? =
        snapshot().firstOrNull {it.id==request.session}?.endings?.invoke(request,account)
}
