package org.logh7.protocol

/** Revival 032e/032f mode 5: bounded country/calendar metadata, outside native outfit caches. Author: Peppone Choi. */
object CountryMetadataMessages {
    fun reply(query:OutfitPartyMessages.Query,viewerPower:Int,name:String,calendar:String,yearOffset:Int,revision:Long):ByteArray {
        require(query.mode==5 && query.outfit>0 && query.base in 1..65535 && yearOffset in -10000..10000 && revision>=0)
        require(name.length<=24 && calendar.length<=12 && (name+calendar).none {it.isISOControl() || it.isSurrogate()})
        val digits=revision.toString()
        val fields=listOf(name.take(12),name.drop(12),calendar,yearOffset.toString(),digits.take(12),digits.drop(12))
        return OutfitPartyMessages.party(query,viewerPower,0,0,emptyList(),emptyList(),0,0,
            fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)})
    }
}
