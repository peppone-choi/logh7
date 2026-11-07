package org.logh7.protocol

import java.util.Base64

/** Opt-in public country thumbnails; no paths, URLs or original uploads. Author: Peppone Choi. */
object CountryImageMessages {
    const val MODE=17
    fun replies(query:OutfitPartyMessages.Query,revision:Long,emblem:ByteArray?,mapIcon:ByteArray?):List<ByteArray> {
        require(query.mode==MODE && query.outfit>0 && query.base in 1..65535 && revision>=0)
        require(listOfNotNull(emblem,mapIcon).all {it.size==4096})
        val raw=byteArrayOf(1,((if(emblem!=null)1 else 0) or (if(mapIcon!=null)2 else 0)).toByte())+
            (emblem ?: byteArrayOf())+(mapIcon ?: byteArrayOf())
        var hash=2166136261L
        for(byte in raw)hash=((hash xor (byte.toInt() and 255).toLong())*16777619L) and 0xffffffffL
        val text=Base64.getEncoder().encodeToString(raw)
        val chunks=text.chunked(12)
        val digits=revision.toString()
        val header=listOf("CI1",digits.take(12),digits.drop(12),raw.size.toString(),hash.toString(),text.length.toString(),chunks.size.toString(),"32","","")
        fun packet(kind:Int,number:Int,members:List<OutfitPartyMessages.Member>)=
            LoginMessages.sessionMessage(0x032f,OutfitPartyMessages.party(query,2,kind,number,emptyList(),emptyList(),0,0,members))
        return listOf(packet(0,0,header.mapIndexed {i,value->OutfitPartyMessages.Member(i+1,i,0,value)}))+
            chunks.mapIndexed {i,value->OutfitPartyMessages.Member(i+1,0,0,value)}.chunked(10).mapIndexed {i,members->packet(1,i,members)}
    }
}
