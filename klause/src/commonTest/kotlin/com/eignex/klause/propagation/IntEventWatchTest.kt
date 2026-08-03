package com.eignex.klause.propagation

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gate for the typed int-domain event substrate (#621): a factor that opts into
 * [Factor.initialIntEventWatches] is woken *only* for the subscribed `(var, kind)` events and is
 * dropped from the occurrence-list wakeup on those variables. The hazard is a *missed* wake — a
 * subscription too narrow to keep the factor correct, or an engine bug that drops a kind bit, lets
 * an invalid assignment through (or prunes a valid one). Enumerating the full solution set under the
 * CDCL backtracker, which fires `propagate` across deep push/pop, makes any such miss show up as a
 * mismatch against brute force. A selectivity check confirms the complementary property — that an
 * unsubscribed kind genuinely does *not* wake the factor.
 */
class IntEventWatchTest {

    /** `x < y` by bounds only: `x.max ≤ y.max − 1`, `y.min ≥ x.min + 1`. Subscribes to exactly the
     *  two events that can enable propagation — `x`'s lower bound rising (pushes `y.min`) and `y`'s
     *  upper bound falling (pushes `x.max`) — and ignores interior holes, which a bounds-consistent
     *  `<` cannot act on. This complete-but-minimal subscription is what the enumeration validates. */
    private class StrictLessThan(val x: Int, val y: Int) :
        Factor,
        Propagator {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(x, y)
        override val initialIntEventWatches: IntArray =
            intArrayOf(IntEvent.pack(x, IntEvent.LB_RAISED), IntEvent.pack(y, IntEvent.UB_LOWERED))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            if (!state.tightenIntMax(x, state.intDomains[y].max - 1)) return false
            return state.tightenIntMin(y, state.intDomains[x].min + 1)
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = StrictLessThan(intMap[x], intMap[y])

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    /** `x ≠ y`, propagating only on assignment: when one side is fixed, carve its value from the
     *  other. Subscribes to [IntEvent.FIXED] on both variables — exercising the FIXED path and the
     *  value-removal it triggers downstream. */
    private class Disequal(val x: Int, val y: Int) :
        Factor,
        Propagator {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(x, y)
        override val initialIntEventWatches: IntArray =
            intArrayOf(IntEvent.pack(x, IntEvent.FIXED), IntEvent.pack(y, IntEvent.FIXED))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val dx = state.intDomains[x]
            if (dx.min == dx.max && !state.excludeIntValue(y, dx.min)) return false
            val dy = state.intDomains[y]
            if (dy.min == dy.max && !state.excludeIntValue(x, dy.min)) return false
            return true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Disequal(intMap[x], intMap[y])

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()

    @Test
    fun `strict-less-than chain via bound events enumerates exactly the brute-force set`() {
        // x0 < x1 < x2 < x3 over 0..4: a tighten on one link must cascade through the typed-event
        // wakeups to the next. A dropped wake would leave a link unpropagated and admit a bad tuple.
        val n = 4
        val hi = 4
        val factors = (0 until n - 1).map { StrictLessThan(it, it + 1) as Factor }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, hi.toLong()) },
            factors = factors,
        )
        val brute = HashSet<List<Int>>()
        fun rec(i: Int, acc: IntArray) {
            if (i == n) {
                if ((0 until n - 1).all { acc[it] < acc[it + 1] }) brute.add(acc.toList())
                return
            }
            for (v in 0..hi) {
                acc[i] = v
                rec(i + 1, acc)
            }
        }
        rec(0, IntArray(n))
        assertEquals(brute, enumerate(problem, 1L), "strict-less chain enumeration must equal brute force")
    }

    @Test
    fun `disequality clique via fixed events enumerates exactly the brute-force set`() {
        // All-pairs x0≠x1≠x2 over 0..2 (an implicit AllDifferent built from Disequal advisors that
        // only fire on FIXED). The solution set is the permutations; a missed FIXED wake would admit
        // a collision.
        val n = 3
        val hi = 2
        val factors = ArrayList<Factor>()
        for (i in 0 until n) for (j in i + 1 until n) factors.add(Disequal(i, j))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, hi.toLong()) },
            factors = factors,
        )
        val brute = HashSet<List<Int>>()
        fun rec(i: Int, acc: IntArray) {
            if (i == n) {
                val pairsOk = (0 until n).all { a -> (a + 1 until n).all { b -> acc[a] != acc[b] } }
                if (pairsOk) brute.add(acc.toList())
                return
            }
            for (v in 0..hi) {
                acc[i] = v
                rec(i + 1, acc)
            }
        }
        rec(0, IntArray(n))
        assertEquals(brute, enumerate(problem, 7L), "disequality-clique enumeration must equal brute force")
    }

    /** Counts how often [propagate] runs; otherwise a no-op so a wake never cascades. */
    private class WakeCounter(val v: Int, val kind: Int) :
        Factor,
        Propagator {
        var fires: Int = 0
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(v)
        override val initialIntEventWatches: IntArray = intArrayOf(IntEvent.pack(v, kind))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            fires++
            return true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = WakeCounter(intMap[v], kind)

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    @Test
    fun `subscriber wakes only on its subscribed kind`() {
        val counter = WakeCounter(v = 0, kind = IntEvent.LB_RAISED)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = listOf(counter),
        )
        val session = PropagationSession(problem)
        session.seed(Assumptions.None) // full-propagation bake fires every factor once
        val baseline = counter.fires

        session.pinIntAtMost(0, 3) // upper bound lowered — NOT an LB_RAISED, must not wake
        assertEquals(baseline, counter.fires, "UB-lowered must not wake an LB_RAISED-only subscriber")

        session.pinIntAtLeast(0, 2) // lower bound raised — the subscribed kind, must wake exactly once
        assertEquals(baseline + 1, counter.fires, "LB-raised must wake the subscriber exactly once")
    }
}
