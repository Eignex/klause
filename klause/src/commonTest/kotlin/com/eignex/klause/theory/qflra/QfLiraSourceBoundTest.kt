package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class QfLiraSourceBoundTest {
    @Test
    fun `linear adapter should enforce source predicates and retract their scope`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0),
            realUpper = doubleArrayOf(2.0),
        )
        ExactLiraSearchComponent(source).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )

            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.negative)))

            assertIs<ComponentCheck.Feasible>(session.check())
        }
    }

}
