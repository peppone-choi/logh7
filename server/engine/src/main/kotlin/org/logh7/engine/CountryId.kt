package org.logh7.engine

/** A country's identity; absence of membership is null, never a display code or ID zero. */
@JvmInline
value class CountryId(val value: Int) {
    init { require(value > 0) { "Country ID must be positive" } }
}

val Power.country: CountryId get() = CountryId(when (this) {
    Power.EMPIRE -> 2
    Power.ALLIANCE -> 3
})

/** shortcut: legacy authority still supports countries 2/3; remove after ownership migration. */
fun CountryId?.legacyPower(): Power = when (this?.value) {
    2 -> Power.EMPIRE
    3 -> Power.ALLIANCE
    else -> error("Country membership is unsupported by legacy authority")
}
