package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceExecutionControlsTest {
    @Test
    fun `incremental linear control should bind to ordinary source execution`() {
        val parsed = SmtLib.parse("(declare-const x Real) (declare-const y Real) " +
            "(assert (<= (+ (* 2 x) (* 3 y)) 1))")
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        for ((options, expected) in listOf(emptyList<String>() to false,
            listOf("open-linear-incremental=true") to true, listOf("open-linear-incremental=false") to false)) {
            val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(options))

            assertEquals(expected, request.theory.openLinearIncremental)
        }
    }

    @Test
    fun `ordinary source cut controls should configure companions without changing the finite recipe`() {
        val source = LinearSourceProblem(listOf(
            LinearSourceVariable("i", true, SemanticRational.ZERO, SemanticRational.ONE),
            LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE),
        ), emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE))))
        for ((supplied, options, expected) in listOf(
            Triple(false, emptyList<String>(), listOf(true, false, false)),
            Triple(true, emptyList<String>(), listOf(true, false, false)),
            Triple(false, listOf("source-cuts=false", "source-point-cuts=true", "source-aggregate-cuts=true"),
                listOf(false, true, true)),
            Triple(true, listOf("source-cuts=false", "source-point-cuts=true", "source-aggregate-cuts=true"),
                listOf(false, true, true)),
        )) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED))
            val executionOptions = ExecutionOptions.parse(options)
            val request = SourceExecutionRequest(invocation, executionOptions, finiteRequest = if (supplied) {
                { shape -> SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                    executionOptions = executionOptions) }
            } else null)
            val finite = assertNotNull(request.finiteRequest).invoke(invocation.finiteShape())

            val scenario = buildPortfolioScenario(finite.executionOptions.reader(), finite.randomSeed,
                finite.cores, Kind.COP, EngineMix.MIXED, finite.defaultArms)

            assertTrue(invocation.requestsFiniteNumericalCompanions)
            assertEquals(expected, listOf(request.theory.sourceCuts, request.theory.sourcePointCuts,
                request.theory.sourceAggregateCuts))
            assertFalse(scenario.explicitArms)
            assertFalse(scenario.companionIntegers)
            assertEquals(4_000L, scenario.companionActiveMillis)
        }
    }

    @Test
    fun `ordinary finite source execution should bind advisory completion to its companions`() {
        val source = LinearSourceProblem(listOf(
            LinearSourceVariable("i", true, SemanticRational.ZERO, SemanticRational.ONE),
            LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE),
        ), emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE))))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED))

        val request = SourceExecutionRequest(invocation,
            ExecutionOptions.parse(listOf("source-incumbent-completion=true")))

        assertTrue(invocation.requestsFiniteNumericalCompanions)
        assertTrue(request.theory.sourceIncumbentCompletion)
    }

    @Test
    fun `advisory incumbent completion should require explicit opt in`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER))
        for ((options, expected) in listOf(emptyList<String>() to false,
            listOf("source-incumbent-completion=true") to true)) {
            val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(options))

            assertEquals(expected, request.theory.sourceIncumbentCompletion)
        }
    }

    @Test
    fun `pure real sources should reject integer residue authority`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (declare-const y Real) " +
                "(assert (= (+ (* x x) (* y y)) 3))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val failure = assertFailsWith<PipelineConfigException> {
            SourceSetup.execute(SourceExecutionRequest(invocation,
                ExecutionOptions.parse(listOf("nia-residues=true"))))
        }

        assertTrue("nia-residues" in assertNotNull(failure.message))
    }

    @Test
    fun `numerical refutation resolution should require explicit opt in`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER,
        ))
        for ((options, expected) in listOf(emptyList<String>() to false,
            listOf("source-refutation-resolution=true") to true)) {
            val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(options))

            assertEquals(expected, request.theory.sourceRefutationResolution)
        }
    }

    @Test
    fun `numerical unit refutations should require explicit opt in`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER,
        ))
        for ((options, expected) in listOf(emptyList<String>() to false,
            listOf("source-refutation-propagation=true") to true)) {
            val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(options))

            assertEquals(expected, request.theory.sourceRefutationPropagation)
        }
    }

    @Test
    fun `point cut selection should bind to numerical execution`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER))

        val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf("source-point-cuts=true")))

        assertTrue(request.theory.sourcePointCuts)
    }

    @Test
    fun `bounded source aggregation should require explicit opt in`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER,
        ))
        for ((options, expected) in listOf(emptyList<String>() to false,
            listOf("source-aggregate-cuts=true") to true)) {
            val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(options))

            assertEquals(expected, request.theory.sourceAggregateCuts)
        }
    }

    @Test
    fun `source cut control should bind to numerical execution`() {
        val source = SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList()), ProblemSettings(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.FIXED,
            linearTraversal = LinearTraversal.NUMERICAL_FRONTIER,
        ))

        val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf("source-cuts=false")))

        assertEquals(false, request.theory.sourceCuts)
    }

    @Test
    fun `source option binding should retain the containing ledger across native families`() {
        val sources = listOf(
            "(declare-const x Real) (assert (> x 0))",
            "(declare-const x Real) (assert (= (* x x) 2))",
            "(declare-const x Int) (assert (= (* x x) 2))",
            "(declare-const x Real) (assert (= (sin x) 0))",
        ).map { SmtLib.parse(it).source } + SourceAttachment(
            LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList()), ProblemSettings(),
        )
        val budget = NodeBudget(3)
        budget.spend()
        for (source in sources) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            ))

            val request = SourceExecutionRequest(invocation, ExecutionOptions(), nodeBudget = budget)

            assertSame(budget, request.nodeBudget)
            assertSame(budget, request.theory.nodeBudget)
            assertSame(budget, request.polynomial.strategy.backtrack.nodeBudget)
            assertSame(budget, request.polynomial.strategy.localSearch.nodeBudget)
            request.nonlinearInteger?.let { assertSame(budget, it.nodeBudget) }
            request.transcendental?.let { assertSame(budget, it.nodeBudget) }
            assertEquals(1L, budget.spent)
        }
    }

    @Test
    fun `source options should keep node and native arithmetic allowances independent`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (> x 0))").source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf(
            "node-limit=0", "open-work-limit=unlimited",
        )))
        val result = assertIs<SourceExecutionResult.Linear>(SourceSetup.execute(request))

        assertEquals(0L, assertNotNull(request.nodeBudget).limit)
        assertEquals(Long.MAX_VALUE, request.theory.openWorkLimit)
        assertIs<OpenTheoryResult.Sat>(assertIs<OpenTheoryExecution.Satisfy>(result.result).result)
        assertEquals(0L, request.nodeBudget.spent)
    }

    @Test
    fun `native source auto limits should use the parameter defaults`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf(
            "nia-candidates=auto", "nia-moves=auto", "nra-dense-coefficients=auto", "nra-matrix-cells=auto",
        )))

        val integer = assertNotNull(request.nonlinearInteger)
        assertEquals(NonlinearIntegerTheoryParams().maximumCandidates, integer.maximumCandidates)
        assertEquals(NonlinearIntegerTheoryParams().maximumMoves, integer.maximumMoves)
        assertEquals(PolynomialTheoryParams().maximumDenseCoefficients, request.polynomial.maximumDenseCoefficients)
        assertEquals(PolynomialTheoryParams().maximumMatrixCells, request.polynomial.maximumMatrixCells)
    }

    @Test
    fun `native source unlimited limits should preserve their native sentinels`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf(
            "nia-candidates=unlimited", "nia-moves=unlimited", "nra-work-limit=unlimited",
            "nra-dense-coefficients=unlimited", "nra-matrix-cells=unlimited",
        )))

        assertEquals(Long.MAX_VALUE, assertNotNull(request.nonlinearInteger).maximumCandidates)
        assertEquals(Long.MAX_VALUE, request.nonlinearInteger.maximumMoves)
        assertNull(request.polynomial.maximumWork)
        assertNull(request.polynomial.maximumDenseCoefficients)
        assertNull(request.polynomial.maximumMatrixCells)
    }

    @Test
    fun `source native zero work should stop arithmetic without spending the node ledger`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* x x) 2))").source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val request = SourceExecutionRequest(invocation, ExecutionOptions(
            ExecutionOption.nodeLimit(7L), ExecutionOption.nraWorkLimit(0L),
        ))

        val execution = assertIs<SourceExecutionResult.Polynomial>(SourceSetup.execute(request)).result

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, request.nodeBudget?.spent)
    }

    @Test
    fun `source option seeds should bind the same default and override to every strategy`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* x x) 2))").source
        for ((options, fallback, expected) in listOf(
            Triple(ExecutionOptions(), null, 0L),
            Triple(ExecutionOptions(), 19L, 19L),
            Triple(ExecutionOptions(ExecutionOption.seed(-7L)), 19L, -7L),
        )) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                source, ExecutionOperation.SATISFY, FiniteEngine.MIXED,
            ))

            val request = SourceExecutionRequest(invocation, options, randomSeed = fallback)

            assertEquals(expected, request.theory.randomSeed)
            assertEquals(expected, request.polynomial.strategy.backtrack.randomSeed)
            assertEquals(expected, request.polynomial.strategy.localSearch.randomSeed)
        }
    }

    @Test
    fun `source options should reject conflicting containing allowances`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (> x 0))").source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val budget = NodeBudget(3)
        for (options in listOf(
            ExecutionOptions(ExecutionOption.nodeLimit(2L)),
            ExecutionOptions(ExecutionOption.nodeLimit(null)),
            ExecutionOptions.parse(listOf("node-limit=3", "node-limit=4")),
        )) {
            assertFailsWith<PipelineConfigException> {
                SourceExecutionRequest(invocation, options, nodeBudget = budget)
            }
        }
        assertEquals(0L, budget.spent)
    }

    @Test
    fun `a finite application factory should not replace the containing node ledger`() {
        val source = SmtLib.parse("(declare-const b Bool) (assert b)").source
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val foreign = SourceExecutionRequest(invocation, ExecutionOptions(), nodeBudget = NodeBudget(4))
        val budget = NodeBudget(4)
        val request = SourceExecutionRequest(
            invocation, ExecutionOptions(), nodeBudget = budget, finiteRequest = foreign.finiteRequest,
        )

        assertFailsWith<IllegalArgumentException> { SourceSetup.execute(request) }
        assertEquals(0L, budget.spent)
    }

    @Test
    fun `function source auto work should retain the function parameter default`() {
        val source = SmtLib.parse("(declare-const x Real) (assert (= (sin x) 0))").source
        for ((raw, expected) in listOf("auto" to TranscendentalTheoryParams().maximumWork, "unlimited" to null)) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            ))

            val request = SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf("nra-work-limit=$raw")))

            assertEquals(expected, assertNotNull(request.transcendental).maximumWork)
        }
    }

    @Test
    fun `source execution should reject unknown options across source families`() {
        for (text in listOf(
            "(declare-const b Bool) (assert b)",
            "(declare-const x Real) (assert (> x 0))",
            "(declare-const x Real) (assert (= (* x x) 2))",
            "(declare-const x Int) (assert (= (* x x) 2))",
            "(declare-const x Real) (assert (= (sin x) 0))",
        )) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SmtLib.parse(text).source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            ))

            val failure = assertFailsWith<PipelineConfigException> {
                SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions.parse(listOf("unknown=1"))))
            }

            assertTrue("unknown" in assertNotNull(failure.message))
        }
    }

    @Test
    fun `default finite source controls should retain the selected engine and containing allowance`() {
        val source = SmtLib.parse("(declare-const b Bool) (assert b)").source
        for (engine in FiniteEngine.entries) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY, engine))
            val budget = NodeBudget(4)

            val request = SourceExecutionRequest(invocation, ExecutionOptions(), nodeBudget = budget)
            val finite = assertNotNull(request.finiteRequest).invoke(invocation.finiteShape())

            assertEquals(engine, finite.engine)
            assertSame(invocation, finite.shape.sourceInvocation)
            assertSame(budget, finite.nodeBudget)
            assertEquals(0L, finite.randomSeed)
            assertEquals(0L, finite.executionOptions.reader()[ExecutionOption.seed])
        }
    }

    @Test
    fun `polynomial strategy controls should reach the finite workers`() {
        val options = ExecutionOptions.parse(listOf(
            "max-decisions=3", "max-flips=7", "max-instructions=11", "alns-iterations=2",
        ))

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Real) (assert (= (* x x) 2))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val params = SourceExecutionRequest(invocation, options, randomSeed = 17).polynomial.strategy

        assertEquals(17L, params.backtrack.randomSeed)
        assertEquals(17L, params.localSearch.randomSeed)
        assertEquals(3L, params.backtrack.maxDecisions)
        assertEquals(7L, params.localSearch.maxFlips)
        assertEquals(11L, params.localSearch.maxInstructions)
        assertEquals(2, params.alnsIterations)
    }

    @Test
    fun `nonlinear source controls should retain explicit candidate and move allowances`() {
        val options = ExecutionOptions.parse(listOf("nia-candidates=7", "nia-moves=11", "nra-work-limit=128"))

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val params = assertNotNull(SourceExecutionRequest(invocation, options).nonlinearInteger)

        assertEquals(7L, params.maximumCandidates)
        assertEquals(11L, params.maximumMoves)
        assertEquals(128L, params.polynomial.maximumWork)
    }

    @Test
    fun `nonlinear cell controls should retain independent producer and consumer switches`() {
        for ((reuse, steering) in listOf(false to false, true to false, true to true)) {
            val options = ExecutionOptions.parse(listOf("nia-cell-refinement=true", "nia-cell-reuse=$reuse",
                "nia-cell-steering=$steering"))
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source,
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            ))

            val params = assertNotNull(SourceExecutionRequest(invocation, options).nonlinearInteger)

            assertTrue(params.cellRefinement)
            assertEquals(reuse, params.reuseSourceCells)
            assertEquals(steering, params.steerSourceCells)
        }
    }

    @Test
    fun `nonlinear root admission should stay disabled unless requested`() {
        for ((arguments, expected) in listOf(emptyList<String>() to false, listOf("nia-integer-roots=true") to true)) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(
                SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source,
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            ))

            val params = assertNotNull(
                SourceExecutionRequest(invocation, ExecutionOptions.parse(arguments)).nonlinearInteger,
            )

            assertEquals(expected, params.admitIntegerRoots)
            assertFalse(params.cellRefinement)
        }
    }

    @Test
    fun `polynomial finite workers should share one generic node allowance`() {
        val options = ExecutionOptions.parse(listOf("node-limit=7"))

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const x Int) (assert (= (* x x) 2))").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val request = SourceExecutionRequest(invocation, options)

        assertEquals(7L, request.nodeBudget?.limit)
        assertSame(request.nodeBudget, request.polynomial.strategy.backtrack.nodeBudget)
        assertSame(request.nodeBudget, request.polynomial.strategy.localSearch.nodeBudget)
    }
}
