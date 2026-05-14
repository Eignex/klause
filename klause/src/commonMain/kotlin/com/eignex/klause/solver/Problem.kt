package com.eignex.klause.solver

/**
 * Immutable solver-side problem. Variables come in two id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [Assignment].
 *
 * Each integer variable has an [IntDomain] for bounds. Factors mention either or both.
 * Occurrence lists are split per kind so `flip(boolVar)` and `setInt(intVar)` only walk the
 * factors mentioning that specific variable.
 */
class Problem(
    val numBoolVars: Int,
    val numIntVars: Int,
    val intDomains: Array<IntDomain>,
    val factors: List<Factor>,
) {
    init {
        require(intDomains.size == numIntVars) {
            "intDomains size ${intDomains.size} != numIntVars $numIntVars"
        }
    }

    val boolOccurrences: Array<IntArray> = invert(numBoolVars) { it.boolVars }
    val intOccurrences: Array<IntArray> = invert(numIntVars) { it.intVars }

    /**
     * For each factor, the ids of every other factor sharing at least one variable.
     * Used by clause-weighting strategies (DDFW) to find candidate weight donors.
     */
    val factorNeighbors: Array<IntArray> = Array(factors.size) { fid ->
        val seen = HashSet<Int>()
        val f = factors[fid]
        for (v in f.boolVars) for (o in boolOccurrences[v]) if (o != fid) seen.add(o)
        for (v in f.intVars) for (o in intOccurrences[v]) if (o != fid) seen.add(o)
        seen.toIntArray()
    }

    val numFactors: Int get() = factors.size

    /**
     * Result of running [propagate] once with empty assumptions at construction time. Caches
     * literals/values forced by the constraints alone — every solver call gets a smaller
     * residual problem with no per-call propagation cost, and trivially-Unsat problems surface
     * here instead of after a full search budget. May be [PropagationResult.Unsat] for
     * trivially-infeasible problems; callers that want fail-fast behavior can check this.
     */
    val baked: PropagationResult = propagate(Assumptions.None)

    /**
     * Run sound-but-incomplete deductive propagation against [assumptions]. Each factor's
     * [Factor.propagate] is invoked to fixed point; pins / domain tightenings cascade through
     * the occurrence lists. Returns the literals/values forced *beyond* [assumptions] (disjoint
     * from the input), or [PropagationResult.Unsat] if a contradiction is derived.
     *
     * This is the same routine the solver uses internally at init and at every sample / solve
     * call that carries non-empty assumptions.
     */
    /**
     * Best-effort conflict-set minimisation: when [assumptions] is jointly infeasible, drops
     * each member in turn and re-propagates without it. Members whose removal still yields
     * Unsat were non-essential. Worst case `O(|conflict| × propagate)`. Off by default —
     * callers that care about minimal cores (conflict-directed backjumping in a bandit's
     * greedy descent, for instance) pass `minimizeConflict = true`.
     */
    fun propagate(
        assumptions: Assumptions,
        minimizeConflict: Boolean,
    ): PropagationResult {
        val first = propagate(assumptions)
        if (!minimizeConflict || first !is PropagationResult.Unsat) return first
        return minimiseConflict(assumptions, first)
    }

    private fun minimiseConflict(
        assumptions: Assumptions,
        seed: PropagationResult.Unsat,
    ): PropagationResult.Unsat {
        var bools = seed.conflictBools.toMutableSet()
        var ints = seed.conflictInts.toMutableSet()
        // Sweep bools.
        val boolsSnapshot = bools.toList()
        for (b in boolsSnapshot) {
            if (b !in bools) continue
            val trial = buildAssumptions(assumptions, bools - b, ints)
            val r = propagate(trial)
            if (r is PropagationResult.Unsat) {
                bools.remove(b)
                // Tighten the conflict to whatever the deeper propagate returned (it may
                // already exclude more members than just `b`).
                bools = bools.intersect(r.conflictBools).toMutableSet()
                ints = ints.intersect(r.conflictInts).toMutableSet()
            }
        }
        val intsSnapshot = ints.toList()
        for (i in intsSnapshot) {
            if (i !in ints) continue
            val trial = buildAssumptions(assumptions, bools, ints - i)
            val r = propagate(trial)
            if (r is PropagationResult.Unsat) {
                ints.remove(i)
                bools = bools.intersect(r.conflictBools).toMutableSet()
                ints = ints.intersect(r.conflictInts).toMutableSet()
            }
        }
        return PropagationResult.Unsat(bools, ints)
    }

    /** Restrict [base] to only the variables in [keepBools] / [keepInts]. */
    private fun buildAssumptions(
        base: Assumptions,
        keepBools: Set<Int>,
        keepInts: Set<Int>,
    ): Assumptions = Assumptions(
        bools = base.bools.filterKeys { it in keepBools },
        ints = base.ints.filterKeys { it in keepInts },
    )

    fun propagate(assumptions: Assumptions = Assumptions.None): PropagationResult {
        val state = PropagationState(this, assumptions)
        if (!state.seeded) {
            return PropagationResult.Unsat(
                state.extractConflictBools(state.conflictReason),
                state.extractConflictInts(state.conflictReason),
            )
        }

        // Initial worklist: every factor (the seeded vars may not cover everything, and the
        // baked-once-at-init pass needs all of them anyway).
        val pending = BooleanArray(numFactors) { true }
        val queue: ArrayDeque<Int> = ArrayDeque(numFactors)
        for (fid in 0 until numFactors) queue.addLast(fid)

        while (queue.isNotEmpty()) {
            val fid = queue.removeFirst()
            pending[fid] = false
            val f = factors[fid]
            state.currentReason = state.reasonForVars(f.boolVars, f.intVars)
            state.conflictReason = null
            if (!f.propagate(state, fid)) {
                val r = state.conflictReason ?: state.currentReason
                return PropagationResult.Unsat(
                    state.extractConflictBools(r),
                    state.extractConflictInts(r),
                )
            }

            // Drain whatever the factor dirtied; enqueue every other factor touching those vars.
            while (true) {
                val v = state.pollDirtyBool()
                if (v < 0) break
                for (other in boolOccurrences[v]) {
                    if (!pending[other]) { pending[other] = true; queue.addLast(other) }
                }
            }
            while (true) {
                val v = state.pollDirtyInt()
                if (v < 0) break
                for (other in intOccurrences[v]) {
                    if (!pending[other]) { pending[other] = true; queue.addLast(other) }
                }
            }
        }

        // Diff against input: only emit newly-forced facts.
        val bools = HashMap<Int, Boolean>()
        for (v in 0 until numBoolVars) {
            val b = state.boolValues[v] ?: continue
            if (assumptions.bools[v] == b) continue
            bools[v] = b
        }
        val ints = HashMap<Int, Int>()
        for (v in 0 until numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (assumptions.ints[v] == d.min) continue
                ints[v] = d.min
            }
        }
        return PropagationResult.Implied(bools, ints)
    }

    private inline fun invert(slots: Int, vars: (Factor) -> IntArray): Array<IntArray> {
        val counts = IntArray(slots)
        for (f in factors) for (v in vars(f)) counts[v]++
        val out = Array(slots) { IntArray(counts[it]) }
        val cursor = IntArray(slots)
        factors.forEachIndexed { id, f ->
            for (v in vars(f)) out[v][cursor[v]++] = id
        }
        return out
    }
}
