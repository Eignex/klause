package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.isFoldedPropagationView
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** Small math and problem-rebuild helpers shared across the presolve passes. */
internal object PresolveShared {

    /** Apply a factor-only [delta] while retaining the canonical source representation. */
    fun Problem.withSourcePassDelta(delta: PassDelta): Problem {
        require(delta.domains == null && delta.reconstruct == null) {
            "source presolve may only rewrite factors"
        }
        if (delta.isEmpty) return this
        val dropped = if (delta.droppedIndices.isEmpty()) {
            null
        } else {
            IntHashSet().apply { for (index in delta.droppedIndices) add(index) }
        }
        val capacity = factors.size - delta.droppedIndices.size + delta.addedFactors.size
        val kept = ArrayList<Factor>(capacity)
        val implied = impliedFactorMask?.let { ArrayList<Boolean>(capacity) }
        for (index in factors.indices) {
            if (dropped == null || index !in dropped) {
                kept.add(factors[index])
                implied?.add(impliedFactorMask[index])
            }
        }
        for (factor in delta.addedFactors) {
            kept.add(factor)
            implied?.add(false)
        }
        return Problem(
            numBoolVars = numBoolVars,
            numIntVars = numIntVars,
            intColumns = intColumns,
            factors = kept.toTypedArray(),
            impliedFactorMask = implied?.toBooleanArray(),
            hasSymmetryBreaking = hasSymmetryBreaking,
            numRealVars = numRealVars,
            realLower = realLower,
            realUpper = realUpper,
            modelBounds = intBounds,
        )
    }

    /**
     * Materialize the problem that results from applying [delta] to [this] — the fresh-path counterpart
     * of [PresolveSession.applyDelta]. The next factor list is [this]'s factors with [PassDelta.droppedIndices]
     * removed (kept in order) followed by [PassDelta.addedFactors]; the domains are the delta's own
     * ([PassDelta.domains]) or [this]'s when it leaves them alone. Re-baked eagerly through
     * [rebuildProblem] (the per-firing-pass rebuild the fresh path always did), so it is a plain solver
     * `Problem` whose `baked` folds the delta's narrowings and any dependent tightenings.
     */
    fun Problem.withPassDelta(delta: PassDelta, bakeConfig: BakeConfig): Problem {
        val kept = ArrayList<Factor>(factors.size - delta.droppedIndices.size + delta.addedFactors.size)
        val dropped = if (delta.droppedIndices.isEmpty()) {
            null
        } else {
            IntHashSet().apply { for (x in delta.droppedIndices) add(x) }
        }
        for (i in factors.indices) if (dropped == null || i !in dropped) kept.add(factors[i])
        kept.addAll(delta.addedFactors)
        return rebuildProblem(this, kept, delta.domains ?: requireFiniteIntDomains().copyOf(), bakeConfig)
    }

    /**
     * The [PassDelta] taking [inputFactors] to [out] — a rewritten factor list where every survivor is
     * identity-equal to an input factor (`Factor` uses reference equality, so a plain [HashMap] keys by
     * identity). Adds every [out] factor absent from the input; drops every input index whose factor is
     * not among [out]'s survivors. For passes that rebuild their whole factor list (variable renames,
     * substitutions) rather than deciding keep/drop per input index.
     */
    fun identityDelta(
        inputFactors: Array<Factor>,
        out: List<Factor>,
        domains: Array<IntDomain>? = null,
        reconstruct: ((Sample) -> Sample)? = null,
    ): PassDelta {
        val idByFactor = HashMap<Factor, Int>(inputFactors.size)
        for (i in inputFactors.indices) idByFactor[inputFactors[i]] = i
        val kept = IntHashSet()
        val added = ArrayList<Factor>()
        for (f in out) {
            val id = idByFactor[f]
            if (id != null) kept.add(id) else added.add(f)
        }
        val dropped = IntArrayList()
        for (i in inputFactors.indices) if (i !in kept) dropped.add(i)
        return PassDelta(dropped.toIntArray(), added, domains, reconstruct)
    }

