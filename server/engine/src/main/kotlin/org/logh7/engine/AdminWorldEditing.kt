package org.logh7.engine

import java.security.MessageDigest

/** Operator edits carry a base-specific version, unaffected by ordinary clock ticks. Author: Peppone Choi (peppone-choi). */
data class AdminBaseEdit(val generation:Long,val baseId:Int,val expected:String,
    val supplies:Int,val food:Int,val mineral:Int,val treasury:Long,val economy:PlanetEconomy?=null)

enum class AdminEditOutcome { CHANGED, UNCHANGED, CONFLICT, INVALID, NOT_FOUND, UNAVAILABLE }
data class AdminBaseEditResult(val outcome:AdminEditOutcome,val stocks:PlanetStocks?=null)

object AdminWorldEditing {
    fun version(generation:Long,base:PlanetStock):String = MessageDigest.getInstance("SHA-256")
        .digest("$generation:$base".toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}

    /** Called by the world actor; the caller commits before publishing this candidate. */
    fun evaluate(stocks:PlanetStocks,request:AdminBaseEdit):AdminBaseEditResult {
        if(request.generation<=0 || request.baseId !in 1..65535 || request.supplies<0 || request.food<0 ||
            request.mineral<0 || request.treasury<0 || !request.expected.matches(Regex("[0-9a-f]{64}")))
            return AdminBaseEditResult(AdminEditOutcome.INVALID)
        if(stocks.generation!=request.generation)return AdminBaseEditResult(AdminEditOutcome.CONFLICT)
        val base=stocks.bases[request.baseId] ?: return AdminBaseEditResult(AdminEditOutcome.NOT_FOUND)
        if(version(stocks.generation,base)!=request.expected)return AdminBaseEditResult(AdminEditOutcome.CONFLICT)
        val next=base.copy(supplies=request.supplies,food=request.food,mineral=request.mineral,treasury=request.treasury,
            economy=request.economy ?: base.economy)
        if(next==base)return AdminBaseEditResult(AdminEditOutcome.UNCHANGED,stocks)
        return AdminBaseEditResult(AdminEditOutcome.CHANGED,stocks.copy(bases=stocks.bases+(base.baseId to next)))
    }
}
