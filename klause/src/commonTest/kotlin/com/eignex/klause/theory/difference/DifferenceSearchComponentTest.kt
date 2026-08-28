package com.eignex.klause.theory.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DifferenceSearchComponentTest {

    @Test
    fun `unconditional root rows publish an equality for a finite peer`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(7, 0), null, null),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 7),
            ),
        )
        val bounds = rootBounds(0, 0, 7)
        val session = SearchSession(
            listOf(bounds, DifferenceSearchComponent.withRootBounds(model, intArrayOf(1), intArrayOf(0))),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(7L, session.intLowerBound(0))
        assertEquals(7L, session.intUpperBound(0))
    }

    @Test
    fun `guarded root row does not publish an unconditional bound`() {
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 7)),
        )
        val bounds = rootBounds(0, 0, 10)
        val guard = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult = context.publish(0)
        }
        val session = SearchSession(
            listOf(bounds, guard, DifferenceSearchComponent.withRootBounds(model, intArrayOf(), intArrayOf(0))),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(0L, session.intLowerBound(0))
        assertEquals(10L, session.intUpperBound(0))
    }

    @Test
    fun `unconditional root bounds report a root conflict`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(7), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        val session = SearchSession(
            listOf(
                rootBounds(0, 0, 7),
                DifferenceSearchComponent.withRootBounds(model, intArrayOf(), intArrayOf(0)),
            ),
        )

        assertIs<ComponentResult.Conflict>(session.initialize())
    }

    @Test
    fun `published root equality survives backtracking and restart`() {
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(7, 0), null, null),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 7),
            ),
        )
        val session = SearchSession(
            listOf(
                rootBounds(0, 0, 7),
                DifferenceSearchComponent.withRootBounds(model, intArrayOf(1), intArrayOf(0)),
            ),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        session.popTo(0)
        assertEquals(7L, session.intLowerBound(0))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.restart())

        assertEquals(7L, session.intLowerBound(0))
        assertEquals(7L, session.intUpperBound(0))
    }

    @Test
    fun `a Long minimum root lower bound is not negated into a false edge`() {
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(Long.MIN_VALUE), longArrayOf(3), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        val session = SearchSession(
            listOf(
                rootBounds(0, Long.MIN_VALUE, 3),
                DifferenceSearchComponent.withRootBounds(model, intArrayOf(), intArrayOf(0)),
            ),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(Long.MIN_VALUE, session.intLowerBound(0))
        assertEquals(3L, session.intUpperBound(0))
    }

    @Test
    fun `abandoned root paths publish no partial bound`() {
        val open = com.eignex.klause.util.Bits.full(2)
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 7)),
        )
        var polls = 0
        val session = SearchSession(
            listOf(
                rootBounds(0, 0, 10),
                rootBounds(1, 0, 0),
                DifferenceSearchComponent.withRootBounds(model, intArrayOf(1), intArrayOf(0)),
            ),
            cancellation = Cancellation { ++polls >= 4 },
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(0L, session.intLowerBound(0))
        assertEquals(10L, session.intUpperBound(0))
    }

    @Test
    fun `unconditional paths publish a multihop one-sided bound`() {
        val open = com.eignex.klause.util.Bits.full(3)
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(0, 0, 0), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 2),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 3),
            ),
        )
        val roots = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult {
                val x = context.publish(SearchDecision.IntAtMost(0, 10))
                if (x !is ComponentResult.Consistent) return x
                return context.publish(SearchDecision.IntAtMost(2, 4))
            }
        }
        val session = SearchSession(
            listOf(roots, DifferenceSearchComponent.withRootBounds(model, intArrayOf(1, 2), intArrayOf(0))),
        )

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(9L, session.intUpperBound(0))
        assertEquals(null, session.intLowerBound(0))
    }

    @Test
    fun `reset root facts recomputes unconditional consequences`() {
        val open = com.eignex.klause.util.Bits.full(2)
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 2)),
        )
        var y = 0L
        val roots = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult {
                val lower = context.publish(SearchDecision.IntAtLeast(0, 0))
                if (lower !is ComponentResult.Consistent) return lower
                val upper = context.publish(SearchDecision.IntAtMost(0, 10))
                if (upper !is ComponentResult.Consistent) return upper
                return context.publish(SearchDecision.IntEqual(1, y))
            }
        }
        val session = SearchSession(
            listOf(roots, DifferenceSearchComponent.withRootBounds(model, intArrayOf(), intArrayOf(0, 1))),
        )
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertEquals(2L, session.intLowerBound(0))

        session.resetRootFacts()
        y = 3L
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(5L, session.intLowerBound(0))
        assertEquals(5L, session.intUpperBound(0))
    }

    @Test
    fun `guarded-only finite peers add no root shortest-path work`() {
        val guarded = Array<Factor>(1_000) {
            ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, it)
        }
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(10, 10), null, null),
            factors = guarded,
        )

        fun cancellationPolls(rootVars: IntArray): Int {
            var polls = 0
            val session = SearchSession(
                listOf(DifferenceSearchComponent.withRootBounds(model, intArrayOf(1), rootVars)),
                cancellation = Cancellation {
                    polls++
                    false
                },
            )
            assertIs<ComponentResult.Consistent>(session.initialize())
            return polls
        }

        assertEquals(cancellationPolls(intArrayOf()), cancellationPolls(intArrayOf(0)))
    }

    @Test
    fun `large unconditional graph stays outside the root work budget`() {
        val variables = 200
        val factors = Array<Factor>(variables - 1) { variable ->
            Linear(intArrayOf(1, -1), intArrayOf(variable, variable + 1), LinearOp.LE, 0)
        }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                LongArray(variables),
                LongArray(variables) { 10 },
                null,
                null,
            ),
            factors = factors,
        )

        fun cancellationPolls(rootVars: IntArray): Int {
            var polls = 0
            val session = SearchSession(
                listOf(DifferenceSearchComponent.withRootBounds(model, intArrayOf(), rootVars)),
                cancellation = Cancellation {
                    polls++
                    false
                },
            )
            assertIs<ComponentResult.Consistent>(session.initialize())
            return polls
        }

        assertEquals(cancellationPolls(intArrayOf()), cancellationPolls(intArrayOf(0)))
    }

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

        val completeModel = session.model()
        assertEquals(0L, completeModel.valueOf<Sample>(component)?.ints?.get(0))
        assertEquals(0L, completeModel.valueOf<Long>(SearchIntValue(0)))
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
    }

    private fun rootBounds(variable: Int, lower: Long, upper: Long): SearchComponent = object : SearchComponent {
        override fun initialize(context: SearchContext): ComponentResult {
            val lowerResult = context.publish(SearchDecision.IntAtLeast(variable, lower))
            if (lowerResult !is ComponentResult.Consistent) return lowerResult
            return context.publish(SearchDecision.IntAtMost(variable, upper))
        }
    }
}
