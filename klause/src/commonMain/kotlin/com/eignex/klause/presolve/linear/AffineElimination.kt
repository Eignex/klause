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
     * Eliminates an integer `x` defined by an equality `c_x·x + Σ_j c_j·y_j = b`. The definition is dropped,
     * `x = b/c_x + Σ_j (−c_j/c_x)·y_j` folds into every other [Linear] mentioning `x`, bound rows on the `y_j`
     * keep `x` in its domain, and `x` is rebuilt by [asRebuilds].
     *
     * A unit pivot keeps every folded coefficient integral. A non-unit pivot is admitted only for a contained
     * `x` whose coefficient divides every other coefficient and the bound; the rest go to the residue-class pass.
     * An alias `x = y` renames `x` in every factor via `Factor.remap`. The rewrite-cost policy also keeps
     * [ReifiedLinear] channels and unit views through [ComparisonClause]. A single-partner `x = a·y + b` also
     * projects out of globals accepting `Factor.substituteAffine`; a multi-partner relation folds only into
     * linear rows.
     *
     * Variables in [objectiveIntVars] stay protected unless [objective] admits an exact substitution.
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

    // [eliminateAffineSingletons] over a canonical source model. A bound row is emitted only for a side the model
    // bounds, since an open side gives `x` no bound to carry to its terms. Residue-class doubletons are skipped:
    // the partner values keeping `x` an in-domain integer form a set with holes, which a range cannot state.
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
        // Before any fold the working set equals the input, so the first scan reads the session's shared
        // occurrence index and a barren firing returns without copying it into [AffineWorkingSet]. On a re-run
        // only a touched variable can head a new candidate, so only its factors are tested.
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
        val ws = AffineWorkingSet(
            problem.factors, eliminated.size, sharedIntOcc,
            pivotOrder == AffinePivotOrder.REWRITE_COST)
        // Elimination cost depends on the order as much as on the set: folding cheap pivots first shrinks later
        // folds, hence the [AffinePivotOrder] policy. The gates below can only refuse a fold, never defer one.
        // Stopping early is sound; the remaining affine-defined variables stay.
        val order = AffinePivotOrders.new(pivotOrder, ws, eliminated, objVars, capWide, pivotable, cancellation)
        // Σ pivot-degree · substituted-terms: a dense chain of wide folds is superlinear.
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
        return Eliminated(ws.liveFactors(), subs, objectiveRewrite?.result())
    }

    // Every candidate is vetted by [candidateInFactor]; stopping early is sound since an unfolded variable stays.
    internal interface PivotOrder {

        fun next(): AffineCandidate?

        // The fold just made rewrote factors from stable id [minRewrittenId] upward.
        fun onFolded(minRewrittenId: Int)

        // Reconsiders an equality after its selected pivot becomes protected.
        fun onRejected(definitionId: Int)
    }

    // A single-partner global's affine view is an index shift, representable only when scale and offset fit Int.
    private val INT_RANGE = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()

    // Building the restricted partner domain is O(span), and a residue class on a wide domain floods it with holes.
    private const val RESIDUE_DOMAIN_SPAN_CAP = 1024

    // In an underdetermined model (see [PresolveContext.affineUnderdetermined]), defer a fold whose fill-in
    // (pivot degree × substituted terms) exceeds this — it is the runaway dense kind that inflates a few
    // wide rows without simplifying. The variable stays (sound, solved directly).
    private const val WIDE_FILL_IN = 64

    // Each fold into a row rebuilds the whole row, so a row absorbing folds from successive pivots costs
    // O(folds²) and densifies the model. A pivot that would rewrite a row past this cap stays unfolded.
    internal const val FOLD_ABSORB_CAP = 16

    // [FOLD_ABSORB_CAP] bounds one row's growth; this bounds aggregate fill-in across the pass.
    private const val AFFINE_FILL_IN_BUDGET = 300_000L

    // The O(factors) candidate scan dominates presolve on multi-million-factor models; skipping is sound.
    private const val AFFINE_MAX_FACTORS = 1_000_000

    // A non-unit doubleton `a·x + b·y = c` with `x` contained, rebuilt as `(constTerm + coeffY·y) / divisor`
    // over [restrictedY].
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
            // `x` must be contained, since a non-unit fold can't stay integral. `y`'s domain is restricted
            // below, so it too must stay clear of the objective.
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

    // Restricts the single partner of a holed pivot to the values keeping `x` off its holes, so the rebuild
    // never lands on an excluded value.
    private fun holedPivotPartner(domains: Array<IntDomain>, c: AffineCandidate): IntDomain? {
        if (c.termVars.size != 1) return null
        val domY = domains[c.termVars[0]]
        val span = domY.max - domY.min
        if (span < 0L || span > RESIDUE_DOMAIN_SPAN_CAP) return null
        return restrictPartnerDomain(domY, domains[c.x], 1L, -c.termCoeffs[0], c.constTerm)
    }

    // The `y` for which `x = (c − b·y)/a` is an integer in [domX]; null when empty or on overflow.
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

    // The EQ at [defIdx] defines `x = constTerm + Σ termCoeffs·termVars`. An alias substitutes into any factor
    // via `Factor.remap`.
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
        // Stable-id order picks candidates in the sequence a full rebuild would. Giving up after
        // [AFFINE_SCAN_ABORT] non-candidates is sound: an unfound pivot stays.
        val limit = minOf(ws.size.toLong(), start.toLong() + AFFINE_SCAN_ABORT).toInt()
        var polled = 0
        var di = ws.nextEqId(start)
        while (di < limit) {
            // One scan over a large wide-row factor set can run for seconds. Count iterations, since [di] is sparse.
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

    internal const val CANCEL_POLL_MASK = 0x3FF

    // Far above the gap between successive pivots in productive models, so only a fruitless walk over a giant
    // factor set is cut short.
    private const val AFFINE_SCAN_ABORT = 200_000

    // Per-factor body of [findAffineCandidate], also used by the touched-variable re-scan.
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
            // `x = (bound − Σ c_j·y_j) / c_x` stays integral for every partner assignment only when `c_x` divides
            // each `c_j` and the bound. A non-unit pivot must also be contained in this equality alone; one that
            // fails is left to the residue-class pass.
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
            // Aliases never inflate. Other folds skip rows at [FOLD_ABSORB_CAP] and, in underdetermined models,
            // fill-in past [WIDE_FILL_IN].
            if (!isAlias) {
                if (ws.anyOtherLinearAtAbsorbCap(di, x)) continue
                if (capWide && (ws.degreeOf(x) - 1).toLong() * termVars.size > WIDE_FILL_IN) continue
                // Leave x un-eliminated if folding it would overflow 64-bit arithmetic in some row it
                // rewrites — sound (x stays, solved directly) and avoids a wrong wrapped coefficient.
                if (ws.foldOverflowsLong(di, x, termVars, termCoeffs, constTerm, cancellation)) continue
            }
            // A global absorbs a single-partner `x = a·y + b` via Factor.substituteAffine as an index shift in Int
            // space, so a scale or offset beyond Int range takes the Linear-fold path instead.
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

    // New candidates are confined to touched variables, so `false` means the full scan would find nothing too.
    // `true` falls back to the full loop, whose stable-id order this test cannot reproduce.
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
                // O(touched · occurrences) on wide rows; bailing skips this firing, which is sound.
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

    private fun dividesAllPartnersAndBound(vars: IntArray, row: IntegerConstants, xi: Int): Boolean {
        val cx = row.coeff(xi)
        if (cx == 0L) return false
        if (row.bound % cx != 0L) return false
        for (j in vars.indices) if (j != xi && row.coeff(j) % cx != 0L) return false
        return true
    }

    // Read-only occurrence queries of the candidate scans, answered by both the mutable [AffineWorkingSet] and
    // the pristine-input [AffineSeedOccurrences] so a scan needs no working set.
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

        // Always false on the pristine seed.
        fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean

        // Ids a rewrite promoted into the pivot-candidate set. The list is append-only, so a reader remembering
        // this count reads later promotions from its tail via [promotedAt].
        fun promotedCount(): Int

        fun promotedAt(k: Int): Int

        fun foldOverflowsLong(
            defIdx: Int,
            x: Int,
            termVars: IntArray,
            termCoeffs: LongArray,
            constTerm: Long,
            cancellation: ExecutionControl = ExecutionControl.Never,
        ): Boolean

        fun aliasOverflowsLong(defIdx: Int, x: Int, replacement: Int): Boolean

        // The smallest id `>= from` that could head a pivot (a live [Linear] equality of arity `>= 2`), or [size].
        // A fold that promotes a row into that class is tracked; a dropped or degraded id may still be returned
        // and is rejected by the candidate gate.
        fun nextEqId(from: Int): Int
    }

    // Returns the lowest stable id whose candidacy the fold can newly establish, where the scan may resume.
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

/** An elimination `x = (constTerm + Σ termCoeffs·termVars) / divisor` recorded during [Presolve]. A residue-class
 *  [divisor] is exact on every value the partner's restricted domain admits. */
internal class AffineSub(
    val x: Int,
    val constTerm: Long,
    val termVars: IntArray,
    val termCoeffs: LongArray,
    val divisor: Long = 1,
)

// Latest first: an eliminated `x` may read a partner eliminated later, never one eliminated earlier.
internal fun List<AffineSub>.asRebuilds(): SourceRebuilds = SourceRebuilds(
    asReversed().map { RebuildStep.AffineValue(it.x, it.constTerm, it.termVars, it.termCoeffs, it.divisor) },
)

// Work units per scanned factor, tested term or folded fill-in entry, relative to one simplex operation.
internal const val AFFINE_WORK_WEIGHT = 10L
