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
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WideLinearPropagatorTest {

    private val w = parseBigInt("18446744073709551616")

    private fun problem(factor: Factor, xHi: Long = 5, yHi: Long = 5) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, yHi)),
        factors = arrayOf(factor),
    )

    @Test
    fun `a wide coefficient should tighten a variable domain`() {
        val row = Linear(intArrayOf(0, 1), arrayOf(w, w), LinearOp.LE, w * bigIntOf(2))
        val s = PropagationSession(problem(row))
        assertTrue(s.pinInt(1, 1) !is PropagationResult.Unsat)
        assertEquals(1L, s.intDomain(0).max)
    }

    @Test
    fun `wide linear deductions should be implied by their reasons under carved holes`() {
        for (op in listOf(LinearOp.LE, LinearOp.EQ, LinearOp.GE)) {
            val rng = Random(0x1DE0)
            repeat(8) { iter ->
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
                PropagationReasonOracle.assertReasonsImply(
                    problem, "wide-linear-$op#$iter", allowReasonlessRootConflict = true,
                ) { state ->
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
    fun `wide linear not-equal deductions should be implied by their reasons once all but one term is fixed`() {
        val rng = Random(0x1DE1)
        repeat(4) { iter ->
            val n = 3
            val coefficients = LongArray(n) { listOf(-3L, -2L, -1L, 1L, 2L, 3L).random(rng) }
            val excluded = LongArray(n) { rng.nextInt(4).toLong() }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    Linear(
                        IntArray(n) { it },
                        Array(n) { w * bigIntOf(coefficients[it]) },
                        LinearOp.NE,
                        w * bigIntOf((0 until n).sumOf { coefficients[it] * excluded[it] }),
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "wide-linear-NE#$iter") { state ->
                val open = rng.nextInt(n)
                val alsoOpen = if (rng.nextInt(4) == 0) rng.nextInt(n) else -1
                (0 until n).all { v ->
                    val pick = rng.nextInt(4)
                    when {
                        v == alsoOpen || (v == open && pick < 2) -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        pick == 3 -> state.setInt(v, rng.nextInt(4).toLong())
                        else -> state.setInt(v, excluded[v])
                    }
                }
            }
        }
    }

    @Test
    fun `a wide bound should cite only the side of the sum it reads`() {
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
