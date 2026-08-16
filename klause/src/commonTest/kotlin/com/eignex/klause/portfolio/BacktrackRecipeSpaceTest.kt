package com.eignex.klause.portfolio

import com.eignex.klause.lp.bounding.LpEmphasis
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the backtrack recipe cross-product generator (the backtrack analogue of [RecipeSpaceTest]):
 * `all()` enumerates `variable × value × restart × lp × obj-guided` with unique labels, every recipe
 * builds fresh params, and `sample()` is a deterministic distinct subset.
 */
class BacktrackRecipeSpaceTest {

    @Test
    fun `all enumerates the full cross-product with unique labels`() {
        val space = BacktrackRecipeSpace()
        val all = space.all()
        assertEquals(
            space.variables.size * space.values.size * space.restarts.size * space.lp.size * space.objGuided.size,
            all.size,
            "all() must be the full a×b×c×d×e cross-product",
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
            if (recipe.label.contains("/no-lp/")) assertNull(a.lpConfig, "the no-lp option must disable LP")
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
        val recipe = BacktrackRecipeSpace().all().first { it.label.contains("/lp-aggressive/") }
        assertEquals(LpEmphasis.AGGRESSIVE, recipe.build(1L).lpConfig?.emphasis)
    }

    @Test
    fun `the objective-guided axis toggles cost-based value diving`() {
        val recipes = BacktrackRecipeSpace().all()
        assertTrue(
            recipes.first { it.label.endsWith("/obj-guided") }.build(1L).objectiveGuidedValues,
            "the obj-guided option enables objective-guided value selection",
        )
        assertFalse(
            recipes.first { it.label.endsWith("/cost-agnostic") }.build(1L).objectiveGuidedValues,
            "the cost-agnostic option leaves it off",
        )
    }

    @Test
    fun `the restart axis selects each pluggable schedule`() {
        val recipes = BacktrackRecipeSpace().all()
        val adaptive = recipes.first { it.label.contains("/adaptive/") }.build(1L)
        assertTrue(adaptive.adaptiveRestart, "the adaptive option enables the LBD-driven schedule")
        assertNull(adaptive.lubyRestartBase, "the adaptive option leaves no Luby base")

        val ema = recipes.first { it.label.contains("/ema/") }.build(1L)
        assertTrue(ema.emaRestart, "the ema option enables the EMA schedule")
        assertFalse(ema.adaptiveRestart, "the ema option leaves the LBD-driven schedule off")
        assertNull(ema.lubyRestartBase, "the ema option leaves no Luby base")

        val modeSwitch = recipes.first { it.label.contains("/mode-switch/") }.build(1L)
        assertTrue(modeSwitch.modeSwitchingRestart, "the mode-switch option enables the mixing schedule")
        assertNull(modeSwitch.lubyRestartBase, "the mode-switch option leaves no Luby base")

        val noRestart = recipes.first { it.label.contains("/no-restart/") }.build(1L)
        assertFalse(noRestart.adaptiveRestart, "the no-restart option leaves adaptive off")
        assertFalse(noRestart.emaRestart, "the no-restart option leaves ema off")
        assertFalse(noRestart.modeSwitchingRestart, "the no-restart option leaves mode-switching off")
        assertNull(noRestart.lubyRestartBase, "the no-restart option leaves no Luby base")

        val luby = recipes.first { it.label.contains("/luby-256/") }.build(1L)
        assertEquals(256L, luby.lubyRestartBase, "the luby option carries its base")
        assertFalse(luby.adaptiveRestart, "the luby option leaves adaptive off")
    }
}
