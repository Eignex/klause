package com.eignex.klause.solver.search

import com.eignex.klause.util.Cancellation

/** Why an exact search operation could not finish; none of these causes proves exhaustion. */
enum class SearchStopCause {
    /** The invocation deadline expired. */
    Timeout,
    /** The invocation or lifetime token was cancelled. */
    Cancelled,
    /** The containing native work allowance was spent. */
    WorkAllowance,
    /** The containing complete-check allowance was spent. */
    CheckAllowance,
    /** The containing decision allowance was spent. */
    DecisionAllowance,
    /** A policy-owned operation allowance expired before its containing invocation. */
    OperationAllowance,
    /** The component could not represent or certify the residual exactly. */
    RepresentationDecline,
}

/** Policy-owned accounting in native units, shared across every operation of its containing scope. */
interface SearchExecutionAccounting {
    /** Whether a further decision may enter its native owner. */
    fun canCommitDecision(): Boolean = true

    /** Charge one committed decision, without converting its native currency in search. */
    fun recordDecision(decision: SearchDecision): Boolean = true

    /** Charge one admitted complete check. */
    fun consumeCheck(): Boolean = true

    /** The containing native allowance has been exhausted. */
    val exhausted: Boolean get() = false

    /** Native reason for refusing an event; adapters can distinguish their own check and decision limits. */
    val stopCause: SearchStopCause? get() = if (exhausted) SearchStopCause.WorkAllowance else null

    /** Unmetered accounting. */
    data object Unlimited : SearchExecutionAccounting
}

/** Execution services available to components and alternate coordinators through [SearchContext]. */
interface SearchExecutionControls : SearchExecutionAccounting {
    /** Invocation token, retaining its deadline and caller work meter. */
    val cancellation: Cancellation

    /** Most recent unresolved stop, with containing cancellation and work limits taking precedence. */
    override val stopCause: SearchStopCause?

    /** Select a native operation allowance without renewing the containing allowance. */
    fun operationAllowance(parent: Cancellation): Cancellation = parent.shorten(0.5)

    /** Report a stopped or declined operation. */
    fun reportStop(cause: SearchStopCause)

    /** Begin another component operation; containing allowance stops remain owned by their scope. */
    fun beginOperation()
}

/** Injects caller cancellation, native accounting and policy-owned operation allowances. */
class SearchExecutionControlAdapter(
    override val cancellation: Cancellation = Cancellation.Never,
    private val accounting: SearchExecutionAccounting = SearchExecutionAccounting.Unlimited,
    private val allowance: (Cancellation) -> Cancellation = { it.shorten(0.5) },
) : SearchExecutionControls {
    private var cause: SearchStopCause? = null

    override val exhausted: Boolean get() = accounting.exhausted

    override val stopCause: SearchStopCause? get() = when {
        cancellation.deadline()?.hasPassedNow() == true -> SearchStopCause.Timeout
        cancellation() -> SearchStopCause.Cancelled
        accounting.stopCause != null -> accounting.stopCause
        else -> cause
    }

    override fun canCommitDecision(): Boolean = accounting.canCommitDecision().also {
        if (!it) reportStop(accounting.stopCause ?: SearchStopCause.DecisionAllowance)
    }

    override fun recordDecision(decision: SearchDecision): Boolean = accounting.recordDecision(decision).also {
        if (!it) reportStop(accounting.stopCause ?: SearchStopCause.DecisionAllowance)
    }

    override fun consumeCheck(): Boolean = accounting.consumeCheck().also {
        if (!it) reportStop(accounting.stopCause ?: SearchStopCause.CheckAllowance)
    }

    override fun operationAllowance(parent: Cancellation): Cancellation = allowance(parent)

    override fun reportStop(cause: SearchStopCause) {
        if ((this.cause == SearchStopCause.WorkAllowance || this.cause == SearchStopCause.CheckAllowance ||
                this.cause == SearchStopCause.DecisionAllowance) &&
            (cause == SearchStopCause.OperationAllowance || cause == SearchStopCause.RepresentationDecline)
        ) return
        this.cause = cause
    }

    override fun beginOperation() {
        if (cause == SearchStopCause.OperationAllowance || cause == SearchStopCause.RepresentationDecline) cause = null
    }
}
