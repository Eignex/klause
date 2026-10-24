package com.eignex.klause.factor.table.internals

import com.eignex.klause.factor.table.Mdd
import kotlin.concurrent.Volatile

internal class MddLsRelationIndex(
    val graph: MddTransitionIndex,
    val symbols: Array<ExtensionalSupportIndex.Column>?,
)

internal class MddLsIndex(
    private val states: IntArray,
    private val starts: IntArray,
    private val transitions: LongArray,
    private val stride: Int,
    private val maxBytes: Long = ExtensionalSupportIndex.MAX_BYTES,
) {
    private class Prepared(val index: MddLsRelationIndex?)
    @Volatile
    private var prepared: Prepared? = null

    fun get(): MddLsRelationIndex? {
        prepared?.let { return it.index }
        var bytes = transitions.size.toLong() / stride * (2 * Int.SIZE_BYTES + Long.SIZE_BYTES)
        for (layer in 0 until states.lastIndex) {
            bytes += (states[layer].toLong() + states[layer + 1] + 2) * Int.SIZE_BYTES + LAYER_ARRAY_OVERHEAD
        }
        val index = if (bytes <= maxBytes) {
            val graph = MddTransitionIndex.build(transitions, starts, states, stride)
            MddLsRelationIndex(graph, graph.symbolSupports(transitions) {})
        } else null
        prepared = Prepared(index)
        return index
    }

    private companion object {
        const val LAYER_ARRAY_OVERHEAD = 5L * 32
    }
}

// Reference identity declares shared immutable relation data; equal contents alone do not.
@Suppress("AvoidReferentialEquality")
internal class MddLsRelation(private val mdd: Mdd) {
    override fun equals(other: Any?): Boolean = other is MddLsRelation &&
        mdd.transitions === other.mdd.transitions && mdd.layerStarts === other.mdd.layerStarts &&
        mdd.numStatesPerLayer === other.mdd.numStatesPerLayer && mdd.recordStride == other.mdd.recordStride &&
        mdd.initial == other.mdd.initial && mdd.accepting === other.mdd.accepting

    override fun hashCode(): Int = mdd.transitions.hashCode() xor mdd.layerStarts.hashCode() xor
        mdd.numStatesPerLayer.hashCode() xor mdd.recordStride xor mdd.initial xor mdd.accepting.hashCode()
}
