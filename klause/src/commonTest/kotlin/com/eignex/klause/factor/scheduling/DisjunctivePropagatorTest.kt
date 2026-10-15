package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisjunctivePropagatorTest {

    @Test
    fun `disjunctive deductions over optional tasks should be implied by their reasons`() {
        val rng = Random(0xD15)
        repeat(100) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = n,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 6) },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 2L + rng.nextInt(2) },
                        presents = IntArray(n) { Lit.make(it, true) },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "disjunctive#$iter") { state ->
                (0 until n).all { b -> rng.nextInt(3) != 0 || state.pinBool(b, rng.nextBoolean()) } &&
                    (0 until 3).all {
                        val v = rng.nextInt(n)
                        if (rng.nextBoolean()) {
                            state.tightenIntMax(v, rng.nextInt(4).toLong())
                        } else {
                            state.tightenIntMin(v, 1L + rng.nextInt(4))
                        }
                    }
            }
        }
    }

    @Test
    fun `disjunctive deductions over duration variables should be implied by their reasons`() {
        val rng = Random(0xD16)
        repeat(40) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * n,
                intDomains = Array(2 * n) { if (it < n) IntDomain(0, 4) else IntDomain(1, 3) },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 1L },
                        durationVars = IntArray(n) { n + it },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "disjunctive-durations#$iter") { state ->
                (0 until n).all {
                    val d = 1L + rng.nextInt(3)
                    state.tightenIntMin(n + it, d) && state.tightenIntMax(n + it, d)
                } &&
                    (0 until 3).all {
                        val v = rng.nextInt(n)
                        when (rng.nextInt(3)) {
                            0 -> state.excludeIntValue(v, rng.nextInt(5).toLong())
                            1 -> state.tightenIntMin(v, 1L + rng.nextInt(4))
                            else -> state.tightenIntMax(v, rng.nextInt(5).toLong())
                        }
                    }
            }
        }
    }

    @Test
    fun `an edge-finding bound should cite only the cluster it follows`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2, 3), durations = longArrayOf(2, 2, 2, 2)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 3) && state.tightenIntMax(1, 3) && state.tightenIntMax(2, 4))
        check(state.tightenIntMin(3, 7))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[2])!!.map { state.atoms.intVar[Lit.variable(it)] }.toSet()
        assertEquals(setOf(0, 1), cited)
    }

    @Test
    fun `energetic-window conflict reason should cite only the tasks packed into the overloaded window`() {
        // Three unit tasks squeezed into [0, 2) overload without any compulsory overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2, 3), durations = longArrayOf(1, 1, 1, 1)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1))
        check(state.tightenIntMax(1, 1))
        check(state.tightenIntMax(2, 1))
        check(state.tightenIntMin(3, 5))
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))
        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it in setOf(0, 1, 2) }, "$citedVars")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-energetic")
    }

    @Test
    fun `mutual-precedence conflict reason should be a sound nogood citing only the two tasks`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(3, 3, 3))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 2))
        check(state.tightenIntMax(1, 2))
        check(state.tightenIntMin(2, 6))
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))
        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it == 0 || it == 1 }, "$citedVars")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-precedence")
    }

    @Test
    fun `pairwise detectable precedence should push the earliest start`() {
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(3, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "$result")
        val unsatPin = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(unsatPin is PropagationResult.Unsat, "$unsatPin")
        val okPin = problem.propagate(Assumptions(ints = mapOf(1 to 3)))
        assertTrue(okPin is PropagationResult.Implied, "$okPin")
    }

    @Test
    fun `edge-finding should pin a task forced to come last by an energetic overflow`() {
        // 0 + 2 + 4 > lct({0, 1}) = 5 puts task 2 after the cluster; no single pair forces it.
        val factor = Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(2, 2, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(4, result.ints[2], "implied=${result.ints}")
    }

    @Test
    fun `pure pairwise infeasibility should be detected`() {
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(1, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(5, 5), IntDomain(5, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Unsat, "$result")
    }
}
