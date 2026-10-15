package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.materializeKey

/** Exact real array selection with an integer index and ordered constant or real-variable entries. */
class RealElement private constructor(
    /** Integer index column. */
    val idx: Int,
    /** Real result column. */
    val result: Int,
    /** Finite binary64 constants, empty for variable entries. */
    val constants: DoubleArray,
    /** Ordered real column ids, empty for constant entries. */
    val entries: IntArray,
    /** Whether entries name real columns. */
    val arrIsVars: Boolean,
    /** Source index assigned to the first entry. */
    val indexOffset: Int,
) : Factor {
    /** Select from exact binary64 constants. */
    constructor(idx: Int, result: Int, arr: DoubleArray, indexOffset: Int = 1) :
        this(idx, result, arr, intArrayOf(), false, indexOffset)

    /** Select from real columns. */
    constructor(idx: Int, result: Int, arr: IntArray, indexOffset: Int = 1) :
        this(idx, result, doubleArrayOf(), arr, true, indexOffset)

    init {
        require(idx >= 0 && result >= 0 && entries.all { it >= 0 })
        require(constants.all { it.isFinite() })
    }

    /** Number of ordered entries. */
    val size: Int get() = if (arrIsVars) entries.size else constants.size

    override val variables: VarList = MixedVars(boundInts = intArrayOf(idx),
        reals = (intArrayOf(result) + entries).distinct().toIntArray())

    internal fun position(index: Long): Int? {
        val first = indexOffset.toLong()
        if (index < first || index > first + size - 1) return null
        return (index - first).toInt()
    }

    override fun remap(mapping: VarRemap): Factor = if (arrIsVars) {
        RealElement(mapping.int(idx), mapping.real(result), IntArray(size) { mapping.real(entries[it]) }, indexOffset)
    } else RealElement(mapping.int(idx), mapping.real(result), constants, indexOffset)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_ELEMENT, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.intVar(idx)
        sink.realVar(result)
        sink.long(indexOffset.toLong())
        sink.long(if (arrIsVars) 1L else 0L)
        sink.long(size.toLong())
        if (arrIsVars) for (entry in entries) sink.realVar(entry)
        else for (value in constants) sink.long(value.toRawBits())
    }
}
