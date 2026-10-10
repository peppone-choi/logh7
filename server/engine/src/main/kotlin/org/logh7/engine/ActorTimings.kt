package org.logh7.engine

/** Durations use the independent metric clock, never simulation elapsed time. */
enum class ActorTiming {
    QUEUE_AGE, BACKPRESSURED_QUEUE_AGE, SEND_WAIT, TICK_WAKE_LAG, TICK_DEQUEUE_LAG
}

/** Optional observer. Implementations must be fast; exceptions are isolated by the engine. */
interface ActorTimings {
    fun record(metric: ActorTiming, nanoseconds: Long)
    fun clockRegression() {}
}
