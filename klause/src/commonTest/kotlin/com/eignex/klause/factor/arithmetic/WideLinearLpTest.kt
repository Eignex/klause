package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.emitLpRelaxation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WideLinearLpTest {

    private class WideRow(val cols: List<Int>, val coeffs: List<Double>, val op: LinearOp, val rhs: Double)

    /** Records the double rows a wide [Linear] emits over the root boxes in [boxes]; a variable in
     *  [openLo] / [openHi] has that endpoint marked as one the model does not state. */
    private class RecordingBuilder(
        private val boxes: Map<Int, IntDomain>,
        private val openLo: Set<Int> = emptySet(),
        private val openHi: Set<Int> = emptySet(),
    ) : RelaxationBuilder {
        val realRows = mutableListOf<WideRow>()

        // Aux columns get fresh ids above the int-var range; their bounds are recorded by id.
        private var nextAux = 1000
        val auxBounds = mutableMapOf<Int, Pair<Long, Long>>()

        override fun intColumn(intVar: Int): Int = intVar
        override fun rootDomain(intVar: Int): IntDomain = boxes.getValue(intVar)
        override fun statesLowerBound(intVar: Int): Boolean = intVar !in openLo
        override fun statesUpperBound(intVar: Int): Boolean = intVar !in openHi
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
        row.emitLpRelaxation(b)
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
        row.emitLpRelaxation(b)
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
    fun `a wide row over a column the model leaves open emits no row`() {
        // The split caps the column at its root box, so an invented endpoint restricts the model — and a
        // column with no stated side of zero has no rounding direction either. An outer relaxation has no
        // weaker form, so the row stays CP-only.
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.LE, w)
        listOf(
            RecordingBuilder(mapOf(0 to IntDomain(-5, 5)), openHi = setOf(0)),
            RecordingBuilder(mapOf(0 to IntDomain(-5, 5)), openLo = setOf(0)),
            RecordingBuilder(mapOf(0 to IntDomain(0, 10)), openLo = setOf(0)),
        ).forEach { b ->
            row.emitLpRelaxation(b)
            assertEquals(0, b.realRows.size, "an unstated endpoint leaves the row to propagation")
            assertTrue(b.auxBounds.isEmpty(), "and allocates no split column")
        }
    }

    @Test
    fun `a column the model bounds below stays unsplit and keeps its rounding direction`() {
        // Open above but stated non-negative below: the sign is known, so no split is needed and the
        // coefficient still rounds down on the `≤` side.
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.LE, w)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)), openHi = setOf(0))
        row.emitLpRelaxation(b)
        assertEquals(1, b.realRows.size)
        assertTrue(b.auxBounds.isEmpty(), "a sign-known column needs no split")
        assertTrue(BigInteger.tryFromDouble(b.realRows[0].coeffs[0], exactRequired = false) <= w, "coeff rounded down")
    }

    @Test
    fun `a coefficient past the Double range emits no relaxation row instead of an infinite one`() {
        // 2^2000 has no finite Double to round outward to, so the row stays CP-only; rounding it anyway
        // would put an infinity in the LP.
        val huge = BigInteger.ONE.shl(2000)
        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(0, b.realRows.size, "no row is emitted for values the LP cannot represent")
    }

    @Test
    fun `a coefficient in the top Double exponent band still emits a row when it is finite`() {
        // 2^1023 shares its bit length with values that overflow, so the finiteness test cannot stop at
        // the exponent — this one converts and must be relaxed like any other wide coefficient.
        val big = BigInteger.ONE.shl(1023)
        val row = Linear(intArrayOf(0), arrayOf(big), LinearOp.LE, big)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(1, b.realRows.size)
        assertTrue(b.realRows[0].coeffs.all { it.isFinite() }, "the emitted coefficients are finite")
    }

    @Test
    fun `a coefficient in the top Double exponent band emits no row when it rounds to infinity`() {
        // 2^1024 − 2^969 is past the largest finite Double 2^1024 − 2^971 yet has the same bit length as
        // it, so only the conversion separates the two.
        val overflowing = BigInteger.ONE.shl(1024) - BigInteger.ONE.shl(969)
        val row = Linear(intArrayOf(0), arrayOf(overflowing), LinearOp.LE, overflowing)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(0, b.realRows.size, "no row is emitted for values the LP cannot represent")
    }

    @Test
    fun `a wide equality emits a bracketing pair of outer rows`() {
        val row = Linear(intArrayOf(0), arrayOf(w), LinearOp.EQ, w)
        val b = RecordingBuilder(mapOf(0 to IntDomain(0, 10)))
        row.emitLpRelaxation(b)
        assertEquals(2, b.realRows.size)
        assertEquals(setOf(LinearOp.LE, LinearOp.GE), b.realRows.map { it.op }.toSet())
    }
}
