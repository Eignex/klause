package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

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
    ): PassDelta {
        if (problem.numIntVars == 0) return PassDelta()
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
                )
            } else {
                findAffineCandidate(seed, 0, eliminated, objectiveIntVars, capWide) != null ||
                    findResidueCandidate(seed, eliminated, objectiveIntVars, problem.intDomains) != null
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
        while (!cancellation()) {
            val cand = findAffineCandidate(ws, scanFrom, eliminated, objectiveIntVars, capWide) ?: break
            scanFrom = foldOutVariable(problem, ws, cand)
            eliminated[cand.x] = true
            subs.add(AffineSub(cand.x, cand.constTerm, cand.termVars, cand.termCoeffs))
        }
        // Residue-class doubletons (#522): a 2-term `a·x + b·y = c` with no unit pivot, where `x` is
        // contained, determines `x = (c − b·y)/a` only for the `y` values keeping it an in-domain
        // integer. Restrict `y` to those values (a domain modification, not a folded factor) and
        // reconstruct `x` with the divisor. Runs after the unit-pivot loop, so a residue partner `y`
        // is always a surviving variable.
        val domains = problem.intDomains.copyOf()
        while (!cancellation()) {
            val r = findResidueCandidate(ws, eliminated, objectiveIntVars, domains) ?: break
            ws.drop(r.defIdx)
            domains[r.y] = r.restrictedY
            eliminated[r.x] = true
            subs.add(AffineSub(r.x, r.constTerm, intArrayOf(r.y), intArrayOf(r.coeffY), divisor = r.divisor))
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

    /** A residue-class doubleton `a·x + b·y = c` (no unit pivot) at [defIdx]: `x` is contained and
     *  reconstructed as `(constTerm + coeffY·y) / divisor` over the [restrictedY] partner domain. */
    private class ResidueCandidate(
        val defIdx: Int,
        val x: Int,
        val y: Int,
        val constTerm: Int,
        val coeffY: Int,
        val divisor: Int,
        val restrictedY: IntDomain,
    )

    private fun findResidueCandidate(
        ws: FactorOcc,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
        domains: Array<IntDomain>,
    ): ResidueCandidate? {
        for (di in 0 until ws.size) {
            residueCandidateInFactor(
                ws,
                di,
                eliminated,
                objectiveIntVars,
                domains,
            )?.let { return it }
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
        for (xi in 0..1) {
            val x = f.vars[xi]
            val y = f.vars[1 - xi]
            val a = f.coeffs[xi]
            val b = f.coeffs[1 - xi]
            // The unit-pivot loop already ran, so a remaining 2-term EQ has no unit coefficient;
            // guard anyway. `x` must be contained (a non-unit fold can't stay integral) and free.
            // `y`'s domain is restricted below, so it too must stay clear of the objective — the
            // pass leaves every objective variable untouched.
            if (a == 0 || a == 1 || a == -1 || eliminated[x] || eliminated[y] || x == y) continue
            if (x in objectiveIntVars || y in objectiveIntVars) continue
            if (!ws.isContained(di, x)) continue
            val domY = domains[y]
            if (domY.max.toLong() - domY.min.toLong() > RESIDUE_DOMAIN_SPAN_CAP) continue
            val restricted = restrictPartnerDomain(domY, domains[x], a, b, f.bound) ?: continue
            return ResidueCandidate(
                di,
                x,
                y,
                constTerm = f.bound,
                coeffY = -b,
                divisor = a,
                restrictedY = restricted,
            )
        }
        return null
    }

    /** The partner domain restricted to the `y` values for which `x = (c − b·y)/a` is an integer
     *  inside [domX], or `null` if no such `y` exists (leave the constraint for propagation to fail). */
    private fun restrictPartnerDomain(domY: IntDomain, domX: IntDomain, a: Int, b: Int, c: Int): IntDomain? {
        val valid = IntArrayList()
        for (y in domY.min..domY.max) {
            if (y !in domY) continue
            val num = c - b * y
            if (num % a != 0) continue
            val x = num / a
            if (x in domX) valid.add(y)
        }
        if (valid.isEmpty()) return null
        var d = domY.withMinAtLeast(valid[0]).withMaxAtMost(valid.last())
        val keep = IntHashSet()
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
        val constTerm: Int,
        val termVars: IntArray,
        val termCoeffs: IntArray,
        val isAlias: Boolean,
    )

    private fun findAffineCandidate(
        ws: FactorOcc,
        start: Int,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
        capWide: Boolean,
    ): AffineCandidate? {
        // The scan walks the live factors in stable-id order — the same order a fresh compacted list would
        // present — so it picks candidates in the identical sequence a full rebuild would.
        for (di in start until ws.size) {
            candidateInFactor(
                ws,
                di,
                eliminated,
                objectiveIntVars,
                capWide,
            )?.let { return it }
        }
        return null
    }

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
    ): AffineCandidate? {
        val f = ws.factorAt(di) ?: return null
        if (f !is Linear || f.op != LinearOp.EQ || f.vars.size < 2) return null
        for (xi in f.vars.indices) {
            val x = f.vars[xi]
            val cx = f.coeffs[xi]
            if (eliminated[x] || x in objectiveIntVars) continue
            // The substitution `x = (bound − Σ c_j·y_j) / c_x` stays integral for *every*
            // assignment of the partners only when `c_x` divides each `c_j` and the bound — for a
            // unit pivot trivially, and for a non-unit pivot exactly when `x` is implied-free
            // (contained in this equality alone) and `c_x | gcd(c_j, bound)` (#445/#601). A
            // non-unit pivot that fails the divisibility test would fold non-integral coefficients,
            // so it is left for the residue-class doubleton pass or for propagation.
            val isUnit = cx == 1 || cx == -1
            if (!isUnit && !dividesAllPartnersAndBound(f, xi)) continue
            if (!isUnit && !ws.isContained(di, x)) continue
            // x = B + Σ A_j·y_j, with B = bound / c_x and A_j = −c_j / c_x for the other terms y_j;
            // for a unit pivot the divisions are exact by definition.
            val termVars = IntArray(f.vars.size - 1)
            val termCoeffs = IntArray(f.vars.size - 1)
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
            val isAlias = termVars.size == 1 && termCoeffs[0] == 1 && constTerm == 0
            // Defer a fold that would inflate rather than simplify (leave the variable). Aliases are pure
            // renames that never inflate, so they always proceed. Otherwise skip a pivot whose fold would
            // rewrite a row that has already absorbed [FOLD_ABSORB_CAP] folds (bounding the accumulating-row
            // pathology), and additionally cap total fill-in at the tighter [WIDE_FILL_IN] in underdetermined
            // models.
            if (!isAlias) {
                if (ws.anyOtherLinearAtAbsorbCap(di, x)) continue
                if (capWide && (ws.degreeOf(x) - 1).toLong() * termVars.size > WIDE_FILL_IN) continue
            }
            // A single-partner affine `x = a·y + b` can also be projected out of non-linear globals
            // that absorb the affine view (via Factor.substituteAffine); a multi-partner relation
            // only folds into Linear factors.
            val singlePartnerSubstitutable = termVars.size == 1 &&
                ws.otherOccurrencesAffineSubstitutable(di, x, termCoeffs[0], constTerm, termVars[0])
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
    ): Boolean {
        val checked = IntHashSet()
        for (x in touchedVars) {
            val degree = seed.degreeOf(x)
            for (k in 0 until degree) {
                val di = seed.occurrenceAt(x, k)
                if (!checked.add(di)) continue
                if (candidateInFactor(seed, di, eliminated, objectiveIntVars, capWide) != null) return true
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
        if (cx == 0) return false
        if (f.bound % cx != 0) return false
        for (j in f.vars.indices) if (j != xi && f.coeffs[j] % cx != 0) return false
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

                    // Single-partner affine into a global the gate accepted (non-null substitute).
                    singlePartner ->
                        requireNotNull(f.substituteAffine(c.x, c.termCoeffs[0], c.constTerm, c.termVars[0])) {
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
            for (id in slots.indices) {
                if (id == c.defIdx) continue
                val f = slots[id] ?: continue
                slots[id] = f.remap(boolMap, intMap)
            }
            for (id in touched) {
                if (id == c.defIdx) continue
                val f = slots[id] ?: continue
                intOcc[y].removeValue(id)
                for (v in f.intVars) if (v == y) intOcc[y].add(id)
            }
            intOcc[c.x].clear()
            drop(c.defIdx)
            for (bound in domainBoundsOnTerms(problem.intDomains[c.x], c)) append(bound)
            // A rename rewrites every live factor, so any factor's candidacy can change: rescan from 0.
            return 0
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
        val newCoeffs = IntArray(newVars.size)
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
    private fun domainBoundsOnTerms(domX: IntDomain, c: AffineCandidate): List<Factor> = listOf(
        Linear(c.termCoeffs.copyOf(), c.termVars.copyOf(), LinearOp.LE, domX.max - c.constTerm),
        Linear(c.termCoeffs.copyOf(), c.termVars.copyOf(), LinearOp.GE, domX.min - c.constTerm),
    )
}

/** A single affine elimination `x = (constTerm + Σ termCoeffs·termVars) / divisor` recorded by
 *  [Presolve.eliminateAffineSingletons]. [divisor] is `1` for the unit-pivot cases and the
 *  pivot coefficient for a residue-class doubleton (#522), where the division is always exact on the
 *  values the partner's restricted domain admits. */
internal class AffineSub(
    val x: Int,
    val constTerm: Int,
    val termVars: IntArray,
    val termCoeffs: IntArray,
    val divisor: Int = 1,
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
            ints[s.x] = if (s.divisor == 1) v else v / s.divisor
        }
        return Sample(sample.bools, ints)
    }
}
