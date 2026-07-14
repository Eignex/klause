package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet

internal object AffineSingletons {

    /**
     * Affine variable elimination (#318/#335/#445). Eliminates an integer variable `x` defined by an
     * `n`-term equality `c_x·x + Σ_j c_j·y_j = b` with a **unit** pivot coefficient `|c_x| = 1`, i.e.
     * `x = B + Σ_j A_j·y_j` where `A_j = −c_x·c_j` and `B = c_x·b`. The defining equality is dropped,
     * the affine relation is folded into every other [Linear] that mentions `x`, and bounds on the
     * `y_j` are added so `x` stays inside its declared domain; `x` becomes unconstrained and is
     * rebuilt from the solution via [AffineElimination.reconstruct].
     *
     * Two-term equalities are the common case (an alias or a one-partner definition); the `n`-term
     * generalisation (#445) projects out an *implied-free* variable defined by a longer sum (e.g. an
     * auxiliary `x = y1 + y2 − y3` used nowhere a global needs it). The unit-pivot restriction keeps
     * every folded coefficient integral unconditionally; a **non-unit** pivot is admitted only for an
     * implied-free (contained) `x` and only when `c_x` divides every other coefficient and the bound
     * (#601), so `x = b/c_x + Σ_j (−c_j/c_x)·y_j` stays integral for *all* partner assignments. A
     * non-unit pivot that fails the divisibility test is left to the residue-class doubleton below.
     *
     * For the **alias** case `x = y` (`n = 2`, `A = 1`, `B = 0`) the substitution `x → y` is a plain
     * variable rename, applied to *every* factor via [Factor.remap] regardless of type (#364).
     * Otherwise the relation folds into every other [Linear]; a **single-partner** `x = a·y + b`
     * additionally projects out of any non-linear factor that can represent the affine view via
     * [Factor.substituteAffine] (an Element index shift, a Table column rewrite). A multi-partner
     * `B + Σ A_j·y_j` only folds into [Linear] factors — a global keyed on `x`'s value as a sum can't
     * represent it. The #318 contained slice (`x` in no other factor) is the zero-fold special case,
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
    ): PassDelta {
        if (problem.numIntVars == 0) return PassDelta()
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
                    objectiveIntVars,
                    capWide,
                    problem.intDomains,
                    cancellation,
                )
            } else {
                findAffineCandidate(seed, 0, eliminated, objectiveIntVars, capWide, cancellation) != null ||
                    findResidueCandidate(seed, eliminated, objectiveIntVars, problem.intDomains, cancellation) != null
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
        // The candidate scan is lowest-stable-id-first. A fold only changes the content of the factors it
        // rewrites, so no factor below their minimum id can newly become a candidate; resume the next scan
        // from that minimum instead of restarting at 0, turning the loop from O(eliminations · factors) into
        // O(factors + Σ rewrites). Every factor below [scanFrom] is a confirmed non-candidate (unchanged
        // since it was last examined), so the selection order — and the eliminations — are unchanged.
        var scanFrom = 0
        // Cumulative substitution fill-in (Σ pivot-degree · substituted-terms). A dense chain of wide
        // folds is superlinear and can dominate presolve on large models where it barely simplifies;
        // once the budget is spent the loop stops, leaving the remaining affine-defined variables to be
        // solved directly (sound). The budget is far above what productive models spend.
        var fillIn = 0L
        while (!cancellation()) {
            val cand = findAffineCandidate(ws, scanFrom, eliminated, objectiveIntVars, capWide, cancellation) ?: break
            fillIn += ws.degreeOf(cand.x).toLong() * cand.termVars.size
            scanFrom = foldOutVariable(problem, ws, cand)
            eliminated[cand.x] = true
            subs.add(AffineSub(cand.x, cand.constTerm, cand.termVars, cand.termCoeffs))
            if (fillIn > AFFINE_FILL_IN_BUDGET) break
        }
        // Residue-class doubletons (#522): a 2-term `a·x + b·y = c` with no unit pivot, where `x` is
        // contained, determines `x = (c − b·y)/a` only for the `y` values keeping it an in-domain
        // integer. Restrict `y` to those values (a domain modification, not a folded factor) and
        // reconstruct `x` with the divisor. Runs after the unit-pivot loop, so a residue partner `y`
        // is always a surviving variable.
        val domains = problem.intDomains.copyOf()
        while (!cancellation()) {
            val r = findResidueCandidate(ws, eliminated, objectiveIntVars, domains, cancellation) ?: break
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
        objectiveIntVars: Set<Int>,
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
        objectiveIntVars: Set<Int>,
        domains: Array<IntDomain>,
    ): ResidueCandidate? {
        val f = ws.factorAt(di) ?: return null
        if (f !is Linear || f.op != LinearOp.EQ || f.vars.size != 2) return null
        val fBound = f.bound
        for (xi in 0..1) {
            val x = f.vars[xi]
            val y = f.vars[1 - xi]
            val a = f.coeffs[xi]
            val b = f.coeffs[1 - xi]
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
        objectiveIntVars: Set<Int>,
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
        objectiveIntVars: Set<Int>,
        capWide: Boolean,
        cancellation: Cancellation = Cancellation.Never,
    ): AffineCandidate? {
        val f = ws.factorAt(di) ?: return null
        if (f !is Linear || f.op != LinearOp.EQ || f.vars.size < 2) return null
        for (xi in f.vars.indices) {
            // A single wide row can carry thousands of pivot candidates each running an O(occurrences)
            // overflow check, so poll the deadline between them (and inside the check below).
            if ((xi and CANCEL_POLL_MASK) == 0 && cancellation()) return null
            val x = f.vars[xi]
            val cx = f.coeffs[xi]
            if (eliminated[x] || x in objectiveIntVars) continue
            // The substitution `x = (bound − Σ c_j·y_j) / c_x` stays integral for *every*
            // assignment of the partners only when `c_x` divides each `c_j` and the bound — for a
            // unit pivot trivially, and for a non-unit pivot exactly when `x` is implied-free
            // (contained in this equality alone) and `c_x | gcd(c_j, bound)` (#445/#601). A
            // non-unit pivot that fails the divisibility test would fold non-integral coefficients,
            // so it is left for the residue-class doubleton pass or for propagation.
            val isUnit = cx == 1L || cx == -1L
            if (!isUnit && !dividesAllPartnersAndBound(f, xi)) continue
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
                termCoeffs[w] = -f.coeffs[j] / cx
                w++
            }
            if (partnerEliminated) continue
            val constTerm = f.bound / cx
            // The alias case (n = 2, A = 1, B = 0, i.e. x = y) substitutes into ANY factor via
            // remap; otherwise x must occur only in foldable Linear factors. A contained non-unit
            // pivot has no other occurrences, so `otherOccurrencesAllLinear` holds vacuously.
            val isAlias = termVars.size == 1 && termCoeffs[0] == 1L && constTerm == 0L
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
        objectiveIntVars: Set<Int>,
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

    /** Whether the pivot coefficient `f.coeffs(xi)` divides every other coefficient and the bound of
     *  [f], so substituting out the pivot variable keeps all folded coefficients and the constant term
     *  integral. */
    private fun dividesAllPartnersAndBound(f: Linear, xi: Int): Boolean {
        val cx = f.coeffs[xi]
        if (cx == 0L) return false
        if (f.bound % cx != 0L) return false
        for (j in f.vars.indices) if (j != xi && f.coeffs[j] % cx != 0L) return false
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

        /** The smallest stable id `>= from` that could head a pivot — a live [Linear] equality of arity
         *  `>= 2` — or [size] if none remains, in ascending order. Lets the candidate scan skip the
         *  inequality/global bulk (which can never be a candidate) instead of testing every id, while
         *  still visiting the identical candidates in the identical order a walk over every id would: a
         *  fold that promotes a row into the candidate class is tracked, and a dropped or degraded id may
         *  still be returned and is re-rejected by the candidate gate. */
        fun nextEqId(from: Int): Int
    }

