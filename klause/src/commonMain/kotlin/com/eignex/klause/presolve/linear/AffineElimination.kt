package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.IntegerConstants
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.LpOverflowException
import com.eignex.klause.lp.engine.addExact
import com.eignex.klause.lp.engine.mulExact
import com.eignex.klause.lp.engine.subExact
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.SharedIntOccurrence
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet

internal object AffineSingletons {

    /**
     * Affine variable elimination. Eliminates an integer variable `x` defined by an
     * `n`-term equality `c_x·x + Σ_j c_j·y_j = b` with a **unit** pivot coefficient `|c_x| = 1`, i.e.
     * `x = B + Σ_j A_j·y_j` where `A_j = −c_x·c_j` and `B = c_x·b`. The defining equality is dropped,
     * the affine relation is folded into every other [Linear] that mentions `x`, and bounds on the
     * `y_j` are added so `x` stays inside its declared domain; `x` becomes unconstrained and is
     * rebuilt from the solution via [AffineElimination.reconstruct].
     *
     * Two-term equalities are the common case (an alias or a one-partner definition); the `n`-term
     * generalisation projects out an *implied-free* variable defined by a longer sum (e.g. an
     * auxiliary `x = y1 + y2 − y3` used nowhere a global needs it). The unit-pivot restriction keeps
     * every folded coefficient integral unconditionally; a **non-unit** pivot is admitted only for an
     * implied-free (contained) `x` and only when `c_x` divides every other coefficient and the
     * bound, so `x = b/c_x + Σ_j (−c_j/c_x)·y_j` stays integral for *all* partner assignments. A
     * non-unit pivot that fails the divisibility test is left to the residue-class doubleton below.
     *
     * For the **alias** case `x = y` (`n = 2`, `A = 1`, `B = 0`) the substitution `x → y` is a plain
     * variable rename, applied to *every* factor via [Factor.remap] regardless of type.
     * Otherwise the relation folds into every other [Linear]; a **single-partner** `x = a·y + b`
     * additionally projects out of any non-linear factor that can represent the affine view via
     * [Factor.substituteAffine] (an Element index shift, a Table column rewrite). A multi-partner
     * `B + Σ A_j·y_j` only folds into [Linear] factors — a global keyed on `x`'s value as a sum can't
     * represent it. The contained slice (`x` in no other factor) is the zero-fold special case,
     * and is what lets an `n`-term definition be projected out.
     *
     * Variables in [objectiveIntVars] are never eliminated: the objective reads them directly and
     * the engine optimises over the presolved problem where an eliminated variable is unconstrained.
     */
    fun eliminateAffineSingletons(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
        sharedIntOcc: SharedIntOccurrence? = null,
        capWide: Boolean = false,
        incrementalTouchedVars: IntArray? = null,
        maxFactors: Int = AFFINE_MAX_FACTORS,
        pivotOrder: AffinePivotOrder = AffinePivotOrder.MARKOWITZ,
    ): PassDelta {
        if (problem.numIntVars == 0) return PassDelta()
        // Membership is tested per candidate variable, so the caller's boxed set is unpacked once here
        // rather than boxing an Int on every check.
        val objVars = IntHashSet().apply { for (v in objectiveIntVars) add(v) }
        // On instances with millions of factors the pass scans the whole set for candidates and can run
        // for several seconds while barely simplifying (e.g. Coprime/ItemsetMining: ~10M factors, net
        // factor change in the tens). Skipping it there keeps presolve bounded; it is sound (the
        // affine-defined variables simply stay and are solved directly) and, per a solve A/B over the
        // hakank suite, disabling affine did not change whether any instance solved. The threshold sits
        // far above what any non-giant model reaches, so ordinary instances are unaffected (byte-identical).
        if (problem.factors.size > maxFactors) return PassDelta()
        val eliminated = BooleanArray(problem.numIntVars)
        val subs = ArrayList<AffineSub>()
        // Before any fold the working set is byte-for-byte the pristine input, so the first candidate scan
        // can read the session's shared occurrence index directly (its CSR is in stable-id order). If no
        // candidate exists there, the pass is fruitless and returns without ever building the mutable
        // [WorkingSet] — skipping its O(occurrences) index copy on every barren firing. On a re-run the
        // engine supplies the variables the delta touched since the last firing: a candidate can only newly
        // arise on a touched variable (an untouched variable's factors, and hence its foldability, are
        // unchanged since the previous run reached its fixpoint), so the scan need only test the factors
        // those variables appear in — O(delta) instead of O(factors) on the common fruitless re-run.
        if (sharedIntOcc != null) {
            val seed = SeedOcc(problem.factors, sharedIntOcc)
            val hasCandidate = if (incrementalTouchedVars != null) {
                anyTouchedVarHeadsCandidate(
                    seed,
                    incrementalTouchedVars,
                    eliminated,
                    objVars,
                    capWide,
                    problem.requireFiniteIntDomains(),
                    cancellation,
                )
            } else {
                findAffineCandidate(seed, 0, eliminated, objVars, capWide, cancellation) != null ||
                    findResidueCandidate(
                        seed,
                        eliminated,
                        objVars,
                        problem.requireFiniteIntDomains(),
                        cancellation,
                    ) != null
            }
            if (!hasCandidate) return PassDelta()
        }
        // The working set holds the factors by stable id (tombstones for drops, appends for the folded
        // rewrites and bound rows) and a per-variable occurrence index maintained across eliminations.
        // The session-maintained index matches the pristine input factor order, so the first candidate
        // search reads it instead of rebuilding an O(factors) index; each fold then patches only the
        // occurrence entries of the rewritten factors rather than re-indexing the whole set.
        val ws = WorkingSet(problem.factors, eliminated.size, sharedIntOcc)
        // Each elimination rewrites only the factors mentioning the pivot and appends bound rows, so the
        // per-elimination cost is O(occurrences of x) rather than O(factors). The candidate scan still
        // walks the live factors in order (the elimination order is selection-order-sensitive), but the
        // membership predicates it runs are answered from the incremental occurrence index.
        // Poll the budget so it stops with the eliminations made so far — sound (the remaining
        // affine-defined variables simply stay and are solved directly).
        // Which pivot to take next is a policy ([AffinePivotOrder]), because elimination cost depends on
        // the order as much as on the set: folding the cheap pivots first shrinks the graph, so folds that
        // would have been expensive become cheap or stop existing. The gates below (fill-in budget, absorb
        // cap) can only refuse a fold; they cannot defer one.
        val order = newPivotOrder(pivotOrder, ws, eliminated, objVars, capWide, cancellation)
        // Cumulative substitution fill-in (Σ pivot-degree · substituted-terms). A dense chain of wide
        // folds is superlinear and can dominate presolve on large models where it barely simplifies;
        // once the budget is spent the loop stops, leaving the remaining affine-defined variables to be
        // solved directly (sound). The budget is far above what productive models spend.
        var fillIn = 0L
        while (!cancellation()) {
            val cand = order.next() ?: break
            fillIn += ws.degreeOf(cand.x).toLong() * cand.termVars.size
            order.onFolded(foldOutVariable(problem, ws, cand))
            eliminated[cand.x] = true
            subs.add(AffineSub(cand.x, cand.constTerm, cand.termVars, cand.termCoeffs))
            if (fillIn > AFFINE_FILL_IN_BUDGET) break
        }
        // Residue-class doubletons: a 2-term `a·x + b·y = c` with no unit pivot, where `x` is
        // contained, determines `x = (c − b·y)/a` only for the `y` values keeping it an in-domain
        // integer. Restrict `y` to those values (a domain modification, not a folded factor) and
        // reconstruct `x` with the divisor. Runs after the unit-pivot loop, so a residue partner `y`
        // is always a surviving variable.
        val domains = problem.requireFiniteIntDomains().copyOf()
        while (!cancellation()) {
            val r = findResidueCandidate(ws, eliminated, objVars, domains, cancellation) ?: break
            ws.drop(r.defIdx)
            domains[r.y] = r.restrictedY
            eliminated[r.x] = true
            subs.add(AffineSub(r.x, r.constTerm, intArrayOf(r.y), longArrayOf(r.coeffY), divisor = r.divisor))
        }
        if (subs.isEmpty()) return PassDelta()
        // The eliminations rebuilt the factor list in place; recover the delta against the input by
        // identity — every survivor is === an input factor, so the drops are the inputs absent from
        // [factors] and the adds are the factors [foldOutVariable] introduced (rewrites + domain bounds).
        return PresolveShared.identityDelta(
            problem.factors,
            ws.liveFactors(),
            domains,
            AffineElimination(subs)::reconstruct,
        )
    }

