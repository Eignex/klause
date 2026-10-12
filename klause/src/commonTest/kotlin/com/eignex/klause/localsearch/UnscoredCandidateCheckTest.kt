package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs

class UnscoredCandidateCheckTest {
    @Test
    fun `full integer coordinates decide a propagation only product`() {
        val factor = object : Factor by Product(0, 1, 2), Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean {
                val domains = state.intDomains
                if (domains.any { it.size != 1 }) return true
                return domains[0].min * domains[1].min == domains[2].min
            }
        }
        val source = Problem(0, 3, Array(3) { IntDomain(0, 9) }, arrayOf<Factor>(factor))
        val check = UnscoredCandidateCheck(source)

        assertIs<Completion.Refuted>(check.check(Sample(BooleanArray(0), longArrayOf(2, 3, 5)), Assumptions.None, Cancellation.Never))
        assertIs<Completion.Witness>(check.check(Sample(BooleanArray(0), longArrayOf(2, 3, 6)), Assumptions.None, Cancellation.Never))
    }
}
