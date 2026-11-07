package org.logh7.protocol

/** Pre-game country choice on 032e/032f. Actual country ids never occupy native power slots.
 * outfit = country id (0 for cancel), base = nonce*65536+session, mode = create/lottery/cancel.
 * A successful NS1 acknowledgement precedes opening the native wizard. Author: Peppone Choi. */
object CountrySelectionMessages {
    const val CREATE=8
    const val LOTTERY=9
    const val CANCEL=10
    fun matches(mode:Int)=mode in CREATE..CANCEL
    data class Request(val query:OutfitPartyMessages.Query) {
        val countryId get()=query.outfit
        val sessionId get()=query.base and 65535
        val nonce get()=query.base ushr 16
        init {
            require(matches(query.mode) && sessionId in 1..65535 && nonce in 1..32767)
            require(if(query.mode==CANCEL)countryId==0 else countryId>0)
        }
    }
    fun request(query:OutfitPartyMessages.Query)=Request(query)
    fun reply(request:Request,accepted:Boolean,creationPower:Int=0,shipStyle:String=""):ByteArray {
        require(if(accepted && request.query.mode!=CANCEL)creationPower in 2..3 && shipStyle in setOf("empire","alliance")
            else creationPower==0 && shipStyle.isEmpty())
        val fields=listOf("NS1",if(accepted)"1" else "0",creationPower.toString(),shipStyle,"","","","","","")
        return OutfitPartyMessages.party(request.query,2,0,0,emptyList(),emptyList(),0,0,
            fields.mapIndexed {index,value->OutfitPartyMessages.Member(index+1,index,0,value)})
    }
}
