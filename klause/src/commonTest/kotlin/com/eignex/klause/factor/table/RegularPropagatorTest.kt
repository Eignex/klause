package com.eignex.klause.factor.table

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegularPropagatorTest {

    @Test
    fun `regular restricts wide sequence domains to its alphabet`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-1000000000000L, 1000000000000L)),
            factors = arrayOf<Factor>(
                Regular(intArrayOf(0), 1, 2, longArrayOf(1, 1), q0 = 1, accepting = intArrayOf(1)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(state.factorAt(0).propagate(state, 0))

        assertEquals(1L, state.intDomains[0].min)
        assertEquals(2L, state.intDomains[0].max)
    }

    @Test
    fun `regular deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x7E61)
        repeat(300) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(1, 3) },
                factors = arrayOf<Factor>(
                    Regular(
                        seq = IntArray(n) { it },
                        numStates = 3,
                        alphabetSize = 3,
                        transitions = LongArray(9) { rng.nextInt(4).toLong() },
                        q0 = 1,
                        accepting = intArrayOf(1 + rng.nextInt(3)),
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "regular#$iter") { state ->
                (0 until 4).all {
                    val v = rng.nextInt(n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, 1L + rng.nextInt(3))
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, 2L + rng.nextInt(2))
                    }
                }
            }
        }
    }

    @Test
    fun `a regular prune should cite only the symbols its cut of the automaton crosses`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = longArrayOf(2, 1, 0, 1),
                    q0 = 1,
                    accepting = intArrayOf(1, 2),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(1, 1L) && state.tightenIntMin(3, 2L))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[0])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(1, AtomKind.LE, 1L)), cited)
    }
}
