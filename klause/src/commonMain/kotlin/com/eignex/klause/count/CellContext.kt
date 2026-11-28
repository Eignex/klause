package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.projectedOutcomes
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.util.arrays.emptyIntArray

// One family of XOR-hash cells over a fixed projection: the problem the solver enumerates, the variable ids the
// hashes range over, and how to project and decode a model. A purely Boolean projection runs over the original
// problem. Integer projection variables are channelled to Boolean bits by IntBitChannel, a bijection on in-domain
// values, and the hashes range over those bits. The channel only appends variables and factors, so original ids
// are unchanged.
internal class CellContext private constructor(
    val problem: BakedProblem,
    val hashDomain: IntArray,
    private val boolSet: IntArray,
    private val intSet: IntArray,
    private val baseNumBoolVars: Int,
    private val baseNumIntVars: Int,
) {
    // Chosen Boolean values (0/1), then integer values.
    fun projectionKey(model: Sample): List<Long> {
        val key = ArrayList<Long>(boolSet.size + intSet.size)
        for (v in boolSet) key.add(if (model.boolValue(v)) 1L else 0L)
        for (v in intSet) key.add(model.intValue(v))
        return key
    }

    fun decode(model: Sample): Sample {
        if (model.numBoolVars == baseNumBoolVars && model.numIntVars == baseNumIntVars) {
            return model.withSourceSemantics(problem.semantics)
        }
        // The channel appends its vars after the originals, so the originals are the leading slice.
        return Sample.fromOwned(
            bools = BooleanArray(baseNumBoolVars) { model.boolValue(it) },
            ints = LongArray(baseNumIntVars) { model.intValue(it) },
            reals = model.reals,
            exactReals = model.exactReals,
            exactInts = model.exactInts?.take(baseNumIntVars),
        ).withSourceSemantics(problem.semantics)
    }

    fun countCell(hashes: List<Xor>, cap: Int, maxDecisions: Long = CELL_DECISION_BUDGET): CellResult {
        require(cap >= 0)
        if ((0 until problem.numRealVars).any { problem.realLower[it] > problem.realUpper[it] }) {
            return CellResult(0, capped = false, representatives = emptyList(), complete = true)
        }
        val params = BacktrackPresets.satOptimized().copy(maxDecisions = maxDecisions)
        val outcomes = BacktrackSolver(problem.withHashes(hashes).bake()).projectedOutcomes(params, boolSet, intSet)
        val representatives = ArrayList<Sample>()
        var complete = false
        for (outcome in outcomes) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    representatives.add(decode(outcome.sample))
                    if (representatives.size > cap) break
                }
                is SearchOutcome.Exhausted -> complete = !outcome.indeterminate
                is SearchOutcome.BudgetCapped -> Unit
            }
        }
        return CellResult(representatives.size, representatives.size > cap, representatives, complete)
    }

    companion object {
        // Both sets null projects over every variable.
        fun resolve(base: BakedProblem, boolSet: IntArray?, intSet: IntArray?): CellContext {
            val bools: IntArray
            val ints: IntArray
            if (boolSet == null && intSet == null) {
                bools = base.allBoolVars()
                ints = IntArray(base.numIntVars) { it }
            } else {
                bools = boolSet?.distinct()?.toIntArray() ?: emptyIntArray()
                ints = intSet?.distinct()?.toIntArray() ?: emptyIntArray()
            }
            return build(base, bools, ints)
        }

        private fun build(base: BakedProblem, boolSet: IntArray, intSet: IntArray): CellContext {
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
