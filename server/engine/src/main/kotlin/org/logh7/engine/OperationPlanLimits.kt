package org.logh7.engine

enum class OperationPlanVerdict {
    ALLOWED,
    EXISTING_PLANS_UNSUSTAINABLE,
    DUPLICATE_TARGET_FOR_CARD,
    UNIT_TOTAL_EXCEEDED,
}

/**
 * Pure planning limits and force-assignment timing from manual W PDF p.39 (R2–R5).
 * Target duplication is forbidden even for different purposes or activation times;
 * total committed units include both pending and active plans.
 *
 * U3: interpreting unsustainable plans as faction-wide committed > effective units,
 * and prioritizing shortage before duplicate target before total excess, are approved
 * design interpretations, not rules whose exact criteria/order the manual establishes.
 * Effective-unit eligibility and faction/card aggregation belong to the caller.
 * No plan lifecycle, department authority, or actual force assignment is performed.
 */
object OperationPlanLimits {
    /**
     * [cardTargets] contains this card's pending/active target IDs, excluding withdrawn
     * and ended plans. [committedUnits] is the faction-wide pending/active unit total;
     * [effectiveUnits] is the caller-defined faction-wide effective unit total.
     * Inputs are validated before any verdict and are never modified.
     *
     * @throws IllegalArgumentException for blank IDs, requested units < 1, or negative totals.
     * @throws ArithmeticException if the total overflows after shortage/duplicate checks.
     */
    fun canPlan(
        cardTargets: Set<String>,
        target: String,
        requestedUnits: Int,
        committedUnits: Int,
        effectiveUnits: Int,
    ): OperationPlanVerdict {
        require(target.isNotBlank()) { "target must not be blank" }
        require(cardTargets.all { it.isNotBlank() }) { "cardTargets must not contain blank IDs" }
        require(requestedUnits >= 1) { "requestedUnits must be positive" }
        if (isUnsustainable(committedUnits, effectiveUnits)) {
            return OperationPlanVerdict.EXISTING_PLANS_UNSUSTAINABLE
        }
        if (target in cardTargets) return OperationPlanVerdict.DUPLICATE_TARGET_FOR_CARD
        if (Math.addExact(committedUnits, requestedUnits) > effectiveUnits) {
            return OperationPlanVerdict.UNIT_TOTAL_EXCEEDED
        }
        return OperationPlanVerdict.ALLOWED
    }

    /** R4 / U3: equality is sustainable; negative totals are invalid. */
    fun isUnsustainable(committedUnits: Int, effectiveUnits: Int): Boolean {
        require(committedUnits >= 0) { "committedUnits must not be negative" }
        require(effectiveUnits >= 0) { "effectiveUnits must not be negative" }
        return committedUnits > effectiveUnits
    }

    /** R5 / approved U4 boundary: assignment is allowed at or after activation, not before. */
    fun mayAssignForces(nowGameSeconds: Long, activationGameSeconds: Long): Boolean {
        require(nowGameSeconds >= 0) { "nowGameSeconds must not be negative" }
        require(activationGameSeconds >= 0) { "activationGameSeconds must not be negative" }
        return nowGameSeconds >= activationGameSeconds
    }
}
