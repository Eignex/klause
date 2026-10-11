package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.ProblemClass
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortfolioPlanTest {

    @Test
    fun `automatic sequential clausal satisfaction uses one arm per family`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val profile = ProblemProfile(ProblemClass.Sat, optimizing = false, wide = false, scheduling = false)

        val resolved = clausalDefaultScenario(scenario, profile, autoArms(1), explicitArms = false)

        assertEquals(2, resolved.arms)
    }

    @Test
    fun `explicit counts and recipes retain the requested pool size`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val profile = ProblemProfile(ProblemClass.Sat, optimizing = false, wide = false, scheduling = false)
        val controls = listOf(
            scenario.copy(cores = 2),
            scenario.copy(kind = Kind.COP),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("clausal-balanced") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("probsat/plain") }),
            scenario.copy(btEdit = { it }),
            scenario.copy(annotationArm = BacktrackParams()),
        )

        for (control in controls) {
            assertEquals(control.arms,
                clausalDefaultScenario(control, profile, autoArms(1), explicitArms = false).arms)
        }
        assertEquals(6, clausalDefaultScenario(scenario, profile, autoArms(1), explicitArms = true).arms)
        assertEquals(6, clausalDefaultScenario(scenario,
            profile.copy(problemClass = ProblemClass.PseudoBoolean), autoArms(1), explicitArms = false).arms)
        assertEquals(12, clausalDefaultScenario(scenario.copy(arms = 12),
            profile, 12, explicitArms = false).arms)
    }

    @Test
    fun `curated sequential clausal satisfaction reserves complete proof time`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val profile = ProblemProfile(ProblemClass.Sat, optimizing = false, wide = false, scheduling = false)

        val shares = clausalProofShares(scenario, profile, listOf("ls/probsat/plain", "bt/clausal-balanced"))

        assertEquals(listOf(0.0, 0.9), shares.toList())
    }

    @Test
    fun `explicit or non clausal search keeps unreserved scheduling`() {
        val scenario = PortfolioScenario.sequential(Kind.CSP)
        val profile = ProblemProfile(ProblemClass.Sat, optimizing = false, wide = false, scheduling = false)
        val labels = listOf("ls/probsat/plain", "bt/clausal-balanced")
        val controls = listOf(
            scenario.copy(cores = 2),
            scenario.copy(kind = Kind.COP),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("clausal-balanced") }),
            scenario.copy(lsPool = listOf { LocalSearchCatalog.byLabel("probsat/plain") }),
            scenario.copy(btEdit = { it }),
            scenario.copy(annotationArm = BacktrackParams()),
        )

        for (control in controls) assertTrue(clausalProofShares(control, profile, labels).isEmpty())
        assertTrue(clausalProofShares(scenario,
            profile.copy(problemClass = ProblemClass.PseudoBoolean), labels).isEmpty())
        assertTrue(clausalProofShares(scenario, profile, listOf("bt/satOptimized")).isEmpty())
    }

    @Test
    fun `portfolio resolves distinct slice schedules`() {
        val controls = listOf(emptyList<String>() to Triple(5_000L, 150_000L, 1.5),
            listOf("slice-work=1000", "slice-max-work=1000", "slice-growth=1") to Triple(1_000L, 1_000L, 1.0),
            listOf("slice-work=20000", "slice-max-work=60000", "slice-growth=2") to Triple(20_000L, 60_000L, 2.0))
        for ((params, expected) in controls) {
            val scenario = buildPortfolioScenario(EngineParams(params), 1L, 1, Kind.COP, EngineMix.MIXED, 6)

            assertEquals(expected, Triple(scenario.sliceWork, scenario.maxSliceWork, scenario.sliceGrowth))
            val copied = scenario.copy(seed = 7L)
            assertEquals(expected, Triple(copied.sliceWork, copied.maxSliceWork, copied.sliceGrowth))
        }
    }

    @Test
    fun `portfolio rejects invalid slice schedules`() {
        for (params in listOf(listOf("slice-work=0"), listOf("slice-work=-1"),
            listOf("slice-max-work=4999"), listOf("slice-growth=0.5"), listOf("slice-growth=NaN"),
            listOf("slice-growth=Infinity"))) {
            assertFailsWith<PipelineConfigException> {
                buildPortfolioScenario(EngineParams(params), 1L, 1, Kind.COP, EngineMix.MIXED, 6)
            }
        }
    }

    @Test
    fun `portfolio phase timing accepts an explicit opt in`() {
        for (enabled in listOf(false, true)) {
            val scenario = buildPortfolioScenario(
                EngineParams(listOf("ls-phase-times=$enabled")), 1L, 1, Kind.COP, EngineMix.MIXED, 6,
            )

            assertEquals(enabled, scenario.reportLsPhaseTimes)
            assertEquals(enabled, scenario.copy(phaseRetention = 0.5).reportLsPhaseTimes)
        }
    }

    @Test
    fun `portfolio residual reporting accepts an explicit opt in`() {
        val scenario = buildPortfolioScenario(
            EngineParams(listOf("ls-residuals=true")), 1L, 1, Kind.COP, EngineMix.MIXED, 6,
        )

        assertTrue(scenario.reportLsResiduals)
        assertTrue(scenario.copy(phaseRetention = 0.5).reportLsResiduals)
    }

    @Test
    fun `curated sequential continuous optimization reserves LP descent time`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemProfile(ProblemClass.MixedInteger, optimizing = true, wide = false, scheduling = false)

        val shares = continuousLpShares(scenario, profile, listOf("bt/satOptimized", "bt/lp-default", "ls/cbls/fixed"),
            LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        assertEquals(listOf(0.0, 0.5, 0.0), shares.toList())
    }

    @Test
    fun `explicit and inapplicable scenarios keep their requested scheduling`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemProfile(ProblemClass.MixedInteger, optimizing = true, wide = false, scheduling = false)
        val labels = listOf("bt/satOptimized", "bt/lp-default")
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val scenarios = listOf(
            scenario.copy(cores = 2),
            scenario.copy(kind = Kind.CSP),
            scenario.copy(engine = EngineMix.BACKTRACK),
            scenario.copy(lpCeiling = LpConfig.OFF),
            scenario.copy(lpCeiling = LpConfig(overrides = mapOf(LpTechnique.BOUNDING to false))),
            scenario.copy(btPool = listOf { BacktrackCatalog.byLabel("lp-default") }),
        )

        for (explicit in scenarios) assertTrue(continuousLpShares(explicit, profile, labels, objective).isEmpty())
        assertTrue(continuousLpShares(scenario,
            profile.copy(problemClass = ProblemClass.FiniteCp), labels, objective).isEmpty())
        assertTrue(continuousLpShares(scenario, profile,
            listOf("bt/satOptimized", "bt/conflictDriven"), objective).isEmpty())
    }

    @Test
    fun `finite objectives keep unreserved scheduling with continuous constraints`() {
        val scenario = PortfolioScenario.sequential(Kind.COP)
        val profile = ProblemProfile(ProblemClass.MixedInteger, optimizing = true, wide = false, scheduling = false)
        val labels = listOf("bt/satOptimized", "bt/lp-default")

        for (objective in listOf(null, LinearObjective(intCoefficients = longArrayOf(1L)),
            LinearObjective(realCoefficients = doubleArrayOf(0.0)))) {
            assertTrue(continuousLpShares(scenario, profile, labels, objective).isEmpty())
        }
    }

    @Test
    fun `root LP overrides preserve independent node techniques`() {
        val params = EngineParams(listOf("lp-variable-shaving=true", "lp-prune-hulls=true", "lp-root-cuts=false"))
        val edit = checkNotNull(backtrackOverride(params, allowSelectors = true))
        val base = BacktrackParams(lpPlan = LpPlan(bounding = true, cuts = true))

        val edited = edit(base)
        params.finish("backtrack", BACKTRACK_OVERRIDE_KEYS.joinToString())

        assertTrue(edited.lpPlan.variableShaving)
        assertTrue(edited.lpPlan.pruneHulls)
        assertFalse(edited.lpPlan.rootCutHarvest)
        assertTrue(edited.lpPlan.cuts)
        assertTrue(edited.lpPlan.bounding)
    }

    @Test
    fun `portfolio reseeding accepts an off control and nonnegative thresholds`() {
        for ((params, expected) in listOf(emptyList<String>() to 3, listOf("reseed-stale-threshold=0") to 0,
            listOf("reseed-stale-threshold=2") to 2, listOf("reseed-stale-threshold=4") to 4)) {
            val scenario = buildPortfolioScenario(EngineParams(params), 1L, 1, Kind.COP, EngineMix.MIXED, 6)

            assertEquals(expected, scenario.reseedStaleThreshold)
        }
    }

    @Test
    fun `portfolio rejects a negative reseeding threshold`() {
        assertFailsWith<PipelineConfigException> {
            buildPortfolioScenario(EngineParams(listOf("reseed-stale-threshold=-1")), 1L, 1,
                Kind.COP, EngineMix.MIXED, 6)
        }
    }


    private fun plan(annotated: BacktrackParams?, params: List<String> = emptyList()) =
        FinitePipeline.planFixedBacktrack(
            FixedBacktrackPlanRequest(
                annotatedParams = annotated,
                engineParams = params,
                randomSeed = 1L,
                cancellation = Cancellation.Never,
                nodeBudget = null,
                solveBudgetMillis = null,
                lpConfig = LpConfig.AUTO,
                zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                onEvent = null,
            ),
        )

    @Test
    fun `a model that states its own search is never branched by the lp`() {
        assertFalse(plan(BacktrackParams()).params.lpPlan.branching)
    }

    @Test
    fun `an unannotated model branches on the lp`() {
        assertTrue(plan(null).params.lpPlan.branching)
    }

    @Test
    fun `an explicit lp-branching param does not override a stated search`() {
        assertFalse(plan(BacktrackParams(), listOf("lp-branching=true")).params.lpPlan.branching)
    }
}
