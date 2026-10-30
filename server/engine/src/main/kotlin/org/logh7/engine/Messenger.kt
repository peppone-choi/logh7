package org.logh7.engine

/** 매뉴얼 W p.17의 1:1 호출/수락. 온라인 연결만 보관한다. 작성: 최병호. */
class Messenger(private val generation:Long,private val currentGeneration:()->Long={generation},
    private val capacity:Int=4096) {
    data class Endpoint(val account:String,val character:Int,val connection:String) {
        init { require(account.isNotBlank() && character>0 && connection.isNotBlank()) }
    }
    enum class Reason { REPLACED, CONNECTED, DISCONNECTED, OFFLINE }
    sealed interface Event {
        data class Calling(val from:Endpoint,val to:Endpoint):Event
        data class Cancelled(val from:Endpoint,val to:Endpoint,val reason:Reason):Event
        data class Connected(val first:Endpoint,val second:Endpoint):Event
        data class Disconnected(val first:Endpoint,val second:Endpoint,val reason:Reason):Event
        data class Message(val from:Endpoint,val to:Endpoint,val text:String):Event
    }
    private val online=linkedMapOf<Int,Endpoint>()
    private val outgoing=linkedMapOf<Int,Int>()
    private val partners=mutableMapOf<Int,Int>()
    init {require(generation>0 && capacity in 2..4096)}
    private fun active() {check(generation==currentGeneration()) {"Stale messenger generation"}}
    private fun owned(endpoint:Endpoint) {active();require(online[endpoint.character]==endpoint) {"Stale messenger connection"}}
    private fun cancel(caller:Int,reason:Reason,events:MutableList<Event>) {
        val target=outgoing.remove(caller) ?: return
        events+=Event.Cancelled(online.getValue(caller),online.getValue(target),reason)
    }
    private fun unlink(character:Int,reason:Reason,events:MutableList<Event>) {
        val peer=partners.remove(character) ?: return
        check(partners.remove(peer)==character)
        events+=Event.Disconnected(online.getValue(character),online.getValue(peer),reason)
    }
    /** 기존 인물/계정 임대를 먼저 끝낸다. 늦은 옛 disconnect는 새 연결을 지우지 못한다. */
    @Synchronized fun online(endpoint:Endpoint):List<Event> {
        active()
        if(online[endpoint.character]==endpoint)return emptyList()
        require(online.values.none {it.connection==endpoint.connection})
        val previous=online.values.filter {it.character==endpoint.character || it.account==endpoint.account}
        require(online.size-previous.size<capacity) {"Messenger capacity exceeded"}
        val events=mutableListOf<Event>()
        for(peer in previous)events+=offline(peer)
        online[endpoint.character]=endpoint
        return events
    }
    @Synchronized fun offline(endpoint:Endpoint):List<Event> {
        active()
        if(online[endpoint.character]!=endpoint)return emptyList()
        val events=mutableListOf<Event>()
        unlink(endpoint.character,Reason.OFFLINE,events)
        outgoing.filter {it.key==endpoint.character || it.value==endpoint.character}.keys.toList()
            .forEach {cancel(it,Reason.OFFLINE,events)}
        online.remove(endpoint.character)
        return events
    }
    @Synchronized fun directory(endpoint:Endpoint):List<Endpoint> {
        owned(endpoint);return online.values.filter {it!=endpoint}.toList()
    }
    @Synchronized fun callerList(endpoint:Endpoint):List<Endpoint> {
        owned(endpoint);return outgoing.filterValues {it==endpoint.character}.keys.map(online::getValue)
    }
    @Synchronized fun partner(endpoint:Endpoint):Endpoint? {
        owned(endpoint);return partners[endpoint.character]?.let(online::getValue)
    }
    /** 기존 대화와 본인의 이전 호출을 종료한 뒤 한 명에게만 호출한다. */
    @Synchronized fun call(endpoint:Endpoint,target:Int):List<Event> {
        owned(endpoint)
        require(target!=endpoint.character)
        val recipient=requireNotNull(online[target]) {"Messenger recipient offline"}
        if(outgoing[endpoint.character]==target)return emptyList()
        val events=mutableListOf<Event>()
        unlink(endpoint.character,Reason.REPLACED,events)
        cancel(endpoint.character,Reason.REPLACED,events)
        outgoing[endpoint.character]=target
        events+=Event.Calling(endpoint,recipient)
        return events
    }
    /** 수신자가 현재 호출자 중 하나를 골라야 연결된다. 양쪽의 다른 호출은 모두 취소한다. */
    @Synchronized fun accept(endpoint:Endpoint,caller:Int):List<Event> {
        owned(endpoint)
        require(outgoing[caller]==endpoint.character) {"No current messenger call"}
        val first=online.getValue(caller)
        val events=mutableListOf<Event>()
        unlink(endpoint.character,Reason.REPLACED,events)
        unlink(caller,Reason.REPLACED,events)
        outgoing.remove(caller)
        outgoing.filter {it.key==caller || it.value==caller || it.key==endpoint.character || it.value==endpoint.character}
            .keys.toList().forEach {cancel(it,Reason.CONNECTED,events)}
        partners[caller]=endpoint.character;partners[endpoint.character]=caller
        events+=Event.Connected(first,endpoint)
        return events
    }
    @Synchronized fun disconnect(endpoint:Endpoint):List<Event> {
        owned(endpoint)
        val events=mutableListOf<Event>()
        unlink(endpoint.character,Reason.DISCONNECTED,events)
        cancel(endpoint.character,Reason.DISCONNECTED,events)
        return events
    }
    /** 주소록/스폿과 독립된 연결 상대에게만 전달. 로그/오프라인 큐는 만들지 않는다. */
    @Synchronized fun send(endpoint:Endpoint,text:String):Event.Message {
        owned(endpoint)
        require(text.isNotBlank() && text.length<=511 && text.none {it.isSurrogate() ||
            it=='\u0000' || (it<' ' && it !in "\t\r\n") || it=='\u007f'}) {"Invalid messenger text"}
        val recipient=checkNotNull(partners[endpoint.character]?.let(online::getValue)) {"Messenger not connected"}
        return Event.Message(endpoint,recipient,text)
    }
}
