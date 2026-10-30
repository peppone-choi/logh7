package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0046c560/0046ded0/00471260/00484280 수신 계약. 작성: 최병호. */
object MessengerMessages {
    data class Status(val peer:MailMessages.Peer,val status:Int)
    data class Link(val close:Boolean,val first:MailMessages.Peer,val second:MailMessages.Peer,
        val phase:Int,val result:Int)
    /** peer는 송신자이며 수신자는 현재 수락된 연결에서 결정한다. */
    data class Text(val id:Int,val peer:MailMessages.Peer,val text:String)
    private fun input(body:ByteArray,opcode:Int)=ByteBuffer.wrap(body).also {
        require(body.size>=2 && (it.short.toInt() and 65535)==opcode)
    }
    private fun packet(opcode:Int,write:ByteBuffer.()->Unit):ByteArray {
        val out=ByteBuffer.allocate(65536);out.write()
        return LoginMessages.sessionMessage(opcode,out.array().copyOf(out.position()))
    }
    fun status(body:ByteArray):Status=with(input(body,0x0f0d)) {
        val peer=with(MailMessages){peer()};val state=get().toInt() and 255
        require(!hasRemaining());Status(peer,state)
    }
    fun status(value:Status)=packet(0x0f0d) {
        require(value.status in 0..255);with(MailMessages){peer(value.peer)};put(value.status.toByte())
    }
    fun link(body:ByteArray):Link=with(input(body,0x0f0e)) {
        val closed=get().toInt() and 255;require(closed in 0..1)
        val first=with(MailMessages){peer()};val second=with(MailMessages){peer()}
        val phase=get().toInt() and 255;val result=get().toInt() and 255
        require(!hasRemaining());Link(closed==1,first,second,phase,result)
    }
    fun link(value:Link)=packet(0x0f0e) {
        require(value.phase in 0..4 && value.result in 0..3)
        put(if(value.close)1 else 0);with(MailMessages){peer(value.first);peer(value.second)}
        put(value.phase.toByte());put(value.result.toByte())
    }
    fun text(body:ByteArray):Text=with(input(body,0x0f0f)) {
        val id=int;val peer=with(MailMessages){peer()};val text=with(MailMessages){text(512,true)}
        require(!hasRemaining());Text(id,peer,text)
    }
    fun text(value:Text)=packet(0x0f0f) {
        putInt(value.id);with(MailMessages){peer(value.peer);text(value.text,512,true)}
    }
    fun directory(values:List<Status>)=packet(0x0f07) {
        // A byte count and native fixed array: never emit 4096 live rows here.
        require(values.size<=100 && values.map {it.peer.id}.distinct().size==values.size)
        put(values.size.toByte())
        values.forEach {require(it.status in 0..255);with(MailMessages){peer(it.peer)};put(it.status.toByte())}
    }
    fun directory(body:ByteArray):List<Status> = with(input(body,0x0f07)) {
        val count=get().toInt() and 255;require(count<=100)
        List(count){Status(with(MailMessages){peer()},get().toInt() and 255)}.also {
            require(!hasRemaining() && it.map {row->row.peer.id}.distinct().size==it.size)
        }
    }
}
