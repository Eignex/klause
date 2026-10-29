package com.eignex.klause.propagation

import com.eignex.klause.backtrack.toBacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.toSearchHints
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IntegerConflictLearnerTest {
    @Test
    fun `a paired arithmetic assertion should publish both row and clause deductions`() {
        val native = PropagationSession(Problem(2, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))
        val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 2),
            longArrayOf(4, -1), intArrayOf(0, 2), LinearOp.LE, 0), useLo = true))
        val fallback = ConflictAnalyzer.AnalysisResult.Learned(intArrayOf(Lit.make(0, true)),
            backjumpLevel = 0, lbd = 1, decisionLevels = intArrayOf(0))
        val learned = ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(row,
            backjumpLevel = 1, lbd = 2, decisionLevels = intArrayOf(0, 1), fallbackClause = fallback)

        val result = assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned))

        assertEquals(1L, result.intMaxOrNullCompat(0))
        assertEquals(true, result.boolValueOrNull(0))
    }

    @Test
    fun `prepared optimization should consume a guarded arithmetic consequence`() {
        val program = parseFlatZinc("""
            var bool: active;
            var 7..12: z;
            var 0..3: x;
            var 0..6: y;
            constraint int_lin_le_reif([2,1,-1],[x,y,z],0,active);
            constraint int_lin_le([2,-1],[x,y],0);
            solve :: seq_search([
                bool_search([active],input_order,indomain_max,complete),
                int_search([z],input_order,indomain_min,complete),
                int_search([x,y],input_order,indomain_max,complete)
            ]) maximize x;
        """.trimIndent())
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            program.problem, FiniteEngine.BACKTRACK, program.linearObjective(), randomSeed = 3L,
        ))
        val base = assertNotNull(program.searchHints).toSearchHints().toBacktrackParams(
            prepared.problem.numBoolVars, prepared.problem.numIntVars)
        val params = base.copy(integerRowLearning = true, pbLearning = false,
            objectiveGuidedValues = false, randomSeed = 3L, maxDecisions = 1_000)

        val result = BacktrackSolver(assertIs<BakedProblem>(prepared.problem)).minimize(
            assertNotNull(prepared.objective), params)

        val optimum = assertIs<MinimizeResult.Optimal>(result)
        val point = prepared.reconstruct(optimum.sample)
        val x = point.intValue(program.intVarsByName.getValue("x"))
        val y = point.intValue(program.intVarsByName.getValue("y"))
        val z = point.intValue(program.intVarsByName.getValue("z"))
        assertEquals(3L, x)
        assertEquals(2 * x + y <= z, point.boolValue(program.boolVarsByName.getValue("active")))
        assertTrue(2 * x <= y)
        assertTrue((result.stats.search.learning["learningIntegerConsumed"] ?: 0L) > 0L)
    }

    @Test
    fun `a prepared enumeration should reuse and retire its guarded arithmetic consequence`() {
        val program = parseFlatZinc("""
            var bool: active;
            var 7..12: z;
            var 0..3: x;
            var 0..6: y;
            constraint int_lin_le_reif([2,1,-1],[x,y,z],0,active);
            constraint int_lin_le([2,-1],[x,y],0);
            solve :: seq_search([
                bool_search([active],input_order,indomain_max,complete),
                int_search([z],input_order,indomain_min,complete),
                int_search([x,y],input_order,indomain_max,complete)
            ]) satisfy;
        """.trimIndent())
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            program.problem, FiniteEngine.BACKTRACK, solutionSetSensitive = true, randomSeed = 3L,
        ))
        var native: PropagationSession? = null
        val trace = ArithmeticLearningTrace(1024, 65536)
        val uses = mutableMapOf<Pair<LearnedIntegerPropagator, Int>, MutableSet<Long>>()
        val base = assertNotNull(program.searchHints).toSearchHints().toBacktrackParams(
            prepared.problem.numBoolVars, prepared.problem.numIntVars)
        fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
            override fun fresh(): VariableSelector = observing(delegate.fresh())
            override fun pick(session: PropagationSession, rng: Random): VarRef? {
                if (native == null) session.explanationState.arithmeticLearningTrace = trace
                native = session
                val state = session.explanationState
                for (variable in state.intMaxReason.indices) {
                    val reason = state.intMaxReason[variable]
                    if (reason < state.baseFactorCount) continue
                    val row = state.factorAt(reason) as? LearnedIntegerPropagator ?: continue
                    uses.getOrPut(row to variable) { mutableSetOf() }.add(state.intDomains[variable].max)
                }
                return delegate.pick(session, rng)
            }
        }
        val params = base.copy(variableSelector = observing(base.variableSelector),
            integerRowLearning = true, pbLearning = false, randomSeed = 3L, maxDecisions = 1_000)
        BacktrackSolver(assertIs<BakedProblem>(prepared.problem)).openEnumerate(params).use { stream ->
            repeat(20) {
                assertTrue(stream.hasNext())
                val point = prepared.reconstruct(stream.next())
                val x = point.intValue(program.intVarsByName.getValue("x"))
                val y = point.intValue(program.intVarsByName.getValue("y"))
                val z = point.intValue(program.intVarsByName.getValue("z"))
                assertEquals(2 * x + y <= z, point.boolValue(program.boolVarsByName.getValue("active")))
                assertTrue(2 * x <= y)
            }
            val session = assertNotNull(native)
            val retained = uses.entries.firstOrNull { it.value.size >= 2 }?.key?.first
            assertNotNull(retained, "prepared=" + ProblemJson().encodeToString(prepared.problem) +
                "; learning=" + session.learnedLifecycle() + "; uses=" + uses.values +
                "; declines=" + trace.events.filter { "decline" in it.kind }.groupingBy { it.kind }.eachCount() +
                "; tail=" + trace.events.takeLast(20).map { it.kind })
            assertTrue(retained.row.guard != Lit.NONE)
            session.popToLevel(0)
            assertIs<PropagationResult.Implied>(session.pinBool(Lit.variable(retained.row.guard),
                !Lit.isPositive(retained.row.guard)))
            assertTrue(session.explanationState.forgetLearnedClauses(maxWork = 16_000_000L,
                maxScratchBytes = 16_000_000L, cancelled = { session.cancelRequested }) { _, _ -> false })
            assertFalse(session.explanationState.learned.integerRows?.contains(retained) == true)
            assertTrue((session.learnedLifecycle()["learningIntegerRetired"] ?: 0L) > 0L)
        }
    }

    @Test
    fun `a consumed guarded row should retire after its historical reason retracts`() {
        val native = PropagationSession(Problem(2, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))
        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))
        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        native.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))
        val state = native.explanationState
        val retained = assertNotNull(state.learned.integerRows).single()
        assertSame(retained, state.factorAt(state.intMaxReason[0]))
        assertTrue(assertNotNull(state.reasonOf(state.intMaxAntecedents[0])).contains(Lit.make(0, false)))

        native.forgetLearnedClauses { _, _ -> false }
        assertSame(retained, assertNotNull(state.learned.integerRows).single())
        native.popToLevel(0)
        assertIs<PropagationResult.Implied>(native.pinBool(1, true))
        native.forgetLearnedClauses { _, _ -> false }

        assertTrue(state.learned.integerRows.isNullOrEmpty())
        assertEquals(1L, native.learnedLifecycle()["learningIntegerRetired"])
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 11))
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertEquals(5L, native.intDomain(0).max)
    }

    @Test
    fun `an incidental hole should preserve a learned row guard`() {
        val native = PropagationSession(Problem(1, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12).excludeValue(2), IntDomain(0, 24)), arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        assertEquals(Lit.make(0, true), learned.row.guard)
        assertTrue(learned.fallbackClause.literals.contains(Lit.make(0, false)))
        native.popToLevel(learned.backjumpLevel)
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))
        assertEquals(1L, native.intDomain(0).max)
    }

    @Test
    fun `original rows should retain a reusable consequence of the scaling conflict`() {
        for (scale in listOf(2, 8, 32)) {
            for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
                listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))) {
                val factors = arrayOf(
                    Linear(intArrayOf(scale, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    Linear(intArrayOf(1, -scale, -1), intArrayOf(2, 0, 3), LinearOp.LE, 0),
                )
                val native = PropagationSession(Problem(0, 4, arrayOf(IntDomain(0, scale.toLong()),
                    IntDomain(0, scale.toLong()), IntDomain(0, scale.toLong() * (scale + 1)),
                    IntDomain(0, scale.toLong() * (scale + 1))), order.map { factors[it] }.toTypedArray()),
                    pbLearning = false).apply { enableIntegerRowLearning() }
                assertIs<PropagationResult.Implied>(native.pinIntAtLeast(0, 1))

                val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtMost(3, 0))

                val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause,
                    "scale=$scale order=$order")
                native.popToLevel(learned.backjumpLevel)
                assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(learned.row, learned.lbd))
                assertEquals(1L, native.intDomain(3).min, "scale=$scale order=$order")
            }
        }
    }

    @Test
    fun `an incidental hole should retain a row across sibling endpoints without filling the domain`() {
        for (sign in listOf(1, -1)) {
            val native = PropagationSession(Problem(0, 3, arrayOf(
                IntDomain(if (sign > 0) 0L else -6L, if (sign > 0) 6L else 0L),
                IntDomain(if (sign > 0) 0L else -12L, if (sign > 0) 12L else 0L).excludeValue(2L * sign),
                IntDomain(if (sign > 0) 0L else -24L, if (sign > 0) 24L else 0L),
            ), arrayOf(
                Linear(intArrayOf(2 * sign, sign, -sign), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2 * sign, -sign), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
            val cp = CpSearchComponent(native, intArrayOf(4, 7, 9))
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                SearchDecision.IntAtMost(9, 7) else SearchDecision.IntAtLeast(9, -7)))
            assertIs<ComponentResult.Conflict>(shared.push(if (sign > 0)
                SearchDecision.IntAtLeast(4, 2) else SearchDecision.IntAtMost(4, -2)))
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
            assertEquals(4L * sign, learned.row.coefficient(0))
            assertEquals(-sign.toLong(), learned.row.coefficient(2))
            val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(backjump.decisionLevel)

            assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

            assertEquals(sign.toLong(), if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
            for (upper in listOf(11L, 15L, 23L)) {
                shared.popTo(0)
                assertFalse(2L * sign in native.intDomain(1))
                assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                    SearchDecision.IntAtMost(9, upper) else SearchDecision.IntAtLeast(9, -upper)))
                assertEquals(sign * (upper / 4),
                    if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
                val state = native.explanationState
                val reason = if (sign > 0) state.intMaxReason[0] else state.intMinReason[0]
                assertIs<LearnedIntegerPropagator>(state.factorAt(reason))
                assertNotNull(state.reasonOf(if (sign > 0) state.intMaxAntecedents[0] else state.intMinAntecedents[0]))
            }
            shared.popTo(0)
            assertFalse(2L * sign in native.intDomain(1))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(4, 2L * sign)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(7, 4L * sign)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntEqual(9, 8L * sign)))
        }
    }

    @Test
    fun `a retained row reason should keep its historical premises after factor ids move`() {
        val native = PropagationSession(Problem(1, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.seed(Assumptions.None.withInt(2, 7)))
        val dummy = native.learnedClauseCount
        val tautology = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
        native.addLearnedClause(tautology, lbd = 3)
        val cp = CpSearchComponent(native)
        cp.rebase()
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
        val state = native.explanationState
        val row = requireNotNull(state.learned.integerRows).single()
        val prior = assertNotNull(state.reasonOf(state.intMaxAntecedents[0])).copyOf()
        assertTrue(prior.isNotEmpty())

        native.forgetLearnedClauses { index, _ -> index != dummy }
        native.addLearnedClause(tautology, lbd = 3)
        state.lazyReasonMemo.clear()

        assertSame(row, state.factorAt(state.intMaxReason[0]))
        assertContentEquals(prior, state.reasonOf(state.intMaxAntecedents[0]))
    }

    @Test
    fun `a full row store should fall back until forgetting releases capacity`() {
        val native = PropagationSession(Problem(0, 4,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24), IntDomain(0, 6)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        repeat(INTEGER_LEARNING_ROWS) { index ->
            val row = requireNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(3),
                longArrayOf(1), intArrayOf(3), LinearOp.LE, 6L + index), useLo = true))
            native.addLearnedIntegerRow(row, lbd = 3)
        }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))
        val capped = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))
        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(capped.learnedClause)
        native.popToLevel(0)
        native.forgetLearnedClauses { index, _ -> index != 0 }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val retried = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(retried.learnedClause)
        assertEquals(INTEGER_LEARNING_ROWS - 1, native.explanationState.learned.integerRows?.size)
    }

    @Test
    fun `an unavailable shared bound premise should decline native row admission`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        val peer = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult =
                context.publish(SearchDecision.IntAtMost(2, 7))
        }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(peer, cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)

        assertFalse(cp.prefersNativeConflictAnalysis)

        val fallback = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(fallback.decisionLevel)
        fallback.apply(shared)
        assertTrue(native.explanationState.learned.integerRows.isNullOrEmpty())
    }

    @Test
    fun `cancellation before row analysis should publish no learned result`() {
        val state = PropagationState(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), Assumptions.None)
        state.undoLogging = true
        state.integerRowLearning = true
        assertTrue(state.setIntMaxAsDecision(2, 7))
        state.runToFixpoint(allFactors = true)
        assertTrue(state.setIntMinAsDecision(0, 2))
        assertNotNull(state.runToFixpoint(allFactors = false))
        state.finiteWork.cancellation = Cancellation { true }

        val result = state.conflictAnalyzer.analyze(1)

        assertIs<ConflictAnalyzer.AnalysisResult.NotApplicable>(result)
        assertTrue(state.learned.integerRows.isNullOrEmpty())
    }

    @Test
    fun `a stored row should wake beside a delta consumer after compaction and late atoms`() {
        val native = PropagationSession(Problem(1, 4,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24), IntDomain(0, 6)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Table(intArrayOf(0, 3), LongArray(14) { (it / 2).toLong() }),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.seed(Assumptions.None.withBool(0, true)))
        native.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false))), lbd = 3)
        val cp = CpSearchComponent(native)
        cp.rebase()
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 7)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)
        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
        shared.popTo(0)
        val state = native.explanationState
        val row = requireNotNull(state.learned.integerRows).single()
        native.forgetLearnedClauses { index, _ ->
            native.learnedClausePermanent(index) || state.learned.store[index] === row
        }
        val lateBound = state.atomVarGe(0, 3)
        val lateEquality = state.atomVarEq(0, 4)

        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(2, 11)))

        assertEquals(2L, native.intDomain(0).max)
        assertEquals(2L, native.intDomain(3).max)
        assertEquals(false, state.litTruth(Lit.make(lateBound, true)))
        assertEquals(false, state.litTruth(Lit.make(lateEquality, true)))
        assertSame(row, state.factorAt(state.intMaxReason[0]))
        assertEquals(true, native.boolValue(0))
    }

    @Test
    fun `a stored source row should assert and tighten several sibling endpoints`() {
        for (sign in listOf(1, -1)) {
            for ((op, orientation) in listOf(LinearOp.LE to 1, LinearOp.EQ to 1, LinearOp.EQ to -1)) {
                val native = PropagationSession(Problem(0, 3, arrayOf(
                    IntDomain(if (sign > 0) 0L else -6L, if (sign > 0) 6L else 0L),
                    IntDomain(if (sign > 0) 0L else -12L, if (sign > 0) 12L else 0L),
                    IntDomain(if (sign > 0) 0L else -24L, if (sign > 0) 24L else 0L),
                ), arrayOf(
                    Linear(intArrayOf(2 * sign * orientation, sign * orientation, -sign * orientation),
                        intArrayOf(0, 1, 2), op, 0),
                    Linear(intArrayOf(2 * sign, -sign), intArrayOf(0, 1), LinearOp.LE, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
                val cp = CpSearchComponent(native, intArrayOf(4, 7, 9))
                val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
                assertIs<ComponentResult.Consistent>(shared.initialize())
                assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                    SearchDecision.IntAtMost(9, 7) else SearchDecision.IntAtLeast(9, -7)))
                assertIs<ComponentResult.Conflict>(shared.push(if (sign > 0)
                    SearchDecision.IntAtLeast(4, 2) else SearchDecision.IntAtMost(4, -2)))
                val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(
                    cp.lastConflict?.learnedClause,
                )
                assertEquals(4L * sign, learned.row.coefficient(0))
                assertEquals(-sign.toLong(), learned.row.coefficient(2))
                val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
                shared.popTo(backjump.decisionLevel)

                assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

                assertEquals(if (sign > 0) 1L else -1L,
                    if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
                assertFalse(native.learnedConstraintSatisfied(learned))
                for (upper in listOf(11L, 15L, 23L)) {
                    shared.popTo(0)
                    assertIs<ComponentResult.Consistent>(shared.push(if (sign > 0)
                        SearchDecision.IntAtMost(9, upper) else SearchDecision.IntAtLeast(9, -upper)))
                    assertEquals(if (sign > 0) upper / 4 else -(upper / 4),
                        if (sign > 0) native.intDomain(0).max else native.intDomain(0).min)
                    val reason = if (sign > 0) native.explanationState.intMaxReason[0]
                        else native.explanationState.intMinReason[0]
                    assertIs<LearnedIntegerPropagator>(native.explanationState.factorAt(reason))
                }
            }
        }
    }

    @Test
    fun `the production traversal should consume an integer row with PB disabled`() {
        val native = PropagationSession(Problem(0, 3, arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)),
            arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        var consumed = false
        val branching = CpBranching { session, source ->
            val state = session.explanationState
            if (state.learned.integerRows.isNullOrEmpty()) {
                if (session.intDomain(2).max > 7) {
                    listOf(SearchDecision.IntAtMost(2, 7), SearchDecision.IntAtLeast(2, 8))
                } else {
                    listOf(SearchDecision.IntAtLeast(0, 2), SearchDecision.IntAtMost(0, 1))
                }
            } else {
                consumed = consumed || session.intDomain(0).max == 1L
                CpBranching.Middle.alternatives(session, source)
            }
        }
        val shared = SearchSession(
            listOf(CpSearchComponent(native, branching = branching)), atoms = SearchAtomRegistry(0),
        )
        assertIs<ComponentResult.Consistent>(shared.initialize())

        assertIs<SearchResult.Satisfied>(shared.solve(0))

        assertTrue(consumed)
        assertEquals(1, native.explanationState.learned.integerRows?.size)
    }

    @Test
    fun `a root asserting row should retain a feasible parent`() {
        val native = PropagationSession(Problem(0, 3, arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 7)),
            arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(0, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        assertEquals(0, backjump.decisionLevel)
        shared.popTo(0)

        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertEquals(1L, native.intDomain(0).max)
    }

    @Test
    fun `integer rounding that loses conflict should retain clause fallback`() {
        val native = PropagationSession(Problem(0, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 2)), arrayOf(
            Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.LE, 3),
            Linear(intArrayOf(-1, -2), intArrayOf(0, 1), LinearOp.LE, -3),
        ))).apply { enableIntegerRowLearning() }

        val conflict = assertIs<PropagationResult.Unsat>(native.pinInt(0, 2))

        assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        assertTrue(native.explanationState.learned.integerRows.isNullOrEmpty())
    }

    @Test
    fun `historical holes should withhold a row even after the live interval loses the hole`() {
        for (reverse in listOf(false, true)) {
            val rows = listOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            )
            val native = PropagationSession(Problem(0, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12).excludeValue(4), IntDomain(0, 24)),
                (if (reverse) rows.reversed() else rows).toTypedArray())).apply { enableIntegerRowLearning() }
            assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

            val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

            assertIs<ConflictAnalyzer.AnalysisResult.Learned>(conflict.learnedClause)
        }
    }

    @Test
    fun `a reified source reason should retain its Boolean condition in the row and fallback`() {
        val native = PropagationSession(Problem(1, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 7))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinIntAtLeast(0, 2))

        val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(conflict.learnedClause)
        assertEquals(Lit.make(0, true), learned.row.guard)
        assertTrue(learned.fallbackClause.literals.contains(Lit.make(0, false)))
    }
}
