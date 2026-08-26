package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WideReifiedLinearPropagatorTest {

    // 2^64 — one past the signed 64-bit range, so it can only live in the wide coefficient lane.
    private val w = BigInteger.parseString("18446744073709551616")

    // bool 0 = aux; int 0 = x, int 1 = y.
    private fun problem(factor: Factor, xHi: Long = 5, yHi: Long = 5) = Problem(
        numBoolVars = 1,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, yHi)),
        factors = arrayOf(factor),
    )

    /** `aux ↔ (x ≤ y)`, expressed with a wide coefficient on both terms. */
    private fun auxIffXLeY() = ReifiedLinear(0, intArrayOf(0, 1), arrayOf(w, -w), LinearOp.LE, BigInteger.ZERO)

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
        val bodyHolds = w * BigInteger.fromLong(x) - w * BigInteger.fromLong(y) <= BigInteger.ZERO
        assertEquals(aux, bodyHolds, "witness must satisfy aux ↔ (x ≤ y) exactly (aux=$aux, x=$x, y=$y)")
    }

    @Test
    fun `local search declines a problem carrying a wide factor`() {
        // A bare wide Linear with no integer solution (2^64·x = 2·2^64 + 1). Its invariant is inert, so an
        // ungated local search could ignore it and report a bogus "solution"; the wide-factor gate declines.
        val bound = w * BigInteger.fromLong(2) + BigInteger.ONE
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, bound)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf<Factor>(row),
        )
        val r = LocalSearchSolver(p.bake()).solve(LocalSearchParams(maxFlips = 100, randomSeed = 1))
        assertIs<SolveResult.Unknown>(r)
    }
}
