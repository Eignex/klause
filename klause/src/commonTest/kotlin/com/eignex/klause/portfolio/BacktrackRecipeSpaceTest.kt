package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.lp.LpEmphasis
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the backtrack recipe cross-product generator (the backtrack analogue of [RecipeSpaceTest]):
 * `all()` enumerates `variable × value × restart × lp` with unique labels, every recipe builds fresh
 * params, and `sample()` is a deterministic distinct subset.
 */
class BacktrackRecipeSpaceTest {

    @Test
    fun `all enumerates the full cross-product with unique labels`() {
        val space = BacktrackRecipeSpace()
        val all = space.all()
        assertEquals(
            space.variables.size * space.values.size * space.restarts.size * space.lp.size,
            all.size,
            "all() must be the full a×b×c×d cross-product",
        )
        assertEquals(space.size, all.size)
        assertEquals(all.size, all.map { it.label }.toSet().size, "recipe labels must be unique")
    }

    @Test
    fun `every recipe builds params carrying its seed and lp axis choice`() {
        for (recipe in BacktrackRecipeSpace().all()) {
            val a = recipe.build(1L)
            val b = recipe.build(2L)
            assertEquals(1L, a.randomSeed, "the recipe applies the worker seed")
            assertEquals(2L, b.randomSeed, "each build re-applies the given seed")
            // The no-lp axis option leaves the relaxation off.
            if (recipe.label.endsWith("/no-lp")) assertNull(a.lpConfig, "the no-lp option must disable LP")
        }
    }

    @Test
    fun `sample is a deterministic distinct subset clamped to the space`() {
        val space = BacktrackRecipeSpace()
        val a = space.sample(20, Random(7)).map { it.label }
        val b = space.sample(20, Random(7)).map { it.label }
        assertEquals(a, b, "sample must be deterministic for a fixed seed")
        assertEquals(20, a.size)
        assertEquals(20, a.toSet().size, "sample must be distinct")
        assertEquals(space.size, space.sample(space.size + 1000, Random(1)).size, "n >= size returns the whole space")
    }

    @Test
    fun `an lp-aggressive recipe carries the aggressive emphasis`() {
        val recipe = BacktrackRecipeSpace().all().first { it.label.endsWith("/lp-aggressive") }
        assertEquals(LpEmphasis.AGGRESSIVE, recipe.build(1L).lpConfig?.emphasis)
    }
}
