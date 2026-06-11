package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Presolve
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample

/**
 * Result of running a presolve pipeline: the transformed [problem] plus the [reconstruct]
 * function that maps a solution of [problem] back to a solution of the original problem. For an
 * all-identity pipeline [reconstruct] is the identity (no per-sample cost).
 */
class Presolved(val problem: Problem, val reconstruct: (Sample) -> Sample)

/**
 * Information a pass needs to stay sound. [objectiveIntVars] / [objectiveBoolVars] are the
 * variables an objective reads (so passes that eliminate variables or add ordering constraints
 * must leave them alone — eliminating an objective variable, or breaking symmetry over one, would
 * change the optimum).
 */
class PresolveContext(
    val objectiveIntVars: Set<Int> = emptySet(),
    val objectiveBoolVars: Set<Int> = emptySet(),
    /**
     * True when the caller needs the full solution set preserved — enumeration, model counting,
     * or diverse sampling. Solution-set-altering passes (notably symmetry breaking) auto-resolve
     * to OFF in this case, since they would silently drop valid solutions. Defaults to `false`
     * (a decision / optimization query, where collapsing symmetric solutions is fine).
     */
    val solutionSetSensitive: Boolean = false,
) {
    /** Factories for the common contexts. */
    companion object {
        /** Protects no variable — pure feasibility, or when there is no objective. */
        val EMPTY = PresolveContext()

        /** Protect every variable an objective reads — the nonzero-coefficient indices. */
        fun of(objective: LinearObjective?, solutionSetSensitive: Boolean = false): PresolveContext {
            if (objective == null) return PresolveContext(solutionSetSensitive = solutionSetSensitive)
            val ints = HashSet<Int>()
            for (i in objective.intCoefficients.indices) if (objective.intCoefficients[i] != 0L) ints.add(i)
            val bools = HashSet<Int>()
            for (b in objective.boolWeights.indices) if (objective.boolWeights[b] != 0L) bools.add(b)
            return PresolveContext(ints, bools, solutionSetSensitive)
        }
    }
}

/** A configurable presolve transform. The string [id] is the serializable form used by
 *  [PresolveConfig.parse] and the CLI `--presolve` flag, so CLI, bench, and config strings never
 *  drift. */
enum class PresolvePass(val id: String, val stage: Stage, val preservesSolutionSet: Boolean) {
    /** GCD coefficient strengthening (#319). Same variable space, identity reconstruction. */
    STRENGTHEN_COEFFICIENTS("strengthen", Stage.PROBLEM, preservesSolutionSet = true),

    /** Affine singleton elimination (#318). Reconstructs the eliminated variable. */
    ELIMINATE_AFFINE_SINGLETONS("affine", Stage.PROBLEM, preservesSolutionSet = true),

    /** Interchangeable-variable symmetry breaking (#317). Same space, identity reconstruction.
     *  Solution-set-ALTERING: collapses symmetric solutions, so it is sound for decision /
     *  optimization but drops valid solutions for enumeration / counting / sampling. Also hurts a
     *  pure local-search engine (the ordering constraints fight the search). */
    BREAK_SYMMETRIES("symmetry", Stage.PROBLEM, preservesSolutionSet = false),

    /** Construction-time failed-literal SAC (#146): fold forced polarities into `Problem.baked`. */
    PROBE_FAILED_LITERALS("probe-failed-literals", Stage.CONSTRUCTION, preservesSolutionSet = true),

    /** Construction-time bound SAC: tighten int-var bounds via probe-and-propagate. */
    PROBE_INT_BOUNDS("probe-int-bounds", Stage.CONSTRUCTION, preservesSolutionSet = true),

    /** Construction-time interior-hole SAC; implies [PROBE_INT_BOUNDS]. */
    PROBE_INT_HOLES("probe-int-holes", Stage.CONSTRUCTION, preservesSolutionSet = true),
    ;

    /** The pipeline stage a pass runs at: at [Problem] construction (folded into `baked`) or as a
     *  problem-to-problem transform before solving. */
    enum class Stage { CONSTRUCTION, PROBLEM }

