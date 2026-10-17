package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExecutionControlAdapter
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchStopCause
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLiraSolverBranchingTest {
    @Test
    fun `an alternate context should supply native operation controls without session casts`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
        )
        val controls = SearchExecutionControlAdapter(allowance = { Cancellation { true } })
        val context = object : SearchContext by SearchSession(emptyList()) {
            override val executionControls = controls
        }

        ExactLiraSearchComponent(model).use { component ->
            assertIs<ComponentResult.Indeterminate>(component.initialize(context))

            assertEquals(SearchStopCause.OperationAllowance, controls.stopCause)
            assertTrue(!context.cancelled())
        }
    }

    @Test
    fun `branching should decline when its operation allowance expires`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
        )
        var spent = false
        val context = SearchSession(emptyList())

        ExactLiraSearchComponent(model).use { component ->
            component.useOperationAllowance { parent -> Cancellation { parent() || spent } }
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            spent = true

            assertNull(component.nextBranch(context))
            assertIs<ComponentCheck.Indeterminate>(component.check(context))
            assertTrue(component.operationBudgetExhausted)
            assertTrue(!context.cancelled())
        }
    }

    @Test
    fun `successful theory assertions and checks should receive fresh operation allowances`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)),
        )
        val context = SearchSession(emptyList())
        var attempts = 0
        var firstSpent = false

        ExactLiraSearchComponent(model).use { component ->
            component.useOperationAllowance { parent ->
                val attempt = ++attempts
                Cancellation { parent() || (attempt == 1 && firstSpent) }
            }
            assertIs<ComponentResult.Consistent>(component.initialize(context))
            firstSpent = true

            assertIs<ComponentResult.Consistent>(component.assert(SearchDecision.IntAtMost(0, 1L), context))
            assertIs<ComponentResult.Consistent>(component.propagate(context))
            assertIs<ComponentResult.Consistent>(component.assert(SearchDecision.IntAtLeast(0, 0L), context))
            assertIs<ComponentResult.Consistent>(component.propagate(context))
            assertEquals(5, attempts)
            assertTrue(!component.operationBudgetExhausted)
            assertTrue(!context.cancelled())
        }
    }

    @Test
    fun `fractional source integers should branch after reduction admission declines`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                LongArray(129),
                LongArray(129) { if (it == 0) 1L else 0L },
                null,
                null,
            ),
            factors = arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.GE, 1)),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(0))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(BIG_ONE, assignment.ints[0])
            assertTrue(stats.snapshot().sourceLp.operations > 0L)
            assertEquals(0L, stats.snapshot().reductionRequests)
        }
    }

    @Test
    fun `source split should leave an unchecked branch indeterminate`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(35), LongArray(35) { if (it == 0) 1L else 0L }, null, null),
            factors = arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.GE, 1)),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0), maxChecks = 2)
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<SearchResult.Indeterminate>(session.solve(0))

            assertTrue(stats.snapshot().sourceLp.operations > 0L)
            assertEquals(0L, stats.snapshot().unexplainedConflicts)
        }
    }

    @Test
    fun `a source bound after a root graph witness should retain conflicts and retraction`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertIs<ComponentCheck.Feasible>(component.check(session))
            val split = realSignSplit(session)

            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))

            assertContentEquals(intArrayOf(split.negative.literal), assertNotNull(conflict.explanation).literals)
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(split.negative)))
            assertIs<ComponentCheck.Feasible>(component.check(session))
        }
    }

    @Test
    fun `root restart should retain conditional reasons and registered atom names`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(2.0), LinearOp.GE, 0.0),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        source.componentPlan().search(
            source,
            emptyMap(),
            Long.MAX_VALUE,
            Cancellation.Never,
            SearchLearnedDbParams(),
            null,
        ).use { planned ->
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = realSignSplit(session)
            assertIs<ComponentResult.Consistent>(session.publish(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertIs<ComponentResult.Consistent>(session.restart())
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))

            assertContentEquals(
                intArrayOf(Lit.make(0, false), split.negative.literal).sortedArray(),
                assertNotNull(conflict.explanation).literals.sortedArray(),
            )
            val registered = realSignSplit(session)
            assertEquals(split.positive.literal, registered.positive.literal)
        }
    }

    @Test
    fun `source branches should refute an integer system with a satisfiable equality subset`() {
        // Unequal bounds on the padding columns keep the full reduction past its row limit while the
        // equality subset stays small.
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(66), LongArray(66) { 1L }, null, null),
            numRealVars = 3,
            realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1, 2), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(5), intArrayOf(0), LinearOp.GE, 1),
                Linear(intArrayOf(5), intArrayOf(0), LinearOp.LE, 4),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<SearchResult.Exhausted>(session.solve(0))

            assertTrue(stats.snapshot().sourceLp.operations > 0L)
            assertEquals(0L, stats.snapshot().unexplainedConflicts)
        }
    }

    @Test
    fun `native and published integer sides should keep the strongest bound with real column offsets`() {
        for (upper in listOf(false, true)) {
            for (difference in -1..1) {
                val published = if (upper) 3L - difference else 1L + difference
                val strongest = if (upper) minOf(3L, published) else maxOf(1L, published)
                val excluded = strongest + if (upper) 1L else -1L
                val model = Problem(
                    numBoolVars = 1,
                    intBounds = IntBounds.fromModelBounds(longArrayOf(1L), longArrayOf(3L), null, null),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(0.0),
                    factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, excluded.toInt())),
                )
                ExactLiraSearchComponent(model).use { component ->
                    val session = SearchSession(listOf(component))
                    assertIs<ComponentResult.Consistent>(session.initialize())
                    val bound = if (upper) {
                        SearchDecision.IntAtMost(0, published)
                    } else {
                        SearchDecision.IntAtLeast(0, published)
                    }
                    assertIs<ComponentResult.Consistent>(session.push(bound))

                    assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                }
            }
        }
    }

    @Test
    fun `a retracted published integer side should not constrain its sibling reduction`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0L, 0L),
                longArrayOf(1L, 0L),
                null,
                Bits(2).also { it.set(1) },
            ),
            factors = arrayOf(Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, 0L)))
            assertIs<SearchResult.Exhausted>(session.solve(0))
            assertTrue(stats.snapshot().sourceLp.operations > 0L)
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtLeast(0, 1L)))

            val result = assertIs<SearchResult.Satisfied>(session.solve(0))

            val assignment = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(listOf(BIG_ONE, BIG_ZERO), assignment.ints.toList())
        }
    }

    @Test
    fun `crossed native and published integer sides should stay a local conflict`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(1L), null, null),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.IntAtLeast(0, 2L)))
            session.popTo(0)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, 1L)))

            assertIs<SearchResult.Satisfied>(session.solve(0))
        }
    }

    @Test
    fun `a learned strict conflict should backjump to an exact source witness`() {
        val model = Problem(
            1,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                    strict = true,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            session.initialize()
            assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))

            val result = assertIs<SearchResult.Satisfied>(session.solve(1))

            val point = assertNotNull(result.model.valueOf<ExactLraAssignment>(component))
            assertEquals(false, point.bools.single())
            assertTrue(point.reals.single() <= BigFraction.ONE)
        }
    }

    @Test
    fun `strict mixed candidates should use shared integer branching`() {
        val model = Problem(
            0,
            intBounds = openBounds(),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    0.0,
                    strict = true,
                ),
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.5,
                    strict = true,
                ),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.observeWith(stats)
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(0))
            session.initialize()

            val result = assertIs<SearchResult.Satisfied>(session.solve(0))

            val point = assertNotNull(result.model.valueOf<ExactLiraAssignment>(component))
            assertEquals(BIG_ONE, point.ints.single())
            assertEquals(BigFraction.ONE, point.reals.single())
            assertTrue(stats.snapshot().sourceLp.operations > 0L)
        }
    }

    @Test
    fun `rejected strict LP evidence should not invoke a private strict fallback`() {
        val model = Problem(
            0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    0.0,
                    strict = true,
                ),
            ),
        )
        val stats = SmtStatsSink()
        ExactLiraSearchComponent(model).use { component ->
            component.solveWith(LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }))
            component.observeWith(stats)
            val session = SearchSession(listOf(component))
            session.initialize()

            assertIs<SearchResult.Indeterminate>(session.solve(0))
            assertEquals(0L, stats.snapshot().sourceLp.operations)
        }
    }

    @Test
    fun `permanent term bounds should survive nested scopes and shared restarts`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(0),
            numRealVars = 3,
            realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY },
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1, 2),
                    doubleArrayOf(1.0, 1.0, -1.0),
                    LinearOp.EQ,
                    0.0,
                ),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(2),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.0,
                ),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(1, 0),
                    doubleArrayOf(-2.0, -2.0),
                    LinearOp.LE,
                    -2.0,
                    strict = true,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            assertIs<ComponentResult.Consistent>(session.initialize())
            session.publish(SearchDecision.Bool(Lit.make(0, true)))
            assertIs<ComponentResult.Consistent>(session.propagate())
            repeat(2) {
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
                assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, true))))
                session.popTo(1)
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, false))))
                assertIs<ComponentResult.Consistent>(session.restart())
            }
        }
    }

    @Test
    fun `a strict all fixed expression should be contradictory after source reconstruction`() {
        val model = Problem(
            0,
            intBounds = openBounds(0),
            numRealVars = 1,
            realLower = doubleArrayOf(2.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.LE,
                    4.0,
                    strict = true,
                ),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            session.initialize()

            assertIs<SearchResult.Exhausted>(session.solve(0))
        }
    }

    @Test
    fun `shared signed forms should agree with source exact checks for strict complements`() {
        for (scale in listOf(0.5, -2.0)) {
            val model = Problem(
                2,
                intBounds = openBounds(0),
                numRealVars = 2,
                realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                factors = arrayOf(
                    ReifiedRealLinear(
                        0,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0, 1),
                        doubleArrayOf(1.0, 2.0),
                        LinearOp.LE,
                        3.0,
                    ),
                    ReifiedRealLinear(
                        1,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(1, 0),
                        doubleArrayOf(2.0 * scale, scale),
                        if (scale > 0) LinearOp.LE else LinearOp.GE,
                        3.0 * scale,
                    ),
                ),
            )
            ExactLiraSearchComponent(model).use { component ->
                val session = SearchSession(listOf(component))
                session.initialize()
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
                assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(1, false))))
                session.popTo(1)
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
                assertIs<ComponentCheck.Feasible>(component.check(session))
            }
        }
    }

    @Test
    fun `rational canonical mixed terms should retain source integer branching`() {
        val model = Problem(
            0,
            intBounds = openBounds(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(2.0), intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0),
            ),
        )
        ExactLiraSearchComponent(model).use { component ->
            val session = SearchSession(listOf(component))
            session.initialize()

            assertIs<SearchResult.Exhausted>(session.solve(1))
        }
    }

    @Test
    fun `repeated partial conflicts should omit an irrelevant assertion after retraction`() {
        val source = partialModel()
        val model = Problem(3, intBounds = source.intBounds, factors = source.factors)
        val stats = SmtStatsSink()
        val component = ExactLiraSearchComponent(model).also { it.observeWith(stats) }
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))

        repeat(3) {
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, true))))
            assertContentEquals(intArrayOf(Lit.make(0, false)), conflict.explanation?.literals)
            session.popTo(1)
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
            session.popTo(1)
        }

        assertEquals(3L, stats.snapshot().explainedConflicts)
        assertEquals(3L, stats.snapshot().conflictLiterals)
    }

    @Test
    fun `partial integer equality should be relaxed before complete chronological refutation`() {
        val model = Problem(
            2,
            intBounds = openBounds(),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, true))))
        assertIs<SearchResult.Exhausted>(session.solve(2))
    }

    private fun partialModel(): Problem = Problem(
        numBoolVars = 2,
        intBounds = openBounds(),
        factors = arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
        ),
    )

    private fun realSignSplit(session: SearchContext) = assertNotNull(
        SourceBoundAtom.rationalSplit(
            session,
            listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
            BigFraction.ZERO,
        ),
    )

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
