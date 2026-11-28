package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.difference.withDifferenceSystem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.isClausal
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpHarvestReport
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.util.ExecutionControl
import kotlin.time.TimeSource

// A spin guard: the loop normally exits as soon as a harvest tightens nothing.
private const val MAX_PRESOLVE_HARVEST_ROUNDS = 4

/**
 * The outcome of [PresolvePipeline.run]: the transformed [problem], a [reconstruct] that lifts a solution
 * of it back to the original variable space, the [stats], and [changed] — false when presolve altered
 * nothing (the caller keeps its original, untransformed handle).
 */
class PresolveOutcome(
    val problem: Problem,
    val reconstruct: (Sample) -> Sample,
    val stats: PresolveStats,
    val changed: Boolean,
    /**
     * The objective after exact affine substitution and Boolean namespace extension, or `null` when
     * the caller can retain its objective. Constants remain in source objective units.
     */
    val objective: LinearObjective? = null,
) {
    internal var mapping: SourceMapping =
        SourceMapping(problem, problem, TransformationGuarantees.IDENTITY, lift = reconstruct)
}

/**
 * The presolve driver, independent of any front-end representation. Presolve and the LP-relaxation harvest
 * iterate to a fixpoint, since a harvest's proven tightenings can unlock further reductions; a round runs
 * only when the previous harvest tightened the problem, up to [MAX_PRESOLVE_HARVEST_ROUNDS].
 */
object PresolvePipeline {

    // Source-safe presolve, the single phase every route is planned from, so it runs once per solve. Limited
    // to [PresolvePass.Capability.SOURCE]: passes that read finite domains or root deductions stay behind
    // the bake. [finiteLane] marks a phase that [run] continues, so it applies the finite lane's policy.
    internal fun prepareSource(
        problem: Problem,
        config: PresolveConfig = PresolveConfig.DEFAULT,
        linearObjective: LinearObjective? = null,
        solutionSetSensitive: Boolean = false,
        cancellation: ExecutionControl = ExecutionControl.Never,
        presolveBudget: PresolveBudget? = null,
        finiteLane: Boolean = false,
        transformObjective: Boolean = true,
    ): PreparedSource {
        // An already-baked model reached this boundary through a finite preparation that ran the phase,
        // so running it again would re-derive the same fixpoint at full cost.
        val rewriteObjective = finiteLane && transformObjective && !solutionSetSensitive &&
            !problem.semantics.relaxesRows(problem.numRealVars)
        if (problem is BakedProblem) return PreparedSource.unchanged(problem, presolveBudget, rewriteObjective)
        val mark = TimeSource.Monotonic.markNow()
        val context = PresolveContext.of(
            linearObjective,
            solutionSetSensitive,
            problem.hasSymmetryBreaking,
            transformObjective = rewriteObjective,
        )
            .withPresolveBudget(presolveBudget)
            .withKeepBinaryComparisons(config.keepsBinaryComparisons(finiteLane))
        val presolved = Presolver.runSource(problem, config, context, phaseCancellation(cancellation, presolveBudget))
        return PreparedSource(
            source = problem,
            problem = presolved.problem,
            infeasible = presolved.infeasible,
            objective = refit(presolved.objective ?: linearObjective, presolved.problem),
            passesFired = presolved.passesFired,
            rebuild = presolved.rebuild,
            budget = presolveBudget,
            config = config,
            elapsed = mark.elapsedNow(),
            transformObjective = rewriteObjective,
        )
    }

    /**
     * Run presolve over [problem] with [config], resolving effort against [linearObjective] and
     * [solutionSetSensitive]. [cancellation] caps every pass and LP solve.
     */
    fun run(
        problem: Problem,
        linearObjective: LinearObjective?,
        config: PresolveConfig,
        solutionSetSensitive: Boolean,
        cancellation: ExecutionControl = ExecutionControl.Never,
        presolveBudget: PresolveBudget? = null,
        zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
        randomSeed: Long? = null,
        sourcePreparation: LpSourcePreparation = LpSourcePreparation.ADMITTED,
    ): PresolveOutcome = run(
        prepareSource(
            problem, config, linearObjective, solutionSetSensitive, cancellation, presolveBudget, finiteLane = true,
        ),
        linearObjective,
        config,
        solutionSetSensitive,
        cancellation,
        zeroObjectivePricing,
        randomSeed,
        sourcePreparation = sourcePreparation,
    )