    /**
     * The next pivot to fold out, and the bookkeeping that keeps that choice cheap across eliminations.
     * The two orders differ only in *which* admissible candidate they hand back — every candidate is
     * vetted by the same [candidateInFactor] gate, so no order can produce an unsound fold, and stopping
     * early is always sound (an unfolded variable simply stays and is solved directly).
     */
    private interface PivotOrder {

        /** The next pivot to fold, or `null` when no admissible candidate remains. */
        fun next(): AffineCandidate?

        /** Record that the fold just made rewrote factors from stable id [minRewrittenId] upward. */
        fun onFolded(minRewrittenId: Int)
    }

    private fun newPivotOrder(
        policy: AffinePivotOrder,
        ws: FactorOcc,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        cancellation: Cancellation,
    ): PivotOrder = when (policy) {
        AffinePivotOrder.STABLE_ID -> StableIdOrder(ws, eliminated, objectiveIntVars, capWide, cancellation)
        AffinePivotOrder.MARKOWITZ -> MarkowitzOrder(ws, eliminated, objectiveIntVars, capWide, cancellation)
    }

    /**
     * Lowest-stable-id-first: the first admissible candidate at or after a resume point. A fold only
     * changes the content of the factors it rewrites, so no factor below their minimum id can newly
     * become a candidate; resuming from that minimum instead of restarting at 0 turns the loop from
     * O(eliminations · factors) into O(factors + Σ rewrites). Every factor below the resume point is a
     * confirmed non-candidate (unchanged since it was last examined), so the selection order — and the
     * eliminations — match what a full rebuild would produce.
     */
    private class StableIdOrder(
        private val ws: FactorOcc,
        private val eliminated: BooleanArray,
        private val objectiveIntVars: IntHashSet,
        private val capWide: Boolean,
        private val cancellation: Cancellation,
    ) : PivotOrder {
        private var scanFrom = 0

        override fun next(): AffineCandidate? =
            findAffineCandidate(ws, scanFrom, eliminated, objectiveIntVars, capWide, cancellation)

        override fun onFolded(minRewrittenId: Int) {
            scanFrom = minRewrittenId
        }
    }

    /**
     * Cheapest-fill-first, the classic Markowitz rule for sparse elimination: prefer the pivot whose
     * substitution creates the fewest new nonzeros, `(r − 1)(c − 1)` for a pivot in `r` rows and a row of
     * `c` nonzeros — here [markowitzCost]. Ordering is a different lever from the fill-in gates: a
     * threshold judges a fold in isolation and must refuse it, while an order defers it, and a deferred
     * fold is often cheap by the time it comes round because the folds ahead of it shrank the graph.
     *
     * Costs go stale as folds change the degrees of variables in candidates already queued. Rather than
     * invalidate exactly — measured to cost as much as it saves on a dense constraint graph — the heap
     * keeps possibly-stale keys and revalidates on pop: a candidate whose true cost exceeds its key is
     * re-queued at the true cost, so a popped candidate is always the cheapest known. A key that is too
     * *low* only degrades the heuristic, never correctness, which is why an approximate order is enough.
     */
    private class MarkowitzOrder(
        private val ws: FactorOcc,
        private val eliminated: BooleanArray,
        private val objectiveIntVars: IntHashSet,
        private val capWide: Boolean,
        private val cancellation: Cancellation,
    ) : PivotOrder {
        private val heap = PivotHeap()
        private var seeded = false

        /** Promotions already queued. [FactorOcc.promotedAt] is append-only, so the tail is exactly the
         *  ids that folds turned into candidates since the last look. */
        private var promotionsQueued = 0

        override fun next(): AffineCandidate? {
            if (!seeded) {
                seed()
                seeded = true
            }
            queuePromotions()
            var polled = 0
            // Give up after this many candidates in a row fail the gate, the counterpart of the stable-id
            // scan's [AFFINE_SCAN_ABORT]: on a large factor set with no exploitable affine structure the
            // gate is O(occurrences) per candidate and grinding the whole heap is pure cost. Sound — an
            // unfound pivot simply stays. Reset on every accepted candidate, so a productive model never
            // reaches it.
            var rejected = 0
            while (!heap.isEmpty()) {
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
                if (rejected >= AFFINE_SCAN_ABORT) return null
                val key = heap.peekCost()
                val id = heap.popId()
                // Inadmissible ids are dropped, not re-queued. Re-queueing them after each fold was tried
                // and changed neither the eliminations made nor the wall time on any measured instance.
                val cand = candidateInFactor(ws, id, eliminated, objectiveIntVars, capWide, cancellation)
                if (cand == null) {
                    rejected++
                    continue
                }
                val cost = markowitzCost(ws, cand)
                // Stale key. Re-queue at the true cost and take the new minimum; the key is now exact
                // for this working set, so the same id cannot be re-queued twice in one call.
                if (cost > key) {
                    heap.push(cost, id)
                    continue
                }
                rejected = 0
                return cand
            }
            return null
        }

        override fun onFolded(minRewrittenId: Int) {
            // Queued costs are now stale, which the pop-time revalidation absorbs. Newly promoted ids are
            // picked up by [queuePromotions] on the next call.
        }

        /** Seed every pivot-candidate id at its [estimatedCost], counting them first so the heap is sized
         *  once rather than doubled into — the seed is the largest it ever gets. */
        private fun seed() {
            var count = 0
            var id = ws.nextEqId(0)
            while (id < ws.size) {
                count++
                id = ws.nextEqId(id + 1)
            }
            heap.reserve(count)
            id = ws.nextEqId(0)
            while (id < ws.size) {
                heap.push(estimatedCost(id), id)
                id = ws.nextEqId(id + 1)
            }
            promotionsQueued = ws.promotedCount()
        }

        private fun queuePromotions() {
            val n = ws.promotedCount()
            while (promotionsQueued < n) {
                val id = ws.promotedAt(promotionsQueued++)
                heap.push(estimatedCost(id), id)
            }
        }

        /**
         * The `(r − 1)(c − 1)` the row at [id] is expected to cost. [candidateInFactor] takes the *first*
         * admissible pivot in row order rather than the cheapest, so mirroring that choice — the first
         * unit-coefficient variable that is neither eliminated nor in the objective — is exact for the
         * unit-pivot rows that dominate, and costs O(arity) instead of the gate's occurrence walk.
         *
         * Getting this close matters more than it looks: the key only has to be *a* bound for the lazy
         * revalidation to be correct, but every under-estimate costs a pop, a re-push and a second pop.
         * A slack bound made 90% of pops on Coprime-23 re-pushes.
         */
        private fun estimatedCost(id: Int): Long {
            val f = ws.factorAt(id) ?: return 0L
            val vars = f.intVars
            if (vars.size < 2) return 0L
            val row = (f as? Linear)?.integerConstants
            if (row != null) {
                for (xi in f.vars.indices) {
                    val x = f.vars[xi]
                    if (eliminated[x] || x in objectiveIntVars) continue
                    val cx = row.coeff(xi)
                    if (cx != 1L && cx != -1L) continue
                    return (ws.degreeOf(x) - 1).toLong() * (f.vars.size - 1)
                }
            }
            // No unit pivot to mirror; fall back to the cheapest conceivable one, which only ever
            // under-estimates and is corrected on pop.
            var minDegree = Int.MAX_VALUE
            for (v in vars) {
                val d = ws.degreeOf(v)
                if (d < minDegree) minDegree = d
            }
            return (minDegree - 1).toLong() * (vars.size - 1)
        }
    }

