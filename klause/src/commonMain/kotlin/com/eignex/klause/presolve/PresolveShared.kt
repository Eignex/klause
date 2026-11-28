package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

internal object PresolveShared {

    fun problemComplexity(factorCount: Int, domains: Array<IntDomain>): Long {
        var complexity = factorCount.toLong()
        for (domain in domains) {
            val span = domain.max - domain.min
            // Ordered endpoints can differ by more than Long.MAX_VALUE;
            // several representable spans can also overflow their sum.
            if (span < 0L || complexity > Long.MAX_VALUE - span) return Long.MAX_VALUE
            complexity += span
        }
        return complexity
    }

    // Null when [delta] refutes. A proved range intersects the column's declaration rather than replacing
    // it, so a non-contiguous declaration does not widen to its hull; the intersection can itself refute.
    fun Problem.withSourcePassDelta(delta: SourceDelta): Problem? {
        if (delta.infeasible) return null
        if (delta.isEmpty) return this
        val rebounded = delta.bounds?.let {
            if (it.crossedColumn() >= 0) return null
            declaredIntDomains.rebounded(it) ?: return null
        }
        val spec = if (rebounded == null) this else withDeclaredIntDomains(rebounded)
        return spec.withFactorPassDelta(delta)
    }

    private fun Problem.withFactorPassDelta(delta: SourceDelta): Problem {
        if (delta.droppedIndices.isEmpty() && delta.addedFactors.isEmpty()) return this
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
        return withFactors(kept.toTypedArray(), implied?.toBooleanArray())
    }

    // From-scratch counterpart of [PresolveSession.applyDelta], re-baked eagerly through [rebuildProblem].
    fun BakedProblem.withPassDelta(delta: PassDelta, bakeConfig: BakeConfig): BakedProblem {
        val kept = ArrayList<Factor>(factors.size - delta.droppedIndices.size + delta.addedFactors.size)
        val dropped = if (delta.droppedIndices.isEmpty()) {
            null
        } else {
            IntHashSet().apply { for (x in delta.droppedIndices) add(x) }
        }
        for (i in factors.indices) if (dropped == null || i !in dropped) kept.add(factors[i])
        kept.addAll(delta.addedFactors)
        return rebuildProblem(this, kept, delta.domains ?: rootIntDomains(), bakeConfig)
    }

    // For passes that rebuild their whole factor list. Survivors are matched by identity (`Factor` uses
    // reference equality), so a rewritten factor counts as dropped and added.
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

    fun sourceIdentityDelta(inputFactors: Array<Factor>, out: List<Factor>, rebuild: SourceRebuilds): SourceDelta {
        val delta = identityDelta(inputFactors, out)
        return SourceDelta(delta.droppedIndices, delta.addedFactors, rebuild = rebuild)
    }

    fun rebuildProblem(
        problem: BakedProblem,
        factors: List<Factor>,
        intDomains: Array<IntDomain> = problem.rootIntDomains(),
        bakeConfig: BakeConfig = BakeConfig.NONE,
        // Substitutions and objective views append Boolean coordinates, so every existing
        // factor still addresses the same variable.
        numBoolVars: Int = problem.numBoolVars,
        // Root deductions the [factors] alone do not imply, carried into the rebuild's own bake.
        seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
        cancellation: ExecutionControl = problem.cancellation,
    ): BakedProblem {
        // Inherit the pass-view mode: an already-folded input yields an already-folded output (the session
        // re-folds via incremental propagation); an eager input yields an eager rebuild.
        val base = BakedProblem(
            settings = problem.settings,
            semantics = problem.semantics,
            numBoolVars = numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = intDomains,
            factors = factors,
            seedDeductions = seedDeductions,
            cancellation = cancellation,
            alreadyFolded = problem.alreadyFolded,
            // Presolve never touches the LP-only real namespace; dropping it would lose the reals and silently
            // disable the leaf verdict.
            numRealVars = problem.numRealVars,
            realLower = problem.realLower,
            realUpper = problem.realUpper,
            // No pass renumbers the integer namespace, so the open-side marks still address the same columns.
            // A side a pass tightened only leaves the LP column wider than needed, which stays sound;
            // dropping the marks would cost the LP its open-range reasoning and the search its cutoff bound.
            packedOpenIntLo = problem.intBounds.openLowerBits,
            packedOpenIntHi = problem.intBounds.openUpperBits,
            projectionProviders = problem.projectionProviders,
            projectionSelection = problem.factorProjections?.let { captured ->
                { view -> captured.forEngineView(view) }
            },
        )
        return RootBaker.reseed(base, bakeConfig)
    }

    fun gcdOf(xs: LongArray, visit: () -> Unit = {}): Long {
        var g = 0L
        for (x in xs) {
            visit()
            g = gcd(g, x)
        }
        return g
    }

    // Saturates to [Long.MAX_VALUE] on overflow; reads endpoints only.
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

    fun structuralKeyMultiset(factors: List<Factor>): Map<StructuralKey, Int> {
        val base = HashMap<StructuralKey, Int>()
        for (f in factors) {
            val key = f.structuralKey()
            base[key] = (base[key] ?: 0) + 1
        }
        return base
    }

    // Whether [transform] is an automorphism of the constraint set. A factor it cannot map (null) fails.
    fun matchesMultiset(factors: List<Factor>, base: Map<StructuralKey, Int>, transform: (Factor) -> Factor?): Boolean {
        val counts = HashMap<StructuralKey, Int>(base.size)
        for (f in factors) {
            val key = (transform(f) ?: return false).structuralKey()
            val next = (counts[key] ?: 0) + 1
            if (next > (base[key] ?: 0)) return false
            counts[key] = next
        }
        return counts == base
    }
}
