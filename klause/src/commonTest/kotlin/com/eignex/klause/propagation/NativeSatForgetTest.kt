package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Forgetting and glue export on the native-SAT learned store: compaction must renumber the survivors,
 * preserve their watches, and drop the rejected clauses' watches, so a survivor still propagates and a
 * dropped clause no longer constrains.
 */
class NativeSatForgetTest {

    private fun session(numVars: Int, vararg clauses: IntArray): PropagationSession = PropagationSession(
        Problem(
            numBoolVars = numVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = clauses.map<IntArray, Factor> { Clause(it) }.toTypedArray(),
        ),
        nativeSat = true,
    )

    private fun or(a: Int, aPos: Boolean, b: Int, bPos: Boolean) = intArrayOf(Lit.make(a, aPos), Lit.make(b, bPos))

    @Test
    fun `forget renumbers survivors and preserves their propagation`() {
        // One trivial base clause keeps the problem non-empty; learned clauses drive the test.
        val session = session(4, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)))
        // Four two-literal learned clauses over fresh variables — none propagates at root.
        session.addLearnedClause(Clause(or(0, true, 1, true)), lbd = 2) // index 0 — kept
        session.addLearnedClause(Clause(or(2, true, 3, true)), lbd = 2) // index 1 — dropped
        session.addLearnedClause(Clause(or(0, true, 2, true)), lbd = 2) // index 2 — kept
        session.addLearnedClause(Clause(or(1, true, 3, true)), lbd = 2) // index 3 — dropped
        assertEquals(4, session.learnedClauseCount)

        session.forgetLearnedClauses { idx, _ -> idx % 2 == 0 }
        assertEquals(2, session.learnedClauseCount, "two survivors after dropping the odd indices")

        // Survivor (0 ∨ 1) must still force var 1 when var 0 is false.
        session.pinBool(0, false)
        assertTrue(session.boolValue(1) == true, "surviving clause (0 ∨ 1) must propagate var 1 true")
        // Survivor (0 ∨ 2) must still force var 2.
        assertTrue(session.boolValue(2) == true, "surviving clause (0 ∨ 2) must propagate var 2 true")
    }

    @Test
    fun `a dropped clause no longer propagates`() {
        val session = session(4, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)))
        session.addLearnedClause(Clause(or(0, true, 1, true)), lbd = 2) // index 0 — kept
        session.addLearnedClause(Clause(or(2, true, 3, true)), lbd = 2) // index 1 — dropped

        session.forgetLearnedClauses { idx, _ -> idx == 0 }
        assertEquals(1, session.learnedClauseCount)

        // The dropped clause (2 ∨ 3) would force var 3 true when var 2 is false; it must not.
        session.pinBool(0, true) // satisfy the surviving clause so it stays quiet
        session.pinBool(2, false)
        assertNull(session.boolValue(3), "dropped clause (2 ∨ 3) must not propagate var 3")
    }

    @Test
    fun `an eligible problem runs on the native lane`() {
        val eligible = PropagationSession(
            Problem(
                numBoolVars = 2,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
            ),
            nativeSat = true,
        )
        assertTrue(eligible.usesNativeSat, "an eligible pure-CNF session must use the native lane")
    }

    @Test
    fun `glue export reads native learned clause literals`() {
        val session = session(3, intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        session.addLearnedClause(Clause(or(0, false, 1, true)), lbd = 2)
        session.addLearnedClause(Clause(or(1, false, 2, true)), lbd = 8) // above the glue LBD cap
        val glue = session.exportGlueClauses(maxLbd = 2, maxLen = 8)
        assertEquals(1, glue.size, "only the low-LBD clause is glue")
    }
}
