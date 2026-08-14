package com.eignex.klause.factor.arithmetic

import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.solver.IntDomain
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WideLinearLpTest {

    private class WideRow(val cols: List<Int>, val coeffs: List<Double>, val op: LinearOp, val rhs: Double)

    /** Records the double rows a wide [Linear] emits, and hands back the declared domain per variable. */
    private class RecordingBuilder(private val declared: Map<Int, IntDomain>) : RelaxationBuilder {
        val realRows = mutableListOf<WideRow>()

        // Aux columns get fresh ids above the int-var range; their bounds are recorded by id.
        private var nextAux = 1000
        val auxBounds = mutableMapOf<Int, Pair<Long, Long>>()

        override fun intColumn(intVar: Int): Int = intVar
        override fun declaredDomain(intVar: Int): IntDomain = declared.getValue(intVar)
        override fun auxColumn(lo: Long, hi: Long, presence: LongArray?): Int {
            val c = nextAux++
            auxBounds[c] = lo to hi
            return c
        }

        override fun realRow(
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
            strict: Boolean,
            premiseLits: IntArray,
        ) {
            realRows += WideRow(columns.toList(), coeffs.toList(), op, rhs)
        }

        // Unused by the wide Linear path under test.
        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")
        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) = error("unused")
        override fun hullEnabled(): Boolean = true
        override fun boolColumn(boolVar: Int): Int = error("unused")
        override fun liveDomain(intVar: Int): IntDomain = error("unused")
        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) =
            error("unused")
        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) = error("unused")
    }

    // 2^64 + 1 — beyond Int64 and not exactly representable as a Double, so rounding direction matters.
    private val w = BigInteger.parseString("18446744073709551617")

    @Test
    fun `a wide at-most row over a nonnegative variable is an outward relaxation`() {
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.LE, w)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.linearize(b, factorId = 0)
        assertEquals(1, b.realRows.size)
        val r = b.realRows[0]
        assertEquals(LinearOp.LE, r.op)
        // Weakened: a nonnegative variable's coefficient rounds down (<= the true value), the bound up.
        assertTrue(BigInteger.tryFromDouble(r.coeffs[0], exactRequired = false) <= w, "coeff rounded down")
        assertTrue(BigInteger.tryFromDouble(r.rhs, exactRequired = false) >= w, "bound rounded up")
    }

    @Test
    fun `a wide row over a sign-straddling variable splits into nonnegative parts`() {
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.LE, w)
        val b = RecordingBuilder(mapOf(0 to IntDomain(-5, 5)))
        row.linearize(b, factorId = 0)
        // A link row x = x⁺ − x⁻ and one outer ≤ row over the two nonnegative split columns.
        assertEquals(2, b.realRows.size)
        val link = b.realRows.first { it.op == LinearOp.EQ }
        assertEquals(listOf(1.0, -1.0, 1.0), link.coeffs, "x − x⁺ + x⁻ = 0")
        assertEquals(0.0, link.rhs)
        // Split columns are nonnegative with bounds [0, max] and [0, −min].
        assertEquals(setOf(0L to 5L), b.auxBounds.values.toSet())
        val outer = b.realRows.first { it.op == LinearOp.LE }
        assertEquals(2, outer.cols.size, "the straddling term becomes two nonnegative terms")
        // x⁺ coefficient rounds down (≤ w); x⁻ coefficient is −w rounded down (so its negation ≥ w).
        assertTrue(BigInteger.tryFromDouble(outer.coeffs[0], exactRequired = false) <= w, "x⁺ coeff rounded down")
        assertTrue(BigInteger.tryFromDouble(-outer.coeffs[1], exactRequired = false) >= w, "x⁻ coeff rounded up")
        assertTrue(BigInteger.tryFromDouble(outer.rhs, exactRequired = false) >= w, "bound rounded up")
    }

    @Test
    fun `a coefficient past the Double range emits no relaxation row instead of an infinite one`() {
        // 2^2000 has no finite Double to round outward to, so the row stays CP-only; rounding it anyway
        // would put an infinity in the LP.
        val huge = BigInteger.ONE.shl(2000)
        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.linearize(b, factorId = 0)
        assertEquals(0, b.realRows.size, "no row is emitted for values the LP cannot represent")
    }

    @Test
    fun `a wide equality emits a bracketing pair of outer rows`() {
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, w)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.linearize(b, factorId = 0)
        assertEquals(2, b.realRows.size)
        assertEquals(setOf(LinearOp.LE, LinearOp.GE), b.realRows.map { it.op }.toSet())
    }
}
