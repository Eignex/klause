package com.eignex.klause.presolve

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.difference.withDifferenceSystem
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpHarvestReport
import com.eignex.klause.solver.result.PresolveStats
import kotlin.time.TimeSource

/** Round cap for the presolve↔LP-harvest fixpoint: a spin guard, never the real stop. The loop
 *  exits as soon as a harvest tightens nothing, which is the common case after the first round. */
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
     * The caller's objective re-fitted to [problem]'s variable space, or `null` when it needs no change (no
     * objective, or a transform that left the variable counts alone). Only
     * [PresolvePass.SUBSTITUTE_BINARY_COLUMNS] moves them: it extends the Boolean namespace, which an
     * objective's `boolWeights` must span. The new variables carry no objective weight — a column the
     * objective reads is never substituted — so this is the same objective, zero-extended.
     */
    val objective: LinearObjective? = null,
)

/**
 * The presolve driver: the full-model transform pipeline, independent of any front-end representation.
 * Presolve and the LP-relaxation harvest are iterated to a fixpoint: the harvest's proven domain
 * tightenings can unlock further reductions (coefficient strengthening, affine elimination, structural
 * reductions), which can in turn expose more for the next harvest. The loop is self-gating — it runs a
 * second [Presolver.run] only when a harvest actually tightened the problem — and bounded by
 * [MAX_PRESOLVE_HARVEST_ROUNDS]. Lives in the presolve layer (not a front-end) and drives the LP steps
 * directly through [lp.bounding][com.eignex.klause.lp.bounding], so every caller shares the same pipeline.
 */
object PresolvePipeline {

