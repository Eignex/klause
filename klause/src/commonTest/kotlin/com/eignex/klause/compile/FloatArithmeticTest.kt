package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.times
import com.eignex.klause.solver.LocalSearchParams
import com.eignex.klause.solver.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FloatArithmeticTest {

    /** Bucket-aware lowering: literal arithmetic against a single float collapses to a
     *  single bucket-int comparison without needing the user to compute bucket indices. */
    @Test
    fun shiftedFloatComparison() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)
            // rate + 0.1 ≤ 0.6  ↔  rate ≤ 0.5
            val c by constraint { (rate + 0.1) le 0.6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 1)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate <= 0.5 + 1e-9, "rate=$rate violated rate + 0.1 ≤ 0.6")
        }
    }

    @Test
    fun scaledFloatComparison() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)
            // 2 * rate ≥ 0.6  ↔  rate ≥ 0.3
            val c by constraint { (2 * rate) ge 0.6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 2)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.3 - 1e-9, "rate=$rate violated 2 * rate ≥ 0.6")
        }
    }

    @Test
    fun negativeCoefficientFlipsComparison() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)
            // -rate ≤ -0.4  ↔  rate ≥ 0.4
            val c by constraint { -rate le -0.4 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 3)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.4 - 1e-9, "rate=$rate violated -rate ≤ -0.4")
        }
    }

    @Test
    fun sameHandleExpressionVsExpression() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)
            // 2 * rate ≥ rate + 0.3  ↔  rate ≥ 0.3
            val c by constraint { (2 * rate) ge (rate + 0.3) }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 4)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.3 - 1e-9, "rate=$rate violated 2*rate ≥ rate+0.3")
        }
    }

    @Test
    fun thresholdAboveMaxIsRejectedAtCompileTime() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)
            // rate ≥ 5.0 over [0, 1]: provably unsatisfiable, surfaced as a compile-time
            // contradiction rather than a silent never-yields behaviour at solve time.
            val c by constraint { rate ge 5.0 }
        }
        assertFails { S().compile() }
    }

    @Test
    fun thresholdAboveMaxIsTautologyAtCompileTime() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)
            // rate ≤ 5.0 → tautology; compiler drops the constraint, no factors emitted.
            val c by constraint { rate le 5.0 }
        }
        val compiled = S().compile()
        assertEquals(0, compiled.problem.factors.size, "tautological constraint should produce no factors")
    }

    @Test
    fun crossHandleArithmeticThrows() {
        // Same-source linear arithmetic works, but combining two distinct floats does not.
        assertFails {
            class S : VariableSchema() {
                val a by floatVar(min = 0.0, max = 1.0, buckets = 11)
                val b by floatVar(min = 0.0, max = 1.0, buckets = 11)
                val c by constraint { (a.toExpr() + b.toExpr()) le 1.0 }
            }
            S().compile()
        }
    }

    @Test
    fun defaultBucketCountIsApplied() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0)
            val c by constraint { rate ge 0.5 }
        }
        val compiled = S().compile()
        // Default DEFAULT_FLOAT_BUCKETS = 1024 → int domain [0, 1023] for "rate".
        val rateDomain = compiled.problem.intDomains[0]
        assertEquals(0, rateDomain.min)
        assertEquals(1023, rateDomain.max)
    }
}
