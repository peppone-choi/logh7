package org.logh7.gateway

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Maintenance admission switch (checklist §14): while closed, new logins get the original client's login
 * failure reply; players already in the game stay connected until the planned restart. The original client
 * has no maintenance message on its login failure, so the operator announces it beforehand in game
 * ([GamePresence.operatorNotice]) and on the launcher/web notice (`evidence:guess`). Author: Peppone Choi.
 */
class MaintenanceGate {
    private val closed = AtomicBoolean()
    val refused = AtomicLong()
    fun closed(): Boolean = closed.get()
    /** Returns false when the gate was already in that state. */
    fun setClosed(value: Boolean): Boolean = closed.getAndSet(value) != value
    /** True (and counted) when a login must be refused now. */
    fun refuses(): Boolean = closed.get().also { if (it) refused.incrementAndGet() }
}
