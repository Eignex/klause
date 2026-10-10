package com.eignex.klause.solver

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.randomValue
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import kotlin.random.Random

/**
 * Mutable mixed assignment over `numBoolVars` Boolean variables (packed into a [LongArray]),
 * `numIntVars` integer variables (a plain [LongArray]) and `numRealVars` continuous variables held
 * approximately as [Double]s. Each kind lives in its own id space; a factor that touches several names
 * them through `boolVars` / `intVars` / its real variables.
 */
class Assignment(
    /** Number of Boolean variables. */
    val numBoolVars: Int,
    /** Number of integer variables. */
    val numIntVars: Int,
    /** Number of continuous (real) variables. */
    val numRealVars: Int = 0,
) {
    private val bits: Bits = Bits(numBoolVars)
    private val ints: LongArray = LongArray(numIntVars)
    private val reals: DoubleArray = if (numRealVars == 0) EmptyDoubleArray else DoubleArray(numRealVars)

    /** Current value of Boolean variable [varId]. */
    fun boolValue(varId: Int): Boolean = bits.get(varId)

    /** Set Boolean variable [varId] to [value]. */
    fun setBool(varId: Int, value: Boolean) {
        if (value) bits.set(varId) else bits.clear(varId)
    }

    /** Flip Boolean variable [varId]'s current value. */
    fun flipBool(varId: Int) {
        if (bits.get(varId)) bits.clear(varId) else bits.set(varId)
    }

    /** Current value of integer variable [varId]. */
    fun intValue(varId: Int): Long = ints[varId]

    /** Set integer variable [varId] to [value]. */
    fun setInt(varId: Int, value: Long) {
        ints[varId] = value
    }

    /** Current approximate value of real variable [varId]. */
    fun realValue(varId: Int): Double = reals[varId]

    /** Set real variable [varId] to [value]. */
    fun setReal(varId: Int, value: Double) {
        reals[varId] = value
    }

    /** Randomize every variable uniformly within its domain. */
    fun randomize(rng: Random, intDomains: Array<IntDomain>) {
        // Direct word fill — much faster than a per-var coin flip via bits.set / clear.
        val ws = bits.words
        for (i in ws.indices) ws[i] = rng.nextLong()
        val tail = numBoolVars and 63
        if (tail != 0) ws[ws.size - 1] = ws[ws.size - 1] and ((1L shl tail) - 1L)
        for (i in 0 until numIntVars) {
            val d = intDomains[i]
            ints[i] = d.randomValue(rng)
        }
    }

    /** Capture the assignment as an immutable [Sample]. */
    fun snapshot(): Sample = Sample(
        bools = BooleanArray(numBoolVars) { bits.get(it) },
        ints = ints.copyOf(),
        reals = if (numRealVars == 0) EmptyDoubleArray else reals.copyOf(),
    )
}

