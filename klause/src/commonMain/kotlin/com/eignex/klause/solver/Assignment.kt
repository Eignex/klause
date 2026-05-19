package com.eignex.klause.solver

import kotlin.random.Random

/**
 * Mutable mixed assignment over `numBoolVars` Boolean variables (packed into a [LongArray]) and
 * `numIntVars` integer variables (a plain [IntArray]). Bool and int variables live in separate
 * id spaces; a factor that touches both kinds names them through `boolVars` / `intVars` arrays.
 */
class Assignment(
    val numBoolVars: Int,
    val numIntVars: Int,
    val numSetVars: Int = 0,
    /** Universe size per set var — needed to allocate the membership bitsets. Length
     *  must equal [numSetVars] when non-empty; empty IntArray when there are no set vars. */
    val setUniverseSizes: IntArray = IntArray(0),
) {
    private val bits: Bits = Bits(numBoolVars)
    private val ints: IntArray = IntArray(numIntVars)
    private val sets: Array<Bits> = Array(numSetVars) { Bits(setUniverseSizes[it]) }

    fun boolValue(varId: Int): Boolean = bits.get(varId)

    fun setBool(varId: Int, value: Boolean) {
        if (value) bits.set(varId) else bits.clear(varId)
    }

    fun flipBool(varId: Int) {
        if (bits.get(varId)) bits.clear(varId) else bits.set(varId)
    }

    fun intValue(varId: Int): Int = ints[varId]

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
            val d = intDomains[i]
            ints[i] = d.valueAt(rng.nextInt(d.size))   // sparse-aware uniform pick
        }
    }

    /** Element membership in the set var [setId]. */
    fun setMember(setId: Int, element: Int): Boolean = sets[setId].get(element)

    /** Add [element] to the set var [setId]. */
    fun setInclude(setId: Int, element: Int) { sets[setId].set(element) }

    /** Remove [element] from the set var [setId]. */
    fun setExclude(setId: Int, element: Int) { sets[setId].clear(element) }

    /** Read-only snapshot of the set var's current members as sorted ints. */
    fun setMembers(setId: Int): IntArray = sets[setId].toIntArray()

    fun snapshot(): Sample = Sample(
        bools = BooleanArray(numBoolVars) { bits.get(it) },
        ints = ints.copyOf(),
        sets = Array(numSetVars) { sets[it].toIntArray() },
    )
}

/**
 * Immutable assignment snapshot yielded by the solver. [sets] is empty for problems that
 * declared no set vars; for set-var problems each element is the sorted-ascending int array
 * of element slot ids currently in that set.
 */
data class Sample(
    val bools: BooleanArray,
    val ints: IntArray,
    val sets: Array<IntArray> = emptyArray(),
) {

    /** Hamming distance to [other]: number of variable slots that differ. Caller must
     *  ensure same arity (same numBoolVars / numIntVars / numSetVars and universes); not
     *  bounds-checked. Used by diversity post-filters on `enumerate` / `samples`. Set vars
     *  contribute the symmetric-difference count of element slots. */
    fun hammingDistanceTo(other: Sample): Int {
        var d = 0
        for (i in bools.indices) if (bools[i] != other.bools[i]) d++
        for (i in ints.indices) if (ints[i] != other.ints[i]) d++
        for (i in sets.indices) {
            val a = sets[i]
            val b = other.sets[i]
            // Symmetric difference over two sorted IntArrays.
            var ai = 0; var bi = 0
            while (ai < a.size && bi < b.size) {
                when {
                    a[ai] < b[bi] -> { d++; ai++ }
                    a[ai] > b[bi] -> { d++; bi++ }
                    else -> { ai++; bi++ }
                }
            }
            d += (a.size - ai) + (b.size - bi)
        }
        return d
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Sample) return false
        if (!bools.contentEquals(other.bools)) return false
        if (!ints.contentEquals(other.ints)) return false
        if (sets.size != other.sets.size) return false
        for (i in sets.indices) if (!sets[i].contentEquals(other.sets[i])) return false
        return true
    }
    override fun hashCode(): Int {
        var h = 31 * bools.contentHashCode() + ints.contentHashCode()
        for (s in sets) h = 31 * h + s.contentHashCode()
        return h
    }
}
