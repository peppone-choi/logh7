package org.logh7.engine

/** A country owner and non-state factions are distinct even when the client uses one display byte. */
sealed interface Allegiance {
    data class Country(val id:CountryId):Allegiance
    data class Independent(val characterId:Int):Allegiance {
        init { require(characterId > 0) }
    }
    data object Unspecified:Allegiance
    data object Neutral:Allegiance
    data object Pirates:Allegiance

    val country:CountryId? get()=(this as? Country)?.id

    /** shortcut: legacy rules support only countries 2/3; remove after authority migration. */
    fun legacyPower():Int=when(this) {
        Unspecified->0
        Neutral,is Independent->1
        Pirates->4
        is Country->when(id.legacyPower()) {Power.EMPIRE->2;Power.ALLIANCE->3}
    }

    companion object {
        fun fromLegacy(power:Int):Allegiance=when(power) {
            0->Unspecified
            1->Neutral
            2,3->Country(CountryId(power))
            4->Pirates
            else->throw IllegalArgumentException("Invalid legacy affiliation")
        }
    }
}
