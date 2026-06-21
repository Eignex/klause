package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gate for the per-factor dirty-variable delta substrate (#624): the engine accumulates the
 * subscribed variables that fired since a [Factor.consumesIntEventDelta] consumer last drained, and
 * [PropagationState.drainIntEventDirtyVars] hands them over. The soundness-critical property is that
 * the delivered set is a *superset* of "changed since last fire" across deep backtracking — never
 * missing a change. [DeltaAllDifferent] enforces distinctness using *only* the delivered delta to
 * decide which variables to re-examine each fire; if the engine ever dropped a variable that became
 * fixed, a clash would slip through and the enumerated set would exceed the brute-force distinct set.
 */
class IntEventDeltaTest {

    /**
     * Forward-checking all-different driven entirely by the dirty-variable delta: on each fire it
     * looks only at the variables the engine reports as changed (all variables on the first,
     * full-propagation fire) and, for any that is now a singleton, removes its value from the others.
     * Subscribes to every kind on every variable, so the delta is a sound superset of its changes.
     */
    private class DeltaAllDifferent(override val intVars: IntArray) :
        Factor,
        Propagator {
        override val boolVars: IntArray = IntArray(0)
        override val consumesIntEventDelta: Boolean = true
        override val initialIntEventWatches: IntArray = IntArray(intVars.size * IntEvent.COUNT).also { out ->
            var w = 0
            for (v in intVars) {
                out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
                out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
                out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
                out[w++] = IntEvent.pack(v, IntEvent.FIXED)
            }
        }

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            // Always drain (keeps the accumulator clean); the first fire (no payload yet, at bake /
            // level 0) examines all variables, every later fire only the delivered delta.
            val firstFire = state.refPayload[factorId] == null
            if (firstFire) state.refPayload[factorId] = Unit
            val delta = state.drainIntEventDirtyVars(factorId)
            val toCheck = if (firstFire) intVars else delta
            for (v in toCheck) {
                val d = state.intDomains[v]
                if (d.min != d.max) continue // not fixed
                val value = d.min
                for (u in intVars) {
                    if (u != v && !state.excludeIntValue(u, value)) return false
                }
            }
            return true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
            DeltaAllDifferent(IntArray(intVars.size) { intMap[intVars[it]] })

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {
            override val boolVars get() = this@DeltaAllDifferent.boolVars
            override val intVars get() = this@DeltaAllDifferent.intVars
        }
    }

    /** Carves [src]'s fixed value out of [dst] when [src] is fixed — punches interior holes that
     *  show up as extra `VALUE_REMOVED` entries in the consumer's delta (which it must tolerate). */
    private class ExcludeOnFix(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {
            override val boolVars get() = this@ExcludeOnFix.boolVars
            override val intVars get() = this@ExcludeOnFix.intVars
        }
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { it.ints.toList() }.toHashSet()

    @Test
    fun `delta-driven alldifferent enumerates exactly the brute-force distinct set`() {
        // n vars over 0..n-1: the delta-driven consumer must reject every non-distinct assignment, so
        // the enumerated set is exactly the permutations. A dropped delivery would admit a clash.
        for (n in 3..5) {
            val problem = Problem(0, n, Array(n) { IntDomain(0, n - 1) }, listOf(DeltaAllDifferent(IntArray(n) { it })))
            val brute = HashSet<List<Int>>()
            fun rec(i: Int, acc: IntArray, used: BooleanArray) {
                if (i == n) {
                    brute.add(acc.toList())
                    return
                }
                for (v in 0 until n) {
                    if (used[v]) continue
                    used[v] = true
                    acc[i] = v
                    rec(i + 1, acc, used)
                    used[v] = false
                }
            }
            rec(0, IntArray(n), BooleanArray(n))
            assertEquals(brute, enumerate(problem, n.toLong()), "n=$n: delta-driven alldiff must equal permutations")
        }
    }

    @Test
    fun `delta-driven alldifferent stays sound with a co-constraint punching interior holes`() {
        // x0..x2 distinct over 0..3 via the delta consumer, plus x3 whose fixing carves its value out
        // of x0/x1 — flooding the consumer's delta with extra VALUE_REMOVED entries it must tolerate.
        for (seed in 1L..5L) {
            val factors = listOf<Factor>(
                DeltaAllDifferent(intArrayOf(0, 1, 2)),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, factors)
            val brute = HashSet<List<Int>>()
            for (a in 0..3) {
                for (b in 0..3) {
                    for (c in 0..3) {
                        for (d in 0..3) {
                            if (a != b && a != c && b != c && a != d && b != d) brute.add(listOf(a, b, c, d))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: delta consumer + holes must match brute")
        }
    }
}
