package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SharedIntOccurrence
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.asSampleLift
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.presolve.protectedStructureColumns
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

internal object AffineSingletons {

    /**
     * Affine variable elimination. Eliminates an integer variable `x` defined by an
     * `n`-term equality `c_x·x + Σ_j c_j·y_j = b` with a **unit** pivot coefficient `|c_x| = 1`, i.e.
     * `x = B + Σ_j A_j·y_j` where `A_j = −c_x·c_j` and `B = c_x·b`. The defining equality is dropped,
     * the affine relation is folded into every other [Linear] that mentions `x`, and bounds on the
     * `y_j` are added so `x` stays inside its declared domain; `x` becomes unconstrained and is
     * rebuilt from the solution by the steps [asRebuilds] states.
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
     * variable rename, applied to *every* factor via `Factor.remap` regardless of type.
     * Otherwise the relation folds into every other [Linear], and the rewrite-cost policy also
     * retains [ReifiedLinear] channels through the fold and unit views through [ComparisonClause].
     * A **single-partner** `x = a·y + b`
     * additionally projects out of any non-linear factor that can represent the affine view via
     * `Factor.substituteAffine` (an Element index shift, a Table column rewrite). A multi-partner
     * `B + Σ A_j·y_j` only folds into admitted linear rows — a global keyed on `x`'s value as a sum can't
     * represent it. The contained slice (`x` in no other factor) is the zero-fold special case,
     * and is what lets an `n`-term definition be projected out.
     *
     * Variables in [objectiveIntVars] stay protected unless [objective] supplies an exact objective
     * whose aliases and translations can be substituted with checked coefficient arithmetic.
     */
    fun eliminateAffineSingletons(
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
        sharedIntOcc: SharedIntOccurrence? = null,
        capWide: Boolean = false,
        incrementalTouchedVars: IntArray? = null,
        maxFactors: Int = AFFINE_MAX_FACTORS,
        pivotOrder: AffinePivotOrder = AffinePivotOrder.MARKOWITZ,
        objective: LinearObjective? = null,
        preserveStructure: Boolean = false,
    ): PassDelta {
        val domains = problem.rootIntDomains()
        val out = run(
            problem,
            ColumnRanges.of(domains),
            domains,
            objectiveIntVars,
            cancellation,
            sharedIntOcc,
            capWide,
            incrementalTouchedVars,
            maxFactors,
            pivotOrder,
            objective,
            preserveStructure,
        ) ?: return PassDelta()
        val rebuild = out.subs.asRebuilds()
        return PresolveShared.identityDelta(
            problem.factors,
            out.factors,
            domains,
            rebuild.asSampleLift(),
        ).also {
            it.rebuild = rebuild
            it.objective = out.objective
        }
    }

    /**
     * [eliminateAffineSingletons] over a canonical source model.
     *
     * Two differences, both forced by what a declaration states. A bound row is emitted only for a side
     * the model bounds, since an open side gives `x` no bound to carry over to its terms. And the
     * residue-class doubletons are left alone: restricting a partner to the values that keep `x` an
     * in-domain integer is a value set with holes, which a range cannot state and a hull would state
     * wrongly — it would admit the partner values that make `x` non-integral.
     */
    fun eliminateSourceAffineSingletons(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
        maxFactors: Int = AFFINE_MAX_FACTORS,
        pivotOrder: AffinePivotOrder = AffinePivotOrder.MARKOWITZ,
        objective: LinearObjective? = null,
        preserveStructure: Boolean = false,
    ): SourceDelta {
        val out = run(
            problem,
            ColumnRanges.of(problem.intBounds),
            domains = null,
            objectiveIntVars = objectiveIntVars,
            cancellation = cancellation,
            sharedIntOcc = null,
            capWide = false,
            incrementalTouchedVars = null,
            maxFactors = maxFactors,
            pivotOrder = pivotOrder,
            objective = objective,
            preserveStructure = preserveStructure,
        ) ?: return SourceDelta()
        return PresolveShared.sourceIdentityDelta(problem.factors, out.factors, out.subs.asRebuilds())
            .also { it.objective = out.objective }
    }

    /** What one run of the elimination produced: the surviving factors and the substitutions it recorded. */
    private class Eliminated(val factors: List<Factor>, val subs: List<AffineSub>, val objective: LinearObjective?)

