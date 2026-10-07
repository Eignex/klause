package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    /** `x ≤ y`, expressed with a wide coefficient on both terms. */
    private fun xLeY() = Linear(intArrayOf(0, 1), arrayOf(w, -w), LinearOp.LE, BIG_ZERO)

    @Test
    fun `an assignment violating a wide row is rejected exactly`() {
        val s = PropagationSession(problem(xLeY()))
        assertTrue(s.pinInt(0, 3) !is PropagationResult.Unsat)
        // 2^64·3 − 2^64·2 = 2^64 > 0 violates x ≤ y — must be rejected, not lost to 64-bit wrap.
        assertIs<PropagationResult.Unsat>(s.pinInt(1, 2))
    }

    @Test
    fun `an assignment satisfying a wide row passes`() {
        val s = PropagationSession(problem(xLeY()))
        assertTrue(s.pinInt(0, 2) !is PropagationResult.Unsat)
        assertTrue(s.pinInt(1, 4) !is PropagationResult.Unsat)
    }

    @Test
    fun `a wide coefficient tightens a variable domain`() {
        // 2^64·x + 2^64·y ≤ 2·2^64  ⇔  x + y ≤ 2. Pinning y = 1 forces x ≤ 1.
        val row = Linear(intArrayOf(0, 1), arrayOf(w, w), LinearOp.LE, w * bigIntOf(2))
        val s = PropagationSession(problem(row))
        assertTrue(s.pinInt(1, 1) !is PropagationResult.Unsat)
        assertEquals(1L, s.intDomain(0).max, "x's max must be tightened to 1 through the wide coefficient")
    }

    @Test
    fun `a collapsed wide coefficient bakes and propagates`() {
        val original = Linear(intArrayOf(0, 1), arrayOf(w, -w), LinearOp.EQ, BIG_ZERO)
        val remapped = original.remap(VarRemap(IntArray(0), intArrayOf(0, 0)))
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-1, 1)),
            factors = arrayOf(remapped),
        )

        val session = PropagationSession(p.bake())

        assertEquals(IntDomain(-1, 1), session.intDomain(0))
    }

    @Test
    fun `solver finds a witness satisfying a wide-coefficient row`() {
        // 2^64·x + y = 2·2^64 + 1 with y ∈ [0,3] forces x = 2, y = 1 (y is too small to carry a 2^64 unit).
        val bound = w * bigIntOf(2) + BIG_ONE
        val row = Linear(intArrayOf(0, 1), arrayOf(w, BIG_ONE), LinearOp.EQ, bound)
        val r = BacktrackSolver(problem(row, xHi = 3, yHi = 3).bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val x = sat.assignment.ints[0]
        val y = sat.assignment.ints[1]
        val lhs = w * bigIntOf(x) + bigIntOf(y)
        assertEquals(bound, lhs, "witness (x=$x, y=$y) must satisfy the wide row exactly")
    }

    @Test
    fun `solver handles a wide row over a sign-straddling variable`() {
        // 2^64·x = 2^64·(−2) with x ∈ [−3, 3] (straddling zero) forces x = −2. Drives the LP x⁺/x⁻ split
        // for the wide row and checks the whole path stays sound (no false UNSAT, correct witness).
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, w * bigIntOf(-2))
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-3, 3)),
            factors = arrayOf<Factor>(row),
        )
        val r = BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L))
        assertEquals(-2L, assertIs<SolveResult.Sat>(r).assignment.ints[0], "x must be pinned to −2")
    }

    @Test
    fun `solver proves unsat when a wide row has no integer solution`() {
        // 2^64·x = 2·2^64 + 1 has no integer x (remainder 1): the propagator derives x ≤ 2 ∧ x ≥ 3.
        val bound = w * bigIntOf(2) + BIG_ONE
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, bound)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(row),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L)))
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
}
