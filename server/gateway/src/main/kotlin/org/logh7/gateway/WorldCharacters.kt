package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.CharacterMessages

/** One current personnel view for login, observers and tactical reconnect. Author: Peppone Choi. */
internal object WorldCharacters {
    /** Private strategic detail belongs to a registered actual country, regardless of the native display byte. */
    fun friendlyCountry(actor:CharacterProfile?,target:CharacterProfile?,countries:CountryCatalog=CountryCatalogLoader.current()):CountryId? =
        actor?.country?.takeIf {it==target?.country && it.value in countries.countries}
    fun mayReadPrivateDetail(actor:CharacterProfile?,target:CharacterProfile?,countries:CountryCatalog=CountryCatalogLoader.current()):Boolean =
        actor!=null && target!=null && (actor.character.id==target.character.id || friendlyCountry(actor,target,countries)!=null)

    /** A world checkpoint is authoritative after admission/defection. Match account, character and
     * generation so an old navigator can never supply a newly registered character's membership. */
    fun profiles(stored:Map<String,CharacterProfile>,snapshot:WorldSnapshot?):Map<String,CharacterProfile> {
        val deleted = snapshot?.planetStocks?.takeIf { it.generation == snapshot.session?.generation }?.deletedCharacters.orEmpty()
        return stored.filterNot { (account, profile) ->
            profile.character.generated && profile.character.id.toInt() in deleted[account].orEmpty()
        }.mapValues { (account, profile) ->
            val player = snapshot?.navigators?.get(account)?.takeIf {
                it.characterId == profile.character.id.toInt() && it.generation == snapshot.session?.generation
            }
            if (player == null) profile else profile.copy(country = player.country, shipStyle = player.shipStyle)
        }
    }
    fun memberCounts(stored:Map<String,CharacterProfile>,snapshot:WorldSnapshot?):Map<CountryId,Int> =
        profiles(stored,snapshot).values.mapNotNull {it.country}.groupingBy {it}.eachCount()

    fun identities(stored:Map<String,CharacterProfile>,snapshot:WorldSnapshot?):List<StrategicIdentity> =
        (profiles(stored,snapshot).values + governmentProfiles(snapshot)).sortedBy {it.character.id}.map {profile->
            val c=profile.character
            StrategicIdentity(c.id.toInt(),profile.country,c.rank,c.origin,c.abilities.toList(),c.age)
        }

    fun governmentProfiles(snapshot: WorldSnapshot?): List<CharacterProfile> =
        InitialGovernment.roster(snapshot?.planetStocks?.takeIf { it.generation == snapshot.session?.generation }).map { official ->
            val power = official.country.value
            val hull = Flagships.rankDefault(power, official.rank)
            CharacterProfile.legacy(CharacterMessages.Generate(
                category = 4, id = official.id.toLong(), power = power, origin = 0, gender = 0,
                surname = official.surname, givenName = official.givenName, age = 0, birthMonth = 1,
                birthDay = 1, face = 1000001, abilities = InitialGovernment.baselineAbilities, bonus = 0,
                special = 0, title = 0, rank = official.rank, shipType = hull.type, shipKind = hull.kind,
                shipName = "", check = 0, generated = false))
        }

    fun view(character:CharacterMessages.Generate,snapshot:WorldSnapshot?):CharacterMessages.Generate {
        if(snapshot==null)return character
        val id=character.id.toInt()
        val stocks=snapshot.planetStocks
        val profile=stocks?.strategic?.people?.get(id)
        val entry=snapshot.navigators.entries.singleOrNull {it.value.characterId==id}
        val player=entry?.value
        val switches=stocks?.tacticalCommands?.ai(id) ?: List(5){0}
        val updated=profile?.let {character.copy(rank=it.rank ?: character.rank,abilities=it.abilities ?: character.abilities,
            title=it.peerage?.let {title->title.ordinal+1} ?: 0,merit=it.merit,influence=it.influence,
            evaluation=it.evaluation,money=it.money,decorations=it.decorations)} ?: character
        val configured=updated.copy(merit=stocks?.takeIf {profile!=null || id in it.tacticalCommands.merits}?.let {TacticalPersonnel.merit(it,id)} ?: updated.merit,
            ai=listOf(0)+switches,experience=stocks?.social?.academy?.experience?.get(id) ?: updated.experience)
        return player?.hull?.let {Flagships.find(it.kind)}?.let {configured.copy(shipKind=it.kind,shipType=it.type)} ?: configured
    }
}
