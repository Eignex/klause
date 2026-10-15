package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.pinBoolAsDecision
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReifiedLinearPropagatorTest {
    @Test
    fun `a fixed binary integer should imply its equality indicator with a valid bound reason`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (target in listOf(0L, 1L)) {
                for (value in listOf(0L, 1L)) {
                    val problem = Problem(
                        1,
                        1,
                        arrayOf(IntDomain(0L, 1L)),
                        arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), op, target)),
                    )

                    PropagationReasonOracle.assertReasonsImply(problem, "$op fixed binary $target $value") { state ->
                        if (value == 1L) state.tightenIntMin(0, value) else state.tightenIntMax(0, value)
                    }
                }
            }
        }
    }

    @Test
    fun `binary equality channels should imply the integer value for either indicator polarity`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (target in listOf(0L, 1L)) {
                for (indicator in listOf(false, true)) {
                    val problem = Problem(
                        1,
                        1,
                        arrayOf(IntDomain(0L, 1L)),
                        arrayOf(ReifiedLinear(0, longArrayOf(-2L), intArrayOf(0), op, -2L * target)),
                    )

                    PropagationReasonOracle.assertReasonsImply(problem, "$op binary $target $indicator") { state ->
                        state.pinBool(0, indicator)
                    }
                }
            }
        }
    }

    @Test
    fun `a negated equality over a wider domain should retain values outside the binary interval`() {
        val problem = Problem(
            1,
            1,
            arrayOf(IntDomain(0L, 2L)),
            arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, 1L)),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(state.pinBool(0, false))

        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(listOf(0L, 2L), (0L..2L).filter { it in state.intDomains[0] })
    }

    @Test
    fun `single sided indicator reasons should imply the pin for signed rows`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ, LinearOp.NE)) {
            for (coefficient in listOf(-1L, 1L)) {
                val problem = Problem(
                    1,
                    2,
                    arrayOf(IntDomain(0L, 3L), IntDomain(0L, 3L)),
                    arrayOf(
                        ReifiedLinear(
                            0,
                            longArrayOf(coefficient, coefficient),
                            intArrayOf(0, 1),
                            op,
                            3L * coefficient,
                        ),
                    ),
                )

                PropagationReasonOracle.assertReasonsImply(problem, "$op signed indicator $coefficient") { state ->
                    state.tightenIntMin(0, 2L) && state.tightenIntMin(1, 2L)
                }
            }
        }
    }

    @Test
    fun `settled inequality indicator reasons should cite the bounds at the pin through rollback`() {
        for (value in listOf(false, true)) {
            val problem = Problem(
                1,
                1,
                arrayOf(IntDomain(0L, 10L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.LE, 5L)),
            )
            val state = PropagationState(problem, Assumptions.None)
            state.undoLogging = true
            assertNull(state.runToFixpoint(allFactors = true))
            val root = state.mark()
            state.currentLevel = 1
            if (value) assertTrue(state.tightenIntMax(0, 5L)) else assertTrue(state.tightenIntMin(0, 6L))
            assertNull(state.runToFixpoint(allFactors = false))
            val reason = state.boolAntecedents[0]
            state.currentLevel = 2
            if (value) assertTrue(state.tightenIntMax(0, 3L)) else assertTrue(state.tightenIntMin(0, 8L))

            val explained = state.reasonOf(reason)

            val atom = if (value) state.atomVarLe(0, 5L) else state.atomVarGe(0, 6L)
            assertEquals(value, state.boolValues[0])
            assertEquals(listOf(Lit.make(atom, false)), explained?.toList())
            state.undoTo(root)
            assertNull(state.boolValues[0])
            state.currentLevel = 1
            if (value) assertTrue(state.tightenIntMin(0, 6L)) else assertTrue(state.tightenIntMax(0, 5L))
            assertNull(state.runToFixpoint(allFactors = false))
            assertEquals(!value, state.boolValues[0])
            val siblingAtom = if (value) state.atomVarGe(0, 6L) else state.atomVarLe(0, 5L)
            assertEquals(listOf(Lit.make(siblingAtom, false)), state.reasonOf(state.boolAntecedents[0])?.toList())
        }
    }

    @Test
    fun `incremental reification should follow interior target membership through rollback`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (target in listOf(2L, Int.MAX_VALUE.toLong() + 1L)) {
                val problem = Problem(
                    1,
                    1,
                    arrayOf(IntDomain(target - 2L, target + 2L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), op, target)),
                )
                val state = PropagationState(problem, Assumptions.None)
                state.undoLogging = true
                assertNull(state.runToFixpoint(allFactors = true))
                val root = state.mark()
                state.currentLevel = 1
                assertTrue(state.excludeIntValue(0, target - 1L))
                assertNull(state.runToFixpoint(allFactors = false))
                assertNull(state.boolValues[0])

                assertTrue(state.excludeIntValue(0, target))
                assertNull(state.runToFixpoint(allFactors = false))

                assertEquals(op == LinearOp.NE, state.boolValues[0])
                assertEquals(target - 2L, state.intDomains[0].min)
                assertEquals(target + 2L, state.intDomains[0].max)
                assertEquals(listOf(Lit.make(state.atomVarEq(0, target), true)), state.boolAntecedents[0]?.toList())
                state.undoTo(root)
                assertNull(state.boolValues[0])
                assertTrue(target in state.intDomains[0])
                state.currentLevel = 1
                assertTrue(state.tightenIntMin(0, target))
                assertTrue(state.tightenIntMax(0, target))
                assertNull(state.runToFixpoint(allFactors = false))
                assertEquals(op == LinearOp.EQ, state.boolValues[0])
            }
        }
    }

    @Test
    fun `nonintegral single term targets should decide both reification polarities`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (coefficient in listOf(2L, -2L)) {
                val bound = if (coefficient > 0L) 1L else -1L
                val session = PropagationSession(
                    Problem(
                        1,
                        1,
                        arrayOf(IntDomain(0L, 2L)),
                        arrayOf(ReifiedLinear(0, longArrayOf(coefficient), intArrayOf(0), op, bound)),
                    ),
                )

                assertEquals(op == LinearOp.NE, session.boolValue(0))
            }
        }
    }

    @Test
    fun `a carved equality target outside the 32 bit range should keep its premise`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val problem = Problem(
                1,
                1,
                arrayOf(IntDomain(target - 1L, target + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
            )

            PropagationReasonOracle.assertReasonsImply(problem, "64 bit equality hole $target") { state ->
                state.excludeIntValue(0, target)
            }
        }
    }

    @Test
    fun `an extremal reified bound should bake as a fixed true indicator`() {
        val factor = ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE)
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), listOf(factor))
        val session = PropagationSession(problem.bake())

        assertEquals(true, session.boolValue(0))
        assertIs<PropagationResult.Unsat>(session.pinBool(0, false))
    }

    @Test
    fun `reified linear deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x11E1)
        for (op in LinearOp.entries) {
            repeat(50) { iter ->
                val n = 3
                val problem = Problem(
                    numBoolVars = 1,
                    numIntVars = n,
                    intDomains = Array(n) { IntDomain(0, 3) },
                    factors = arrayOf<Factor>(
                        ReifiedLinear(
                            0,
                            IntArray(n) { (1 + rng.nextInt(3)) * (if (rng.nextBoolean()) 1 else -1) },
                            IntArray(n) { it },
                            op,
                            rng.nextInt(-3, 7),
                        ),
                    ),
                )
                PropagationReasonOracle.assertReasonsImply(problem, "reified-linear-$op#$iter") { state ->
                    (rng.nextInt(3) != 0 || state.pinBool(0, rng.nextBoolean())) &&
                        (0 until 4).all {
                            val v = rng.nextInt(n)
                            when (rng.nextInt(3)) {
                                0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                                1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                                else -> state.tightenIntMax(v, 1L + rng.nextInt(2))
                            }
                        }
                }
            }
        }
    }

    @Test
    fun `body conflict reason should be false and contain the indicator literal`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.pinBoolAsDecision(0, true)
        assertTrue(state.tightenIntMax(0, 4))
        assertFalse(PropagationProblem(problem).propagators[0].propagate(state, 0))

        val reason = assertNotNull(PropagationProblem(problem).propagators[0].conflictReason(state, 0))

        assertTrue(reason.isNotEmpty() && reason.all { state.litFalse(it) })
        assertTrue(Lit.make(0, false) in reason)
    }

    @Test
    fun `equality reification conflict reason should be sound over a search-time hole`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 3)), arrayOf())
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.pinBool(0, true))
        state.currentLevel = 2
        check(state.excludeIntValue(0, 2, null))
        val reif = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2)

        val reason = assertNotNull(reif.propagatorProjection().conflictReason(state, 0))

        assertTrue(reason.any { lit -> holdsAtAuxTrueXTwo(state, lit) == Lit.isPositive(lit) })
    }

    private fun holdsAtAuxTrueXTwo(state: PropagationState, lit: Int): Boolean {
        val atom = Lit.variable(lit) - state.problem.numBoolVars
        if (atom < 0) return true
        return when (state.atoms.kind[atom]) {
            AtomKind.GE -> 2 >= state.atoms.threshold[atom]
            AtomKind.LE -> 2 <= state.atoms.threshold[atom]
            AtomKind.EQ -> 2L == state.atoms.threshold[atom]
        }
    }
}
