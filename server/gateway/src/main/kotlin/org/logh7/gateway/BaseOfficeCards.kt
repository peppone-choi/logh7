package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.engine.PlanetStock
import org.logh7.protocol.BootstrapMessages.OfficeCard

/** Original group-3 office kinds; assignments remain independent of outfit command. */
internal class BaseOfficeCards(galaxy: Galaxy) {
    private val policy=org.logh7.engine.BaseOfficePolicy(galaxy)
    fun holders(base: PlanetStock)=policy.holders(base)
    fun cards(base: PlanetStock, character: Int): List<OfficeCard> {
        if(character<=0) return emptyList()
        return holders(base).filterValues { it==character }.keys.map { OfficeCard(it,base.baseId) }
    }
}
