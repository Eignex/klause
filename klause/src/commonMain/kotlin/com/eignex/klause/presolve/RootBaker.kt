package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.LongHashSet
import kotlin.random.Random

/**
 * Bake-time root probing, owned by the presolve lane rather than the immutable model.
 *
 * The propagation projection performs the cheap unconditional base bake. The heavier failed-literal /
 * singleton arc consistency (SAC) probing tiers, enabled only by the compile / presolve layers, live here
 * so the engine never depends on `presolve`.
 *
 * [bake] runs the probing fixpoint against an already-base-baked [BakedProblem] and returns the accumulated
 * deductions; [reseed] builds a [BakedProblem] carrying them.
 *
 * SAC: Debruyne and Bessière, "Some Practicable Filtering Techniques for the Constraint Satisfaction
 * Problem", IJCAI 1997, https://mlanthology.org/ijcai/1997/debruyne1997ijcai-some
 */
object RootBaker {

    /**
     * Re-seed a base-baked [problem] with the [config]-enabled probing tiers: run [bake] and, when it
     * found extra deductions, return a folded [BakedProblem] over the same factors and domains.
     * Returns [problem] unchanged when no tier is enabled, the problem is an already-folded pass view
     * (which never bakes), or the base bake is already `Unsat`. The kernel never initiates it, so the
     * engine carries no dependency on presolve.
     */
    fun reseed(problem: BakedProblem, config: BakeConfig): BakedProblem {
        if (!config.anyEnabled || problem.alreadyFolded) return problem
        val extra = bake(problem, config)
        if (extra === problem.baked) return problem
        return BakedProblem(
            settings = problem.settings,
            semantics = problem.semantics,
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = problem.rootIntDomains(),
            factors = problem.factors,
            seedDeductions = extra,
            cancellation = problem.cancellation,
            impliedFactorMask = problem.impliedFactorMask,
            hasSymmetryBreaking = problem.hasSymmetryBreaking,
            numRealVars = problem.numRealVars,
            realLower = problem.realLower,
            realUpper = problem.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = problem.intBounds.openLowerBits,
            packedOpenIntHi = problem.intBounds.openUpperBits,
            projectionProviders = problem.projectionProviders,
            projectionSelection = problem.factorProjections?.let { captured ->
                { view -> captured.forEngineView(view) }
            },
        )
    }

    /**
     * Run the [config]-enabled probing tiers against the base-baked [problem] and return the resulting
     * deductions — the base bake merged with every probing finding, ready for a rebuilt [BakedProblem].
     * Returns [problem]'s existing bake unchanged when no tier is enabled or the base bake is already
     * `Unsat`. Cancellation is polled between phases and probes; a fired deadline yields a sound partial
     * bake (probing only ever tightens).
     */
    fun bake(problem: BakedProblem, config: BakeConfig): PropagationResult {
        val base = problem.baked
        if (base !is PropagationResult.Implied) return base
        if (!config.anyEnabled) return base
        var result: PropagationResult = base
        if (config.probeFailedLiterals) {
            result = observeTier(problem, PresolvePass.PROBE_FAILED_LITERALS, result) { initial, token ->
                probeFreeBools(problem, initial, token)
            }
            if (result is PropagationResult.Unsat) return result
        }
        if (config.probeIntBounds || config.probeIntHoles) {
            // Both tiers share weights so a failed probe steers subsequent integer candidates.
            val factorWeights by lazy { DoubleArray(problem.numFactors) { 1.0 } }
            val rng by lazy { Random(config.probeSeed) }
            result = observeTier(problem, PresolvePass.PROBE_INT_BOUNDS, result) { initial, token ->
                probeBoundSac(problem, initial, config, factorWeights, rng, token)
            }
            if (result is PropagationResult.Unsat) return result
            if (config.probeIntHoles) {
                result = observeTier(problem, PresolvePass.PROBE_INT_HOLES, result) { initial, token ->
                    probeIntHoles(problem, initial, config, factorWeights, rng, token)
                }
            }
        }
        return result
    }

