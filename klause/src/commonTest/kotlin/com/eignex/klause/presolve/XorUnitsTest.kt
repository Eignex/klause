package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GF(2) elimination over the root xor system ([Presolve.deriveXorUnits]). Asserts the pass's
 * observable output — the unit [Clause]s it appends — for forced literals, contradictions, derived
 * cross-row units, idempotence, and the no-op empty delta.
 */
class XorUnitsTest {

    private fun units(problem: Problem): List<Int> =
        problem.factors.filterIsInstance<Clause>().filter { it.literals.size == 1 }.map { it.literals[0] }

    private fun derived(problem: Problem): Problem =
        problem.withPassDelta(Presolve.deriveXorUnits(problem), BakeConfig.NONE)

    @Test
    fun `a single-literal xor forces its variable with the right polarity`() {
        // targetParity xor negParity decides the value: a negated literal flips the forced polarity.
        val cases = listOf(
            Triple(Lit.make(0, true), 1, Lit.make(0, true)), //  x0 = true
            Triple(Lit.make(0, true), 0, Lit.make(0, false)), //  x0 = false
            Triple(Lit.make(0, false), 1, Lit.make(0, false)), // !x0 odd  ⇒ x0 = false
            Triple(Lit.make(0, false), 0, Lit.make(0, true)), //  !x0 even ⇒ x0 = true
        )
        for ((lit, parity, expected) in cases) {
            val problem = Problem(1, 0, emptyArray(), listOf(Xor(intArrayOf(lit), targetParity = parity)))
            val out = derived(problem)
            assertEquals(listOf(expected), units(out), "xor($lit)=$parity should force $expected")
        }
    }

    @Test
    fun `forced units are emitted sorted by variable`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(2, true)), targetParity = 1),
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 0),
                Xor(intArrayOf(Lit.make(1, true)), targetParity = 1),
            ),
        )
        val out = derived(problem)
        assertEquals(
            listOf(Lit.make(0, false), Lit.make(1, true), Lit.make(2, true)),
            units(out),
            "forced units come out ordered by variable",
        )
    }

    @Test
    fun `elimination across rows derives a unit no single xor shows`() {
        // x0 ⊕ x1 = 0 and x0 ⊕ x1 ⊕ x2 = 1: neither row is a unit, but their sum forces x2 = true.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 0),
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), targetParity = 1),
            ),
        )
        val out = derived(problem)
        assertEquals(listOf(Lit.make(2, true)), units(out), "the cross-row residue forces x2 = true")
    }

    @Test
    fun `a contradictory system emits a unit pair on a witness variable`() {
        // x0 = true and x0 = false reduce to 0 = 1; the pass posts both polarities on the witness.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 1),
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 0),
            ),
        )
        val out = derived(problem)
        assertEquals(
            setOf(Lit.make(0, true), Lit.make(0, false)),
            units(out).toSet(),
            "contradiction posts both polarities on the witness",
        )
    }

    @Test
    fun `the pass reaches a fixpoint instead of re-adding present units`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 0),
                Xor(intArrayOf(Lit.make(1, true)), targetParity = 1),
            ),
        )
        val once = derived(problem)
        assertEquals(
            setOf(Lit.make(0, true), Lit.make(1, true)),
            units(once).toSet(),
            "first run forces both variables",
        )
        assertTrue(Presolve.deriveXorUnits(once).isEmpty, "re-running adds no duplicate units")
    }

    @Test
    fun `a problem with no xor factors is a no-op`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))),
        )
        assertTrue(Presolve.deriveXorUnits(problem).isEmpty, "no xor factors is the pass's no-op signal")
    }
}
