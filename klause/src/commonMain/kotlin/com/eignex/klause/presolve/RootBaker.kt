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
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.argsortBy
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongHashSet
import kotlin.random.Random

/**
 * Bake-time root probing, owned by the presolve lane rather than the immutable model.
 *
 * The propagation projection performs the cheap base bake; the failed-literal and singleton arc
 * consistency (SAC) probing tiers live here so the engine never depends on `presolve`.
 *
 * SAC: Debruyne and Bessière, "Some Practicable Filtering Techniques for the Constraint Satisfaction
 * Problem", IJCAI 1997, https://mlanthology.org/ijcai/1997/debruyne1997ijcai-some
 */
object RootBaker {

    /**
     * [problem] re-baked with the deductions of the [config]-enabled probing tiers. Returns [problem]
     * unchanged when no tier is enabled, it is an already-folded pass view, or its base bake is `Unsat`.
     */
    fun reseed(problem: BakedProblem, config: BakeConfig): BakedProblem {
        if (!config.anyEnabled || problem.alreadyFolded) return problem
        val extra = bake(problem, config)
        return fold(problem, extra)
    }

    internal fun reseedBounded(problem: BakedProblem, config: BakeConfig): BakedProblem {
        val base = problem.baked
        if (base !is PropagationResult.Implied) return problem
        val extra = observeTier(problem, PresolvePass.PROBE_INT_HOLES, base) { initial, token ->
            BoundedIntegerProbing.bake(problem, initial, config, token)
        }
        return fold(problem, extra)
    }

    private fun fold(problem: BakedProblem, extra: PropagationResult): BakedProblem {
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
     * The base bake of [problem] merged with every finding of the [config]-enabled probing tiers. A fired
     * deadline yields a sound partial bake, since probing only ever tightens.
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
        action: (PropagationResult.Implied, ExecutionControl) -> PropagationResult,
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
                    if (result is PropagationResult.Implied && result !== base) {
                        invocation?.recordTierYield(problem, base, result)
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

    private fun PresolveInvocation.recordTierYield(
        problem: BakedProblem,
        base: PropagationResult.Implied,
        result: PropagationResult.Implied,
    ) {
        fixedColumns = result.numBools + result.numInts - base.numBools - base.numInts
        val narrowed = IntHashSet()
        for (i in result.intMinKeys.indices) {
            val v = result.intMinKeys[i]
            val previous = base.intMinOrNullCompat(v) ?: problem.rootIntDomain(v).min
            if (result.intMinValues[i] > previous && result.intValueOrNull(v) == null) narrowed.add(v)
        }
        for (i in result.intMaxKeys.indices) {
            val v = result.intMaxKeys[i]
            val previous = base.intMaxOrNullCompat(v) ?: problem.rootIntDomain(v).max
            if (result.intMaxValues[i] < previous && result.intValueOrNull(v) == null) narrowed.add(v)
        }
        val previousHoles = HashMap<Int, LongHashSet>()
        base.forEachIntHole { v, value -> previousHoles.getOrPut(v) { LongHashSet() }.add(value) }
        result.forEachIntHole { v, value ->
            if (result.intValueOrNull(v) == null && previousHoles[v]?.contains(value) != true) narrowed.add(v)
        }
        for (i in result.intSetKeys.indices) {
            val v = result.intSetKeys[i]
            val previous = base.intSetKeys.binarySearchInt(v)
            val size = result.intSetOffsets[i + 1] - result.intSetOffsets[i]
            if (previous < 0 || size < base.intSetOffsets[previous + 1] - base.intSetOffsets[previous]) narrowed.add(v)
        }
        narrowedColumns = result.numInts - base.numInts + narrowed.size
    }

    private fun probeIntHoles(
        problem: BakedProblem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        factorWeights: DoubleArray,
        rng: Random,
        cancellation: ExecutionControl,
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
                var candidate = orig.higher(curMin)
                while (candidate < curMax) {
                    if (cancellation()) return acc
                    if (perVarCalls[v] >= config.probeBudgetPerVar) {
                        cancellation.interruptPresolveInvocation()
                        break
                    }
                    if (totalCalls >= config.probeTotalBudget) {
                        cancellation.interruptPresolveInvocation()
                        return acc
                    }
                    val k = candidate
                    candidate = orig.higher(k)
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

    // Descending `Σ factorWeights(f) / dom(v)`, spending the budget first on heavily constrained variables;
    // conflict weights steer later passes toward related variables (Boussemart, Hemery, Lecoutre and Sais,
    // "Boosting Systematic Search by Weighting Constraints", ECAI 2004). Seeded random ties avoid id bias.
    internal fun sacProbeOrder(
        problem: BakedProblem,
        acc: PropagationResult.Implied,
        factorWeights: DoubleArray,
        rng: Random,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): IntArray {
        val numIntVars = problem.numIntVars
        val intOccurrences by lazy { PropagationProblem(problem).intOccurrences }
        val scores = DoubleArray(numIntVars) { v ->
            cancellation.charge(1)
            if (cancellation()) return@DoubleArray Double.NEGATIVE_INFINITY
            if (acc.intValueOrNull(v) != null) return@DoubleArray Double.NEGATIVE_INFINITY
            val orig = problem.rootIntDomain(v)
            val lo = acc.intMinOrNullCompat(v) ?: orig.min
            val hi = acc.intMaxOrNullCompat(v) ?: orig.max
            val dom = (hi - lo + 1).coerceAtLeast(1)
            var wdeg = 0.0
            val occ = intOccurrences[v]
            for (i in occ.indices) {
                cancellation.charge(1)
                if (i and 63 == 0 && cancellation()) break
                wdeg += factorWeights[occ[i]]
            }
            wdeg / dom
        }
        if (cancellation()) return IntArray(0)
        val tie = IntArray(numIntVars) { rng.nextInt() }
        val order = argsortBy(numIntVars) { a, b ->
            val sa = scores[a]
            val sb = scores[b]
            if (sa != sb) sb.compareTo(sa) else tie[a].compareTo(tie[b])
        }
        cancellation.charge(numIntVars.toLong())
        if (cancellation()) return IntArray(0)
        return order
    }

    private fun bumpFactorWeights(unsat: PropagationResult.Unsat, factorWeights: DoubleArray) {
        for (f in unsat.conflictFactors) {
            if (f in factorWeights.indices) factorWeights[f] += 1.0
        }
    }

    private fun probeBoundSac(
        problem: BakedProblem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        factorWeights: DoubleArray,
        rng: Random,
        cancellation: ExecutionControl,
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

    private fun probeFreeBools(
        problem: BakedProblem,
        initial: PropagationResult.Implied,
        cancellation: ExecutionControl,
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
     * [problem] with an explicit contradiction appended, for a caller whose oracle certified infeasibility
     * but needs a concrete infeasible model. Two equalities pinning one variable to consecutive values (or a
     * Boolean forced both ways) conflict regardless of domains.
     *
     * The open-side marks are carried through, so the refutation is not read as one derived inside a box
     * the model never stated, which is the difference between `unsat` and `unknown`.
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
                factors.add(Clause(intArrayOf(Lit.make(0, true))))
                factors.add(Clause(intArrayOf(Lit.make(0, false))))
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
