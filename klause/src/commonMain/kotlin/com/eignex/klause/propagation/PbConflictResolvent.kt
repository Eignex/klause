package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Pseudo-Boolean cutting-planes conflict resolvent (#1119 Phase 3) — the second [ConflictResolvent]
 * implementation the Phase 0 seam anticipated. The accumulating nogood is a [PbAccumulator] `≥`
 * constraint rather than a clause, and `resolve` is generalized resolution (coefficient cancellation)
 * followed by saturation and Chvátal-Gomory rounding, so the learned constraint can be strictly
 * stronger than the clause a 1UIP walk would yield.
 *
 * The 1UIP driver ([ConflictAnalyzer]) is unchanged: it selects pivots off the pin trail exactly as for
 * clauses. What differs is that this resolvent ignores the driver's clause-form `reason` array and
 * instead recovers the *coefficient-carrying* reason constraint for each step — the failing constraint
 * for the seed (from [seedFactorId]) and, for each resolved pivot, the constraint that forced it (from
 * [PropagationState.boolReason]). When a reason is not a loadable `≥` constraint (an `EQ` pseudo-Boolean,
 * a cardinality/xor factor, or an arithmetic overflow) it falls back to the clause-form reason, which is
 * always sound. If any step cannot proceed soundly it sets [failed]; the analyzer then re-runs the whole
 * conflict through the [ClauseResolvent], so a PB failure never loses a conflict or corrupts a nogood.
 */
internal class PbConflictResolvent(private val state: PropagationState, private val graph: ReasonGraph) :
    ConflictResolvent {

    private val acc = PbAccumulator()
    private val reason = PbAccumulator()
    private val resolvedVars = IntHashSet()
    private val bumped = IntHashSet()

    /** Factor id of the conflicting constraint (the seed), or -1 for an externally-supplied clause seed
     *  (LP Farkas, decision conflict). Set by the analyzer before each analysis. */
    var seedFactorId: Int = -1

    private var conflictLevel = 0
    private var seeded = false
    private var pendingPivot = -1

    /** Set when a step cannot proceed as a sound PB derivation; the analyzer falls back to clauses. */
    var failed = false
        private set

    override val bumpBoolVars = IntArrayList()
    override val bumpIntVars = IntArrayList() // always empty: PB problems have no integer variables
    override val offTrailFrontier = IntArrayList() // always empty: no order-literal atoms in PB problems

    override fun reset(universe: Int) {
        acc.clear()
        reason.clear()
        resolvedVars.clear()
        bumped.clear()
        bumpBoolVars.clear()
        bumpIntVars.clear()
        offTrailFrontier.clear()
        conflictLevel = 0
        seeded = false
        pendingPivot = -1
        failed = false
    }

    override val liveAtCurrentLevel: Int
        get() {
            var n = 0
            for (v in acc.coef.keys) {
                if (isFrontier(v) && graph.levelOf(v) == conflictLevel) n++
            }
            return n
        }

    override fun isFrontier(v: Int): Boolean {
        if (resolvedVars.contains(v)) return false
        val c = acc.coefOf(v)
        if (c == 0L) return false
        // The constraint literal of v is (v, c>0); it is a live frontier literal iff currently false.
        return state.litFalse(Lit.make(v, c > 0L))
    }

    override fun resolveOut(pivot: Int) {
        resolvedVars.add(pivot)
        pendingPivot = pivot
    }

    override fun addAsserting(pivot: Int) {
        // The asserting literal is already present in [acc] with its coefficient; nothing to add. The PB
        // constraint carries it into [finalizeResult] unchanged.
    }

    override fun drainFrontier() {
        // No-op: [acc] already holds the complete constraint (all lower-level literals included).
    }

    override fun resolve(reason: IntArray, currentLevel: Int) {
        if (failed) return
        if (!seeded) {
            conflictLevel = currentLevel
            seeded = true
            if (!loadSeed(reason)) failed = true else bumpAll(acc)
            return
        }
        val pivot = pendingPivot
        pendingPivot = -1
        if (pivot < 0) {
            failed = true
            return
        }
        cancel(pivot, reason)
    }

    /** Load the conflicting constraint: the PB/clause of [seedFactorId] when available, else the
     *  clause-form seed the driver supplied. */
    private fun loadSeed(reasonLits: IntArray): Boolean {
        if (seedFactorId >= 0 && loadFactor(seedFactorId, acc)) return true
        return acc.loadClause(reasonLits)
    }

    /** Cancel [pivot] between [acc] and the constraint that forced it, then saturate and gcd-normalize. */
    private fun cancel(pivot: Int, fallbackReasonLits: IntArray) {
        if (!loadPivotReason(pivot, fallbackReasonLits)) {
            failed = true
            return
        }
        val a = acc.coefOf(pivot)
        val b = reason.coefOf(pivot)
        // Cancellation needs opposite-sign coefficients on the pivot in the two constraints.
        if (a == 0L || b == 0L || (a > 0L) == (b > 0L)) {
            failed = true
            return
        }
        val absA = if (a < 0L) -a else a
        val absB = if (b < 0L) -b else b
        val g = gcd(absA, absB)
        val mulSelf = absB / g
        val mulOther = absA / g
        if (!acc.addScaled(reason, mulSelf, mulOther)) {
            failed = true
            return
        }
        acc.saturate()
        acc.normalizeByGcd()
        bumpAll(reason)
    }

    /** Load [pivot]'s reason constraint into [reason]: the forcing factor's `≥` constraint (must mention
     *  [pivot]), else the fallback clause = the driver's antecedents plus [pivot]'s forced literal. */
    private fun loadPivotReason(pivot: Int, fallbackReasonLits: IntArray): Boolean {
        val forcingFid = state.boolReason[pivot]
        if (loadFactor(forcingFid, reason) && reason.coefOf(pivot) != 0L) return true
        // Fallback: reconstruct the reason clause. The driver's antecedents omit the forced literal;
        // add it back so the clause mentions the pivot and cancellation can proceed.
        val forced = Lit.make(pivot, state.boolValueAt(pivot))
        val lits = IntArray(fallbackReasonLits.size + 1)
        fallbackReasonLits.copyInto(lits)
        lits[fallbackReasonLits.size] = forced
        return reason.loadClause(lits)
    }

    /** Load factor [fid]'s constraint into [target] as a `≥` constraint; false when not a loadable kind. */
    private fun loadFactor(fid: Int, target: PbAccumulator): Boolean {
        if (fid < 0) return false
        return when (val f = state.factorAt(fid)) {
            is PseudoBooleanPropagator -> f.loadReason(target)
            is ClausePropagator -> target.loadClause(f.literals)
            else -> false
        }
    }

    private fun bumpAll(a: PbAccumulator) {
        for (v in a.coef.keys) {
            if (a.coefOf(v) != 0L && bumped.add(v)) bumpBoolVars.add(v)
        }
    }

    override fun finalizeResult(currentLevel: Int): ConflictAnalyzer.AnalysisResult {
        if (failed) return ConflictAnalyzer.AnalysisResult.NotApplicable
        val m = acc.materialize() ?: return ConflictAnalyzer.AnalysisResult.NotApplicable
        val levels = distinctLevels(m.literals)
        var atConflictLevel = 0
        for (lit in m.literals) if (graph.levelOf(Lit.variable(lit)) == currentLevel) atConflictLevel++
        val backjump = backjumpLevelOf(m.literals, currentLevel)
        val asserting = atConflictLevel == 1
        // A unit-weight, degree-1 constraint is exactly a clause; emit it as one so it flows the clause
        // storage/vivification/glue paths rather than a degenerate PB propagator.
        if (m.degree == 1L && m.weights.all { it == 1L }) {
            return ConflictAnalyzer.AnalysisResult.Learned(m.literals, backjump, levels.size, levels, asserting)
        }
        return ConflictAnalyzer.AnalysisResult.LearnedPb(
            m.weights,
            m.literals,
            m.degree,
            backjump,
            levels.size,
            levels,
            asserting,
        )
    }

    private fun distinctLevels(literals: IntArray): IntArray {
        val seen = IntHashSet(literals.size)
        for (lit in literals) {
            val lvl = graph.levelOf(Lit.variable(lit))
            if (lvl > 0) seen.add(lvl)
        }
        val out = seen.toIntArray()
        out.sort()
        return out
    }

    private fun backjumpLevelOf(literals: IntArray, currentLevel: Int): Int {
        var best = 0
        for (lit in literals) {
            val lvl = graph.levelOf(Lit.variable(lit))
            if (lvl < currentLevel && lvl > best) best = lvl
        }
        return best
    }

    private companion object {
        fun gcd(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return if (x < 0L) -x else x
        }
    }
}
