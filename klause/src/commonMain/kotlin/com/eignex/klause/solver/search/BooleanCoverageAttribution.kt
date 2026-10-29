package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit

/**
 * Bounded exact-match attribution of entered Boolean regions. A duplicate is a lower bound on overlap,
 * not a refuted region or saved work. Scope, mapping and cutoff must match before regions are compared.
 * The caller serializes observations and discards the capture when its diagnostic invocation retires.
 */
class BooleanCoverageAttribution(
    /** Context whose coordinates the observations use. */
    val context: BooleanProofContext,
    private val maximumRegions: Int = 4096,
    private val maximumLiterals: Int = 65536,
    private val onEntry: (Int, BooleanCube) -> Unit = { _, _ -> },
) {
    private val firstOwner = HashMap<BooleanCube, Int>()
    private val firstRefutation = HashMap<BooleanCube, Int>()
    private var retainedLiterals = 0
    /** Number of admitted branch entries, including repeat entries. */
    var entries: Long = 0
        private set
    /** Entries whose exact region was already entered by another worker. */
    var duplicateEntries: Long = 0
        private set
    /** Entries repeated by the region's first worker, including restarts and retries. */
    var repeatedEntries: Long = 0
        private set
    /** Observations refused by the capture bounds. Refusals make the capture partial. */
    var declinedEntries: Long = 0
        private set
    /** Scoped native refutations whose exact region was already refuted by another worker. */
    var duplicateRefutations: Long = 0
        private set
    /** Distinct regions with reported native refutations; independent proof checking is separate. */
    val refutedRegions: Int get() = firstRefutation.size
    /** Number of retained distinct regions. */
    val regions: Int get() = firstOwner.size
    /** Retained literal payload bytes; excludes collections, object headers and solver state. */
    val literalBytes: Long get() = retainedLiterals * Int.SIZE_BYTES.toLong()

    init {
        require(maximumRegions in 1..65536 && maximumLiterals in 1..1048576)
    }

    /** Record an actual branch assertion in [observedContext]; incompatible contexts are refused. */
    fun record(observedContext: BooleanProofContext, worker: Int, cube: BooleanCube): Boolean {
        require(worker >= 0)
        if (observedContext != context) return false
        onEntry(worker, cube)
        val owner = firstOwner[cube]
        if (owner != null) {
            entries++
            if (owner == worker) repeatedEntries++ else duplicateEntries++
            return true
        }
        val size = cube.literals.size
        if (firstOwner.size + firstRefutation.size == maximumRegions || size > maximumLiterals - retainedLiterals) {
            declinedEntries++
            return false
        }
        firstOwner[cube] = worker
        retainedLiterals += size
        entries++
        return true
    }

    /**
     * Attribute an actual completed scoped native refutation. The caller validates result scope before
     * reporting it; an unknown, witness or cancelled traversal must never be reported here.
     */
    fun recordRefutation(observedContext: BooleanProofContext, worker: Int, cube: BooleanCube): Boolean {
        require(worker >= 0)
        if (observedContext != context) return false
        val owner = firstRefutation[cube]
        if (owner != null) {
            if (owner != worker) duplicateRefutations++
            return true
        }
        val size = cube.literals.size
        if (firstOwner.size + firstRefutation.size == maximumRegions || size > maximumLiterals - retainedLiterals) {
            declinedEntries++
            return false
        }
        firstRefutation[cube] = worker
        retainedLiterals += size
        return true
    }

    /**
     * Observe pure Boolean traversal attempts in a fixed context. [numBoolVars] must describe the
     * whole model with identity source Boolean coordinates and no hidden integer, real or theory
     * branches. Read a fresh context at
     * every attempt when objective authority can change. Incompatible epochs are refused.
     */
    fun observer(
        worker: Int, numBoolVars: Int, currentContext: () -> BooleanProofContext = { context },
    ): SearchBranchAttemptObserver {
        require(worker >= 0 && numBoolVars in 0..4096)
        return object : SearchBranchAttemptObserver {
            override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                if (decision !is SearchDecision.Bool || Lit.variable(decision.literal) !in 0 until numBoolVars) {
                    declinedEntries++
                    return
                }
                val literals = ArrayList<Int>()
                for (variable in 0 until numBoolVars) {
                    context.boolValue(variable)?.let { literals += Lit.make(variable, it) }
                }
                literals += decision.literal
                record(currentContext(), worker, BooleanCube(literals))
            }
        }
    }

    /** Release retained diagnostic payloads; counters remain available. */
    fun clear() {
        firstOwner.clear()
        firstRefutation.clear()
        retainedLiterals = 0
    }
}
