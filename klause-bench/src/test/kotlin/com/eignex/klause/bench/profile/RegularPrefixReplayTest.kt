package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RegularPrefixReplayTest {
    @Test
    fun `a retained state exclusion prunes a different source prefix after undo`() {
        val transitions = longArrayOf(1, 2, 2, 3, 3, 1)
        val source = Problem(
            0, 5, Array(5) { IntDomain(1, 2) },
            arrayOf(
                Regular(intArrayOf(0, 1, 2), 3, 2, transitions, 1, intArrayOf(1, 2, 3)),
                Regular(intArrayOf(0, 1, 2, 3, 4), 3, 2, transitions, 1, intArrayOf(1)),
                Linear(intArrayOf(1, -1), intArrayOf(3, 4), LinearOp.NE, 0),
            ),
        )
        val definitions = regularPrefixDefinitions(source)
        val producer = PropagationSession(definitions.problem)
        val cp = CpSearchComponent(producer)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(definitions.problem.numBoolVars))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.Bool(Lit.make(definitions.booleans[0], true))))
        val learned = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(learned.decisionLevel)
        assertEquals(SearchLearnedConflictResult.Resume, learned.apply(shared))
        assertIs<PropagationResult.Implied>(producer.pinInt(0, 1))
        assertIs<PropagationResult.Unsat>(producer.pinInt(1, 1))
        producer.popToLevel(0)

        assertIs<PropagationResult.Implied>(producer.pinInt(0, 2))
        assertIs<PropagationResult.Implied>(producer.pinInt(1, 2))

        assertEquals(false, producer.boolValue(definitions.booleans[0]))
        assertFalse(2L in producer.intDomain(2))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `each accepted source word has exactly its deterministic state extension`(partial: Boolean) {
        val transitions = if (partial) longArrayOf(1, 2, 2, 0, 3, 1) else longArrayOf(1, 2, 2, 3, 3, 1)
        val source = Problem(
            0,
            3,
            Array(3) { IntDomain(1, 2) },
            arrayOf(Regular(intArrayOf(0, 1, 2), 3, 2, transitions, 1, intArrayOf(1, 2, 3))),
        )
        val definitions = regularPrefixDefinitions(source)

        for (bits in 0 until 8) {
            val word = LongArray(3) { 1L + ((bits shr it) and 1) }
            val session = PropagationSession(definitions.problem)
            val result = session.seed(word.indices.fold(Assumptions.None) { pins, i -> pins.withInt(i, word[i]) })
            var q = 1L
            val states = longArrayOf(1, 0, 0, 0)
            for (i in word.indices) {
                q = if (q == 0L) 0L else transitions[(q.toInt() - 1) * 2 + word[i].toInt() - 1]
                states[i + 1] = q
            }
            if (q == 0L) {
                assertIs<PropagationResult.Unsat>(result)
            } else {
                assertIs<PropagationResult.Implied>(result)
                for (i in states.indices) {
                    assertEquals(states[i], session.intDomain(definitions.states[i]).min)
                    assertEquals(states[i], session.intDomain(definitions.states[i]).max)
                }
                for (r in 1..3) assertEquals(states[1] == r.toLong(), session.boolValue(definitions.booleans[r - 1]))
            }
        }
    }

    @Test
    fun `matching kernels share prefix states across accepting sets`() {
        val transitions = longArrayOf(1, 2, 2, 3, 3, 1)
        val source = Problem(
            0,
            5,
            Array(5) { IntDomain(1, 2) },
            arrayOf(
                Regular(intArrayOf(0, 1, 2), 3, 2, transitions, 1, intArrayOf(1, 2)),
                Regular(intArrayOf(0, 1, 2, 3, 4), 3, 2, transitions, 1, intArrayOf(1)),
            ),
        )

        val definitions = regularPrefixDefinitions(source)

        assertEquals(11, definitions.problem.numIntVars)
    }

    @Test
    fun `retracting a state decision restores the sibling prefix`() {
        val source = Problem(
            0,
            3,
            Array(3) { IntDomain(1, 2) },
            arrayOf(
                Regular(intArrayOf(0, 1, 2), 3, 2, longArrayOf(1, 2, 2, 3, 3, 1), 1, intArrayOf(1, 2, 3)),
            ),
        )
        val definitions = regularPrefixDefinitions(source)
        val session = PropagationSession(definitions.problem)
        assertIs<PropagationResult.Implied>(session.pinBool(definitions.booleans[0], true))
        assertEquals(1L, session.intDomain(0).max)

        session.popToLevel(0)

        assertIs<PropagationResult.Implied>(session.pinBool(definitions.booleans[1], true))
        assertEquals(2L, session.intDomain(0).min)
    }
}
