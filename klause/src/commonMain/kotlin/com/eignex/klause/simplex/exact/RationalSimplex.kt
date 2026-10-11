package com.eignex.klause.simplex.exact

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BigInt

// Multipliers orient the equality so its RHS is below the minimum selected by the cited bounds.
// Strict rows contribute -multiplier to the infinitesimal RHS; a negative delta breaks an exact tie.
internal class BigRationalConflict(
    val rows: IntArray,
    val multipliers: List<BigFraction>,
    val bounds: List<ExactSimplexBound>,
)

internal data class ExactSimplexBound(val column: Int, val upper: Boolean)

/**
 * An arithmetic level for the exact simplex: a rational number type with exact operations. A
 * fixed-width level signals exhaustion by latching [overflowed] — its results are then void and the
 * caller escalates; the unbounded level never latches. [ofDouble] returns null for a value the
 * level cannot represent (non-finite always; out-of-range for a fixed-width level).
 */
internal interface FracOps<F> {
    val zero: F
    val one: F
    val minusOne: F
    val half: F

    fun ofLong(v: Long): F

    fun ofDouble(v: Double): F?

    fun plus(a: F, b: F): F

    fun minus(a: F, b: F): F

    fun times(a: F, b: F): F

    fun reciprocal(a: F): F

    fun signum(a: F): Int

    fun compare(a: F, b: F): Int

    fun toDouble(a: F): Double

    fun overflowed(): Boolean

    fun isZero(a: F): Boolean = signum(a) == 0
}

/** The unbounded [BigFraction] level; never overflows. */
internal object BigFracOps : FracOps<BigFraction> {
    override val zero: BigFraction = BigFraction.ZERO
    override val one: BigFraction = BigFraction.ONE
    override val minusOne: BigFraction = BigFraction.MINUS_ONE
    override val half: BigFraction = BigFraction.of(BIG_ONE, BIG_TWO)

    override fun ofLong(v: Long): BigFraction = BigFraction.ofLong(v)

    override fun ofDouble(v: Double): BigFraction? = BigFraction.ofDouble(v)

    override fun plus(a: BigFraction, b: BigFraction): BigFraction = a + b

    override fun minus(a: BigFraction, b: BigFraction): BigFraction = a - b

    override fun times(a: BigFraction, b: BigFraction): BigFraction = a * b

    override fun reciprocal(a: BigFraction): BigFraction = a.reciprocal()

    override fun signum(a: BigFraction): Int = a.signum()

    override fun compare(a: BigFraction, b: BigFraction): Int = a.compareTo(b)

    override fun toDouble(a: BigFraction): Double = a.toDouble()

    override fun overflowed(): Boolean = false

    override fun isZero(a: BigFraction): Boolean = a.isZero
}

/** Engine-neutral normalized LP input consumed by the exact feasibility simplex. */
internal interface ExactSimplexModel {
    val n: Int
    val m: Int
    val numVars: Int
    val rhs: LongArray
    val upper: LongArray
    val hasUpper: BooleanArray
    val rowStrict: BooleanArray
    val probeClampedLo: BooleanArray
    val probeClampedHi: BooleanArray
    val doubleView: ExactSimplexDoubleView?

    /**
     * Optional arbitrary-precision input view.  The ordinary LP engine provides either its compact
     * integer columns or a double view; reduction kernels use this view when a source coefficient or
     * bound does not fit either representation.  It deliberately lives at the exact-simplex boundary
     * so callers do not have to smuggle big coefficients through a floating point relaxation.
     */
    val bigView: ExactSimplexBigView?
        get() = null

    fun forEachExactColumn(j: Int, action: (row: Int, value: Long) -> Unit)

    fun loShiftD(j: Int): Double
}

/** Double-precision normalized LP input used when the model contains real data. */
internal interface ExactSimplexDoubleView {
    val colPtr: IntArray
    val rowIdx: IntArray
    val colVal: DoubleArray
    val rhs: DoubleArray
    val upper: DoubleArray
    val hasUpper: BooleanArray
}

/** Arbitrary-precision counterpart of [ExactSimplexDoubleView]. */
internal class ExactSimplexBigView(
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colVal: List<BigFraction>,
    val rhs: List<BigFraction>,
    val upper: List<BigFraction?>,
)

/** One exact `a*x <= rhs` row for the unshifted non-negative simplex form. */
internal class ExactRationalInequality(
    val columns: IntArray,
    val coefficients: List<BigFraction>,
    val rhs: BigFraction,
    val strict: Boolean = false,
) {
    init {
        require(columns.size == coefficients.size) { "exact row columns and coefficients differ in size" }
        for (index in 1 until columns.size) {
            require(columns[index - 1] < columns[index]) { "exact row columns must be strictly ascending" }
        }
    }
}

/** One source row together with the finite lower activity that makes it double-bounded. */
internal class ExactDoubleBoundedRow(val index: Int, val inequality: ExactRationalInequality, val lower: BigFraction)

/** Exact Double-Bounded Reduction split before its mixed-echelon/Hermite column transformation. */
internal sealed interface ExactDoubleBoundedSplit {
    data object Infeasible : ExactDoubleBoundedSplit

    data object Unknown : ExactDoubleBoundedSplit

    class Split(val bounded: List<ExactDoubleBoundedRow>, val unbounded: List<Int>) : ExactDoubleBoundedSplit
}
