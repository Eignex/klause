package com.eignex.klause.presolve

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import kotlin.random.Random

/**
 * Bake-time root probing, moved out of the kernel [Problem] into the presolve lane.
 *
 * A [Problem] only ever performs the cheap unconditional *base bake* (one `propagate(Assumptions.None)`
 * folded into its domains). The heavier failed-literal / SAC probing tiers — which are only ever enabled
 * by the compile / presolve layers — live here so the kernel never depends on `presolve` (the
 * `solver → presolve → solver` cycle the package split exists to prevent).
 *
 * [bake] runs the probing fixpoint against an already-base-baked [Problem] (probing calls
 * `problem.propagate(assumptions)` exactly as the kernel used to) and returns the accumulated
 * deductions. The pipeline feeds that back as [Problem]'s `seedDeductions`, so the rebuilt problem's
 * [Problem.baked] carries the probing pins / bound tightenings / holes.
 */
object RootBaker {

    /**
     * Re-seed a base-baked [problem] with the [config]-enabled probing tiers: run [bake] and, when it
     * found extra deductions, return a fresh eager [Problem] over the same factors / domains whose
     * [Problem.baked] carries them. Returns [problem] unchanged when no tier is enabled, the problem is
     * a [Problem.preFolded] pass view (which never bakes), or the base bake is already `Unsat`. This is
     * the central re-probe point for the presolve fresh path and the LP harvest — the kernel never
     * initiates it, so no `solver → presolve` cycle.
     */
    fun reseed(problem: Problem, config: BakeConfig): Problem {
        if (!config.anyEnabled || problem.preFolded) return problem
        val extra = bake(problem, config)
        if (extra === problem.baked) return problem
        return Problem(
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = Array(problem.numIntVars) { problem.intDomains[it] },
            factors = problem.factors,
            seedDeductions = extra,
            cancellation = problem.cancellation,
            impliedFactorMask = problem.impliedFactorMask,
            hasSymmetryBreaking = problem.hasSymmetryBreaking,
        )
    }

    /**
     * Run the [config]-enabled probing tiers against the base-baked [problem] and return the resulting
     * deductions — the base bake merged with every probing finding, ready to seed a rebuilt [Problem].
     * Returns [problem]'s existing bake unchanged when no tier is enabled or the base bake is already
     * `Unsat`. Cancellation is polled between phases and probes; a fired deadline yields a sound partial
     * bake (probing only ever tightens).
     */
    fun bake(problem: Problem, config: BakeConfig): PropagationResult {
        val base = problem.baked
        if (base !is PropagationResult.Implied) return base
        if (!config.anyEnabled) return base
        // SAC probing fires `propagate` repeatedly; stop entering new probe phases once the bake
        // deadline has passed (each phase below also polls between its own probes).
        if (problem.cancellation()) return base
        var result: PropagationResult = base
        if (config.probeFailedLiterals) {
            result = probeFreeBools(problem, base)
            if (result is PropagationResult.Unsat) return result
        }
        if (config.probeIntBounds || config.probeIntHoles) {
            // wdeg state shared across bound-SAC and hole-SAC: a probe failure under bound-SAC raises
            // factor weights that then steer hole-SAC's first iteration, and vice versa.
            val factorWeights = DoubleArray(problem.numFactors) { 1.0 }
            val rng = Random(config.probeSeed)
            result = probeBoundSac(problem, result as PropagationResult.Implied, config, factorWeights, rng)
            if (result is PropagationResult.Unsat) return result
            if (config.probeIntHoles) {
                result = probeIntHoles(problem, result as PropagationResult.Implied, config, factorWeights, rng)
            }
        }
        return result
    }

