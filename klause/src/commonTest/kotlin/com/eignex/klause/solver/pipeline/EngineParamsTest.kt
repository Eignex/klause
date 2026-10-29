package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.ObjectiveGuidance
import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.editing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineParamsTest {
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
        )))
        assertEquals(true, configured.rootSimplification)
        assertEquals(7, configured.rootSimplifyBatch)
        assertEquals(3, configured.rootProbeBatch)
        assertEquals(256L, configured.rootProbeWork)
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
