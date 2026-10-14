package com.eignex.klause.solver.search

import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertIs

class SourceCoordinateAgreementComponentTest {
    @Test
    fun `a rejected owner selected point does not refute all source completions`() {
        val coordinate = SearchExactRealValue(SourceRealId(0))
        val owner = object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.contribute(coordinate, BigFraction.ZERO)
            }
        }
        val peer = object : SearchComponent, SourceCoordinateAgreement {
            override fun shareSourcePredicates(context: SearchContext): ComponentResult = ComponentResult.Consistent
            override fun checkSourceCoordinates(model: AssembledSearchModel, context: SearchContext): ComponentCheck =
                if (model.valueOf(coordinate) == BigFraction.ONE) ComponentCheck.Feasible else ComponentCheck.Infeasible()
        }
        lateinit var session: SearchSession
        val agreement = SourceCoordinateAgreementComponent(listOf(peer), { session.model() })
        session = SearchComponentSet(listOf(owner, peer, agreement)).session()

        val result = session.solve(0)

        assertIs<SearchResult.Indeterminate>(result)
    }
}
