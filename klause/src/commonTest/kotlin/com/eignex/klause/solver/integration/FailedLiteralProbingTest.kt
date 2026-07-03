package com.eignex.klause.solver.integration

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FailedLiteralProbingTest {

    @Test
    fun `probing forces a literal when one polarity propagates Unsat`() {
        // (a ∨ b), (a ∨ c), (¬b ∨ ¬c). Direct propagation forces nothing. Probing
        // a=false forces b=true and c=true, then (¬b ∨ ¬c) fails → a must be true.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
            probeFailedLiterals = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        assertEquals(true, baked.bools[0], "probing should have forced a=true")
    }

    @Test
    fun `probing off does not detect the failed literal`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
            probeFailedLiterals = false,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        assertNull(baked.bools[0], "without probing, var 0 stays free")
    }

    @Test
    fun `probing detects Unsat when both polarities fail`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
            probeFailedLiterals = true,
        )
        assertIs<PropagationResult.Unsat>(p.baked)
    }

    @Test
    fun `probing reaches fixpoint over multiple passes`() {
        // Pass 1 forces a=true (via the (a∨b), (a∨c), (¬b∨¬c) triangle); pass 2 then
        // chains through (¬a ∨ d) to force d=true.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true))),
            ),
            probeFailedLiterals = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        assertEquals(true, baked.bools[0])
        assertEquals(true, baked.bools[3])
    }

    @Test
    fun `probing on a feasible problem with no forced literals leaves baked unchanged`() {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                ),
            ),
            probeFailedLiterals = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        for (v in 0..3) assertNull(baked.bools[v], "var $v unexpectedly forced: $baked")
    }
}
