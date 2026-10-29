package org.logh7.protocol

/** constmsg group 3 indices. Rebel cards are distinct from ordinary office slots. */
object OfficeCardKinds {
    fun isOffice(card:Int)=card in 1..58 || card in 68..122 || card in 132..194 || card in 197..256
    fun isPeerage(card:Int)=card in 60..65 || card in 124..129
    fun isFief(card:Int)=card==66 || card==130
    fun isOutfit(card:Int)=card in 39..50 || card in 103..114 || card in 175..186 || card in 237..248
    fun requiresBase(card:Int)=card in 51..55 || card in 115..119 || card in 187..191 || card in 249..253
    fun power(card:Int)=when(card){in 1..131->2;in 132..258->3;259->4;260->1;else->0}
    fun camp(card:Int)=if(card in 68..131 || card in 197..258)1 else 0
}
