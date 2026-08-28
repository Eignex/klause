package com.eignex.klause.solver.result

/** Deterministic, solve-local accounting for complete open-theory search. */
data class OpenTheoryWorkStats(
    /** Committed shared Boolean decision frames. */
    val openBoolDecisions: Long = 0,
    /** Committed shared integer decision frames. */
    val openIntDecisions: Long = 0,
    /** Committed opaque theory decision frames. */
    val openTheoryDecisions: Long = 0,
    /** Accepted legacy theory-check allowance uses. */
    val openTheoryChecks: Long = 0,
    /**
     * General-LIA work visits: witness-bound import, shared-bound import, equality propagation,
     * feasibility/range checks, and final-row validation. Every increment is at one of those loops.
     */
    val openLiaRowVisits: Long = 0,
    /** Cooperative General-LIA cancellation polls. This is not useful-inference work. */
    val openCancellationPolls: Long = 0,
    /** Accounting-policy sum of committed decisions, checks, and General-LIA row visits. */
    val openWork: Long = 0,
)

/** Mutable allocation-free sink scoped to one open-theory solve. */
class OpenTheoryWorkSink(private val limit: Long = Long.MAX_VALUE) {
    private var boolDecisions = 0L
    private var intDecisions = 0L
    private var theoryDecisions = 0L
    private var theoryChecks = 0L
    private var liaRowVisits = 0L
    private var cancellationPolls = 0L
    private var work = 0L

    /** Whether a charged event found the fixed-work allowance already spent. */
    var exhausted: Boolean = false
        private set

    private fun consume(): Boolean {
        if (work >= limit) {
            exhausted = true
            return false
        }
        work++
        return true
    }

    /** Whether one more fixed-work event can be committed. */
    fun hasAllowance(): Boolean {
        if (work >= limit) exhausted = true
        return !exhausted
    }

    /** Charge one committed shared Boolean decision. */
    fun boolDecision(): Boolean = consume().also { if (it) boolDecisions++ }

    /** Charge one committed shared integer decision. */
    fun intDecision(): Boolean = consume().also { if (it) intDecisions++ }

    /** Charge one committed opaque theory decision. */
    fun theoryDecision(): Boolean = consume().also { if (it) theoryDecisions++ }

    /** Charge one accepted legacy theory check. */
    fun theoryCheck(): Boolean = consume().also { if (it) theoryChecks++ }

    /** Charge one General-LIA row visit. */
    fun liaRowVisit(): Boolean = consume().also { if (it) liaRowVisits++ }

    /** Record one cooperative General-LIA cancellation poll. */
    fun cancellationPoll() {
        cancellationPolls++
    }

    /** Return the immutable public snapshot. */
    fun snapshot(): OpenTheoryWorkStats = OpenTheoryWorkStats(
        boolDecisions,
        intDecisions,
        theoryDecisions,
        theoryChecks,
        liaRowVisits,
        cancellationPolls,
        work,
    )
}
