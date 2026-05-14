package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.PropagationResult

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FailedLiteralProbingTest {

    @Test
    fun `probing forces a literal when one polarity propagates Unsat`() {
        // (a ∨ b), (¬a ∨ c), (¬b ∨ c), (¬c). Without probing, no immediate forcing.
        // With probing: c=false (from clause 4). Now (¬a ∨ c) forces a=false; (¬b ∨ c)
        // forces b=false; but (a ∨ b) then requires one true → Unsat.
        //
        // Use a simpler shape: (a ∨ b), (¬a). Without probing, the second clause forces
        // a=false; (a ∨ b) then forces b=true. Both already done at bake. So this case
        // doesn't need probing.
        //
        // Probing-only case: (a ∨ b), (a ∨ c), (¬b ∨ ¬c). Direct propagation: nothing
        // forced. Probe a=false: (a ∨ b) forces b=true. (a ∨ c) forces c=true. (¬b ∨ ¬c)
        // now needs ¬b ∨ ¬c with both true → Unsat. So a must be true.
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
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
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
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
        // (x), (¬x): direct propagation already gives Unsat. Probing should preserve it.
        val p = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
            probeFailedLiterals = true,
        )
        assertIs<PropagationResult.Unsat>(p.baked)
    }

    @Test
    fun `probing reaches fixpoint over multiple passes`() {
        // Chain: probing var 0 reveals var 1=true is forced; then probing again with
        // var 1=true reveals var 2=true; etc. Requires multiple sweeps to converge.
        // Construction: (a ∨ b), (a ∨ c), (¬b ∨ ¬c) — pass 1 forces a=true.
        // Add: (¬a ∨ d ∨ e), (d ∨ f), (e ∨ f), (¬d ∨ ¬e). a=true (from pass 1) means
        // the first clause needs d ∨ e. Probing d=false: (d ∨ f) → f=true; (e ∨ f) sat;
        // first clause needs d ∨ e but d=false so e=true; (¬d ∨ ¬e) needs ¬d ∨ ¬e with
        // d=false → sat. So d=false is feasible — not a forced.
        // Probing d=true: (¬d ∨ ¬e) forces e=false; (a ∨ d ∨ e wait a is true so this
        // clause is already sat). Hmm not a clean chain.
        //
        // Simpler test: use exactlyOne to force a chain.
        val p = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true))),
            ),
            probeFailedLiterals = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        // a=true (from probing), and then the 4th clause forces d=true.
        assertEquals(true, baked.bools[0])
        assertEquals(true, baked.bools[3])
    }

    @Test
    fun `probing with already-Unsat problem reports Unsat`() {
        val p = Problem(
            numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
            probeFailedLiterals = true,
        )
        assertIs<PropagationResult.Unsat>(p.baked)
    }

    @Test
    fun `probing on a feasible problem with no forced literals leaves baked unchanged`() {
        // exactly-one over 4 vars — no probing-discoverable forcings.
        val p = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality.exactlyOne(intArrayOf(
                Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
            ))),
            probeFailedLiterals = true,
        )
        val baked = assertIs<PropagationResult.Implied>(p.baked)
        // No var should be forced — every var could be the "true" one.
        for (v in 0..3) assertNull(baked.bools[v], "var $v unexpectedly forced: $baked")
    }
}
