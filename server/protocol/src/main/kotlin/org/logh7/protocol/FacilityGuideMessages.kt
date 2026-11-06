package org.logh7.protocol

/** Mode 6 is display-only facility metadata, never an outfit or command. Author: Peppone Choi. */
object FacilityGuideMessages {
    const val PAGES=9
    const val PAGE_TEXT=96
    fun reply(query:OutfitPartyMessages.Query,viewerPower:Int,revision:Long,name:String,purpose:String,
        usage:String,commands:String):ByteArray {
        require(query.mode==6 && query.outfit/100 in 1..65535 && query.outfit%100 in 1..39 &&
            query.base in 16..65535 && query.base%16<PAGES && revision>=0)
        require(name.length<=24 && purpose.length<=200 && usage.length<=200 && commands.length<=192)
        require((name+purpose+usage+commands).none {it.isISOControl() || it.isSurrogate()})
        val page=query.base%16
        val (text,offset)=when(page) {
            0->name to 0
            in 1..3->purpose to (page-1)*PAGE_TEXT
            in 4..6->usage to (page-4)*PAGE_TEXT
            else->commands to (page-7)*PAGE_TEXT
        }
        val fragment=text.drop(offset).take(PAGE_TEXT)
        val digits=revision.toString()
        val fields=listOf(digits.take(12),digits.drop(12))+(0..7).map {fragment.drop(it*12).take(12)}
        return OutfitPartyMessages.party(query,viewerPower,0,0,emptyList(),emptyList(),0,0,
            fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)})
    }
}
