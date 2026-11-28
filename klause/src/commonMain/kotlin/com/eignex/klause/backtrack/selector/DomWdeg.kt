package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.collections.IndexedMaxHeap
import com.eignex.util.collections.IntArrayList
import kotlin.random.Random

// Domain-over-weighted-degree (Boussemart, Hemery, Lecoutre and Sais, "Boosting Systematic Search by
// Weighting Constraints", ECAI 2004, pp. 146–150): `wdeg(v) / dom(v)`, where each factor in a conflict's
// propagation-graph attribution gains weight 1. Weights persist across restarts.
internal class DomWdeg : VariableSelector {

    override fun fresh() = DomWdeg()

    private var factorWeights: DoubleArray = DoubleArray(0)

    // Keyed on `wdeg(v)`; the dom(v) divider applies at pick time.
    private var heap: IndexedMaxHeap? = null
    private var problemRef: Problem? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    // Conflict bumps that arrive before the first pick has captured the problem.
    private val pendingBumps = IntArrayList(8)

    private fun ensureInitialized(session: PropagationSession) {
        val problem = session.problem
        if (heap != null && problemRef === problem) return
        val numFactors = problem.numFactors
        if (factorWeights.size != numFactors) factorWeights = DoubleArray(numFactors) { 1.0 }
        val numBool = problem.numBoolVars
        val numInt = problem.numIntVars
        val h = IndexedMaxHeap(numBool + numInt)
        val projection = session.projection
        for (v in 0 until numBool) {
            var sum = 0.0
            for (fid in projection.boolOccurrences[v]) sum += factorWeights[fid]
            h.insert(v, sum)
        }
        for (v in 0 until numInt) {
            var sum = 0.0
            for (fid in projection.intOccurrences[v]) sum += factorWeights[fid]
            h.insert(numBool + v, sum)
        }
        heap = h
        problemRef = problem
        numBoolCached = numBool
        numIntCached = numInt
        if (pendingBumps.size > 0) {
            for (i in 0 until pendingBumps.size) {
                val fid = pendingBumps[i]
                if (fid !in factorWeights.indices) continue
                factorWeights[fid] += 1.0
                applyFactorBump(problem, fid)
            }
            pendingBumps.clear()
        }
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        ensureInitialized(session)
        return pickByActivityWithDomDivider(
            heap = requireNotNull(heap),
            session = session,
            numBool = numBoolCached,
            skip = pickSkipBuffer,
        )
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        val problem = problemRef
        for (fid in unsat.conflictFactors) {
            if (problem == null) {
                pendingBumps.add(fid)
            } else if (fid in factorWeights.indices) {
                factorWeights[fid] += 1.0
                applyFactorBump(problem, fid)
            }
        }
    }

    private fun applyFactorBump(problem: Problem, fid: Int) {
        val h = heap ?: return
        val factor = problem.factors[fid]
        for (b in factor.boolVars) h.updateKey(b, h.keyOf(b) + 1.0)
        for (i in factor.intVars) {
            val id = numBoolCached + i
            h.updateKey(id, h.keyOf(id) + 1.0)
        }
    }
}
