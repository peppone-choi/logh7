package org.logh7.protocol

import java.nio.ByteBuffer

/** CD 0045aca0 / 00472e40 InformationMail, 00482620 address list. 작성: 최병호. */
object MailMessages {
    data class Peer(val id:Int,val name:String="",val origin:Int=0,val rank:Int=0,val title:String="",
        val extra:List<Int> = List(3){0}) {
        val personal:Boolean get()=extra.all {it==0}
    }
    data class Query(val owner:Peer,val all:Boolean,val inbox:Boolean)
    data class Item(val id:Int,val reference:Int=-1,val read:Boolean=false,val sender:Peer,val recipient:Peer,
        val seconds:Long,val subject:String,val text:String)
    data class Change(val owner:Peer,val inbox:Boolean,val id:Int)
    data class Exchange(val first:Peer,val second:Peer,val phase:Int,val result:Int)
    private fun ByteBuffer.u8()=get().toInt() and 255
    private fun ByteBuffer.u16()=short.toInt() and 65535
    private fun ByteBuffer.flag()=u8().also {require(it in 0..1)}==1
    private fun ByteBuffer.text(maximum:Int,wideLength:Boolean=false):String {
        val length=if(wideLength)u16() else u8()
        require(length in 0..maximum && remaining()>=length*2)
        if(length==0)return ""
        val units=CharArray(length){char}
        require(units.last()=='\u0000' && units.dropLast(1).none {it=='\u0000' || it.isSurrogate()})
        return units.concatToString(0,length-1)
    }
    private fun ByteBuffer.peer():Peer {
        val id=int;val name=text(13);val origin=u16();val rank=u16();val title=text(16)
        repeat(u8().also {require(it<=1)}) {
            int;u8();u8()
            repeat(u8().also {require(it<=1)}) {int;u16();u16();text(13)}
        }
        repeat(u8().also {require(it<=4)}) {u16();int;u16();u16();text(13)}
        return Peer(id,name,origin,rank,title,List(3){int})
    }
    private fun input(bytes:ByteArray,opcode:Int)=ByteBuffer.wrap(bytes).also {
        require(bytes.size>=2 && it.u16()==opcode)
    }
    fun query(bytes:ByteArray):Query=with(input(bytes,0x0f08)) {
        Query(peer(),flag(),flag()).also {require(!hasRemaining())}
    }
    fun item(bytes:ByteArray,opcode:Int=0x0f10):Item=with(input(bytes,opcode)) {
        Item(int,int,flag(),peer(),peer(),int.toLong() and 0xffffffffL,text(128),text(512,true))
            .also {require(!hasRemaining())}
    }
    fun change(bytes:ByteArray,opcode:Int):Change=with(input(bytes,opcode)) {
        require(opcode in listOf(0x0f11,0x0f12))
        Change(peer(),flag(),int).also {require(it.id>0 && !hasRemaining())}
    }
    fun exchange(bytes:ByteArray):Exchange=with(input(bytes,0x0f0b)) {
        Exchange(peer(),peer(),u8(),u8()).also {require(it.phase in 1..4 && it.result in 0..3 && !hasRemaining())}
    }
    fun deleteAddress(bytes:ByteArray):Peer=with(input(bytes,0x0f0c)) {peer().also {require(!hasRemaining())}}
    private fun ByteBuffer.text(value:String,maximum:Int,wideLength:Boolean=false) {
        require(value.length<maximum && value.none {it=='\u0000' || it.isSurrogate()})
        val count=if(value.isEmpty())0 else value.length+1
        if(wideLength)putShort(count.toShort()) else put(count.toByte())
        if(count>0)(value+'\u0000').forEach(::putChar)
    }
    private fun ByteBuffer.peer(peer:Peer) {
        require(peer.id>0 && peer.origin in 0..65535 && peer.rank in 0..65535 && peer.extra.size==3)
        putInt(peer.id);text(peer.name,13);putShort(peer.origin.toShort());putShort(peer.rank.toShort());text(peer.title,16)
        put(0);put(0);peer.extra.forEach(::putInt)
    }
    private fun packet(opcode:Int,write:ByteBuffer.()->Unit):ByteArray {
        val out=ByteBuffer.allocate(32_000);out.write()
        return LoginMessages.sessionMessage(opcode,out.array().copyOf(out.position()))
    }
    fun addresses(peers:List<Peer>):ByteArray {
        require(peers.size<=100 && peers.map {it.id}.distinct().size==peers.size)
        return packet(0x0f05){put(peers.size.toByte());peers.forEach {peer(it)}}
    }
    fun begin(query:Query)=packet(0x0f08){peer(query.owner);put(if(query.all)1 else 0);put(if(query.inbox)1 else 0)}
    fun end()=LoginMessages.sessionMessage(0x0f09,byteArrayOf(0))
    fun item(item:Item,opcode:Int=0x0f0a)=packet(opcode) {
        require(opcode in listOf(0x0f0a,0x0f10) && item.seconds in 0..0xffffffffL)
        putInt(item.id);putInt(item.reference);put(if(item.read)1 else 0);peer(item.sender);peer(item.recipient)
        putInt(item.seconds.toInt());text(item.subject,128);text(item.text,512,true)
    }
    fun change(change:Change,opcode:Int)=packet(opcode) {
        require(opcode in listOf(0x0f11,0x0f12));peer(change.owner);put(if(change.inbox)1 else 0);putInt(change.id)
    }
    fun exchange(exchange:Exchange)=packet(0x0f0b) {
        peer(exchange.first);peer(exchange.second);put(exchange.phase.toByte());put(exchange.result.toByte())
    }
    fun deleteAddress(value:Peer)=packet(0x0f0c){peer(value)}
    /** 004391f0 reader: type:u16, error:raw u8, count:u8, UTF-16BE.
     * type=failed opcode/error=0 is our correlation policy, not a recovered enum.
     * Dispatch adds another NUL at count; keep that inside its 128-unit array. */
    fun rejected(opcode:Int,message:String)=packet(0x0500) {
        require(opcode in listOf(0x0f08,0x0f0b,0x0f0c,0x0f10,0x0f11,0x0f12))
        require(message.isNotEmpty() && message.length<=126 && message.none {it=='\u0000' || it.isSurrogate() || it.isISOControl()})
        putShort(opcode.toShort());put(0);text(message,128)
    }
}