    /** Lookup by serializable [id]. */
    companion object {
        /** The pass whose [id] equals [id], or `null` if none matches. */
        fun fromId(id: String): PresolvePass? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Per-pass presolve settings, each a tri-state `Boolean?`: an explicit `true` forces the pass on,
 * `false` forces it off, and an absent entry means **auto** — resolved per [PresolvePass] by
 * [resolved] using the query [PresolveContext]. [AUTO] (all auto) is the default; the CLI
 * `--presolve` flag and the `klause.presolve` property parse into this via [parse].
 */
class PresolveConfig(
    /** Explicit per-pass overrides; passes absent from the map are resolved automatically. */
    val settings: Map<PresolvePass, Boolean> = emptyMap(),
) {

    /** Whether [pass] runs under [context]: an explicit setting wins, else the pass's auto rule. */
    fun resolved(pass: PresolvePass, context: PresolveContext): Boolean =
        settings[pass] ?: autoEnabled(pass, context)

    /** The [Stage.PROBLEM] passes that run under [context], in enum (application) order. */
    fun problemPasses(context: PresolveContext): List<PresolvePass> =
        PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM && resolved(it, context) }

    /** Force [PresolvePass.BREAK_SYMMETRIES] off — for a pure local-search engine, where the
     *  ordering constraints hurt, regardless of auto resolution. */
    fun withoutSymmetry(): PresolveConfig =
        PresolveConfig(settings + (PresolvePass.BREAK_SYMMETRIES to false))

    /** Auto rule per pass, chosen to preserve historical defaults: the cheap solution-preserving
     *  problem passes are on; symmetry is on only for non-solution-set-sensitive queries; the
     *  expensive construction-time SAC probes are opt-in (off). */
    private fun autoEnabled(pass: PresolvePass, context: PresolveContext): Boolean = when (pass) {
        PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.ELIMINATE_AFFINE_SINGLETONS -> true
        PresolvePass.BREAK_SYMMETRIES -> !context.solutionSetSensitive
        PresolvePass.PROBE_FAILED_LITERALS, PresolvePass.PROBE_INT_BOUNDS, PresolvePass.PROBE_INT_HOLES -> false
    }

    /** Predefined configs and the spec-string [parse]r. */
    companion object {
        /** All passes auto — the default. */
        val AUTO = PresolveConfig(emptyMap())

        /** Back-compat alias: the automatic path. */
        val DEFAULT = AUTO

        /** Force every pass off. */
        val NONE = PresolveConfig(PresolvePass.entries.associateWith { false })

        /**
         * `null`/blank/`default`/`auto` → [AUTO]; `none`/`off` → [NONE]; `all` → every pass forced
         * on; otherwise a comma-separated list of pass ids, each forced on with all others forced
         * off. An unknown id throws.
         */
        fun parse(spec: String?): PresolveConfig = when (val s = spec?.trim()?.lowercase()) {
            null, "", "default", "auto" -> AUTO

            "none", "off" -> NONE

            "all" -> PresolveConfig(PresolvePass.entries.associateWith { true })

            else -> {
                val on = s.split(",").map { token ->
                    val id = token.trim()
                    PresolvePass.fromId(id) ?: error("unknown presolve pass `$id`")
                }.toSet()
                PresolveConfig(PresolvePass.entries.associateWith { it in on })
            }
        }
    }
}

/**
 * Runs a [PresolveConfig] over a [Problem]: applies each pass in order, threading the transformed
 * problem forward, and composes the per-pass reconstruct functions in reverse so the returned
 * [Presolved.reconstruct] maps a final-problem solution all the way back to the original.
 */
object Presolver {

    /** Apply [config]'s passes to [problem] under [context], returning the transformed problem and
     *  a reconstruct that maps its solutions back to the original problem. */
    fun run(problem: Problem, config: PresolveConfig, context: PresolveContext = PresolveContext.EMPTY): Presolved {
        var current = problem
        val reconstructs = ArrayList<(Sample) -> Sample>() // in application order
        for (pass in config.problemPasses(context)) {
            when (pass) {
                PresolvePass.STRENGTHEN_COEFFICIENTS ->
                    current = Presolve.strengthenCoefficients(current)

                PresolvePass.ELIMINATE_AFFINE_SINGLETONS -> {
                    val elim = Presolve.eliminateAffineSingletons(current, context.objectiveIntVars)
                    current = elim.problem
                    reconstructs.add(elim::reconstruct)
                }

                PresolvePass.BREAK_SYMMETRIES ->
                    current = Presolve.breakSymmetries(current, context.objectiveIntVars, context.objectiveBoolVars)

                // Construction-time SAC passes never reach here — problemPasses filters to Stage.PROBLEM.
                PresolvePass.PROBE_FAILED_LITERALS,
                PresolvePass.PROBE_INT_BOUNDS,
                PresolvePass.PROBE_INT_HOLES,
                -> {}
            }
        }
        val reconstruct: (Sample) -> Sample =
            if (reconstructs.isEmpty()) {
                { it }
            } else {
                { sample -> reconstructs.foldRight(sample) { f, acc -> f(acc) } }
            }
        return Presolved(current, reconstruct)
    }
}
