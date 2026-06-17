package com.eignex.klause.portfolio

import com.eignex.klause.solver.localsearch.strategy.SourceDrivenStrategy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the LS recipe cross-product generator (#721): `all()` enumerates `sources × scoring ×
 * acceptance × restart` with unique labels, every recipe assembles a worker config, and `sample()`
 * is a deterministic distinct subset.
 */
class RecipeSpaceTest {

    @Test
    fun `all enumerates the full cross-product with unique labels`() {
        val space = RecipeSpace()
        val all = space.all()
        assertEquals(
            space.sources.size * space.scorings.size * space.acceptances.size * space.restarts.size,
            all.size,
            "all() must be the full a×b×c×d cross-product",
        )
        assertEquals(space.size, all.size)
        assertEquals(all.size, all.map { it.label }.toSet().size, "recipe labels must be unique")
    }

    @Test
    fun `every recipe assembles a fresh worker config`() {
        for (recipe in RecipeSpace().all()) {
            val wc = recipe.toWorkerConfig()
            assertTrue(wc.label.startsWith("recipe/"), "worker label should be namespaced, got ${wc.label}")
            assertTrue(wc.strategy is SourceDrivenStrategy, "a recipe builds a SourceDrivenStrategy")
            // Fresh instances each call (no shared stateful strategy/restart across workers).
            assertTrue(recipe.toWorkerConfig().strategy !== wc.strategy)
        }
    }

    @Test
    fun `sample is a deterministic distinct subset, clamped to the space`() {
        val space = RecipeSpace()
        val a = space.sample(20, Random(7)).map { it.label }
        val b = space.sample(20, Random(7)).map { it.label }
        assertEquals(a, b, "sample must be deterministic for a fixed seed")
        assertEquals(20, a.size)
        assertEquals(20, a.toSet().size, "sample must be distinct")
        assertEquals(space.size, space.sample(space.size + 1000, Random(1)).size, "n >= size returns the whole space")
    }
}
