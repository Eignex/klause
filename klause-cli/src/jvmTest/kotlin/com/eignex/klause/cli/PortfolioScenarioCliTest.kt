package com.eignex.klause.cli

import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Core/arm decoupling (#406): `-p N` is the core count (drives sequential-vs-parallel), the arm pool
 * auto-tunes from it, and `--param arms=N` overrides — distinct from the engine mix.
 */
class PortfolioScenarioCliTest {

    private fun scenario(cores: Int, params: List<String> = emptyList(), mix: EngineMix = EngineMix.MIXED) =
        buildPortfolioScenario(
            EngineParams(params),
            fallbackSeed = null,
            cores = cores,
            kind = Kind.COP,
            defaultEngine = mix,
            defaultArms = autoArms(cores),
        )

    @Test
    fun `single core selects the sequential executor over a multi-arm pool`() {
        val s = scenario(cores = 1)
        assertEquals(1, s.cores, "cores == 1 ⇒ SequentialPortfolio (the free track)")
        assertEquals(PortfolioScenario.DEFAULT_ARMS, s.arms, "the single core still bandit-schedules a real pool")
    }

    @Test
    fun `arm pool auto-tunes upward with the core count`() {
        assertEquals(PortfolioScenario.DEFAULT_ARMS, scenario(cores = 1).arms, "free track gets the floor pool")
        assertEquals(8, scenario(cores = 4).arms, "scales past the floor: more arms than cores")
        assertEquals(16, scenario(cores = 8).arms, "keeps scaling with cores")
    }

    @Test
    fun `--param arms overrides the pool and is clamped up to the core count`() {
        assertEquals(20, scenario(cores = 4, params = listOf("arms=20")).arms, "explicit override wins")
        assertEquals(8, scenario(cores = 8, params = listOf("arms=2")).arms, "never fewer arms than cores")
    }

    @Test
    fun `bt-arm resolves a named backtrack recipe pool, null when unset`() {
        val pool = resolveBtRecipes(EngineParams(listOf("bt-arm=free,conflictDriven")), Kind.COP)
        assertEquals(
            listOf("free", "conflictDriven"),
            pool?.map { it().label },
            "bt-arm resolves the named arms in order",
        )
        assertNull(resolveBtRecipes(EngineParams(emptyList()), Kind.COP), "no bt-arm keeps the curated pool")
    }

    @Test
    fun `ls and bt set both the engine mix and the arm count`() {
        val bt = scenario(cores = 1, params = listOf("ls=0", "bt=3"))
        assertEquals(EngineMix.BACKTRACK, bt.engine)
        assertEquals(3, bt.arms)
        val ls = scenario(cores = 1, params = listOf("ls=4", "bt=0"))
        assertEquals(EngineMix.LOCAL_SEARCH, ls.engine)
        assertEquals(4, ls.arms)
    }
}