    @Suppress("LongParameterList") // one body serves both lanes; the parameters are the lane's differences
    private fun run(
        problem: Problem,
        ranges: ColumnRanges,
        domains: Array<IntDomain>?,
        objectiveIntVars: Set<Int>,
        cancellation: ExecutionControl,
        sharedIntOcc: SharedIntOccurrence?,
        capWide: Boolean,
        incrementalTouchedVars: IntArray?,
        maxFactors: Int,
        pivotOrder: AffinePivotOrder,
        objective: LinearObjective?,
        preserveStructure: Boolean,
    ): Eliminated? {
        if (problem.numIntVars == 0) return null
        // The entry scan alone can dominate optional preparation on very large models.
        if (problem.factors.size > maxFactors) return null
        val objectiveRewrite = objective?.takeUnless { problem.semantics.relaxesFeasibility }
            ?.let { AffineObjective(it, cancellation) }
        if (objectiveRewrite?.interrupted == true) return null
        val objVars = IntHashSet().apply {
            if (objectiveRewrite == null) for (v in objectiveIntVars) add(v)
            if (objectiveRewrite == null && objective != null) {
                for (v in objective.intCoefficientValues.indices) {
                    if (objective.intCoefficientValues[v] != 0L) add(v)
                }
            }
        }
        if (preserveStructure) {
            val protected = protectedStructureColumns(problem, cancellation) ?: return null
            for (variable in protected) objVars.add(variable)
        }
        val eliminated = BooleanArray(problem.numIntVars)
        // Once `x` is gone only its range survives, as the bound rows on its terms, so a pivot with interior
        // holes could be rebuilt onto a value the model excludes. A source declaration has nowhere to carry
        // them, so that lane refuses such a pivot outright; the finite lane takes one only with a single
        // partner, which the loop below restricts to the values keeping `x` off its holes. Where every
        // column is closed and contiguous and binary substitution has already run, there is nothing to
        // refuse, so the finite lane pays no predicate.
        val domainPivotable: ((Int, Int) -> Boolean)? = when {
            domains == null -> { x: Int, _: Int ->
                ranges.isClosed(x) && !ranges.isBinary(x) && (problem.intDomainOrNull(x)?.holeCount ?: 0L) == 0L
            }

            domains.any { it.holeCount > 0L } -> { x: Int, partners: Int ->
                partners == 1 || domains[x].holeCount == 0L
            }

            else -> null
        }
        val pivotable: ((Linear, Int) -> Boolean)? = if (objectiveRewrite == null && domainPivotable == null) {
            null
        } else {
            { definition, pivot ->
                (objectiveRewrite?.permits(definition, pivot) ?: true) &&
                    (domainPivotable?.invoke(definition.vars[pivot], definition.vars.size - 1) ?: true)
            }
        }
        val subs = ArrayList<AffineSub>()
        // Before any fold the working set is byte-for-byte the pristine input, so the first candidate scan
        // can read the session's shared occurrence index directly (its CSR is in stable-id order). If no
        // candidate exists there, the pass is fruitless and returns without ever building the mutable
        // [AffineWorkingSet] — skipping its O(occurrences) index copy on every barren firing. On a re-run the
        // engine supplies the variables the delta touched since the last firing: a candidate can only newly
        // arise on a touched variable (an untouched variable's factors, and hence its foldability, are
        // unchanged since the previous run reached its fixpoint), so the scan need only test the factors
        // those variables appear in — O(delta) instead of O(factors) on the common fruitless re-run.
        if (sharedIntOcc != null) {
            val seed = AffineSeedOccurrences(problem.factors, sharedIntOcc, pivotOrder == AffinePivotOrder.REWRITE_COST)
            val hasCandidate = if (incrementalTouchedVars != null) {
                anyTouchedVarHeadsCandidate(
                    seed,
                    incrementalTouchedVars,
                    eliminated,
                    objVars,
                    capWide,
                    domains,
                    pivotable,
                    cancellation,
                )
            } else {
                findAffineCandidate(seed, 0, eliminated, objVars, capWide, pivotable, cancellation) != null ||
                    (domains != null && findResidueCandidate(seed, eliminated, objVars, domains, cancellation) != null)
            }
            if (!hasCandidate) return null
        }
        // The working set holds the factors by stable id (tombstones for drops, appends for the folded
        // rewrites and bound rows) and a per-variable occurrence index maintained across eliminations.
        // The session-maintained index matches the pristine input factor order, so the first candidate
        // search reads it instead of rebuilding an O(factors) index; each fold then patches only the
        // occurrence entries of the rewritten factors rather than re-indexing the whole set.
        val ws = AffineWorkingSet(
            problem.factors, eliminated.size, sharedIntOcc,
            pivotOrder == AffinePivotOrder.REWRITE_COST)
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
        val order = AffinePivotOrders.new(pivotOrder, ws, eliminated, objVars, capWide, pivotable, cancellation)
        // Cumulative substitution fill-in (Σ pivot-degree · substituted-terms). A dense chain of wide
        // folds is superlinear and can dominate presolve on large models where it barely simplifies;
        // once the budget is spent the loop stops, leaving the remaining affine-defined variables to be
        // solved directly (sound). The budget is far above what productive models spend.
        var fillIn = 0L
        while (!cancellation()) {
            val cand = order.next() ?: break
            if (domainBoundsOverflow(ranges, cand)) {
                objVars.add(cand.x)
                order.onRejected(cand.defIdx)
                continue
            }
            val replacement = objectiveRewrite?.let { checkNotNull(it.substitute(cand)) }
            val holed = domains != null && domains[cand.x].holeCount > 0L
            val partnerDomain = if (holed) holedPivotPartner(checkNotNull(domains), cand) else null
            if (holed && partnerDomain == null) {
                // The holes have nowhere to go, so `x` stays; holding it keeps the gate from offering it again.
                objVars.add(cand.x)
                order.onRejected(cand.defIdx)
                continue
            }
            val foldCost = ws.degreeOf(cand.x).toLong() * cand.termVars.size
            cancellation.charge(AFFINE_WORK_WEIGHT * (1L + foldCost))
            fillIn += foldCost
            order.onFolded(foldOutVariable(problem, ranges, ws, cand))
            replacement?.invoke()
            if (partnerDomain != null) checkNotNull(domains)[cand.termVars[0]] = partnerDomain
            eliminated[cand.x] = true
            subs.add(AffineSub(cand.x, cand.constTerm, cand.termVars, cand.termCoeffs))
            if (fillIn > AFFINE_FILL_IN_BUDGET) break
        }
        // Residue-class doubletons: a 2-term `a·x + b·y = c` with no unit pivot, where `x` is
        // contained, determines `x = (c − b·y)/a` only for the `y` values keeping it an in-domain
        // integer. Restrict `y` to those values (a domain modification, not a folded factor) and
        // reconstruct `x` with the divisor. Runs after the unit-pivot loop, so a residue partner `y`
        // is always a surviving variable.
        objectiveRewrite?.protectRemaining(objVars)
        while (domains != null && !cancellation()) {
            val r = findResidueCandidate(ws, eliminated, objVars, domains, cancellation) ?: break
            ws.drop(r.defIdx)
            domains[r.y] = r.restrictedY
            eliminated[r.x] = true
            subs.add(AffineSub(r.x, r.constTerm, intArrayOf(r.y), longArrayOf(r.coeffY), divisor = r.divisor))
        }
        if (subs.isEmpty()) return null
        // The eliminations rebuilt the factor list in place; recover the delta against the input by
        // identity — every survivor is === an input factor, so the drops are the inputs absent from
        // [factors] and the adds are the factors [foldOutVariable] introduced (rewrites + domain bounds).
        return Eliminated(ws.liveFactors(), subs, objectiveRewrite?.result())
    }

