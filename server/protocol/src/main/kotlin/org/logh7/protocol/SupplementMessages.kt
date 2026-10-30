package org.logh7.protocol

import java.nio.ByteBuffer

/** Native 00552610 writer / 00556b10 reader, variable records without C++ padding. Author: Peppone Choi. */
object SupplementMessages {
    data class Units(val kind: Int,val ids: List<Int>) {
        init {require(kind in 0..65535 && ids.size<=99)}
    }
    data class Query(val header: LogisticsMessages.Header,val mode: Int,val base: Int,val outfit: Int,val kind: Int,
        val units: List<Units>,val troops: List<LogisticsMessages.TroopMove>,val supplies: Int,
        val result: LogisticsMessages.Inventory,val resultSupplies: Int,val maxCrew: Int,val maxSupplies: Int) {
        init {require(mode in 0..255 && kind in 0..255 && units.size<=99 && troops.size<=24)}
    }
    private fun ByteBuffer.u8()=get().toInt() and 255
    private fun ByteBuffer.u16()=short.toInt() and 65535
    private fun <T> ByteBuffer.rows(max: Int,width: Int,read: ByteBuffer.()->T): List<T> {
        require(hasRemaining());val count=u8();require(count<=max && remaining()>=count*width)
        return List(count){read()}
    }
    fun query(bytes: ByteArray): Query {
        require(bytes.size>=48)
        val b=ByteBuffer.wrap(bytes);require(b.u16()==0x0c05)
        val time=b.int;val character=b.int;val mode=b.u8()
        val h=LogisticsMessages.Header(time,character,b.int,b.int)
        val base=b.int;val outfit=b.int;val kind=b.u8()
        val units=b.rows(99,3) {val k=u16();Units(k,rows(99,4){int})}
        val troops=b.rows(24,5){LogisticsMessages.TroopMove(u16(),u8(),short.toInt())}
        require(b.remaining()>=4);val supplies=b.int
        val ships=b.rows(99,5){LogisticsMessages.ShipStock(u16(),u8(),u16())}
        val crew=b.rows(24,5){LogisticsMessages.TroopStock(u16(),u8(),u16())}
        require(b.remaining()==12)
        return Query(h,mode,base,outfit,kind,units,troops,supplies,LogisticsMessages.Inventory(ships,crew),b.int,b.int,b.int)
    }
    fun encode(q: Query): ByteArray {
        val size=48+q.units.sumOf {3+it.ids.size*4}+5*(q.troops.size+q.result.ships.size+q.result.troops.size)
        val b=ByteBuffer.allocate(size)
        b.putShort(0x0c05).putInt(q.header.time).putInt(q.header.characterId).put(q.mode.toByte())
            .putInt(q.header.pcp).putInt(q.header.mcp).putInt(q.base).putInt(q.outfit).put(q.kind.toByte())
        b.put(q.units.size.toByte());q.units.forEach {row ->
            b.putShort(row.kind.toShort()).put(row.ids.size.toByte());row.ids.forEach(b::putInt)
        }
        fun row(kind: Int,middle: Int,last: Int){b.putShort(kind.toShort()).put(middle.toByte()).putShort(last.toShort())}
        b.put(q.troops.size.toByte());q.troops.forEach {row(it.kind,it.grade,it.units)}
        b.putInt(q.supplies).put(q.result.ships.size.toByte());q.result.ships.forEach {row(it.kind,it.units,it.boats)}
        b.put(q.result.troops.size.toByte());q.result.troops.forEach {row(it.kind,it.grade,it.units)}
        b.putInt(q.resultSupplies).putInt(q.maxCrew).putInt(q.maxSupplies)
        check(!b.hasRemaining());return b.array()
    }
    fun response(q: Query,seconds: Long,pcp: Int,mcp: Int,accepted: Boolean,
        troops: List<LogisticsMessages.TroopMove> = emptyList(),supplies: Int=0,
        inventory: LogisticsMessages.Inventory=LogisticsMessages.Inventory(emptyList(),emptyList()),
        totalSupplies: Int=0,maxCrew: Int=0,maxSupplies: Int=0): ByteArray {
        require(seconds in 0..0xffffffffL && pcp>=0 && mcp>=0 && totalSupplies>=0 && maxCrew>=0 && maxSupplies>=0)
        val value=q.copy(header=LogisticsMessages.Header(seconds.toInt(),q.header.characterId,pcp,mcp),
            units=if(accepted)q.units else emptyList(),troops=if(accepted)troops else emptyList(),
            supplies=if(accepted)supplies else 0,result=if(accepted)inventory else LogisticsMessages.Inventory(emptyList(),emptyList()),
            resultSupplies=if(accepted)totalSupplies else 0,maxCrew=if(accepted)maxCrew else 0,maxSupplies=if(accepted)maxSupplies else 0)
        val bytes=encode(value)
        return LoginMessages.sessionMessage(0x0c05,bytes.copyOfRange(2,bytes.size),if(accepted)0 else 1)
    }
}
