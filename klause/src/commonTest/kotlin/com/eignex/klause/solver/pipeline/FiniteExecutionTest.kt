package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.prepareSourceExecution
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.theory.SourceToleranceSearch
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FiniteExecutionTest {
    @Test
    fun `finite portfolio conversion should preserve a local search root proof`() {
        for (optimize in listOf(false, true)) {
            val problem = Problem.of(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0)), Clause(intArrayOf(1))))
            val objective = if (optimize) LinearObjective(boolWeights = longArrayOf(1)) else null
            val scenario = PortfolioScenario(1, 1, if (optimize) Kind.COP else Kind.CSP,
                EngineMix.LOCAL_SEARCH, seed = 17L, lpCeiling = LpConfig.OFF)

            val verdict = FinitePipeline.portfolioExecutor(problem.bake(), scenario, objective, null, null, null)
                .use { executor ->
                    if (optimize) executor.minimize().finitePortfolioVerdict()
                    else executor.solve().finitePortfolioVerdict()
                }

            assertEquals(FiniteExecutionVerdict.UNSAT, verdict, "optimize=$optimize")
        }
    }

    @Test
    fun `a local search portfolio should preserve a root refutation without presolve`() {
        for (optimize in listOf(false, true)) {
            val problem = Problem.of(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0)), Clause(intArrayOf(1))))
            val shape = FiniteSolveShape(problem, optimize, false, null,
                if (optimize) LinearObjective(boolWeights = longArrayOf(1)) else null, null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF, portfolioArms = 1).finiteRequest(
                shape, FiniteEngine.LOCAL_SEARCH, presolveConfig = PresolveConfig.NONE,
                randomSeed = 17L,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.UNSAT, completed.verdict, "optimize=$optimize")
            assertEquals(0.0, completed.stats.ls.moves.sum)
            assertNull(completed.terminationReason)
        }
    }

    @Test
    fun `an unfinished finite search should retain its fixed work stop`() {
        for (engine in listOf(FiniteEngine.FIXED, FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH)) {
            val shape = FiniteSolveShape(Problem.of(2, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(0, 2)))), false, false, null, null, null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF, portfolioArms = 1).finiteRequest(
                shape, engine, presolveConfig = PresolveConfig.NONE,
                randomSeed = 17L, nodeBudget = NodeBudget(0L),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.UNKNOWN, completed.verdict, "$engine")
            assertEquals(TerminationReason.BudgetExhausted, completed.terminationReason, "$engine")
        }
    }

    // Binary64 `0.01 * 200` exceeds the declared bound `2.0` by `3/72057594037927936`.
    private fun roundedBound(declarations: String, constraints: String) = """
        var 0.0..2.0: y;
        var 200..200: n;
        var 0.0..200.0: nf;
        $declarations
        constraint int2float(n, nf);
        constraint float_lin_eq([0.01, -1.0], [nf, y], 0.0);
        $constraints
        solve satisfy;
    """.trimIndent()

    private fun solveFlatZinc(
        overrides: SemanticOverrides,
        engine: FiniteEngine = FiniteEngine.FIXED,
        onSample: (Sample) -> Unit = {},
        declarations: String = "",
        constraints: String = "",
        source: String = roundedBound(declarations, constraints),
        floatPoints: Boolean = true,
        presolve: PresolveConfig = PresolveConfig.NONE,
    ): FiniteSolveVerdict {
        val program = parseFlatZinc(source, semanticOverrides = overrides)
        val maximize = program.sourceProgram.solve is SolveDirective.Maximize
        val objective = program.sourceProgram.linearObjective()?.let { if (maximize) it.negated() else it }
        val invocation = program.prepareSourceExecution(SourceSetupRequest(program.sourceAttachment(),
            if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE, engine,
            objective = objective, maximize = maximize))
        val request = SourceExecutionPolicy().finiteRequest(invocation.finiteShape(), engine,
            presolveConfig = presolve, cancellation = ExecutionControl.after(1.seconds),
            toleranceCheck = if (floatPoints) null else exactPointsOnly(program.sourceProgram.problem))

        val result = FinitePipeline.solve(request, FiniteSolveCallbacks(onSample, {}))

        return assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict
    }

    @Test
    fun `a FlatZinc real completion should be accepted within the declared tolerance`() {
        val samples = ArrayList<Sample>()

        val verdict = solveFlatZinc(SemanticOverrides(), onSample = samples::add)

        assertEquals(FiniteSolveVerdict.SAT, verdict)
        assertNull(samples.single().exactReals)
    }

    @Test
    fun `incomplete engines should accept a FlatZinc real completion within the declared tolerance`() {
        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val samples = ArrayList<Sample>()

            val verdict = solveFlatZinc(SemanticOverrides(), engine, samples::add)

            assertEquals(FiniteSolveVerdict.SAT, verdict, "$engine")
            assertNull(samples.last().exactReals, "$engine")
        }
    }

    private val unrelatedNonlinear = listOf(
        "constraint float_abs(a, b);",
        "constraint array_float_element(i, [0.25, 0.5], b);",
    )

    private fun solveWithUnrelated(relation: String, overrides: SemanticOverrides) = solveFlatZinc(overrides,
        declarations = "var -1.0..1.0: a; var 0.0..1.0: b; var 1..2: i;", constraints = relation)

    @Test
    fun `an unrelated nonlinear real relation should keep a FlatZinc completion within the declared tolerance`() {
        val verdicts = unrelatedNonlinear.associateWith { solveWithUnrelated(it, SemanticOverrides()) }

        assertEquals(unrelatedNonlinear.associateWith { FiniteSolveVerdict.SAT }, verdicts)
    }

    @Test
    fun `an unrelated nonlinear real relation should keep a zero FlatZinc tolerance exact`() {
        val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())

        val verdicts = unrelatedNonlinear.associateWith { solveWithUnrelated(it, exact) }

        assertEquals(unrelatedNonlinear.associateWith { FiniteSolveVerdict.UNSAT }, verdicts)
    }

    private val slackRelations = listOf(
        "var 0.0..2.0: b;" to "constraint float_abs(y, b);",
        "var 1..2: i;" to "constraint array_float_element(i, [2.0, 1.0], y);",
        "var 1..3: k; var 1.0..3.0: kf; var 0.0..6.0: b;" to
            "constraint int2float(k, kf); constraint float_times(kf, y, b);",
    )

    private fun solveSlackRelations(overrides: SemanticOverrides) =
        slackRelations.associate { (declarations, relation) ->
            relation to solveFlatZinc(overrides, declarations = declarations, constraints = relation)
        }

    @Test
    fun `a real relation over a row that needs the slack should be judged within the declared tolerance`() {
        val verdicts = solveSlackRelations(SemanticOverrides())

        assertEquals(slackRelations.associate { it.second to FiniteSolveVerdict.SAT }, verdicts)
    }

    @Test
    fun `a real relation over a row that needs the slack should be refuted at zero tolerance`() {
        val verdicts = solveSlackRelations(SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertEquals(slackRelations.associate { it.second to FiniteSolveVerdict.UNSAT }, verdicts)
    }

    @Test
    fun `incomplete engines should complete a real relation over a row that needs the slack`() {
        val verdicts = listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS).associateWith { engine ->
            slackRelations.associate { (declarations, relation) ->
                relation to solveFlatZinc(SemanticOverrides(), engine, declarations = declarations,
                    constraints = relation)
            }
        }

        assertEquals(listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS).associateWith {
            slackRelations.associate { it.second to FiniteSolveVerdict.SAT }
        }, verdicts)
    }

    // Source acceptance that refuses every floating point, so the search within tolerance decides each leaf.
    private fun exactPointsOnly(source: Problem) = capturedRealAcceptance(source, ExecutionControl.Never)?.let { base ->
        FiniteSourceAcceptance({ it.exactReals != null && base(it) }, permitsToleranceOptimum = true,
            search = SourceToleranceSearch(source, checkNotNull(source.semantics.effective.feasibilityTolerance)))
    }

    @Test
    fun `a leaf of linear rows should be completed by the search when its float point is refused`() {
        val verdict = solveFlatZinc(SemanticOverrides(), floatPoints = false)

        assertEquals(FiniteSolveVerdict.SAT, verdict)
    }

    @Test
    fun `minimization should complete a real relation over a row that needs the slack`() {
        val verdict = solveFlatZinc(SemanticOverrides(), floatPoints = false,
            source = roundedBound("var 0.0..2.0: b; var 0..3: z;", "constraint float_abs(y, b);")
                .replace("solve satisfy;", "solve minimize z;"))

        assertEquals(FiniteSolveVerdict.OPTIMAL, verdict)
    }

    // An absolute value over an operand in [-2.0, 3.0] beside a row every k leaves slack: each leaf has the optimum.
    private fun absObjective(direction: String) = """
        var 1..2: k;
        var 1.0..2.0: kf;
        var -2.0..3.0: x;
        var 0.0..5.0: y;
        constraint int2float(k, kf);
        constraint float_lin_le([1.0, -1.0], [x, kf], 2.0);
        constraint float_abs(x, y);
        solve $direction;
    """.trimIndent()

    // Exact optima in the minimized frame the source objective reads: a maximum of 3.0 is -3.0.
    private val absOptima = mapOf("minimize y" to 0.0, "maximize y" to -3.0, "minimize x" to -2.0, "maximize x" to -3.0)

    private val numerical = SemanticOverrides()
    private val exact = SemanticOverrides(feasibilityTolerance = SourceTolerance())

    // The backtracking verdict and the source objective at the last point it publishes for [source].
    private fun outcome(
        overrides: SemanticOverrides,
        source: String,
        presolve: PresolveConfig = PresolveConfig.NONE,
        floatPoints: Boolean = true,
    ): Pair<FiniteSolveVerdict, Double?> {
        val samples = ArrayList<Sample>()
        val verdict = solveFlatZinc(overrides, onSample = samples::add, source = source, floatPoints = floatPoints,
            presolve = presolve)
        val objective = parseFlatZinc(source).sourceProgram.linearObjective()
        return verdict to objective?.let { samples.lastOrNull()?.let(objective::evaluate) }
    }

    @Test
    fun `backtracking should prove the exact optimum of a real objective over an absolute value within tolerance`() {
        val reached = absOptima.keys.associateWith { outcome(numerical, absObjective(it)) }

        assertEquals(absOptima.mapValues { FiniteSolveVerdict.OPTIMAL to it.value }, reached)
    }

    @Test
    fun `backtracking should prove the optimum of a real objective over an absolute value exactly`() {
        val reached = absOptima.keys.associateWith { outcome(exact, absObjective(it)) }

        assertEquals(absOptima.mapValues { FiniteSolveVerdict.OPTIMAL to it.value }, reached)
    }

    @Test
    fun `backtracking should prove the optimum of a real objective over an absolute value under presolve`() {
        val reached = outcome(numerical, absObjective("maximize y"), PresolveConfig())

        assertEquals(FiniteSolveVerdict.OPTIMAL to -3.0, reached)
    }

    @Test
    fun `a real objective over an absolute value beyond the declared tolerance should be refuted`() {
        val source = """
            var 1..2: k;
            var -1.0..1.0: a;
            var 0.0..0.4: b;
            constraint float_abs(a, b);
            constraint float_eq(a, -0.5);
            solve minimize b;
        """.trimIndent()

        assertEquals(FiniteSolveVerdict.UNSAT, outcome(numerical, source).first)
    }

    @Test
    fun `a real objective over strict rows should be optimized over the region satisfaction decides`() {
        val directions = mapOf("satisfy" to FiniteSolveVerdict.SAT, "maximize x" to FiniteSolveVerdict.OPTIMAL,
            "minimize x" to FiniteSolveVerdict.OPTIMAL)

        val verdicts = directions.keys.associateWith { solve ->
            val source = "var 1..2: k; var 0.0..1.0: x; var 0.0..1.0: y; constraint float_lt(x, y); " +
                "constraint float_le(y, x); solve $solve;"
            outcome(numerical, source).first
        }

        assertEquals(directions, verdicts)
    }

    // Two rows that hold together only within the slack: `x - y <= -2^-25` and `x - y >= -2^-26`. Acceptance refuses
    // floating points, so the exact LP refutes every leaf and the search within the slack decides it.
    private fun slackRows(solve: String) = """
        var 1..2: k;
        var 0.0..1.0: x;
        var 0.0..1.0: y;
        constraint float_lin_le([1.0, -1.0], [x, y], -0.0000000298023223876953125);
        constraint float_lin_le([-1.0, 1.0], [x, y], 0.00000001490116119384765625);
        solve $solve;
    """.trimIndent()

    @Test
    fun `a real objective should be optimized over the tolerance region the satisfaction search decides`() {
        val directions = mapOf("satisfy" to FiniteSolveVerdict.SAT, "maximize x" to FiniteSolveVerdict.OPTIMAL,
            "minimize x" to FiniteSolveVerdict.OPTIMAL)

        val verdicts = directions.keys.associateWith { solve ->
            outcome(numerical, slackRows(solve), floatPoints = false).first
        }

        assertEquals(directions, verdicts)
    }

    @Test
    fun `a FlatZinc source without integer columns should be decided within the declared tolerance`() {
        val sources = mapOf(
            "within the slack" to "var 0.0..2.0: y; constraint float_lin_eq([3.0], [y], 6.0000000001); " +
                "solve satisfy;",
            "beyond the slack" to "var 0.0..2.0: y; constraint float_lin_eq([3.0], [y], 9.0); solve satisfy;",
            "optimum" to "var 0.0..2.0: y; var 0.0..5.0: z; constraint float_lin_eq([3.0], [y], 6.0000000001); " +
                "constraint float_lin_le([1.0, 1.0], [y, z], 4.5); solve maximize z;",
            "unbounded" to "var float: x; var float: y; constraint float_lin_le([1.0, -1.0], [x, y], 3.5); " +
                "solve maximize x;",
            "with Booleans" to "var bool: p; var bool: q; var 0.0..2.0: y; constraint bool_clause([p], [q]); " +
                "constraint float_lin_eq([3.0], [y], 6.0000000001); solve satisfy;",
        )

        val verdicts = sources.mapValues { (_, source) -> solveFlatZinc(SemanticOverrides(), source = source) }

        assertEquals(mapOf("within the slack" to FiniteSolveVerdict.SAT, "beyond the slack" to FiniteSolveVerdict.UNSAT,
            "optimum" to FiniteSolveVerdict.OPTIMAL, "unbounded" to FiniteSolveVerdict.UNBOUNDED,
            "with Booleans" to FiniteSolveVerdict.SAT), verdicts)
    }

    // `x < y` beside `y < x + bound`: each row keeps the absolute slack as its gap, so a point exists only when the
    // bound is at least twice that slack.
    private fun strictRows(bound: String, declarations: String = "", constraints: String = "") =
        "$declarations var 0.0..1.0: x; var 0.0..1.0: y; constraint float_lt(x, y); " +
            "constraint float_lin_lt([1.0, -1.0], [y, x], $bound); $constraints solve satisfy;"

    // An absolute slack of 1/1024, whose gaps binary64 states exactly.
    private val dyadicSlack = SemanticOverrides(feasibilityTolerance = SourceTolerance(SemanticRational.of(bigIntOf(1),
        bigIntOf(1024))))

    @Test
    fun `a strict row should keep the absolute slack as its gap by default`() {
        val sources = mapOf(
            "sides held together" to strictRows("0.0000001"),
            "bound below twice the slack" to strictRows("0.00000015"),
            "with integers" to strictRows("0.00000015", "var 0..2: k;", "constraint int_le(k, 1);"),
        )

        val verdicts = sources.mapValues { (_, source) -> solveFlatZinc(SemanticOverrides(), source = source) }

        assertEquals(sources.mapValues { FiniteSolveVerdict.UNSAT }, verdicts)
    }

    @Test
    fun `a strict row should admit exactly the absolute slack as its gap`() {
        val bounds = mapOf("twice the slack" to "0.001953125",
            "just below twice the slack" to "0.001953124068677425384521484375")

        val verdicts = bounds.mapValues { (_, bound) -> solveFlatZinc(dyadicSlack, source = strictRows(bound)) }

        assertEquals(mapOf("twice the slack" to FiniteSolveVerdict.SAT,
            "just below twice the slack" to FiniteSolveVerdict.UNSAT), verdicts)
    }

    @Test
    fun `a scaled strict row should keep the absolute slack in the units of the row`() {
        // `2x - 2y < 0` keeps `2(y - x) >= 1/1024`, so `y - x <= bound - 1/1024` needs a bound of 1.5/1024.
        val bounds = mapOf("room for half the slack" to "0.00146484375",
            "room below half the slack" to "0.001220703125")

        val verdicts = bounds.mapValues { (_, bound) ->
            solveFlatZinc(dyadicSlack, source = "var 0.0..1.0: x; var 0.0..1.0: y; " +
                "constraint float_lin_lt([2.0, -2.0], [x, y], 0.0); " +
                "constraint float_lin_lt([1.0, -1.0], [y, x], $bound); solve satisfy;")
        }

        assertEquals(mapOf("room for half the slack" to FiniteSolveVerdict.SAT,
            "room below half the slack" to FiniteSolveVerdict.UNSAT), verdicts)
    }

    @Test
    fun `incomplete engines should leave a strict row gap of the absolute slack unknown on the grid`() {
        // The default grid step 1/1023 holds no gap of exactly 1/1024, and a grid result is no source verdict.
        val engines = listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)

        val verdicts = engines.associateWith { solveFlatZinc(dyadicSlack, it, source = strictRows("0.001953125")) }

        assertEquals(engines.associateWith { FiniteSolveVerdict.UNKNOWN }, verdicts)
    }

    @Test
    fun `maximization over a strict row should attain the bound less the absolute slack`() {
        val sources = mapOf(
            "two variables" to "var 0.0..1.0: x; var 0.0..0.5: y; constraint float_lt(x, y); solve maximize x;",
            "one variable" to "var 0.0..1.0: x; constraint float_lt(x, 0.5); solve maximize x;",
        )

        val optima = sources.mapValues { (_, source) ->
            val samples = ArrayList<Sample>()
            val verdict = solveFlatZinc(SemanticOverrides(), onSample = samples::add, source = source)
            verdict to samples.last().approximateRealValue(0)
        }

        assertEquals(sources.mapValues { FiniteSolveVerdict.OPTIMAL }, optima.mapValues { it.value.first })
        assertTrue(optima.values.all { (_, x) -> kotlin.math.abs(x - 0.4999999) <= 1e-12 }, "$optima")
    }

    @Test
    fun `a real relation violated beyond the declared tolerance should be refuted`() {
        val verdict = solveFlatZinc(SemanticOverrides(), declarations = "var -1.0..1.0: a; var 0.0..0.4: b;",
            constraints = "constraint float_abs(a, b); constraint float_eq(a, -0.5);")

        assertEquals(FiniteSolveVerdict.UNSAT, verdict)
    }

    @Test
    fun `a zero FlatZinc tolerance should refute a completion only the slack admits`() {
        val verdict = solveFlatZinc(SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertEquals(FiniteSolveVerdict.UNSAT, verdict)
    }

    @Test
    fun `adaptive companion repair should reject unknown modes and missing completion`() {
        for (settings in listOf("companion-repair=never", "companion-repair=adaptive")) {
            val shape = FiniteSolveShape(Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                presolveConfig = PresolveConfig.NONE, executionOptions = ExecutionOptions.parse(listOf(settings)),
                nodeBudget = NodeBudget(8))

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `typed companion repair modes should reach completion enabled portfolios`() {
        for (repair in listOf("fixed", "adaptive")) {
            val options = ExecutionOptions(ExecutionOption.companionRepair(repair))
            val model = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val shape = FiniteSolveShape(model, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                presolveConfig = PresolveConfig.NONE, executionOptions = options,
                cancellation = ExecutionControl.after(1.seconds), nodeBudget = NodeBudget(8))

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}),
                TheoryParams(sourceIncumbentCompletion = true))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `parsed companion repair modes should reach completion enabled portfolios`() {
        for (repair in listOf("fixed", "adaptive")) {
            val options = ExecutionOptions.parse(listOf("companion-repair=$repair"))
            val model = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val shape = FiniteSolveShape(model, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy().finiteRequest(shape, FiniteEngine.MIXED,
                presolveConfig = PresolveConfig.NONE, executionOptions = options,
                cancellation = ExecutionControl.after(1.seconds), nodeBudget = NodeBudget(8))

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}),
                TheoryParams(sourceIncumbentCompletion = true))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `typed and parsed publication batching should execute curated integer optimization`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.portfolioPublications("segment")),
            ExecutionOptions.parse(listOf("portfolio-publications=segment")))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = ExecutionControl.after(1.seconds), executionOptions = options, nodeBudget =
                NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `publication batching should reject unknown policies explicit recipes and work schedules`() {
        for (settings in listOf(listOf("portfolio-publications=never"),
            listOf("portfolio-publications=segment", "arms=2"),
            listOf("portfolio-publications=segment", "bt-arm=satOptimized"),
            listOf("portfolio-publications=segment", "portfolio-work=100"))) {
            val shape = FiniteSolveShape(Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings), cancellation = ExecutionControl.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `publication batching should reject unsupported operations lanes and engines`() {
        for ((optimize, cores, engine) in listOf(
            Triple(false, 1, FiniteEngine.MIXED), Triple(true, 2, FiniteEngine.MIXED),
            Triple(true, 1, FiniteEngine.LOCAL_SEARCH), Triple(true, 1, FiniteEngine.BACKTRACK),
        )) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)).takeIf { optimize }, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, engine, cores = cores, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("portfolio-publications=segment")),
                cancellation = ExecutionControl.after(1.seconds),
            )

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `publication batching should reject Boolean sources`() {
        val shape = FiniteSolveShape(Problem.of(1, 0, emptyArray(), emptyArray()), true, false, null,
            LinearObjective(longArrayOf(1)), 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
            shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
            executionOptions = ExecutionOptions.parse(listOf("portfolio-publications=segment")),
            cancellation = ExecutionControl.after(1.seconds),
        )

        assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
    }

    @Test
    fun `publication batching should require a containing deadline`() {
        val shape = FiniteSolveShape(Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()), true, false, null,
            LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
        val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
            shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
            executionOptions = ExecutionOptions.parse(listOf("portfolio-publications=segment")),
            nodeBudget = NodeBudget(8),
        )

        assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
    }

    @Test
    fun `fixed cumulative optimization should automatically use cost aware propagation`() {
        for (hints in listOf(null, SearchHints(emptyList()))) {
            val problem = Problem.of(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(
                Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), 1),
            ))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1, 1)), null, null, searchHints = hints)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.FIXED, presolveConfig = PresolveConfig.NONE,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict)
            assertTrue(completed.stats.search.learning.getValue("propagationScheduleDispatches") > 0)
        }
    }

    @Test
    fun `explicit fifo should override automatic fixed cumulative scheduling`() {
        for (options in listOf(ExecutionOptions(ExecutionOption.costAwarePropagation(false)),
            ExecutionOptions.parse(listOf("cost-aware-propagation=false")))) {
            val problem = Problem.of(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(
                Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(1, 1), 1),
            ))
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1, 1)), null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.FIXED, presolveConfig = PresolveConfig.NONE, executionOptions = options,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.OPTIMAL, completed.verdict)
            assertTrue(completed.stats.search.learning.keys.none { it.startsWith("propagationSchedule") })
        }
    }

    @Test
    fun `unqualified fixed models should retain fifo scheduling`() {
        val starts = intArrayOf(0, 1)
        val durations = longArrayOf(2, 2)
        val resources = longArrayOf(1, 1)
        for ((optimize, factor) in listOf(
            false to Cumulative(starts, durations, resources, 1),
            true to null,
            true to Cumulative(starts, durations, resources, 1, presents = intArrayOf(0, 0)),
            true to Cumulative(starts, durations, resources, 2, capacityVar = 2),
            true to Cumulative(starts, durations, resources, 1, durationVars = intArrayOf(2, 2)),
            true to Cumulative(starts, durations, resources, 2, resourceVars = intArrayOf(2, 2)),
        )) {
            val problem = Problem.of(1, 3, Array(3) { if (it == 2) IntDomain(1, 2) else IntDomain(0, 4) },
                listOfNotNull(factor).toTypedArray())
            val shape = FiniteSolveShape(problem, optimize, false, null,
                LinearObjective(intCoefficients = longArrayOf(1, 1, 0)).takeIf { optimize }, null, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.FIXED, presolveConfig = PresolveConfig.NONE,
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertTrue(completed.stats.search.learning.keys.none { it.startsWith("propagationSchedule") })
        }
    }

    @Test
    fun `funded companion admission should reject missing start contracts`() {
        for (settings in listOf(listOf("companion-admission=never"), listOf("companion-admission=funded"),
            listOf("companion-admission=funded", "companion-after-incumbent=true"),
            listOf("companion-admission=progress-funded"),
            listOf("companion-admission=progress-funded", "companion-after-incumbent=true"))) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(settings), nodeBudget = NodeBudget(8),
            )

            assertFailsWith<PipelineConfigException> { FinitePipeline.solve(request, FiniteSolveCallbacks({}, {})) }
        }
    }

    @Test
    fun `typed and parsed funded admission should reach the source portfolio`() {
        val variants = listOf("funded", "progress-funded").flatMap { admission ->
            listOf(ExecutionOptions(ExecutionOption.companionAdmission(admission),
                ExecutionOption.companionAfterIncumbent(true)), ExecutionOptions.parse(listOf(
                "companion-admission=$admission", "companion-after-incumbent=true")))
        }
        for (options in variants) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, true, false, null,
                LinearObjective(intCoefficients = longArrayOf(1)), 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                cancellation = ExecutionControl.after(1.seconds), executionOptions = options, nodeBudget =
                NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            assertIs<FiniteSolveOutcome.Completed>(result.outcome)
        }
    }

    @Test
    fun `Boolean proof controls should reach actual source portfolio probing`() {
        for ((enabled, options) in listOf(
            true to ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail")),
            true to ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail"))),
            false to ExecutionOptions.parse(listOf("arms=2", "bt-arm=clausal-balanced")),
            false to ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "boolean-proof-tasks=false")),
            true to ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "boolean-proof-tasks=true")),
            true to ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail")), ExecutionOption.booleanProofTasks(true)),
        )) {
            val problem = Problem.of(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ))
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            val observations = completed.stats.portfolio.booleanProof

            assertEquals(if (enabled) 1 else 0, observations.size)
            if (enabled) {
                assertNull(observations.single().decline)
                assertEquals(1, observations.single().probeSegments)
                assertEquals(0, observations.single().tasks)
                assertTrue(observations.single().workerWork >= observations.single().probeWork)
            }
        }
    }

    @Test
    fun `private rebuild budgets should preserve the default clausal source pool`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.privateRebuildBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("private-rebuild-bytes=4194304")),
            ExecutionOptions(ExecutionOption.privateRebuildWork(0L)),
            ExecutionOptions.parse(listOf("private-rebuild-work=0")),
            ExecutionOptions(ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("learned-memory-scratch-bytes=4194304")),
            ExecutionOptions(ExecutionOption.privateRebuildWork(0L),
                ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("private-rebuild-work=0", "learned-memory-scratch-bytes=4194304")),
            ExecutionOptions(ExecutionOption.privateRebuildWork(-1L),
                ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("private-rebuild-work=-1", "learned-memory-scratch-bytes=4194304")),
        )) {
            val problem = Problem.of(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ))
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            assertEquals(listOf("ls/probsat/plain", "bt/clausal-balanced"),
                completed.stats.portfolio.arms.map { it.label })
            assertTrue(completed.stats.portfolio.arms.all { it.faults == 0L && it.failures == 0L })
        }
    }

    @Test
    fun `ordinary private defaults should honor explicit work controls`() {
        for ((enabled, options) in listOf(
            true to ExecutionOptions(ExecutionOption.sliceWork(1_000L)),
            false to ExecutionOptions(ExecutionOption.sliceWork(1_000L), ExecutionOption.privateRebuildWork(0L),
                ExecutionOption.privateRebuildBytes(4_194_304L)),
            false to ExecutionOptions.parse(listOf("slice-work=1000",
                "private-rebuild-work=0", "private-rebuild-bytes=4194304")),
        )) {
            val parity = (0 until 128).flatMap { row ->
                listOf(1, 2, 4, 7).map { mask ->
                    Clause(IntArray(3) { Lit.make(row + 2 * (row / 16) + it, mask and (1 shl it) == 0) })
                }
            }
            val contradiction = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            )
            val problem = Problem.of(144, 0, emptyArray(), (parity + contradiction).toTypedArray())
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.MIXED, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(50_000),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.UNSAT, completed.verdict)
            val learning = completed.stats.search.learning
            val consumers = if (enabled) 1L else 0L
            assertEquals(consumers, learning["privateRebuildParityJointFactors"] ?: 0L)
            assertEquals(consumers, learning["privateRebuildAutomaticAttempts"] ?: 0L)
            assertEquals(if (enabled) 4_194_304L else 0L,
                learning["privateRebuildAutomaticByteLimit"] ?: 0L)
            assertEquals(if (enabled) 1_048_576L else 0L,
                learning["privateRebuildAutomaticCanonicalScratchLimit"] ?: 0L)
            assertEquals(consumers, learning["privateRebuildConsumers"] ?: 0L)
            assertEquals(consumers, learning["privateRebuildRetired"] ?: 0L)
            assertEquals(listOf("ls/probsat/plain", "bt/clausal-balanced"),
                completed.stats.portfolio.arms.map { it.label })
            assertTrue(completed.stats.portfolio.arms.all { it.faults == 0L && it.failures == 0L })
        }
    }

    @Test
    fun `automatic private rebuilding should reach the source portfolio`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail")),
                ExecutionOption.privateRebuildWork(-1L), ExecutionOption.learnedMemoryScratchBytes(4_194_304L)),
            ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "private-rebuild-work=-1",
                "learned-memory-scratch-bytes=4194304")),
        )) {
            val clauses = (0 until 128).flatMap { row ->
                listOf(1, 2, 4, 7).map { mask ->
                    Clause(IntArray(3) { Lit.make(row + 2 * (row / 16) + it, mask and (1 shl it) == 0) })
                }
            } + Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))
            val problem = Problem.of(144, 0, emptyArray(), clauses.toTypedArray())
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(5_000),
            )
            val samples = mutableListOf<Sample>()

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({ samples += it }, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            assertTrue(samples.isNotEmpty())
            for (sample in samples) {
                assertEquals(problem.numBoolVars, sample.numBoolVars)
                assertTrue(clauses.all { clause ->
                    clause.literals.any { Lit.evaluate(it, sample.boolValue(Lit.variable(it))) }
                })
            }
            val learning = completed.stats.search.learning
            assertTrue(learning.getValue("privateRebuildAutomaticAdmitted") > 0L)
            assertTrue(learning.getValue("privateRebuildConsumers") > 0L)
            assertTrue(learning.getValue("privateRebuildSourceAccepted") > 0L)
            assertEquals(learning.getValue("privateRebuildConsumers"), learning["privateRebuildRetired"])
            assertTrue(completed.stats.portfolio.booleanProof.isEmpty())
            assertTrue(completed.stats.portfolio.arms.all { it.faults == 0L && it.failures == 0L })
        }
    }

    @Test
    fun `explicit source proof tasks should decline private rebuild recipes`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.arms(2),
                ExecutionOption.backtrackArmsByLabel(listOf("first-fail")),
                ExecutionOption.privateRebuildWork(65_536L), ExecutionOption.booleanProofTasks(true)),
            ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail", "private-rebuild-work=65536",
                "boolean-proof-tasks=true")),
        )) {
            val problem = Problem.of(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ))
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = options, nodeBudget = NodeBudget(8),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))

            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            val observation = completed.stats.portfolio.booleanProof.single()
            assertEquals("worker-model-or-recipe-mismatch", observation.decline)
            assertEquals(0, observation.tasks)
            assertEquals(0, observation.probeSegments)
        }
    }

    @Test
    fun `unsupported source models should keep ordinary solving and report only explicit task declines`() {
        for (explicit in listOf(false, true)) {
            val problem = Problem.of(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val shape = FiniteSolveShape(problem, false, false, null, null, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                executionOptions = ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail") +
                    if (explicit) listOf("boolean-proof-tasks=true") else emptyList()),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)
            assertEquals(FiniteSolveVerdict.SAT, completed.verdict)
            val observations = completed.stats.portfolio.booleanProof

            assertEquals(if (explicit) 1 else 0, observations.size)
            if (explicit) {
                assertEquals("model-ineligible", observations.single().decline)
                assertEquals(0, observations.single().tasks)
                assertEquals(0, observations.single().probeSegments)
            }
        }
    }

    @Test
    fun `Boolean proof controls should reject optimization and parallel lanes`() {
        for ((optimize, cores) in listOf(true to 1, false to 2)) {
            val problem = Problem.of(2, 0, emptyArray(), emptyArray())
            val objective = LinearObjective(boolWeights = longArrayOf(1L, 0L)).takeIf { optimize }
            val shape = FiniteSolveShape(problem, optimize, false, null, objective, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE, cores = cores,
                executionOptions = ExecutionOptions.parse(listOf("boolean-proof-tasks=true")),
            )

            assertFailsWith<PipelineConfigException> {
                FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            }
        }
    }

    @Test
    fun `automatic Boolean tasks should bypass optimization and parallel execution`() {
        for ((optimize, cores) in listOf(true to 1, false to 2)) {
            val problem = Problem.of(2, 0, emptyArray(), emptyArray())
            val objective = LinearObjective(boolWeights = longArrayOf(1L, 0L)).takeIf { optimize }
            val shape = FiniteSolveShape(problem, optimize, false, null, objective, 0, null)
            val request = SourceExecutionPolicy(lpCeiling = LpConfig.OFF).finiteRequest(
                shape, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE, cores = cores,
                executionOptions = ExecutionOptions.parse(listOf("arms=2", "bt-arm=first-fail")),
            )

            val result = FinitePipeline.solve(request, FiniteSolveCallbacks({}, {}))
            val completed = assertIs<FiniteSolveOutcome.Completed>(result.outcome)

            assertTrue(completed.stats.portfolio.booleanProof.isEmpty())
        }
    }

    @Test
    fun `a mixed request with only heuristic arms should retain its resolved admission`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))
        val request = SourceExecutionRequest(invocation, ExecutionOptions(
            ExecutionOption.backtrackArms(0), ExecutionOption.localSearchArms(1),
        ))

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(request))

        assertEquals(false, result.result.completeSearchAdmitted)
        assertEquals(false, result.completeSearchAdmitted)
        assertEquals(FiniteSolveVerdict.SAT, assertIs<FiniteSolveOutcome.Completed>(result.result.outcome).verdict)
    }

    @Test
    fun `preparation diagnostics should have no resolved search admission`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val request = SourceExecutionRequest(invocation, ExecutionOptions(ExecutionOption.dryRunPresolve(true)))

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(request))

        assertIs<FiniteSolveOutcome.PreparedOnly>(result.result.outcome)
        assertNull(result.result.completeSearchAdmitted)
        assertNull(result.completeSearchAdmitted)
    }

    @Test
    fun `containing cancellation before preparation should not invent a worker admission`() {
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SmtLib.parse("(declare-const b Bool) (assert b)").source,
            ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))
        var cancelled = false
        val request = SourceExecutionRequest(invocation, ExecutionOptions(), cancellation =
            ExecutionControl { cancelled })
        cancelled = true

        val result = assertIs<SourceExecutionResult.Finite>(SourceSetup.execute(request))

        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.result.outcome).verdict)
        assertNull(result.completeSearchAdmitted)
    }
}
