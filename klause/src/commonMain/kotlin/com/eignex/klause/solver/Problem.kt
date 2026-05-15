package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Immutable solver-side problem. Variables come in three id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [Assignment].
 *  - Float vars: ids `[0, numFloatVars)`, IEEE-754 doubles in solutions. Each has a
 *    [FloatInterval] domain. Native-float backends (Z3) reason over them directly;
 *    bucketing backends (bit-blaster, LogicNG, currently LS / Backtrack) lower them
 *    to bounded ints at the boundary via a `FloatLowering` pass.
 *
 * Factors declare which vars they touch; occurrence lists are split per kind so
 * propagation only walks the factors mentioning the changed variable.
 */
class Problem(
    val numBoolVars: Int,
    val numIntVars: Int,
    val intDomains: Array<IntDomain>,
    val factors: List<Factor>,
    val numFloatVars: Int = 0,
    val floatDomains: Array<FloatInterval> = emptyArray(),
    /**
     * Opt-in failed-literal probing at bake time. When `true`, every free bool variable is
     * tested with both polarities: if pinning one polarity propagates Unsat, the other
     * polarity is permanently folded into [baked]. Iterated to a fixed point. Cost is
     * `O(numFreeBools × propagate)` once at construction; the result is a tighter baseline
     * for every subsequent session. Off by default — tests construct many small problems
     * and don't want the construction overhead.
     */
    val probeFailedLiterals: Boolean = false,
) {
    init {
        require(intDomains.size == numIntVars) {
            "intDomains size ${intDomains.size} != numIntVars $numIntVars"
        }
        require(floatDomains.size == numFloatVars) {
            "floatDomains size ${floatDomains.size} != numFloatVars $numFloatVars"
        }
    }

    val boolOccurrences: Array<IntArray> = invert(numBoolVars) { it.boolVars }
    val intOccurrences: Array<IntArray> = invert(numIntVars) { it.intVars }
    val floatOccurrences: Array<IntArray> = invert(numFloatVars) { it.floatVars }

    /**
     * For each factor, the ids of every other factor sharing at least one variable.
     * Used by clause-weighting strategies (DDFW) to find candidate weight donors.
     */
    val factorNeighbors: Array<IntArray> = Array(factors.size) { fid ->
        val seen = HashSet<Int>()
        val f = factors[fid]
        for (v in f.boolVars) for (o in boolOccurrences[v]) if (o != fid) seen.add(o)
        for (v in f.intVars) for (o in intOccurrences[v]) if (o != fid) seen.add(o)
        for (v in f.floatVars) for (o in floatOccurrences[v]) if (o != fid) seen.add(o)
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
    val baked: PropagationResult = computeBaked()

    private fun computeBaked(): PropagationResult {
        val initial = propagate(Assumptions.None)
        if (!probeFailedLiterals || initial is PropagationResult.Unsat) return initial
        return probeFreeBools(initial as PropagationResult.Implied)
    }

    /**
     * Iteratively strengthens [initial] by probing each free bool with both polarities. If
     * one polarity is Unsat under the current accumulated base, the opposite polarity is
     * forced. Repeats until a full pass finds no new forcings.
     */
    private fun probeFreeBools(initial: PropagationResult.Implied): PropagationResult {
        val bools = HashMap(initial.bools)
        val ints = HashMap(initial.ints)
        var changed = true
        while (changed) {
            changed = false
            for (v in 0 until numBoolVars) {
                if (v in bools) continue
                val tryTrue = propagate(Assumptions(bools + (v to true), ints))
                if (tryTrue is PropagationResult.Unsat) {
                    val r = propagate(Assumptions(bools + (v to false), ints))
                    if (r is PropagationResult.Unsat) return r
                    foldInto(bools, ints, v, false, r as PropagationResult.Implied)
                    changed = true
                    continue
                }
                val tryFalse = propagate(Assumptions(bools + (v to false), ints))
                if (tryFalse is PropagationResult.Unsat) {
                    val r = propagate(Assumptions(bools + (v to true), ints))
                    if (r is PropagationResult.Unsat) return r
                    foldInto(bools, ints, v, true, r as PropagationResult.Implied)
                    changed = true
                }
            }
        }
        return PropagationResult.Implied(bools, ints)
    }

    private fun foldInto(
        bools: HashMap<Int, Boolean>,
        ints: HashMap<Int, Int>,
        forcedVar: Int,
        forcedValue: Boolean,
        implied: PropagationResult.Implied,
    ) {
        bools[forcedVar] = forcedValue
        for ((k, b) in implied.bools) bools[k] = b
        for ((k, i) in implied.ints) ints[k] = i
    }

    /**
     * Run sound-but-incomplete deductive propagation against [assumptions]. Each factor's
     * [Factor.propagate] is invoked to fixed point; pins / domain tightenings cascade through
     * the occurrence lists. Returns the literals/values forced *beyond* [assumptions] (disjoint
     * from the input), or [PropagationResult.Unsat] if a contradiction is derived.
     *
     * This is the same routine the solver uses internally at init and at every sample / solve
     * call that carries non-empty assumptions.
     */
    fun propagate(assumptions: Assumptions = Assumptions.None): PropagationResult {
        val state = PropagationState(this, assumptions)
        if (!state.seeded) {
            val lvls = state.conflictLevels ?: emptySet()
            return PropagationResult.Unsat(
                state.extractConflictBools(lvls),
                state.extractConflictInts(lvls),
                lvls,
            )
        }
        val conflict = state.runToFixpoint(allFactors = true)
        if (conflict != null) {
            return PropagationResult.Unsat(
                state.extractConflictBools(conflict),
                state.extractConflictInts(conflict),
                conflict,
            )
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