    /**
     * The next pivot to fold out, and the bookkeeping that keeps that choice cheap across eliminations.
     * Every candidate is vetted by [candidateInFactor] under the run's consumer policy, and stopping
     * early is always sound (an unfolded variable simply stays and is solved directly).
     */
    internal interface PivotOrder {

        /** The next pivot to fold, or `null` when no admissible candidate remains. */
        fun next(): AffineCandidate?

        /** Record that the fold just made rewrote factors from stable id [minRewrittenId] upward. */
        fun onFolded(minRewrittenId: Int)

        /** Reconsider an equality after its selected pivot becomes protected. */
        fun onRejected(definitionId: Int)
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
    // denser problem rather than a simpler one. Once a row has absorbed this many folds, leave any further
    // pivot that would rewrite it unfolded (sound — the variable stays and is solved directly), which bounds
    // each row's growth. Set well above the handful of folds a productively-eliminated variable draws, so
    // only a row that is a fold sink is capped.
    internal const val FOLD_ABSORB_CAP = 16

    /** Cumulative substitution-fill-in budget across the whole pass (Σ pivot-degree · substituted-terms).
     *  [FOLD_ABSORB_CAP] bounds individual row growth; this bounds aggregate work across all rows.
     *  Once spent, the loop stops and remaining affine-defined variables are solved directly. */
    private const val AFFINE_FILL_IN_BUDGET = 300_000L