    fun rebuildProblem(
        problem: Problem,
        factors: List<Factor>,
        intDomains: Array<IntDomain> = problem.requireFiniteIntDomains().copyOf(),
        bakeConfig: BakeConfig = BakeConfig.NONE,
        // Extends the Boolean namespace for the one transform that mints variables
        // ([BinaryColumnSubstitution]): ids `[problem.numBoolVars, numBoolVars)` are the fresh ones, so
        // every existing factor still addresses the same variable.
        numBoolVars: Int = problem.numBoolVars,
    ): Problem {
        // Inherit the pass-view mode: a pass fed a cheap already-folded input returns a cheap already-folded
        // output (the session re-folds via incremental propagation); a fresh-path rebuild stays eager.
        val base = BakedProblem(
            numBoolVars = numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = intDomains,
            factors = factors,
            alreadyFolded = problem.isFoldedPropagationView,
            // The LP-only continuous columns are a separate namespace presolve never touches (real-bearing
            // rows are guarded out of every pass, and int renumbering leaves real ids alone), so carry it
            // through unchanged — else the solve loses the reals and the leaf verdict silently no-ops.
            numRealVars = problem.numRealVars,
            realLower = problem.realLower,
            realUpper = problem.realUpper,
            // No pass renumbers the integer namespace, so the marks recording which sides the front-end
            // invented still address the same columns. A side a pass has since tightened only leaves the
            // LP column wider than it need be — the relaxation stays a superset of the model, so every
            // bound read off it is still sound. Dropping them instead would cost the LP its open-range
            // reasoning and the search its objective-cutoff bound on exactly those columns.
            packedOpenIntLo = problem.intBounds.openLowerBits,
            packedOpenIntHi = problem.intBounds.openUpperBits,
        )
        // An already-folded pass view never bakes (nothing reads `Problem.baked`), so [RootBaker.reseed] leaves
        // it untouched; with no probing tier enabled the plain base bake stands. Otherwise the reseed runs
        // [RootBaker] against the base-baked problem and returns a fresh eager `Problem` whose
        // `Problem.baked` carries the failed-literal / SAC deductions — the kernel's former self-bake, now
        // driven from the presolve lane.
        return RootBaker.reseed(base, bakeConfig)
    }

    fun gcdOf(xs: LongArray): Long {
        var g = 0L
        for (x in xs) g = gcd(g, x)
        return g
    }

    /** The widest integer-variable domain span (`max − min`, saturating to [Long.MAX_VALUE] on overflow),
     *  or 0 when there are no integer variables. A cheap O(numIntVars) gate for the span-sensitive
     *  presolve steps — it reads each domain's endpoints only, never enumerating values. */
    fun maxIntSpan(problem: Problem): Long {
        var widest = 0L
        for (v in 0 until problem.numIntVars) {
            val d = problem.finiteIntDomain(v)
            val span = d.max - d.min
            widest = maxOf(widest, if (span < 0L) Long.MAX_VALUE else span)
        }
        return widest
    }

    private fun gcd(a: Long, b: Long): Long {
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    fun divAll(xs: LongArray, g: Long): LongArray = LongArray(xs.size) { xs[it] / g }

    /** Multiset of `Factor.structuralKey` over [factors] — the constraint set keyed for comparison
     *  against a transform of itself. */
    fun structuralKeyMultiset(factors: List<Factor>): Map<StructuralKey, Int> {
        val base = HashMap<StructuralKey, Int>()
        for (f in factors) {
            val key = f.structuralKey()
            base[key] = (base[key] ?: 0) + 1
        }
        return base
    }

    /** Whether applying [transform] to every factor in [factors] reproduces the [base] multiset of
     *  structural keys — i.e. the transform is an automorphism of the constraint set. [transform]
     *  returns `null` for a factor it cannot map (unkeyable / un-remappable), which fails the match.
     *  The `next > base[key]` short-circuit bails as soon as any key over-counts, before reading the
     *  whole factor list. */
    fun matchesMultiset(factors: List<Factor>, base: Map<StructuralKey, Int>, transform: (Factor) -> Factor?): Boolean {
        val counts = HashMap<StructuralKey, Int>(base.size)
        for (f in factors) {
            val key = (transform(f) ?: return false).structuralKey()
            val next = (counts[key] ?: 0) + 1
            if (next > (base[key] ?: 0)) return false // already can't match the multiset
            counts[key] = next
        }
        return counts == base
    }
}
