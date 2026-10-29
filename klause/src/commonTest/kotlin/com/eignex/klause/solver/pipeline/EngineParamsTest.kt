package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.ObjectiveGuidance
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.SourceProgressReward
import com.eignex.klause.portfolio.editing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineParamsTest {
    @Test
    fun `source progress rewards should be an independent opt in`() {
        val ordinary = scenario(cores = 1)
        val source = scenario(cores = 1, params = listOf("portfolio-reward=source"))

        assertNull(ordinary.rewardPolicy)
        assertSame(SourceProgressReward, source.rewardPolicy)
        assertEquals(ordinary, source.copy(rewardPolicy = null))
    }

    @Test
    fun `unknown portfolio reward policies should fail before worker creation`() {
        assertFailsWith<PipelineConfigException> {
            scenario(cores = 1, params = listOf("portfolio-reward=unknown"))
        }
    }

    @Test
    fun `learned memory controls should reach fixed and portfolio workers without enabling defaults`() {
        val base = com.eignex.klause.backtrack.BacktrackParams()
        val settings = listOf(
            "learned-memory-bytes=4096", "learned-memory-work=1234", "learned-memory-scratch-bytes=789",
        )

        val fixed = applyFixedBacktrackParams(base, EngineParams(settings), allowSelectors = true)
        val worker = applyBacktrackParams(base, EngineParams(settings))

        assertEquals(null, base.learnedMemoryBytes)
        for (configured in listOf(fixed, worker)) {
            assertEquals(4096L, configured.learnedMemoryBytes)
            assertEquals(1234L, configured.learnedMemoryWork)
            assertEquals(789L, configured.learnedMemoryScratchBytes)
        }
    }

    @Test
    fun `companion timing controls should remain separate from admission`() {
        val selected = scenario(1, listOf("companion-after-incumbent=true", "companion-active-ms=7000",
            "portfolio-progress=true"))

        assertTrue(selected.companionAfterIncumbent)
        assertEquals(7_000L, selected.companionActiveMillis)
        assertTrue(selected.reportPortfolioProgress)
        assertEquals(false, selected.companionIntegers)
    }

    @Test
    fun `companion lifetime should require a bounded positive value`() {
        for (value in listOf("0", "-1", "60001")) {
            assertFailsWith<PipelineConfigException> { scenario(1, listOf("companion-active-ms=$value")) }
        }
    }

    @Test
    fun `companion trial should retain an explicitly selected default arm count`() {
        val selected = scenario(1, listOf("arms=6", "companion-integers=true"))

        assertTrue(selected.explicitArms)
        assertTrue(selected.companionIntegers)
        assertEquals(PortfolioScenario.DEFAULT_ARMS, selected.arms)
    }

    @Test
    fun `root inprocessing controls should remain opt in and reach backtrack workers`() {
        val base = com.eignex.klause.backtrack.BacktrackParams()
        assertEquals(false, base.rootSimplification)
        assertEquals(0, base.rootProbeBatch)
        val configured = applyBacktrackParams(base, EngineParams(listOf(
            "root-simplification=true", "root-simplify-batch=7", "root-probe-batch=3", "root-probe-work=256",
            "root-rebuild-capture-visits=1024",
        )))
        assertEquals(true, configured.rootSimplification)
        assertEquals(7, configured.rootSimplifyBatch)
        assertEquals(1024, configured.rootRebuildCaptureVisits)
        assertEquals(3, configured.rootProbeBatch)
        assertEquals(256L, configured.rootProbeWork)
    }

    @Test
    fun `inprocessing spending and trigger controls should reach backtrack workers`() {
        val configured = applyBacktrackParams(com.eignex.klause.backtrack.BacktrackParams(), EngineParams(listOf(
            "inprocessing-work=100", "inprocessing-root-work=11", "inprocessing-probe-work=12",
            "inprocessing-subsume-work=13", "inprocessing-vivify-work=14", "inprocessing-interval=15",
            "inprocessing-growth=16", "inprocessing-cooldown=17",
        )))

        assertEquals(listOf(100L, 11L, 12L, 13L, 14L, 15L, 17L), listOf(configured.inprocessingWork,
            configured.inprocessingRootWork, configured.inprocessingProbeWork, configured.inprocessingSubsumeWork,
            configured.inprocessingVivifyWork, configured.inprocessingInterval, configured.inprocessingCooldown))
        assertEquals(16, configured.inprocessingGrowth)
    }

    @Test
    fun `propagation attribution and historical table refinement remain separate opt in controls`() {
        val base = com.eignex.klause.backtrack.BacktrackParams()
        val attributed = applyBacktrackParams(base, EngineParams(listOf("propagation-attribution=true")))
        val refined = applyBacktrackParams(base, EngineParams(listOf("historical-table-reasons=true")))

        assertTrue(!base.propagationAttribution && !base.historicalTableReasons)
        assertTrue(attributed.propagationAttribution && !attributed.historicalTableReasons)
        assertTrue(refined.historicalTableReasons && !refined.propagationAttribution)
    }

    @Test
    fun `cost aware propagation should be independent from explanation controls`() {
        val base = com.eignex.klause.backtrack.BacktrackParams()

        val configured = applyBacktrackParams(base, EngineParams(listOf("cost-aware-propagation=true")))

        assertTrue(!base.costAwarePropagation)
        assertTrue(configured.costAwarePropagation)
        assertTrue(!configured.propagationAttribution && !configured.historicalTableReasons)
    }

    @Test
    fun `directional lp gains remain opt in and survive fixed backtrack overrides`() {
        val base = com.eignex.klause.backtrack.BacktrackParams()
        assertTrue(!base.lpDirectionalGains)
        assertTrue(applyBacktrackParams(base, EngineParams(listOf("lp-directional-gains=true"))).lpDirectionalGains)
    }

    @Test
    fun `early objective core admission should reject parallel portfolios`() {
        assertFailsWith<PipelineConfigException> { scenario(2, listOf("core-early=true")) }
    }

    @Test
    fun `objective guidance must not create an automatic recipe pool`() {
        assertFailsWith<PipelineConfigException> {
            resolveLocalSearchRecipes(EngineParams(listOf("objective-guidance=geometric")))
        }
    }

    @Test
    fun `explicit objective guidance expands one recipe into fresh composition slots`() {
        val resolved = resolveLocalSearchRecipes(EngineParams(listOf(
            "arm=cbls-plateau/ils-basin", "objective-guidance=functional,geometric",
        )))

        val recipes = resolved.pool!!.map { it() }

        assertEquals(listOf(ObjectiveGuidance.Functional, ObjectiveGuidance.Geometric),
            recipes.map { it.objectiveGuidance })
        assertEquals(listOf("cbls-plateau/ils-basin/functional", "cbls-plateau/ils-basin/geometric"),
            recipes.map { it.label })
        assertTrue(recipes[0].strategy !== recipes[1].strategy)
    }

    @Test
    fun `invalid objective guidance is rejected before worker creation`() {
        assertFailsWith<PipelineConfigException> {
            resolveLocalSearchRecipes(EngineParams(listOf("objective-guidance=geometirc")))
        }
    }


    private fun scenario(cores: Int, params: List<String> = emptyList(), mix: EngineMix = EngineMix.MIXED) =
        buildPortfolioScenario(
            EngineParams(params),
            fallbackSeed = null,
            cores = cores,
            kind = Kind.COP,
            defaultEngine = mix,
            defaultArms = autoArms(cores),
        )

    private fun pool(vararg params: String) = resolveBtRecipes(EngineParams(params.toList()), Kind.COP)

    private val anArm: String get() = BacktrackCatalog.labels(Kind.COP).first()

    @Test
    fun `the arm pool should scale upward with the core count`() {
        assertEquals(PortfolioScenario.DEFAULT_ARMS, scenario(cores = 1).arms)
        assertEquals(8, scenario(cores = 4).arms)
        assertEquals(16, scenario(cores = 8).arms)
    }

    @Test
    fun `the arms param should override the pool and be clamped up to the core count`() {
        assertEquals(20, scenario(cores = 4, params = listOf("arms=20")).arms)
        assertEquals(8, scenario(cores = 8, params = listOf("arms=2")).arms)
    }

    @Test
    fun `the phase retention param should set retained evidence and default to the policy value`() {
        assertEquals(Portfolio.DEFAULT_PHASE_RETENTION, scenario(cores = 1).phaseRetention)
        assertEquals(0.5, scenario(cores = 1, params = listOf("phase-retention=0.5")).phaseRetention)
    }

    @Test
    fun `ls and bt params should set both the engine mix and the arm count`() {
        val bt = scenario(cores = 1, params = listOf("ls=0", "bt=3"))
        assertEquals(EngineMix.BACKTRACK, bt.engine)
        assertEquals(3, bt.arms)
        val ls = scenario(cores = 1, params = listOf("ls=4", "bt=0"))
        assertEquals(EngineMix.LOCAL_SEARCH, ls.engine)
        assertEquals(4, ls.arms)
    }

    @Test
    fun `backtrack resolution without arm or override should leave the curated pool alone`() {
        val resolved = pool()

        assertNull(resolved.pool)
        assertNull(resolved.edit)
    }

    @Test
    fun `a pinned backtrack arm should accept an override`() {
        val resolved = pool("bt-arm=$anArm", "lp-branching=false")

        assertEquals(1, resolved.pool?.size)
        val recipe = resolved.pool!!.single()().editing(resolved.edit)
        assertEquals(anArm, recipe.label)
        assertTrue(!recipe.build(1L, null).lpPlan.branching)
    }

    @Test
    fun `a pinned local search arm should accept a sources edit`() {
        val params = EngineParams(listOf("arm=cbls-implicit/fixed", "sources=+unused-values"))

        val recipe = resolveLocalSearchRecipes(params).pool!!.single()()

        assertEquals("cbls-implicit/fixed", recipe.label)
        assertTrue(recipe.seedImplicitOnRestart)
        assertTrue(recipe.strategy.sources.any { it.source.id == MoveSourceCatalog.idOf("unused-values") })
    }
}
