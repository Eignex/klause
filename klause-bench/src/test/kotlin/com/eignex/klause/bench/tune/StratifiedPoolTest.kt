package com.eignex.klause.bench.tune

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StratifiedPoolTest {
    private fun ref(name: String) =
        ProblemRef(name, Format.MINIZINC, ProblemSource.Vendored("pool/$name"), Category.OPTIMIZATION, Expected.Unknown)

    private fun entry(ref: ProblemRef, elapsedMs: Long, structure: String) = ReferenceEntry(
        suite = ReferenceStore.suiteOf(ref),
        problem = ref.name,
        maximize = false,
        objective = 1.0,
        feasible = true,
        proven = true,
        elapsedMs = elapsedMs,
        solver = "cp-sat",
        budgetMs = 1000L,
        format = "minizinc",
        structure = structure,
    )

    /** Six refs whose cp-sat elapsedMs [1,2,3,4,100,200] put the quartile cuts at [2,3,4], so the tiers
     *  are S(≤2) / M(≤3) / L(≤4) / XL(>4), crossed with the structure column. */
    private fun pool(): StratifiedPool {
        val refs = listOf("a", "b", "c", "d", "e", "f").map { ref(it) }
        val times = listOf(1L, 2L, 3L, 4L, 100L, 200L)
        val structures = listOf("global", "global", "linear", "linear", "global", "linear")
        val references = refs.indices.associate { i ->
            (ReferenceStore.suiteOf(refs[i]) to refs[i].name) to entry(refs[i], times[i], structures[i])
        }
        return StratifiedPool(refs, references)
    }

    @Test
    fun `strata are (sizeTier, structure) with quartile-cut size tiers`() {
        val pool = pool()
        assertEquals("S|global", pool.stratumFor(ref("a")), "elapsedMs 1 is the smallest tier")
        assertEquals("M|linear", pool.stratumFor(ref("c")), "elapsedMs 3 is the median tier")
        assertEquals("XL|linear", pool.stratumFor(ref("f")), "elapsedMs 200 is the largest tier")
        assertEquals(5, pool.strata().size, "five distinct (tier, structure) strata over the six refs")
    }

    @Test
    fun `a sample spans distinct strata rather than clustering in one`() {
        val pool = pool()
        val drawn = pool.sampleRefs(4, Random(0))
        assertEquals(4, drawn.size, "draws four distinct refs")
        val strata = drawn.mapNotNull { pool.stratumFor(it) }.toSet()
        assertTrue(strata.size >= 2, "a batch spans multiple strata (round-robin), got $strata")
    }
}
