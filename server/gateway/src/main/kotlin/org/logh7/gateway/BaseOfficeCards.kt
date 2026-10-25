package org.logh7.gateway

import org.logh7.engine.Galaxy
import org.logh7.engine.PlanetStock
import org.logh7.protocol.BootstrapMessages.OfficeCard

/** Original group-3 office kinds; assignments remain independent of outfit command. */
internal class BaseOfficeCards(galaxy: Galaxy) {
    private val kinds=galaxy.bases.associate { it.id to it.kind }
    fun cards(base: PlanetStock, character: Int): List<OfficeCard> {
        if (base.camp!=0 || base.power !in 2..3 || character<=0) return emptyList()
        val offset=if (base.power==3) 136 else 0
        val kind=checkNotNull(kinds[base.baseId]) { "Appointed base is missing from catalogue" }
        return buildList {
            if (base.governorId==character) add(OfficeCard(54+offset,base.baseId))
            if (base.garrisonCommanderId==character) add(OfficeCard((if (kind==1) 52 else 55)+offset,base.baseId))
        }
    }
}
