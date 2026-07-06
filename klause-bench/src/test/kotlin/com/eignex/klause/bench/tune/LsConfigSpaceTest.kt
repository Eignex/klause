package com.eignex.klause.bench.tune

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsConfigSpaceTest {

    @Test
    fun `sampled points gate family knobs and every family decodes to a recipe`() {
        val rng = Random(1)
        val families = mutableSetOf<String>()
        repeat(1000) {
            val a = LsConfigSpace.sample(rng)
            val fam = a["family"] as String
            families += fam
            // Conditional params are present only for their family (child-param gating).
            assertEquals(fam == "cbls", a.keys.any { it.startsWith("cbls.") }, "cbls knobs gated: $a")
            assertEquals(fam == "probsat", a.keys.any { it.startsWith("probsat.") }, "probsat knobs gated: $a")
            assertEquals(fam == "walksat", a.keys.any { it.startsWith("walksat.") }, "walksat knobs gated: $a")
            assertEquals(fam == "sa", a.keys.any { it.startsWith("sa.") }, "sa knobs gated: $a")
            // Every sampled point decodes to a fresh recipe without throwing.
            val recipe = LsConfigSpace.toRecipe(a)
            assertTrue(recipe.label.startsWith("cfg/"), recipe.label)
        }
        assertEquals(
            setOf("cbls", "probsat", "walksat", "sa", "fjump"),
            families,
            "all five families are reachable in the space",
        )
    }

    @Test
    fun `decoding is deterministic for a fixed assignment and fresh per call`() {
        val a = mapOf<String, Any>(
            "family" to "cbls",
            "restart" to "fixed",
            "cbls.augment" to "plateau",
            "cbls.noise" to 0.05,
            "cbls.tabu" to 10,
            "cbls.scoring" to "weighted",
        )
        val r1 = LsConfigSpace.toRecipe(a)
        val r2 = LsConfigSpace.toRecipe(a)
        assertEquals(r1.label, r2.label, "same assignment -> same label")
        assertTrue(r1.strategy !== r2.strategy, "fresh strategy instances (no shared mutable state)")
        assertTrue(r1.optimizeStrategy != null, "cbls uses the unified minimize path")
    }
}
