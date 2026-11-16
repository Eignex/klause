package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NValuePropagatorTest {

    private fun nvalueProblem(xsDomains: Array<IntDomain>, nDomain: IntDomain, mode: NValue.Mode): Problem {
        val k = xsDomains.size
        return Problem(
            numBoolVars = 0,
            numIntVars = k + 1,
            intDomains = xsDomains + nDomain,
            factors = arrayOf<Factor>(NValue(n = k, xs = IntArray(k) { it }, mode = mode)),
        )
    }

    @Test
    fun `excepted values should not contribute to count bounds`() {
        for (mode in NValue.Mode.entries) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(IntDomain(0, 0), IntDomain(2, 2), IntDomain(2, 2), IntDomain(0, 3)),
                factors = arrayOf(NValue(3, intArrayOf(0, 1, 2), mode, exceptSet = longArrayOf(0))),
            )
            val state = PropagationState(problem, Assumptions.None)
            state.currentFactor = 0

            assertTrue(problem.propagators[0].propagate(state, 0))
            assertEquals(if (mode == NValue.Mode.AtLeast) 0L else 1L, state.intDomains[3].min)
            assertEquals(if (mode == NValue.Mode.AtMost) 3L else 1L, state.intDomains[3].max)
        }
    }

    @Test
    fun `an optional or excepted value should not force a positive count`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(2, 2), IntDomain(0, 0)),
            factors = arrayOf(NValue(2, intArrayOf(0, 1), presents = intArrayOf(
                Lit.make(0, true), Lit.make(0, false),
            ), exceptSet = longArrayOf(0, 1, 2))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(0L, state.intDomains[2].max)
    }

    @Test
    fun `excepted count deductions should retain their domain premises`() {
        for (mode in NValue.Mode.entries) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                factors = arrayOf(NValue(2, intArrayOf(0, 1), mode, exceptSet = longArrayOf(0))),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "excepted-$mode") { state ->
                state.excludeIntValue(0, 0) && state.setInt(1, 0)
            }
        }
    }

    @Test
    fun `equal minimum signed word values should require only one distinct value`() {
        for (mode in listOf(NValue.Mode.Eq, NValue.Mode.AtMost)) {
            val problem = nvalueProblem(
                xsDomains = Array(2) { IntDomain(Long.MIN_VALUE, Long.MIN_VALUE) },
                nDomain = IntDomain(1, 1),
                mode = mode,
            )
            val state = PropagationState(problem, Assumptions.None)
            state.currentFactor = 0

            assertTrue(problem.propagators[0].propagate(state, 0), "mode=$mode")
        }
    }

    @Test
    fun `two distinct values should exclude a third value beside the minimum signed word endpoint`() {
        val problem = nvalueProblem(
            xsDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE), IntDomain(0, 0), IntDomain(0, 1)),
            nDomain = IntDomain(2, 2),
            mode = NValue.Mode.AtMost,
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(0L, state.intDomains[2].max)
    }

    @Test
    fun `atleast nvalues should force a variable pinned by the maximum matching`() {
        val problem = nvalueProblem(
            xsDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 1)),
            nDomain = IntDomain(2, 2),
            mode = NValue.Mode.AtLeast,
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[1].min)
        assertEquals(1, state.intDomains[1].max)
    }

    @Test
    fun `atmost independent-set lower bound should force unsat`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 1),
                IntDomain(2, 3),
                IntDomain(4, 5),
                IntDomain(2, 2),
            ),
            factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.AtMost)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `atmost independent-set lower bound should be hole-aware`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 2).excludeValue(1),
                IntDomain(1, 1),
                IntDomain(3, 5).excludeValue(4),
                IntDomain(2, 2),
            ),
            factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.AtMost)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `a kernel count should cite only the bounds that keep its windows apart`() {
        val problem = nvalueProblem(Array(3) { IntDomain(0, 4) }, IntDomain(0, 3), NValue.Mode.AtMost)
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1) && state.tightenIntMin(1, 3) && state.excludeIntValue(2, 2))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[3])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.LE, 1L), Triple(1, AtomKind.GE, 3L)), cited.toSet())
    }

    @Test
    fun `nvalue deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x57A2)
        for (mode in NValue.Mode.entries) {
            repeat(40) { iter ->
                val k = 4
                val problem = nvalueProblem(Array(k) { IntDomain(0, 3) }, IntDomain(0, k.toLong()), mode)
                PropagationReasonOracle.assertReasonsImply(problem, "nvalue-$mode#$iter") { state ->
                    (0 until 5).all {
                        val v = rng.nextInt(k + 1)
                        when (rng.nextInt(3)) {
                            0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                            1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                            else -> state.tightenIntMax(v, 1L + rng.nextInt(3))
                        }
                    }
                }
            }
        }
    }
}
