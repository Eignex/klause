package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WideLinearPropagatorTest {

    // 2^64 — one past the signed 64-bit range, so it can only live in the wide coefficient lane.
    private val w = parseBigInt("18446744073709551616")

    private fun problem(factor: Factor, xHi: Long = 5, yHi: Long = 5) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, yHi)),
        factors = arrayOf(factor),
    )

    @Test
    fun `a wide coefficient tightens a variable domain`() {
        // 2^64·x + 2^64·y ≤ 2·2^64  ⇔  x + y ≤ 2. Pinning y = 1 forces x ≤ 1.
        val row = Linear(intArrayOf(0, 1), arrayOf(w, w), LinearOp.LE, w * bigIntOf(2))
        val s = PropagationSession(problem(row))
        assertTrue(s.pinInt(1, 1) !is PropagationResult.Unsat)
        assertEquals(1L, s.intDomain(0).max, "x's max must be tightened to 1 through the wide coefficient")
    }

    @Test
    fun `wide linear deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x1DE0)
        for (op in LinearOp.entries) {
            repeat(75) { iter ->
                val n = 3
                val problem = Problem(
                    numBoolVars = 0,
                    numIntVars = n,
                    intDomains = Array(n) { IntDomain(0, 3) },
                    factors = arrayOf<Factor>(
                        Linear(
                            IntArray(n) { it },
                            Array(n) { w * bigIntOf(listOf(-3, -2, -1, 1, 2, 3).random(rng).toLong()) },
                            op,
                            w * bigIntOf(rng.nextInt(-3, 7).toLong()),
                        ),
                    ),
                )
                PropagationReasonOracle.assertReasonsImply(problem, "wide-linear-$op#$iter") { state ->
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
    fun `a wide bound cites only the side of the sum it reads`() {
        // 2^64·(x + y) <= 5·2^64 with x >= 3 forces y <= 2; x's upper bound plays no part.
        val row = Linear(intArrayOf(0, 1), arrayOf(w, w), LinearOp.LE, w * bigIntOf(5L))
        val problem = problem(row, xHi = 9, yHi = 9)
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 3) && state.tightenIntMax(0, 4))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMaxAntecedents[1])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.GE, 3L)), cited)
    }
}
