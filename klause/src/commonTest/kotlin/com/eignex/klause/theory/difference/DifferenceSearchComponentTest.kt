package com.eignex.klause.theory.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.differenceFragmentOf
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExecutionAccounting
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DifferenceSearchComponentTest {

    @Test
    fun `partial graph guidance still requires a complete checked witness`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val component = DifferenceSearchComponent(model)
        val hints = assertNotNull(component.potentialHints(Cancellation.Never)).second
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = session.openRun(1, candidateHints = hints).next()

        assertIs<SearchRunEvent.Satisfied>(result)
        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `retraction discards sibling potential preferences`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val component = DifferenceSearchComponent(model)
        val hints = assertNotNull(component.potentialHints(Cancellation.Never)).second
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertEquals(true, hints.preferredBool(0))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertEquals(false, hints.preferredBool(0))

        session.popTo(0)

        assertNull(hints.preferredBool(0))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertEquals(false, hints.preferredBool(0))
        assertIs<SearchRunEvent.Satisfied>(session.openRun(1, candidateHints = hints).next())
        assertEquals(false, session.boolValue(0))
    }

    @Test
    fun `same sized sibling node sets retain original source coordinates`() {
        val open = Bits.full(3)
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtLeast(1, 7)))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtLeast(2, 9)))
        val result = assertIs<SearchRunEvent.Satisfied>(session.openRun(0).next())

        assertEquals(bigIntOf(0L), result.model.valueOf(SearchExactIntValue(SourceIntId(1))))
        assertEquals(bigIntOf(9L), result.model.valueOf(SearchExactIntValue(SourceIntId(2))))
    }

    @Test
    fun `overflowing sibling seed retains a current source conflict`() {
        val open = Bits.full(1)
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, Long.MIN_VALUE)))
        session.popTo(0)

        val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.IntAtLeast(0, 1)))

        assertNull(conflict.explanation)
        session.popTo(0)
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertIs<SearchRunEvent.Satisfied>(session.openRun(0).next())
    }

    @Test
    fun `containing seed work exhaustion withholds a fresh point`() {
        for ((remaining, transient) in listOf(1L to false, 3L to false, 1L to true, 3L to true)) {
            var work = 0L
            var limit = Long.MAX_VALUE
            var delivered = false
            val meter = WorkMeter { work += it }
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean {
                    if (work < limit || (transient && delivered)) return false
                    delivered = true
                    return true
                }
                override fun workMeter(): WorkMeter = meter
            }
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
            )
            val component = DifferenceSearchComponent(model)
            val hints = assertNotNull(component.potentialHints(stop)).second
            val session = SearchSession(listOf(component), cancellation = stop)
            assertIs<ComponentResult.Consistent>(session.initialize())
            limit = work + remaining

            assertIs<ComponentResult.Indeterminate>(session.propagate())

            assertNull(hints.preferredBool(0))
            assertNull(session.boolValue(0))
            assertNull(session.reasonFor(0))
            assertTrue(work >= limit)
            if (transient) {
                assertEquals(false, stop())
                assertIs<ComponentResult.Consistent>(session.propagate())
                assertNotNull(hints.preferredBool(0))
                assertIs<SearchRunEvent.Satisfied>(session.openRun(1, candidateHints = hints).next())
            } else {
                assertIs<SearchRunEvent.Indeterminate>(session.openRun(1, candidateHints = hints).next())
            }
        }
    }

    @Test
    fun `root initialization replaces a completed sibling point`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val component = DifferenceSearchComponent(model)
        val hints = assertNotNull(component.potentialHints(Cancellation.Never)).second
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.popTo(0)
        session.resetRootFacts()

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, hints.preferredBool(0))
        assertIs<SearchRunEvent.Satisfied>(session.openRun(1, candidateHints = hints).next())
    }

    @Test
    fun `declined hint index capacity preserves ordinary difference search`() {
        for (capacity in listOf(3, 4)) {
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
            )
            val component = DifferenceSearchComponent(model)
            val hints = component.potentialHints(Cancellation.Never, maxEdges = capacity)
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.openRun(1, candidateHints = hints?.second ?: SearchCandidateHints.None).next()

            assertIs<SearchRunEvent.Satisfied>(result)
            assertEquals(capacity == 4, hints != null)
        }
    }

    @Test
    fun `containing cancellation withholds a retained potential preference`() {
        var stopped = false
        val stop = Cancellation { stopped }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val component = DifferenceSearchComponent(model)
        val hints = assertNotNull(component.potentialHints(stop)).second
        val session = SearchSession(listOf(component), cancellation = stop)
        assertIs<ComponentResult.Consistent>(session.initialize())
        stopped = true

        assertNull(hints.preferredBool(0))
        assertIs<SearchRunEvent.Indeterminate>(session.openRun(1, candidateHints = hints).next())
    }

    @Test
    fun `unconditional root rows publish an equality for a finite peer`() {
        val model = Problem(
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
        val model = Problem(
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
        val model = Problem(
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
        val model = Problem(
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
        val model = Problem(
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
        val model = Problem(
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

        assertIs<ComponentResult.Indeterminate>(session.initialize())

        assertEquals(0L, session.intLowerBound(0))
        assertEquals(10L, session.intUpperBound(0))
    }

    @Test
    fun `reset root facts recomputes unconditional consequences`() {
        val open = com.eignex.klause.util.Bits.full(2)
        val model = Problem(
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
        val model = Problem(
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
        val model = Problem(
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
        val model = Problem(
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
    fun `an active source path refutes a guarded row before its Boolean decision`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(-10, -10, -10), longArrayOf(10, 10, 10), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.LE, -1),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.LE, -1),
            ),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        assertEquals(false, session.boolValue(2))
        assertEquals(setOf(1, 3, 5), session.reasonFor(2)?.literals?.toSet())
        assertIs<SearchRunEvent.Satisfied>(session.openRun(3, SearchSolveParams(maxDecisions = 0)).next())
    }

    @Test
    fun `immutable source bounds imply either signed guard without a decision`() {
        for ((relation, bound, truth) in listOf(Triple(LinearOp.LE, -1, false), Triple(LinearOp.GE, 0, true))) {
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), relation, bound)),
            )
            val session = SearchSession(listOf(DifferenceSearchComponent(model)))

            assertIs<ComponentResult.Consistent>(session.initialize())

            assertEquals(truth, session.boolValue(0))
            assertEquals(setOf(Lit.make(0, truth)), session.reasonFor(0)?.literals?.toSet())
            session.resetRootFacts()
            assertNull(session.boolValue(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertEquals(truth, session.boolValue(0))
            assertIs<SearchRunEvent.Satisfied>(session.openRun(1, SearchSolveParams(maxDecisions = 0)).next())
        }
    }

    @Test
    fun `one contradicted member refutes the whole signed source group`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, -1), longArrayOf(0, 1), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.LE, 0),
            ),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(0))
        assertEquals(setOf(0), session.reasonFor(0)?.literals?.toSet())
        assertIs<SearchRunEvent.Satisfied>(session.openRun(1, SearchSolveParams(maxDecisions = 0)).next())
    }

    @Test
    fun `a feasible point satisfying one group member supplies no Boolean proof`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, -1), longArrayOf(1, 1), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.GE, 0),
            ),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertNull(session.boolValue(0))
        assertNull(session.reasonFor(0))
        assertIs<SearchRunEvent.Satisfied>(session.openRun(1, SearchSolveParams(maxDecisions = 1)).next())
    }

    @Test
    fun `transient bounds never become unconditional guard reasons`() {
        val open = com.eignex.klause.util.Bits.full(1)
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val session = SearchSession(listOf(rootBounds(0, 0, 0), DifferenceSearchComponent(model)))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertNull(session.boolValue(0))
        assertNull(session.reasonFor(0))
        assertIs<SearchRunEvent.Satisfied>(session.openRun(1).next())
    }

    @Test
    fun `same depth sibling retraction invalidates source path implications`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(-10, -10, -10), longArrayOf(10, 10, 10), null, null),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(2, 1), LinearOp.LE, -1),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.LE, -1),
            ),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))
        assertEquals(false, session.boolValue(2))

        session.popTo(1)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertNull(session.boolValue(2))
        assertNull(session.reasonFor(2))
        assertIs<SearchRunEvent.Satisfied>(session.openRun(3).next())
    }

    @Test
    fun `declining an optional guard index retains the ordinary complete alternative`() {
        for (capacity in listOf(3, 4)) {
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
            )
            val fragment = assertNotNull(differenceFragmentOf(model.factors, model.numIntVars, model.intBounds))
            val closure = DifferenceGuardPropagation.create(fragment, 1, Cancellation.Never, maxEdges = capacity)
            val checker = DifferenceSearchComponent(model)
            val component = object : SearchComponent {
                override fun propagate(context: SearchContext): ComponentResult =
                    closure?.propagate(fragment, longArrayOf(0, 0), context) ?: ComponentResult.Consistent
                override fun check(context: SearchContext): ComponentCheck = checker.check(context)
            }
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.openRun(1, SearchSolveParams(maxDecisions = 0)).next()

            if (capacity == 4) assertIs<SearchRunEvent.Satisfied>(result)
            else {
                assertEquals(SearchRunEvent.Indeterminate.Budget, result)
                assertIs<SearchRunEvent.Satisfied>(session.openRun(1).next())
            }
        }
    }

    @Test
    fun `containing guard preparation work exhaustion publishes no source implication`() {
        var work = 0L
        val meter = WorkMeter { work += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2L
            override fun workMeter(): WorkMeter = meter
        }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
        )
        val session = SearchSession(listOf(DifferenceSearchComponent(model)), cancellation = stop)

        assertIs<ComponentResult.Indeterminate>(session.initialize())

        assertNull(session.boolValue(0))
        assertNull(session.reasonFor(0))
        assertTrue(work >= 2L)
        assertIs<SearchRunEvent.Indeterminate>(session.openRun(1).next())
    }

    @Test
    fun `guard scans latch independent containing stops and retain metered debt`() {
        for (kind in listOf("context", "token", "replacement token", "native", "meter", "dynamic meter")) {
            var armed = false
            var observed = false
            var work = 0L
            var metered = kind == "meter"
            val meter = WorkMeter { work += it }
            val owner = object : Cancellation {
                override fun isCancelled(): Boolean {
                    if (metered) return work >= 3L
                    if ((kind != "token" && kind != "replacement token") || !armed || observed) return false
                    observed = true
                    return true
                }
                override fun workMeter(): WorkMeter? = if (metered) meter else null
            }
            val accounting = object : SearchExecutionAccounting {
                override val exhausted: Boolean get() {
                    if (kind != "native" || !armed || observed) return false
                    observed = true
                    return true
                }
            }
            var current: Cancellation = if (kind == "replacement token") Cancellation.Never else owner
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
            )
            val fragment = assertNotNull(differenceFragmentOf(model.factors, model.numIntVars, model.intBounds))
            val closure = assertNotNull(DifferenceGuardPropagation.create(fragment, 1, Cancellation.Never))
            val session = SearchSession(emptyList())
            val context = object : SearchContext by session {
                override val executionControls get() = SearchExecutionControlAdapter(current, accounting)
                override fun boolValue(variable: Int): Boolean? {
                    armed = true
                    if (kind == "replacement token") current = owner
                    if (kind == "dynamic meter") metered = true
                    return session.boolValue(variable)
                }
                override fun cancelled(): Boolean {
                    if (kind != "context" || !armed || observed) return false
                    observed = true
                    return true
                }
            }

            assertIs<ComponentResult.Indeterminate>(closure.propagate(fragment, longArrayOf(0, 0), context))

            assertNull(session.boolValue(0))
            assertNull(session.reasonFor(0))
            if (metered) {
                assertTrue(work >= 3L)
                val spent = work
                assertIs<ComponentResult.Indeterminate>(closure.propagate(fragment, longArrayOf(0, 0), context))
                assertEquals(spent, work)
            } else {
                assertTrue(observed)
                assertIs<ComponentResult.Consistent>(closure.propagate(fragment, longArrayOf(0, 0), context))
                assertEquals(false, session.boolValue(0))
                assertEquals(setOf(1), session.reasonFor(0)?.literals?.toSet())
                assertIs<ComponentCheck.Feasible>(DifferenceSearchComponent(model).check(context))
            }
        }
    }

    @Test
    fun `the final guard publication poll withholds persistent and transient cancellation`() {
        for (transient in listOf(false, true)) {
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
            )
            val fragment = assertNotNull(differenceFragmentOf(model.factors, model.numIntVars, model.intBounds))
            var publicationPoll = 0
            for (cancel in listOf(false, true)) {
                var polls = 0
                val owner = Cancellation {
                    polls++
                    cancel && (if (transient) polls == publicationPoll else polls >= publicationPoll)
                }
                val session = SearchSession(emptyList())
                val context = object : SearchContext by session {
                    override val executionControls = SearchExecutionControlAdapter(owner)
                    override fun cancelled(): Boolean = false
                    override fun imply(literal: Int, explanation: SearchExplanation): ComponentResult {
                        if (!cancel) publicationPoll = polls
                        return session.imply(literal, explanation)
                    }
                }
                val closure = assertNotNull(DifferenceGuardPropagation.create(fragment, 1, Cancellation.Never))

                val result = closure.propagate(fragment, longArrayOf(0, 0), context)

                if (cancel) {
                    assertIs<ComponentResult.Indeterminate>(result)
                    assertNull(session.boolValue(0))
                    assertNull(session.reasonFor(0))
                } else {
                    assertIs<ComponentResult.Consistent>(result)
                    assertEquals(false, session.boolValue(0))
                    assertTrue(publicationPoll > 0)
                }
            }
        }
    }

    @Test
    fun `native exhaustion exposed by a final false cancellation callback withholds the guard`() {
        for (channel in listOf("context", "token")) {
            val model = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
                factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
            )
            val fragment = assertNotNull(differenceFragmentOf(model.factors, model.numIntVars, model.intBounds))
            var publicationPoll = 0
            for (exhaust in listOf(false, true)) {
                var polls = 0
                var spent = false
                val accounting = object : SearchExecutionAccounting {
                    override val exhausted: Boolean get() = spent
                }
                val owner = Cancellation {
                    if (channel == "token") {
                        polls++
                        if (exhaust && polls == publicationPoll) spent = true
                    }
                    false
                }
                val session = SearchSession(emptyList())
                val context = object : SearchContext by session {
                    override val executionControls = SearchExecutionControlAdapter(owner, accounting)
                    override fun cancelled(): Boolean {
                        if (channel == "context") {
                            polls++
                            if (exhaust && polls == publicationPoll) spent = true
                        }
                        return false
                    }
                    override fun imply(literal: Int, explanation: SearchExplanation): ComponentResult {
                        if (!exhaust) publicationPoll = polls
                        return session.imply(literal, explanation)
                    }
                }
                val closure = assertNotNull(DifferenceGuardPropagation.create(fragment, 1, Cancellation.Never))

                val result = closure.propagate(fragment, longArrayOf(0, 0), context)

                if (exhaust) {
                    assertIs<ComponentResult.Indeterminate>(result)
                    assertEquals(publicationPoll, polls)
                    assertNull(session.boolValue(0))
                    assertNull(session.reasonFor(0))
                } else {
                    assertIs<ComponentResult.Consistent>(result)
                    assertEquals(false, session.boolValue(0))
                }
            }
        }
    }

    @Test
    fun `unmetered sparse guard scans batch external polls without proving point preferences`() {
        var polls = 0
        var reads = 0
        val model = Problem(
            numBoolVars = 32,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = Array(32) { ReifiedLinear(it, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0) },
        )
        val fragment = assertNotNull(differenceFragmentOf(model.factors, model.numIntVars, model.intBounds))
        val closure = assertNotNull(DifferenceGuardPropagation.create(fragment, 32, Cancellation.Never))
        val session = SearchSession(emptyList())
        val context = object : SearchContext by session {
            override val executionControls = SearchExecutionControlAdapter(Cancellation { polls++; false })
            override fun cancelled(): Boolean = false
            override fun boolValue(variable: Int): Boolean? {
                reads++
                return session.boolValue(variable)
            }
        }

        assertIs<ComponentResult.Consistent>(closure.propagate(fragment, longArrayOf(0, 0), context))

        assertTrue(polls > 0 && polls < reads)
        for (variable in 0 until 32) {
            assertNull(session.boolValue(variable))
            assertNull(session.reasonFor(variable))
        }
    }

    private fun rootBounds(variable: Int, lower: Long, upper: Long): SearchComponent = object : SearchComponent {
        override fun initialize(context: SearchContext): ComponentResult {
            val lowerResult = context.publish(SearchDecision.IntAtLeast(variable, lower))
            if (lowerResult !is ComponentResult.Consistent) return lowerResult
            return context.publish(SearchDecision.IntAtMost(variable, upper))
        }
    }
}