    private fun observeTier(
        problem: BakedProblem,
        pass: PresolvePass,
        base: PropagationResult,
        action: (PropagationResult.Implied, Cancellation) -> PropagationResult,
    ): PropagationResult {
        val budget = problem.cancellation.presolveBudget()
        val token = budget?.observeStops(problem.cancellation) ?: problem.cancellation
        return budget.observe(pass, PresolveLane.FINITE, { problem.presolveSize() }) { invocation ->
            when {
                !pass.namespaceEligible(problem) -> {
                    invocation?.outcome = PresolveInvocationOutcome.ELIGIBILITY_SKIP
                    base
                }
                token() -> base
                else -> action(base as PropagationResult.Implied, token).also { result ->
                    if (result is PropagationResult.Implied) {
                        invocation?.fixedColumns = result.numBools + result.numInts - base.numBools - base.numInts
                    }
                    if (result is PropagationResult.Unsat) {
                        invocation?.outcome = PresolveInvocationOutcome.REFUTED
                    } else if (result !== base && invocation?.outcome != PresolveInvocationOutcome.INTERRUPTED) {
                        invocation?.outcome = PresolveInvocationOutcome.CHANGED
                    }
                }
            }
        }
    }

    /** Interior-value SAC: probe every value strictly between each multi-value int var's current min
     *  and max. Iterates with bound-SAC interleaved so hole-discovered tightenings can lift bounds and
     *  vice versa. */
    private fun probeIntHoles(
        problem: BakedProblem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        factorWeights: DoubleArray,
        rng: Random,
        cancellation: Cancellation,
    ): PropagationResult {
        var acc: PropagationResult.Implied = base
        val perVarCalls = IntArray(problem.numIntVars)
        var totalCalls = 0
        var changed = true
        while (changed) {
            if (cancellation()) return acc
            changed = false
            for (v in sacProbeOrder(problem, acc, factorWeights, rng)) {
                if (cancellation()) return acc
                if (acc.intValueOrNull(v) != null) continue
                if (perVarCalls[v] >= config.probeBudgetPerVar) {
                    cancellation.interruptPresolveInvocation()
                    continue
                }
                if (totalCalls >= config.probeTotalBudget) {
                    cancellation.interruptPresolveInvocation()
                    return acc
                }
                val orig = problem.rootIntDomain(v)
                val curMin = acc.intMinOrNullCompat(v) ?: orig.min
                val curMax = acc.intMaxOrNullCompat(v) ?: orig.max
                if (curMin >= curMax) continue
                val accAsAssumptions = acc.toAssumptions()
                val existingHoles = LongHashSet()
                for (i in 0 until acc.intHoleVarIds.size) {
                    if (acc.intHoleVarIds[i] == v) existingHoles.add(acc.intHoleValues[i])
                }
                for (k in (curMin + 1) until curMax) {
                    if (cancellation()) return acc
                    if (perVarCalls[v] >= config.probeBudgetPerVar) {
                        cancellation.interruptPresolveInvocation()
                        break
                    }
                    if (totalCalls >= config.probeTotalBudget) {
                        cancellation.interruptPresolveInvocation()
                        return acc
                    }
                    if (k !in orig) continue
                    if (k in existingHoles) continue
                    perVarCalls[v]++
                    totalCalls++
                    cancellation.recordPresolveProbe()
                    val pin = problem.propagate(accAsAssumptions.withInt(v, k), cancellation)
                    if (pin is PropagationResult.Unsat) {
                        bumpFactorWeights(pin, factorWeights)
                        acc = acc.withHole(v, k)
                        if (cancellation()) return acc
                        perVarCalls[v]++
                        totalCalls++
                        cancellation.recordPresolveProbe()
                        val r = problem.propagate(accAsAssumptions.withIntHole(v, k), cancellation)
                        if (r is PropagationResult.Unsat) return r
                        acc = acc.withHole(v, k).merge(r as PropagationResult.Implied)
                        changed = true
                    }
                }
            }
        }
        return acc
    }