    /** The fill a fold on [cand]'s pivot would create: `(r − 1)(c − 1)` over the `r` rows mentioning the
     *  pivot and the `c` nonzeros of its defining row. The same quantity the [WIDE_FILL_IN] gate tests. */
    private fun markowitzCost(ws: FactorOcc, cand: AffineCandidate): Long =
        (ws.degreeOf(cand.x) - 1).toLong() * cand.termVars.size

    /**
     * A binary min-heap of `(cost, stable id)` packed into one `Long` — cost in the high word (saturated
     * to fit), id in the low. Packing makes the comparison a single `Long` compare *and* breaks cost ties
     * by ascending id, so the order is deterministic rather than dependent on heap shape.
     */
    private class PivotHeap {
        private var entries = LongArrayList(0)

        fun isEmpty(): Boolean = entries.size == 0

        /** Pre-size the backing store for [n] pushes. */
        fun reserve(n: Int) {
            if (n > 0 && entries.size == 0) entries = LongArrayList(n)
        }

        fun push(cost: Long, id: Int) {
            entries.add(pack(cost, id))
            var i = entries.size - 1
            while (i > 0) {
                val parent = (i - 1) / 2
                if (entries[parent] <= entries[i]) break
                swap(i, parent)
                i = parent
            }
        }

        fun peekCost(): Long = entries[0] ushr ID_BITS

        /** Remove and return the minimum entry's stable id. */
        fun popId(): Int {
            val top = entries[0]
            val last = entries.size - 1
            entries[0] = entries[last]
            entries.truncateTo(last)
            siftDown()
            return (top and ID_MASK).toInt()
        }

        private fun siftDown() {
            var i = 0
            while (true) {
                val left = 2 * i + 1
                if (left >= entries.size) break
                val right = left + 1
                val child = if (right < entries.size && entries[right] < entries[left]) right else left
                if (entries[i] <= entries[child]) break
                swap(i, child)
                i = child
            }
        }

        private fun swap(a: Int, b: Int) {
            val t = entries[a]
            entries[a] = entries[b]
            entries[b] = t
        }

        private fun pack(cost: Long, id: Int): Long = (cost.coerceIn(0L, MAX_COST) shl ID_BITS) or id.toLong()

        private companion object {
            /** Stable ids index the working set, so 32 bits always cover them and leave 31 for the cost. */
            const val ID_BITS = 32
            const val ID_MASK = 0xFFFFFFFFL
            const val MAX_COST = Int.MAX_VALUE.toLong()
        }
    }

    /** The `Int` value window; a single-partner global's affine view (an index/position shift) is only
     *  representable when the fold's scale and offset land in it. */
    private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    /** Cap on a residue partner's domain span: scanning each value to build the restricted domain is
     *  O(span), and a residue class on a very wide domain would flood it with holes, so skip above it. */
    private const val RESIDUE_DOMAIN_SPAN_CAP = 1024

    // In an underdetermined model (see [PresolveContext.affineUnderdetermined]), defer a fold whose fill-in
    // (pivot degree × substituted terms) exceeds this — it is the runaway dense kind that inflates a few
    // wide rows without simplifying. The variable stays (sound, solved directly).
    private const val WIDE_FILL_IN = 64

    // Cap on how many folds a single linear row may absorb, applied in every model. Folding a pivot into a
    // row rebuilds and re-coalesces the whole row; a row that keeps absorbing folds from successive pivots
    // grows monotonically, so the k-th fold into it costs O(k) — O(folds²) churn that also hands the solver a
    // denser problem rather than a simpler one (numbrix accumulates one row from 215 to 4560 terms over ~80
    // folds). Once a row has absorbed this many folds, leave any further pivot that would rewrite it unfolded
    // (sound — the variable stays and is solved directly), which bounds each row's growth. Set well above the
    // handful of folds a productively-eliminated variable draws so ordinary models are untouched; only a row
    // that is a fold sink — the pathological accumulator — is capped.
    private const val FOLD_ABSORB_CAP = 16

    /** Cumulative substitution-fill-in budget across the whole pass (Σ pivot-degree · substituted-terms).
     *  [FOLD_ABSORB_CAP] bounds any single row's growth, but a model with *many* moderately-folded rows can
     *  still spend seconds in aggregate (an SMT-LIB SharedMemory instance runs ~6s under the per-row cap
     *  alone); this bounds the total work. Once spent, the loop stops and the remaining affine-defined
     *  variables are solved directly (sound). Set well above what productive models spend. */
    private const val AFFINE_FILL_IN_BUDGET = 300_000L

    /** Skip the whole affine pass on instances with more than this many factors: the O(factors) candidate
     *  scan dominates presolve on multi-million-factor giants (where it also barely simplifies), and the
     *  pass is optional (skipping is sound). Far above any non-giant model, so ordinary instances run it
     *  unchanged. */
    private const val AFFINE_MAX_FACTORS = 1_000_000

    /** A residue-class doubleton `a·x + b·y = c` (no unit pivot) at [defIdx]: `x` is contained and
     *  reconstructed as `(constTerm + coeffY·y) / divisor` over the [restrictedY] partner domain. */
    private class ResidueCandidate(
        val defIdx: Int,
        val x: Int,
        val y: Int,
        val constTerm: Long,
        val coeffY: Long,
        val divisor: Long,
        val restrictedY: IntDomain,
    )

    private fun findResidueCandidate(
        ws: FactorOcc,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        domains: Array<IntDomain>,
        cancellation: Cancellation = Cancellation.Never,
    ): ResidueCandidate? {
        var polled = 0
        var di = ws.nextEqId(0)
        while (di < ws.size) {
            if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            residueCandidateInFactor(
                ws,
                di,
                eliminated,
                objectiveIntVars,
                domains,
            )?.let { return it }
            di = ws.nextEqId(di + 1)
        }
        return null
    }

    /** The residue-class doubleton the 2-term equality at stable id [di] defines, or `null` if it defines
     *  none. The per-factor body of [findResidueCandidate], reused by the touched-variable re-scan. */
    private fun residueCandidateInFactor(
        ws: FactorOcc,
        di: Int,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        domains: Array<IntDomain>,
    ): ResidueCandidate? {
        val f = ws.factorAt(di) ?: return null
        if (f !is Linear || f.op != LinearOp.EQ || f.vars.size != 2) return null
        val row = f.integerConstants ?: return null
        val fBound = row.bound
        for (xi in 0..1) {
            val x = f.vars[xi]
            val y = f.vars[1 - xi]
            val a = row.coeff(xi)
            val b = row.coeff(1 - xi)
            // The unit-pivot loop already ran, so a remaining 2-term EQ has no unit coefficient;
            // guard anyway. `x` must be contained (a non-unit fold can't stay integral) and free.
            // `y`'s domain is restricted below, so it too must stay clear of the objective — the
            // pass leaves every objective variable untouched.
            if (a == 0L || a == 1L || a == -1L || eliminated[x] || eliminated[y] || x == y) continue
            if (x in objectiveIntVars || y in objectiveIntVars) continue
            if (!ws.isContained(di, x)) continue
            val domY = domains[y]
            if (domY.max - domY.min > RESIDUE_DOMAIN_SPAN_CAP) continue
            val restricted = restrictPartnerDomain(domY, domains[x], a, b, fBound) ?: continue
            return ResidueCandidate(
                di,
                x,
                y,
                constTerm = fBound,
                coeffY = -b,
                divisor = a,
                restrictedY = restricted,
            )
        }
        return null
    }

