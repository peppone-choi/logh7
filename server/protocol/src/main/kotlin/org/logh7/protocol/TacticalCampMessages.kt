package org.logh7.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 032e/032f mode 15: the actual combat side of every unit on the viewer's tactical grid, beside the native
 * display power. Pages of [PAGE] rows by ascending unit ID after an exclusive cursor. Author: Peppone Choi.
 */
object TacticalCampMessages {
    const val MODE=15
    const val PAGE=60
    const val MAXIMUM=600
    /** kind 0 country (owner = country ID), 1 unaffiliated individual (owner = character ID), 2 pirates, 3 Fezzan. */
    data class Side(val kind:Int,val owner:Int,val insurgent:Boolean) {
        init {
            when(kind) {
                0,1->require(owner>0) {"Side owner required"}
                2,3->require(owner==0 && !insurgent) {"Faction side has no owner or rebellion"}
                else->throw IllegalArgumentException("Unknown side kind")
            }
        }
        /** (owner << 3) | (kind << 1) | insurgent. */
        val code:Long get()=(owner.toLong() shl 3) or (kind.toLong() shl 1) or (if(insurgent)1L else 0L)
    }
    /**
     * Stable fingerprint of the viewer's side and the whole unit/side list (not the page, not time or position):
     * SHA-256 over big-endian Long viewer code, then for each unit by ascending ID a big-endian Int ID and Long
     * side code; the first 8 bytes as a big-endian Long, masked to non-negative.
     */
    fun stamp(viewer:Side,units:Map<Int,Side>):Long {
        val sorted=units.entries.sortedBy {it.key}
        val buffer=ByteBuffer.allocate(8+sorted.size*12).putLong(viewer.code)
        sorted.forEach {(id,side)->buffer.putInt(id).putLong(side.code)}
        val digest=MessageDigest.getInstance("SHA-256").digest(buffer.array())
        return ByteBuffer.wrap(digest,0,8).long and Long.MAX_VALUE
    }
    fun reply(query:OutfitPartyMessages.Query,grid:Int,generation:Long,viewer:Side,units:Map<Int,Side>):List<ByteArray> {
        require(query.mode==MODE && query.outfit in 1..65535 && query.base>=0) {"Invalid tactical camp query"}
        require(grid in 0..4999 && generation>0 && units.size<=MAXIMUM && units.keys.all {it>0})
        val remaining=units.entries.filter {it.key>query.base}.sortedBy {it.key}
        val page=remaining.take(PAGE)
        fun digits(value:Long)=value.toString().let {listOf(it.take(12),it.drop(12))}
        fun frame(kind:Int,number:Int,members:List<OutfitPartyMessages.Member>)=
            OutfitPartyMessages.party(query,2,kind,number,emptyList(),emptyList(),0,0,members)
        val header=listOf("UC1",page.size.toString(),(page.lastOrNull()?.key ?: query.base).toString(),
            if(remaining.size>PAGE)"1" else "0",grid.toString())+digits(generation)+digits(stamp(viewer,units))+viewer.code.toString()
        return listOf(frame(0,0,header.mapIndexed {i,value->OutfitPartyMessages.Member(i+1,i,0,value)}))+
            page.chunked(10).mapIndexed {batch,rows->frame(1,batch,rows.map {(id,side)->
                OutfitPartyMessages.Member(id,side.kind,if(side.insurgent)1 else 0,side.owner.toString())})}
    }
}
