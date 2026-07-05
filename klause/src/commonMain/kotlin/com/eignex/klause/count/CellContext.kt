package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.EmptyIntArray

/**
 * Everything needed to count / sample one family of XOR-hash cells over a fixed projection of a
 * problem: the [problem] the solver enumerates, the variable ids the hashes range over
 * ([hashDomain]), and how to project / decode an enumerated model back to the original variables.
 *
 * A purely-Boolean projection runs natively over the original problem — no transformation, full
 * native propagation. When the projection includes integer variables, the chosen variables are
 * channelled to fresh Boolean bits via [IntBitChannel] (a binary encoding, kept native — every
 * original constraint is preserved) and the hashes range over those bits; counting is over distinct
 * integer *values* (the encoding is a bijection on in-domain values, so projecting back is exact).
 * The channel only *appends* variables and factors, so the original ids are unchanged — projecting
 * and decoding read them directly. Built once per count/sample run and reused across hash families.
 */
internal class CellContext private constructor(
    val problem: Problem,
    val hashDomain: IntArray,
    private val boolSet: IntArray,
    private val intSet: IntArray,
    private val baseNumBoolVars: Int,
    private val baseNumIntVars: Int,
) {
    /** Projection key of an enumerated [model]: chosen Boolean values (0/1) then integer values. */
    fun projectionKey(model: Sample): List<Int> {
        val key = ArrayList<Int>(boolSet.size + intSet.size)
        for (v in boolSet) key.add(if (model.bools[v]) 1 else 0)
        for (v in intSet) key.add(model.ints[v])
        return key
    }

    /** Decode an enumerated [model] back to an assignment over the original problem's variables. */
    fun decode(model: Sample): Sample {
        if (model.bools.size == baseNumBoolVars && model.ints.size == baseNumIntVars) return model
        // The channel appends its vars after the originals, so the originals are the leading slice.
        return Sample(
            bools = model.bools.copyOf(baseNumBoolVars),
            ints = model.ints.copyOf(baseNumIntVars),
        )
    }

    /**
     * Count distinct projections of the cell carved out by [hashes], up to `cap + 1`, with one
     * decoded representative per distinct projection (see [CellResult]).
     *
     * The XOR hashes are propagated jointly by Gauss-Jordan elimination
     * ([com.eignex.klause.factor.bool.GaussianXor], wired in by [withHashes]), so
     * [BacktrackSolver.enumerate] finds every model in the hashed cell quickly — early parity
     * conflict/forcing keeps the search off infeasible branches. A [CELL_DECISION_BUDGET] cap bounds
     * the rare residual exhaustion-tail thrash; because Gaussian finds all models before that tail,
     * cutting it leaves the count correct.
     */
    fun countCell(hashes: List<Xor>, cap: Int): CellResult {
        val params = BacktrackPresets.satOptimized().copy(maxDecisions = CELL_DECISION_BUDGET)
        val enumeration = BacktrackSolver(problem.withHashes(hashes)).enumerate(params)
        // For hashed cells, cap+1 models decide ">cap" while staying out of the exhaustion tail; the
        // un-hashed base has no parity slices and must be enumerated fully for an exact projected count.
        val models = if (hashes.isEmpty()) enumeration else enumeration.take(cap + 1)
        val reps = LinkedHashMap<List<Int>, Sample>()
        for (model in models) {
            val key = projectionKey(model)
            if (key !in reps) {
                reps[key] = decode(model)
                if (reps.size > cap) break
            }
        }
        return CellResult(count = reps.size, capped = reps.size > cap, representatives = reps.values.toList())
    }

    companion object {
        /**
         * Resolve the projection from a config's [boolSet]/[intSet] (see [ApproxCountConfig]) and build
         * the context. Both `null` projects over every variable; otherwise only the listed ones.
         */
        fun resolve(base: Problem, boolSet: IntArray?, intSet: IntArray?): CellContext {
            val bools: IntArray
            val ints: IntArray
            if (boolSet == null && intSet == null) {
                bools = base.allBoolVars()
                ints = IntArray(base.numIntVars) { it }
            } else {
                bools = boolSet ?: EmptyIntArray
                ints = intSet ?: EmptyIntArray
            }
            return build(base, bools, ints)
        }

        private fun build(base: Problem, boolSet: IntArray, intSet: IntArray): CellContext {
            if (intSet.isEmpty()) {
                return CellContext(base, boolSet, boolSet, intSet, base.numBoolVars, base.numIntVars)
            }
            val channel = IntBitChannel.channel(base, intSet)
            val bits = channel.allBits()
            // The original Boolean projection vars keep their ids; the hashes also range over the
            // channel bits that encode the chosen integer variables.
            val domain = IntArray(boolSet.size + bits.size)
            boolSet.copyInto(domain)
            bits.copyInto(domain, destinationOffset = boolSet.size)
            return CellContext(channel.problem, domain, boolSet, intSet, base.numBoolVars, base.numIntVars)
        }
    }
}
