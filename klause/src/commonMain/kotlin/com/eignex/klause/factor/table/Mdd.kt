package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

/**
 * Acceptance by a layered multi-valued decision diagram with `seq.size + 1` state layers. Layer `i` of
 * [transitions] spans `layerStarts(i) until layerStarts(i+1)`. When [recordStride] is 4, each record carries a
 * weight and the weights along the accepted path sum to [cost].
 *
 * Propagation follows Cheng and Yap, "An MDD-based generalized arc consistency algorithm for positive and
 * negative table constraints and some global constraints", Constraints 15(2), 2010,
 * https://doi.org/10.1007/s10601-009-9087-y: forward and backward reachability sweeps prune values with no
 * transition between live states, and the cost variant bounds [cost] by the min/max weighted live path.
 */
class Mdd(
    /** Sequence variable ids, one per layer. */
    val seq: IntArray,
    /** Number of states in each layer (length `seq.size + 1`). */
    val numStatesPerLayer: IntArray,
    /** Prefix-sum index into [transitions] per layer. */
    val layerStarts: IntArray,
    /** Flat records `(src, symbol, dst[, weight])` of stride [recordStride]; all but the symbol fit [Int]. */
    val transitions: LongArray,
    /** Start state. */
    val initial: Int,
    /** Accepting states at the final layer. */
    val accepting: IntArray,
    /** Ints per transition record: 3 for plain MDD, 4 for cost MDD. */
    val recordStride: Int,
    /** Cost variable id, or -1 for a plain (non-cost) MDD. */
    val cost: Int = -1,
) : Factor {

    init {
        require(seq.isNotEmpty()) { "Mdd: empty seq" }
        require(numStatesPerLayer.size == seq.size + 1) { "Mdd: numStatesPerLayer must be seq.size+1" }
        require(layerStarts.size == seq.size + 1) { "Mdd: layerStarts must be seq.size+1" }
        require(recordStride == 3 || recordStride == 4) { "Mdd: stride must be 3 or 4" }
        require(transitions.size % recordStride == 0) { "Mdd: transitions length not a multiple of stride" }
        if (recordStride == 4) require(cost >= 0) { "Mdd: cost-MDD requires cost var" }
    }

    override fun remap(mapping: VarRemap): Factor = Mdd(
        mapping.ints(seq),
        numStatesPerLayer,
        layerStarts,
        transitions,
        initial,
        accepting,
        recordStride,
        if (cost >= 0) mapping.int(cost) else cost,
    )

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.MDD, structuralKeyWeight, ::buildKey)

    // The key splices the whole diagram, so the arity-derived default would badly under-size the builder.
    override val structuralKeyWeight: Int
        get() = seq.size + numStatesPerLayer.size + layerStarts.size + transitions.size + accepting.size

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.MDD, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(initial)
        sink.int(recordStride)
        sink.intVarOrSelf(cost)
        sink.constInts(numStatesPerLayer)
        sink.constInts(layerStarts)
        sink.constLongs(transitions)
        sink.constInts(accepting)
        sink.intVars(seq)
    }

    // Records carry the symbol explicitly, so any map yields a valid diagram and the verifier's key comparison
    // decides whether it is a symmetry.
    override fun remapValues(valueMap: (Long) -> Long): Factor {
        val newTransitions = transitions.copyOf()
        var p = 0
        while (p < newTransitions.size) {
            newTransitions[p + 1] = valueMap(newTransitions[p + 1])
            p += recordStride
        }
        return Mdd(seq, numStatesPerLayer, layerStarts, newTransitions, initial, accepting, recordStride, cost)
    }

    override val variables: VarList = SpanIntVars(if (cost >= 0) seq + intArrayOf(cost) else seq.copyOf())

    // Drops states that are not both reachable and co-reachable under [domains] and renumbers the survivors
    // densely, baking in once what propagation would redo for the whole solve. An emptied layer is left to the
    // propagator to refute.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "ReturnCount")
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        val records = transitions.size / recordStride
        if (records == 0 || records > MINIMIZE_RECORD_CAP || initial !in 0 until numStatesPerLayer[0]) {
            return Unchanged
        }
        val n = seq.size
        val fwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        fwd[0][initial] = true
        for (i in 0 until n) {
            val d = domains[seq[i]]
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val src = transitions[p].toInt()
                val dst = transitions[p + 2].toInt()
                if (src in fwd[i].indices && fwd[i][src] && transitions[p + 1] in d && dst in fwd[i + 1].indices) {
                    fwd[i + 1][dst] = true
                }
                p += recordStride
            }
        }
        val bwd = Array(n + 1) { BooleanArray(numStatesPerLayer[it]) }
        for (a in accepting) if (a in 0 until numStatesPerLayer[n] && fwd[n][a]) bwd[n][a] = true
        for (i in n - 1 downTo 0) {
            val d = domains[seq[i]]
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val src = transitions[p].toInt()
                val dst = transitions[p + 2].toInt()
                if (src in fwd[i].indices && fwd[i][src] && transitions[p + 1] in d &&
                    dst in bwd[i + 1].indices && bwd[i + 1][dst]
                ) {
                    bwd[i][src] = true
                }
                p += recordStride
            }
        }
        val liveId = Array(n + 1) { l -> IntArray(numStatesPerLayer[l]) { -1 } }
        val newCounts = IntArray(n + 1)
        for (l in 0..n) {
            var id = 0
            for (s in 0 until numStatesPerLayer[l]) if (fwd[l][s] && bwd[l][s]) liveId[l][s] = id++
            if (id == 0) return Unchanged
            newCounts[l] = id
        }
        if (newCounts.contentEquals(numStatesPerLayer)) return Unchanged
        val newTransitions = LongArrayList()
        val newStarts = IntArray(n + 1)
        for (i in 0 until n) {
            newStarts[i] = newTransitions.size
            val d = domains[seq[i]]
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val src = transitions[p].toInt()
                val dst = transitions[p + 2].toInt()
                if (src in liveId[i].indices && liveId[i][src] >= 0 && dst in liveId[i + 1].indices &&
                    liveId[i + 1][dst] >= 0 && transitions[p + 1] in d
                ) {
                    newTransitions.add(liveId[i][src].toLong())
                    newTransitions.add(transitions[p + 1])
                    newTransitions.add(liveId[i + 1][dst].toLong())
                    if (recordStride == 4) newTransitions.add(transitions[p + 3])
                }
                p += recordStride
            }
        }
        newStarts[n] = newTransitions.size
        val newAccepting = IntArrayList()
        for (a in accepting) {
            if (a in 0 until numStatesPerLayer[n] && fwd[n][a] && bwd[n][a]) newAccepting.add(liveId[n][a])
        }
        return Rewrite(
            listOf(
                Mdd(
                    seq,
                    newCounts,
                    newStarts,
                    newTransitions.toLongArray(),
                    liveId[0][initial],
                    newAccepting.toIntArray(),
                    recordStride,
                    cost,
                ),
            ),
        )
    }

    private companion object {
        // A giant diagram is not re-swept each presolve round.
        const val MINIMIZE_RECORD_CAP: Int = 100_000
    }
}
