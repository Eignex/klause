package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ReifiedPseudoBooleanPropagator
import com.eignex.klause.factor.bool.CardinalityPropagator
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.ExactPseudoBooleanPropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.ir.Lit
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.maxOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus

// Resolves pseudo-Boolean conflicts with coefficient-carrying reasons. If a sound PB derivation is not
// available, [failed] sends the conflict through [ClauseResolvent].
internal class PbConflictResolvent(private val state: PropagationState, private val graph: ReasonGraph) :
    ConflictResolvent {

    private val acc = PbAccumulator()
    private val reason = PbAccumulator()
    private var budget = ArithmeticLearningBudget(state)
    private val resolvedVars = IntHashSet()
    private val bumped = IntHashSet()

    // -1 for an external clause seed (LP Farkas, decision conflict).
    var seedFactorId: Int = -1

    private var conflictLevel = 0
    private var seeded = false
    private var pendingPivot = -1

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
        // The asserting literal is already in [acc] with its coefficient.
    }

    override fun drainFrontier() {
        // [acc] already holds every lower-level literal.
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

    // A seed has no forced literal, so cardinality and equality reasons, ambiguous without one, fall back to the
    // clause form.
    private fun loadSeed(reasonLits: IntArray): Boolean {
        if (seedFactorId >= 0 && loadFactor(seedFactorId, acc, forcedLit = Lit.NONE)) return true
        return !failed && acc.loadClause(reasonLits)
    }

    // Division rule of Elffers and Nordström ("Divide and Conquer: Towards Faster Pseudo-Boolean Solving",
    // IJCAI 2018, https://doi.org/10.24963/ijcai.2018/180): divide the reason by its pivot coefficient rounding
    // up (a Chvátal-Gomory cut, so still implied), then add `acc + |coefₐ(pivot)|·reason`. The conflict side is
    // never scaled, so coefficients stay bounded, which LCM-scaling resolution loses.
    private fun cancel(pivot: Int, fallbackReasonLits: IntArray) {
        if (!loadPivotReason(pivot, fallbackReasonLits) || !reason.hasSafeActivity()) {
            decline("pivot-reason-or-activity")
            return
        }
        val a = acc.coefOf(pivot)
        val b = reason.coefOf(pivot)
        // Cancelling the pivot needs opposite-sign coefficients in the two constraints.
        if (a == BIG_ZERO || b == BIG_ZERO || (a > BIG_ZERO) == (b > BIG_ZERO)) {
            decline("pivot-sign")
            return
        }
        val absA = a.abs()
        val absB = b.abs()
        // Weaken only the reason's non-falsified literals whose coefficient the pivot coefficient does not divide,
        // the ones rounding would inflate into spurious slack. Unit-weight reasons are untouched, so the
        // pigeonhole counting structure survives.
        reason.weakenForDivision(pivot, absB) { lit -> state.litFalse(lit) }
        reason.divideRoundUp(absB)
        // acc + absA·reason cancels the pivot (opposite signs, |reason pivot| now 1). acc is not scaled.
        if (!acc.addScaled(reason, mulSelf = BIG_ONE, mulOther = absA) || !acc.hasSafeActivity()) {
            decline("combination-overflow")
            return
        }
        acc.saturate()
        bumpAll(reason)
    }

    private fun loadPivotReason(pivot: Int, fallbackReasonLits: IntArray): Boolean {
        val forcingFid = state.boolReason[pivot]
        val forced = Lit.make(pivot, state.boolValueAt(pivot))
        if (loadFactor(forcingFid, reason, forced) && reason.coefOf(pivot) != BIG_ZERO) return true
        if (failed) return false
        // The driver's antecedents omit the forced literal; add it back so the clause mentions the pivot.
        budget.spend(work = fallbackReasonLits.size.toLong() + 1,
            bytes = (fallbackReasonLits.size.toLong() + 1) * Int.SIZE_BYTES)
        val lits = IntArray(fallbackReasonLits.size + 1)
        fallbackReasonLits.copyInto(lits)
        lits[fallbackReasonLits.size] = forced
        return reason.loadClause(lits)
    }

    // [forcedLit] selects the propagating half of a cardinality or equality reason ([Lit.NONE] for a seed). A
    // `boolReason` can name a since-forgotten learned constraint, so an out-of-range id falls back to the clause
    // form rather than indexing a compacted store.
    private fun loadFactor(fid: Int, target: PbAccumulator, forcedLit: Int): Boolean {
        state.arithmeticLearningTrace?.record("pb.source", fid.toLong(), forcedLit.toLong())
        if (fid < 0 || fid >= state.totalFactorCount) return false
        return when (val f = state.factorAt(fid)) {
            is ReifiedPseudoBooleanPropagator ->
                admitSource(f.literals) && f.loadArithmeticReason(target, forcedLit, state, budget)
            is ExactPseudoBooleanPropagator ->
                admitSource(f.literals) && f.loadArithmeticReason(target, forcedLit, state, budget)
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
        // A non-asserting PB nogood would backtrack chronologically without learning, worse than the clause
        // resolvent's 1UIP clause.
        if (!asserting) {
            decline("non-asserting")
            return ConflictAnalyzer.AnalysisResult.NotApplicable
        }
        // A unit-weight, degree-1 constraint is a clause; emit it as one so it takes the clause storage paths.
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
        budget.spend(work = out.size.toLong() * (Int.SIZE_BITS - out.size.countLeadingZeroBits()))
        out.sort()
        return out
    }

    // The smallest level `L` below the conflict level at which the constraint is non-conflicting and forces a
    // literal once the trail is popped to `L`, which prunes the most search while staying asserting. For a
    // clause this is the second-highest level.
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
                if (state.litFalse(literals[index])) {
                    budget.scalar(available, weights[index])
                    available -= weights[index]
                }
            }
            budget.scalar(available, degree)
            val slack = available - degree
            if (slack >= BIG_ZERO && suffix[position] > slack) return level to true
            if (position == order.size) break
            level = levels[order[position]]
        }
        return 0 to false
    }
}
