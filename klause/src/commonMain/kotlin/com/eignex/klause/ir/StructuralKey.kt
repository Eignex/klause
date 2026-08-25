package com.eignex.klause.ir

/**
 * A canonical identity for a constraint up to variable identity: same factor type, same constants
 * (coefficients, bounds, polarities), and the same multiset of variables — independent of internal
 * ordering — compare equal. [equals] / [hashCode] are structural and [compareTo] is a total order, so
 * keys serve as hash-bucket keys and can be sorted into a canonical multiset. Built with `StructuralKey.of`;
 * A constraint's `structuralKey` returns `null` for an unkeyed factor.
 */
class StructuralKey internal constructor(private val kind: FactorKind, private val payload: LongArray) :
    Comparable<StructuralKey> {

    override fun equals(other: Any?): Boolean =
        this === other || (other is StructuralKey && kind == other.kind && payload.contentEquals(other.payload))

    // Immutable, so the content hash is computed once and memoised — these keys are hashed repeatedly
    // as multiset and hash-bucket keys across presolve rounds. `0` is the not-yet-computed sentinel; a
    // key that genuinely hashes to 0 simply recomputes (harmless).
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
        /**
         * Assemble a key of [kind] from the payload appended in [block]. Every variable-length segment
         * is length-prefixed, so distinct factors never collide; the builder methods choose positional
         * ([StructuralKeyBuilder.ints]) versus set ([StructuralKeyBuilder.sortedInts]) semantics
         * explicitly.
         */
        fun of(kind: FactorKind, block: StructuralKeyBuilder.() -> Unit): StructuralKey =
            StructuralKeyBuilder().apply(block).build(kind)

        /**
         * Build a standalone payload fragment (no kind) for [StructuralKeyBuilder.words] to splice in.
         * Lets a factor compute the variable-independent part of its key **once** and reuse it across
         * the many remapped copies symmetry refinement keys — the expensive constant work (e.g. a
         * table's sorted tuple set) is hoisted out of the per-round hot path.
         */
        fun words(block: StructuralKeyBuilder.() -> Unit): LongArray = StructuralKeyBuilder().apply(block).buildWords()
    }
}

/** The factor-type discriminator of a [StructuralKey]; two keys of different kinds never compare equal. */
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
}

/** Payload builder for `StructuralKey.of`. Appends scalars and length-prefixed array segments into a
 *  primitive `long` buffer — no per-word boxing, since these keys are rebuilt in symmetry refinement's
 *  per-round inner loop. */
internal class StructuralKeyBuilder(expectedWords: Int = 0) {
    // Sized from the caller's estimate where one is available. A key whose payload is a whole transition
    // table or tuple set would otherwise double its way up and then be copied once more by [build],
    // peaking at ~3x the payload it produces (MagicSquare-mdd-16_c23 died here at 3 GB).
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

    /** A positional int array (order significant): length, then elements in order. */
    fun ints(xs: IntArray) {
        append(xs.size.toLong())
        reserve(xs.size)
        for (x in xs) buf[size++] = x.toLong()
    }

    /** A positional long array (order significant): length, then elements in order. */
    fun longs(xs: LongArray) {
        append(xs.size.toLong())
        reserve(xs.size)
        for (x in xs) buf[size++] = x
    }

    /** A set-semantics int array (order insignificant): length, then elements ascending. */
    fun sortedInts(xs: IntArray) {
        append(xs.size.toLong())
        val sorted = xs.copyOf()
        sorted.sort()
        reserve(sorted.size)
        for (x in sorted) buf[size++] = x.toLong()
    }

    /** `(key, value)` pairs ordered by key ascending, where the value for the entry at original index
     *  `i` is `valueOf(i)`: length, then `key, value` per pair. */
    fun pairsByKey(keys: IntArray, valueOf: (Int) -> Long) {
        append(keys.size.toLong())
        val order = IntArray(keys.size) { it }
        sortIndicesByKey(order, keys)
        reserve(keys.size * 2)
        for (i in order) {
            buf[size++] = keys[i].toLong()
            buf[size++] = valueOf(i)
        }
    }

    /** Splice a fragment of already-built words verbatim (from [StructuralKey.words]) — the cached,
     *  variable-independent part of a factor's key. */
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

/** Above this arity the index sort switches from in-place insertion sort to the O(n log n) packed
 *  sort; below it insertion sort's lack of allocation wins. A high-arity factor (a wide linear row or
 *  knapsack) whose [StructuralKey] is rebuilt for every incident variable each WL-refinement round
 *  made the quadratic sort dominate presolve, so the crossover bounds that to `n log n`. */
private const val INDEX_SORT_INSERTION_MAX = 32

/** Sort [order] (a permutation of its own indices) by `keys[order[i]]` ascending, without boxing — the
 *  index counterpart of `indices.sortedBy { keys[it] }`. Short arrays use in-place insertion sort (no
 *  allocation); longer ones pack `(key, index)` into a `Long` (key high, index low so equal keys keep
 *  ascending-index order) and use the primitive `O(n log n)` sort. */
private fun sortIndicesByKey(order: IntArray, keys: IntArray) {
    if (order.size <= INDEX_SORT_INSERTION_MAX) {
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
        return
    }
    val packed = LongArray(
        order.size,
    ) { (keys[order[it]].toLong() shl Int.SIZE_BITS) or (order[it].toLong() and LOW_WORD) }
    packed.sort()
    for (i in order.indices) order[i] = (packed[i] and LOW_WORD).toInt()
}

/** Low 32 bits mask for unpacking the index half of a `(key, index)` packed `Long`. */
private const val LOW_WORD = 0xFFFFFFFFL
