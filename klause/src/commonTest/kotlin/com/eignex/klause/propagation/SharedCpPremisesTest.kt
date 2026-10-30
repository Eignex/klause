package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedCpPremisesTest {
    @Test
    fun `complete nested peer premises should share publication reconstruction`() {
        val native = PropagationSession(Problem(1, 8, Array(8) { IntDomain(0, 10) },
            Array(7) { Linear(intArrayOf(1, -1), intArrayOf(it, it + 1), LinearOp.LE, 0) }),
            pbLearning = false)
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (decision == SearchDecision.Bool(0)) context.publish(SearchDecision.IntAtLeast(0, 3),
                    SearchAtomPremise.All(listOf(SearchAtomPremise.All(listOf(
                        SearchAtomPremise.Asserted(SearchDecision.Bool(0)))))))
                else ComponentResult.Consistent
        }
        val shared = SearchSession(listOf(peer, CpSearchComponent(native)), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(0)))
        for (variable in 1..7) native.sharedBoundPremise(variable, false, shared, { it }, 0)
        val memo = CpPublicationPremises(native, shared, { it }, rootLevel = 0)
        val beforeMemo = native.work

        val retained = (1..7).map { memo.bound(it, upper = false) }
        val memoWork = native.work - beforeMemo
        val beforeFresh = native.work
        val fresh = (1..7).map { native.sharedBoundPremise(it, false, shared, { it }, 0) }
        val freshWork = native.work - beforeFresh

        for (premise in retained + fresh) {
            assertContentEquals(intArrayOf(1), assertNotNull(shared.explainAtoms(premise)).literals)
        }
        assertTrue(memoWork < freshWork, "memo=$memoWork fresh=$freshWork")
    }

    @Test
    fun `shared decision chains should explain with less reconstruction work`() {
        val native = PropagationSession(Problem(0, 8, Array(8) { IntDomain(0, 10) },
            Array(7) { Linear(intArrayOf(1, -1), intArrayOf(it, it + 1), LinearOp.LE, 0) }),
            pbLearning = false)
        val shared = SearchSession(listOf(CpSearchComponent(native)), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(0, 3)))
        for (variable in 1..7) native.sharedBoundPremise(variable, false, shared, { it }, 0)
        val memo = CpPublicationPremises(native, shared, { it }, rootLevel = 0)
        val beforeMemo = native.work

        val retained = (1..7).map { memo.bound(it, upper = false) }
        val memoWork = native.work - beforeMemo
        val beforeFresh = native.work
        val fresh = (1..7).map { native.sharedBoundPremise(it, false, shared, { it }, 0) }
        val freshWork = native.work - beforeFresh

        for (index in retained.indices) {
            assertContentEquals(assertNotNull(shared.explainAtoms(fresh[index])).literals,
                assertNotNull(shared.explainAtoms(retained[index])).literals)
        }
        assertTrue(memoWork < freshWork, "memo=$memoWork fresh=$freshWork")
    }

    @Test
    fun `inherited unavailable leaves should remain unavailable across publication proofs`() {
        for (depth in 0..3) {
            val native = PropagationSession(Problem(0, 4, Array(4) { IntDomain(0, 10) },
                Array(3) { Linear(intArrayOf(1, -1), intArrayOf(it, it + 1), LinearOp.LE, 0) }),
                pbLearning = false)
            var inherited: SearchAtomPremise = SearchAtomPremise.Unavailable
            repeat(depth) { inherited = SearchAtomPremise.All(listOf(inherited)) }
            val peer = object : SearchComponent {
                override fun initialize(context: SearchContext): ComponentResult = context.publish(
                    SearchDecision.IntAtLeast(0, 3), inherited)
            }
            val shared = SearchSession(listOf(peer, CpSearchComponent(native)), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            val memo = CpPublicationPremises(native, shared, { it }, rootLevel = 0)

            val premises = (1..3).map { memo.bound(it, upper = false) }

            for (premise in premises) assertNull(shared.explainAtoms(premise))
        }
    }

    @Test
    fun `unavailable proof branch should not replace an independent named premise`() {
        val native = PropagationSession(Problem(2, 4, Array(4) { IntDomain(0, 10) }, arrayOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.LE, 0),
        )), pbLearning = false)
        val peer = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult = when (decision) {
                SearchDecision.Bool(0) -> context.publish(
                    SearchDecision.IntAtLeast(0, 3), SearchAtomPremise.All(listOf(SearchAtomPremise.Unavailable)),
                )
                SearchDecision.Bool(2) -> context.publish(
                    SearchDecision.IntAtLeast(2, 4), SearchAtomPremise.Asserted(decision),
                )
                else -> ComponentResult.Consistent
            }
        }
        val shared = SearchSession(listOf(peer, CpSearchComponent(native)), atoms = SearchAtomRegistry(2))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(0)))
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(2)))
        val publication = CpPublicationPremises(native, shared, { it }, rootLevel = 0)

        val unavailable = publication.bound(1, upper = false)
        val named = publication.bound(3, upper = false)
        val unavailableAgain = publication.bound(1, upper = false)
        val namedAgain = publication.bound(3, upper = false)

        assertNull(shared.explainAtoms(unavailable))
        assertContentEquals(intArrayOf(3), assertNotNull(shared.explainAtoms(named)).literals)
        assertNull(shared.explainAtoms(unavailableAgain))
        assertContentEquals(intArrayOf(3), assertNotNull(shared.explainAtoms(namedAgain)).literals)
    }
}
