package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brute-force equivalence for the subset-sum reachability filter: on random small
 * instances with random partial pins, the factor must report infeasibility exactly when
 * no completion hits the target, and every var it leaves open (or forces) must match the
 * brute support sets.
 */
class SubsetSumEqTest {

    @Test
    fun `matches brute force on random pinned instances`() {
        val rng = Random(7)
        repeat(200) {
            val n = 2 + rng.nextInt(6)
            val coeffs = IntArray(n) { 1 + rng.nextInt(9) }
            val target = 1 + rng.nextInt(coeffs.sum())
            val pins = IntArray(n) { rng.nextInt(3) } // 0 = free, 1 = pin 0, 2 = pin 1

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) {
                    when (pins[it]) {
                        1 -> IntDomain(0, 0)
                        2 -> IntDomain(1, 1)
                        else -> IntDomain(0, 1)
                    }
                },
                factors = arrayOf<Factor>(SubsetSumEq(IntArray(n) { it }, coeffs, target)),
            )
            val state = PropagationState(problem, Assumptions.None)
            val ok = problem.factors[0].propagate(state, 0)

            // Brute: enumerate completions of the pinned assignment.
            val support0 = BooleanArray(n)
            val support1 = BooleanArray(n)
            var feasible = false
            for (m in 0 until (1 shl n)) {
                var sum = 0
                var legal = true
                for (i in 0 until n) {
                    val v = (m shr i) and 1
                    if (pins[i] == 1 && v == 1) legal = false
                    if (pins[i] == 2 && v == 0) legal = false
                    sum += v * coeffs[i]
                }
                if (!legal || sum != target) continue
                feasible = true
                for (i in 0 until n) {
                    if ((m shr i) and 1 == 0) support0[i] = true else support1[i] = true
                }
            }

            val ctx = "coeffs=${coeffs.toList()} target=$target pins=${pins.toList()}"
            assertEquals(feasible, ok, "feasibility mismatch: $ctx")
            if (!feasible) return@repeat
            for (i in 0 until n) {
                val d = state.intDomains[i]
                val expectMin = if (support0[i]) 0 else 1
                val expectMax = if (support1[i]) 1 else 0
                assertEquals(expectMin, d.min, "min of var $i: $ctx")
                assertEquals(expectMax, d.max, "max of var $i: $ctx")
            }
        }
    }

    @Test
    fun `forces the unique completion`() {
        // 3 + 5 + 7 = 15 is the only subset hitting 15: all three vars must pin to 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(SubsetSumEq(intArrayOf(0, 1, 2), intArrayOf(3, 5, 7), 15)),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(problem.factors[0].propagate(state, 0))
        for (v in 0 until 3) assertEquals(1, state.intDomains[v].min, "var $v must be forced to 1")
    }
}
