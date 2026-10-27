package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.movesource.MoveSourceCatalog
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.editing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineParamsTest {

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
