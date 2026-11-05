package org.logh7.protocol

/** Revival 032e/032f mode 4: display-only RGB overrides, consumed before native outfit caches. */
object CountryColorMessages {
    /** Names are ASCII fields: 1..4=RGB/inherit; 5..6=decimal revision split at 12 chars. */
    fun reply(query:OutfitPartyMessages.Query,viewerPower:Int,colors:List<String>,revision:Long):ByteArray {
        require(query.mode==4 && query.outfit>0 && query.base in 1..65535 && revision>=0)
        require(colors.size==4 && colors.all {it=="inherit" || it.matches(Regex("[0-9A-F]{6}"))})
        val digits=revision.toString()
        val fields=colors+listOf(digits.take(12),digits.drop(12))
        val members=fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)}
        return OutfitPartyMessages.party(query,viewerPower,0,0,emptyList(),emptyList(),0,0,members)
    }
}
