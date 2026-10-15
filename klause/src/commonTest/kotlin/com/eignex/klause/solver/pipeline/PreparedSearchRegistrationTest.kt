package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.prepareExactLiraSource
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedSearchRegistrationTest {
    @Test
    fun `factory openings without a default component keep null preparation absent and zero observable`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
                factors = arrayOf(
                    Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4)),
                    ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.LE, 0.0),
                    ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.GE, 1.0),
                ),
            )
            val prepared = kotlin.test.assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
            var positiveTrace: List<String>? = null
            for (allowance in listOf(1L, 0L, null)) {
                val trace = ArrayList<String>()
                val stop = object : Cancellation {
                    override fun isCancelled(): Boolean { trace += "poll"; return false }
                    override fun workMeter(): WorkMeter = WorkMeter { trace += "charge:$it" }
                }
                val stats = SmtStatsSink()
                val resources = SearchInvocationResources()
                val opening = SearchComponentOpening(stop, stats, null, resources, allowance)
                val factory = kotlin.test.assertNotNull(
                    source.componentPlan().theoryComponentFactory(source) { prepared })
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
    fun `explicit hint preparation keeps providers owned without overriding live state or caller precedence`() {
        for ((openingAllowance, providerAllowance, exposed) in listOf(
            Triple(0L, 0L, false), Triple(1L, 1L, true), Triple(0L, null, true), Triple(null, null, true),
        )) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2))))
            var stopped = false
            var available = false
            var releases = 0
            var calls = 0
            val resources = SearchInvocationResources()
            val stats = SmtStatsSink()
            val opening = SearchComponentOpening(Cancellation { stopped }, stats, null, resources, openingAllowance)
            opening.registerCandidateHints(intArrayOf(0, 1), SearchCandidateHints { calls++; false },
                { releases++ }, { available }, openHintFlips = providerAllowance)
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
    fun `a declined structural index keeps its primitive provider owned until close`() {
        val width = 22000
        val target = Lit.make(2, true)
        val source = Problem(width + 3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.negate(target), Lit.make(0, true))),
            Clause(intArrayOf(Lit.negate(target), Lit.make(1, true))),
            Clause(intArrayOf(target, Lit.make(0, false), Lit.make(1, false))),
            Clause(IntArray(width) { Lit.make(it + 3, true) }),
        ))
        val resources = SearchInvocationResources()
        val stats = SmtStatsSink()
        val opening = SearchComponentOpening(Cancellation.Never, stats, null, resources)
        var available = false
        var releases = 0
        var calls = 0
        opening.registerCandidateHints(intArrayOf(0, 1), SearchCandidateHints { calls++; false },
            { releases++ }, { available })

        val hints = opening.candidateHints(source)

        assertNotEquals(SearchCandidateHints.None, hints)
        assertEquals(0, releases)
        assertEquals(0, calls)
        assertNull(hints.preferredBool(0))
        val caller = observedCandidateHints(SearchCandidateHints { true }, hints, stats.hintUsage)
        assertEquals(true, caller.preferredBool(0))
        assertEquals(0, calls)
        available = true
        val component = observedCandidateHints(SearchCandidateHints.None, hints, stats.hintUsage)
        assertEquals(false, component.preferredBool(0))
        assertNull(component.preferredBool(2))
        assertEquals(1, calls)
        assertEquals(1L, stats.hintUsage.snapshot().visitDeclines)
        assertEquals(0L, stats.hintUsage.snapshot().indexesAdmitted)
        assertEquals(1L, stats.hintUsage.snapshot().primitiveSelections)
        assertEquals(0L, stats.hintUsage.snapshot().gateSelections)
        assertEquals(0L, stats.hintUsage.snapshot().clauseConsultations)
        available = false
        assertNull(component.preferredBool(0))
        resources.close()
        resources.close()
        assertEquals(1, releases)
        assertNull(hints.preferredBool(0))
        assertEquals(1, calls)
    }

    @Test
    fun `invalid or stopped primitive admission still releases the registered provider`() {
        for (stopped in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2))))
            val resources = SearchInvocationResources()
            val opening = SearchComponentOpening(Cancellation { stopped }, null, null, resources)
            var released = false
            var calls = 0
            opening.registerCandidateHints(intArrayOf(0, 2), SearchCandidateHints { calls++; true },
                { released = true }, { true })

            val hints = opening.candidateHints(source)

            assertSame(SearchCandidateHints.None, hints)
            assertTrue(released)
            assertEquals(0, calls)
            resources.close()
            assertEquals(0, calls)
        }
    }

    @Test
    fun `a declared Boolean follows semantic implications of a live partial real point`() {
        val source = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            factors = arrayOf(
                Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(0, 4)),
                ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
        )
        for ((allowance, callerWins, guided) in listOf(
            Triple(null, false, false), Triple(0L, false, false),
            Triple(1L, false, true), Triple(1L, true, false),
        )) {
            val stats = SmtStatsSink()
            source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams(), stats, openHintFlips = allowance).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                if (allowance == 0L) assertSame(SearchCandidateHints.None, planned.candidateHints)
                if (allowance == null) assertNull(planned.candidateHints.preferredBool(0))
                val caller = if (callerWins) SearchCandidateHints { false }
                    else SearchCandidateHints.None

                val result = assertIs<SearchResult.Satisfied>(planned.session.solve(3,
                    SearchSolveParams(maxDecisions = 2), candidateHints = observedCandidateHints(
                        caller, planned.candidateHints, stats.hintUsage)))

                assertEquals(guided, result.model.valueOf(SearchBoolValue(0)))
                assertEquals(guided, result.model.valueOf(SearchBoolValue(1)))
                assertEquals(!guided, result.model.valueOf(SearchBoolValue(2)))
            }
            val usage = stats.hintUsage.snapshot()
            assertEquals(if (guided) 1L else 0L, usage.clauseSelections)
            if (allowance == null) {
                assertEquals(0L, usage.noProvider)
                assertEquals(null, usage.rawClausesMax)
                assertEquals(null, usage.rawLedgerDebits)
            } else {
                assertEquals(2L, usage.rawClausesMax)
                assertTrue(kotlin.test.assertNotNull(usage.rawLedgerDebits) > 0)
            }
            if (allowance == 0L) assertEquals(0L, usage.componentSelections)
            if (callerWins) {
                assertTrue(usage.callerSelections > 0)
                assertEquals(0L, usage.componentSelections)
            }
        }
    }

    @Test
    fun `default real and mixed openings guide primitive polarity and retain a conflicting caller alternative`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
                factors = arrayOf<Factor>(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0)) + if (integerCount == 0) emptyArray() else arrayOf(
                    Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.EQ, 0.0)),
            )
            for (callerWins in listOf(false, true)) {
                val stats = SmtStatsSink()
                source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                    SearchLearnedDbParams(), stats).use { planned ->
                    assertNull(planned.candidateHints.preferredBool(0))
                    assertIs<ComponentResult.Consistent>(planned.session.initialize())
                    assertNull(planned.session.boolValue(0))
                    assertEquals(true, planned.candidateHints.preferredBool(0))
                    val caller = if (callerWins) SearchCandidateHints { false } else SearchCandidateHints.None

                    val result = assertIs<SearchResult.Satisfied>(planned.session.solve(1,
                        SearchSolveParams(maxDecisions = if (callerWins) 2 else 1),
                        candidateHints = observedCandidateHints(caller, planned.candidateHints, stats.hintUsage)))

                    assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
                    assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
                    if (integerCount > 0) {
                        assertEquals(BIG_ZERO, result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
                    }
                }
                val usage = stats.hintUsage.snapshot()
                assertEquals(if (callerWins) 0L else 1L, usage.componentSelections)
                assertEquals(if (callerWins) 1L else 0L, usage.callerSelections)
                assertEquals(0L, usage.clauseConsultations)
                assertEquals(0L, usage.indexesAdmitted)
                assertNull(usage.rawLedgerDebits)
            }
        }
    }

    @Test
    fun `default integer openings guide primitive polarity and retain a conflicting caller alternative`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        val plan = source.componentPlan()
        assertEquals(ProblemPipeline.EXACT_LIRA, plan.theoryPipeline)
        assertTrue(plan.cpIntVars.isEmpty())
        for (variable in 0..1) assertEquals(IntVariableOwner.THEORY, plan.intOwner(variable))
        for (callerWins in listOf(false, true)) {
            val stats = SmtStatsSink()
            plan.search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams(), stats).use { planned ->
                assertNull(planned.candidateHints.preferredBool(0))
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertNull(planned.session.boolValue(0))
                assertEquals(true, planned.candidateHints.preferredBool(0))
                val caller = if (callerWins) SearchCandidateHints { false } else SearchCandidateHints.None

                val result = assertIs<SearchResult.Satisfied>(planned.session.solve(1,
                    SearchSolveParams(maxDecisions = if (callerWins) 2 else 1),
                    candidateHints = observedCandidateHints(caller, planned.candidateHints, stats.hintUsage)))

                assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
                val first = kotlin.test.assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
                val second = kotlin.test.assertNotNull(result.model.valueOf(SearchExactIntValue(SourceIntId(1))))
                assertEquals(BIG_ZERO, first + second)
            }
            val usage = stats.hintUsage.snapshot()
            assertEquals(if (callerWins) 0L else 1L, usage.componentSelections)
            assertEquals(if (callerWins) 1L else 0L, usage.callerSelections)
            assertEquals(0L, usage.clauseConsultations)
            assertEquals(0L, usage.indexesAdmitted)
            assertNull(usage.rawLedgerDebits)
        }
    }

    @Test
    fun `explicit integer opening allowances retain absent primitive preparation`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
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
            source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams(), stats, openHintFlips = allowance).use { planned ->
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
    fun `default integer openings without an active arithmetic row retain ordinary traversal`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        val plan = source.componentPlan()
        assertEquals(ProblemPipeline.EXACT_LIRA, plan.theoryPipeline)
        val stats = SmtStatsSink()
        plan.search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
            SearchLearnedDbParams(), stats).use { planned ->
            assertIs<ComponentResult.Consistent>(planned.session.initialize())
            assertNull(planned.session.boolValue(0))

            assertNull(planned.candidateHints.preferredBool(0))

            assertIs<SearchResult.Satisfied>(planned.session.solve(1,
                candidateHints = observedCandidateHints(SearchCandidateHints.None,
                    planned.candidateHints, stats.hintUsage)))
        }
        assertEquals(0L, stats.hintUsage.snapshot().componentSelections)
        assertEquals(0L, stats.hintUsage.snapshot().indexesAdmitted)
    }

    @Test
    fun `default mixed openings without an active arithmetic row retain ordinary traversal`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.LE, 0.0)),
        )
        val stats = SmtStatsSink()
        source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
            SearchLearnedDbParams(), stats).use { planned ->
            assertIs<ComponentResult.Consistent>(planned.session.initialize())
            assertNull(planned.session.boolValue(0))
            assertNull(planned.candidateHints.preferredBool(0))

            val result = assertIs<SearchResult.Satisfied>(planned.session.solve(1,
                SearchSolveParams(maxDecisions = 2), candidateHints = observedCandidateHints(
                    SearchCandidateHints.None, planned.candidateHints, stats.hintUsage)))

            assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
            assertEquals(BIG_ZERO, result.model.valueOf(SearchExactIntValue(SourceIntId(0))))
            assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
        }
        assertEquals(0L, stats.hintUsage.snapshot().componentSelections)
        assertEquals(0L, stats.hintUsage.snapshot().indexesAdmitted)
    }

    @Test
    fun `default primitive preferences expire on arithmetic retraction and opening close`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 2,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
                factors = arrayOf<Factor>(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0)) + if (integerCount == 0) emptyArray() else arrayOf(
                    Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.EQ, 0.0)),
            )
            val planned = source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams())
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
    fun `a custom provider declines or prefers without consulting the default primitive component`() {
        for ((integerCount, realCount) in listOf(0 to 1, 1 to 1, 2 to 0)) {
            val open = Bits(integerCount).also { bits -> repeat(integerCount, bits::set) }
            val source = if (realCount == 0) Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), open, open),
                factors = arrayOf(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0),
                ),
            ) else Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
                factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0)),
            )
            val prepared = kotlin.test.assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
            for (preference in listOf(false, null)) {
                val resources = SearchInvocationResources()
                val opening = SearchComponentOpening(Cancellation.Never, null, null, resources)
                var calls = 0
                var releases = 0
                opening.registerCandidateHints(intArrayOf(0), SearchCandidateHints { calls++; preference },
                    { releases++ }, { true })
                val factory = kotlin.test.assertNotNull(
                    source.componentPlan().theoryComponentFactory(source) { prepared })
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
    fun `interrupted default primitive registration retains its debit without a callback`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
                factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0)),
            )
            val prepared = kotlin.test.assertNotNull(prepareExactLiraSource(source, Cancellation.Never))
            var stopped = false
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = stopped
                override fun workMeter(): WorkMeter = WorkMeter { charged += it; stopped = true }
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
    fun `default prepared point capture honors a separate containing stop and retains work`() {
        for (integerCount in listOf(0, 1)) {
            val source = Problem(
                numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(LongArray(integerCount), LongArray(integerCount), null, null),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
                factors = arrayOf<Factor>(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0)) + if (integerCount == 0) emptyArray() else arrayOf(
                    Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.EQ, 0.0)),
            )
            var stopped = false
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = stopped
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, stop,
                SearchLearnedDbParams(), smtStats = null, theorySolveStop = Cancellation.Never).use { planned ->
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
    fun `an earlier nested signed source gate reuses partial real point preferences`() {
        for (positive in listOf(false, true)) {
            val target = Lit.make(0, positive)
            val inner = Lit.make(1, false)
            val left = Lit.make(2, true)
            val right = Lit.make(3, false)
            val last = Lit.make(4, true)
            val source = Problem(
                numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0),
                factors = arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.EQ, 0.0),
                    ReifiedRealLinear(2, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.LE, 0.0),
                    ReifiedRealLinear(3, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.GE, 1.0),
                    ReifiedRealLinear(4, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                        doubleArrayOf(1.0), LinearOp.LE, 0.0),
                    Clause(intArrayOf(Lit.negate(inner), left)),
                    Clause(intArrayOf(Lit.negate(inner), right)),
                    Clause(intArrayOf(inner, Lit.negate(left), Lit.negate(right))),
                    Clause(intArrayOf(Lit.negate(target), inner)),
                    Clause(intArrayOf(Lit.negate(target), last)),
                    Clause(intArrayOf(target, Lit.negate(inner), Lit.negate(last))),
                ),
            )
            val stats = SmtStatsSink()
            source.componentPlan().search(source, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams(), stats, openHintFlips = 1L).use { planned ->
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                assertEquals(positive, planned.candidateHints.preferredBool(0))

                val result = assertIs<SearchResult.Satisfied>(planned.session.solve(5,
                    SearchSolveParams(maxDecisions = 1), candidateHints = observedCandidateHints(
                        SearchCandidateHints.None, planned.candidateHints, stats.hintUsage)))

                assertEquals(positive, result.model.valueOf(SearchBoolValue(0)))
                assertEquals(BigFraction.ZERO, result.model.valueOf(SearchExactRealValue(SourceRealId(0))))
            }
            assertEquals(1L, stats.hintUsage.snapshot().indexesAdmitted)
            assertEquals(1L, stats.hintUsage.snapshot().componentConsultations)
            assertEquals(1L, stats.hintUsage.snapshot().componentSelections)
            assertEquals(0L, stats.hintUsage.snapshot().primitiveSelections)
            assertEquals(1L, stats.hintUsage.snapshot().gateSelections)
        }
    }

    @Test
    fun `partial factory construction releases earlier peers and scratch resources`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val closed = ArrayList<String>()
        val primary = IllegalArgumentException("construction")
        val cleanup = IllegalStateException("scratch cleanup")
        val registrations = listOf(
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("first",
                ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
                listOf(object : SearchComponent, AutoCloseable {
                    override fun close() { closed += "peer" }
                })
            },
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("second",
                ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) { opening ->
                opening.own(AutoCloseable { closed += "scratch"; throw cleanup })
                throw primary
            },
        )
        val prepared = PreparedSearchRegistrations(source, registrations)

        val failure = assertFailsWith<IllegalArgumentException> {
            prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null)
        }

        assertSame(primary, failure)
        assertSame(cleanup, failure.suppressedExceptions.single())
        assertEquals(listOf("scratch", "peer"), closed)
    }

    @Test
    fun `failed component installation releases each constructed peer once`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        var closes = 0
        val peer = object : SearchComponent, AutoCloseable {
            override fun close() { closes++ }
        }
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("duplicate",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) { listOf(peer, peer) }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))

        assertFailsWith<IllegalArgumentException> {
            prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null)
        }

        assertEquals(1, closes)
    }

    @Test
    fun `planned search closes every installed peer in reverse order`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val closed = ArrayList<Int>()
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("peers",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            List(2) { index -> object : SearchComponent, AutoCloseable {
                override fun close() { closed += index }
            } }
        }
        val search = PreparedSearchRegistrations(source, listOf(registration))
            .open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null)

        search.close()
        search.close()

        assertEquals(listOf(1, 0), closed)
    }

    @Test
    fun `a selected inert clause is checked without reinstalling default deduction`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original clause is checked at the complete point.") { NoPropagator }
        })
        val ownership = source.componentPlan(providers.prepare(source), preferFinite = true)
        val prepared = PreparedSearchComponents(ownership, source, emptyMap())

        for (value in listOf(false, true)) prepared.open().use { search ->
            assertIs<ComponentResult.Consistent>(search.session.initialize())
            assertNull(search.session.boolValue(0))
            assertIs<ComponentResult.Consistent>(search.session.push(SearchDecision.Bool(Lit.make(0, value))))

            if (value) assertIs<ComponentCheck.Feasible>(search.session.check())
            else assertIs<ComponentCheck.Infeasible>(search.session.check())
        }
    }

    @Test
    fun `prepared openings keep mutable custom deduction private`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 1))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) {
                object : Propagator {
                    private var owner: PropagationState? = null
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        if (owner == null) owner = state
                        check(owner === state) { "mutable deduction belongs to one live state" }
                        return true
                    }
                }
            }
        })
        val ownership = source.componentPlan(providers.prepare(source), preferFinite = true)
        val prepared = PreparedSearchComponents(ownership, source, emptyMap())

        prepared.open().use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            prepared.open().use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))
                assertIs<ComponentCheck.Feasible>(sibling.session.check())
            }

            assertIs<ComponentCheck.Feasible>(first.session.check())
            assertEquals(true, first.session.boolValue(0))
        }
    }

    @Test
    fun `registered Boolean peers enforce all source clauses`() {
        val source = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2))))
        val prepared = PreparedSearchRegistrations(source, source.factors.indices.map { factor ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("clauses-$factor",
                ComponentResponsibility.BOOLEAN, listOf(factor), emptyList(), emptyList(),
                if (factor == 0) listOf(0, 1) else emptyList())) {
                listOf(ClauseSearchComponent(listOf(source.factors[factor] as Clause)))
            }
        })

        prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { search ->
            assertIs<ComponentResult.Consistent>(search.session.initialize())
            val result = assertIs<SearchResult.Satisfied>(search.session.solve(2))

            assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
        }
    }

    @Test
    fun `prepared Boolean openings isolate asserted levels`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("boolean",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList(), listOf(0))) {
            listOf(ClauseSearchComponent(emptyList()))
        }
        val prepared = PreparedSearchRegistrations(source, listOf(registration))

        prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            prepared.open(Long.MAX_VALUE, Cancellation.Never, SearchLearnedDbParams(), null, null).use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))

                assertEquals(true, first.session.boolValue(0))
                assertEquals(false, sibling.session.boolValue(0))
            }
        }
    }

    @Test
    fun `foreign source registration declines before invoking its factory`() {
        val source = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val other = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        var openings = 0
        val registration = PreparedSearchRegistration(source, ExecutionComponentDescriptor("boolean",
            ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList())) {
            openings++
            listOf(ClauseSearchComponent(emptyList()))
        }

        assertFailsWith<IllegalArgumentException> { PreparedSearchRegistrations(other, listOf(registration)) }

        assertEquals(0, openings)
    }

    @Test
    fun `multiple authoritative coordinate owners decline before opening`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val registrations = List(2) { index ->
            PreparedSearchRegistration(source, ExecutionComponentDescriptor("boolean-$index",
                ComponentResponsibility.BOOLEAN, emptyList(), emptyList(), emptyList(), listOf(0))) {
                listOf(ClauseSearchComponent(emptyList()))
            }
        }

        assertFailsWith<IllegalArgumentException> { PreparedSearchRegistrations(source, registrations) }
    }

    @Test
    fun `prepared exact openings isolate cached outcomes`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.GE, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val prepared = PreparedSearchComponents(source.componentPlan(), source, emptyMap())
        prepared.prepareSource(Cancellation.Never)
        val coordinate = SearchExactRealValue(SourceRealId(0))

        prepared.open().use { first ->
            assertIs<ComponentResult.Consistent>(first.session.initialize())
            assertIs<ComponentResult.Consistent>(first.session.push(SearchDecision.Bool(0)))
            assertIs<ComponentCheck.Feasible>(first.session.check())
            prepared.open().use { sibling ->
                assertIs<ComponentResult.Consistent>(sibling.session.initialize())
                assertIs<ComponentResult.Consistent>(sibling.session.push(SearchDecision.Bool(1)))
                assertIs<ComponentCheck.Feasible>(sibling.session.check())

                assertNotEquals(BigFraction.ONE, sibling.session.model().valueOf(coordinate))
            }
            assertIs<ComponentCheck.Feasible>(first.session.check())
            assertEquals(BigFraction.ONE, first.session.model().valueOf(coordinate))
        }
    }

    @Test
    fun `published exact coordinates outlive their producer`() {
        val source = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                doubleArrayOf(1.0), LinearOp.GE, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val prepared = PreparedSearchComponents(source.componentPlan(), source, emptyMap())
        prepared.prepareSource(Cancellation.Never)

        val published = prepared.open().use { search ->
            assertIs<ComponentResult.Consistent>(search.session.initialize())
            assertIs<ComponentResult.Consistent>(search.session.push(SearchDecision.Bool(0)))
            assertIs<ComponentCheck.Feasible>(search.session.check())
            search.session.model()
        }

        assertEquals(BigFraction.ONE, published.valueOf(SearchExactRealValue(SourceRealId(0))))
    }
}
