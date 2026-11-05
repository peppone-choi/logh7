package org.logh7.engine

/** RGB overrides; null keeps the native asset's colors. Author: Peppone Choi (peppone-choi). */
data class CountryColors(val primary:Int?=null,val secondary:Int?=null,val map:Int?=null,val fleet:Int?=null) {
    init {require(listOf(primary,secondary,map,fleet).all {it==null || it in 0..0xffffff})}
    companion object {
        fun parse(value:String):Int? {
            if(value=="inherit")return null
            require(value.matches(Regex("#[0-9a-fA-F]{6}"))) {"Expected #RRGGBB or inherit"}
            return value.drop(1).toInt(16)
        }
        fun hex(value:Int?):String=if(value==null)"inherit" else "#%06X".format(value)
    }
}
data class CountryAppearance(val countryId:Int,val name:String,val family:String,val colors:CountryColors,val revision:Long) {
    init {require(countryId>0 && revision>=0)}
}
enum class CountryColorOutcome { CHANGED, UNCHANGED, CONFLICT, NOT_FOUND }
interface CountryColorStore {
    fun list():List<CountryAppearance>
    fun save(countryId:Int,expectedRevision:Long,colors:CountryColors):CountryColorOutcome
}
