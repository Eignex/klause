package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #623: [Linear] is driven by typed bound-event subscription. [propagateLinearBounds] reads only each
 * term's `min`/`max`, so it subscribes to `LB_RAISED`/`UB_LOWERED` and is not woken by interior
 * `VALUE_REMOVED` carves. The soundness risk is that an interior hole punched by a co-constraint
 * mid-search could (wrongly) be needed by the linear; the enumeration tests punch exactly such holes
 * and confirm the full solution set is unchanged, across LE/EQ and the value-excluding NE branch.
 */
class LinearBoundsEventTest {

    /** When [src] is fixed, carve its value out of [dst] — punches interior holes into the linear's
     *  variables mid-search. Plain occurrence wakeup (no event subscription), so it always fires. */
    private class ExcludeOnFix(val src: Int, val dst: Int) : Factor {
        override val boolVars: IntArray = IntArray(0)
        override val intVars: IntArray = intArrayOf(src, dst)

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
    }

    @Test
    fun `linear subscribes to only bound events on every term`() {
        val lin = Linear(intArrayOf(1, 2, -1), intArrayOf(0, 1, 2), LinearOp.LE, 5)
        val watches = lin.asPropagator().initialIntEventWatches!!
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        assertEquals(
            setOf(
                0 to IntEvent.LB_RAISED,
                0 to IntEvent.UB_LOWERED,
                1 to IntEvent.LB_RAISED,
                1 to IntEvent.UB_LOWERED,
                2 to IntEvent.LB_RAISED,
                2 to IntEvent.UB_LOWERED,
            ),
            pairs,
        )
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
            "linear bound propagation reads only min/max, so it must not subscribe to interior/fixed events",
        )
    }

    @Test
    fun `linear is dropped from occurrence wakeup on its vars`() {
        val lin = Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 6)
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 4) }, listOf(lin))
        for (v in 0..2) {
            assertTrue(problem.intOccurrences[v].contains(0), "factor still mentions var $v")
            assertFalse(
                problem.nonIntEventWatcherIntOccurrences[v].contains(0),
                "subscribed linear must be off the occurrence-wakeup list for var $v",
            )
        }
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { it.ints.toList() }.toHashSet()

    @Test
    fun `linear with interior holes punched mid-search enumerates exactly brute force`() {
        // For each op: a linear over x0,x1,x2 (0..4) plus a co-constraint that carves x3's fixed value
        // out of x0 and x1 — punching interior holes the linear is not woken for. Enumerated set must
        // equal brute force computed directly from the relation, proving the skipped wakes are sound.
        val hi = 4
        val cases = listOf(
            Triple(LinearOp.LE, 5) { a: Int, b: Int, c: Int -> a + b + c <= 5 },
            Triple(LinearOp.EQ, 6) { a: Int, b: Int, c: Int -> a + b + c == 6 },
            Triple(LinearOp.GE, 9) { a: Int, b: Int, c: Int -> a + b + c >= 9 },
            Triple(LinearOp.NE, 6) { a: Int, b: Int, c: Int -> a + b + c != 6 },
        )
        for ((op, bound, rel) in cases) {
            for (seed in 1L..4L) {
                val factors = listOf<Factor>(
                    Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), op, bound),
                    ExcludeOnFix(src = 3, dst = 0),
                    ExcludeOnFix(src = 3, dst = 1),
                )
                val problem = Problem(0, 4, Array(4) { IntDomain(0, hi) }, factors)
                val brute = HashSet<List<Int>>()
                val base = hi + 1
                for (m in 0 until base * base * base * base) {
                    val a = m % base
                    val b = (m / base) % base
                    val c = (m / (base * base)) % base
                    val d = m / (base * base * base)
                    if (rel(a, b, c) && a != d && b != d) brute.add(listOf(a, b, c, d))
                }
                assertEquals(
                    brute,
                    enumerate(problem, seed),
                    "op=$op seed=$seed: linear + interior holes must match brute",
                )
            }
        }
    }
}
