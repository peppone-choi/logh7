package org.logh7.engine

import java.io.InputStream
import java.util.Properties

/** Client calendar uses twelve 30-day months; do not substitute Gregorian dates. */
data class GameDate(val year: Int, val month: Int, val day: Int) {
    init { require(month in 1..12 && day in 1..30) }
    fun seconds(baseYear: Int): Long {
        require(year >= baseYear)
        return ((year.toLong() - baseYear) * 360 + (month - 1) * 30 + day - 1) * 86_400
    }
    companion object {
        fun parse(value: String): GameDate {
            val fields = value.split('-').map(String::toInt)
            require(fields.size == 3)
            return GameDate(fields[0], fields[1], fields[2])
        }
    }
}

data class SessionRules(val speed: Long, val capacity: Int, val cpIntervalSeconds: Long,
    val baseYear: Int, val start: GameDate, val end: GameDate,
    val strategyTickRealMillis: Long, val tacticsTickRealMillis: Long) {
    val startSeconds = start.seconds(baseYear)
    val endSeconds = end.seconds(baseYear)
    init {
        require(speed in 1..1000 && capacity in 1..2000 && cpIntervalSeconds in 1..86_400)
        require(strategyTickRealMillis in 1..60_000 && tacticsTickRealMillis in 1..60_000)
        require(startSeconds < endSeconds && endSeconds <= 0xffffffffL)
    }
    companion object {
        fun load(input: InputStream = checkNotNull(SessionRules::class.java.getResourceAsStream("/rules/session.properties"))): SessionRules {
            val values = Properties().also { input.use(it::load) }
            fun value(key: String) = requireNotNull(values.getProperty(key)) { "Missing session rule $key" }
            return SessionRules(value("clock.speed").toLong(), value("session.capacity").toInt(),
                value("cp.interval_seconds").toLong(), value("calendar.base_year").toInt(),
                GameDate.parse(value("session.start_date")), GameDate.parse(value("session.end_date")),
                value("strategy.tick_real_millis").toLong(), value("tactics.tick_real_millis").toLong())
        }
    }
}
