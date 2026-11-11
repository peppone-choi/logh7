package org.logh7.engine

/** Native tactical modes; strategy location modes have a separate contract. Author: Peppone Choi. */
enum class TacticalShipMode(val wire:Int,val berth:ShipBerth) {
    STATIONED(4,ShipBerth.STATIONED), ANCHORED(5,ShipBerth.ANCHORED),
    SAILING(6,ShipBerth.NONE), BATTLE(7,ShipBerth.NONE);
    val requiresBase get()=berth!=ShipBerth.NONE
}

private fun validPosture(mode:TacticalShipMode,base:Int)=
    base in 0..65535 && (base>0)==mode.requiresBase

/** Accepted target, distinct from the current posture until the actor commits execution. */
data class TacticalPostureOrder(val character:Int,val mode:TacticalShipMode,val base:Int,
    val acceptedAt:Long,val readyAt:Long,val issuedByOfflineAi:Boolean=false) {
    init {require((character>0 || character==0 && !issuedByOfflineAi &&
        (mode==TacticalShipMode.ANCHORED || mode==TacticalShipMode.SAILING)) && validPosture(mode,base) &&
        acceptedAt in 0..0xffffffffL && readyAt in acceptedAt..0xffffffffL &&
        (!issuedByOfflineAi || mode==TacticalShipMode.ANCHORED))}

    fun canBeExecutedBy(actor:Navigator,grid:Int,now:Long,stocks:PlanetStocks):Boolean =
        actor.characterId==character && actor.generation==stocks.generation && actor.grid==grid &&
            actor.cpPaused && actor.baseId==0 && actor.mode==5 &&
            !BattleDeath.deceased(stocks,character) && !stocks.social.justice.detained(character) &&
            (actor.hull?.woundedUntil ?: 0)<=now
}

/** Grid-scoped state shared by personal flagships and commissioned ship units. */
data class TacticalPosture(val grid:Int,val mode:TacticalShipMode=TacticalShipMode.SAILING,val base:Int=0,
    val order:TacticalPostureOrder?=null,val autonomousAnchor:Boolean=false) {
    init {require(grid in 0..4999 && validPosture(mode,base) && (!autonomousAnchor || mode==TacticalShipMode.ANCHORED))}
}
