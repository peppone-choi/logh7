package org.logh7.protocol

/** Private own-country metadata on 032e/032f mode 11. Query outfit = own character, base = nonce.
 * The response echoes both; country id is a text field, never a native power byte. Author: Peppone Choi. */
object CountryIdentityMessages {
    const val MODE=11
    fun reply(query:OutfitPartyMessages.Query,country:Int,nativePower:Int,name:String,calendar:String,
        yearOffset:Int,revision:Long,registered:Boolean):ByteArray {
        require(query.mode==MODE && query.outfit>0 && query.base in 1..65535)
        require(country>=0 && nativePower in 2..3 && yearOffset in -10000..10000 && revision>=0 && (!registered || country>0))
        require(name.isNotEmpty() && name.length<=24 && calendar.isNotEmpty() && calendar.length<=12 &&
            (name+calendar).none {it.isISOControl() || it.isSurrogate()})
        val digits=revision.toString()
        val fields=listOf("NI1",country.toString(),name.take(12),name.drop(12),calendar,yearOffset.toString(),
            digits.take(12),digits.drop(12),nativePower.toString(),if(registered)"1" else "0")
        // Mode 11 has no cargo. Its first cargo word advertises the private fuel query.
        return OutfitPartyMessages.party(query,2,0,0,emptyList(),emptyList(),1,1,
            fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)})
    }
}
