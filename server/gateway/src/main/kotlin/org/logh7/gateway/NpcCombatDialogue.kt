package org.logh7.gateway

import org.logh7.engine.TacticalWeapon

/** Called on the world delivery path. Implementations must return without waiting for a network response. */
fun interface NpcCombatDialogue {
    fun request(weapon:TacticalWeapon, complete:(String?)->Unit)
}
