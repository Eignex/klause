package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How `--param` resolves the backtrack arm pool: which arms run, and how each is built.
 *
 * The two are independent — `bt-arm` chooses the arms, an override edits them — and a benchmarker
 * needs both at once to measure one knob on one arm rather than on a pool that dilutes it.
 */
class BacktrackPoolResolutionTest {

    private fun pool(vararg params: String) = resolveBtRecipes(EngineParams(params.toList()), Kind.COP)

    private val anArm: String get() = BacktrackCatalog.labels(Kind.COP).first()

    @Test
    fun `no arm and no override leaves the curated pool alone`() {
        assertNull(pool(), "a null pool is what tells the caller to use the catalog as-is")
    }

    @Test
    fun `pinning one arm resolves a pool of one`() {
        assertEquals(1, pool("bt-arm=$anArm")?.size)
    }

    @Test
    fun `an override alone edits every curated arm`() {
        val edited = pool("lp-branching=false")

        assertEquals(BacktrackCatalog.labels(Kind.COP).size, edited?.size)
    }

    @Test
    fun `a pinned arm accepts an override rather than refusing it`() {
        val resolved = pool("bt-arm=$anArm", "lp-branching=false")

        assertEquals(1, resolved?.size, "pinning chooses the arm; the override only changes how it is built")
        val recipe = resolved!!.single()()
        assertEquals(anArm, recipe.label, "an edit preserves the arm's label for telemetry")
        assertTrue(!recipe.build(1L, null).lpPlan.branching)
    }
}
