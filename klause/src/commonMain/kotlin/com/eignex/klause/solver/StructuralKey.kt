package com.eignex.klause.solver

/**
 * A canonical identity for a [Factor] up to variable identity: same factor type, same constants
 * (coefficients, bounds, polarities), and the same multiset of variables — independent of internal
 * ordering — compare equal. [equals] / [hashCode] are structural and [compareTo] is a total order, so
 * keys serve as hash-bucket keys and can be sorted into a canonical multiset. Built with `StructuralKey.of`;
 * [Factor.structuralKey] returns `null` for an unkeyed factor.
 */
class StructuralKey internal constructor(private val kind: FactorKind, private val payload: LongArray) :
    Comparable<StructuralKey> {

    override fun equals(other: Any?): Boolean =
        this === other || (other is StructuralKey && kind == other.kind && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * kind.ordinal + payload.contentHashCode()

    /** A stable, injective rendering (`kind:p0,p1,…`). Distinct keys render distinctly, so it is a
     *  sound canonical token for the heuristic colour-refinement signatures that compose keys. */
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
    SUBCIRCUIT,
    CUMULATIVE,
    DIFFN,
    DISJUNCTIVE,
    ARRAY_MIN_MAX,
    PRODUCT,
    GAUSSIAN_XOR,
    LEX_LESS,
    SYMMETRIC_ALL_DIFFERENT,
    SYMMETRY_HANDLING,
}

/** Payload builder for `StructuralKey.of`. Appends scalars and length-prefixed array segments. */
internal class StructuralKeyBuilder {
    private val buf = ArrayList<Long>()

    fun int(value: Int) {
        buf.add(value.toLong())
    }

    fun bool(value: Boolean) {
        buf.add(if (value) 1L else 0L)
    }

    fun enum(value: Enum<*>) {
        buf.add(value.ordinal.toLong())
    }

    /** A positional int array (order significant): length, then elements in order. */
    fun ints(xs: IntArray) {
        buf.add(xs.size.toLong())
        for (x in xs) buf.add(x.toLong())
    }

    /** A set-semantics int array (order insignificant): length, then elements ascending. */
    fun sortedInts(xs: IntArray) {
        buf.add(xs.size.toLong())
        for (x in xs.sorted()) buf.add(x.toLong())
    }

    /** `(key, value)` pairs ordered by key ascending, where the value for the entry at original index
     *  `i` is `valueOf(i)`: length, then `key, value` per pair. */
    fun pairsByKey(keys: IntArray, valueOf: (Int) -> Long) {
        buf.add(keys.size.toLong())
        for (i in keys.indices.sortedBy { keys[it] }) {
            buf.add(keys[i].toLong())
            buf.add(valueOf(i))
        }
    }

    fun build(kind: FactorKind): StructuralKey = StructuralKey(kind, buf.toLongArray())
}
