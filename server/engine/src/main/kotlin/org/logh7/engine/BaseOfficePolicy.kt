package org.logh7.engine

/** Original capital identities, matching the existing world/environment seed. */
enum class Capital(val power: Int, val grid: Int, val baseName: String) {
    EMPIRE(2,2487,"オーディン"), ALLIANCE(3,2015,"ハイネセン");
    companion object {
        fun of(base: GalaxyBase): Capital? = entries.singleOrNull {
            base.kind==0 && base.grid==it.grid && base.name==it.baseName
        }
    }
}

/** Manual organization pp.58/64: capital and ordinary planetary offices are distinct. */
class BaseOfficePolicy(galaxy: Galaxy) {
    private val bases=galaxy.bases.associateBy { it.id }
    fun ordinaryPlanet(id: Int): Boolean = bases[id]?.let { it.kind==0 && Capital.of(it)==null } == true
    fun validate(stock: PlanetStock) {
        val seed=checkNotNull(bases[stock.baseId]) { "Appointed base is missing from catalogue" }
        if(Capital.of(seed)?.power?.let { it!=stock.power }==true)
            require(stock.capitalAdministratorId==0 && stock.capitalDefenseCommanderId==0 && stock.guardInspectorId==0)
        when(Capital.of(seed)) {
            Capital.EMPIRE -> require(stock.capitalAdministratorId==0)
            Capital.ALLIANCE -> require(stock.guardInspectorId==0)
            null -> require(stock.capitalAdministratorId==0 && stock.capitalDefenseCommanderId==0 && stock.guardInspectorId==0) {
                "Capital office assigned to an ordinary base"
            }
        }
    }
    fun holders(stock: PlanetStock): Map<Int,Int> {
        validate(stock)
        if(stock.camp!=0 || stock.power !in 2..3) return emptyMap()
        val seed=bases.getValue(stock.baseId)
        val capital=Capital.of(seed)
        if(capital!=null && capital.power!=stock.power) return emptyMap()
        val offset=if(stock.power==3)136 else 0
        return when(capital) {
            Capital.EMPIRE -> mapOf(56 to stock.capitalDefenseCommanderId,57 to stock.guardInspectorId)
            Capital.ALLIANCE -> mapOf(192 to stock.capitalAdministratorId,193 to stock.capitalDefenseCommanderId)
            null -> if(seed.kind==1) mapOf(52+offset to stock.garrisonCommanderId)
                else mapOf(54+offset to stock.governorId,55+offset to stock.garrisonCommanderId)
        }.filterValues { it>0 }
    }
}