    /** The partner domain restricted to the `y` values for which `x = (c − b·y)/a` is an integer
     *  inside [domX], or `null` if no such `y` exists (leave the constraint for propagation to fail). */
    private fun restrictPartnerDomain(domY: IntDomain, domX: IntDomain, a: Long, b: Long, c: Long): IntDomain? {
        val valid = LongArrayList()
        for (y in domY.min..domY.max) {
            if (y !in domY) continue
            // `c − b·y` can exceed 64 bits for a wide partner coefficient and a high-magnitude `y`; such a
            // `y` admits no representable `x`, so drop it from the restricted partner domain.
            val num = try {
                subExact(c, mulExact(b, y))
            } catch (_: LpOverflowException) {
                continue
            }
            if (num % a != 0L) continue
            val x = num / a
            if (x in domX) valid.add(y)
        }
        if (valid.isEmpty()) return null
        var d = domY.withMinAtLeast(valid[0]).withMaxAtMost(valid.last())
        val keep = LongHashSet()
        valid.forEach { keep.add(it) }
        for (y in valid[0]..valid.last()) if (y !in keep && y in d) d = d.excludeValue(y)
        return d
    }

    /** An `EQ` [Linear] at [defIdx] defining `x = constTerm + Σ termCoeffs·termVars` (unit pivot). The
     *  other occurrences of `x` are either all foldable (Linear) or — for the alias case `x = y` —
     *  substituted via [Factor.remap] into any factor type. */
    private class AffineCandidate(
        val defIdx: Int,
        val x: Int,
        val constTerm: Long,
        val termVars: IntArray,
        val termCoeffs: LongArray,
        val isAlias: Boolean,
    )

    private fun findAffineCandidate(
        ws: FactorOcc,
        start: Int,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        cancellation: Cancellation = Cancellation.Never,
    ): AffineCandidate? {
        // The scan walks the live factors in stable-id order — the same order a fresh compacted list would
        // present — so it picks candidates in the identical sequence a full rebuild would. Give up after
        // [AFFINE_SCAN_ABORT] consecutive non-candidates: on a huge factor set with no exploitable affine
        // structure here (e.g. DiamondFree's ~490k decomposed constraints, none a foldable equality — the
        // pass folds nothing), scanning to the end is pure fruitless cost. Sound: an unfound pivot stays.
        val limit = minOf(ws.size.toLong(), start.toLong() + AFFINE_SCAN_ABORT).toInt()
        var polled = 0
        var di = ws.nextEqId(start)
        while (di < limit) {
            // Poll the presolve deadline inside the scan, not only between folds: a single scan over a large
            // wide-row factor set (Coprime) can run for seconds, so without this the budget cannot bound it.
            // Count iterations (a dense counter) rather than keying on [di], which a candidate index makes
            // sparse. Giving up returns the folds made so far — sound (an unfound pivot simply stays).
            if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            candidateInFactor(
                ws,
                di,
                eliminated,
                objectiveIntVars,
                capWide,
                cancellation,
            )?.let { return it }
            di = ws.nextEqId(di + 1)
        }
        return null
    }

    /** Poll the cancellation once per this many scanned factors (a power-of-two mask for a cheap check). */
    private const val CANCEL_POLL_MASK = 0x3FF

    /** Consecutive non-candidate factors [findAffineCandidate] scans before giving up. Set well above the
     *  gap between successive pivots in any model where affine is productive (which cluster near the top),
     *  so ordinary instances scan to completion unchanged; only a fruitless walk over a giant factor set is
     *  cut short. */
    private const val AFFINE_SCAN_ABORT = 200_000

