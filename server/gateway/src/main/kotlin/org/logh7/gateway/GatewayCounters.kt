package org.logh7.gateway

import java.util.concurrent.atomic.AtomicLong

/**
 * Login, session-ticket, update and connection outcomes of one gateway since start, exported by the ops
 * /metrics for the repeated-failure alerts (deploy/prometheus/game-alerts.yml). No account names or
 * credentials are kept: only counts. Author: Peppone Choi.
 */
class GatewayCounters {
    val loginAccepted = AtomicLong()
    val loginRejected = AtomicLong()
    /** A session-port token that was unknown, expired, reused or bound to another session/generation. */
    val sessionTicketRejected = AtomicLong()
    /** Authenticated admission attempts refused because the account already owns a connection to this world. */
    val duplicateAdmissionRejected = AtomicLong()
    val updateCompleted = AtomicLong()
    /** An update exchange that started (sent its first frame) but closed before completing. */
    val updateFailed = AtomicLong()
    /** Connections closed because a frame or handler failed (protocol or handler errors). */
    val connectionErrors = AtomicLong()
    /** Clients disconnected because their pending world notifications passed 128 batches or 512 KiB. */
    val notificationOverflow = AtomicLong()
    /** World notification batches waiting behind a pending request, across all connections (a gauge). */
    val notificationsQueued = AtomicLong()

    /** Metric name (without the logh7_ prefix) to value, in a stable order. */
    fun snapshot(): Map<String, Long> = linkedMapOf(
        "login_accepted_total" to loginAccepted.get(), "login_rejected_total" to loginRejected.get(),
        "session_ticket_rejected_total" to sessionTicketRejected.get(),
        "duplicate_admission_rejected_total" to duplicateAdmissionRejected.get(),
        "update_completed_total" to updateCompleted.get(), "update_failed_total" to updateFailed.get(),
        "connection_errors_total" to connectionErrors.get(),
        "notification_overflow_total" to notificationOverflow.get())

    /** Gauges (not counters): exported separately so their Prometheus type is right. */
    fun gauges(): Map<String, Long> = linkedMapOf("notifications_queued" to notificationsQueued.get())
}
