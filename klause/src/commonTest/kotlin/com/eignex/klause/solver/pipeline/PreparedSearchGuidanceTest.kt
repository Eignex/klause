package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.collections.Bits
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedSearchGuidanceTest {
    @Test
    fun `factory openings without a default component should keep preparation absent and unobserved`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
                factors = arrayOf(
                    Clause(intArrayOf(1, 2)),
                    Clause(intArrayOf(0, 4)),
                    ReifiedRealLinear(
                        1,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                    ReifiedRealLinear(
                        2,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        1.0,
                    ),
                ),
            )
            val prepared = kotlin.test.assertNotNull(prepareExactLiraSource(source, ExecutionControl.Never))
            var positiveTrace: List<String>? = null
            for (allowance in listOf(1L, 0L, null)) {
                val trace = ArrayList<String>()
                val stop = object : ExecutionControl() {
                    override fun isCancelled(): Boolean {
                        trace += "poll"
                        return false
                    }
                    override val accounting: WorkMeter get() = WorkMeter { trace += "charge:$it" }
                }
                val stats = SmtStatsSink()
                val resources = SearchInvocationResources()
                val opening = SearchComponentOpening(stop, stats, null, resources, allowance)
                val factory = kotlin.test.assertNotNull(
                    source.componentPlan().theoryComponentFactory(source) { prepared },
                )
                try {
                    factory(opening)
                    val hints = opening.candidateHints(source)
                    when (allowance) {
                        null -> {
                            assertTrue(trace.isEmpty())
                            assertSame(SearchCandidateHints.None, hints)
                            assertEquals(null, stats.hintUsage.snapshot().rawLedgerDebits)
                        }

                        0L -> {
                            assertEquals(positiveTrace, trace)
                            assertSame(SearchCandidateHints.None, hints)
                            assertEquals(2L, stats.hintUsage.snapshot().rawClausesMax)
                        }

                        else -> {
                            assertTrue(trace.isNotEmpty())
                            assertNotEquals(SearchCandidateHints.None, hints)
                            positiveTrace = trace.toList()
                        }
                    }
                } finally {
                    resources.close()
                }
            }
        }
    }

    @Test
    fun `explicit hint preparation should not override live state or caller precedence`() {
        for ((openingAllowance, providerAllowance, exposed) in listOf(
            Triple(0L, 0L, false),
            Triple(1L, 1L, true),
            Triple(0L, null, true),
            Triple(null, null, true),
        )) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2))))
            var stopped = false
            var available = false
            var releases = 0
            var calls = 0
            val resources = SearchInvocationResources()
            val stats = SmtStatsSink()
            val opening = SearchComponentOpening(ExecutionControl { stopped }, stats, null, resources, openingAllowance)
            opening.registerCandidateHints(
                intArrayOf(0, 1),
                SearchCandidateHints {
                    calls++
                    false
                },
                { releases++ },
                { available },
                openHintFlips = providerAllowance,
            )
            val hints = opening.candidateHints(source)
            assertEquals(1L, stats.hintUsage.snapshot().indexesAdmitted)
            assertEquals(0, releases)
            assertNull(hints.preferredBool(0))
            val caller = observedCandidateHints(SearchCandidateHints { true }, hints, stats.hintUsage)
            available = true
            assertEquals(true, caller.preferredBool(0))
            assertEquals(0, calls)

            assertEquals(if (exposed) false else null, hints.preferredBool(0))

            assertEquals(if (exposed) 1 else 0, calls)
            stopped = true
            assertNull(hints.preferredBool(0))
            assertEquals(if (exposed) 1 else 0, calls)
            resources.close()
            resources.close()
            assertEquals(1, releases)
            stopped = false
            assertNull(hints.preferredBool(0))
            assertEquals(if (exposed) 1 else 0, calls)
        }
    }

    @Test
    fun `invalid or stopped primitive admission should still release the registered provider`() {
        for (stopped in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2))))
            val resources = SearchInvocationResources()
            val opening = SearchComponentOpening(ExecutionControl { stopped }, null, null, resources)
            var released = false
            var calls = 0
            opening.registerCandidateHints(
                intArrayOf(0, 2),
                SearchCandidateHints {
                    calls++
                    true
                },
                { released = true },
                { true },
            )

            val hints = opening.candidateHints(source)

            assertSame(SearchCandidateHints.None, hints)
            assertTrue(released)
            assertEquals(0, calls)
            resources.close()
            assertEquals(0, calls)
        }
    }

    @Test
    fun `declared Boolean should follow implications of a live partial real point`() {
        val source = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(0, 4)),
                ReifiedRealLinear(
                    1,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                ReifiedRealLinear(
                    2,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        for ((allowance, callerWins, guided) in listOf(
            Triple(null, false, false),
            Triple(0L, false, false),
            Triple(1L, false, true),
            Triple(1L, true, false),
        )) {
            val stats = SmtStatsSink()
            source.componentPlan().search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                ExecutionControl.Never,
                SearchLearnedDbParams(),
                stats,
                openHintFlips = allowance,
            ).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                val caller = if (callerWins) SearchCandidateHints { false } else SearchCandidateHints.None

                val result = assertIs<SearchResult.Satisfied>(
                    planned.session.solve(
                        3,
                        SearchSolveParams(maxDecisions = 2),
                        candidateHints = observedCandidateHints(
                            caller,
                            planned.candidateHints,
                            stats.hintUsage,
                        ),
                    ),
                )

                assertEquals(guided, result.model.valueOf(SearchBoolValue(0)))
                assertEquals(guided, result.model.valueOf(SearchBoolValue(1)))
                assertEquals(!guided, result.model.valueOf(SearchBoolValue(2)))
            }
            val usage = stats.hintUsage.snapshot()
            assertEquals(if (guided) 1L else 0L, usage.clauseSelections)
            if (callerWins) assertEquals(0L, usage.componentSelections)
        }
    }

    @Test
    fun `default real and mixed openings should guide polarity and keep a conflicting caller alternative`() {
        for (integerCount in listOf(0, 1)) {
            val source = guardedZeroSource(integerCount, numBoolVars = 1)
            for (callerWins in listOf(false, true)) {
                val stats = SmtStatsSink()
                source.componentPlan().search(
                    source,
                    emptyMap(),
                    Long.MAX_VALUE,
                    ExecutionControl.Never,
                    SearchLearnedDbParams(),
                    stats,
                ).use { planned ->
                    assertIs<ComponentResult.Consistent>(planned.session.initialize())
                    val caller = if (callerWins) SearchCandidateHints { false } else SearchCandidateHints.None

                    val result = assertIs<SearchResult.Satisfied>(
                        planned.session.solve(
                            1,
                            SearchSolveParams(maxDecisions = if (callerWins) 2 else 1),
                            candidateHints = observedCandidateHints(caller, planned.candidateHints, stats.hintUsage),
                        ),
                    )

                    assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
                    assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
                    if (integerCount > 0) {
                        assertEquals(BIG_ZERO, result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
                    } else {
                        assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(1))))
                    }
                }
                val usage = stats.hintUsage.snapshot()
                assertEquals(if (callerWins) 0L else 1L, usage.componentSelections)
                assertEquals(if (callerWins) 1L else 0L, usage.callerSelections)
            }
        }
    }

    @Test
    fun `default LP guidance should use source cardinalities kept by their finite owner`() {
        for (minimum in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
                    Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)), minimum, 1),
                    ReifiedRealLinear(
                        0,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                ),
            )
            val stats = SmtStatsSink()
            val plan = source.componentPlan()
            assertEquals(FactorOwner.CP, plan.factorOwner(0))
            plan.search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                ExecutionControl.Never,
                SearchLearnedDbParams(),
                stats,
            ).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                val result = assertIs<SearchResult.Satisfied>(
                    planned.session.solve(
                        3,
                        SearchSolveParams(maxDecisions = 3),
                        candidateHints = planned.candidateHints,
                    ),
                )
                assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
                assertEquals(false, result.model.valueOf(SearchBoolValue(1)))
                assertEquals(false, result.model.valueOf(SearchBoolValue(2)))
                assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
            }
        }
    }

    @Test
    fun `real LP openings should use default and explicit point guidance`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.0),
            factors = arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
            ),
        )
        for (allowance in listOf(null, 0L, 1L)) {
            for (callerWins in listOf(false, true)) {
                val stats = SmtStatsSink()
                source.componentPlan().search(
                    source,
                    emptyMap(),
                    Long.MAX_VALUE,
                    ExecutionControl.Never,
                    SearchLearnedDbParams(),
                    stats,
                    openHintFlips = allowance,
                ).use { planned ->
                    assertIs<ComponentResult.Consistent>(planned.session.initialize())
                    val caller = if (callerWins) SearchCandidateHints { false } else SearchCandidateHints.None

                    val result = assertIs<SearchResult.Satisfied>(
                        planned.session.solve(
                            1,
                            SearchSolveParams(maxDecisions = 2),
                            candidateHints = observedCandidateHints(
                                caller,
                                planned.candidateHints,
                                stats.hintUsage,
                            ),
                        ),
                    )

                    assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
                    assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
                }
                val usage = stats.hintUsage.snapshot()
                assertEquals(if (callerWins) 1L else 0L, usage.callerSelections)
                assertEquals(if (!callerWins && allowance != 0L) 1L else 0L, usage.componentSelections)
            }
        }
    }

    @Test
    fun `default integer openings should guide polarity and keep a conflicting caller alternative`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        val plan = source.componentPlan()
        for (callerWins in listOf(false, true)) {
            val stats = SmtStatsSink()
            plan.search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                ExecutionControl.Never,
                SearchLearnedDbParams(),
                stats,
            ).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                val caller = if (callerWins) SearchCandidateHints { false } else SearchCandidateHints.None

                val result = assertIs<SearchResult.Satisfied>(
                    planned.session.solve(
                        1,
                        SearchSolveParams(maxDecisions = if (callerWins) 2 else 1),
                        candidateHints = observedCandidateHints(caller, planned.candidateHints, stats.hintUsage),
                    ),
                )

                assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
                val first = kotlin.test.assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
                val second = kotlin.test.assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(1))))
                assertEquals(BIG_ZERO, first + second)
            }
            val usage = stats.hintUsage.snapshot()
            assertEquals(if (callerWins) 0L else 1L, usage.componentSelections)
            assertEquals(if (callerWins) 1L else 0L, usage.callerSelections)
        }
    }

    @Test
    fun `explicit integer opening allowances should keep primitive preparation absent`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        for (allowance in listOf(0L, 1L)) {
            val stats = SmtStatsSink()
            source.componentPlan().search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                ExecutionControl.Never,
                SearchLearnedDbParams(),
                stats,
                openHintFlips = allowance,
            ).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                assertSame(SearchCandidateHints.None, planned.candidateHints)
                assertEquals(false, assertIs<ExactLiraSearchComponent>(planned.theory).partialPointHintAvailable())
            }
            val usage = stats.hintUsage.snapshot()
            assertEquals(1L, usage.noProvider)
            assertEquals(0L, usage.componentSelections)
            assertEquals(0L, usage.indexesAdmitted)
            assertNull(usage.rawLedgerDebits)
        }
    }

    @Test
    fun `default integer openings without an active arithmetic row should keep ordinary traversal`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        val plan = source.componentPlan()
        assertEquals(ProblemPipeline.EXACT_LIRA, plan.theoryPipeline)
        val stats = SmtStatsSink()
        plan.search(
            source,
            emptyMap(),
            Long.MAX_VALUE,
            ExecutionControl.Never,
            SearchLearnedDbParams(),
            stats,
        ).use { planned ->
            assertIs<ComponentResult.Consistent>(planned.session.initialize())
            assertNull(planned.session.boolValue(0))

            assertNull(planned.candidateHints.preferredBool(0))

            assertIs<SearchResult.Satisfied>(
                planned.session.solve(
                    1,
                    candidateHints = observedCandidateHints(
                        SearchCandidateHints.None,
                        planned.candidateHints,
                        stats.hintUsage,
                    ),
                ),
            )
        }
        assertEquals(0L, stats.hintUsage.snapshot().componentSelections)
        assertEquals(0L, stats.hintUsage.snapshot().indexesAdmitted)
    }

    @Test
    fun `default primitive preferences should expire on arithmetic retraction and opening close`() {
        for (integerCount in listOf(0, 1)) {
            val source = guardedZeroSource(integerCount, numBoolVars = 2)
            val planned = source.componentPlan().search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                ExecutionControl.Never,
                SearchLearnedDbParams(),
            )
            try {
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertEquals(true, planned.candidateHints.preferredBool(0))
                assertNull(planned.candidateHints.preferredBool(1))
                assertIs<ComponentResult.Consistent>(planned.session.push(SearchDecision.Bool(Lit.make(0, true))))

                planned.session.popTo(0)

                assertNull(planned.candidateHints.preferredBool(0))
                assertIs<ComponentResult.Consistent>(planned.session.push(SearchDecision.Bool(Lit.make(1, true))))
                assertEquals(true, planned.candidateHints.preferredBool(0))
            } finally {
                planned.close()
            }
            assertNull(planned.candidateHints.preferredBool(0))
        }
    }

    @Test
    fun `custom provider should decide without consulting the default primitive component`() {
        for ((integerCount, realCount) in listOf(0 to 1, 1 to 1, 2 to 0)) {
            val open = Bits(integerCount).also { bits -> repeat(integerCount, bits::set) }
            val source = if (realCount == 0) {
                Problem(
                    numBoolVars = 1,
                    intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), open, open),
                    factors = arrayOf(
                        Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
                        ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                    ),
                )
            } else {
                Problem(
                    numBoolVars = 1,
                    intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(0.0),
                    factors = arrayOf(
                        ReifiedRealLinear(
                            0,
                            intArrayOf(),
                            doubleArrayOf(),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.LE,
                            0.0,
                        ),
                    ),
                )
            }
            val prepared = kotlin.test.assertNotNull(prepareExactLiraSource(source, ExecutionControl.Never))
            for (preference in listOf(false, null)) {
                val resources = SearchInvocationResources()
                val opening = SearchComponentOpening(ExecutionControl.Never, null, null, resources)
                var calls = 0
                var releases = 0
                opening.registerCandidateHints(
                    intArrayOf(0),
                    SearchCandidateHints {
                        calls++
                        preference
                    },
                    { releases++ },
                    { true },
                )
                val factory = kotlin.test.assertNotNull(
                    source.componentPlan().theoryComponentFactory(source) { prepared },
                )
                try {
                    val component = assertIs<ExactLiraSearchComponent>(factory(opening).single())
                    val hints = opening.candidateHints(source, component)
                    val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(1))
                    assertIs<ComponentResult.Consistent>(session.initialize())

                    assertEquals(preference, hints.preferredBool(0))

                    assertEquals(1, calls)
                    assertEquals(false, component.partialPointHintAvailable())
                } finally {
                    resources.close()
                }
                assertEquals(1, releases)
            }
        }
    }

    @Test
    fun `interrupted default primitive registration should keep its debit without a callback`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
                    ReifiedRealLinear(
                        0,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                ),
            )
            val prepared = kotlin.test.assertNotNull(prepareExactLiraSource(source, ExecutionControl.Never))
            var stopped = false
            var charged = 0L
            val stop = object : ExecutionControl() {
                override fun isCancelled(): Boolean = stopped
                override val accounting: WorkMeter get() = WorkMeter {
                    charged += it
                    stopped = true
                }
            }
            val resources = SearchInvocationResources()
            val opening = SearchComponentOpening(stop, null, null, resources)
            val factory = kotlin.test.assertNotNull(source.componentPlan().theoryComponentFactory(source) { prepared })
            try {
                val component = assertIs<ExactLiraSearchComponent>(factory(opening).single())

                assertSame(SearchCandidateHints.None, opening.candidateHints(source, component))

                assertTrue(charged > 0L)
                assertTrue(stopped)
                assertEquals(false, component.partialPointHintAvailable())
            } finally {
                resources.close()
            }
        }
    }

    @Test
    fun `default prepared point capture should honor a separate containing stop and keep work`() {
        for (integerCount in listOf(0, 1)) {
            val source = guardedZeroSource(integerCount, numBoolVars = 1)
            var stopped = false
            var charged = 0L
            val stop = object : ExecutionControl() {
                override fun isCancelled(): Boolean = stopped
                override val accounting: WorkMeter get() = WorkMeter { charged += it }
            }
            source.componentPlan().search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                stop,
                SearchLearnedDbParams(),
                smtStats = null,
                theorySolveStop = ExecutionControl.Never,
            ).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertTrue(charged > 0L)
                assertEquals(true, planned.candidateHints.preferredBool(0))
                val spent = charged

                stopped = true

                assertNull(planned.candidateHints.preferredBool(0))
                assertNull(planned.session.boolValue(0))
                assertEquals(spent, charged)
            }
        }
    }

    @Test
    fun `earlier nested signed source gate should reuse partial real point preferences`() {
        for (positive in listOf(false, true)) {
            val target = Lit.make(0, positive)
            val inner = Lit.make(1, false)
            val left = Lit.make(2, true)
            val right = Lit.make(3, false)
            val last = Lit.make(4, true)
            val source = Problem(
                numBoolVars = 5,
                numIntVars = 0,
                intDomains = emptyArray(),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.EQ,
                        0.0,
                    ),
                    ReifiedRealLinear(
                        2,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                    ReifiedRealLinear(
                        3,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        1.0,
                    ),
                    ReifiedRealLinear(
                        4,
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        0.0,
                    ),
                    Clause(intArrayOf(Lit.negate(inner), left)),
                    Clause(intArrayOf(Lit.negate(inner), right)),
                    Clause(intArrayOf(inner, Lit.negate(left), Lit.negate(right))),
                    Clause(intArrayOf(Lit.negate(target), inner)),
                    Clause(intArrayOf(Lit.negate(target), last)),
                    Clause(intArrayOf(target, Lit.negate(inner), Lit.negate(last))),
                ),
            )
            val stats = SmtStatsSink()
            source.componentPlan().search(
                source,
                emptyMap(),
                Long.MAX_VALUE,
                ExecutionControl.Never,
                SearchLearnedDbParams(),
                stats,
                openHintFlips = 1L,
            ).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertEquals(positive, planned.candidateHints.preferredBool(0))

                val result = assertIs<SearchResult.Satisfied>(
                    planned.session.solve(
                        5,
                        SearchSolveParams(maxDecisions = 1),
                        candidateHints = observedCandidateHints(
                            SearchCandidateHints.None,
                            planned.candidateHints,
                            stats.hintUsage,
                        ),
                    ),
                )

                assertEquals(positive, result.model.valueOf(SearchBoolValue(0)))
                assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
            }
            assertEquals(1L, stats.hintUsage.snapshot().gateSelections)
        }
    }

    private fun guardedZeroSource(integerCount: Int, numBoolVars: Int) = Problem(
        numBoolVars = numBoolVars,
        intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
        numRealVars = if (integerCount == 0) 2 else 1,
        realLower = DoubleArray(if (integerCount == 0) 2 else 1),
        realUpper = DoubleArray(if (integerCount == 0) 2 else 1),
        factors = if (integerCount == 0) {
            arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 2.0), LinearOp.EQ, 0.0),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 2.0),
                    LinearOp.LE,
                    0.0,
                ),
            )
        } else {
            arrayOf(
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    0.0,
                ),
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.0),
            )
        },
    )
}
