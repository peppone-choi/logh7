package org.logh7.engine

/** Ability changes of one unmanned guard ship relative to its base hull, in percent.
 * `weapons` lists the base weapons that remain mounted. Author: Peppone Choi. */
data class GuardModifier(val attack:Int=90,val armor:Int=100,val speed:Int=100,val sensor:Int=100,
    val weapons:Set<TacticalWeapon> = TacticalWeapon.entries.toSet()) {
    init {require(listOf(attack,armor,speed,sensor).all {it in 10..200} && weapons.isNotEmpty())}
}

/** One purchasable guard ship (update03). Price is in evaluation points; `maximumRank`
 * is the lowest admitted protocol rank number (2 marshal .. 20 private). */
data class GuardShipOption(val index:Int,val power:Int,val name:String,val baseKind:Int,val price:Long,
    val maximumRank:Int,val modifier:GuardModifier,val code:String) {
    /** CD 0055ba80 rows hold 12 characters: the short code plus the price. */
    val label get()="$code $price"
    init {require(index in 0..25 && power in 2..3 && name.isNotBlank() && price>0 && maximumRank in 2..20 && label.length<=12 &&
        ProductionUnits.definitions.any {it.ship && it.kind==baseKind && it.power==power})}
}

/** update03 guard ships: 13 per faction, prices and required ranks are evidence:manual.
 * Base hulls follow each name; the merchant conversions are described as heavily armed, so
 * they use the destroyer hull rather than the unarmed merchant (evidence:guess).
 * Every modifier is evidence:guess derived from the official description: unmanned ships
 * default to 90% attack ("能力値は元になっているユニットより劣る"). Author: Peppone Choi. */
object GuardShips {
    const val MAXIMUM_UNITS=3
    const val BOATS=300
    private const val COMMODORE=7;private const val CAPTAIN=8;private const val COMMANDER=9
    private const val LIEUTENANT_COMMANDER=10;private const val LIEUTENANT=11;private const val SUB_LIEUTENANT=12
    private const val ENSIGN=13;private const val WARRANT=14;private const val PRIVATE=20
    private val beam=setOf(TacticalWeapon.BEAM);private val gun=setOf(TacticalWeapon.GUN)
    private val missile=setOf(TacticalWeapon.MISSILE)
    val options:List<GuardShipOption> = listOf(
        GuardShipOption(0,2,"SS75q1형 전함",32,20000,COMMODORE,GuardModifier(),"SS75q1"),
        GuardShipOption(1,2,"SS75q2형 전함",32,18000,COMMODORE,GuardModifier(sensor=150,weapons=setOf(TacticalWeapon.BEAM,TacticalWeapon.MISSILE)),"SS75q2"),
        GuardShipOption(2,2,"PK86q2형 고속전함(신형 기관)",40,14000,COMMODORE,GuardModifier(speed=115,armor=80),"PK86A"),
        GuardShipOption(3,2,"PK86q2형 고속전함(구형 병장)",40,12000,COMMODORE,GuardModifier(attack=80),"PK86B"),
        GuardShipOption(4,2,"SK80q1형 순항함",48,8000,COMMANDER,GuardModifier(),"SK80q1"),
        GuardShipOption(5,2,"SK80q2형 순항함",48,7000,LIEUTENANT_COMMANDER,GuardModifier(speed=110),"SK80q2"),
        GuardShipOption(6,2,"SK80q4형 순항함",48,6000,LIEUTENANT,GuardModifier(weapons=beam),"SK80q4"),
        GuardShipOption(7,2,"SK80q3형 순항함",48,5000,SUB_LIEUTENANT,GuardModifier(attack=50,armor=150),"SK80q3"),
        GuardShipOption(8,2,"Z82q1형 구축함",56,3000,ENSIGN,GuardModifier(weapons=gun),"Z82q1"),
        GuardShipOption(9,2,"Z82q2형 구축함",56,2500,WARRANT,GuardModifier(weapons=missile),"Z82q2"),
        GuardShipOption(10,2,"Z82q3형 구축함",56,2000,PRIVATE,GuardModifier(attack=110,weapons=missile),"Z82q3"),
        GuardShipOption(11,2,"K86형 q1 고속정",60,1000,PRIVATE,GuardModifier(attack=50,speed=120,sensor=150),"K86q1"),
        GuardShipOption(12,2,"보조 경비함(상선 개장)",56,500,PRIVATE,GuardModifier(attack=80,speed=70),"보조함"),
        GuardShipOption(13,3,"787년 XA형 전함",119,20000,COMMODORE,GuardModifier(attack=80,speed=115),"787XA"),
        GuardShipOption(14,3,"787년 XB형 전함",119,18000,COMMODORE,GuardModifier(armor=140,speed=80),"787XB"),
        GuardShipOption(15,3,"787년 XV형 전함",119,16000,COMMODORE,GuardModifier(attack=85,sensor=80),"787XV"),
        GuardShipOption(16,3,"795년 XA형 순항함",127,14000,CAPTAIN,GuardModifier(attack=100),"795XA"),
        GuardShipOption(17,3,"795년 XM형 순항함(장갑)",127,12000,COMMANDER,GuardModifier(attack=50,armor=130),"795XMA"),
        GuardShipOption(18,3,"795년 XM형 순항함(색적)",127,10000,LIEUTENANT_COMMANDER,GuardModifier(sensor=150),"795XMB"),
        GuardShipOption(19,3,"794년 XA형 타격순항함",135,8000,LIEUTENANT,GuardModifier(attack=95),"794XA"),
        GuardShipOption(20,3,"794년 XD형 타격순항함",135,7000,SUB_LIEUTENANT,GuardModifier(attack=150,armor=40,weapons=missile),"794XD"),
        GuardShipOption(21,3,"796년 XA형 구축함",139,4000,ENSIGN,GuardModifier(attack=100),"796XA"),
        GuardShipOption(22,3,"796년 XD형 구축함",139,3000,WARRANT,GuardModifier(attack=150,armor=60,weapons=missile),"796XD"),
        GuardShipOption(23,3,"796년 XR형 구축함",139,2000,PRIVATE,GuardModifier(sensor=150),"796XR"),
        GuardShipOption(24,3,"보조 경비함(강화 상선)",139,1000,PRIVATE,GuardModifier(),"강화상선"),
        GuardShipOption(25,3,"보조 경비함(상선 개장)",139,500,PRIVATE,GuardModifier(attack=80,speed=70),"보조함"))
    fun forPower(power:Int)=options.filter {it.power==power}
    fun option(index:Int)=options.getOrNull(index)
}

/** Applies a guard ship's GuardModifier to the base hull in tactical combat. Author: Peppone Choi. */
object GuardCombat {
    fun modifier(outfit:OutfitStock?,unit:Int):GuardModifier? =
        outfit?.guard?.get(unit)?.let {GuardShips.option(it)?.modifier}
    fun mount(base:TacticalWeaponMount,weapon:TacticalWeapon,outfit:OutfitStock?,unit:Int):TacticalWeaponMount {
        val m=modifier(outfit,unit) ?: return base
        if(weapon !in m.weapons || base.power==0)return TacticalWeaponMount(255,0,0,0)
        return base.copy(power=maxOf(1,(base.power.toLong()*m.attack/100).toInt()))
    }
    fun armor(base:Int,outfit:OutfitStock?,unit:Int):Int =
        modifier(outfit,unit)?.let {maxOf(1,base*it.armor/100)} ?: base
    fun speed(base:Float,outfit:OutfitStock?,unit:Int):Float =
        modifier(outfit,unit)?.let {base*it.speed/100f} ?: base
}