    /** Skip the whole affine pass on instances with more than this many factors: the O(factors) candidate
     *  scan dominates presolve on multi-million-factor giants (where it also barely simplifies), and the
     *  pass is optional (skipping is sound). */
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
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): ResidueCandidate? {
        var polled = 0
        var di = ws.nextEqId(0)
        while (di < ws.size) {
            if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            cancellation.charge(AFFINE_WORK_WEIGHT)
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
        val f = ws.factorAt(di)?.equivalentLinear() ?: return null
        if (f.op != LinearOp.EQ || f.vars.size != 2) return null
        val row = f.integerConstants ?: return null
        if (row.bound == Long.MIN_VALUE || row.coeff(0) == Long.MIN_VALUE || row.coeff(1) == Long.MIN_VALUE) return null
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
            val span = domY.max - domY.min
            if (span < 0L || span > RESIDUE_DOMAIN_SPAN_CAP) continue
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

    /**
     * The domain of the single partner `y` of a holed pivot `x = constTerm + a·y`, restricted to the `y`
     * that keep `x` off its holes, or null when `x` has more than one term, the partner is too wide to
     * scan, or no `y` survives. Carrying the holes there lets the pivot fold without the rebuild landing
     * on a value its domain excludes.
     */
    private fun holedPivotPartner(domains: Array<IntDomain>, c: AffineCandidate): IntDomain? {
        if (c.termVars.size != 1) return null
        val domY = domains[c.termVars[0]]
        val span = domY.max - domY.min
        if (span < 0L || span > RESIDUE_DOMAIN_SPAN_CAP) return null
        return restrictPartnerDomain(domY, domains[c.x], 1L, -c.termCoeffs[0], c.constTerm)
    }

    /** The partner domain restricted to the `y` values for which `x = (c − b·y)/a` is an integer
     *  inside [domX], or null when the projection is empty or needs wider arithmetic. */
    private fun restrictPartnerDomain(domY: IntDomain, domX: IntDomain, a: Long, b: Long, c: Long): IntDomain? {
        val valid = LongArrayList()
        for (y in domY.min..domY.max) {
            if (y !in domY) continue
            // A wide numerator can still have a representable quotient, so an unrepresentable
            // intermediate declines the entire projection rather than excluding this partner value.
            val num = try {
                subExact(c, mulExact(b, y))
            } catch (_: CheckedLongOverflowException) {
                return null
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
     *  substituted via `Factor.remap` into any factor type. */
    internal class AffineCandidate(
        val defIdx: Int,
        val x: Int,
        val constTerm: Long,
        val termVars: IntArray,
        val termCoeffs: LongArray,
        val isAlias: Boolean,
    )

    internal fun findAffineCandidate(
        ws: FactorOcc,
        start: Int,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        pivotable: ((Linear, Int) -> Boolean)? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): AffineCandidate? {
        // The scan walks the live factors in stable-id order — the same order a fresh compacted list would
        // present — so it picks candidates in the identical sequence a full rebuild would. Give up after
        // [AFFINE_SCAN_ABORT] consecutive non-candidates: on a huge factor set with no exploitable affine
        // structure, scanning to the end is pure fruitless cost. Sound: an unfound pivot stays.
        val limit = minOf(ws.size.toLong(), start.toLong() + AFFINE_SCAN_ABORT).toInt()
        var polled = 0
        var di = ws.nextEqId(start)
        while (di < limit) {
            // Poll the presolve deadline inside the scan, not only between folds: a single scan over a large
            // wide-row factor set can run for seconds, so without this the budget cannot bound it.
            // Count iterations (a dense counter) rather than keying on [di], which a candidate index makes
            // sparse. Giving up returns the folds made so far — sound (an unfound pivot simply stays).
            if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            cancellation.charge(AFFINE_WORK_WEIGHT)
            candidateInFactor(
                ws,
                di,
                eliminated,
                objectiveIntVars,
                capWide,
                pivotable,
                cancellation,
            )?.let { return it }
            di = ws.nextEqId(di + 1)
        }
        return null
    }

    /** Poll the cancellation once per this many scanned factors (a power-of-two mask for a cheap check). */
    internal const val CANCEL_POLL_MASK = 0x3FF

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
    internal fun candidateInFactor(
        ws: FactorOcc,
        di: Int,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        // Whether the definition admits this pivot; null admits every column.
        pivotable: ((Linear, Int) -> Boolean)?,
        cancellation: ExecutionControl = ExecutionControl.Never,
        minimizeRewrite: Boolean = false,
    ): AffineCandidate? {
        val f = ws.factorAt(di)?.equivalentLinear() ?: return null
        if (f.op != LinearOp.EQ || f.vars.size < 2) return null
        val row = f.integerConstants ?: return null
        val fallback = if (minimizeRewrite) {
            cancellation.charge(AFFINE_WORK_WEIGHT)
            candidateInFactor(ws, di, eliminated, objectiveIntVars, capWide, pivotable, cancellation) ?: return null
        } else null
        cancellation.charge(AFFINE_WORK_WEIGHT * f.vars.size)
        if (row.bound == Long.MIN_VALUE) return null
        var preferred = 0
        if (minimizeRewrite) {
            var smallestWeight = Long.MAX_VALUE
            for (index in f.vars.indices) {
                cancellation.charge(AFFINE_WORK_WEIGHT)
                if (cancellation()) return null
                val variable = f.vars[index]
                if (eliminated[variable] || variable in objectiveIntVars ||
                    (row.coeff(index) != 1L && row.coeff(index) != -1L)
                ) continue
                if (pivotable != null && !pivotable(f, index)) continue
                val weight = ws.rewriteWeight(di, variable, cancellation)
                if (cancellation()) return null
                if (weight < smallestWeight) {
                    smallestWeight = weight
                    preferred = index
                }
                if (weight == 0L) break
            }
            if (f.vars[preferred] == fallback?.x) return fallback
        }
        for (position in f.vars.indices) {
            if (position > 0 && fallback != null) return fallback
            val xi = when {
                position == 0 -> preferred
                position <= preferred -> position - 1
                else -> position
            }
            // A single wide row can carry thousands of pivot candidates each running an O(occurrences)
            // overflow check, so poll the deadline between them (and inside the check below).
            if ((position and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            val x = f.vars[xi]
            val cx = row.coeff(xi)
            if (eliminated[x] || x in objectiveIntVars || cx == Long.MIN_VALUE) continue
            // A column with an open side states no bound to carry over to the terms it folds into, so
            // eliminating it would drop the row a later bound proof reads and leave the column itself
            // unconstrained — trading a lane the model could have routed to for one reduction. A column
            // with holes needs a single partner to take them: the bound rows carry only its range.
            if (pivotable != null && !pivotable(f, xi)) continue
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
            var invalidPartner = false
            for (j in f.vars.indices) {
                if (j == xi) continue
                if (eliminated[f.vars[j]] || row.coeff(j) == Long.MIN_VALUE) {
                    invalidPartner = true
                    break
                }
                termVars[w] = f.vars[j]
                termCoeffs[w] = -row.coeff(j) / cx
                w++
            }
            if (invalidPartner) continue
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
            // only folds into admitted linear rows. The global's affine view is an index/position shift in
            // Int space, so a scale or offset beyond Int range takes the Linear-fold path instead.
            val singlePartnerSubstitutable = !isAlias && termVars.size == 1 &&
                termCoeffs[0] in INT_RANGE && constTerm in INT_RANGE &&
                ws.otherOccurrencesAffineSubstitutable(di, x, termCoeffs[0].toInt(), constTerm.toInt(),
                    termVars[0], cancellation)
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
        seed: AffineSeedOccurrences,
        touchedVars: IntArray,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        // Null where the lane states ranges rather than domains, which is where no residue doubleton is
        // eliminated at all — so there is no residue candidate to look for either.
        domains: Array<IntDomain>?,
        pivotable: ((Linear, Int) -> Boolean)?,
        cancellation: ExecutionControl = ExecutionControl.Never,
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
                cancellation.charge(AFFINE_WORK_WEIGHT)
                if (candidateInFactor(
                        seed,
                        di,
                        eliminated,
                        objectiveIntVars,
                        capWide,
                        pivotable,
                        cancellation,
                    ) != null
                ) {
                    return true
                }
                if (domains != null &&
                    residueCandidateInFactor(seed, di, eliminated, objectiveIntVars, domains) != null
                ) {
                    return true
                }
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
     * mutable [AffineWorkingSet] and the pristine-input [AffineSeedOccurrences] answer these,
     * so a scan can run over the
     * session's shared occurrence index without first materialising a mutable working set.
     */
    internal interface FactorOcc {
        val size: Int
        fun factorAt(id: Int): Factor?
        fun degreeOf(x: Int): Int
        fun occurrenceAt(x: Int, k: Int): Int

        fun rewriteWeight(defIdx: Int, x: Int, cancellation: ExecutionControl): Long {
            var weight = 0L
            for (k in 0 until degreeOf(x)) {
                cancellation.charge(AFFINE_WORK_WEIGHT)
                if (cancellation()) return Long.MAX_VALUE
                val id = occurrenceAt(x, k)
                if (id != defIdx) weight += factorAt(id)?.structuralKeyWeight?.coerceAtLeast(1)?.toLong() ?: 0L
            }
            return weight
        }
        fun isContained(defIdx: Int, x: Int): Boolean
        fun otherOccurrencesAllLinear(defIdx: Int, x: Int): Boolean
        fun otherOccurrencesAffineSubstitutable(
            defIdx: Int,
            x: Int,
            scale: Int,
            offset: Int,
            replacement: Int,
            cancellation: ExecutionControl,
        ): Boolean

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
            cancellation: ExecutionControl = ExecutionControl.Never,
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

    // Drop the defining equality and remove `x`: for the alias case `x = y`, substitute `x → y` into
    // every other factor via Factor.remap (any factor type); otherwise fold `x = constTerm +
    // Σ termCoeffs·termVars` into every other Linear mentioning `x`. In both cases bounds on the term
    // vars keep `x` within its domain. Returns the lowest stable id whose candidacy the fold can newly
    // establish — the point the candidate scan may safely resume from.
    private fun foldOutVariable(problem: Problem, ranges: ColumnRanges, ws: AffineWorkingSet, c: AffineCandidate): Int =
        if (c.isAlias) ws.alias(problem, ranges, c) else ws.fold(ranges, c)

    private fun domainBoundsOverflow(ranges: ColumnRanges, c: AffineCandidate): Boolean = try {
        if (ranges.hasUpper(c.x)) subExact(ranges.max(c.x), c.constTerm)
        if (ranges.hasLower(c.x)) subExact(ranges.min(c.x), c.constTerm)
        false
    } catch (_: CheckedLongOverflowException) {
        true
    }
}

/** A single affine elimination `x = (constTerm + Σ termCoeffs·termVars) / divisor` recorded by
 *  [AffineSingletons] during [Presolve]. [divisor] is `1` for the unit-pivot cases and the pivot coefficient for a
 *  residue-class doubleton, where the division is always exact on the values the partner's restricted
 *  domain admits. */
internal class AffineSub(
    val x: Int,
    val constTerm: Long,
    val termVars: IntArray,
    val termCoeffs: LongArray,
    val divisor: Long = 1,
)

/**
 * The affine eliminations as the steps that rebuild them, latest first.
 *
 * Reverse elimination order: an eliminated `x` may depend on a `y` eliminated later (a chain), and a
 * later elimination never depends on an earlier one (the candidate scan skips already-eliminated
 * partners), so reversing guarantees every `y` is recovered before the `x` that reads it.
 */
internal fun List<AffineSub>.asRebuilds(): SourceRebuilds = SourceRebuilds(
    asReversed().map { RebuildStep.AffineValue(it.x, it.constTerm, it.termVars, it.termCoeffs, it.divisor) },
)

// A reified row remains a two-way channel when rewritten; it cannot supply an asserted pivot.
internal const val AFFINE_WORK_WEIGHT = 10L
