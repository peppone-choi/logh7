package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages

/** One current personnel view for login, observers and tactical reconnect. Author: Peppone Choi. */
internal object WorldCharacters {
    fun view(character:CharacterMessages.Generate,snapshot:WorldSnapshot?):CharacterMessages.Generate {
        if(snapshot==null)return character
        val id=character.id.toInt()
        val stocks=snapshot.planetStocks
        val profile=stocks?.strategic?.people?.get(id)
        val entry=snapshot.navigators.entries.singleOrNull {it.value.characterId==id}
        val player=entry?.value
        val switches=stocks?.tacticalCommands?.ai(id) ?: List(5){0}
        val updated=profile?.let {character.copy(rank=it.rank ?: character.rank,abilities=it.abilities ?: character.abilities,
            title=it.peerage?.let {title->title.ordinal+1} ?: character.title,merit=it.merit,influence=it.influence,
            evaluation=it.evaluation,money=it.money,decorations=it.decorations)} ?: character
        val configured=updated.copy(merit=stocks?.takeIf {profile!=null || id in it.tacticalCommands.merits}?.let {TacticalPersonnel.merit(it,id)} ?: updated.merit,
            ai=listOf(0)+switches,experience=stocks?.social?.academy?.experience?.get(id) ?: updated.experience)
        return player?.hull?.let {Flagships.find(it.kind)}?.let {configured.copy(shipKind=it.kind,shipType=it.type)} ?: configured
    }
}
