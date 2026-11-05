package org.logh7.engine

/** Model/texture family, independent of the country owning a ship. */
@JvmInline
value class ShipStyleId(val value:String) {
    init {require(value.matches(Regex("[a-z][a-z0-9_]{0,63}"))) {"Invalid ship style ID"}}
    /** shortcut: native model tables currently contain two styles; extend with registered model packs. */
    fun legacyPower():Power=when(value) {
        "empire"->Power.EMPIRE
        "alliance"->Power.ALLIANCE
        else->error("Ship style is unsupported by native model tables")
    }
    val nativePower:Int get()=when(legacyPower()) {Power.EMPIRE->2;Power.ALLIANCE->3}
    companion object {
        fun fromLegacy(power:Power)=ShipStyleId(when(power) {Power.EMPIRE->"empire";Power.ALLIANCE->"alliance"})
    }
}
