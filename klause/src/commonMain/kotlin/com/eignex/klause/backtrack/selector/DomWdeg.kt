package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IndexedMaxHeap
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * Domain-over-weighted-degree (Boussemart-Hemery-Lecoutre-Sais 2004). Maintains a
 * per-factor *failure weight* that's bumped every
 * time the factor participates in a conflict — read off
 * [PropagationResult.Unsat.conflictFactors], which carries the full propagation-graph
 * attribution. The variable score is then
 *     wdeg(v) / dom(v)
 * where `wdeg(v) = Σ factor_weights[f]` over every factor mentioning `v`. Pick the
 * variable with the highest score; first-fail with a conflict-driven prior on which
 * constraints have proven hard so far.
 *
 * Compared to [Vsids]: dom/wdeg attributes "hardness" to *constraints* (so two variables
 * touched by the same hard constraint are both prioritised), while VSIDS attributes
 * directly to *variables*. Empirically dom/wdeg wins on CSPs with structured propagators;
 * VSIDS wins on SAT-style problems. They compose — a portfolio bandit can pick between
 * them per restart.
 *
 * Weights persist across [onRestart]; that's intentional, mirroring VSIDS's persistence.
 * Resizes the factor-weights array across problems for instance reuse.
 */
internal class DomWdeg : VariableSelector {

    override fun fresh() = DomWdeg()

    private var factorWeights: DoubleArray = DoubleArray(0)

    // Combined bool+int heap keyed on `wdeg(v) = Σ factorWeights[f]`; the dom(v) divider
    // is applied at pick time via an upper-bound prune. See [pickByActivityWithDomDivider].
    private var heap: IndexedMaxHeap? = null
    private var problemRef: Problem? = null
    private var numBoolCached: Int = 0
    private var numIntCached: Int = 0
    private val pickSkipBuffer = IntArrayList(16)

    /** Conflict bumps that arrived before the first [pick] (so before we'd captured the
     *  problem reference). Applied as part of the next [pick]'s heap init. */
    private val pendingBumps = IntArrayList(8)

    private fun ensureInitialized(problem: Problem) {
        if (heap != null && problemRef === problem) return
        val numFactors = problem.numFactors
        if (factorWeights.size != numFactors) factorWeights = DoubleArray(numFactors) { 1.0 }
        val numBool = problem.numBoolVars
        val numInt = problem.numIntVars
        val h = IndexedMaxHeap(numBool + numInt)
        val projection = PropagationProblem(problem)
        // Seed each var's key with Σ factorWeights[f] over its occurrence list.
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
        // Drain any conflict bumps that arrived before initialization.
        if (pendingBumps.size > 0) {
            for (i in 0 until pendingBumps.size) applyFactorBump(problem, pendingBumps[i])
            pendingBumps.clear()
        }
    }

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        ensureInitialized(session.problem)
        return pickByActivityWithDomDivider(
            heap = requireNotNull(heap),
            session = session,
            numBool = numBoolCached,
            skip = pickSkipBuffer,
        )
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        // Every factor implicated in the conflict gets +1. The reason set was assembled
        // by a backward BFS through the propagation graph (see
        // PropagationState.extractConflictFactors), so this captures all contributing
        // constraints, not just the single failing one.
        val problem = problemRef
        for (fid in unsat.conflictFactors) {
            if (fid >= factorWeights.size) continue
            factorWeights[fid] += 1.0
            if (problem != null) applyFactorBump(problem, fid) else pendingBumps.add(fid)
        }
    }

    /** Push the `+1` weight change on factor [fid] into every var in its scope's heap key. */
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
