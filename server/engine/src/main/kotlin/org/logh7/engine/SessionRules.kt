package org.logh7.engine

import java.io.InputStream
import java.util.Properties

/** Client calendar uses twelve 30-day months; do not substitute Gregorian dates. */
data class GameDate(val year: Int, val month: Int, val day: Int, val hour: Int = 0, val minute: Int = 0, val second: Int = 0) {
    init { require(month in 1..12 && day in 1..30 && hour in 0..23 && minute in 0..59 && second in 0..59) }
    fun seconds(baseYear: Int): Long {
        require(year >= baseYear)
        return ((year.toLong() - baseYear) * 360 + (month - 1) * 30 + day - 1) * 86_400 + hour * 3600 + minute * 60 + second
    }
    companion object {
        fun parse(value: String): GameDate {
            val parts = value.split('T', limit = 2)
            val fields = parts[0].split('-').map(String::toInt)
            require(fields.size == 3)
            val time = if (parts.size == 2) parts[1].split(':').map(String::toInt) else listOf(0, 0, 0)
            require(time.size in 2..3)
            return GameDate(fields[0], fields[1], fields[2], time[0], time[1], time.getOrElse(2) { 0 })
        }
    }
}

data class SessionRules(val speed: Long, val capacity: Int, val cpIntervalSeconds: Long,
    val baseYear: Int, val start: GameDate, val end: GameDate,
    val strategyTickRealMillis: Long, val tacticsTickRealMillis: Long, val restartDelayRealMillis: Long) {
    val startSeconds = start.seconds(baseYear)
    val endSeconds = end.seconds(baseYear)
    init {
        require(speed in 1..1000 && capacity in 1..2000 && cpIntervalSeconds in 1..86_400)
        require(strategyTickRealMillis in 1..60_000 && tacticsTickRealMillis in 1..60_000)
        require(restartDelayRealMillis in 0..3_600_000)
        require(startSeconds < endSeconds && endSeconds <= 0xffffffffL)
    }
    companion object {
        fun load(input: InputStream = checkNotNull(SessionRules::class.java.getResourceAsStream("/rules/session.properties"))): SessionRules {
            val values = Properties().also { input.use(it::load) }
            fun value(key: String) = requireNotNull(values.getProperty(key)) { "Missing session rule $key" }
            return SessionRules(value("clock.speed").toLong(), value("session.capacity").toInt(),
                value("cp.interval_seconds").toLong(), value("calendar.base_year").toInt(),
                GameDate.parse(value("session.start_date")), GameDate.parse(value("session.end_date")),
                value("strategy.tick_real_millis").toLong(), value("tactics.tick_real_millis").toLong(), value("session.restart_delay_real_millis").toLong())
        }
    }
}
