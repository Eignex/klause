package com.eignex.klause.schema

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntOperatorsTest {

    @Test
    fun `sum of two ints at top level emits linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val cap by constraint { x + y le 7 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear
        assertEquals(LinearOp.LE, linear.op)
        assertEquals(7L, linear.bound)
        assertEquals(2, linear.coeffs.size)
        assertTrue(linear.coeffs.all { it == 1L })
    }

    @Test
    fun `scaled terms carry coefficients`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val cap by constraint { 2 * x + 3 * y le 10 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear
        assertEquals(setOf(2L, 3L), linear.coeffs.toSet())
        assertEquals(10L, linear.bound)
    }

    @Test
    fun `subtraction and unary minus`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val y by intVar(min = 0, max = 10)
            val cap by constraint { x - y ge 2 }
        }
        val compiled = S().compile()
        val linear = compiled.problem.factors.single { it is Linear } as Linear

        // `x - y ≥ 2` is canonicalised to `≤` at construction: `−x + y ≤ −2`.
        assertEquals(LinearOp.LE, linear.op)
        assertEquals(-2L, linear.bound)
        assertEquals(setOf(1L, -1L), linear.coeffs.toSet())
    }

    @Test
    fun `single var constraint collapses to single-term Linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 100)
            val y by intVar(min = 0, max = 100)
            val cap by constraint { (x + y) - y le 10 }
        }
        val compiled = S().compile()

        val lin = compiled.problem.factors.single { it is Linear } as Linear
        assertEquals(LinearOp.LE, lin.op)
        assertEquals(10, lin.bound)
        assertEquals(1, lin.vars.size)
    }

    @Test
    fun `reified single var compare`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val budget by intVar(min = 0, max = 100)
            val capWhenFlag by constraint { flag implies (budget le 50) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear && it.vars.size == 1 })
    }

    @Test
    fun `reified linear for multi var inside implies`() {
        class S : VariableSchema() {
            val flag by boolVar()
            val x by intVar(min = 0, max = 10)
            val y by intVar(min = 0, max = 10)
            val capSum by constraint { flag implies (x + y le 5) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is ReifiedLinear })
    }

    @Test
    fun `arithmetic end to end solve satisfies predicate`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val sumCap by constraint { x + y le 6 }
            val xLeY by constraint { x le y }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 17)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv + yv <= 6, "x+y=${xv + yv}")
            assertTrue(xv <= yv, "x=$xv y=$yv")
        }
    }

    @Test
    fun `negative unary and inequality match`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val nonZero by constraint { -x le -1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 3)).take(5).toList()
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv >= 1, "Expected x>=1, got $xv")
        }
    }

    @Test
    fun `var times var emits product factor`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val cap by constraint { (x * y) le 6 }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Product })
    }

    @Test
    fun `multiplication constraint holds in samples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val pin by constraint { (x * y) eq 6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 19)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv * yv == 6L, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }

    @Test
    fun `division constraint holds in samples`() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 12)
            val d by intVar(min = 1, max = 4)

            val pin by constraint { (n / d) eq 3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 27)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(dv >= 1, "d=$dv must be positive")
            assertTrue(nv / dv == 3L, "n=$nv d=$dv n/d=${nv / dv}, expected 3")
        }
    }

    @Test
    fun `modulus constraint holds in samples`() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 10)
            val d by intVar(min = 1, max = 4)

            val pin by constraint { (n % d) eq 1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 41)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(nv % dv == 1L, "n=$nv d=$dv n%d=${nv % dv}, expected 1")
        }
    }

    /** Truncated toward zero: -7 div 2 = -3 (remainder -1), matching Kotlin's `/`. */
    @Test
    fun `negative numerator truncated division`() {
        class S : VariableSchema() {
            val n by intVar(min = -7, max = -7)
            val d by intVar(min = 1, max = 3)
            val pinQ by constraint { (n / d) eq -3 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 61)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertEquals(-7, nv)
            assertEquals(-3, nv / dv, "n=$nv d=$dv")
        }
    }

    /** Negative denominator, positive numerator: truncated and Euclidean agree here (the
     *  remainder is non-negative either way). e.g. 4 div -2 = -2 (r=0), 5 div -2 = -2 (r=1). */
    @Test
    fun `negative denominator division`() {
        class S : VariableSchema() {
            val n by intVar(min = 0, max = 6)
            val d by intVar(min = -3, max = -1)
            val pinQ by constraint { (n / d) eq -2 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 71)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        val expectedPairs = setOf(2L to -1L, 4L to -2L, 5L to -2L, 6L to -3L)
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertTrue(
                (nv to dv) in expectedPairs,
                "n=$nv d=$dv not in expected q=-2 pairs $expectedPairs",
            )
        }
    }

    /** Truncated mod takes the sign of the dividend: -10 mod 3 = -1 (matching Kotlin's `%`),
     *  not the Euclidean +2. */
    @Test
    fun `negative numerator truncated mod takes dividend sign`() {
        class S : VariableSchema() {
            val n by intVar(min = -10, max = -10)
            val d by intVar(min = 3, max = 3)
            val pinR by constraint { (n % d) eq -1 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 47)).take(5).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val nv = compiled.decode(schema.n, s)
            val dv = compiled.decode(schema.d, s)
            assertEquals(-10L, nv)
            assertEquals(3L, dv)
            assertEquals(-1L, nv % dv, "n=$nv d=$dv")
        }
    }

    @Test
    fun `lt accepts bound minus one`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x lt 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(40).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv < 5, "x=$xv violates lt 5")
        }
        val anyAtFour = samples.any { compiled.decode(schema.x, it) == 4L }
        assertTrue(anyAtFour, "no sample reached x=4 - compiler is over-tightening lt")
    }

    @Test
    fun `gt accepts bound plus one`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 10)
            val cap by constraint { x gt 5 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(40).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            assertTrue(xv > 5, "x=$xv violates gt 5")
        }
        val anyAtSix = samples.any { compiled.decode(schema.x, it) == 6L }
        assertTrue(anyAtSix, "no sample reached x=6 - compiler is over-tightening gt")
    }

    @Test
    fun `two int vars disequality compiles via reified linear`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 5)
            val y by intVar(min = 0, max = 5)
            val differ by constraint { x ne y }
        }
        val compiled = S().compile()

        assertTrue(compiled.problem.factors.any { it is ReifiedLinear })
    }

    @Test
    fun `two int vars disequality holds in samples`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 4)
            val y by intVar(min = 0, max = 4)
            val differ by constraint { x ne y }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver =
            LocalSearchSolver(compiled.problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 20_000, randomSeed = 31)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv != yv, "x=$xv y=$yv equal")
        }
    }

    @Test
    fun `signed product holds in samples`() {
        class S : VariableSchema() {
            val x by intVar(min = -3, max = 3)
            val y by intVar(min = -3, max = 3)
            val pin by constraint { (x * y) eq -6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 500),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 53)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv * yv == -6L, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }

    @Test
    fun `product converges on tighly factored target`() {
        class S : VariableSchema() {
            val x by intVar(min = 1, max = 10)
            val y by intVar(min = 1, max = 10)
            val pin by constraint { (x * y) eq 42 }
        }
        val schema = S()
        val compiled = schema.compile()
        val solver = LocalSearchSolver(
            compiled.problem.bake(),
            restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200),
        )
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 30_000, randomSeed = 4)).take(8).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val xv = compiled.decode(schema.x, s)
            val yv = compiled.decode(schema.y, s)
            assertTrue(xv * yv == 42L, "x=$xv y=$yv x*y=${xv * yv}")
        }
    }

    @Test
    fun `shifted float comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { (rate + 0.1) le 0.6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 1)),
        )
        val rate = compiled.decode(schema.rate, sat.assignment)
        assertTrue(rate <= 0.5 + 1e-9, "rate=$rate violated rate + 0.1 <= 0.6")
    }

    @Test
    fun `scaled float comparison`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { (2 * rate) ge 0.6 }
        }
        val schema = S()
        val compiled = schema.compile()
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 2)),
        )
        val rate = compiled.decode(schema.rate, sat.assignment)
        assertTrue(rate >= 0.3 - 1e-9, "rate=$rate violated 2 * rate >= 0.6")
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
        val solver = LocalSearchSolver(compiled.problem.bake())
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
        val solver = LocalSearchSolver(compiled.problem.bake())
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
        val solver = LocalSearchSolver(compiled.problem.bake())
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
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 3)),
        )
        val rate = compiled.decode(schema.rate, sat.assignment)
        assertTrue(rate >= 0.4 - 1e-9, "rate=$rate violated -rate <= -0.4")
    }

    @Test
    fun `same handle expression vs expression`() {
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0, buckets = 21)

            val c by constraint { (2 * rate) ge (rate + 0.3) }
        }
        val schema = S()
        val compiled = schema.compile()
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 4)),
        )
        val rate = compiled.decode(schema.rate, sat.assignment)
        assertTrue(rate >= 0.3 - 1e-9, "rate=$rate violated 2*rate >= rate+0.3")
    }

    @Test
    fun `threshold above max is unsat at solve time`() {
        // `rate ge 5.0` with rate in [0, 1] is unsatisfiable. The old schema-level
        // bucketing path caught this at compile-time; the new native-float path
        // leaves it for the solver / propagator to surface.
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0)
            val c by constraint { rate ge 5.0 }
        }
        val compiled = S().compile()
        val solver = LocalSearchSolver(compiled.problem.bake())
        val samples = solver.samples(LocalSearchParams(maxFlips = 1_000, randomSeed = 1)).take(1).toList()
        assertTrue(samples.isEmpty(), "rate ge 5.0 on rate in [0,1] should yield no samples")
    }

    @Test
    fun `tautological float constraint emits a factor that the solver trivially satisfies`() {
        // `rate le 5.0` with rate in [0, 1] is trivially true. The old schema-level
        // path emitted zero factors; the new native-float path emits one FloatLinear
        // which the solver simply finds satisfied.
        class S : VariableSchema() {
            val rate by floatVar(min = 0.0, max = 1.0)
            val c by constraint { rate le 5.0 }
        }
        val compiled = S().compile()
        // One FloatLinear (rate <= 5.0) - always true.
        assertEquals(1, compiled.problem.factors.size)
        assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 1)),
        )
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
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 5)),
        )
        val av = compiled.decode(schema.a, sat.assignment)
        val bv = compiled.decode(schema.b, sat.assignment)
        assertTrue(av + bv <= 1.0 + 1e-9, "a=$av b=$bv violated a+b <= 1.0")
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
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 6)),
        )
        val av = compiled.decode(schema.a, sat.assignment)
        val bv = compiled.decode(schema.b, sat.assignment)
        assertTrue(av - bv >= 0.5 - 1e-9, "a=$av b=$bv violated a-b >= 0.5")
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
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams(randomSeed = 7)),
        )
        val av = compiled.decode(schema.a, sat.assignment)
        val bv = compiled.decode(schema.b, sat.assignment)
        assertTrue(
            2 * av + 3 * bv <= 5.0 + 1e-9,
            "a=$av b=$bv violated 2a + 3b <= 5.0",
        )
    }
}
