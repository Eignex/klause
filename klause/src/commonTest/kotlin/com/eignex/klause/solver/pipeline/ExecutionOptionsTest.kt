package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.localsearch.strategy.AxisToken
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecutionOptionsTest {
    @Test
    fun `typed weighted search controls should match their parsed scenario`() {
        val typed = ExecutionOptions(ExecutionOption.coreEarly(true),
            ExecutionOption.coreRefinement(CoreRefinementMode.REASONS))
        val parsed = ExecutionOptions.parse(listOf("core-early=true", "core-refinement=reasons"))

        val expected = buildPortfolioScenario(typed.reader(), 3L, 1, Kind.COP, EngineMix.MIXED, 6)
        val actual = buildPortfolioScenario(parsed.reader(), 3L, 1, Kind.COP, EngineMix.MIXED, 6)

        assertEquals(expected, actual)
        assertTrue(actual.earlyCoreAdmission)
        assertEquals(CoreRefinementMode.REASONS, actual.coreRefinement)
    }

    @Test
    fun `unknown objective core refinement should be rejected before worker creation`() {
        val options = ExecutionOptions.parse(listOf("core-refinement=unbounded"))

        assertFailsWith<PipelineConfigException> {
            buildPortfolioScenario(options.reader(), 3L, 1, Kind.COP, EngineMix.MIXED, 6)
        }
    }

    @Test
    fun `parsed and typed luby units should reject non-positive values before recipe construction`() {
        for (base in listOf(0L, -1L, Long.MIN_VALUE)) {
            for (options in listOf(
                ExecutionOptions.parse(listOf("luby=$base", "ema-restart=on")),
                ExecutionOptions(ExecutionOption.luby(base), ExecutionOption.emaRestart(true)),
            )) {
                val failure = assertFailsWith<PipelineConfigException> {
                    backtrackOverride(options.reader(), allowSelectors = true)
                }
                assertTrue("luby" in failure.message.orEmpty())
                assertTrue("positive integer" in failure.message.orEmpty())
            }
        }
    }

    @Test
    fun `resource settings should retain automatic unlimited and zero as distinct policies`() {
        for ((raw, expected) in listOf("auto" to 13L, "unlimited" to null, "0" to 0L, "7" to 7L)) {
            val options = ExecutionOptions.parse(listOf("nra-work-limit=$raw"))

            assertEquals(expected, options.reader().defaulted(ExecutionOption.nraWorkLimit, 13L))
        }
    }

    @Test
    fun `conflicting resource settings should reject both typed and parsed declarations`() {
        for (options in listOf(
            ExecutionOptions(ExecutionOption.nodeLimit(3L), ExecutionOption.nodeLimit(null)),
            ExecutionOptions.parse(listOf("node-limit=3", "node-limit=unlimited")),
            ExecutionOptions.parse(listOf("node-limit=3", "node-limit=4", "nra-work-limit=0", "nra-work-limit=1")),
        )) {
            assertFailsWith<PipelineConfigException> { options.reader()[ExecutionOption.nodeLimit] }
        }
    }

    @Test
    fun `equivalent repeated resource amounts should retain one allowance`() {
        val options = ExecutionOptions.parse(listOf("node-limit=0", "node-limit=00"))

        assertEquals(0L, options.reader()[ExecutionOption.nodeLimit])
    }

    @Test
    fun `numerical completion policy should preserve captured tuning and independent consumption`() {
        val options = ExecutionOptions.parse(listOf("seed=7", "arms=2"))

        val numerical = options.withNumericalCompletion()

        assertFalse(options.numericalCompletion)
        assertTrue(numerical.numericalCompletion)
        assertEquals(options.originalPairs, numerical.originalPairs)
        assertEquals(
            buildPortfolioScenario(options.reader(), null, 1, Kind.CSP, EngineMix.BACKTRACK, 6),
            buildPortfolioScenario(numerical.reader(), null, 1, Kind.CSP, EngineMix.BACKTRACK, 6),
        )
    }

    @Test
    fun `typed portfolio settings should preserve parsed settings and seed precedence`() {
        val typed = ExecutionOptions(
            ExecutionOption.seed(2L),
            ExecutionOption.seed(7L),
            ExecutionOption.arms(3),
            ExecutionOption.lambda(0.75),
            ExecutionOption.sliceWork(1_000L),
            ExecutionOption.sliceMaxWork(2_000L),
            ExecutionOption.sliceGrowth(1.25),
            ExecutionOption.phaseRetention(0.5),
        )
        val parsed = ExecutionOptions.parse(
            listOf(
                "seed=2",
                "seed=7",
                "arms=3",
                "lambda=0.75",
                "slice-work=1000",
                "slice-max-work=2000",
                "slice-growth=1.25",
                "phase-retention=0.5",
            ),
        )
        val controls = listOf(typed, parsed)

        val scenarios = controls.map {
            buildPortfolioScenario(it.reader(), 99L, 4, Kind.COP, EngineMix.MIXED, 6)
        }

        assertEquals(scenarios.first(), scenarios.last())
        assertEquals(7L, scenarios.first().seed)
        assertEquals(4, scenarios.first().cores)
        assertEquals(4, scenarios.first().arms)
    }

    @Test
    fun `typed selector overrides should create independent workers with one allowance`() {
        val budget = NodeBudget(20)
        val options = ExecutionOptions(
            ExecutionOption.variableSelector(VarSelectorKind.VSIDS),
            ExecutionOption.valueSelector(ValSelectorKind.SOLUTION_GUIDED),
            ExecutionOption.maxDecisions(11L),
        )
        val edit = resolveBtRecipes(options.reader(), Kind.CSP).edit!!
        val base = BacktrackParams(randomSeed = 17L, nodeBudget = budget)

        val first = edit(base)
        val second = edit(base)

        assertNotSame(first.variableSelector, second.variableSelector)
        assertNotSame(first.valueSelector, second.valueSelector)
        assertSame(budget, first.nodeBudget)
        assertSame(budget, second.nodeBudget)
        assertEquals(17L, first.randomSeed)
        assertEquals(11L, first.maxDecisions)
    }

    @Test
    fun `typed axis settings should capture caller lists before recipe resolution`() {
        val scoring = mutableListOf(AxisToken(null, AxisToken.Op.SET, "raw"))
        val options = ExecutionOptions(ExecutionOption.strategy("cbls"), ExecutionOption.scoring(scoring))
        scoring.clear()
        val parsed = ExecutionOptions.parse(listOf("strategy=cbls", "scoring=raw"))

        val actual = resolveLocalSearchRecipes(options.reader()).pool!!.single()()
        val expected = resolveLocalSearchRecipes(parsed.reader()).pool!!.single()()

        assertEquals(MoveScoring.Raw, actual.strategy.scoring)
        assertEquals(expected.strategy.scoring, actual.strategy.scoring)
    }

    @Test
    fun `typed backtrack labels should capture order without a frontend list grammar`() {
        val labels = mutableListOf("free", "conflictDriven")
        val options = ExecutionOptions(ExecutionOption.backtrackArmsByLabel(labels))
        labels.reverse()

        val resolved = resolveBtRecipes(options.reader(), Kind.COP)

        assertEquals(listOf("free", "conflictDriven"), resolved.pool!!.map { it().label })
    }

    @Test
    fun `typed scalar axes should reject set edits with the frontend diagnostic`() {
        val typed = assertFailsWith<PipelineConfigException> {
            resolveLocalSearchRecipes(
                ExecutionOptions(
                    ExecutionOption.scoring(listOf(AxisToken(null, AxisToken.Op.ADD, "raw"))),
                ).reader(),
            )
        }
        val parsed = assertFailsWith<PipelineConfigException> {
            resolveLocalSearchRecipes(ExecutionOptions.parse(listOf("scoring=+raw")).reader())
        }

        assertEquals("ls: scoring is single-valued and takes no +/- edit (got `raw`)", typed.message)
        assertEquals(typed.message, parsed.message)
    }

    @Test
    fun `unconsumed typed scalar errors should keep the selected route diagnostic`() {
        val options = ExecutionOptions(
            ExecutionOption.scoring(listOf(AxisToken(null, AxisToken.Op.ADD, "raw"))),
        )
        val legacy = assertFailsWith<PipelineConfigException> {
            applyBacktrackParams(BacktrackParams(), EngineParams(listOf("scoring=+raw")))
        }

        val typed = assertFailsWith<PipelineConfigException> {
            applyFixedBacktrackParams(BacktrackParams(), options.reader(), false)
        }

        assertEquals(legacy.message, typed.message)
    }

    @Test
    fun `frontend values should keep route diagnostic precedence`() {
        val controls = listOf(
            listOf("malformed"),
            listOf("seed=bad", "arms=bad"),
            listOf("slice-work=0", "unrecognized=1"),
        )
        for (pairs in controls) {
            val legacy = assertFailsWith<PipelineConfigException> {
                buildPortfolioScenario(EngineParams(pairs), null, 1, Kind.CSP, EngineMix.BACKTRACK, 6)
            }
            val typed = assertFailsWith<PipelineConfigException> {
                buildPortfolioScenario(
                    ExecutionOptions.parse(pairs).reader(),
                    null,
                    1,
                    Kind.CSP,
                    EngineMix.BACKTRACK,
                    6,
                )
            }
            assertEquals(legacy.message, typed.message)
        }
    }

    @Test
    fun `fixed annotated search should reject selector overrides without decoding them`() {
        for (pairs in listOf(listOf("var-selector=invalid"), listOf("var-selector=vsids"))) {
            val legacy = assertFailsWith<PipelineConfigException> {
                applyBacktrackParams(BacktrackParams(), EngineParams(pairs))
            }
            val typed = assertFailsWith<PipelineConfigException> {
                applyFixedBacktrackParams(BacktrackParams(), ExecutionOptions.parse(pairs).reader(), false)
            }
            assertEquals(legacy.message, typed.message)
        }
    }

    @Test
    fun `local search dry run should keep unconsumed invalid portfolio options`() {
        val request = PortfolioPlanRequest(
            engine = FiniteEngine.LOCAL_SEARCH, optimize = false, cores = 2,
            executionOptions = ExecutionOptions.parse(listOf("dry-run-solver=on", "seed=bad", "unknown=1")),
            randomSeed = null, defaultArms = 6, lpCeiling = LpConfig.OFF,
            zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget = null, annotationArm = null,
        )

        val plan = FinitePipeline.planPortfolio(request)

        assertIs<PortfolioPlan.LocalSearchDryRun>(plan)
    }

    @Test
    fun `typed fixed requests should apply seed overrides before heuristic construction`() {
        val request = FixedBacktrackPlanRequest(
            annotatedParams = null,
            executionOptions = ExecutionOptions(
                ExecutionOption.seed(7L),
                ExecutionOption.luby(17L),
                ExecutionOption.dryRunSolver(true),
                ExecutionOption.variableSelector(VarSelectorKind.LINUCB),
            ),
            randomSeed = 99L, cancellation = Cancellation.Never, nodeBudget = null, solveBudgetMillis = null,
            lpConfig = LpConfig.OFF, zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT, onEvent = null,
        )

        val first = FinitePipeline.planFixedBacktrack(request)
        val second = FinitePipeline.planFixedBacktrack(request)

        assertEquals(7L, first.params.randomSeed)
        assertEquals(17L, first.params.lubyRestartBase)
        assertTrue(first.dryRun)
        assertNotSame(first.params.variableSelector, second.params.variableSelector)
    }
}
