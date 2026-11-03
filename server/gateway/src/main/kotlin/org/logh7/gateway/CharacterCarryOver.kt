package org.logh7.gateway

import org.logh7.engine.WorldSnapshot
import org.logh7.protocol.CharacterMessages

/**
 * U-27 character carry-over (manual W p.11, evidence:manual): a generated character that earned enough evaluation
 * points in the session moves to the next one with its age, abilities and rank, until it reaches 60. Originals never
 * carry over. The manual gives no threshold: [MINIMUM_EVALUATION] is a provisional value (evidence:guess, awaiting the
 * user's confirmation). Merit, money, evaluation and influence start afresh. Author: Peppone Choi.
 */
object CharacterCarryOver {
    const val MINIMUM_EVALUATION = 1_000L
    const val AGE_LIMIT = 60
    /** One game year: twelve 30-day periods, the clock AbilityAging uses. */
    private const val YEAR_SECONDS = 12L * 30 * 86_400

    fun ageAtEnd(registeredAge: Int, gameSeconds: Long) = registeredAge + (gameSeconds / YEAR_SECONDS).toInt()

    fun select(snapshot: WorldSnapshot, generated: Map<String, CharacterMessages.Generate>): Map<String, CharacterMessages.Generate> {
        val seconds = snapshot.session?.gameSeconds ?: return emptyMap()
        val people = snapshot.planetStocks?.strategic?.people ?: return emptyMap()
        return generated.mapNotNull { (account, data) ->
            val person = people[data.id.toInt()] ?: return@mapNotNull null
            val age = ageAtEnd(data.age, seconds)
            if (person.deceasedAt > 0 || person.evaluation < MINIMUM_EVALUATION || age >= AGE_LIMIT) return@mapNotNull null
            account to data.copy(age = age, abilities = person.abilities ?: data.abilities, rank = person.rank ?: data.rank,
                merit = 0, influence = 0, evaluation = 0, money = 0)
        }.toMap()
    }
}
