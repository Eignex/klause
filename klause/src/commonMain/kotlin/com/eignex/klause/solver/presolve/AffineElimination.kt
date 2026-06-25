package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp

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
    fun eliminateAffineSingletons(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): AffineElimination {
        if (problem.numIntVars == 0) return AffineElimination(problem, emptyList())
        var factors = problem.factors.toList()
        val eliminated = BooleanArray(problem.numIntVars)
        val subs = ArrayList<AffineSub>()
        while (true) {
            val cand = findAffineCandidate(factors, eliminated, objectiveIntVars) ?: break
            factors = foldOutVariable(problem, factors, cand)
            eliminated[cand.x] = true
            subs.add(AffineSub(cand.x, cand.constTerm, cand.termVars, cand.termCoeffs))
        }
        // Residue-class doubletons (#522): a 2-term `a·x + b·y = c` with no unit pivot, where `x` is
        // contained, determines `x = (c − b·y)/a` only for the `y` values keeping it an in-domain
        // integer. Restrict `y` to those values (a domain modification, not a folded factor) and
        // reconstruct `x` with the divisor. Runs after the unit-pivot loop, so a residue partner `y`
        // is always a surviving variable.
        val domains = problem.intDomains.copyOf()
        while (true) {
            val r = findResidueCandidate(factors, eliminated, objectiveIntVars, domains) ?: break
            factors = factors.filterIndexed { i, _ -> i != r.defIdx }
            domains[r.y] = r.restrictedY
            eliminated[r.x] = true
            subs.add(AffineSub(r.x, r.constTerm, intArrayOf(r.y), intArrayOf(r.coeffY), divisor = r.divisor))
        }
        if (subs.isEmpty()) return AffineElimination(problem, emptyList())
        return AffineElimination(PresolveShared.rebuildProblem(problem, factors, domains), subs)
    }

    /** Cap on a residue partner's domain span: scanning each value to build the restricted domain is
     *  O(span), and a residue class on a very wide domain would flood it with holes, so skip above it. */
    private const val RESIDUE_DOMAIN_SPAN_CAP = 1024

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
        factors: List<Factor>,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
        domains: Array<IntDomain>,
    ): ResidueCandidate? {
        val occ = buildOccurrenceIndex(factors, eliminated.size)
        for (di in factors.indices) {
            val f = factors[di]
            if (f !is Linear || f.op != LinearOp.EQ || f.vars.size != 2) continue
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
                if (!isContained(occ, di, x)) continue
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
        }
        return null
    }

    /** Whether [x] occurs in no factor other than [defIdx]. */
    private fun isContained(occ: OccurrenceIndex, defIdx: Int, x: Int): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) if (occ.flat[k] != defIdx) return false
        return true
    }

    /** The partner domain restricted to the `y` values for which `x = (c − b·y)/a` is an integer
     *  inside [domX], or `null` if no such `y` exists (leave the constraint for propagation to fail). */
    private fun restrictPartnerDomain(domY: IntDomain, domX: IntDomain, a: Int, b: Int, c: Int): IntDomain? {
        val valid = ArrayList<Int>()
        for (y in domY.min..domY.max) {
            if (y !in domY) continue
            val num = c - b * y
            if (num % a != 0) continue
            val x = num / a
            if (x in domX) valid.add(y)
        }
        if (valid.isEmpty()) return null
        var d = domY.withMinAtLeast(valid.first()).withMaxAtMost(valid.last())
        val keep = valid.toHashSet()
        for (y in valid.first()..valid.last()) if (y !in keep && y in d) d = d.excludeValue(y)
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
        factors: List<Factor>,
        eliminated: BooleanArray,
        objectiveIntVars: Set<Int>,
    ): AffineCandidate? {
        // Occurrence index (variable id → the factor indices that mention it), built once per scan so
        // the per-candidate "where else does x occur" checks are O(occurrences-of-x) instead of a fresh
        // O(factors) linear scan each. On a large model that quadratic scan dominated affine
        // elimination; the index makes it linear in x's actual degree. Result-identical — purely a
        // faster lookup of the same factor membership. CSR layout (counts → offsets → flat) avoids a
        // per-variable list allocation, which matters when there are hundreds of thousands of variables.
        val occ = buildOccurrenceIndex(factors, eliminated.size)
        for (di in factors.indices) {
            val f = factors[di]
            if (f !is Linear || f.op != LinearOp.EQ || f.vars.size < 2) continue
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
                if (!isUnit && !isContained(occ, di, x)) continue
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
                // A single-partner affine `x = a·y + b` can also be projected out of non-linear globals
                // that absorb the affine view (via Factor.substituteAffine); a multi-partner relation
                // only folds into Linear factors.
                val singlePartnerSubstitutable = termVars.size == 1 &&
                    otherOccurrencesAffineSubstitutable(factors, occ, di, x, termCoeffs[0], constTerm, termVars[0])
                if (isAlias || otherOccurrencesAllLinear(factors, occ, di, x) || singlePartnerSubstitutable) {
                    return AffineCandidate(di, x, constTerm, termVars, termCoeffs, isAlias)
                }
            }
        }
        return null
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

    /** Variable id → the factor indices mentioning it, as a flat CSR (compressed sparse row): the
     *  factors for `x` are `flat[offsets[x] until offsets[x + 1]]`. A factor mentioning `x` more than
     *  once lists it once per occurrence, which the membership checks tolerate (a redundant, identical
     *  test). One allocation pair regardless of variable count — no per-variable list. */
    private class OccurrenceIndex(val offsets: IntArray, val flat: IntArray)

    private fun buildOccurrenceIndex(factors: List<Factor>, nVars: Int): OccurrenceIndex {
        val offsets = IntArray(nVars + 1)
        for (f in factors) for (v in f.intVars) offsets[v + 1]++
        for (v in 0 until nVars) offsets[v + 1] += offsets[v]
        val flat = IntArray(offsets[nVars])
        val cursor = offsets.copyOf()
        factors.forEachIndexed { i, f -> for (v in f.intVars) flat[cursor[v]++] = i }
        return OccurrenceIndex(offsets, flat)
    }

    /** Whether every factor other than [defIdx] that mentions [x] is a [Linear] (foldable). */
    private fun otherOccurrencesAllLinear(factors: List<Factor>, occ: OccurrenceIndex, defIdx: Int, x: Int): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) {
            val i = occ.flat[k]
            if (i != defIdx && factors[i] !is Linear) return false
        }
        return true
    }

    /** Whether every factor other than [defIdx] that mentions [x] can take the substitution
     *  `x = scale·y + offset`: a [Linear] folds it directly, any other factor must opt in via
     *  [Factor.substituteAffine] (a global that can represent the affine view, e.g. an Element index
     *  shift). */
    private fun otherOccurrencesAffineSubstitutable(
        factors: List<Factor>,
        occ: OccurrenceIndex,
        defIdx: Int,
        x: Int,
        scale: Int,
        offset: Int,
        y: Int,
    ): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) {
            val i = occ.flat[k]
            if (i == defIdx) continue
            val f = factors[i]
            if (f !is Linear && f.substituteAffine(x, scale, offset, y) == null) return false
        }
        return true
    }

    /** Drop the defining equality and remove `x`: for the alias case `x = y`, substitute `x → y`
     *  into every other factor via [Factor.remap] (any factor type); otherwise fold
     *  `x = constTerm + Σ termCoeffs·termVars` into every other Linear mentioning `x`. In both cases
     *  bounds on the term vars keep `x` within its domain. */
    private fun foldOutVariable(problem: Problem, factors: List<Factor>, c: AffineCandidate): List<Factor> {
        val out = ArrayList<Factor>(factors.size + 1)
        if (c.isAlias) {
            val boolMap = IntArray(problem.numBoolVars) { it }
            val intMap = IntArray(problem.numIntVars) { it }
            intMap[c.x] = c.termVars[0]
            for (i in factors.indices) if (i != c.defIdx) out.add(factors[i].remap(boolMap, intMap))
        } else {
            val singlePartner = c.termVars.size == 1
            for (i in factors.indices) {
                if (i == c.defIdx) continue
                val f = factors[i]
                out.add(
                    when {
                        f is Linear && c.x in f.vars -> foldAffineIntoLinear(f, c)

                        // Single-partner affine into a global the gate accepted (non-null substitute).
                        singlePartner && c.x in f.intVars ->
                            requireNotNull(f.substituteAffine(c.x, c.termCoeffs[0], c.constTerm, c.termVars[0])) {
                                "substituteAffine returned null for a factor accepted by the candidate gate"
                            }

                        else -> f
                    },
                )
            }
        }
        out.addAll(domainBoundsOnTerms(problem.intDomains[c.x], c))
        return out
    }

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
 * Reduced problem from [Presolve.eliminateAffineSingletons] plus the data to rebuild the
 * eliminated variables. Solve [problem], then pass the solution through [reconstruct] to recover
 * a solution of the original problem.
 */
class AffineElimination internal constructor(
    /** The problem with affine-defined variables eliminated. */
    val problem: Problem,
    private val subs: List<AffineSub>,
) {
    /** Recover the eliminated variables in a solution [sample] of [problem]. Processed in reverse
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
