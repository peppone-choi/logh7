package org.logh7.persistence

import org.logh7.engine.FlagshipExchangeOffer
import org.logh7.engine.FlagshipMarketState

/** STOCK31 M record; S/C grammars are unchanged. Author: 최병호. */
internal object FlagshipMarketStateCodec {
    fun encode(state:FlagshipMarketState)=state.offers.joinToString(";") {
        "${it.giver}:${it.receiver}:${it.giverKind}:${it.receiverKind}:${it.base}:${it.spot}:${it.expiresAt}"
    }.ifEmpty {"-"}
    fun decode(text:String):FlagshipMarketState {
        require(text.length<=32_000)
        val offers=if(text=="-")emptyList() else text.split(';').map {row->
            val f=row.split(':');require(f.size==7)
            FlagshipExchangeOffer(f[0].toInt(),f[1].toInt(),f[2].toInt(),f[3].toInt(),f[4].toInt(),f[5].toInt(),f[6].toLong())
        }
        return FlagshipMarketState(offers).also {require(encode(it)==text) {"Noncanonical flagship exchange state"}}
    }
}