    /**
     * Run presolve over [problem] with [config], resolving effort against [linearObjective] and
     * [solutionSetSensitive]. [cancellation] caps every pass and LP solve.
     */
    fun run(
        problem: Problem,
        linearObjective: LinearObjective?,
        config: PresolveConfig,
        solutionSetSensitive: Boolean,
        cancellation: Cancellation = Cancellation.Never,
        presolveBudget: PresolveBudget? = null,
    ): PresolveOutcome {
        // Root-bake probing (failed-literal / SAC) runs in the presolve lane via [RootBaker]: resolve it
        // from the config once and thread it through the context so every rebuild re-derives it.
        val bakeConfig = BakeConfig.from(config)
        val context = PresolveContext.of(linearObjective, solutionSetSensitive, problem.hasSymmetryBreaking)
            .withBakeConfig(bakeConfig)
            .withPresolveBudget(presolveBudget)
        // LP-relaxation harvest: fold the LP's proven domain tightenings, redundant-row removals and
        // implied equalities into the problem permanently so every backend sees them. Gated by the
        // `lp-harvest` pass; the harvest's own LP relaxation+shaving is enabled here directly.
        val harvestPlan = if (config.resolved(PresolvePass.LP_HARVEST, context)) {
            LpPlan(bounding = true, variableShaving = true, objectiveShaving = true)
        } else {
            null
        }
        val objective = linearObjective ?: LinearObjective()

        // Coefficient strengthening runs first — before [RootBaker.reseed] forces the root bake: a
        // gcd-indivisible equality is infeasible regardless of the (possibly very wide) variable bounds, so
        // it is caught in O(factors). The bake would otherwise narrow it toward the empty domain one step
        // per round — O(span) on a wide clamped domain — before any pass runs.
        val strengthenInfeasible = config.resolved(PresolvePass.STRENGTHEN_COEFFICIENTS, context) &&
            Presolve.strengthenCoefficients(problem).infeasible

        // On a genuinely wide integer domain, the LP relaxation proves global infeasibility (e.g. a
        // difference cycle `x < y ∧ y < x`) in O(one LP solve) — before [RootBaker.reseed]'s bound
        // propagation would grind it out one step per round (O(span)). [lpRootInfeasible] builds the root
        // relaxation straight from the declared domains (no bake fixpoint) and certifies infeasibility via
        // an exact Farkas ray; a true result contains every integer solution, so it is the same verdict the
        // bake would reach. Gated on span so small models never pay the LP.
        val wideSpan = Presolve.maxIntSpan(problem) > KlauseConfig.current.largeSpanThreshold
        val lpInfeasible = !strengthenInfeasible && wideSpan &&
            lpRootInfeasible(problem, objective, LpPlan(bounding = true), preBakeSlice(cancellation, presolveBudget))
        val preBakeInfeasible = strengthenInfeasible || lpInfeasible

        // On a wide but feasible domain the LP still can't be skipped like the infeasible case, but its
        // optimum-based bound tightening (OBBT, [lpRootBounds]) collapses each variable's clamped domain in
        // one solve per bound — so the root bake starts from the tightened domains instead of narrowing them
        // one step per round (O(span)). Solution-set-preserving, so it is sound before the bake. Gated on
        // span so small models never pay the OBBT; skipped when already proven infeasible.
        val prebaked = if (!preBakeInfeasible && wideSpan) {
            lpRootBounds(problem, objective, LpPlan(bounding = true), preBakeSlice(cancellation, presolveBudget))
        } else {
            problem
        }

        // Step 0: run the deferred base bake — fold the root propagation into the domains. A no-op for a
        // directly-constructed (already-baked) problem, so this is where a front-end's deferred base bake
        // actually happens, after the O(one-LP) pre-bake infeasibility/OBBT that must precede it.
        val bakeStart = TimeSource.Monotonic.markNow()
        val baked = if (preBakeInfeasible) problem else prebaked.bake(cancellation)
        val seeded = if (preBakeInfeasible) problem else RootBaker.reseed(baked, bakeConfig)
        val bakeElapsed = bakeStart.elapsedNow()
        val reconstructs = ArrayList<(Sample) -> Sample>() // in application order; round 1 first
        val firedPasses = LinkedHashSet<String>() // pass ids that fired, across all rounds, in first-fire order
        // Pseudo-Boolean lane substitution: a `{0, 1}` integer column becomes a Boolean literal and the rows
        // over such columns become clause / cardinality / pseudo-Boolean factors. It runs ahead of the round
        // engine, on the bake's committed domains — that is what makes the `{0, 1}` columns visible — so
        // every round pass then reads the model in the lane it will be solved in: the literal-aware
        // reductions (at-most-one clique merging, coefficient strengthening over literals, bounded variable
        // elimination, clause subsumption) apply where only the integer-column ones could before. It also
        // has to precede symmetry breaking, whose added handling factor reads the columns value-wise and
        // would hold every one of them in the integer lane.
        val substitution = if (preBakeInfeasible || !config.resolved(PresolvePass.SUBSTITUTE_BINARY_COLUMNS, context)) {
            null
        } else {
            BinaryColumnSubstitution.substitute(seeded, context.objectiveIntVars, bakeConfig)
        }
        var current = substitution?.problem ?: seeded
        substitution?.let {
            reconstructs.add(it.reconstruct)
            firedPasses.add(PresolvePass.SUBSTITUTE_BINARY_COLUMNS.id)
        }
        var harvest = LpHarvestReport() // the LP harvest's own contribution, summed over rounds
        var infeasible = preBakeInfeasible
        var round = 0
        while (!preBakeInfeasible && round++ < MAX_PRESOLVE_HARVEST_ROUNDS && !cancellation()) {
            val pre = Presolver.run(current, config, context, cancellation)
            infeasible = infeasible || pre.infeasible
            val harvestResult = harvestPlan?.let {
                lpHarvestReporting(pre.problem, objective, it, bakeConfig, cancellation)
            }
            val harvested = harvestResult?.problem ?: pre.problem
            // Neither presolve nor the harvest changed anything this round → fixpoint.
            if (pre.problem === current && harvested === pre.problem) break
            pre.passesFired.forEach { firedPasses.add(it.id) }
            harvestResult?.let { harvest += it.report }
            // The harvest only narrows domains, so it contributes no reconstruct; add presolve's only when it
            // actually transformed the problem (else it is the identity).
            if (pre.problem !== current) reconstructs.add(pre.reconstruct)
            current = harvested
            // A no-op harvest means the next round's presolve would re-derive the same fixpoint, so stop.
            if (harvested === pre.problem) break
        }
        val reconstruct: (Sample) -> Sample = { sample -> reconstructs.foldRight(sample) { f, acc -> f(acc) } }
        // The base bake (declared → root-propagated domains) is not a presolve reduction: the solve boundary
        // re-runs it. So when no pass fired ([current] === [seeded]) and neither the OBBT pre-bake ([prebaked]
        // === [problem]) nor the probing reseed ([seeded] === [baked]) tightened anything beyond that base
        // fold, report no change and let the caller keep its raw problem — preserving object identity for a
        // genuine no-op presolve, exactly as an already-baked input did before the bake became a fresh type.
        val onlyBaseBake = current === seeded && prebaked === problem && seeded === baked
        // The joint difference system is appended here rather than by a round pass: it mentions every
        // variable its edges touch, so between rounds it would hold those variables against affine
        // elimination. After the fixpoint there is no pass left to block.
        val reduced = current
        val posted = if (!infeasible && config.resolved(PresolvePass.POST_DIFFERENCE_SYSTEM, context)) {
            reduced.withDifferenceSystem()
        } else {
            reduced
        }
        if ((current === problem || onlyBaseBake) && !infeasible && posted === reduced) {
            // The step-0 bake still ran, and reporting it as zero leaves its cost folded into the phase
            // total. That mis-attribution lands on exactly the runs anyone would investigate, since
            // "presolve changed nothing" is what makes a run interesting in the first place.
            return PresolveOutcome(problem, reconstruct, PresolveStats(bakeElapsed = bakeElapsed), changed = false)
        }

        // Terse presolve summary for `-s`: which passes fired (+ `lp-harvest` when the LP tightened anything)
        // and the net constraint drop / proven infeasibility, with the LP harvest's own breakdown attached.
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
            bakeElapsed = bakeElapsed,
        )
        return PresolveOutcome(posted, reconstruct, stats, changed = true, objective = refit(linearObjective, posted))
    }
}

/** [objective] zero-extended to cover [problem]'s Boolean namespace, or `null` when it already does (so the
 *  caller keeps its own). See [PresolveOutcome.objective]. */
private fun refit(objective: LinearObjective?, problem: Problem): LinearObjective? {
    if (objective == null || objective.boolWeights.size >= problem.numBoolVars) return null
    return objective.copy(boolWeights = objective.boolWeights.copyOf(problem.numBoolVars))
}

/**
 * A slice of [budget] for one pre-bake root LP, falling back to [cancellation] when the phase carries no
 * budget. These run before any pass, so a relaxation the LP cannot close in the time available would
 * otherwise spend the whole allowance and leave the round engine none — and, cancelled mid-solve, it
 * yields nothing at all for the time it took. Half of what remains, matching the round engine's own
 * per-pass policy, so each stage costs a bounded share of the phase rather than the phase itself.
 */
private fun preBakeSlice(cancellation: Cancellation, budget: PresolveBudget?): Cancellation = budget?.let {
    val slice = it.slice(it.remaining() / 2)
    Cancellation { cancellation() || slice() }
} ?: cancellation