    // Takes the prepared result so the source phase never runs twice, and spends the rest of the allowance
    // it ran under.
    internal fun run(
        prepared: PreparedSource,
        linearObjective: LinearObjective?,
        config: PresolveConfig,
        solutionSetSensitive: Boolean,
        callerCancellation: ExecutionControl = ExecutionControl.Never,
        zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
        randomSeed: Long? = null,
        projections: PreparedFactorProjections? = null,
        sourcePreparation: LpSourcePreparation = LpSourcePreparation.ADMITTED,
        numericalLp: Boolean = false,
    ): PresolveOutcome {
        val problem = prepared.source
        val sourceProblem = prepared.problem
        val presolveBudget = prepared.budget
        val cancellation = phaseCancellation(callerCancellation, presolveBudget)
        // Resolved once and threaded through the context so every rebuild re-derives the probing.
        val bakeConfig = BakeConfig.from(config)
        var currentObjective = prepared.objective ?: linearObjective
        var context = PresolveContext.of(
            currentObjective,
            solutionSetSensitive,
            sourceProblem.hasSymmetryBreaking,
            transformObjective = prepared.transformObjective,
        )
            .withBakeConfig(bakeConfig)
            .withPresolveBudget(presolveBudget)
            .withKeepBinaryComparisons(config.keepsBinaryComparisons(finiteLane = true))
        val harvestPlan = if (config.resolved(PresolvePass.LP_HARVEST, context)) {
            LpPlan(bounding = true, variableShaving = true, objectiveShaving = true)
        } else {
            null
        }
        val automaticClausalSatisfaction = harvestPlan != null &&
            config.overrides[PresolvePass.LP_HARVEST] != true && linearObjective == null && problem.isClausal()
        val objective = currentObjective ?: LinearObjective()

        // On coupled wide integer domains the bake would narrow toward a refutation one step per round, O(span).
        // [lpRootInfeasible] certifies infeasibility from the declared domains in one LP solve via an exact
        // Farkas ray; the relaxation contains every integer solution, so the verdict is the bake's. An
        // isolated wide output with bounded integer inputs uses the ordinary bake.
        val sourceProjections = projections?.forEngineView(sourceProblem)
        val (wideCoupling, rootInfeasible) = presolveBudget.observePreparation(
            PresolvePreparationStage.PRE_BAKE_LP_FEASIBILITY, PresolveLane.SOURCE, sourceProblem,
            preBakeSlice(cancellation, presolveBudget),
        ) { token, observation ->
            val wide = needsWideDomainLp(sourceProblem)
            val result = if (!prepared.infeasible && wide) {
                lpRootInfeasibleReporting(
                    sourceProblem, objective, LpPlan(bounding = true, sourcePreparation = sourcePreparation), token,
                    zeroObjectivePricing, randomSeed, projections = sourceProjections,
                    numericalLp = numericalLp,
                ).also { if (it.infeasible) observation.preparationRefuted() }
            } else {
                observation?.outcome = PresolveInvocationOutcome.ELIGIBILITY_SKIP
                LpRootInfeasibleResult(infeasible = false, stats = LpStats())
            }
            wide to result
        }
        // A refutation before the bake is the verdict; the bake and rounds would only re-derive it at O(span).
        if (prepared.infeasible || rootInfeasible.infeasible) {
            return PresolveOutcome(
                sourceProblem,
                { it },
                prepared.stats.copy(
                    infeasible = true,
                    lpStats = prepared.stats.lpStats.mergedWith(rootInfeasible.stats),
                ),
                changed = true,
                objective = refit(currentObjective, sourceProblem),
            ).also { it.mapping = prepared.mapping }
        }

        // On coupled wide domains, OBBT ([lpRootBounds]) collapses each domain in one solve per bound so the
        // bake does not narrow it one step per round. Solution-set preserving, so sound before the bake.
        val rootBounds = if (wideCoupling) {
            presolveBudget.observePreparation(
                PresolvePreparationStage.PRE_BAKE_LP_BOUNDS, PresolveLane.SOURCE, sourceProblem,
                preBakeSlice(cancellation, presolveBudget), output = { it.problem },
            ) { token, _ ->
                lpRootBoundsReporting(
                    sourceProblem, objective, LpPlan(bounding = true, sourcePreparation = sourcePreparation), token,
                    zeroObjectivePricing, randomSeed,
                    minimumSpan = sourceProblem.settings.largeSpanThreshold, projections = sourceProjections,
                    numericalLp = numericalLp,
                )
            }
        } else {
            LpRootBoundsResult(sourceProblem, LpStats())
        }
        val prebaked = rootBounds.problem

        // Step 0: the deferred base bake, after the pre-bake LP steps that must precede it. A no-op for an
        // already-baked problem.
        val bakeStart = TimeSource.Monotonic.markNow()
        val (baked, seeded) = presolveBudget.observePreparation(
            PresolvePreparationStage.ROOT_BAKE, PresolveLane.FINITE, prebaked, cancellation,
            output = { it.second },
        ) { token, observation ->
            val selected = projections?.forEngineView(prebaked)
            val root = if (selected == null) prebaked.bake(token) else prebaked.bake(selected, token)
            val explicit = RootBaker.reseed(root, bakeConfig)
            val boundedProbing = config.emphasis == PresolveEmphasis.DEFAULT && currentObjective == null &&
                config.overrides[PresolvePass.PROBE_INT_HOLES] == null &&
                config.overrides[PresolvePass.PROBE_INT_BOUNDS] == null
            val probed = if (boundedProbing) RootBaker.reseedBounded(explicit, bakeConfig) else explicit
            if (probed.baked is PropagationResult.Unsat) observation.preparationRefuted()
            root to probed
        }
        val bakeElapsed = bakeStart.elapsedNow()
        val mappings = ArrayList<SourceMapping>()
        mappings.add(prepared.mapping)
        mappings.add(SourceMapping(sourceProblem, seeded, TransformationGuarantees.IDENTITY))
        val firedPasses = LinkedHashSet<String>() // pass ids that fired, across all rounds, in first-fire order
        prepared.passesFired.forEach { firedPasses.add(it.id) }
        val packing = if (seeded.baked is PropagationResult.Unsat ||
            !config.resolved(PresolvePass.SUBSTITUTE_TUPLE_PACKING, context)
        ) {
            null
        } else {
            presolveBudget.observeTransform(
                PresolvePass.SUBSTITUTE_TUPLE_PACKING, seeded, cancellation,
                eligible = { seeded.numIntVars > 0 },
                unchanged = { null },
                output = { it?.problem ?: seeded },
                refuted = { it?.problem?.baked is PropagationResult.Unsat },
            ) { TuplePackingSubstitution.substitute(seeded, context.objectiveIntVars, cancellation) }
        }
        val packed = packing?.problem ?: seeded
        packing?.let {
            mappings.add(SourceMapping(seeded, it.problem, TransformationGuarantees.IDENTITY, it.rebuild))
            firedPasses.add(PresolvePass.SUBSTITUTE_TUPLE_PACKING.id)
        }
        currentObjective = refit(currentObjective, packed)
        context = context.withObjective(currentObjective)
        // Runs on the bake's committed domains, which expose the `{0, 1}` columns, and ahead of the round
        // engine so every round pass reads the model in the lane it will be solved in. It must also precede
        // symmetry breaking, whose handling factor reads columns value-wise and would pin them as integers.
        val substitution = if (packed.baked is PropagationResult.Unsat ||
            !config.resolved(PresolvePass.SUBSTITUTE_BINARY_COLUMNS, context)
        ) {
            null
        } else {
            presolveBudget.observeTransform(
                PresolvePass.SUBSTITUTE_BINARY_COLUMNS, packed, cancellation,
                eligible = { hasBinaryCandidate(packed, emptySet(), cancellation) },
                unchanged = { null },
                output = { it?.problem ?: packed },
            ) {
                BinaryColumnSubstitution.substitute(
                    packed, context.objectiveIntVars, bakeConfig, context.linearObjective,
                    allowMixedObjective = config.affinePivotOrder == AffinePivotOrder.REWRITE_COST,
                ).also { result ->
                    presolveBudget?.activeInvocation?.fixedColumns = result?.columns ?: 0
                }
            }
        }
        var current = substitution?.problem ?: packed
        currentObjective = refit(substitution?.objective ?: currentObjective, current)
        context = context.withObjective(currentObjective)
        substitution?.let {
            mappings.add(SourceMapping(packed, it.problem, TransformationGuarantees.IDENTITY, it.rebuild))
            firedPasses.add(PresolvePass.SUBSTITUTE_BINARY_COLUMNS.id)
        }
        var harvest = LpHarvestReport() // the LP harvest's own contribution, summed over rounds
        var harvestStats = prepared.stats.lpStats
            .mergedWith(rootInfeasible.stats)
            .mergedWith(rootBounds.stats)
        var infeasible = current.baked is PropagationResult.Unsat
        var round = 0
        while (!infeasible && round++ < MAX_PRESOLVE_HARVEST_ROUNDS && !cancellation()) {
            val pre = Presolver.run(current, config, context, cancellation)
            currentObjective = pre.objective ?: currentObjective
            context = context.withObjective(currentObjective)
            infeasible = infeasible || pre.infeasible
            val harvestResult = if (infeasible) null else harvestPlan?.let {
                presolveBudget.observeTransform(
                    PresolvePass.LP_HARVEST, pre.problem, cancellation,
                    // Cardinality rewrites strengthen clause relaxations; propagator-only symmetry adds no LP rows.
                    eligible = {
                        !automaticClausalSatisfaction || pre.problem.factors.any { factor -> factor is Cardinality }
                    },
                    unchanged = { LpHarvestResult(pre.problem, LpHarvestReport(), LpStats()) },
                    output = { result -> result.problem },
                    refuted = { result ->
                        result.report.rootInfeasible ||
                            result.problem.baked is PropagationResult.Unsat
                    },
                ) {
                    lpHarvestReporting(
                        pre.problem,
                        currentObjective ?: LinearObjective(),
                        it.copy(sourcePreparation = sourcePreparation),
                        bakeConfig,
                        cancellation,
                        zeroObjectivePricing,
                        randomSeed,
                        numericalLp = numericalLp,
                    ).also { result ->
                        presolveBudget?.activeInvocation?.let { invocation ->
                            invocation.narrowedColumns = result.report.boundsShaved
                            if (result.report.skipped) invocation.outcome = PresolveInvocationOutcome.ELIGIBILITY_SKIP
                        }
                    }
                }
            }
            harvestResult?.let {
                harvestStats = harvestStats.mergedWith(it.stats)
            }
            val harvested = harvestResult?.problem ?: pre.problem
            infeasible = infeasible || harvested.baked is PropagationResult.Unsat ||
                harvestResult?.report?.rootInfeasible == true
            mappings.add(pre.mapping)
            if (harvested !== pre.problem) {
                mappings.add(SourceMapping(pre.problem, harvested, TransformationGuarantees.IDENTITY))
            }
            if (pre.problem === current && harvested === pre.problem) break
            pre.passesFired.forEach { firedPasses.add(it.id) }
            harvestResult?.let { harvest += it.report }
            current = harvested
            // A no-op harvest means the next round's presolve would re-derive the same fixpoint, so stop.
            if (harvested === pre.problem) break
        }
        // The base bake is not a presolve reduction (the solve boundary re-runs it), so when nothing beyond it
        // changed, report no change and let the caller keep its raw problem.
        val onlyBaseBake = current === seeded && prebaked === sourceProblem && seeded === baked
        // The joint difference system is appended here rather than by a round pass: it mentions every
        // variable its edges touch, so between rounds it would hold those variables against affine
        // elimination. After the fixpoint there is no pass left to block.
        val reduced = current
        val posted = if (!infeasible && config.resolved(PresolvePass.POST_DIFFERENCE_SYSTEM, context)) {
            presolveBudget.observeTransform(
                PresolvePass.POST_DIFFERENCE_SYSTEM, reduced, cancellation,
                eligible = { reduced.numBoolVars > 0 && reduced.numIntVars >= 2 },
                unchanged = { reduced },
                output = { it },
            ) { reduced.withDifferenceSystem() }
        } else {
            reduced
        }
        if ((current === sourceProblem || onlyBaseBake) && sourceProblem === problem && !infeasible &&
            posted === reduced
        ) {
            // The step-0 bake still ran; reporting its time keeps that cost from being misattributed to
            // the rest of the phase on a no-op presolve.
            return PresolveOutcome(
                problem,
                { it },
                PresolveStats(bakeElapsed = bakeElapsed, lpStats = harvestStats),
                changed = false,
            ).also { it.mapping = SourceMapping(problem, problem, TransformationGuarantees.IDENTITY) }
        }

        val passes = firedPasses.toList() +
            (if (!harvest.isEmpty) listOf("lp-harvest") else emptyList()) +
            (if (posted !== reduced) listOf(PresolvePass.POST_DIFFERENCE_SYSTEM.id) else emptyList())
        val stats = PresolveStats(
            passes = passes,
            // Counted against the reduced problem: the appended system is redundant with the rows it
            // reads, so it is not a constraint the reductions failed to remove.
            constraintsRemoved = problem.factors.size - reduced.factors.size,
            infeasible = infeasible || harvest.rootInfeasible,
            lpHarvest = harvest.takeUnless { it.isEmpty },
            lpStats = harvestStats,
            bakeElapsed = bakeElapsed,
        )
        if (posted !== current) mappings.add(SourceMapping(current, posted, TransformationGuarantees.IDENTITY))
        val mapping = mappings.reduce { acc, next -> acc.then(next) }
        return PresolveOutcome(
            posted,
            { mapping.reconstructFrom(posted, it) },
            stats,
            changed = true,
            objective = refit(currentObjective, posted),
        ).also { it.mapping = mapping }
    }
}

