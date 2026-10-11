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
    fun snapshot(): Sample = Sample.fromOwned(
        bools = BooleanArray(numBoolVars) { bits.get(it) },
        ints = ints.copyOf(),
        reals = if (numRealVars == 0) EmptyDoubleArray else reals.copyOf(),
    )

    internal fun copyInto(target: Assignment) {
        require(
            numBoolVars == target.numBoolVars && numIntVars == target.numIntVars && numRealVars == target.numRealVars,
        )
        target.bits.copyFrom(bits)
        ints.copyInto(target.ints)
        reals.copyInto(target.reals)
    }
}

/**
 * Owned immutable assignment snapshot. Constructor inputs and exported arrays are copied;
 * scalar accessors read the snapshot without allocation. [exactInts] and [exactReals] are authoritative.
 */
class Sample private constructor(
    bools: BooleanArray,
    ints: LongArray,
    reals: DoubleArray,
    exactReals: List<BigFraction>?,
    exactInts: List<BigInt>?,
    owned: Boolean,
) {
    /** Capture caller-owned coordinates, preserving exact authority when supplied. */
    constructor(
        bools: BooleanArray,
        ints: LongArray,
        reals: DoubleArray = EmptyDoubleArray,
        exactReals: List<BigFraction>? = null,
        exactInts: List<BigInt>? = null,
    ) : this(bools, ints, reals, exactReals, exactInts, owned = false)

    /** Arbitrary-precision integer coordinates, when supplied by an exact open theory. */
    val exactInts: List<BigInt>? = exactInts?.let { snapshotValues(it) }
    private val booleanValues = if (owned) bools else bools.copyOf()
    private val integerValues = if (owned) ints else ints.copyOf()

    /** Independent copy of the Boolean coordinates. */
    val bools: BooleanArray get() = booleanValues.copyOf()

    /** Finite integer coordinates. Throws when an authoritative coordinate lies outside Long. */
    val ints: LongArray get() {
        check(exactInts == null || integerValues.size == exactInts.size) {
            "an arbitrary-precision witness needs an explicit finite projection"
        }
        return integerValues.copyOf()
    }

    /** Number of Boolean coordinates. */
    val numBoolVars: Int get() = booleanValues.size

    /** Number of integer coordinates, including arbitrary-precision coordinates. */
    val numIntVars: Int get() = exactInts?.size ?: integerValues.size

    internal var witnessCertificate: EvidenceCertificate? = null

    /** Certified real values indexed by real variable id, when a residual LP supplied them. */
    val exactReals: List<BigFraction>? = exactReals?.let { snapshotValues(it) }
    private val approximateReals = if (owned) reals else reals.copyOf()

    /** Independent copy of the approximate real coordinates. */
    val reals: DoubleArray get() = approximateReals.copyOf()

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
    ): Sample = fromOwned(
        if (bools === booleanValues) bools else bools.copyOf(),
        if (ints === integerValues) ints else ints.copyOf(),
        if (reals === approximateReals) reals else reals.copyOf(),
        exactReals,
        exactInts,
    ).also {
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

    /** Authoritative integer coordinate, widened on demand for finite samples. */
    fun exactIntValue(id: Int): BigInt = exactInts?.get(id) ?: bigIntOf(integerValues[id])

    /** Boolean coordinate without exporting its buffer. */
    fun boolValue(id: Int): Boolean = booleanValues[id]

    /** Finite integer coordinate; a wide exact witness requires [exactIntValue]. */
    fun intValue(id: Int): Long {
        check(exactInts == null || integerValues.size == exactInts.size) {
            "an arbitrary-precision witness needs an explicit finite projection"
        }
        return integerValues[id]
    }

    internal fun copyBoolsInto(target: BooleanArray, endIndex: Int) {
        booleanValues.copyInto(target, endIndex = endIndex)
    }

    internal fun copyIntsInto(target: LongArray, endIndex: Int) {
        integerValues.copyInto(target, endIndex = endIndex)
    }

    internal companion object {
        // Fresh publication buffers have no remaining mutable owner.
        internal fun fromOwned(
            bools: BooleanArray,
            ints: LongArray,
            reals: DoubleArray = EmptyDoubleArray,
            exactReals: List<BigFraction>? = null,
            exactInts: List<BigInt>? = null,
        ): Sample = Sample(bools, ints, reals, exactReals, exactInts, owned = true)
    }
}

internal class SnapshotValues<T>(values: List<T>) : AbstractList<T>() {
    private val snapshot = values.toList()
    override val size: Int get() = snapshot.size
    override fun get(index: Int): T = snapshot[index]
}

internal fun <T> snapshotValues(values: List<T>): List<T> =
    if (values is SnapshotValues<*>) values else SnapshotValues(values)
