package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ExactPseudoBooleanPropagator
import com.eignex.klause.factor.bool.CardinalityPropagator
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.ir.Lit
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.maxOf
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

// Resolves pseudo-Boolean conflicts with coefficient-carrying reasons. If a sound PB derivation is not
// available, [failed] sends the conflict through [ClauseResolvent].
internal class PbConflictResolvent(private val state: PropagationState, private val graph: ReasonGraph) :
    ConflictResolvent {

    private val acc = PbAccumulator()
    private val reason = PbAccumulator()
    private var budget = ArithmeticLearningBudget(state)
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

    private fun decline(cause: String) {
        if (!failed) state.arithmeticLearningTrace?.record("pb.decline.$cause")
        failed = true
    }

    override val bumpBoolVars = IntArrayList()
    override val bumpIntVars = IntArrayList()
    override val offTrailFrontier = IntArrayList()

    fun releaseArithmetic() {
        acc.clear()
        reason.clear()
    }

    fun resourceDecline(reason: String) = decline("resource-$reason")

    override fun reset(universe: Int) {
        budget = ArithmeticLearningBudget(state)
        acc.budget = budget
        reason.budget = budget
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
            acc.forEachVar { v ->
                if (isFrontier(v) && graph.levelOf(v) == conflictLevel) n++
            }
            return n
        }

    override fun isFrontier(v: Int): Boolean {
        if (resolvedVars.contains(v)) return false
        val c = acc.coefOf(v)
        if (c == BIG_ZERO) return false
        // The constraint literal of v is (v, c>0); it is a live frontier literal iff currently false.
        return state.litFalse(Lit.make(v, c > BIG_ZERO))
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
        state.arithmeticLearningTrace?.clause("pb.reason", reason, state)
        if (!booleanLiterals(reason)) {
            decline("non-boolean-reason")
            return
        }
        if (!seeded) {
            conflictLevel = currentLevel
            seeded = true
            if (!loadSeed(reason) || !acc.hasSafeActivity()) decline("seed-or-activity") else bumpAll(acc)
            return
        }
        val pivot = pendingPivot
        pendingPivot = -1
        if (pivot < 0) {
            decline("missing-pivot")
            return
        }
        cancel(pivot, reason)
    }

    /** Load the conflicting constraint: the PB/clause of [seedFactorId] when available, else the
     *  clause-form seed the driver supplied. A seed has no forced literal, so cardinality/EQ reasons
     *  (whose direction is ambiguous without one) fall back to the clause form. */
    private fun loadSeed(reasonLits: IntArray): Boolean {
        if (seedFactorId >= 0 && loadFactor(seedFactorId, acc, forcedLit = Lit.NONE)) return true
        return !failed && acc.loadClause(reasonLits)
    }

    /**
     * Cancel [pivot] between the running conflict [acc] and the constraint that forced it, using the
     * division rule of Elffers and Nordström ("Divide and Conquer: Towards Faster Pseudo-Boolean Solving",
     * IJCAI 2018, https://doi.org/10.24963/ijcai.2018/180) rather than classic generalized resolution: divide
     * the *reason* by its pivot coefficient rounding up (a Chvátal-Gomory cut, so still implied) to make
     * that coefficient ±1, then add `acc + |coefₐ(pivot)|·reason`. The conflict constraint itself is never
     * scaled up, so coefficients stay bounded and the learned cut stays strong — the property classic
     * LCM-scaling resolution loses. Saturate afterward to keep the constraint canonical.
     */
    private fun cancel(pivot: Int, fallbackReasonLits: IntArray) {
        if (!loadPivotReason(pivot, fallbackReasonLits) || !reason.hasSafeActivity()) {
            decline("pivot-reason-or-activity")
            return
        }
        val a = acc.coefOf(pivot)
        val b = reason.coefOf(pivot)
        // ExecutionControl needs opposite-sign coefficients on the pivot in the two constraints.
        if (a == BIG_ZERO || b == BIG_ZERO || (a > BIG_ZERO) == (b > BIG_ZERO)) {
            decline("pivot-sign")
            return
        }
        val absA = a.abs()
        val absB = b.abs()
        // Selective reduction: weaken away only the reason's non-falsified literals whose
        // coefficient is not divisible by the pivot coefficient — the ones the rounding below would
        // inflate into spurious slack. Falsified and divisible literals are kept, so unit-weight
        // (cardinality) reasons are untouched (pivot coefficient 1 divides everything) and the pigeonhole
        // counting structure survives, while genuine non-unit cuts stay tight.
        reason.weakenForDivision(pivot, absB) { lit -> state.litFalse(lit) }
        // Divide the reason by its pivot coefficient, rounding up ⇒ its pivot coefficient becomes ±1.
        reason.divideRoundUp(absB)
        // acc + absA·reason cancels the pivot (opposite signs, |reason pivot| now 1). acc is not scaled.
        if (!acc.addScaled(reason, mulSelf = BIG_ONE, mulOther = absA) || !acc.hasSafeActivity()) {
            decline("combination-overflow")
            return
        }
        acc.saturate()
        bumpAll(reason)
    }

    /** Load [pivot]'s reason constraint into [reason]: the forcing factor's `≥` constraint (must mention
     *  [pivot]), else the fallback clause = the driver's antecedents plus [pivot]'s forced literal. */
    private fun loadPivotReason(pivot: Int, fallbackReasonLits: IntArray): Boolean {
        val forcingFid = state.boolReason[pivot]
        val forced = Lit.make(pivot, state.boolValueAt(pivot))
        if (loadFactor(forcingFid, reason, forced) && reason.coefOf(pivot) != BIG_ZERO) return true
        if (failed) return false
        // Fallback: reconstruct the reason clause. The driver's antecedents omit the forced literal;
        // add it back so the clause mentions the pivot and cancellation can proceed.
        val lits = IntArray(fallbackReasonLits.size + 1)
        fallbackReasonLits.copyInto(lits)
        lits[fallbackReasonLits.size] = forced
        return reason.loadClause(lits)
    }

    /** Load factor [fid]'s constraint into [target] as a `≥` constraint; false when not a loadable kind.
     *  [forcedLit] (the pivot's now-true literal, or [Lit.NONE] for a seed) selects the propagating half of a
     *  cardinality or equality reason. A `boolReason` can name a since-forgotten learned constraint (the
     *  clause path avoids this by reading `boolAntecedents`), so an out-of-range id falls back to the
     *  clause-form reason rather than indexing a compacted store. */
    private fun loadFactor(fid: Int, target: PbAccumulator, forcedLit: Int): Boolean {
        state.arithmeticLearningTrace?.record("pb.source", fid.toLong(), forcedLit.toLong())
        if (fid < 0 || fid >= state.totalFactorCount) return false
        return when (val f = state.factorAt(fid)) {
            is ExactPseudoBooleanPropagator -> admitSource(f.literals) && f.loadArithmeticReason(target, forcedLit, state, budget)
            is LearnedExactPbPropagator -> admitSource(f.literals) && target.loadPb(f.weights, f.literals, f.degree)
            is PseudoBooleanPropagator -> admitSource(f.literals) && f.loadReason(target, forcedLit, state)
            is CardinalityPropagator -> admitSource(f.literals) && f.loadReason(target, forcedLit, state)
            is ClausePropagator -> admitSource(f.literals) && target.loadClause(f.literals)
            else -> false
        }
    }

    private fun booleanLiterals(literals: IntArray): Boolean =
        literals.all {
            budget.spend()
            Lit.variable(it) in 0 until state.problem.numBoolVars
        }

    private fun admitSource(literals: IntArray): Boolean = booleanLiterals(literals).also {
        if (!it) decline("non-boolean-source")
    }

    private fun bumpAll(a: PbAccumulator) {
        a.forEachVar { v ->
            if (a.coefOf(v) != BIG_ZERO && bumped.add(v)) bumpBoolVars.add(v)
        }
    }

    override fun finalizeResult(currentLevel: Int): ConflictAnalyzer.AnalysisResult {
        if (failed) return ConflictAnalyzer.AnalysisResult.NotApplicable
        val m = acc.materialize() ?: run {
            decline("materialization")
            return ConflictAnalyzer.AnalysisResult.NotApplicable
        }
        val levels = distinctLevels(m.literals)
        val (backjump, asserting) = pbAssertion(m.weights, m.literals, m.degree, currentLevel)
        // A non-asserting PB nogood makes the engine backtrack chronologically *without learning* — worse
        // than the clause 1UIP the clause resolvent always yields. Defer to it instead of emitting one.
        if (!asserting) {
            decline("non-asserting")
            return ConflictAnalyzer.AnalysisResult.NotApplicable
        }
        // A unit-weight, degree-1 constraint is exactly a clause; emit it as one so it flows the clause
        // storage/vivification/glue paths rather than a degenerate PB propagator.
        if (m.degree == BIG_ONE && m.weights.all { it == BIG_ONE }) {
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
        budget.spend(bytes = literals.size.toLong() * 24)
        val seen = IntHashSet(literals.size)
        for (lit in literals) {
            budget.spend()
            val lvl = graph.levelOf(Lit.variable(lit))
            if (lvl > 0) seen.add(lvl)
        }
        val out = seen.toIntArray()
        out.sort()
        return out
    }

    /**
     * The pseudo-Boolean assertion level: the smallest decision level `L` in `[0, currentLevel)` at which
     * the learned constraint `Σ weights·literals ≥ degree` becomes non-conflicting *and* forces a literal
     * once the trail is popped to `L`. Backjumping to that `L` prunes the most search while keeping the
     * constraint asserting. Returns `(backjumpLevel, asserting)`; if no such level exists the constraint
     * is non-asserting and the engine falls back to chronological backtracking (always sound).
     *
     * For a unit-weight degree-1 constraint (a clause) this reduces exactly to the clause second-highest
     * level. The candidate levels are the distinct decision levels of the constraint's assigned literals.
     */
    private fun pbAssertion(
        weights: Array<BigInt>,
        literals: IntArray,
        degree: BigInt,
        currentLevel: Int,
    ): Pair<Int, Boolean> {
        budget.spend(bytes = literals.size.toLong() * 48 + 8)
        val levels = IntArray(literals.size) { index ->
            budget.spend()
            val variable = Lit.variable(literals[index])
            if (state.boolAssignedAt(variable)) state.boolLevel[variable].coerceAtLeast(0) else currentLevel
        }
        val order = literals.indices.sortedWith { left, right ->
            budget.spend()
            val level = levels[left].compareTo(levels[right])
            if (level == 0) left.compareTo(right) else level
        }
        val suffix = Array(weights.size + 1) { BIG_ZERO }
        var available = BIG_ZERO
        for (index in order.indices.reversed()) {
            budget.scalar(available, weights[order[index]])
            available += weights[order[index]]
            suffix[index] = maxOf(suffix[index + 1], weights[order[index]])
        }
        var position = 0
        var level = 0
        while (level < currentLevel) {
            while (position < order.size && levels[order[position]] <= level) {
                budget.spend()
                val index = order[position++]
                if (state.litFalse(literals[index])) available -= weights[index]
            }
            val slack = available - degree
            if (slack >= BIG_ZERO && suffix[position] > slack) return level to true
            if (position == order.size) break
            level = levels[order[position]]
        }
        return 0 to false
    }
}
