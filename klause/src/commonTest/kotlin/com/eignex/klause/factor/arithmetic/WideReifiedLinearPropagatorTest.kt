package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WideReifiedLinearPropagatorTest {

    // 2^64 — one past the signed 64-bit range, so it can only live in the wide coefficient lane.
    private val w = parseBigInt("18446744073709551616")

    // bool 0 = aux; int 0 = x, int 1 = y.
    private fun problem(factor: Factor, xHi: Long = 5, yHi: Long = 5) = Problem(
        numBoolVars = 1,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, yHi)),
        factors = arrayOf(factor),
    )

    /** `aux ↔ (x ≤ y)`, expressed with a wide coefficient on both terms. */
    private fun auxIffXLeY() = ReifiedLinear(0, intArrayOf(0, 1), arrayOf(w, -w), LinearOp.LE, BIG_ZERO)

    @Test
    fun `incremental wide reification follows interior target membership through rollback`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (coefficient in listOf(w, -w)) {
                val problem = problem(ReifiedLinear(0, intArrayOf(0), arrayOf(coefficient), op,
                    coefficient * bigIntOf(2L)), xHi = 4L, yHi = 0L)
                val state = PropagationState(problem, Assumptions.None)
                state.undoLogging = true
                assertNull(state.runToFixpoint(allFactors = true))
                val root = state.mark()
                state.currentLevel = 1
                assertTrue(state.excludeIntValue(0, 1L))
                assertNull(state.runToFixpoint(allFactors = false))
                assertNull(state.boolValues[0])

                assertTrue(state.excludeIntValue(0, 2L))
                assertNull(state.runToFixpoint(allFactors = false))

                assertEquals(op == LinearOp.NE, state.boolValues[0])
                assertEquals(0L, state.intDomains[0].min)
                assertEquals(4L, state.intDomains[0].max)
                assertEquals(listOf(Lit.make(state.atomVarEq(0, 2L), true)), state.boolAntecedents[0]?.toList())
                state.undoTo(root)
                assertNull(state.boolValues[0])
                assertTrue(2L in state.intDomains[0])
                state.currentLevel = 1
                assertTrue(state.tightenIntMin(0, 2L))
                assertTrue(state.tightenIntMax(0, 2L))
                assertNull(state.runToFixpoint(allFactors = false))
                assertEquals(op == LinearOp.EQ, state.boolValues[0])
            }
        }
    }

    @Test
    fun `nonintegral wide single term targets decide both reification polarities`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val factor = ReifiedLinear(0, intArrayOf(0), arrayOf(w), op, w * bigIntOf(2L) + BIG_ONE)
            val session = PropagationSession(problem(factor, xHi = 4L, yHi = 0L))

            assertEquals(op == LinearOp.NE, session.boolValue(0))
        }
    }

    @Test
    fun `a carved wide disequality target keeps its premise`() {
        val factor = ReifiedLinear(0, intArrayOf(0), arrayOf(w), LinearOp.NE, w * bigIntOf(2L))
        val problem = problem(factor, xHi = 4L, yHi = 0L)

        PropagationReasonOracle.assertReasonsImply(problem, "wide disequality hole") { state ->
            state.excludeIntValue(0, 2L)
        }
    }

    @Test
    fun `a false wide disequality indicator conflicts with a carved target using its premise`() {
        val factor = ReifiedLinear(0, intArrayOf(0), arrayOf(w), LinearOp.NE, w * bigIntOf(2L))
        val problem = problem(factor, xHi = 4L, yHi = 0L)

        PropagationReasonOracle.assertReasonsImply(problem, "negated wide disequality hole") { state ->
            state.pinBool(0, false) && state.excludeIntValue(0, 2L)
        }
    }

    @Test
    fun `a wide root target hole decides the indicator without search premises`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0L, 4L).excludeValue(2L)),
                arrayOf(ReifiedLinear(0, intArrayOf(0), arrayOf(w), op, w * bigIntOf(2L))))
            val state = PropagationState(problem, Assumptions.None)

            assertNull(state.runToFixpoint(allFactors = true))

            assertEquals(op == LinearOp.NE, state.boolValues[0])
            assertNull(state.boolAntecedents[0])
        }
    }

    @Test
    fun `aux true forces the wide reified row`() {
        val s = PropagationSession(problem(auxIffXLeY()))
        assertTrue(s.pinBool(0, true) !is PropagationResult.Unsat)
        assertTrue(s.pinInt(0, 3) !is PropagationResult.Unsat)
        // aux = true requires x ≤ y; 2^64·3 − 2^64·2 = 2^64 > 0 breaks it — must be rejected exactly.
        assertIs<PropagationResult.Unsat>(s.pinInt(1, 2))
    }

    @Test
    fun `aux false forces the wide reified negation`() {
        val s = PropagationSession(problem(auxIffXLeY()))
        assertTrue(s.pinBool(0, false) !is PropagationResult.Unsat)
        assertTrue(s.pinInt(0, 2) !is PropagationResult.Unsat)
        // aux = false requires ¬(x ≤ y), i.e. x > y; y = 4 makes x ≤ y hold — a contradiction.
        assertIs<PropagationResult.Unsat>(s.pinInt(1, 4))
    }

    @Test
    fun `an entailed wide body pins the indicator true`() {
        val s = PropagationSession(problem(auxIffXLeY()))
        assertTrue(s.pinInt(0, 0) !is PropagationResult.Unsat)
        assertTrue(s.pinInt(1, 5) !is PropagationResult.Unsat) // x = 0 ≤ y = 5 always holds
        assertEquals(true, s.boolValue(0), "an entailed body must pin the indicator true")
    }

    @Test
    fun `a refuted wide body pins the indicator false`() {
        val s = PropagationSession(problem(auxIffXLeY()))
        assertTrue(s.pinInt(0, 5) !is PropagationResult.Unsat)
        assertTrue(s.pinInt(1, 0) !is PropagationResult.Unsat) // x = 5 > y = 0, so x ≤ y is refuted
        assertEquals(false, s.boolValue(0), "a refuted body must pin the indicator false")
    }

    @Test
    fun `the solver finds a witness consistent with the wide reification`() {
        val r = BacktrackSolver(problem(auxIffXLeY()).bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val aux = sat.assignment.bools[0]
        val x = sat.assignment.ints[0]
        val y = sat.assignment.ints[1]
        val bodyHolds = w * bigIntOf(x) - w * bigIntOf(y) <= BIG_ZERO
        assertEquals(aux, bodyHolds, "witness must satisfy aux ↔ (x ≤ y) exactly (aux=$aux, x=$x, y=$y)")
    }

    @Test
    fun `local search never reports a solution a wide factor refutes`() {
        // A bare wide Linear with no integer solution (2^64·x = 2·2^64 + 1): its exact invariant never reads as
        // satisfied, so no assignment is reported.
        val bound = w * bigIntOf(2) + BIG_ONE
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, bound)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(row),
        )
        val r = LocalSearchSolver(p.bake()).solve(LocalSearchParams(maxFlips = 100, randomSeed = 1))
        assertFalse(r is SolveResult.Sat, "got $r")
    }

    @Test
    fun `local search satisfies a wide factor exactly`() {
        // 2^64·x = 3·2^64 holds only at x = 3.
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, w * bigIntOf(3))
        val p = Problem(0, 1, arrayOf(IntDomain(0, 5)), arrayOf<Factor>(row))
        val r = LocalSearchSolver(p.bake()).solve(LocalSearchParams(maxFlips = 1_000, randomSeed = 1))
        assertEquals(3L, assertIs<SolveResult.Sat>(r).assignment.ints[0])
    }

    @Test
    fun `wide reified linear deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x1DE1)
        for (op in LinearOp.entries) {
            repeat(75) { iter ->
                val n = 3
                val problem = Problem(
                    numBoolVars = 1,
                    numIntVars = n,
                    intDomains = Array(n) { IntDomain(0, 3) },
                    factors = arrayOf<Factor>(
                        ReifiedLinear(
                            0,
                            IntArray(n) { it },
                            Array(n) { w * bigIntOf(listOf(-3, -2, -1, 1, 2, 3).random(rng).toLong()) },
                            op,
                            w * bigIntOf(rng.nextInt(-3, 7).toLong()),
                        ),
                    ),
                )
                PropagationReasonOracle.assertReasonsImply(problem, "wide-reified-linear-$op#$iter") { state ->
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
}