private fun refit(objective: LinearObjective?, problem: Problem): LinearObjective? {
    if (objective == null || objective.boolWeightValues.size >= problem.numBoolVars) return objective
    return objective.copy(boolWeights = objective.boolWeightValues.copyOf(problem.numBoolVars))
}

// Whatever token the caller built, the phase's work lands in its own budget.
private fun phaseCancellation(cancellation: ExecutionControl, budget: PresolveBudget?): ExecutionControl =
    budget?.orSpent(cancellation) ?: cancellation

private fun needsWideDomainLp(problem: Problem): Boolean {
    val threshold = problem.settings.largeSpanThreshold
    if (Presolve.maxIntSpan(problem) <= threshold) return false
    val wide = BooleanArray(problem.numIntVars) { v ->
        val domain = problem.finiteIntDomain(v)
        val span = domain.max - domain.min
        domain.min < domain.max && (span < 0L || span > threshold)
    }
    for (factor in problem.factors) {
        var first = -1
        for (v in factor.intVars) {
            if (!wide[v]) continue
            if (factor.variables.reals.isNotEmpty()) return true
            if (first >= 0 && v != first) return true
            first = v
        }
    }
    return false
}

// Half of what remains, like the round engine's per-pass policy: these LPs run before any pass, and one the
// LP cannot close would otherwise spend the whole allowance and yield nothing when cancelled mid-solve.
private fun preBakeSlice(cancellation: ExecutionControl, budget: PresolveBudget?): ExecutionControl = budget?.let {
    it.slice(it.remaining() / 2) or cancellation
} ?: cancellation

private fun hasBinaryCandidate(problem: BakedProblem, objective: Set<Int>, cancellation: ExecutionControl): Boolean {
    for (v in 0 until problem.numIntVars) {
        cancellation.charge(1L)
        if (cancellation()) return false
        val domain = problem.rootIntDomain(v)
        if (domain.min == 0L && domain.max == 1L && v !in objective) return true
    }
    return false
}