    /** The affine candidate the equality at stable id [di] defines (a pivot with unit or divisible
     *  coefficient and foldable other occurrences), or `null` if it defines none. The per-factor body of
     *  [findAffineCandidate], reused by the touched-variable re-scan so a fruitless re-run need only test
     *  the factors mentioning a variable the delta changed. The per-candidate "where else does x occur"
     *  checks are O(occurrences-of-x) off the occurrence index, not a fresh O(factors) scan. */
    private fun candidateInFactor(
        ws: FactorOcc,
        di: Int,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        cancellation: Cancellation = Cancellation.Never,
    ): AffineCandidate? {
        val f = ws.factorAt(di) ?: return null
        if (f !is Linear || f.op != LinearOp.EQ || f.vars.size < 2) return null
        val row = f.integerConstants ?: return null
        for (xi in f.vars.indices) {
            // A single wide row can carry thousands of pivot candidates each running an O(occurrences)
            // overflow check, so poll the deadline between them (and inside the check below).
            if ((xi and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            val x = f.vars[xi]
            val cx = row.coeff(xi)
            if (eliminated[x] || x in objectiveIntVars) continue
            // The substitution `x = (bound − Σ c_j·y_j) / c_x` stays integral for *every*
            // assignment of the partners only when `c_x` divides each `c_j` and the bound — for a
            // unit pivot trivially, and for a non-unit pivot exactly when `x` is implied-free
            // (contained in this equality alone) and `c_x | gcd(c_j, bound)`. A
            // non-unit pivot that fails the divisibility test would fold non-integral coefficients,
            // so it is left for the residue-class doubleton pass or for propagation.
            val isUnit = cx == 1L || cx == -1L
            if (!isUnit && !dividesAllPartnersAndBound(f.vars, row, xi)) continue
            if (!isUnit && !ws.isContained(di, x)) continue
            // x = B + Σ A_j·y_j, with B = bound / c_x and A_j = −c_j / c_x for the other terms y_j;
            // for a unit pivot the divisions are exact by definition.
            val termVars = IntArray(f.vars.size - 1)
            val termCoeffs = LongArray(f.vars.size - 1)
            var w = 0
            var partnerEliminated = false
            for (j in f.vars.indices) {
                if (j == xi) continue
                if (eliminated[f.vars[j]]) partnerEliminated = true
                termVars[w] = f.vars[j]
                termCoeffs[w] = -row.coeff(j) / cx
                w++
            }
            if (partnerEliminated) continue
            val constTerm = row.bound / cx
            // The alias case (n = 2, A = 1, B = 0, i.e. x = y) substitutes into ANY factor via
            // remap; otherwise x must occur only in foldable Linear factors. A contained non-unit
            // pivot has no other occurrences, so `otherOccurrencesAllLinear` holds vacuously.
            val isAlias = termVars.size == 1 && termCoeffs[0] == 1L && constTerm == 0L
            if (isAlias && ws.aliasOverflowsLong(di, x, termVars[0])) continue
            // Defer a fold that would inflate rather than simplify (leave the variable). Aliases are pure
            // renames that never inflate, so they always proceed. Otherwise skip a pivot whose fold would
            // rewrite a row that has already absorbed [FOLD_ABSORB_CAP] folds (bounding the accumulating-row
            // pathology), and additionally cap total fill-in at the tighter [WIDE_FILL_IN] in underdetermined
            // models.
            if (!isAlias) {
                if (ws.anyOtherLinearAtAbsorbCap(di, x)) continue
                if (capWide && (ws.degreeOf(x) - 1).toLong() * termVars.size > WIDE_FILL_IN) continue
                // Leave x un-eliminated if folding it would overflow 64-bit arithmetic in some row it
                // rewrites — sound (x stays, solved directly) and avoids a wrong wrapped coefficient.
                if (ws.foldOverflowsLong(di, x, termVars, termCoeffs, constTerm, cancellation)) continue
            }
            // A single-partner affine `x = a·y + b` can also be projected out of non-linear globals
            // that absorb the affine view (via Factor.substituteAffine); a multi-partner relation
            // only folds into Linear factors. The global's affine view is an index/position shift in
            // Int space, so a scale or offset beyond Int range takes the Linear-fold path instead.
            val singlePartnerSubstitutable = termVars.size == 1 &&
                termCoeffs[0] in INT_RANGE && constTerm in INT_RANGE &&
                ws.otherOccurrencesAffineSubstitutable(di, x, termCoeffs[0].toInt(), constTerm.toInt(), termVars[0])
            if (isAlias || ws.otherOccurrencesAllLinear(di, x) || singlePartnerSubstitutable) {
                return AffineCandidate(di, x, constTerm, termVars, termCoeffs, isAlias)
            }
        }
        return null
    }

    /** Whether any variable in [touchedVars] heads a unit-pivot or residue candidate, testing only the
     *  factors those variables appear in (deduplicated). A re-run's new candidates are confined to the
     *  touched variables, so a `false` result means the pass is fruitless this firing — byte-identical to
     *  the full scan, which would also find nothing. `true` falls back to the full elimination loop, whose
     *  stable-id scan order the O(delta) test cannot reproduce. */
    private fun anyTouchedVarHeadsCandidate(
        seed: SeedOcc,
        touchedVars: IntArray,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        domains: Array<IntDomain>,
        cancellation: Cancellation = Cancellation.Never,
    ): Boolean {
        val checked = IntHashSet()
        var polled = 0
        for (x in touchedVars) {
            val degree = seed.degreeOf(x)
            for (k in 0 until degree) {
                val di = seed.occurrenceAt(x, k)
                if (!checked.add(di)) continue
                // Poll the deadline: this re-scan is O(touched · occurrences) and each candidate test walks
                // a row, so on a wide-row model it must be interruptible. Bailing reports "no candidate", so
                // affine skips this firing — sound (a partial pass only forgoes reduction).
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return false
                if (candidateInFactor(
                        seed,
                        di,
                        eliminated,
                        objectiveIntVars,
                        capWide,
                        cancellation,
                    ) != null
                ) {
                    return true
                }
                if (residueCandidateInFactor(seed, di, eliminated, objectiveIntVars, domains) != null) return true
            }
        }
        return false
    }

    /** Whether the pivot coefficient at [xi] divides every other coefficient and the bound of the row, so
     *  substituting out the pivot variable keeps all folded coefficients and the constant term integral. */
    private fun dividesAllPartnersAndBound(vars: IntArray, row: IntegerConstants, xi: Int): Boolean {
        val cx = row.coeff(xi)
        if (cx == 0L) return false
        if (row.bound % cx != 0L) return false
        for (j in vars.indices) if (j != xi && row.coeff(j) % cx != 0L) return false
        return true
    }

    /**
     * The read-only occurrence queries the candidate scans ([findAffineCandidate], [findResidueCandidate])
     * run: walk the factors by stable id and, per pivot, test where else the variable occurs. Both the
     * mutable [WorkingSet] and the pristine-input [SeedOcc] answer these, so a scan can run over the
     * session's shared occurrence index without first materialising a mutable working set.
     */
    private interface FactorOcc {
        val size: Int
        fun factorAt(id: Int): Factor?
        fun degreeOf(x: Int): Int
        fun isContained(defIdx: Int, x: Int): Boolean
        fun otherOccurrencesAllLinear(defIdx: Int, x: Int): Boolean
        fun otherOccurrencesAffineSubstitutable(defIdx: Int, x: Int, scale: Int, offset: Int, replacement: Int): Boolean

        /** Whether any [Linear] factor other than [defIdx] that mentions [x] has already absorbed
         *  [FOLD_ABSORB_CAP] folds — a fold on [x] would rewrite that row once more. Always `false` before any
         *  fold (the pristine seed), so it only ever defers a pivot targeting an established fold sink. */
        fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean

        /** How many ids a rewrite has *promoted* into the pivot-candidate set so far. The promotion list is
         *  append-only, so a reader that remembers this count picks up later promotions from its tail
         *  ([promotedAt]) instead of re-walking every candidate id. Zero on the pristine seed, which
         *  rewrites nothing. */
        fun promotedCount(): Int

        /** The [k]-th promoted stable id (`0 until promotedCount()`). */
        fun promotedAt(k: Int): Int

        /** Whether folding the pivot `x = constTerm + Σ termCoeffs·termVars` into any Linear row that
         *  mentions [x] (other than [defIdx]) would overflow 64-bit arithmetic. When it would, the
         *  candidate is skipped and `x` is left un-eliminated (sound) rather than wrapping a coefficient. */
        fun foldOverflowsLong(
            defIdx: Int,
            x: Int,
            termVars: IntArray,
            termCoeffs: LongArray,
            constTerm: Long,
            cancellation: Cancellation = Cancellation.Never,
        ): Boolean

        /** Whether renaming [x] to [replacement] would coalesce an integer linear row past [Long]. */
        fun aliasOverflowsLong(defIdx: Int, x: Int, replacement: Int): Boolean

        /** The smallest stable id `>= from` that could head a pivot — a live [Linear] equality of arity
         *  `>= 2` — or [size] if none remains, in ascending order. Lets the candidate scan skip the
         *  inequality/global bulk (which can never be a candidate) instead of testing every id, while
         *  still visiting the identical candidates in the identical order a walk over every id would: a
         *  fold that promotes a row into the candidate class is tracked, and a dropped or degraded id may
         *  still be returned and is re-rejected by the candidate gate. */
        fun nextEqId(from: Int): Int
    }

    /** Whether [f] could head an affine or residue pivot: a [Linear] equality of arity >= 2. */
    private fun isEqCand(f: Factor?): Boolean =
        f is Linear && f.integerConstants != null && f.op == LinearOp.EQ && f.vars.size >= 2

    private fun aliasOverflowsLong(factor: Factor?, x: Int, replacement: Int): Boolean {
        val (vars, coefficients) = when (factor) {
            is Linear -> factor.integerConstants?.let { factor.vars to it }
            is ReifiedLinear -> factor.integerConstants?.let { factor.vars to it }
            else -> null
        } ?: return false
        var xCoeff = 0L
        var replacementCoeff = 0L
        for (i in vars.indices) {
            when (vars[i]) {
                x -> xCoeff = coefficients.coeff(i)
                replacement -> replacementCoeff = coefficients.coeff(i)
            }
        }
        val sum = xCoeff + replacementCoeff
        return ((xCoeff xor sum) and (replacementCoeff xor sum)) < 0L
    }

    /** Stable ids (ascending) of the pristine [factors] that could ever head an affine/residue pivot. */
    private fun eqPivotIds(factors: Array<Factor>): IntArray {
        val ids = IntArrayList(0)
        for (id in factors.indices) if (isEqCand(factors[id])) ids.add(id)
        return ids.toIntArray()
    }

    /** Index of the first entry of the ascending [eqIds] that is `>= from` (`eqIds.size` if none). */
    private fun lowerBound(eqIds: IntArray, from: Int): Int {
        var lo = 0
        var hi = eqIds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (eqIds[mid] < from) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * A read-only [FactorOcc] over the pristine input [factors] and the session's [SharedIntOccurrence],
     * whose CSR is in stable-id order so its dense indices *are* the ids into [factors]. Before any fold
     * the working set equals this, so the first candidate scan reads it directly — a fruitless pass (no
     * candidate) returns without ever building the mutable [WorkingSet]'s O(occurrences) index.
     */
    private class SeedOcc(private val factors: Array<Factor>, private val occ: SharedIntOccurrence) : FactorOcc {
        override val size: Int get() = factors.size
        override fun factorAt(id: Int): Factor = factors[id]
        override fun degreeOf(x: Int): Int = occ.offsets[x + 1] - occ.offsets[x]

        // Built lazily: the incremental touched-variable path never scans by id, so a fruitless re-run
        // that returns early over the seed pays nothing for it.
        private val eqIds by lazy { eqPivotIds(factors) }
        override fun nextEqId(from: Int): Int {
            val p = lowerBound(eqIds, from)
            return if (p < eqIds.size) eqIds[p] else factors.size
        }

        /** The stable id of the [k]-th factor mentioning [x] (`0 until degreeOf(x)`) — lets the
         *  touched-variable re-scan visit only the factors a changed variable appears in. */
        fun occurrenceAt(x: Int, k: Int): Int = occ.flat[occ.offsets[x] + k]

        override fun isContained(defIdx: Int, x: Int): Boolean {
            for (k in occ.offsets[x] until occ.offsets[x + 1]) if (occ.flat[k] != defIdx) return false
            return true
        }

        override fun otherOccurrencesAllLinear(defIdx: Int, x: Int): Boolean {
            for (k in occ.offsets[x] until occ.offsets[x + 1]) {
                val id = occ.flat[k]
                val f = factors[id]
                // A continuous (real-bearing) Linear folds through the real double view, not the integer
                // path this pass rewrites, so it is not "plain linear" for elimination purposes.
                if (id != defIdx && (f !is Linear || f.integerConstants == null)) return false
            }
            return true
        }

        override fun foldOverflowsLong(
            defIdx: Int,
            x: Int,
            termVars: IntArray,
            termCoeffs: LongArray,
            constTerm: Long,
            cancellation: Cancellation,
        ): Boolean {
            var polled = 0
            val termsSmall = termsFitHalfLong(constTerm, termCoeffs)
            for (k in occ.offsets[x] until occ.offsets[x + 1]) {
                // A high-degree pivot's overflow check walks every row it mentions; poll so one candidate's
                // check cannot outrun the budget. Treat a bail as "would overflow" — skipping the fold is sound.
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return true
                val id = occ.flat[k]
                val f = factors[id]
                if (id != defIdx && f is Linear && f.integerConstants != null && foldRowOverflowsLong(
                        f,
                        x,
                        termVars,
                        termCoeffs,
                        constTerm,
                        termsSmall,
                    )
                ) {
                    return true
                }
            }
            return false
        }

        override fun aliasOverflowsLong(defIdx: Int, x: Int, replacement: Int): Boolean {
            for (k in occ.offsets[x] until occ.offsets[x + 1]) {
                val id = occ.flat[k]
                if (id != defIdx && aliasOverflowsLong(factors[id], x, replacement)) return true
            }
            return false
        }

        override fun otherOccurrencesAffineSubstitutable(
            defIdx: Int,
            x: Int,
            scale: Int,
            offset: Int,
            replacement: Int,
        ): Boolean {
            for (k in occ.offsets[x] until occ.offsets[x + 1]) {
                val id = occ.flat[k]
                if (id == defIdx) continue
                val f = factors[id]
                // A real-bearing Linear cannot be folded on the integer path and does not override
                // substituteAffine, so it is treated like any non-Linear factor — the var is eliminated
                // only if every such occurrence can absorb the affine substitution exactly.
                if ((f !is Linear || f.integerConstants == null) && !f.canSubstituteAffine(
                        x,
                        scale,
                        offset,
                        replacement,
                    )
                ) {
                    return false
                }
            }
            return true
        }

        // The seed is the pristine input before any fold, so no row has absorbed one yet.
        override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean = false

        // The seed is the pristine input, so nothing has been rewritten and nothing can have been promoted.
        override fun promotedCount(): Int = 0

        override fun promotedAt(k: Int): Int = error("the pristine seed promotes nothing")
    }

    /**
     * The working factor set for one [eliminateAffineSingletons] call, keyed by a stable id: ids
     * `[0, base.size)` are the pristine inputs; each appended factor takes the next id. A dropped id is
     * tombstoned (its slot becomes `null`) and never reused, so a live factor keeps its id — and hence
     * its position in stable-id order — for the whole run. The candidate scan walks the slots in id
     * order, which is exactly the order a fresh compacted list would present, so it selects the same
     * candidates in the same sequence the full-rebuild path did.
     *
     * Alongside the slots it holds a per-variable occurrence index over the stable ids of the
     * live factors whose [Factor.intVars] contains a variable, one entry per occurrence (mirroring a CSR
     * rebuild's multiset). It is maintained across eliminations: a fold rewrites only the factors that
     * mention the pivot, so only those ids' occurrence entries move. Its per-list order is irrelevant —
     * every reader (`isContained`, `otherOccurrences*`) tests membership, not position.
     */
    private class WorkingSet(base: Array<Factor>, nVars: Int, seed: SharedIntOccurrence?) : FactorOcc {
        private val slots = ArrayList<Factor?>(base.size + 1).apply { addAll(base) }
        private val intOcc = Array(nVars) { IntArrayList(0) }

        // The pivot-candidate ids (a live [Linear] equality of arity >= 2) the scan may visit, letting it
        // skip the inequality/global bulk. [baseEqIds] is the pristine set, ascending. A fold does not only
        // shrink the set: [foldAffineIntoLinear] substitutes a pivot's multi-term definition into a shorter
        // equality that mentions it (e.g. `c·x = b` becomes an equality over the definition's partners),
        // *promoting* an id that was not a candidate into one. Those promoted ids are tracked separately
        // (there are only ever a handful — bounded by the folds' fan-out); dropped or degraded ids are left
        // in place and re-rejected by the candidate gate. Appended rows are inequalities and can only be
        // rewritten into inequalities, so no appended id is ever a candidate.
        private val baseEqIds = eqPivotIds(base)
        private val promoted = IntArrayList(0)
        private val promotedSet = IntHashSet()

        private fun inBaseEq(id: Int): Boolean {
            val p = lowerBound(baseEqIds, id)
            return p < baseEqIds.size && baseEqIds[p] == id
        }

        override fun nextEqId(from: Int): Int {
            val p = lowerBound(baseEqIds, from)
            var best = if (p < baseEqIds.size) baseEqIds[p] else slots.size
            for (k in 0 until promoted.size) {
                val id = promoted[k]
                if (id in from until best) best = id
            }
            return best
        }

        // Folds absorbed by each stable id (parallel to [slots]): incremented whenever [replace] rewrites it,
        // read by [anyOtherLinearAtAbsorbCap] to cap a row that has become a fold sink. Appended rows start at 0.
        private val absorbed = IntArrayList(base.size + 1).apply { repeat(base.size) { add(0) } }

        // Per-variable count of at-cap cappable rows mentioning it, maintained alongside [intOcc] in [replace]
        // and [drop]. Lets [anyOtherLinearAtAbsorbCap] answer in O(1) instead of rescanning the occurrence list
        // of every candidate pivot. Only integer-core [Linear] rows are counted, matching that predicate.
        private val atCapCount = IntArray(nVars)

        private fun isCappable(f: Factor?): Boolean = f is Linear && f.integerConstants != null

        init {
            // The seed's CSR is over the pristine input in input (= stable-id) order, so its dense indices
            // are the stable ids; adopt them directly instead of re-scanning every factor's [intVars].
            if (seed != null) {
                for (v in 0 until nVars) {
                    val occ = intOcc[v]
                    for (k in seed.offsets[v] until seed.offsets[v + 1]) occ.add(seed.flat[k])
                }
            } else {
                for (id in base.indices) for (v in base[id].intVars) intOcc[v].add(id)
            }
        }

        /** Number of stable slots (live plus tombstoned); the upper bound of the candidate scan. */
        override val size: Int get() = slots.size

        /** The factor at stable id [id], or `null` if it was dropped. */
        override fun factorAt(id: Int): Factor? = slots[id]

        /** The live (non-tombstoned) factors in stable-id order — the working constraint set. */
        fun liveFactors(): List<Factor> = slots.filterNotNull()

        /** Tombstone stable id [id], removing its occurrence entries. */
        fun drop(id: Int) {
            val f = slots[id] ?: return
            if (absorbed[id] >= FOLD_ABSORB_CAP && isCappable(f)) for (v in f.intVars) atCapCount[v]--
            for (v in f.intVars) intOcc[v].removeValue(id)
            slots[id] = null
        }

        /** Replace live stable id [id] — currently holding [prev] — with [next], moving its occurrence
         *  entries from [prev]'s variables to [next]'s and recording that it absorbed a fold. */
        private fun replace(id: Int, prev: Factor, next: Factor) {
            val was = absorbed[id]
            if (was >= FOLD_ABSORB_CAP && isCappable(prev)) for (v in prev.intVars) atCapCount[v]--
            absorbed[id] = was + 1
            for (v in prev.intVars) intOcc[v].removeValue(id)
            slots[id] = next
            for (v in next.intVars) intOcc[v].add(id)
            if (was + 1 >= FOLD_ABSORB_CAP && isCappable(next)) for (v in next.intVars) atCapCount[v]++
            // A rewrite that turns a non-candidate row into a candidate equality promotes it into the scan;
            // record it (unless the pristine set already lists it, or it is already tracked).
            if (isEqCand(next) && !isEqCand(prev) && !inBaseEq(id) && promotedSet.add(id)) promoted.add(id)
        }

        /** Append [next] as a fresh stable id and record its occurrences. */
        private fun append(next: Factor) {
            val id = slots.size
            slots.add(next)
            absorbed.add(0)
            for (v in next.intVars) intOcc[v].add(id)
        }

        /** Number of live factors mentioning [x] — the fan-out a fold on [x] would rewrite. */
        override fun degreeOf(x: Int): Int = intOcc[x].size

        /** Whether [x] occurs in no factor other than [defIdx]. */
        override fun isContained(defIdx: Int, x: Int): Boolean {
            val occ = intOcc[x]
            for (k in 0 until occ.size) if (occ[k] != defIdx) return false
            return true
        }

        /** Whether every factor other than [defIdx] that mentions [x] is a [Linear] (foldable). */
        override fun otherOccurrencesAllLinear(defIdx: Int, x: Int): Boolean {
            val occ = intOcc[x]
            for (k in 0 until occ.size) {
                val id = occ[k]
                val f = slots[id]
                if (id != defIdx && (f !is Linear || f.integerConstants == null)) return false
            }
            return true
        }

        override fun foldOverflowsLong(
            defIdx: Int,
            x: Int,
            termVars: IntArray,
            termCoeffs: LongArray,
            constTerm: Long,
            cancellation: Cancellation,
        ): Boolean {
            val occ = intOcc[x]
            var polled = 0
            val termsSmall = termsFitHalfLong(constTerm, termCoeffs)
            for (k in 0 until occ.size) {
                // A high-degree pivot's overflow check walks every row it mentions; poll so one candidate's
                // check cannot outrun the budget. Treat a bail as "would overflow" — skipping the fold is sound.
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return true
                val id = occ[k]
                val f = slots[id]
                if (id != defIdx && f is Linear && f.integerConstants != null && foldRowOverflowsLong(
                        f,
                        x,
                        termVars,
                        termCoeffs,
                        constTerm,
                        termsSmall,
                    )
                ) {
                    return true
                }
            }
            return false
        }

        override fun aliasOverflowsLong(defIdx: Int, x: Int, replacement: Int): Boolean {
            val occ = intOcc[x]
            for (k in 0 until occ.size) {
                val id = occ[k]
                if (id != defIdx && aliasOverflowsLong(slots[id], x, replacement)) return true
            }
            return false
        }

        /** Whether every factor other than [defIdx] that mentions [x] can take the substitution
         *  `x = scale·replacement + offset`: a [Linear] folds it directly, any other factor must opt in
         *  via [Factor.substituteAffine] (a global that can represent the affine view). */
        override fun otherOccurrencesAffineSubstitutable(
            defIdx: Int,
            x: Int,
            scale: Int,
            offset: Int,
            replacement: Int,
        ): Boolean {
            val occ = intOcc[x]
            for (k in 0 until occ.size) {
                val id = occ[k]
                if (id == defIdx) continue
                val f = slots[id] ?: continue
                if ((f !is Linear || f.integerConstants == null) && !f.canSubstituteAffine(
                        x,
                        scale,
                        offset,
                        replacement,
                    )
                ) {
                    return false
                }
            }
            return true
        }

        override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean {
            val total = atCapCount[x]
            if (total == 0) return false
            // `defIdx` mentions `x` (x is a pivot in it), so subtract its own contribution to the count to
            // leave "any OTHER at-cap row". `atCapCount` counts exactly the integer-core [Linear] rows the
            // former per-occurrence scan tested.
            val self = if (absorbed[defIdx] >= FOLD_ABSORB_CAP && isCappable(slots[defIdx])) 1 else 0
            return total - self > 0
        }

        override fun promotedCount(): Int = promoted.size

        override fun promotedAt(k: Int): Int = promoted[k]

        /**
         * Fold `x = constTerm + Σ termCoeffs·termVars` out of the working set, dropping the defining
         * equality [c].`defIdx` and rewriting only the factors that mention `x` in place, then appending
         * the domain-bound rows. The def id is tombstoned last so the occurrence scan below still sees it.
         */
        fun fold(problem: Problem, c: AffineCandidate): Int {
            val singlePartner = c.termVars.size == 1
            // Snapshot the occurrence ids of `x` before mutating them: [replace] rewrites x's occurrence
            // list as it goes, and the def id must be skipped rather than folded into itself.
            val occ = intOcc[c.x]
            val toRewrite = IntArray(occ.size) { occ[it] }
            var minTouched = c.defIdx
            for (id in toRewrite) if (id < minTouched) minTouched = id
            for (id in toRewrite) {
                if (id == c.defIdx) continue
                val f = slots[id] ?: continue
                val next = when {
                    f is Linear && f.integerConstants != null && c.x in f.vars -> foldAffineIntoLinear(f, c)

                    // Single-partner affine into a global the gate accepted (non-null substitute). The
                    // gate only admits this path for an Int-range scale/offset, so the narrowing is safe.
                    singlePartner ->
                        requireNotNull(
                            f.substituteAffine(c.x, c.termCoeffs[0].toInt(), c.constTerm.toInt(), c.termVars[0]),
                        ) {
                            "substituteAffine returned null for a factor accepted by the candidate gate"
                        }

                    else -> f
                }
                if (next !== f) replace(id, f, next)
            }
            drop(c.defIdx)
            for (bound in domainBoundsOnTerms(problem.requireFiniteIntDomains()[c.x], c)) append(bound)
            // The lowest stable id whose candidacy this fold can newly establish: only the rewritten
            // factors (the pivot's occurrences) change content, so no factor below their minimum can
            // become a candidate. The candidate scan resumes from here instead of restarting at 0.
            return minTouched
        }

        /**
         * Apply the alias rename `x → y` to every live factor via [Factor.remap] (any factor type),
         * dropping the defining equality and appending the domain-bound rows. Rewriting the whole set is
         * intrinsic to a rename — [Factor.remap] returns a fresh object for every factor — so this stays
         * O(live factors); only `x`'s occurrences migrate to `y`, so the index is patched, not rebuilt.
         */
        fun alias(problem: Problem, c: AffineCandidate): Int {
            val boolMap = IntArray(problem.numBoolVars) { it }
            val intMap = IntArray(problem.numIntVars) { it }
            val y = c.termVars[0]
            intMap[c.x] = y
            val mapping = VarRemap(boolMap, intMap)
            // Snapshot the ids that mention `x` before remapping — their occurrence entries for `x` and
            // `y` are re-derived from the remapped factor's own [Factor.intVars] below (a factor holding
            // both `x` and `y` coalesces to a single `y`, matching what a fresh CSR rebuild would list).
            val occX = intOcc[c.x]
            val touched = IntArray(occX.size) { occX[it] }
            var minTouched = c.defIdx
            for (id in touched) if (id < minTouched) minTouched = id
            // Remap only the factors that mention `x`: the rename map is identity except `x → y`, so a
            // factor without `x` remaps to an equal object — leaving it untouched is content-identical and
            // keeps the pass O(occurrences of `x`) instead of O(live factors) per alias (the KMedian /
            // DiamondFree alias-remap hotspot).
            for (id in touched) {
                if (id == c.defIdx) continue
                val f = slots[id] ?: continue
                // An alias is not a fold (absorbed is unchanged), but it renames x→y, so an at-cap row moves
                // its at-cap count from x to y in lockstep with its [intOcc] entry (below). x is eliminated,
                // so its count is cleared wholesale afterwards.
                val atCap = absorbed[id] >= FOLD_ABSORB_CAP && isCappable(f)
                val remapped = f.remap(mapping)
                slots[id] = remapped
                intOcc[y].removeValue(id)
                val hasY = remapped.intVars.contains(y)
                if (hasY) intOcc[y].add(id)
                if (atCap) {
                    if (f.intVars.contains(y)) atCapCount[y]--
                    if (hasY) atCapCount[y]++
                }
            }
            intOcc[c.x].clear()
            // drop first (it removes the x=y row's own at-cap y-contribution), then zero x wholesale — x is
            // eliminated, so any remaining x count is dead.
            drop(c.defIdx)
            atCapCount[c.x] = 0
            for (bound in domainBoundsOnTerms(problem.requireFiniteIntDomains()[c.x], c)) append(bound)
            // Only the rewritten factors (`x`'s occurrences, now renamed to `y`) change content, so — as in
            // [fold] — no factor below their minimum can newly become a candidate; the scan resumes from
            // here instead of restarting at 0. (The occurrence-scoped remap above is what makes this valid:
            // when the rename touched every live factor, any factor's candidacy could change.)
            return minTouched
        }
    }

    // Drop the defining equality and remove `x`: for the alias case `x = y`, substitute `x → y` into
    // every other factor via Factor.remap (any factor type); otherwise fold `x = constTerm +
    // Σ termCoeffs·termVars` into every other Linear mentioning `x`. In both cases bounds on the term
    // vars keep `x` within its domain. Returns the lowest stable id whose candidacy the fold can newly
    // establish — the point the candidate scan may safely resume from (0 for the whole-set alias rename).
    private fun foldOutVariable(problem: Problem, ws: WorkingSet, c: AffineCandidate): Int =
        if (c.isAlias) ws.alias(problem, c) else ws.fold(problem, c)

    /** [l] with `x` replaced by `constTerm + Σ A_j·y_j`: drop `x`'s term, add `coeff_x·A_j` to each
     *  term var `y_j`, shift the bound by `−coeff_x·constTerm`. The [Linear] constructor re-coalesces
     *  any term var that already occurs in [l]. */
    private fun foldAffineIntoLinear(l: Linear, c: AffineCandidate): Linear {
        val row = checkNotNull(l.integerConstants) { "only an integer row is folded" }
        val ix = l.vars.indexOf(c.x)
        val cX = row.coeff(ix)
        val newVars = IntArray(l.vars.size - 1 + c.termVars.size)
        // Long throughout: both [l]'s coefficients and the fold candidate's are wide-capable, and the
        // candidate gate declined any pivot whose fold would overflow, so the products below are safe.
        val newCoeffs = LongArray(newVars.size)
        var w = 0
        for (j in l.vars.indices) {
            if (j == ix) continue
            newVars[w] = l.vars[j]
            newCoeffs[w] = row.coeff(j)
            w++
        }
        for (k in c.termVars.indices) {
            newVars[w] = c.termVars[k]
            newCoeffs[w] = cX * c.termCoeffs[k]
            w++
        }
        return Linear(newCoeffs, newVars, l.op, row.bound - cX * c.constTerm)
    }

    /** Bounds on the term vars enforcing that `x = constTerm + Σ termCoeffs·termVars` stays within
     *  `x`'s domain [domX]. */
    private fun domainBoundsOnTerms(domX: IntDomain, c: AffineCandidate): List<Factor> {
        val coeffs = c.termCoeffs.copyOf()
        return listOf(
            Linear(coeffs, c.termVars.copyOf(), LinearOp.LE, domX.max - c.constTerm),
            Linear(coeffs, c.termVars.copyOf(), LinearOp.GE, domX.min - c.constTerm),
        )
    }
}

/** A single affine elimination `x = (constTerm + Σ termCoeffs·termVars) / divisor` recorded by
 *  [Presolve]. [divisor] is `1` for the unit-pivot cases and the
 *  pivot coefficient for a residue-class doubleton, where the division is always exact on the
 *  values the partner's restricted domain admits. */
internal class AffineSub(
    val x: Int,
    val constTerm: Long,
    val termVars: IntArray,
    val termCoeffs: LongArray,
    val divisor: Long = 1,
)

/**
 * The affine eliminations [Presolve] made, holding the data to rebuild the
 * eliminated variables. Pass a solution of the reduced problem through [reconstruct] to recover a
 * solution of the original.
 */
internal class AffineElimination(private val subs: List<AffineSub>) {
    /** Recover the eliminated variables in a solution [sample] of the reduced problem. Processed in reverse
     *  elimination order: an eliminated `x` may depend on a `y` eliminated later (a chain), and a
     *  later elimination never depends on an earlier one (the candidate scan skips already-eliminated
     *  partners), so reverse order guarantees every `y` is reconstructed before the `x` that reads it. */
    fun reconstruct(sample: Sample): Sample {
        if (subs.isEmpty()) return sample
        val ints = sample.ints.copyOf()
        for (s in subs.asReversed()) {
            var v = s.constTerm
            for (k in s.termVars.indices) v += s.termCoeffs[k] * ints[s.termVars[k]]
            ints[s.x] = if (s.divisor == 1L) v else v / s.divisor
        }
        return Sample(sample.bools, ints)
    }
}

/** Whether folding `x = constTerm + Σ termCoeffs·termVars` into the Linear row [f] would overflow
 *  64-bit arithmetic in the shifted bound or any folded coefficient. Each product/sum runs through the
 *  checked helpers, so an overflow surfaces as [LpOverflowException]; a `true` result lets the caller
 *  leave `x` un-eliminated (sound) rather than wrap a coefficient (e.g. DeBruijn-sequence instances). */
private fun foldRowOverflowsLong(
    f: Linear,
    x: Int,
    termVars: IntArray,
    termCoeffs: LongArray,
    constTerm: Long,
    termsSmall: Boolean,
): Boolean {
    // Fast path, no `indexOf`: when the pivot's own terms are all below 2^31 ([termsSmall], pivot-global so
    // the caller hoists it) and this row's magnitudes are too, no product `cX·c` reaches 2^62 and no sum
    // reaches 2^63 — the pivot coefficient `cX ≤ maxAbsCoeff`, so `fitsHalfLong(maxAbsCoeff)` already
    // bounds it and `cX` is never needed. So x's position is not looked up, which is the whole
    // small-coefficient bulk (Coprime/FAPP). The exact path below runs only when some magnitude is huge.
    val row = f.integerConstants ?: return true
    if (termsSmall && fitsHalfLong(row.bound) && fitsHalfLong(row.maxAbsCoeff)) return false
    val xi = f.vars.indexOf(x)
    if (xi < 0) return false
    val cX = row.coeff(xi)
    return try {
        subExact(row.bound, mulExact(cX, constTerm))
        for (k in termVars.indices) {
            val prod = mulExact(cX, termCoeffs[k])
            val yi = f.vars.indexOf(termVars[k])
            if (yi >= 0) addExact(row.coeff(yi), prod)
        }
        false
    } catch (_: LpOverflowException) {
        true
    }
}

/** True when `|v| < 2^31`, so a product of two such values stays below 2^62 and a sum below 2^63 — the
 *  bound the overflow fast-path in [foldRowOverflowsLong] relies on. */
private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

/** Whether the fold's constant and every partner coefficient fit half-Long — the pivot-global half of the
 *  [foldRowOverflowsLong] fast path, computed once per pivot rather than once per rewritten row. */
private fun termsFitHalfLong(constTerm: Long, termCoeffs: LongArray): Boolean {
    if (!fitsHalfLong(constTerm)) return false
    for (c in termCoeffs) if (!fitsHalfLong(c)) return false
    return true
}