    /** Whether [f] could head an affine or residue pivot: a [Linear] equality of arity >= 2. */
    private fun isEqCand(f: Factor?): Boolean = f is Linear && f.op == LinearOp.EQ && f.vars.size >= 2

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
                if (id != defIdx && factors[id] !is Linear) return false
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
            for (k in occ.offsets[x] until occ.offsets[x + 1]) {
                // A high-degree pivot's overflow check walks every row it mentions; poll so one candidate's
                // check cannot outrun the budget. Treat a bail as "would overflow" — skipping the fold is sound.
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return true
                val id = occ.flat[k]
                val f = factors[id]
                if (id != defIdx && f is Linear && foldRowOverflowsLong(
                        f,
                        x,
                        termVars,
                        termCoeffs,
                        constTerm,
                    )
                ) {
                    return true
                }
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
                if (f !is Linear && !f.canSubstituteAffine(x, scale, offset, replacement)) return false
            }
            return true
        }

        // The seed is the pristine input before any fold, so no row has absorbed one yet.
        override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean = false
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
            for (v in f.intVars) intOcc[v].removeValue(id)
            slots[id] = null
        }

        /** Replace live stable id [id] — currently holding [prev] — with [next], moving its occurrence
         *  entries from [prev]'s variables to [next]'s and recording that it absorbed a fold. */
        private fun replace(id: Int, prev: Factor, next: Factor) {
            absorbed[id] = absorbed[id] + 1
            for (v in prev.intVars) intOcc[v].removeValue(id)
            slots[id] = next
            for (v in next.intVars) intOcc[v].add(id)
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
                if (id != defIdx && slots[id] !is Linear) return false
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
            for (k in 0 until occ.size) {
                // A high-degree pivot's overflow check walks every row it mentions; poll so one candidate's
                // check cannot outrun the budget. Treat a bail as "would overflow" — skipping the fold is sound.
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return true
                val id = occ[k]
                val f = slots[id]
                if (id != defIdx && f is Linear && foldRowOverflowsLong(
                        f,
                        x,
                        termVars,
                        termCoeffs,
                        constTerm,
                    )
                ) {
                    return true
                }
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
                if (f !is Linear && !f.canSubstituteAffine(x, scale, offset, replacement)) return false
            }
            return true
        }

        override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean {
            val occ = intOcc[x]
            for (k in 0 until occ.size) {
                val id = occ[k]
                if (id == defIdx) continue
                val f = slots[id] ?: continue
                if (f is Linear && absorbed[id] >= FOLD_ABSORB_CAP) return true
            }
            return false
        }

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
                    f is Linear && c.x in f.vars -> foldAffineIntoLinear(f, c)

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
            for (bound in domainBoundsOnTerms(problem.intDomains[c.x], c)) append(bound)
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
                val remapped = f.remap(boolMap, intMap)
                slots[id] = remapped
                intOcc[y].removeValue(id)
                if (remapped.intVars.contains(y)) intOcc[y].add(id)
            }
            intOcc[c.x].clear()
            drop(c.defIdx)
            for (bound in domainBoundsOnTerms(problem.intDomains[c.x], c)) append(bound)
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
        val ix = l.vars.indexOf(c.x)
        val cX = l.coeffs[ix]
        val newVars = IntArray(l.vars.size - 1 + c.termVars.size)
        // Long throughout: both [l]'s coefficients and the fold candidate's are wide-capable, and the
        // candidate gate declined any pivot whose fold would overflow, so the products below are safe.
        val newCoeffs = LongArray(newVars.size)
        var w = 0
        for (j in l.vars.indices) {
            if (j == ix) continue
            newVars[w] = l.vars[j]
            newCoeffs[w] = l.coeffs[j]
            w++
        }
        for (k in c.termVars.indices) {
            newVars[w] = c.termVars[k]
            newCoeffs[w] = cX * c.termCoeffs[k]
            w++
        }
        return Linear(newCoeffs, newVars, l.op, l.bound - cX * c.constTerm)
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
 *  [Presolve.eliminateAffineSingletons]. [divisor] is `1` for the unit-pivot cases and the
 *  pivot coefficient for a residue-class doubleton (#522), where the division is always exact on the
 *  values the partner's restricted domain admits. */
internal class AffineSub(
    val x: Int,
    val constTerm: Long,
    val termVars: IntArray,
    val termCoeffs: LongArray,
    val divisor: Long = 1,
)

/**
 * The affine eliminations [Presolve.eliminateAffineSingletons] made, holding the data to rebuild the
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
): Boolean {
    val xi = f.vars.indexOf(x)
    if (xi < 0) return false
    val cX = f.coeffs[xi]
    // Fast path: if every magnitude the fold combines is below 2^31, no product `cX·c` reaches 2^62 and no
    // sum reaches 2^63, so the fold cannot overflow — skip the per-term `indexOf` + exact arithmetic below,
    // which is O(arity · row-arity) and dominates the pass on dense wide rows (DiamondFree/SMPT). The two
    // scans here are O(arity + row-arity); the exact path only runs when some coefficient is genuinely huge.
    if (fitsHalfLong(cX) && fitsHalfLong(constTerm) && fitsHalfLong(f.bound)) {
        var big = false
        for (c in termCoeffs) {
            if (!fitsHalfLong(c)) {
                big = true
                break
            }
        }
        if (!big) {
            for (c in f.coeffs) {
                if (!fitsHalfLong(c)) {
                    big = true
                    break
                }
            }
        }
        if (!big) return false
    }
    return try {
        subExact(f.bound, mulExact(cX, constTerm))
        for (k in termVars.indices) {
            val prod = mulExact(cX, termCoeffs[k])
            val yi = f.vars.indexOf(termVars[k])
            if (yi >= 0) addExact(f.coeffs[yi], prod)
        }
        false
    } catch (_: LpOverflowException) {
        true
    }
}

/** True when `|v| < 2^31`, so a product of two such values stays below 2^62 and a sum below 2^63 — the
 *  bound the overflow fast-path in [foldRowOverflowsLong] relies on. */
private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)
