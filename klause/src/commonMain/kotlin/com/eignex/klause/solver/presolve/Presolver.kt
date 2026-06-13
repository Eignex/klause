package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective

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
 * change the optimum). [solutionSetSensitive] is set when the caller needs every solution
 * (enumeration / counting / sampling), which turns off solution-set-altering passes.
 */
class PresolveContext(
    val objectiveIntVars: Set<Int> = emptySet(),
    val objectiveBoolVars: Set<Int> = emptySet(),
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

/**
 * Cost tier of a presolve pass, mirroring SCIP's FAST / MEDIUM / EXHAUSTIVE timing classes. A
 * [PresolveEmphasis] enables a set of tiers, so the level dial is "how expensive a pass may be".
 */
enum class PresolveTiming {
    /** Cheap, run every round (bound/coefficient reductions, substitution). */
    FAST,

    /** Moderate cost (symmetry detection); run from the default level up. */
    MEDIUM,

    /** Expensive (SAC probing); run only at the aggressive level. */
    EXHAUSTIVE,
}

/** Round cap for the iterating emphasis levels; the fixpoint is almost always reached well before. */
private const val MAX_PRESOLVE_ROUNDS = 16

/** What a [PresolvePass] produced: the (possibly identical) [problem] and, if it changed the
 *  variable mapping, the [reconstruct] that lifts a solution back. `problem === input` signals the
 *  pass was a no-op this round (the engine uses identity to detect the fixpoint). */
class PassResult(val problem: Problem, val reconstruct: ((Sample) -> Sample)? = null)

/**
 * The catalogue of presolve passes. Each entry co-locates its metadata with [apply], so adding or
 * toggling a pass is a single self-contained place. Metadata:
 *
 * @property id serializable form for [PresolveConfig.parse] and the CLI `--presolve` flag.
 * @property stage [Stage.PROBLEM] passes are run by the [Presolver] round engine; [Stage.CONSTRUCTION]
 *  passes (SAC probing) are folded into `Problem.baked` at build time and only read via
 *  [PresolveConfig.resolved].
 * @property timing cost tier — a [PresolveEmphasis] enables a set of tiers.
 * @property preservesSolutionSet whether the pass keeps every solution (true) or may collapse the
 *  set (false, e.g. symmetry breaking) — the latter is auto-disabled for solution-set-sensitive
 *  queries.
 * @property autoEligible whether emphasis may turn it on automatically; opt-in passes (value
 *  precedence, which interacts with variable-symmetry breaking) are `false` and need an explicit
 *  override.
 */
enum class PresolvePass(
    val id: String,
    val stage: Stage,
    val timing: PresolveTiming,
    val preservesSolutionSet: Boolean,
    val autoEligible: Boolean,
) {
    /** GCD + bounded-integer coefficient strengthening (#319 / #372). */
    STRENGTHEN_COEFFICIENTS("strengthen", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            PassResult(Presolve.strengthenCoefficients(problem))
    },

    /** Affine singleton elimination (#318) — reconstructs the eliminated variable. */
    ELIMINATE_AFFINE_SINGLETONS("affine", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext): PassResult {
            val elim = Presolve.eliminateAffineSingletons(problem, ctx.objectiveIntVars)
            return PassResult(elim.problem, elim::reconstruct)
        }
    },

    /** Constraint subsumption / redundant-constraint removal (#447) — drops duplicate factors and
     *  dominated linear inequalities. Runs after the simplifying passes so proportional rows are
     *  already GCD-normalised. */
    REMOVE_REDUNDANT("subsume", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            PassResult(Presolve.removeRedundantConstraints(problem))
    },

    /** Interchangeable-variable / block / value symmetry breaking (#317 / #367 / #373 / #366). */
    BREAK_SYMMETRIES(
        "symmetry",
        Stage.PROBLEM,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            PassResult(Presolve.breakSymmetries(problem, ctx.objectiveIntVars, ctx.objectiveBoolVars))
    },

    /** Law–Lee value precedence (#374 / #432) — the strong value-symmetry break. Opt-in: stacking it
     *  with [BREAK_SYMMETRIES] interacts (each pass's added factors disable the other's detection), so
     *  it is enabled only by an explicit override, as an alternative to the single-variable value pin. */
    VALUE_PRECEDENCE(
        "value-precede",
        Stage.PROBLEM,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = false,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            PassResult(Presolve.breakValuePrecedence(problem, ctx.objectiveIntVars))
    },

    /** Construction-time failed-literal SAC (#146): folded into `Problem.baked` at build, read via
     *  [PresolveConfig.resolved] — the [Presolver] engine never runs it (so [apply] is a no-op). */
    PROBE_FAILED_LITERALS(
        "probe-failed-literals",
        Stage.CONSTRUCTION,
        PresolveTiming.EXHAUSTIVE,
        true,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = PassResult(problem)
    },

    /** Construction-time bound SAC. */
    PROBE_INT_BOUNDS("probe-int-bounds", Stage.CONSTRUCTION, PresolveTiming.EXHAUSTIVE, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) = PassResult(problem)
    },

    /** Construction-time interior-hole SAC; implies [PROBE_INT_BOUNDS]. */
    PROBE_INT_HOLES("probe-int-holes", Stage.CONSTRUCTION, PresolveTiming.EXHAUSTIVE, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) = PassResult(problem)
    },
    ;

    /** Transform [problem] under [ctx]. The returned [PassResult.problem] is `=== problem` when the
     *  pass found nothing to do this round. */
    abstract fun apply(problem: Problem, ctx: PresolveContext): PassResult

    /** Where a pass runs. */
    enum class Stage {
        /** Folded into `Problem.baked` at construction (SAC probing). */
        CONSTRUCTION,

        /** A problem-to-problem transform run before solving, via [Presolver.run]. */
        PROBLEM,
    }

    /** Lookup by serializable [id]. */
    companion object {
        /** The pass whose [id] equals [id], or `null` if none matches. */
        fun fromId(id: String): PresolvePass? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Preset effort levels, mirroring SCIP's presolving emphases / the Gurobi `Presolve 0/1/2` dial.
 * Each level is just a bundle: which [PresolveTiming] tiers run, and how many round-to-fixpoint
 * iterations the engine may take (1 = a single pass, no iteration). Per-pass overrides on
 * [PresolveConfig] sit on top of the level.
 */
enum class PresolveEmphasis(
    /** Cost tiers this level lets run. */
    val timings: Set<PresolveTiming>,
    /** Round-to-fixpoint cap (`0` = no presolve, `1` = a single non-iterating pass). */
    val maxRounds: Int,
) {
    /** No presolve. */
    OFF(emptySet(), 0),

    /** Cheap FAST reductions only, applied once — no symmetry, no iteration. */
    CONSERVATIVE(setOf(PresolveTiming.FAST), 1),

    /** FAST + MEDIUM (adds symmetry), iterated to a fixpoint. The shipped default. */
    DEFAULT(setOf(PresolveTiming.FAST, PresolveTiming.MEDIUM), MAX_PRESOLVE_ROUNDS),

    /** FAST + MEDIUM + EXHAUSTIVE (adds SAC probing), iterated to a fixpoint. */
    AGGRESSIVE(setOf(PresolveTiming.FAST, PresolveTiming.MEDIUM, PresolveTiming.EXHAUSTIVE), MAX_PRESOLVE_ROUNDS),
    ;

    /** Spec-string parsing for the emphasis level. */
    companion object {
        /** `null`/blank/`default`/`auto` → [DEFAULT]; `off`/`none` → [OFF]; `conservative`/`fast` →
         *  [CONSERVATIVE]; `aggressive` → [AGGRESSIVE]; otherwise `null`. */
        fun fromId(id: String?): PresolveEmphasis? = when (id?.trim()?.lowercase()) {
            null, "", "default", "auto" -> DEFAULT
            "off", "none" -> OFF
            "conservative", "fast" -> CONSERVATIVE
            "aggressive" -> AGGRESSIVE
            else -> null
        }
    }
}

/**
 * A presolve configuration: an [emphasis] level plus per-pass [overrides] (force a single pass on or
 * off regardless of the level — the benchmarking toggle). [resolved] answers whether a pass runs
 * under a given [PresolveContext]; an override always wins, otherwise the level + the pass's
 * eligibility and solution-set-sensitivity decide.
 */
class PresolveConfig(
    val emphasis: PresolveEmphasis = PresolveEmphasis.DEFAULT,
    val overrides: Map<PresolvePass, Boolean> = emptyMap(),
) {

    /** Whether [pass] runs under [context]: an explicit override wins, else the emphasis rule. */
    fun resolved(pass: PresolvePass, context: PresolveContext): Boolean = overrides[pass] ?: auto(pass, context)

    /** Emphasis rule: an auto-eligible pass whose tier the level enables, unless it would drop
     *  solutions on a solution-set-sensitive query. */
    private fun auto(pass: PresolvePass, context: PresolveContext): Boolean = pass.autoEligible &&
        pass.timing in emphasis.timings &&
        (pass.preservesSolutionSet || !context.solutionSetSensitive)

    /** The [PresolvePass.Stage.PROBLEM] passes that run under [context], in enum (priority) order. */
    fun problemPasses(context: PresolveContext): List<PresolvePass> =
        PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM && resolved(it, context) }

    /** Force every solution-set-altering pass off (symmetry breaking, value precedence — the
     *  `!preservesSolutionSet` ones) — for a pure local-search engine, where the ordering constraints
     *  they add fight the search and it gains nothing from collapsing symmetric solutions. The cheap
     *  solution-preserving reductions (strengthening, substitution, probing) stay on. */
    fun forLocalSearch(): PresolveConfig = PresolveConfig(
        emphasis,
        overrides + PresolvePass.entries.filter { !it.preservesSolutionSet }.associateWith { false },
    )

    /** Predefined configs and the spec-string [parse]r. */
    companion object {
        /** The shipped default — [PresolveEmphasis.DEFAULT], no overrides. */
        val AUTO = PresolveConfig(PresolveEmphasis.DEFAULT)

        /** Back-compat alias for [AUTO]. */
        val DEFAULT = AUTO

        /** No presolve at all. */
        val NONE = PresolveConfig(PresolveEmphasis.OFF)

        /**
         * Parse a `--presolve` / `klause.presolve` spec:
         *  - an emphasis level (`default` / `auto`, `off` / `none`, `conservative` / `fast`,
         *    `aggressive`),
         *  - `all` — every pass forced on,
         *  - or a comma-separated list of pass ids, each forced on with all others forced off.
         *
         * An unknown token throws.
         */
        fun parse(spec: String?): PresolveConfig {
            val s = spec?.trim()?.lowercase().orEmpty()
            // An emphasis keyword (incl. blank → DEFAULT) wins; otherwise it's `all` or a pass list.
            PresolveEmphasis.fromId(s)?.let { return PresolveConfig(it) }
            if (s == "all") return PresolveConfig(overrides = PresolvePass.entries.associateWith { true })
            val on = s.split(",").map { token ->
                PresolvePass.fromId(token.trim()) ?: error("unknown presolve pass `${token.trim()}`")
            }.toSet()
            return PresolveConfig(overrides = PresolvePass.entries.associateWith { it in on })
        }
    }
}

