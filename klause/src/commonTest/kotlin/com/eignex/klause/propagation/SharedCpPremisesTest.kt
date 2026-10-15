package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SharedCpPremisesTest {
    @Test
    fun `an unavailable export preserves an earlier native proof`() {
        val native = PropagationSession(
            Problem(
                1, 4, Array(4) { IntDomain(0, 10) },
                arrayOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.LE, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
                ),
            ),
        )
        val mapping = intArrayOf(4, 7, 9, 12)
        val cp = CpSearchComponent(native, mapping)
        cp.rebase()
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(Lit.make(0, true))))
        assertIs<PropagationResult.Implied>(native.implyIntAtMost(3, 2L))
        val exporter = CpPublicationPremises(native, shared, { mapping[it] }, rootLevel = 0)
        val earlier = exporter.bound(0, upper = true)

        val unavailable = exporter.bound(2, upper = true)

        assertSame(SearchAtomPremise.Unavailable, unavailable)
        assertContentEquals(intArrayOf(Lit.make(0, false)), shared.explainAtoms(earlier)?.literals)
        assertContentEquals(
            intArrayOf(Lit.make(0, false)),
            shared.explainAtoms(exporter.bound(0, upper = true))?.literals,
        )
    }
}
