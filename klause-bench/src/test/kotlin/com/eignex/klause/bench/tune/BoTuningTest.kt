package com.eignex.klause.bench.tune

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.formats.opb.Opb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoTuningTest {

    /** A COP instance from an OPB `min:` objective — parsed in-process (no MiniZinc), so the ask-tell
     *  loop runs fast and hermetically, no Vizier service. */
    private fun cop(name: String, opb: String): ResolvedProblem {
        val parsed = Opb.parse(opb)
        return ResolvedProblem(
            ref = ProblemRef(
                name,
                Format.OPB,
                ProblemSource.Vendored("test/$name"),
                Category.OPTIMIZATION,
                Expected.Unknown,
            ),
            problem = parsed.problem,
            objective = requireNotNull(parsed.objective) { "OPB test instance needs a min: objective" },
        )
    }

    private val instances = listOf(
        cop("opb-a", "min: 1 x1 +2 x2 +3 x3 ;\n+1 x1 +1 x2 >= 1 ;\n+1 x2 +1 x3 >= 1 ;\n"),
        cop("opb-b", "min: 3 x1 +1 x2 +1 x3 ;\n+1 x1 +1 x3 >= 1 ;\n+1 x2 +1 x3 >= 2 ;\n"),
    )

    @Test
    fun `the random-tuner BO loop yields a set-cover palette of evaluated configs`() {
        val result = BoTuning.tuneBt(instances, RandomTuner(seed = 1), trials = 4, batch = 2, budgetMs = 20, seed = 7)

        val evaluated = result.configs.keys
        assertTrue(evaluated.isNotEmpty(), "the loop evaluated at least one config")
        val palette = result.report.diverse.map { it.arm }
        assertTrue(palette.isNotEmpty(), "the greedy set-cover palette is non-empty")
        assertTrue(palette.all { it in evaluated }, "every palette arm is one of the evaluated configs")
        assertTrue(result.report.diverse.first().newlyCovered > 0, "the top palette slot wins at least one instance")
    }

    @Test
    fun `coerce rounds a Double integer param and clamps to the declared domain`() {
        // A tuner may hand cbls.tabu (an IntParam in [0,20]) back as a Double; the loop must round it.
        val coerced = LocalSearchConfigSpace.coerce(
            mapOf("family" to "cbls", "cbls.tabu" to 4.7, "cbls.noise" to 0.05),
        )
        assertEquals(5, coerced["cbls.tabu"], "Double 4.7 rounds to Int 5")
        assertEquals("cbls", coerced["family"], "categorical stays a String")
        assertTrue(coerced["cbls.noise"] is Double, "double param stays Double")
    }
}
