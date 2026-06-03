package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.format.OpbFormat
import com.eignex.klause.bench.runner.InProcessRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.solver.factor.PseudoBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Catalog + in-process resolution coverage. */
class CatalogTest {

    private fun ref(suite: String, name: String): ProblemRef =
        Catalog.suite(suite).problems.first { it.name == name }

    @Test
    fun `dimacs-core resolves with expected shapes and oracles`() {
        val byName = Catalog.suite("dimacs-core").problems.associateBy { it.name }
        assertEquals(3, byName.size)
        assertEquals(Expected.Unsat, byName["php4"]!!.expected)
        assertEquals(Expected.Sat, byName["random3sat-20-80"]!!.expected)

        val php4 = InProcessRunner.resolve(ref("dimacs-core", "php4")).problem
        assertEquals(20, php4.numBoolVars)
        assertEquals(45, php4.factors.size)

        val r50 = InProcessRunner.resolve(ref("dimacs-core", "random3sat-50-200")).problem
        assertEquals(50, r50.numBoolVars)
        assertEquals(200, r50.factors.size)
    }

    @Test
    fun `opb-core resolves problem and objective`() {
        val resolved = InProcessRunner.resolve(ref("opb-core", "setcover-tiny"))
        assertEquals(4, resolved.problem.numBoolVars)
        assertEquals(3, resolved.problem.factors.size)
        assertTrue(resolved.problem.factors.all { it is PseudoBoolean })

        // Objective comes from the OPB parse, surfaced through the format layer.
        val opb = OpbFormat.ingest(CorpusFetcher.resolve(ref("opb-core", "setcover-tiny").source))
        val obj = assertNotNull(opb.objective)
        assertTrue(obj.toString().isNotEmpty())
    }

    @Test
    fun `schema-core resolves the campaign instance`() {
        val p = InProcessRunner.resolve(ref("schema-core", "campaign")).problem
        assertTrue(p.numBoolVars >= 4)
        assertEquals(1, p.numIntVars)
        assertTrue(p.factors.isNotEmpty())
    }

    @Test
    fun `flatzinc-core resolves all bundled instances`() {
        for (name in listOf("cardinality", "permutation4", "small-linear")) {
            val p = InProcessRunner.resolve(ref("flatzinc-core", name)).problem
            assertTrue(p.factors.isNotEmpty(), "$name should have factors")
        }
    }

    @Test
    fun `handwritten-core problems all build in-code`() {
        val hw = Catalog.suite("handwritten-core").problems
        assertTrue(hw.size >= 13)
        for (ref in hw) {
            assertEquals(Format.IN_CODE, ref.format)
            assertTrue(InProcessRunner.supports(ref))
            InProcessRunner.resolve(ref) // must not throw
        }
    }
}
