package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.VarRemap
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WideLinearPropagatorTest {

    // 2^64 — one past the signed 64-bit range, so it can only live in the wide coefficient lane.
    private val w = BigInteger.parseString("18446744073709551616")

    private fun problem(factor: Factor, xHi: Long = 5, yHi: Long = 5) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, yHi)),
        factors = arrayOf(factor),
    )

    /** `x ≤ y`, expressed with a wide coefficient on both terms. */
    private fun xLeY() = Linear(intArrayOf(0, 1), arrayOf(w, -w), LinearOp.LE, BigInteger.ZERO)

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
        val row = Linear(intArrayOf(0, 1), arrayOf(w, w), LinearOp.LE, w * BigInteger.fromLong(2))
        val s = PropagationSession(problem(row))
        assertTrue(s.pinInt(1, 1) !is PropagationResult.Unsat)
        assertEquals(1L, s.intDomain(0).max, "x's max must be tightened to 1 through the wide coefficient")
    }

    @Test
    fun `a collapsed wide coefficient bakes and propagates`() {
        val original = Linear(intArrayOf(0, 1), arrayOf(w, -w), LinearOp.EQ, BigInteger.ZERO)
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
        val bound = w * BigInteger.fromLong(2) + BigInteger.ONE
        val row = Linear(intArrayOf(0, 1), arrayOf(w, BigInteger.ONE), LinearOp.EQ, bound)
        val r = BacktrackSolver(problem(row, xHi = 3, yHi = 3).bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val x = sat.assignment.ints[0]
        val y = sat.assignment.ints[1]
        val lhs = w * BigInteger.fromLong(x) + BigInteger.fromLong(y)
        assertEquals(bound, lhs, "witness (x=$x, y=$y) must satisfy the wide row exactly")
    }

    @Test
    fun `solver handles a wide row over a sign-straddling variable`() {
        // 2^64·x = 2^64·(−2) with x ∈ [−3, 3] (straddling zero) forces x = −2. Drives the LP x⁺/x⁻ split
        // for the wide row and checks the whole path stays sound (no false UNSAT, correct witness).
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, w * BigInteger.fromLong(-2))
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
        val bound = w * BigInteger.fromLong(2) + BigInteger.ONE
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, bound)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(row),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }
}
