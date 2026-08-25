package com.eignex.klause.factor.bool

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.VarRemap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CardinalityPropagatorTest {

    @Test
    fun `cardinality rejects multiple literals for one Boolean variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Cardinality(intArrayOf(Lit.make(0, true), Lit.make(0, false)), min = 0, max = 1)
        }

        assertEquals("Cardinality literals must reference distinct Boolean variables", error.message)
    }

    @Test
    fun `at most one accepts an empty literal list`() {
        val factor = Cardinality.atMostOne(intArrayOf())

        val baked = Problem(0, 0, emptyArray(), arrayOf<Factor>(factor)).baked

        assertIs<PropagationResult.Implied>(baked)
    }

    @Test
    fun `remapping a cardinality rejects collapsed Boolean variables`() {
        val factor = Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 0, max = 1)

        val error = assertFailsWith<IllegalArgumentException> {
            factor.remap(VarRemap(intArrayOf(0, 0), intArrayOf()))
        }

        assertEquals("Cardinality literals must reference distinct Boolean variables", error.message)
    }

    @Test
    fun `at-least boundary forces remaining unassigned to true`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(4) { Lit.make(it, true) }, min = 2, max = 4)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        assertIs<PropagationResult.Implied>(session.pinBool(1, false))
        assertEquals(true, session.boolValue(2))
        assertEquals(true, session.boolValue(3))
    }

    @Test
    fun `at-most boundary forces remaining to false when max reached`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(4) { Lit.make(it, true) }, min = 0, max = 1)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        assertEquals(false, session.boolValue(1))
        assertEquals(false, session.boolValue(2))
        assertEquals(false, session.boolValue(3))
    }

    @Test
    fun `max zero forces all literals false at bake time`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 0, max = 0)),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.baked)
        for (v in 0..2) assertEquals(false, impl.bools[v], "var $v should be forced false with max=0")
    }

    @Test
    fun `enumerate matches brute force for cardinality windows`() {
        val cases = listOf(Triple(4, 2, 4), Triple(3, 1, 1))
        for ((n, min, max) in cases) {
            for (seed in 1L..3L) {
                val problem = Problem(
                    numBoolVars = n,
                    numIntVars = 0,
                    intDomains = emptyArray(),
                    factors = arrayOf<Factor>(Cardinality(IntArray(n) { Lit.make(it, true) }, min = min, max = max)),
                )
                val brute = (0 until (1 shl n))
                    .filter { mask -> (0 until n).count { (mask shr it) and 1 == 1 } in min..max }
                    .map { mask -> (0 until n).map { (mask shr it) and 1 == 1 } }
                    .toHashSet()
                val found = BacktrackSolver(problem.bake())
                    .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                    .take(100).map { it.bools.toList() }.toHashSet()
                assertEquals(brute, found, "seed=$seed: min=$min max=$max of $n must match brute force")
            }
        }
    }
}
