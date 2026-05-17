package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.localsearch.strategy.AdaptiveWalkSat
import com.eignex.klause.solver.localsearch.strategy.AdaptiveProbSat
import com.eignex.klause.solver.localsearch.strategy.AdaptiveDdfw
import com.eignex.klause.solver.localsearch.strategy.NoiseController

import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.ast.atLeast
import com.eignex.klause.ast.atMost
import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveStrategyTest {

    private class CardinalityS : VariableSchema() {
        val a by boolVar()
        val b by boolVar()
        val c by boolVar()
        val d by boolVar()
        val e by boolVar()
        val cap by constraint { atMost(3, a, b, c, d, e) }
        val req by constraint { atLeast(2, a, b, c, d, e) }
    }

    @Test
    fun `noise controller bumps level on stall and decays on improvement`() {
        val controller = NoiseController(initial = 0.2, theta = 3, phi = 0.2)
        // Three consecutive non-improvements at the same cost trigger a bump.
        controller.observe(10)
        controller.observe(10)
        controller.observe(10)
        controller.observe(10)
        assertTrue(controller.level > 0.2, "expected bump after 3 stalls, got ${controller.level}")
        val afterBump = controller.level
        // A strict improvement decays the level back toward zero.
        controller.observe(9)
        assertTrue(controller.level < afterBump, "expected decay after improvement, got ${controller.level} vs $afterBump")
    }

    @Test
    fun `noise controller respects bounds`() {
        val controller = NoiseController(initial = 0.5, theta = 1, phi = 0.5, minLevel = 0.5, maxLevel = 0.9)
        // Stall-only trajectory: many no-improvement observations should saturate at maxLevel.
        repeat(20) { controller.observe(100) }
        assertTrue(controller.level <= 0.9, "level escaped maxLevel: ${controller.level}")
        // Improvement-only trajectory: feed strictly decreasing costs so every observation decays.
        // Subsequent equal-cost observations would re-stall; the bound check is on a single decay.
        val before = controller.level
        for (cost in 99 downTo 80) controller.observe(cost)
        assertTrue(controller.level < before, "level did not decay on strict improvements: $before -> ${controller.level}")
        assertTrue(controller.level >= 0.5, "level escaped minLevel: ${controller.level}")
    }

    @Test
    fun `adaptive walk sat finds feasible samples`() {
        val schema = CardinalityS()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            strategy = AdaptiveWalkSat(baselineNoise = 0.1, theta = 20),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 42)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "AdaptiveWalkSat produced no samples")
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(count in 2..3, "count=$count violates 2..3")
        }
    }

    @Test
    fun `adaptive prob sat finds feasible samples`() {
        val schema = CardinalityS()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            strategy = AdaptiveProbSat(baselineCb = 2.06, theta = 20),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "AdaptiveProbSat produced no samples")
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(count in 2..3, "count=$count violates 2..3")
        }
    }

    @Test
    fun `adaptive ddfw finds feasible samples`() {
        val schema = CardinalityS()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem,
            strategy = AdaptiveDdfw(baselineIncrement = 1.0, theta = 20),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(5).toList()
        assertTrue(samples.isNotEmpty(), "AdaptiveDdfw produced no samples")
        for (s in samples) {
            val count = listOf(schema.a, schema.b, schema.c, schema.d, schema.e).count { compiled.decode(it, s) }
            assertTrue(count in 2..3, "count=$count violates 2..3")
        }
    }
}
