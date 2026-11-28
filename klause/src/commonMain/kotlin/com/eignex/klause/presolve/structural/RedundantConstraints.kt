package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.maximalPersistentAmoCliques
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SubsumeState
import com.eignex.klause.presolve.asSourceDelta
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.ScanMeter
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntLongMap
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.collections.MutableLongIntMap
import com.eignex.util.collections.MutableLongObjectMap
import com.eignex.util.hash.splitmix64
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact

// Lifts bool var ids into a range disjoint from int var ids so the two cannot alias in a shallow key.
private const val BOOL_VAR_MARK = 1L shl 40

internal object RedundantConstraints {
    private class Interrupted : RuntimeException()

    @Suppress("NOTHING_TO_INLINE") // Scalar scans use the bound cursor without default-argument forwarding.

    private inline fun visit(scan: ScanMeter?, units: Long = SUBSUME_WORK_WEIGHT) {
        if (scan != null && !scan.visit(units)) throw Interrupted()
    }

    // With [incremental], the phase-1/2 indices persist in [SubsumeIncremental.memo] and only factors added or
    // dropped since the last run are reprocessed, so a fruitless re-run costs O(delta).
    fun removeRedundantConstraints(
        problem: Problem,
        ranges: ColumnRanges,
        incremental: SubsumeIncremental? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta {
        if (incremental == null) return computeFull(problem, ranges, cancellation)
        val out = incremental.memo.reconcile(problem, incremental, cancellation)
        val delta = finishAfterPhase2(problem, ranges, out, cancellation, incremental.memo.subsets)
        // Phases 3–5 drop factors [reconcile] never saw; feed the full drop set back so the memo retracts
        // every dropped factor's index entry before the next firing.
        incremental.memo.setPendingSelfDrops(
            IntArray(delta.droppedIndices.size) { incremental.liveIds[delta.droppedIndices[it]] },
        )
        if (SUBSUME_DIFFERENTIAL_CHECK) assertMatchesFull(problem, ranges, delta)
        return delta
    }

    // Phases 1, 2 and 4 justify each drop by another retained row, never by a column's extent. Phases 3 and 5
    // charge a column its whole width, so here they decline any row or global mentioning an open column side.
    fun removeRedundantSourceConstraints(
        problem: Problem,
        cancellation: ExecutionControl,
        memo: SubsumeMemo? = null,
    ): SourceDelta = computeFull(
        problem, ColumnRanges.of(problem.intBounds), cancellation, memo?.subsets,
    ).asSourceDelta()

    // Debug switch: every incremental firing also recomputes the full delta and asserts the two agree.
    private const val SUBSUME_DIFFERENTIAL_CHECK = false

    private fun assertMatchesFull(problem: Problem, ranges: ColumnRanges, delta: PassDelta) {
        val full = computeFull(problem, ranges)
        val a = delta.droppedIndices.toHashSet()
        val b = full.droppedIndices.toHashSet()
        require(a == b) { "incremental subsume delta $a != full $b (nfac=${problem.factors.size})" }
    }

    /**
     * Drops constraints implied by another retained one, preserving the feasible set exactly:
     *  1. Exact duplicates by `Factor.structuralKey`; unkeyed factors are always kept.
     *  2. Same-vector domination over [Linear] / [PseudoBoolean] inequalities, each `≥` normalized to `≤`
     *     and GCD-reduced: among rows sharing a reduced coefficient vector the tightest bound implies the
     *     rest. An `=` drops looser rows but is never dropped here.
     *  3. Variable-subset / proportional domination ([SubsetDomination]).
     *  4. Clique-implied knapsacks ([dropCliqueImpliedKnapsacks]).
     *  5. Vacuous globals ([isVacuousGlobal]).
     *
     * Self-redundant rows are left to coefficient strengthening, so this pass is purely cross-constraint.
     */
    private fun computeFull(
        problem: Problem,
        ranges: ColumnRanges,
        cancellation: ExecutionControl = ExecutionControl.Never,
        subsets: SubsetDomination? = null,
    ): PassDelta {
        val scan = ScanMeter(cancellation)
        val survivors = try {
            survivorsOfPhases1And2(problem, scan)
        } catch (_: Interrupted) {
            return PassDelta()
        } finally {
            scan.flush()
        }
        return finishAfterPhase2(problem, ranges, survivors, cancellation, subsets)
    }

    private fun survivorsOfPhases1And2(problem: Problem, scan: ScanMeter): List<Factor> {
        if (!scan.check()) throw Interrupted()
        val factors = problem.factors
        // Phase 1 is two-tier: a cheap [shallowKey] buckets the factors, and the full structural key (a
        // Table's embeds its whole tuple set) is built only on a collision. True duplicates always collide.
        val deduped = ArrayList<Factor>(factors.size)
        val firstByShallow = HashMap<Long, Factor>()
        val keysByShallow = HashMap<Long, HashSet<StructuralKey>>()
        for (f in factors) {
            visit(scan)
            val sk = shallowKey(f, scan)
            val first = firstByShallow[sk]
            if (first == null) {
                firstByShallow[sk] = f
                deduped.add(f)
                continue
            }
            // Shallow collision: the full keys decide. The first factor's key is built once, here.
            val keys = keysByShallow.getOrPut(sk) { hashSetOf(fullKey(first, scan)) }
            if (keys.add(fullKey(f, scan))) deduped.add(f)
        }
        // Unit-coefficient clause rows have the same bound; their duplicates were removed by keys.
        if (deduped.all {
                visit(scan)
                it is Clause
            }) {
            return deduped
        }
        // Phase 2: bucket the ≤-normalised Linear inequalities by coefficient vector; the bucket's
        // tightest bound (and whether an `=` provides it) decides which inequalities are implied.
        val bucketMin = HashMap<TermKey, Long>()
        val bucketEqAtMin = HashMap<TermKey, Boolean>()
        fun offer(key: TermKey, bound: Long, fromEq: Boolean) {
            val cur = bucketMin[key]
            if (cur == null || bound < cur) {
                bucketMin[key] = bound
                bucketEqAtMin[key] = fromEq
            } else if (bound == cur && fromEq) {
                bucketEqAtMin[key] = true
            }
        }
        for (f in deduped) {
            visit(scan)
            // An `=` also offers its opposite direction.
            for (n in offerForms(f, scan)) {
                visit(scan)
                offer(n.key, n.bound, fromEq = n.fromEq)
                if (n.opposite != null) offer(n.opposite.key, n.opposite.bound, fromEq = true)
            }
        }
        val keptRep = HashSet<TermKey>()
        val out = ArrayList<Factor>(deduped.size)
        for (f in deduped) {
            visit(scan)
            val n = dropForm(f, scan)
            // Keep equalities, ≠, and non-(Linear/PseudoBoolean) factors; they are never dropped here.
            if (n == null || n.fromEq) {
                out.add(f)
                continue
            }
            val tightest = bucketMin.getValue(n.key)
            val keep = when {
                n.bound > tightest -> false

                bucketEqAtMin[n.key] == true -> false

                // The first representative at the tightest bound stays.
                else -> keptRep.add(n.key)
            }
            if (keep) out.add(f)
        }
        if (!scan.check()) throw Interrupted()
        return out
    }

    // [out] holds the phase-1/2 survivors in `Problem.factors` order.
    private fun finishAfterPhase2(
        problem: Problem,
        ranges: ColumnRanges,
        out: List<Factor>,
        cancellation: ExecutionControl = ExecutionControl.Never,
        subsets: SubsetDomination? = null,
    ): PassDelta {
        val factors = problem.factors
        val out3 = (subsets ?: SubsetDomination()).removeDominated(problem, ranges, out, cancellation)
        val out4 = dropCliqueImpliedKnapsacks(out3, cancellation)
        // Phase 5: drop globals the current ranges make vacuously satisfied; removing one frees
        // a variable contained only in it, which the affine pass then projects out (implied-free).
        val out5 = out4.filterNot { isVacuousGlobal(it, ranges) }
        if (out5.size == factors.size) return PassDelta()
        // Survivors are identity-equal to input factors in input order, so a two-pointer walk recovers the
        // dropped indices.
        val dropped = IntArrayList(factors.size - out5.size)
        var p = 0
        for (i in factors.indices) {
            if (p < out5.size && factors[i] === out5[p]) p++ else dropped.add(i)
        }
        return PassDelta(dropped.toIntArray())
    }

    // [rebuild] forces a full memo rebuild from the whole live set: first run, or after a reseed invalidated it.
    internal class SubsumeIncremental(
        val rebuild: Boolean,
        val liveIds: IntArray,
        val addedFactors: List<SubsumeFactor>,
        val droppedIds: IntArray,
        val memo: SubsumeMemo,
    ) : SubsumeState

    internal class SubsumeFactor(val id: Int, val factor: Factor)

    // Persistent phase-1/2 indices. Phase 2 keeps at most one representative `≤`-row per bucket, so a re-run
    // reconciles each bucket against the delta in O(1).
    //
    // A dropped row keeps its bucket offer the round it is dropped, as the from-scratch path offers every
    // deduped row before deciding drops, so it is retracted only on the next firing.
    internal class SubsumeMemo : SubsumeState {
        val subsets = SubsetDomination()

        // Long-keyed so clause-heavy models don't box the shallow key on every lookup.
        private val shallowSingle = MutableLongObjectMap<SubsumeFactor>()
        private val shallowMulti = MutableLongObjectMap<HashMap<StructuralKey, SubsumeFactor>>()
        private val buckets = HashMap<TermKey, Bucket>()
        private val indexed = MutableIntObjectMap<SubsumeFactor>()

        // Stable occurrence ids keep a duplicate's retraction separate from its retained representative.
        private var pendingSelfDrops = IntArray(0)

        // A rebuild that bailed on the deadline left the indices cleared, so the next reconcile must rebuild.
        private var bailedRebuild = false

        private fun clearIndices() {
            shallowSingle.clear()
            shallowMulti.clear()
            buckets.clear()
            indexed.clear()
        }

        private class Bucket {
            val bounds = MutableLongIntMap()
            val eqBounds = MutableLongIntMap()
            var rep: SubsumeFactor? = null
            var repBound = 0L

            fun tightest(): Long? {
                if (bounds.isEmpty()) return null
                var min = Long.MAX_VALUE
                bounds.forEach { key, _ -> if (key < min) min = key }
                return min
            }
            fun eqAtMin(): Boolean = tightest()?.let { eqBounds.containsKey(it) } ?: false

            fun addBound(bound: Long, eq: Boolean) {
                bounds.addTo(bound, 1)
                if (eq) eqBounds.addTo(bound, 1)
            }

            fun removeBound(bound: Long, eq: Boolean) {
                val c = bounds.getOrDefault(bound, 0) - 1
                if (c <= 0) bounds.remove(bound) else bounds.put(bound, c)
                if (eq) {
                    val e = eqBounds.getOrDefault(bound, 0) - 1
                    if (e <= 0) eqBounds.remove(bound) else eqBounds.put(bound, e)
                }
            }
        }

        fun reconcile(
            problem: Problem,
            inc: SubsumeIncremental,
            cancellation: ExecutionControl = ExecutionControl.Never,
        ): List<Factor> {
            val drops = IntHashSet()
            if (inc.rebuild || bailedRebuild) {
                clearIndices()
                pendingSelfDrops = IntArray(0)
                bailedRebuild = false
                val factors = problem.factors
                for (i in factors.indices) {
                    // The rebuild is O(factors), so it honors the presolve deadline. Bailing leaves a cleared
                    // (consistent) memo and forces a rebuild next firing; this firing subsumes nothing.
                    if ((i and REBUILD_CANCEL_POLL_MASK) == 0 && cancellation()) {
                        clearIndices()
                        pendingSelfDrops = IntArray(0)
                        bailedRebuild = true
                        return factors.asList()
                    }
                    cancellation.charge(SUBSUME_WORK_WEIGHT * (1L + factors[i].structuralKeyWeight))
                    process(SubsumeFactor(inc.liveIds[i], factors[i]), drops)
                }
            } else {
                for (id in pendingSelfDrops) retract(id)
                for (id in inc.droppedIds) retract(id)
                for (f in inc.addedFactors) {
                    cancellation.charge(SUBSUME_WORK_WEIGHT * (1L + f.factor.structuralKeyWeight))
                    process(f, drops)
                }
            }
            return if (drops.isEmpty()) problem.factors.asList() else
                problem.factors.filterIndexed { i, _ -> inc.liveIds[i] !in drops }
        }

        // Later phases also remove indexed occurrences; duplicates have no entry and retract as a no-op.
        fun setPendingSelfDrops(allDropped: IntArray) {
            pendingSelfDrops = allDropped
        }

        // Mirrors the from-scratch two-tier dedup, so the lazy single-survivor slot never computes a key.
        private fun registerP1(entry: SubsumeFactor): Boolean {
            val f = entry.factor
            val sk = shallowKey(f)
            val multi = shallowMulti[sk]
            if (multi != null) {
                val key = f.structuralKey()
                if (multi.containsKey(key)) return false
                multi[key] = entry
                return true
            }
            val single = shallowSingle[sk]
            if (single == null) {
                shallowSingle.put(sk, entry)
                return true
            }
            val singleKey = single.factor.structuralKey()
            val fKey = f.structuralKey()
            if (singleKey == fKey) return false
            shallowMulti.put(sk, hashMapOf(singleKey to single, fKey to entry))
            shallowSingle.remove(sk)
            return true
        }

        private fun deregisterP1(entry: SubsumeFactor) {
            val f = entry.factor
            val sk = shallowKey(f)
            val multi = shallowMulti[sk]
            if (multi != null) {
                val key = f.structuralKey()
                if (multi[key]?.id == entry.id) multi.remove(key)
                if (multi.size == 1) {
                    shallowSingle.put(sk, multi.values.first())
                    shallowMulti.remove(sk)
                }
            } else if (shallowSingle[sk]?.id == entry.id) {
                shallowSingle.remove(sk)
            }
        }

        // Only the first occurrence of a structural key enters the indices and contributes offers.
        private fun process(entry: SubsumeFactor, drops: IntHashSet) {
            if (!registerP1(entry)) {
                drops.add(entry.id)
                return
            }
            indexed.put(entry.id, entry)
            val f = entry.factor

            // Most factors (every Clause, Xor, plain global) offer nothing to the phase-2 buckets, so the
            // touched-key set and the row form are derived only once a factor actually offers.
            @Suppress("DoubleMutabilityForCollection") // deliberately lazily created on the first offer
            var touched: HashSet<TermKey>? = null
            forEachOffer(f) { key, bound, eq ->
                bucketFor(key).addBound(bound, eq)
                (touched ?: HashSet<TermKey>().also { touched = it }).add(key)
            }
            val keys = touched ?: return
            val member = dropForm(f)?.takeIf { !it.fromEq }
            for (key in keys) {
                val cand = if (member != null && member.key == key) entry else null
                resolve(buckets.getValue(key), cand, member?.bound ?: 0L, drops)
            }
        }

        // An occurrence added then dropped between firings, or dropped twice, has no indexed offers.
        private fun retract(id: Int) {
            val entry = indexed[id] ?: return
            indexed.remove(id)
            val f = entry.factor
            deregisterP1(entry)
            forEachOffer(f) { key, bound, eq -> buckets[key]?.removeBound(bound, eq) }
            forEachOffer(f) { key, _, _ -> buckets[key]?.let { if (it.rep?.id == id) it.rep = null } }
        }

        private fun bucketFor(key: TermKey): Bucket = buckets.getOrPut(key) { Bucket() }

        // Reconcile a bucket's representative against the (at most one) new drop-candidate [cand]: the
        // earliest row at the tightest bound survives when no equality dominates the bucket, mirroring the
        // from-scratch `keptRep` rule. The existing representative predates [cand], so it wins ties.
        private fun resolve(bucket: Bucket, cand: SubsumeFactor?, candBound: Long, drops: IntHashSet) {
            val t = bucket.tightest() ?: return
            val dominatedByEq = bucket.eqAtMin()
            val oldRep = bucket.rep
            val oldRepQualifies = oldRep != null && !dominatedByEq && bucket.repBound == t
            val candQualifies = cand != null && !dominatedByEq && candBound == t
            val newRep = when {
                oldRepQualifies -> oldRep
                candQualifies -> cand
                else -> null
            }
            if (oldRep != null && oldRep !== newRep) drops.add(oldRep.id)
            if (cand != null && cand !== newRep) drops.add(cand.id)
            bucket.rep = newRep
            if (newRep === cand) bucket.repBound = candBound
        }
    }

    private inline fun forEachOffer(f: Factor, action: (key: TermKey, bound: Long, eq: Boolean) -> Unit) {
        for (n in offerForms(f)) {
            action(n.key, n.bound, n.fromEq)
            n.opposite?.let { action(it.key, it.bound, it.fromEq) }
        }
    }

    // An AllDifferent over pairwise-disjoint `[min, max]` intervals can never prune, for every variant:
    // absence and excepted values only relax it. Hole-induced disjointness is not claimed.
    private fun isVacuousGlobal(factor: Factor, ranges: ColumnRanges): Boolean {
        if (factor !is AllDifferent || factor.vars.size < 2) return false
        // Disjointness is a claim about where each column can sit, so a column with an open side leaves
        // it unmade rather than tested against an invented endpoint.
        if (!ranges.allClosed(factor.vars)) return false
        var prevMax = Long.MIN_VALUE
        for (v in factor.vars.sortedBy { ranges.min(it) }) {
            if (ranges.min(v) <= prevMax) return false
            if (ranges.max(v) > prevMax) prevMax = ranges.max(v)
        }
        return true
    }

    private const val REBUILD_CANCEL_POLL_MASK = 0x3FF

    /**
     * Drops a knapsack `Σ wⱼ·lⱼ ≤ b` (positive weights) whose maximal activity, with each at-most-one clique
     * contributing only its largest weight, is already `≤ b`. A greedy cover gives a valid upper bound; a
     * looser one only misses drops.
     *
     * Only [maximalPersistentAmoCliques] (backed by [Cardinality] / [Clause], never a knapsack) are used: a
     * clique implied by a knapsack could justify dropping the very constraint it rests on.
     */
    private fun dropCliqueImpliedKnapsacks(factors: List<Factor>, cancellation: ExecutionControl): List<Factor> {
        val candidates = ArrayList<Pair<Int, LinearRow>>()
        for ((i, f) in factors.withIndex()) {
            // Clauses declare >= rows, so none can be a <= knapsack drop candidate.
            if (f is Clause) continue
            val row = (f.linearForm as? LinearForm.Conjunction)?.rows?.singleOrNull() ?: continue
            if (row.relation == LinearOp.LE && row.isLongUnconditional &&
                (0 until row.size).all { Term.isBool(row.ref(it)) && row.coeff(it) > 0L }
            ) {
                candidates.add(i to row)
            }
        }
        if (candidates.isEmpty()) return factors
        val cliques = maximalPersistentAmoCliques(factors, cancellation)
        if (cliques.isEmpty()) return factors
        val dropped = IntHashSet()
        for ((i, row) in candidates) {
            if (cliqueImpliesKnapsack(row, cliques)) dropped.add(i)
        }
        if (dropped.isEmpty()) return factors
        return factors.filterIndexed { i, _ -> i !in dropped }
    }

    private fun cliqueImpliesKnapsack(knapsack: LinearRow, cliques: List<Set<Int>>): Boolean = try {
        cliqueActivityWithinBound(knapsack, cliques)
    } catch (_: CheckedLongOverflowException) {
        false
    }

    private fun cliqueActivityWithinBound(knapsack: LinearRow, cliques: List<Set<Int>>): Boolean {
        // Candidate weights are > 0, so 0 doubles as the "literal not in the knapsack"
        // sentinel for [MutableIntLongMap.getOrDefault].
        val weightByLit = MutableIntLongMap(knapsack.size)
        for (i in 0 until knapsack.size) {
            val literal = Term.lit(knapsack.ref(i))
            weightByLit.put(literal, addExact(weightByLit.getOrDefault(literal, 0L), knapsack.coeff(i)))
        }
        val assigned = IntHashSet()
        var activity = 0L
        for (clique in cliques) {
            var maxW = 0L
            var any = false
            for (lit in clique) {
                if (lit in assigned) continue
                val w = weightByLit.getOrDefault(lit, 0L)
                if (w == 0L) continue
                any = true
                assigned.add(lit)
                if (w > maxW) maxW = w
            }
            if (any) activity = addExact(activity, maxW)
        }
        weightByLit.forEach { lit, weight -> if (lit !in assigned) activity = addExact(activity, weight) }
        return activity <= knapsack.bound
    }

    // [fromEq] marks an equality, which also contributes its [opposite] direction and is never dropped.
    private class IneqForm(val key: TermKey, val bound: Long, val fromEq: Boolean, val opposite: IneqForm? = null) {
        fun copyWithOpposite(opp: IneqForm) = IneqForm(key, bound, fromEq, opp)
    }

    private fun offerForms(f: Factor, scan: ScanMeter? = null): List<IneqForm> =
        f.impliedLinearRows.mapNotNull {
            visit(scan)
            rowForm(it, scan)
        }

    // A multi-row factor offers its rows but is never itself dropped as dominated.
    private fun dropForm(f: Factor, scan: ScanMeter? = null): IneqForm? =
        if (f.linearForm is LinearForm.Conjunction) {
            f.impliedLinearRows.singleOrNull()?.let { rowForm(it, scan) }
        } else {
            null
        }

    // Pure clauses stay out of the buckets so a clause-heavy model is a phase-2 no-op; clause subsumption
    // belongs to BVE.
    private fun rowForm(row: LinearRow, scan: ScanMeter?): IneqForm? {
        visit(scan)
        if (row.constants !is IntegerConstants) return null
        if (isPureClause(row, scan)) return null
        if (row.activator != LinearRow.ALWAYS || row.strict || row.bound == Long.MIN_VALUE ||
            (0 until row.size).any {
                visit(scan)
                Term.isReal(row.ref(it))
            } ||
            (0 until row.size).any {
                visit(scan)
                row.coeff(it) == Long.MIN_VALUE
            }
        ) {
            return null
        }
        val refs = IntArray(row.size) {
            visit(scan)
            row.ref(it)
        }
        val coeffs = LongArray(row.size) {
            visit(scan)
            row.coeff(it)
        }
        return when (row.relation) {
            LinearOp.LE -> reducedIneq(refs, coeffs, row.bound, fromEq = false, scan)

            LinearOp.GE -> reducedIneq(refs, negated(coeffs, scan), -row.bound, fromEq = false, scan)

            LinearOp.EQ -> reducedIneq(refs, coeffs, row.bound, fromEq = true, scan).copyWithOpposite(
                reducedIneq(refs, negated(coeffs, scan), -row.bound, fromEq = true, scan),
            )

            LinearOp.NE -> null
        }
    }

    private fun isPureClause(row: LinearRow, scan: ScanMeter?): Boolean {
        if (row.relation != LinearOp.GE || row.bound != 1L) return false
        for (k in 0 until row.size) {
            visit(scan)
            if (!Term.isBool(row.ref(k)) || row.coeff(k) != 1L) return false
        }
        return true
    }

    private fun negated(xs: LongArray, scan: ScanMeter? = null): LongArray =
        LongArray(xs.size) {
            visit(scan)
            -xs[it]
        }

    // GCD reduction lets proportional rows share a bucket. Flooring the bound is exact: the left side is a
    // multiple of `g`, so `Σ c·t ≤ b ⟺ Σ (c/g)·t ≤ ⌊b/g⌋`.
    private fun reducedIneq(
        refs: IntArray,
        coeffs: LongArray,
        bound: Long,
        fromEq: Boolean,
        scan: ScanMeter?,
    ): IneqForm {
        val g = PresolveShared.gcdOf(coeffs) { visit(scan) }
        return if (g <= 1L) {
            IneqForm(refKey(refs, coeffs, scan), bound, fromEq)
        } else {
            val reduced = LongArray(coeffs.size) {
                visit(scan)
                coeffs[it] / g
            }
            IneqForm(refKey(refs, reduced, scan), bound.floorDiv(g), fromEq)
        }
    }

    // `(ref, coeff)` pairs sorted by ref. Disjoint integer and Boolean ref tags keep the two kinds apart, and
    // a literal's polarity is part of its ref.
    private data class TermKey(val terms: List<Long>)

    private fun refKey(refs: IntArray, coeffs: LongArray, scan: ScanMeter?): TermKey {
        val terms = ArrayList<Long>(refs.size * 2)
        for (i in refs.indices.sortedBy {
            visit(scan)
            refs[it]
        }) {
            visit(scan)
            terms.add(refs[i].toLong())
            terms.add(coeffs[i])
        }
        return TermKey(terms)
    }

    // Order-invariant so full structural-key equality implies it: a Linear's key compares its terms as a
    // var→coeff map, not positionally. Collisions only fall through to the full-key comparison.
    private fun shallowKey(f: Factor, scan: ScanMeter? = null): Long {
        var vsum = 0L
        for (v in f.intVars) {
            visit(scan)
            vsum += splitmix64(v.toLong())
        }
        for (v in f.boolVars) {
            visit(scan)
            vsum += splitmix64(v.toLong() or BOOL_VAR_MARK)
        }
        return vsum * 31 + f.structuralKeyWeight
    }

    private fun fullKey(factor: Factor, scan: ScanMeter): StructuralKey {
        visit(scan, SUBSUME_WORK_WEIGHT * factor.structuralKeyWeight)
        return factor.structuralKey()
    }
}

// Work units per factor, term, key visit or domination test, relative to one simplex operation.
private const val SUBSUME_WORK_WEIGHT = 10L