    /** Interior-value SAC: probe every value strictly between each multi-value int var's current min
     *  and max. Iterates with bound-SAC interleaved so hole-discovered tightenings can lift bounds and
     *  vice versa. */
    private fun probeIntHoles(
        problem: Problem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        factorWeights: DoubleArray,
        rng: Random,
    ): PropagationResult {
        var acc: PropagationResult.Implied = base
        val perVarCalls = IntArray(problem.numIntVars)
        var totalCalls = 0
        var changed = true
        while (changed) {
            changed = false
            for (v in sacProbeOrder(problem, acc, factorWeights, rng)) {
                if (problem.cancellation()) return acc
                if (acc.intValueOrNull(v) != null) continue
                if (perVarCalls[v] >= config.probeBudgetPerVar) continue
                if (totalCalls >= config.probeTotalBudget) return acc
                val orig = problem.intDomains[v]
                val curMin = acc.intMinOrNullCompat(v) ?: orig.min
                val curMax = acc.intMaxOrNullCompat(v) ?: orig.max
                if (curMin >= curMax) continue
                val accAsAssumptions = acc.toAssumptions()
                // Build a per-var hole-set once so the `alreadyHole` lookup in the k-loop is O(1)
                // instead of a linear scan of acc.intHoleVarIds for every probed k.
                val existingHoles = HashSet<Int>()
                for (i in 0 until acc.intHoleVarIds.size) {
                    if (acc.intHoleVarIds[i] == v) existingHoles.add(acc.intHoleValues[i])
                }
                for (k in (curMin + 1) until curMax) {
                    if (perVarCalls[v] >= config.probeBudgetPerVar) break
                    if (totalCalls >= config.probeTotalBudget) return acc
                    if (k !in orig) continue
                    if (k in existingHoles) continue
                    perVarCalls[v]++
                    totalCalls++
                    val pin = problem.propagate(accAsAssumptions.withInt(v, k), problem.cancellation)
                    if (pin is PropagationResult.Unsat) {
                        bumpFactorWeights(pin, factorWeights)
                        perVarCalls[v]++
                        totalCalls++
                        val r = problem.propagate(accAsAssumptions.withIntHole(v, k), problem.cancellation)
                        if (r is PropagationResult.Unsat) return r
                        acc = acc.withHole(v, k).merge(r as PropagationResult.Implied)
                        changed = true
                    }
                }
            }
        }
        return acc
    }

    /** Probe-order heuristic: wdeg / dom — sort descending by `Σ factorWeights(f) / dom(v)` so the
     *  budget is spent first on vars that are heavily-constrained relative to their remaining domain.
     *  Each Unsat probe bumps the weights of the factors in its conflict via [bumpFactorWeights], so
     *  failing probes steer the next pass toward related vars (the classic wdeg adaptation). Ties break
     *  by a per-pass random key (deterministic for a given seed) — this avoids the deterministic-id-order
     *  bias the prior dom-sized order would inherit when every var has the same dom and weight. */
    private fun sacProbeOrder(
        problem: Problem,
        acc: PropagationResult.Implied,
        factorWeights: DoubleArray,
        rng: Random,
    ): IntArray {
        val numIntVars = problem.numIntVars
        val scores = DoubleArray(numIntVars) { v ->
            if (acc.intValueOrNull(v) != null) return@DoubleArray Double.NEGATIVE_INFINITY
            val orig = problem.intDomains[v]
            val lo = acc.intMinOrNullCompat(v) ?: orig.min
            val hi = acc.intMaxOrNullCompat(v) ?: orig.max
            val dom = (hi - lo + 1).coerceAtLeast(1)
            var wdeg = 0.0
            val occ = problem.intOccurrences[v]
            for (i in occ.indices) wdeg += factorWeights[occ[i]]
            wdeg / dom
        }
        val tie = IntArray(numIntVars) { rng.nextInt() }
        val boxed = Array(numIntVars) { it }
        boxed.sortWith(
            Comparator { a, b ->
                val sa = scores[a]
                val sb = scores[b]
                if (sa != sb) sb.compareTo(sa) else tie[a].compareTo(tie[b])
            },
        )
        return IntArray(numIntVars) { boxed[it] }
    }

    /** Bump every factor implicated in an Unsat conflict by 1.0. This is the wdeg update rule: factors
     *  that repeatedly fail under hypothetical pins gain weight and steer the SAC probe order toward the
     *  vars they mention on subsequent passes. */
    private fun bumpFactorWeights(unsat: PropagationResult.Unsat, factorWeights: DoubleArray) {
        for (f in unsat.conflictFactors) {
            if (f in factorWeights.indices) factorWeights[f] += 1.0
        }
    }