/** Assignment snapshot yielded by the solver. [exactInts] and [exactReals] are authoritative when present. */
class Sample(
    /** Boolean values indexed by variable id. */
    bools: BooleanArray,
    /** Integer values indexed by variable id. */
    ints: LongArray,
    /** Values of the LP-only continuous (real) variables, indexed by real var id; empty for the
     *  integer/Boolean core. Populated at a search leaf from the residual LP solution, so a
     *  hybrid MIP/CP solution carries its continuous part. */
    reals: DoubleArray = EmptyDoubleArray,
    exactReals: List<BigFraction>? = null,
    exactInts: List<BigInt>? = null,
) {
    /** Arbitrary-precision integer coordinates, when supplied by an exact open theory. */
    val exactInts: List<BigInt>? = exactInts?.let { ExactValues(it) }
    private val booleanValues = if (this.exactInts == null) bools else bools.copyOf()
    private val integerValues = if (this.exactInts == null) ints else ints.copyOf()

    /** Boolean coordinates; exact witnesses return a copy to protect their authority. */
    val bools: BooleanArray get() = if (exactInts == null) booleanValues else booleanValues.copyOf()

    /** Finite integer coordinates. Throws when an authoritative coordinate lies outside Long. */
    val ints: LongArray get() {
        check(exactInts == null || integerValues.size == exactInts.size) {
            "an arbitrary-precision witness needs an explicit finite projection"
        }
        return if (exactInts == null) integerValues else integerValues.copyOf()
    }

    /** Number of integer coordinates, including arbitrary-precision coordinates. */
    val numIntVars: Int get() = exactInts?.size ?: integerValues.size

    internal var witnessCertificate: EvidenceCertificate? = null

    /** Certified real values indexed by real variable id, when a residual LP supplied them. */
    val exactReals: List<BigFraction>? = exactReals?.let { ExactValues(it) }
    private val approximateReals = if (this.exactReals == null) reals else reals.copyOf()

    /** Approximate real values. A certified sample returns a copy so its exact and approximate views stay aligned. */
    val reals: DoubleArray get() = if (exactReals == null) approximateReals else approximateReals.copyOf()

    /** Number of approximate real coordinates. */
    val numRealVars: Int get() = approximateReals.size

    /** Approximate real value at [id], without exporting the mutable array. */
    fun approximateRealValue(id: Int): Double = approximateReals[id]

    init {
        this.exactInts?.let { values ->
            if (values.all { it.fitsLong() }) {
                require(values.size == integerValues.size && values.indices.all {
                    values[it].toLongExact() == integerValues[it]
                }) { "exact and finite integer coordinates differ" }
            } else {
                require(integerValues.isEmpty()) { "a wide witness has no finite integer coordinates" }
            }
        }
        require(this.exactReals == null || this.exactReals.size == approximateReals.size) {
            "exact and approximate real coordinates differ"
        }
        this.exactReals?.forEachIndexed { index, exact ->
            require(exact.toDouble().toBits() == approximateReals[index].toBits()) {
                "approximate real coordinate $index differs from its exact value"
            }
        }
    }

    /** Copy this assignment; replacing a finite view discards its exact authority unless supplied. */
    fun copy(
        bools: BooleanArray = booleanValues,
        ints: LongArray = integerValues,
        reals: DoubleArray = approximateReals,
        exactReals: List<BigFraction>? = if (reals === approximateReals) this.exactReals else null,
        exactInts: List<BigInt>? = if (ints === integerValues) this.exactInts else null,
    ): Sample = Sample(bools, ints, reals, exactReals, exactInts).also {
        if (bools.contentEquals(booleanValues) && ints.contentEquals(integerValues) &&
            reals.contentEquals(approximateReals) && exactInts == this.exactInts && exactReals == this.exactReals
        ) it.witnessCertificate = witnessCertificate
    }

    /** Number of Boolean and integer values that differ from [other]. */
    fun hammingDistanceTo(other: Sample): Int {
        var d = 0
        for (i in booleanValues.indices) if (booleanValues[i] != other.booleanValues[i]) d++
        if (exactInts == null && other.exactInts == null) {
            for (i in integerValues.indices) if (integerValues[i] != other.integerValues[i]) d++
        } else {
            for (i in 0 until numIntVars) if (exactIntValue(i) != other.exactIntValue(i)) d++
        }
        return d
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Sample) return false
        return booleanValues.contentEquals(other.booleanValues) && integerValues.contentEquals(other.integerValues) &&
            exactInts == other.exactInts && approximateReals.contentEquals(other.approximateReals) &&
            exactReals == other.exactReals
    }
    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * booleanValues.contentHashCode() + integerValues.contentHashCode()) +
            (exactInts?.hashCode() ?: 0)) + approximateReals.contentHashCode()) + (exactReals?.hashCode() ?: 0)

    internal fun exactIntValue(id: Int): BigInt = exactInts?.get(id) ?: bigIntOf(integerValues[id])

    internal fun boolValue(id: Int): Boolean = booleanValues[id]
}

private class ExactValues<T>(values: List<T>) : AbstractList<T>() {
    private val snapshot = values.toList()
    override val size: Int get() = snapshot.size
    override fun get(index: Int): T = snapshot[index]
}
