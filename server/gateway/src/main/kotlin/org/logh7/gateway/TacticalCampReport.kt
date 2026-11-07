package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages
import org.logh7.protocol.LoginMessages
import org.logh7.protocol.OutfitPartyMessages
import org.logh7.protocol.TacticalCampMessages

/** 032f mode 15 for an authenticated viewer in tactics: exactly [TacticalBootstrap.unitCamps]. Author: Peppone Choi. */
internal object TacticalCampReport {
    fun side(camp:Camp):TacticalCampMessages.Side=when(val owner=camp.owner) {
        is Camp.Owner.Country->TacticalCampMessages.Side(0,owner.id.value,camp.insurgent)
        is Camp.Owner.Independent->TacticalCampMessages.Side(1,owner.character,camp.insurgent)
        Camp.Owner.Pirates->TacticalCampMessages.Side(2,0,false)
        Camp.Owner.Fezzan->TacticalCampMessages.Side(3,0,false)
    }
    fun reply(query:OutfitPartyMessages.Query,snapshot:WorldSnapshot,galaxy:Galaxy,viewer:Navigator,generation:Long,
        characterById:(Int)->CharacterMessages.Generate?,countries:CountryCatalog=CountryCatalogLoader.current()):List<ByteArray> {
        // Only the battlefield the viewer is actually in; never a client-chosen grid or a strategic viewer.
        require(viewer.cpPaused && viewer.baseId==0) {"Tactical camps are outside the current battlefield"}
        val stocks=checkNotNull(snapshot.planetStocks)
        require(viewer.generation==generation && snapshot.session?.generation==generation && stocks.generation==generation) {
            "Stale tactical camp viewer" }
        val camps=TacticalBootstrap(snapshot,galaxy,viewer,countries,characterById).unitCamps.mapValues {side(it.value)}
        return TacticalCampMessages.reply(query,viewer.grid,generation,side(TacticalAuthority.camp(viewer,stocks)),camps)
            .map {LoginMessages.sessionMessage(0x032f,it)}
    }
}