    /**
     * Bound-SAC fixed-point loop. Probes the min and max of each multi-value int var under the current
     * [base]; an Unsat result lets us tighten that bound by one and re-probe. Returns the strengthened
     * [PropagationResult.Implied], or `Unsat` if the problem turns out to be infeasible.
     */
    private fun probeBoundSac(
        problem: Problem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        factorWeights: DoubleArray,
        rng: Random,
    ): PropagationResult {
        var acc: PropagationResult.Implied = base
        val perVarCalls = IntArray(problem.numIntVars)
        var totalCalls = 0
        var changed = true
        while (changed) {
            changed = false
            for (v in sacProbeOrder(problem, acc, factorWeights, rng)) {
                if (problem.cancellation()) return acc
                if (acc.intValueOrNull(v) != null) continue
                if (perVarCalls[v] >= config.probeBudgetPerVar) continue
                if (totalCalls >= config.probeTotalBudget) return acc
                val orig = problem.intDomains[v]
                val curMin = acc.intMinOrNullCompat(v) ?: orig.min
                val curMax = acc.intMaxOrNullCompat(v) ?: orig.max
                if (curMin >= curMax) continue
                val accAsAssumptions = acc.toAssumptions()
                perVarCalls[v]++
                totalCalls++
                val pinMin = problem.propagate(accAsAssumptions.withInt(v, curMin), problem.cancellation)
                if (pinMin is PropagationResult.Unsat) {
                    bumpFactorWeights(pinMin, factorWeights)
                    perVarCalls[v]++
                    totalCalls++
                    val tightened = accAsAssumptions.withTightenedMin(v, curMin + 1)
                    val r = problem.propagate(tightened, problem.cancellation)
                    if (r is PropagationResult.Unsat) return r
                    acc = acc.withMin(v, curMin + 1).merge(r as PropagationResult.Implied)
                    changed = true
                    continue
                }
                if (perVarCalls[v] >= config.probeBudgetPerVar) continue
                if (totalCalls >= config.probeTotalBudget) return acc
                perVarCalls[v]++
                totalCalls++
                val pinMax = problem.propagate(accAsAssumptions.withInt(v, curMax), problem.cancellation)
                if (pinMax is PropagationResult.Unsat) {
                    bumpFactorWeights(pinMax, factorWeights)
                    perVarCalls[v]++
                    totalCalls++
                    val tightened = accAsAssumptions.withTightenedMax(v, curMax - 1)
                    val r = problem.propagate(tightened, problem.cancellation)
                    if (r is PropagationResult.Unsat) return r
                    acc = acc.withMax(v, curMax - 1).merge(r as PropagationResult.Implied)
                    changed = true
                }
            }
        }
        return acc
    }

    /**
     * Iteratively strengthens [initial] by probing each free bool with both polarities. If one polarity
     * is Unsat under the current accumulated base, the opposite polarity is forced. Repeats until a full
     * pass finds no new forcings.
     */
    private fun probeFreeBools(problem: Problem, initial: PropagationResult.Implied): PropagationResult {
        val bools = HashMap(initial.bools)
        val ints = HashMap(initial.ints)
        var changed = true
        while (changed) {
            changed = false
            for (v in 0 until problem.numBoolVars) {
                if (problem.cancellation()) return PropagationResult.Implied(bools, ints)
                if (v in bools) continue
                val tryTrue = problem.propagate(Assumptions(bools + (v to true), ints), problem.cancellation)
                if (tryTrue is PropagationResult.Unsat) {
                    val r = problem.propagate(Assumptions(bools + (v to false), ints), problem.cancellation)
                    if (r is PropagationResult.Unsat) return r
                    foldInto(bools, ints, v, false, r as PropagationResult.Implied)
                    changed = true
                    continue
                }
                val tryFalse = problem.propagate(Assumptions(bools + (v to false), ints), problem.cancellation)
                if (tryFalse is PropagationResult.Unsat) {
                    val r = problem.propagate(Assumptions(bools + (v to true), ints), problem.cancellation)
                    if (r is PropagationResult.Unsat) return r
                    foldInto(bools, ints, v, true, r as PropagationResult.Implied)
                    changed = true
                }
            }
        }
        return PropagationResult.Implied(bools, ints)
    }

    private fun foldInto(
        bools: HashMap<Int, Boolean>,
        ints: HashMap<Int, Int>,
        forcedVar: Int,
        forcedValue: Boolean,
        implied: PropagationResult.Implied,
    ) {
        bools[forcedVar] = forcedValue
        implied.forEachBool { k, b -> bools[k] = b }
        implied.forEachInt { k, i -> ints[k] = i }
    }
}
