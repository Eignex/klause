package com.eignex.klause.z3

import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import kotlin.test.Test
import kotlin.test.assertTrue

class Z3NativeFloatTest {

    /**
     * Z3 should consume the [com.eignex.klause.solver.FloatMetadata] sidecar produced by
     * the schema and solve over reals natively. We verify the metadata is present and
     * Z3 returns a feasible solution; precision-vs-bucketed details aren't tested
     * because the recovered rate goes back through the schema's bucket decoder.
     */
    @Test
    fun `z3 uses native reals when float metadata is present`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 5)
            val c by constraint { rate ge 0.4 }
        }
        val schema = S()
        val compiled = schema.compile()
        assertTrue(compiled.problem.floatMetadata != null)
        assertTrue((compiled.problem.floatMetadata?.numFloatVars ?: 0) == 1)

        val solver = Z3Solver(compiled.problem)
        val sample = solver.sample(Z3Params()).assignment
        assertTrue(sample != null, "Z3 should find a feasible rate ≥ 0.4")
        val rate = compiled.decode(schema.rate, sample!!)
        // Decoded via the bucket grid (5 buckets across [0,1] → step 0.25). Any real
        // value Z3 picked at or above 0.4 rounds to bucket index 2 (rate=0.5) or higher.
        assertTrue(rate >= 0.5 - 1e-9, "rate=$rate should map to a bucket ≥ 0.5")
    }
}
