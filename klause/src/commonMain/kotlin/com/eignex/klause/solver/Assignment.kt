package com.eignex.klause.solver

import kotlin.random.Random

/**
 * Mutable mixed assignment over `numBoolVars` Boolean variables (packed into a [LongArray]) and
 * `numIntVars` integer variables (a plain [IntArray]). Bool and int variables live in separate
 * id spaces; a factor that touches both kinds names them through `boolVars` / `intVars` arrays.
 */
class Assignment(
    /** Number of Boolean variables. */
    val numBoolVars: Int,
    /** Number of integer variables. */
    val numIntVars: Int,
) {
    /** Set Boolean variable [varId] to [value]. */
    private val bits: Bits = Bits(numBoolVars)
    private val ints: IntArray = IntArray(numIntVars)

    fun boolValue(varId: Int): Boolean = bits.get(varId)
/** Flip Boolean variable [varId]'s current value. */

    fun setBool(varId: Int, value: Boolean) {
        if (value) bits.set(varId) else bits.clear(varId)
    }
/** Current value of integer variable [varId]. */

    fun flipBool(varId: Int) {
        /** Set integer variable [varId] to [value]. */
        if (bits.get(varId)) bits.clear(varId) else bits.set(varId)
    }

    fun intValue(varId: Int): Int = ints[varId]
/** Randomise every variable within its domain. */

    fun setInt(varId: Int, value: Int) {
        ints[varId] = value
    }

    fun randomize(rng: Random, intDomains: Array<IntDomain>) {
        // Direct word fill — much faster than a per-var coin flip via bits.set / clear.
        val ws = bits.words
        for (i in ws.indices) ws[i] = rng.nextLong()
        val tail = numBoolVars and 63
        if (tail != 0) ws[ws.size - 1] = ws[ws.size - 1] and ((1L shl tail) - 1L)
        for (i in 0 until numIntVars) {
            /** Capture the current assignment as an immutable [Sample]. */
            val d = intDomains[i]
            ints[i] = d.valueAt(rng.nextInt(d.size)) // sparse-aware uniform pick
        }
    }

    fun snapshot(): Sample = Sample(
        bools = BooleanArray(numBoolVars) { bits.get(it) },
        ints = ints.copyOf(),
    )
}

/** Immutable assignment snapshot yielded by the solver. */
data class Sample(
    /** Boolean values indexed by bool var id. */
    val bools: BooleanArray,
    /** Integer values indexed by int var id. */
    val ints: IntArray,
) {

    /** Hamming distance to [other]: number of variable slots that differ. Caller must
     *  ensure same arity (same numBoolVars / numIntVars); not bounds-checked. Used by
     *  diversity post-filters on `enumerate` / `samples` across every backend. */
    fun hammingDistanceTo(other: Sample): Int {
        var d = 0
        for (i in bools.indices) if (bools[i] != other.bools[i]) d++
        for (i in ints.indices) if (ints[i] != other.ints[i]) d++
        return d
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Sample) return false
        return bools.contentEquals(other.bools) && ints.contentEquals(other.ints)
    }
    override fun hashCode(): Int = 31 * bools.contentHashCode() + ints.contentHashCode()
}