    /** Probe order: descending `Σ factorWeights(f) / dom(v)`, so the budget is spent first on vars that
     *  are heavily constrained relative to their remaining domain. Each Unsat probe bumps the weights of
     *  the factors in its conflict via [bumpFactorWeights], steering the next pass toward related vars
     *  (Boussemart, Hemery, Lecoutre and Sais, "Boosting Systematic Search by Weighting Constraints",
     *  ECAI 2004). Ties break by a seeded per-pass random key, avoiding variable-id order bias. */
    private fun sacProbeOrder(
        problem: BakedProblem,
        acc: PropagationResult.Implied,
        factorWeights: DoubleArray,
        rng: Random,
    ): IntArray {
        val numIntVars = problem.numIntVars
        val scores = DoubleArray(numIntVars) { v ->
            if (acc.intValueOrNull(v) != null) return@DoubleArray Double.NEGATIVE_INFINITY
            val orig = problem.rootIntDomain(v)
            val lo = acc.intMinOrNullCompat(v) ?: orig.min
            val hi = acc.intMaxOrNullCompat(v) ?: orig.max
            val dom = (hi - lo + 1).coerceAtLeast(1)
            var wdeg = 0.0
            val occ = PropagationProblem(problem).intOccurrences[v]
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

    /** Bump every factor implicated in an Unsat conflict by 1.0, the wdeg update rule. */
    private fun bumpFactorWeights(unsat: PropagationResult.Unsat, factorWeights: DoubleArray) {
        for (f in unsat.conflictFactors) {
            if (f in factorWeights.indices) factorWeights[f] += 1.0
        }
    }

    /**
     * Bound-SAC fixed-point loop. Probes the min and max of each multi-value int var under the current
     * [base]; an Unsat result tightens that bound by one and re-probes. Returns the strengthened
     * [PropagationResult.Implied], or `Unsat` if the problem turns out to be infeasible.
     */
    private fun probeBoundSac(
        problem: BakedProblem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        factorWeights: DoubleArray,
        rng: Random,
        cancellation: Cancellation,
    ): PropagationResult {
        var acc: PropagationResult.Implied = base
        val perVarCalls = IntArray(problem.numIntVars)
        var totalCalls = 0
        var changed = true
        while (changed) {
            if (cancellation()) return acc
            changed = false
            for (v in sacProbeOrder(problem, acc, factorWeights, rng)) {
                if (cancellation()) return acc
                if (acc.intValueOrNull(v) != null) continue
                if (perVarCalls[v] >= config.probeBudgetPerVar) {
                    cancellation.interruptPresolveInvocation()
                    continue
                }
                if (totalCalls >= config.probeTotalBudget) {
                    cancellation.interruptPresolveInvocation()
                    return acc
                }
                val orig = problem.rootIntDomain(v)
                val curMin = acc.intMinOrNullCompat(v) ?: orig.min
                val curMax = acc.intMaxOrNullCompat(v) ?: orig.max
                if (curMin >= curMax) continue
                val accAsAssumptions = acc.toAssumptions()
                perVarCalls[v]++
                totalCalls++
                cancellation.recordPresolveProbe()
                val pinMin = problem.propagate(accAsAssumptions.withInt(v, curMin), cancellation)
                if (pinMin is PropagationResult.Unsat) {
                    bumpFactorWeights(pinMin, factorWeights)
                    acc = acc.withMin(v, curMin + 1)
                    if (cancellation()) return acc
                    perVarCalls[v]++
                    totalCalls++
                    val tightened = accAsAssumptions.withTightenedMin(v, curMin + 1)
                    cancellation.recordPresolveProbe()
                    val r = problem.propagate(tightened, cancellation)
                    if (r is PropagationResult.Unsat) return r
                    acc = acc.withMin(v, curMin + 1).merge(r as PropagationResult.Implied)
                    changed = true
                    continue
                }
                if (cancellation()) return acc
                if (perVarCalls[v] >= config.probeBudgetPerVar) {
                    cancellation.interruptPresolveInvocation()
                    continue
                }
                if (totalCalls >= config.probeTotalBudget) {
                    cancellation.interruptPresolveInvocation()
                    return acc
                }
                perVarCalls[v]++
                totalCalls++
                cancellation.recordPresolveProbe()
                val pinMax = problem.propagate(accAsAssumptions.withInt(v, curMax), cancellation)
                if (pinMax is PropagationResult.Unsat) {
                    bumpFactorWeights(pinMax, factorWeights)
                    acc = acc.withMax(v, curMax - 1)
                    if (cancellation()) return acc
                    perVarCalls[v]++
                    totalCalls++
                    val tightened = accAsAssumptions.withTightenedMax(v, curMax - 1)
                    cancellation.recordPresolveProbe()
                    val r = problem.propagate(tightened, cancellation)
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
    private fun probeFreeBools(
        problem: BakedProblem,
        initial: PropagationResult.Implied,
        cancellation: Cancellation,
    ): PropagationResult {
        var acc = initial
        var changed = true
        while (changed) {
            if (cancellation()) return acc
            changed = false
            for (v in 0 until problem.numBoolVars) {
                if (cancellation()) return acc
                if (acc.boolValueOrNull(v) != null) continue
                val assumptions = acc.toAssumptions()
                cancellation.recordPresolveProbe()
                val tryTrue = problem.propagate(assumptions.withBool(v, true), cancellation)
                if (tryTrue is PropagationResult.Unsat) {
                    acc = acc.merge(PropagationResult.Implied(mapOf(v to false), emptyMap()))
                    if (cancellation()) return acc
                    cancellation.recordPresolveProbe()
                    val repair = problem.propagate(acc.toAssumptions(), cancellation)
                    if (repair is PropagationResult.Unsat) return repair
                    acc = acc.merge(repair as PropagationResult.Implied)
                    changed = true
                    continue
                }
                if (cancellation()) return acc
                cancellation.recordPresolveProbe()
                val tryFalse = problem.propagate(assumptions.withBool(v, false), cancellation)
                if (tryFalse is PropagationResult.Unsat) {
                    acc = acc.merge(PropagationResult.Implied(mapOf(v to true), emptyMap()))
                    if (cancellation()) return acc
                    cancellation.recordPresolveProbe()
                    val repair = problem.propagate(acc.toAssumptions(), cancellation)
                    if (repair is PropagationResult.Unsat) return repair
                    acc = acc.merge(repair as PropagationResult.Implied)
                    changed = true
                }
            }
        }
        return acc
    }

    /**
     * [problem] with an explicit contradiction appended so its bake propagation reports `Unsat` — the
     * materialization a caller uses when an oracle (e.g. the LP harvest) has certified infeasibility but a
     * concrete infeasible model is needed. Two equalities pinning one variable to consecutive values
     * (or a Boolean forced both ways) are jointly unsatisfiable regardless of domains, so the conflict is
     * witnessed without depending on the certifying proof being re-derivable downstream.
     *
     * Every part of the model that is not the appended contradiction is carried through, the open-side
     * marks included: a column the front-end closed with an invented endpoint must keep saying so, or the
     * refutation reads as one derived inside a box the model never stated — which is the difference
     * between `unsat` and `unknown` for whoever reports it. The mask is extended over the added rows,
     * which are not implied ones.
     */
    fun provenInfeasible(problem: BakedProblem, config: BakeConfig): BakedProblem {
        val factors = ArrayList<Factor>(problem.factors.size + 2)
        factors.addAll(problem.factors)
        when {
            problem.numIntVars > 0 -> {
                val min = problem.rootIntDomain(0).min
                val c = if (min < Long.MAX_VALUE) min else min - 1
                factors.add(Linear(longArrayOf(1), intArrayOf(0), LinearOp.EQ, c))
                factors.add(Linear(longArrayOf(1), intArrayOf(0), LinearOp.EQ, c + 1))
            }

            problem.numBoolVars > 0 -> {
                factors.add(Clause(intArrayOf(Lit.make(0, true)))) // b0 = true
                factors.add(Clause(intArrayOf(Lit.make(0, false)))) // b0 = false
            }

            else -> return problem // no variable to pin a contradiction on
        }
        return reseed(
            BakedProblem(
                settings = problem.settings,
                semantics = problem.semantics,
                numBoolVars = problem.numBoolVars,
                numIntVars = problem.numIntVars,
                intDomains = problem.rootIntDomains(),
                factors = factors,
                cancellation = problem.cancellation,
                // The certified refutation survives cancellation of mandatory construction.
                seedDeductions = PropagationResult.Unsat(),
                impliedFactorMask = problem.impliedFactorMask?.let { mask ->
                    BooleanArray(factors.size) { it < mask.size && mask[it] }
                },
                hasSymmetryBreaking = problem.hasSymmetryBreaking,
                numRealVars = problem.numRealVars,
                realLower = problem.realLower,
                realUpper = problem.realUpper,
                packedOpenIntLo = problem.intBounds.openLowerBits,
                packedOpenIntHi = problem.intBounds.openUpperBits,
                projectionProviders = problem.projectionProviders,
                projectionSelection = problem.factorProjections?.let { captured ->
                    { view -> captured.forEngineView(view) }
                },
            ),
            config,
        )
    }
}