/**
 * The presolve engine. Runs the enabled [PresolvePass.Stage.PROBLEM] passes in **rounds to a
 * fixpoint** (SCIP-style): each round applies, in priority order, every pass that hasn't already run
 * since the problem last changed; when a pass changes the problem the others become eligible again.
 * This captures cross-pass synergy (e.g. an affine elimination exposing a new GCD) instead of a
 * single linear sweep. The per-pass reconstructs are composed in reverse so [Presolved.reconstruct]
 * maps a final-problem solution all the way back to the original.
 *
 * [PresolveEmphasis.maxRounds] caps the iteration (`1` = the old single-pass behaviour, used by
 * [PresolveEmphasis.CONSERVATIVE]); the version-stamp skip below means a clean fixpoint usually stops
 * earlier.
 */
object Presolver {

    /** Apply [config]'s passes to [problem] under [context], returning the transformed problem and a
     *  reconstruct mapping its solutions back to the original. */
    fun run(problem: Problem, config: PresolveConfig, context: PresolveContext = PresolveContext.EMPTY): Presolved {
        val passes = config.problemPasses(context)
        val maxRounds = config.emphasis.maxRounds
        if (passes.isEmpty() || maxRounds == 0) return Presolved(problem, { it })

        var current = problem
        val reconstructs = ArrayList<(Sample) -> Sample>() // in application order
        // A monotone "problem version" bumped on every change; a pass that already ran at the current
        // version is skipped until some other pass changes the problem — the fixpoint/delay scheme.
        var version = 0
        val ranAtVersion = HashMap<PresolvePass, Int>()
        var round = 0
        while (round < maxRounds) {
            var ranAny = false
            for (pass in passes) {
                if (ranAtVersion[pass] == version) continue
                ranAtVersion[pass] = version
                ranAny = true
                val before = current
                val result = pass.apply(current, context)
                if (result.problem !== before) {
                    current = result.problem
                    result.reconstruct?.let { reconstructs.add(it) }
                    version++
                }
            }
            if (!ranAny) break // every enabled pass has run at the current version → fixpoint
            round++
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
