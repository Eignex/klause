package com.eignex.klause.compile

import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.ge
import com.eignex.klause.schema.gt
import com.eignex.klause.schema.le
import com.eignex.klause.schema.lt
import com.eignex.klause.schema.minus
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import com.eignex.klause.schema.unaryMinus
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloatArithmeticTest {

    @Test
    fun `shifted float comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

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
    fun `scaled float comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

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
    fun `strict less-than excludes the exact boundary bucket`() {
        // #83: 0.5 is exactly bucket 10 (21 buckets over [0,1], step 0.05). `rate < 0.5` must
        // exclude it; before the strict-lowering fix LT was solved as LE and 0.5 leaked in.
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { rate lt 0.5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 11)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate < 0.5 - 1e-9, "rate=$rate admitted on/over the strict boundary of rate < 0.5")
        }
    }

    @Test
    fun `strict greater-than excludes the exact boundary bucket`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { rate gt 0.5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 12)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate > 0.5 + 1e-9, "rate=$rate admitted on/under the strict boundary of rate > 0.5")
        }
    }

    @Test
    fun `strict less-than admits a bucket below a non-integral scaled bound`() {
        // #83: 0.5 (bucket 10) satisfies 0.5 < 0.5000005, but a non-integral scaled bound
        // (500000.5) lowered as floor(B)-1 wrongly excludes it. ceil(B)-1 keeps it.
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { rate lt 0.5000005 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate < 0.5000005, "rate=$rate admitted over the strict bound rate < 0.5000005")
        }
        assertTrue(
            samples.any { compiled.decode(schema.rate, it) >= 0.5 - 1e-9 },
            "bucket 0.5 satisfies 0.5 < 0.5000005 but was excluded by the strict lowering",
        )
    }

    @Test
    fun `negative coefficient flips comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { -rate le -0.4 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 3)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        // Tolerance reflects backend-bucketing precision: integer-scaled coefficients can
        // shift the boundary by a fraction of a bucket step (default 1/1023 ≈ 1e-3).
        for (s in samples) {
            val rate = compiled.decode(schema.rate, s)
            assertTrue(rate >= 0.4 - 2e-3, "rate=$rate violated -rate ≤ -0.4 beyond bucketing tolerance")
        }
    }

    @Test
    fun `same handle expression vs expression`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

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
    fun `threshold above max is unsat at solve time`() {
        // `rate ge 5.0` with rate ∈ [0, 1] is unsatisfiable. The old schema-level
        // bucketing path caught this at compile-time; the new native-float path
        // leaves it for the solver / propagator to surface.
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0)
            val c by constraint { rate ge 5.0 }
        }
        val compiled = S().compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.samples(LocalSearchParams(maxFlips = 1_000, randomSeed = 1)).take(1).toList()
        assertTrue(samples.isEmpty(), "rate ge 5.0 on rate∈[0,1] should yield no samples")
    }

    @Test
    fun `tautological float constraint emits a factor that the solver trivially satisfies`() {
        // `rate le 5.0` with rate ∈ [0, 1] is trivially true. The old schema-level
        // path emitted zero factors; the new native-float path emits one FloatLinear
        // which the solver simply finds satisfied.
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0)
            val c by constraint { rate le 5.0 }
        }
        val compiled = S().compile()
        // One FloatLinear (rate ≤ 5.0) — always true.
        assertEquals(1, compiled.problem.factors.size)
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.samples(LocalSearchParams(maxFlips = 1_000, randomSeed = 1)).take(1).toList()
        assertTrue(samples.isNotEmpty(), "tautology should be satisfied by any assignment")
    }

    @Test
    fun `cross handle sum compares across distinct floats`() {
        class S : VariableSchema() {
            val a by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val b by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val c by constraint { (a + b) le 1.0 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 5)).take(50).toList()
        assertTrue(samples.isNotEmpty())

        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            assertTrue(av + bv <= 1.0 + 0.1 + 1e-9, "a=$av b=$bv violated a+b ≤ 1.0")
        }
    }

    @Test
    fun `cross handle subtraction enforces at least difference`() {
        class S : VariableSchema() {
            val a by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val b by floatVar(min = 0.0, max = 1.0, buckets = 11)
            val c by constraint { (a - b) ge 0.5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 6)).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            assertTrue(av - bv >= 0.5 - 0.1 - 1e-9, "a=$av b=$bv violated a-b ≥ 0.5")
        }
    }

    @Test
    fun `mixed handle scaling compares correctly`() {
        class S : VariableSchema() {

            val a by floatVar(min = 0.0, max = 2.0, buckets = 21)
            val b by floatVar(min = -1.0, max = 1.0, buckets = 11)
            val c by constraint { (2 * a + 3 * b) le 5.0 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(compiled.problem)
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(50).toList()
        assertTrue(samples.isNotEmpty())

        for (s in samples) {
            val av = compiled.decode(schema.a, s)
            val bv = compiled.decode(schema.b, s)
            assertTrue(
                2 * av + 3 * bv <= 5.0 + 0.8 + 1e-9,
                "a=$av b=$bv violated 2a + 3b ≤ 5.0",
            )
        }
    }
}
