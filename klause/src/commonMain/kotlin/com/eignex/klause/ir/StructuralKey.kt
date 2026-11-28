package com.eignex.klause.ir

import com.eignex.util.arrays.argsortByIntKey

/**
 * A canonical identity for a constraint up to variable identity: same factor type, same constants
 * (coefficients, bounds, polarities), and the same multiset of variables — independent of internal
 * ordering — compare equal. [equals] / [hashCode] are structural and [compareTo] is a total order, so
 * keys serve as hash-bucket keys and can be sorted into a canonical multiset. Built with `StructuralKey.of`.
 */
class StructuralKey internal constructor(private val kind: FactorKind, private val payload: LongArray) :
    Comparable<StructuralKey> {

    override fun equals(other: Any?): Boolean =
        this === other || (other is StructuralKey && kind == other.kind && payload.contentEquals(other.payload))

    // Memoised because keys are hashed repeatedly across presolve rounds. `0` means not yet computed; a
    // key that genuinely hashes to 0 simply recomputes.
    private var cachedHash = 0

    override fun hashCode(): Int {
        var h = cachedHash
        if (h == 0) {
            h = 31 * kind.ordinal + payload.contentHashCode()
            cachedHash = h
        }
        return h
    }

    /** A stable, injective rendering (`kind:p0,p1,…`) for diagnostics; distinct keys render distinctly. */
    override fun toString(): String = "${kind.ordinal}:" + payload.joinToString(",")

    override fun compareTo(other: StructuralKey): Int {
        if (kind != other.kind) return kind.ordinal - other.kind.ordinal
        val shared = minOf(payload.size, other.payload.size)
        for (i in 0 until shared) {
            if (payload[i] != other.payload[i]) return if (payload[i] < other.payload[i]) -1 else 1
        }
        return payload.size - other.payload.size
    }

    internal companion object {
        // Every variable-length segment is length-prefixed, so distinct factors never collide.
        fun of(kind: FactorKind, block: StructuralKeyBuilder.() -> Unit): StructuralKey =
            StructuralKeyBuilder().apply(block).build(kind)

        // A kindless fragment for StructuralKeyBuilder.words, so a factor builds the variable-independent
        // part of its key once instead of on every remapped copy symmetry refinement keys.
        fun words(block: StructuralKeyBuilder.() -> Unit): LongArray = StructuralKeyBuilder().apply(block).buildWords()
    }
}

// The factor-type discriminator of a [StructuralKey]; two keys of different kinds never compare equal.
internal enum class FactorKind {
    CLAUSE,
    CARDINALITY,
    PSEUDO_BOOLEAN,
    XOR,
    LINEAR,
    REIFIED_LINEAR,
    REIFIED_PSEUDO_BOOLEAN,
    REIFIED_CARDINALITY,
    ALL_DIFFERENT,
    GLOBAL_CARDINALITY,
    NVALUE,
    VALUE_PRECEDE,
    INVERSE,
    SORT,
    ELEMENT,
    TABLE,
    REGULAR,
    MDD,
    CIRCUIT,
    CUMULATIVE,
    DIFFN,
    DISJUNCTIVE,
    ARRAY_MIN_MAX,
    PRODUCT,
    POWER,
    REAL_PRODUCT,
    REIFIED_REAL_LINEAR,
    GAUSSIAN_XOR,
    LEX_LESS,
    INCREASING,
    SYMMETRIC_ALL_DIFFERENT,
    SYMMETRY_HANDLING,
    OBJECTIVE_BOUND,
    COMPARISON_CLAUSE,
    DIFFERENCE_SYSTEM,
    CONTINUOUS_PRODUCT,
    ABS,
    REAL_ABS,
    GRID_ABS,
    AFFINE_ABS,
    EXACT_SEARCH_ROW,
    MPS_SOURCE_INDICATOR,
    DIV_MOD,
    WIDE_DIV_MOD,
    REAL_ELEMENT,
    REAL_ARRAY_MIN_MAX,
    GRID_ARRAY_MIN_MAX,
    GRID_PRODUCT,
    GRID_POWER,
    EXACT_LINEAR,
    LINEAR_INDICATOR,
    DISTINCT,
    REAL_FINITE_DOMAIN,
    ALL_DIFFERENT_TUPLES,
    EXACT_REAL_EQUALITY,
}

// Payload builder for `StructuralKey.of`. Appends scalars and length-prefixed array segments into a
// primitive `long` buffer to avoid per-word boxing in symmetry refinement's inner loop.
internal class StructuralKeyBuilder(expectedWords: Int = 0) {
    // Caller estimates avoid growth and a final copy peaking at roughly three times a large payload.
    private var buf = LongArray(expectedWords.coerceAtLeast(INITIAL_CAPACITY))
    private var size = 0

    private fun reserve(extra: Int) {
        if (size + extra <= buf.size) return
        var capacity = buf.size * 2
        while (capacity < size + extra) capacity *= 2
        buf = buf.copyOf(capacity)
    }

    private fun append(word: Long) {
        reserve(1)
        buf[size++] = word
    }

    fun int(value: Int) = append(value.toLong())

    fun long(value: Long) = append(value)

    fun bool(value: Boolean) = append(if (value) 1L else 0L)

    fun enum(value: Enum<*>) = append(value.ordinal.toLong())

    fun ints(xs: IntArray) {
        append(xs.size.toLong())
        reserve(xs.size)
        for (x in xs) buf[size++] = x.toLong()
    }

    fun longs(xs: LongArray) {
        append(xs.size.toLong())
        reserve(xs.size)
        for (x in xs) buf[size++] = x
    }

    fun sortedInts(xs: IntArray) {
        append(xs.size.toLong())
        val sorted = xs.copyOf()
        sorted.sort()
        reserve(sorted.size)
        for (x in sorted) buf[size++] = x.toLong()
    }

    fun pairsByKey(keys: IntArray, valueOf: (Int) -> Long) {
        append(keys.size.toLong())
        val order = if (keys.size <= INDEX_SORT_INSERTION_MAX) {
            IntArray(keys.size) { it }.also { sortIndicesByKey(it, keys) }
        } else {
            argsortByIntKey(keys.size) { keys[it] }
        }
        reserve(keys.size * 2)
        for (i in order) {
            buf[size++] = keys[i].toLong()
            buf[size++] = valueOf(i)
        }
    }

    fun words(fragment: LongArray) {
        reserve(fragment.size)
        fragment.copyInto(buf, size)
        size += fragment.size
    }

    // An exactly-filled buffer is handed over rather than duplicated: on a payload that is a whole tuple
    // set or transition table, that copy is the difference between one and two resident copies.
    fun build(kind: FactorKind): StructuralKey = StructuralKey(kind, exactWords())

    fun buildWords(): LongArray = exactWords()

    private fun exactWords(): LongArray = if (size == buf.size) buf else buf.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}

// Above this arity the index sort switches from allocation-free insertion sort to the packed sort: a
// high-arity factor's key is rebuilt for every incident variable each refinement round, where a quadratic
// sort would dominate presolve.
private const val INDEX_SORT_INSERTION_MAX = 32

private fun sortIndicesByKey(order: IntArray, keys: IntArray) {
    for (i in 1 until order.size) {
        val cur = order[i]
        val key = keys[cur]
        var j = i - 1
        while (j >= 0 && keys[order[j]] > key) {
            order[j + 1] = order[j]
            j--
        }
        order[j + 1] = cur
    }
}
