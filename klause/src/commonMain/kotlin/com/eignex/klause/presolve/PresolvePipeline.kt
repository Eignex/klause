package com.eignex.klause.presolve

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpHarvestReport
import com.eignex.klause.solver.result.PresolveStats
import kotlin.time.TimeSource

/** Round cap for the presolve↔LP-harvest fixpoint (#14): a spin guard, never the real stop. The loop
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
)

/**
 * The presolve driver: the full-model transform pipeline, independent of any front-end representation.
 * Presolve and the LP-relaxation harvest are iterated to a fixpoint (#14): the harvest's proven domain
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
    ): PresolveOutcome {
        // Root-bake probing (failed-literal / SAC) runs in the presolve lane via [RootBaker]: resolve it
        // from the config once and thread it through the context so every rebuild re-derives it.
        val bakeConfig = BakeConfig.from(config)
        val context = PresolveContext.of(linearObjective, solutionSetSensitive, problem.hasSymmetryBreaking)
            .withBakeConfig(bakeConfig)
        // LP-relaxation harvest (#10): fold the LP's proven domain tightenings, redundant-row removals and
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
            lpRootInfeasible(problem, objective, LpPlan(bounding = true), cancellation)
        val preBakeInfeasible = strengthenInfeasible || lpInfeasible

        // On a wide but feasible domain the LP still can't be skipped like the infeasible case, but its
        // optimum-based bound tightening (OBBT, [lpRootBounds]) collapses each variable's clamped domain in
        // one solve per bound — so the root bake starts from the tightened domains instead of narrowing them
        // one step per round (O(span)). Solution-set-preserving, so it is sound before the bake. Gated on
        // span so small models never pay the OBBT; skipped when already proven infeasible.
        val prebaked = if (!preBakeInfeasible && wideSpan) {
            lpRootBounds(problem, objective, LpPlan(bounding = true), cancellation)
        } else {
            problem
        }

        // Step 0: run the deferred base bake — fold the root propagation into the domains. A no-op for a
        // directly-constructed (already-baked) problem, so this is where a front-end's deferred base bake
        // actually happens, after the O(one-LP) pre-bake infeasibility/OBBT that must precede it.
        val bakeStart = TimeSource.Monotonic.markNow()
        val seeded = if (preBakeInfeasible) problem else RootBaker.reseed(prebaked.bakeBase(), bakeConfig)
        val bakeElapsed = bakeStart.elapsedNow()
        var current = seeded
        val reconstructs = ArrayList<(Sample) -> Sample>() // in application order; round 1 first
        val firedPasses = LinkedHashSet<String>() // pass ids that fired, across all rounds, in first-fire order
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
        if (current === problem && !infeasible) {
            return PresolveOutcome(problem, reconstruct, PresolveStats(), changed = false)
        }

        // Terse presolve summary for `-s`: which passes fired (+ `lp-harvest` when the LP tightened anything)
        // and the net constraint drop / proven infeasibility, with the LP harvest's own breakdown attached.
        val passes = firedPasses.toList() + (if (!harvest.isEmpty) listOf("lp-harvest") else emptyList())
        val stats = PresolveStats(
            passes = passes,
            constraintsRemoved = problem.factors.size - current.factors.size,
            infeasible = infeasible || harvest.rootInfeasible,
            lpHarvest = harvest.takeUnless { it.isEmpty },
            bakeElapsed = bakeElapsed,
        )
        return PresolveOutcome(current, reconstruct, stats, changed = true)
    }
}
