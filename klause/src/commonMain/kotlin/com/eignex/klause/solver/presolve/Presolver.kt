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
class PresolveContext(val objectiveIntVars: Set<Int> = emptySet(), val objectiveBoolVars: Set<Int> = emptySet()) {
    /** Factories for the common contexts. */
    companion object {
        /** Protects no variable — pure feasibility, or when there is no objective. */
        val EMPTY = PresolveContext()

        /** Protect every variable an objective reads — the nonzero-coefficient indices. */
        fun of(objective: LinearObjective?): PresolveContext {
            if (objective == null) return EMPTY
            val ints = HashSet<Int>()
            for (i in objective.intCoefficients.indices) if (objective.intCoefficients[i] != 0L) ints.add(i)
            val bools = HashSet<Int>()
            for (b in objective.boolWeights.indices) if (objective.boolWeights[b] != 0L) bools.add(b)
            return PresolveContext(ints, bools)
        }
    }
}

/** A configurable presolve transform. The string [id] is the serializable form used by
 *  [PresolveConfig.parse] and the CLI `--presolve` flag, so CLI, bench, and config strings never
 *  drift. */
enum class PresolvePass(val id: String) {
    /** GCD coefficient strengthening (#319). Same variable space, identity reconstruction. */
    STRENGTHEN_COEFFICIENTS("strengthen"),

    /** Affine singleton elimination (#318). Reconstructs the eliminated variable. */
    ELIMINATE_AFFINE_SINGLETONS("affine"),

    /** Interchangeable-variable symmetry breaking (#317). Same space, identity reconstruction.
     *  Must be omitted for a pure local-search engine (ordering constraints hurt LS). */
    BREAK_SYMMETRIES("symmetry"),
    ;

    /** Lookup by serializable [id]. */
    companion object {
        /** The pass whose [id] equals [id], or `null` if none matches. */
        fun fromId(id: String): PresolvePass? = entries.firstOrNull { it.id == id }
    }
}

/**
 * An ordered, serializable list of [PresolvePass]es. Parse from a spec string via [parse]
 * (`none` / `default` / `all` / comma-list of ids). The order is the order of application.
 */
class PresolveConfig(
    /** The passes to apply, in application order. */
    val passes: List<PresolvePass>,
) {

    /** Drop [PresolvePass.BREAK_SYMMETRIES] — for a pure local-search engine, where ordering
     *  constraints hurt. */
    fun withoutSymmetry(): PresolveConfig = PresolveConfig(passes.filter { it != PresolvePass.BREAK_SYMMETRIES })

    /** Predefined configs and the spec-string [parse]r. */
    companion object {
        /** Cheap, sound passes enabled by the automatic path. */
        val DEFAULT = PresolveConfig(
            listOf(
                PresolvePass.STRENGTHEN_COEFFICIENTS,
                PresolvePass.ELIMINATE_AFFINE_SINGLETONS,
                PresolvePass.BREAK_SYMMETRIES,
            ),
        )

        /** Run no presolve passes. */
        val NONE = PresolveConfig(emptyList())

        /**
         * `null`/blank → [DEFAULT]; `none`/`off` → [NONE]; `default`/`all` → [DEFAULT]; otherwise a
         * comma-separated list of pass ids, in application order. An unknown id throws.
         */
        fun parse(spec: String?): PresolveConfig = when (val s = spec?.trim()?.lowercase()) {
            null, "", "default", "all" -> DEFAULT

            "none", "off" -> NONE

            else -> PresolveConfig(
                s.split(",").map { token ->
                    val id = token.trim()
                    PresolvePass.fromId(id) ?: error("unknown presolve pass `$id`")
                },
            )
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
        for (pass in config.passes) {
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
