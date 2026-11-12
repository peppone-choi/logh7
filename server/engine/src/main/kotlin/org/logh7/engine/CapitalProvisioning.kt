package org.logh7.engine

/**
 * Daily provisioning of uninhabitable bases (fortresses, gas planets) from their side's capital (2026-11-04 balance fix,
 * evidence:guess). Such a base grows no food and its garrison population starved from day 11, emptying every fortress
 * within months when nobody runs transports. Each day a regular (camp 0) country's uninhabitable base receives
 * its missing daily ration, limited to its capital's remaining food after that day's local settlement. Every transfer
 * debits the capital immediately. Current ownership determines the supplier; neutral bases and lost capitals get nothing.
 * Author: Peppone Choi.
 */
internal object CapitalProvisioning {
    /** Stored capitals override seed defaults, including when a stored capital has been lost. */
    fun capitals(galaxy: Galaxy, stocks: PlanetStocks): Map<CountryId, Int> {
        val defaults=Capital.entries.mapNotNull {capital ->
            galaxy.bases.singleOrNull {Capital.of(it)==capital}?.let {CountryId(capital.power) to it.id}
        }.toMap()
        return (defaults+stocks.strategic.capitals).filter {(country,id)->
            id in galaxy.conditions && stocks.bases[id]?.let {it.belongsTo(country) && it.camp==0}==true
        }
    }

    fun source(stock:PlanetStock,base:BaseCondition,capitals:Map<CountryId,Int>):Int? =
        if(base.habitability!=0 || stock.camp!=0) null
        else capitals[stock.country]?.takeIf {it!=stock.baseId}

    fun missingRation(stock:PlanetStock,policy:PlanetProductionPolicy):Int {
        val people=stock.economy ?: return 0
        val per = policy.economy.foodPeoplePerUnit
        val required = (people.population.toLong() + per - 1) / per
        return minOf(required-stock.food,policy.maximumStock.toLong()-stock.food).coerceAtLeast(0).toInt()
    }

    /** The stock topped up to today's ration and the amount shipped, or null when nothing is shipped. */
    fun topUp(stock: PlanetStock, base: BaseCondition, policy: PlanetProductionPolicy, capitals: Map<CountryId, Int>,
        availableFood: Int): Pair<PlanetStock, Int>? {
        if(source(stock,base,capitals)==null)return null
        val amount=minOf(missingRation(stock,policy),availableFood)
        if (amount <= 0) return null
        return stock.copy(food = stock.food + amount) to amount
    }
}
