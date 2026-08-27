package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Core/arm decoupling (#406): `-p N` is the core count (drives sequential-vs-parallel), the arm pool
 * auto-tunes from it, and `--param arms=N` overrides — distinct from the engine mix.
 */
class EngineParamsPortfolioScenarioTest {

    private fun plan(engine: FiniteEngine, params: List<String> = emptyList()): PortfolioPlan =
        FinitePipeline.planPortfolio(
            PortfolioPlanRequest(
                engine = engine,
                optimize = true,
                cores = 1,
                engineParams = params,
                randomSeed = null,
                defaultArms = PortfolioScenario.DEFAULT_ARMS,
                lpCeiling = LpConfig.AGGRESSIVE,
                nodeBudget = null,
                annotationArm = null,
            ),
        )

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
    fun `clause-share params override the exchange filter and default to the tuned bounds`() {
        val default = scenario(cores = 2)
        assertEquals(6, default.clauseShareMaxLbd)
        assertEquals(12, default.clauseShareMaxLen)
        val tuned = scenario(cores = 2, params = listOf("clause-share-lbd=6", "clause-share-len=12"))
        assertEquals(6, tuned.clauseShareMaxLbd)
        assertEquals(12, tuned.clauseShareMaxLen)
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

    @Test
    fun `strategy sweep resolves a pool and pins the worker count to all of it`() {
        val res = resolveLocalSearchRecipes(EngineParams(mutableListOf("strategy=sweep")))
        val pool = assertNotNull(res.pool, "sweep resolves an explicit pool rather than the curated default")
        assertTrue(pool.size > 1, "the cross-product is more than one recipe: ${pool.size}")
        assertEquals(pool.size, res.forceArms, "every recipe must get its own arm or the sweep is a prefix")
        assertTrue(pool.all { it().label.startsWith("recipe/") }, "every arm comes from the recipe space")
    }

    @Test
    fun `strategy sweep rejects edits that would contradict the whole space`() {
        for (extra in listOf("arm=fjump", "sources=all", "acceptance=metropolis")) {
            val e = runCatching { resolveLocalSearchRecipes(EngineParams(mutableListOf("strategy=sweep", extra))) }
            assertTrue(e.isFailure, "strategy=sweep with $extra must be rejected")
        }
    }

    @Test
    fun `portfolio planning resolves the execution scenario from the route and parameters`() {
        val execution = assertIs<PortfolioPlan.Execute>(plan(FiniteEngine.BACKTRACK, listOf("arms=3")))

        assertEquals(EngineMix.BACKTRACK, execution.scenario.engine)
        assertEquals(3, execution.scenario.arms)
    }

    @Test
    fun `portfolio planning preserves local search dry run without constructing workers`() {
        val dryRun = assertIs<PortfolioPlan.LocalSearchDryRun>(
            plan(FiniteEngine.LOCAL_SEARCH, listOf("dry-run-solver=on")),
        )

        assertNull(dryRun.pool, "the unchanged curated catalog is rendered by the frontend")
    }

    @Test
    fun `fixed planning resolves the fallback recipe and dry run before solver construction`() {
        val plan = FinitePipeline.planFixedBacktrack(
            FixedBacktrackPlanRequest(
                annotatedParams = null,
                engineParams = listOf("dry-run-solver=on", "luby=17"),
                randomSeed = 4L,
                cancellation = Cancellation.Never,
                nodeBudget = null,
                solveBudgetMillis = null,
                lpConfig = LpConfig.OFF,
                onEvent = null,
            ),
        )

        assertTrue(plan.dryRun)
        assertEquals(4L, plan.params.randomSeed)
        assertEquals(17L, plan.params.lubyRestartBase)
    }
}
