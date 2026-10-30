package org.logh7.engine

/** Designed economy parameters; no original server formulas survived. Author: Peppone Choi. */
data class PlanetEconomy(val population: Int, val adults: Int, val approval: Int, val peace: Int) {
    init { require(population >= 0 && adults in 0..population && approval in 0..100 && peace in 0..100) }
}

data class PlanetEconomyPolicy(val foodWorkersPerUnit: Int = 500, val foodPeoplePerUnit: Int = 1000,
    val mineralWorkersPerUnit: Int = 5000, val suppliesPerMineral: Int = 10,
    val dailyGrowthPerMillion: Int = 100, val dailyStarvationPerMillion: Int = 10000) {
    init { require(foodWorkersPerUnit > 0 && foodPeoplePerUnit > 0 && mineralWorkersPerUnit > 0 &&
        suppliesPerMineral > 0 && dailyGrowthPerMillion in 0..1_000_000 && dailyStarvationPerMillion in 0..1_000_000) }
}

/** One day at a time: production/consumption and demographic feedback share one durable result. */
internal fun PlanetStock.nextDay(base: BaseCondition, policy: PlanetProductionPolicy): PlanetStock {
    val people = checkNotNull(economy)
    val rules = policy.economy
    fun produced(existing: Int, amount: Long) = if (existing >= policy.maximumStock) existing.toLong()
        else minOf(policy.maximumStock.toLong(), existing.toLong() + amount)
    val foodProduced = if (base.habitability != 0) people.adults.toLong() / rules.foodWorkersPerUnit else 0
    val availableFood = produced(food, foodProduced)
    val requiredFood = (people.population.toLong() + rules.foodPeoplePerUnit - 1) / rules.foodPeoplePerUnit
    val missingFood = maxOf(0, requiredFood - availableFood)
    val fed = missingFood == 0L
    val population = when {
        !fed -> {
            val fullLoss = (people.population.toLong() * rules.dailyStarvationPerMillion + 999_999) / 1_000_000
            val loss = (fullLoss * missingFood + requiredFood - 1) / requiredFood
            maxOf(0, people.population.toLong() - loss).toInt()
        }
        base.habitability != 0 -> minOf(Int.MAX_VALUE.toLong(), people.population.toLong() +
            people.population.toLong() * rules.dailyGrowthPerMillion / 1_000_000).toInt()
        else -> people.population
    }
    val availableMineral = produced(mineral, people.adults.toLong() / rules.mineralWorkersPerUnit)
    val capacity = maxOf(0L, policy.maximumStock.toLong() - supplies)
    val suppliesProduced = if (fed && people.adults > 0 && power in 2..3 && camp == 0) minOf(policy.suppliesPerDay.toLong(), capacity,
        availableMineral * rules.suppliesPerMineral) else 0
    // Charge whole mineral units, including the final partial batch.
    val mineralUsed = (suppliesProduced + rules.suppliesPerMineral - 1) / rules.suppliesPerMineral
    fun sentiment(current: Int, normal: Int, damage: Int) = if (!fed) maxOf(0, current - damage)
        else if (current < normal) current + 1 else current
    return copy(supplies = (supplies + suppliesProduced).toInt(), food = maxOf(0, availableFood - requiredFood).toInt(),
        mineral = (availableMineral - mineralUsed).toInt(), economy = PlanetEconomy(population,
            if (people.population == 0) 0 else (people.adults.toLong() * population / people.population).toInt(),
            sentiment(people.approval, base.approval, 2), sentiment(people.peace, base.peace, 1)))
}
