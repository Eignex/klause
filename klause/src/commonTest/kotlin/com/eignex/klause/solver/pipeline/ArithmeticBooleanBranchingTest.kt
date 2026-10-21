package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArithmeticBooleanBranchingTest {
    @Test
    fun `arithmetic inputs should precede unrelated source Booleans with both polarities`() {
        val session = SearchSession(emptyList())
        val branching = ArithmeticBooleanBranching(intArrayOf(2, 3), 4)

        val split = branching.alternatives(session)

        assertEquals(listOf(SearchDecision.Bool(Lit.make(2, false)), SearchDecision.Bool(Lit.make(2, true))), split)
    }

    @Test
    fun `settled or absent arithmetic inputs should fall back to source order`() {
        for (preferred in listOf(intArrayOf(), intArrayOf(2, 3))) {
            val session = SearchSession(emptyList())
            val branching = ArithmeticBooleanBranching(preferred, 4)
            for (variable in preferred) {
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(variable, true))))
            }

            val split = branching.alternatives(session)

            assertEquals(listOf(SearchDecision.Bool(Lit.make(0, false)), SearchDecision.Bool(Lit.make(0, true))), split)
        }
    }

    @Test
    fun `a retracted arithmetic input should regain priority`() {
        val session = SearchSession(emptyList())
        val branching = ArithmeticBooleanBranching(intArrayOf(2, 3), 4)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(3, false))))

        session.popTo(0)

        assertEquals(listOf(SearchDecision.Bool(Lit.make(2, false)), SearchDecision.Bool(Lit.make(2, true))),
            branching.alternatives(session))
    }
}
