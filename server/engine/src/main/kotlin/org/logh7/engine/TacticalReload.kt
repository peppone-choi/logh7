package org.logh7.engine

/** Replacement timing, not recovered original balance: beam 10, gun 15, missile 20 real seconds.
 * Absolute game-clock deadlines survive orders, commander changes, logout and restart. Author: 최병호. */
data class TacticalReload(val beam:Long=0,val gun:Long=0,val missile:Long=0) {
    init {require(listOf(beam,gun,missile).all {it in 0..0xffffffffL})}
    fun ready(weapon:TacticalWeapon,seconds:Long):Boolean {
        require(seconds in 0..0xffffffffL)
        return seconds>=when(weapon) {TacticalWeapon.BEAM->beam;TacticalWeapon.GUN->gun;TacticalWeapon.MISSILE->missile}
    }
    fun fired(weapon:TacticalWeapon,seconds:Long,scale:Long):TacticalReload {
        require(scale in 1..1000 && ready(weapon,seconds))
        val deadline=Math.addExact(seconds,seconds(weapon)*scale)
        require(deadline<=0xffffffffL)
        return when(weapon) {TacticalWeapon.BEAM->copy(beam=deadline);TacticalWeapon.GUN->copy(gun=deadline)
            TacticalWeapon.MISSILE->copy(missile=deadline)}
    }
    companion object {
        fun seconds(weapon:TacticalWeapon):Long=when(weapon) {TacticalWeapon.BEAM->10;TacticalWeapon.GUN->15;TacticalWeapon.MISSILE->20}
        fun canFire(unit:ActiveShipUnit,weapon:TacticalWeapon,now:Long,scale:Long):Boolean =
            scale in 1..1000 && now in 0..(0xffffffffL-seconds(weapon)*scale) && (unit.reload?.ready(weapon,now) ?: true)
        fun salvoReady(unit:ActiveShipUnit,now:Long,scale:Long):Boolean {
            val spec=UnitSpecifications.ships.single {it.kind==unit.stack.kind}
            return TacticalWeapon.entries.filter {TacticalArmaments.mount(spec,it).power>0}.all {canFire(unit,it,now,scale)}
        }
    }
}
