package org.logh7.protocol

/** Country/calendar metadata (5), with the tactical emblem in an opt-in reply (16). Author: Peppone Choi. */
object CountryMetadataMessages {
    const val EMBLEM_MODE=16
    fun reply(query:OutfitPartyMessages.Query,viewerPower:Int,name:String,calendar:String,yearOffset:Int,revision:Long,emblem:Int=0):ByteArray {
        require((query.mode==5 || query.mode==EMBLEM_MODE) && query.outfit>0 && query.base in 1..65535 && yearOffset in -10000..10000 && revision>=0)
        require(emblem in setOf(0,2,3))
        require(name.length<=24 && calendar.length<=12 && (name+calendar).none {it.isISOControl() || it.isSurrogate()})
        val digits=revision.toString()
        val fields=listOf(name.take(12),name.drop(12),calendar,yearOffset.toString(),digits.take(12),digits.drop(12))+
            if(query.mode==EMBLEM_MODE)listOf(emblem.toString()) else emptyList()
        return OutfitPartyMessages.party(query,viewerPower,0,0,emptyList(),emptyList(),0,0,
            fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)})
    }
}
