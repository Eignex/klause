package com.eignex.klause.theory.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DifferenceSearchComponentTest {

    @Test
    fun `difference component rejects a shared bound conflict before a Boolean leaf`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        val publisher = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtLeast(0, 4))
        }
        val session = SearchSession(listOf(publisher, DifferenceSearchComponent(model)))

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `difference component contributes its complete model through the shared session`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        val component = DifferenceSearchComponent(model)
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentCheck.Feasible>(session.check())

        assertEquals(0L, session.model().valueOf<Sample>(component)?.ints?.get(0))
    }

    @Test
    fun `guarded negative cycle learns an asserting shared backjump`() {
        val model = ProblemSpec(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))
        var learned: SearchLearnedConflict? = null
        assertIs<ComponentResult.Consistent>(session.initialize())
        val run = session.openRun(
            numBoolVars = 2,
            booleanBranching = { context ->
                (0 until 2).firstOrNull { context.boolValue(it) == null }?.let { variable ->
                    listOf(SearchDecision.Bool(variable shl 1), SearchDecision.Bool((variable shl 1) or 1))
                }
            },
            observer = object : SearchRunObserver {
                override fun onLearnedConflict(conflict: SearchLearnedConflict) {
                    learned = conflict
                }
            },
        )

        assertIs<com.eignex.klause.solver.search.SearchRunEvent.Satisfied>(run.next())

        assertEquals(1, session.learnedClauseCount)
        assertEquals(1, learned?.decisionLevel)
        assertEquals(false, session.boolValue(1))

        assertIs<ComponentResult.Consistent>(session.restart())
        assertEquals(null, session.boolValue(0))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertEquals(false, session.boolValue(1))
    }
}
