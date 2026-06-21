package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective

/**
 * Result of running a presolve pipeline: the transformed [problem] plus the [reconstruct]
 * function that maps a solution of [problem] back to a solution of the original problem. For an
 * all-identity pipeline [reconstruct] is the identity (no per-sample cost).
 *
 * [components] is the connected-component partition of [problem] — its independent subproblems by
 * variable↔factor incidence. It is purely structural (computed on the final, transformed problem)
 * and a consumer that does not decompose can ignore it; [ProblemComponents.isConnected] reports the
 * common single-block case where there is nothing to split.
 */
class Presolved(
    val problem: Problem,
    val reconstruct: (Sample) -> Sample,
    val components: ProblemComponents = ComponentDecomposition.decompose(problem),
)

/**
 * Information a pass needs to stay sound. [objectiveIntVars] / [objectiveBoolVars] are the
 * variables an objective reads (so passes that eliminate variables or add ordering constraints
 * must leave them alone — eliminating an objective variable, or breaking symmetry over one, would
 * change the optimum); they are exactly the keys of [objectiveIntCoeffs] / [objectiveBoolCoeffs].
 * [solutionSetSensitive] is set when the caller needs every solution (enumeration / counting /
 * sampling), which turns off solution-set-altering passes.
 */
class PresolveContext(
    val solutionSetSensitive: Boolean = false,
    /** Minimize-sense integer objective coefficients (var → nonzero coefficient), used by dual fixing
     *  to decide which bound a dominated variable can be pinned to. */
    val objectiveIntCoeffs: Map<Int, Long> = emptyMap(),
    /** Minimize-sense Boolean objective weights (var → nonzero weight), used by Boolean dual fixing. */
    val objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    /** The model already breaks symmetry itself (a `symmetry_breaking_constraint`, surfaced via
     *  [Problem.hasSymmetryBreaking]). Turns off [PresolvePass.BREAK_SYMMETRIES] in the default
     *  [PresolveConfig.resolved] decision: stacking klause's automorphism break on the model's
     *  hand-written one is redundant and the two can interact. An explicit override still forces it on. */
    val modelBreaksSymmetry: Boolean = false,
) {
    /** Integer variables the objective reads — the nonzero-coefficient indices. */
    val objectiveIntVars: Set<Int> get() = objectiveIntCoeffs.keys

    /** Boolean variables the objective reads — the nonzero-weight indices. */
    val objectiveBoolVars: Set<Int> get() = objectiveBoolCoeffs.keys

    /** Factories for the common contexts. */
    companion object {
        /** Protects no variable — pure feasibility, or when there is no objective. */
        val EMPTY = PresolveContext()

        /** Protect every variable an objective reads — the nonzero-coefficient indices. */
        fun of(
            objective: LinearObjective?,
            solutionSetSensitive: Boolean = false,
            modelBreaksSymmetry: Boolean = false,
        ): PresolveContext {
            if (objective == null) {
                return PresolveContext(
                    solutionSetSensitive = solutionSetSensitive,
                    modelBreaksSymmetry = modelBreaksSymmetry,
                )
            }
            val intCoeffs = HashMap<Int, Long>()
            for (i in objective.intCoefficients.indices) {
                val c = objective.intCoefficients[i]
                if (c != 0L) intCoeffs[i] = c
            }
            val boolCoeffs = HashMap<Int, Long>()
            for (b in objective.boolWeights.indices) {
                val w = objective.boolWeights[b]
                if (w != 0L) boolCoeffs[b] = w
            }
            return PresolveContext(solutionSetSensitive, intCoeffs, boolCoeffs, modelBreaksSymmetry)
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

/** Bake-time SAC probe budgets (the only EXHAUSTIVE-tier work). The unit is a `propagate` call.
 *  The capped tier bounds an EXHAUSTIVE pass turned on by an explicit override under a non-aggressive
 *  level so it can't dominate presolve time; the aggressive tier is larger but still bounded so a big
 *  instance with wide domains terminates. SCIP analog: per-presolver work limits. Tuned by #461. */
private const val CAPPED_PROBE_BUDGET_PER_VAR = 256
private const val CAPPED_PROBE_TOTAL_BUDGET = 20_000
private const val AGGRESSIVE_PROBE_BUDGET_PER_VAR = 4_096
private const val AGGRESSIVE_PROBE_TOTAL_BUDGET = 250_000

/** Free-Boolean candidate cap for the [PresolvePass.PROBE] fixpoint pass per invocation. Each
 *  candidate costs up to two `propagate` calls, so this bounds the pass's work on a Boolean-heavy
 *  model; the round engine re-enters the pass after other passes fire, picking up more candidates
 *  across rounds. */
private const val PROBE_PASS_MAX_CANDIDATES = 2_048

/** Free-Boolean candidate cap for the [PresolvePass.IMPLICATION_GRAPH] harvest, mirroring
 *  [PROBE_PASS_MAX_CANDIDATES]: each candidate costs up to two `propagate` calls to discover its
 *  outgoing implications, so this bounds the pass on a Boolean-heavy model. */
private const val IMPLICATION_GRAPH_MAX_CANDIDATES = 2_048

/** Diminishing-returns abort threshold (SCIP `abortfac`): a round that reduces the problem by less
 *  than this fraction ends the loop. Tiny, so it only trips on marginal spinning, never real work. */
private const val PRESOLVE_ABORT_FRACTION = 0.001

/** Cheap problem-complexity measure for the effectiveness abort: constraint count plus total domain
 *  span. Drops when a pass removes a constraint or tightens a domain; a round that instead grows the
 *  problem (symmetry breaking adding ordering constraints) simply doesn't trip the abort. */
private fun complexity(problem: Problem): Long {
    var c = problem.factors.size.toLong()
    for (d in problem.intDomains) c += d.max.toLong() - d.min.toLong()
    return c
}

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
 * @property preservesSolutionSet whether the pass leaves the model's solution **set and count**
 *  exactly intact (true), or may alter them (false) — by collapsing the set (e.g. symmetry breaking,
 *  dual fixing) *or* by inflating the model count (e.g. affine elimination, which folds the defining
 *  equality away and leaves the eliminated variable unconstrained, so a complete enumerator branches
 *  over its whole domain and yields each real solution once per spurious value). The latter are
 *  auto-disabled for solution-set-sensitive queries (`-a` / `-n N>1`).
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

    /** One-shot GF(2) elimination over all xor factors: emit implied root unit clauses. */
    DERIVE_XOR_UNITS("xor-units", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) = PassResult(Presolve.deriveXorUnits(problem))
    },

    /** Iterated activity-based bound tightening (FME bound propagation). Tightens each variable's
     *  domain from the min/max activity of the linear rows it appears in, to a local fixpoint; only
     *  ever narrows by a valid implication, so it preserves the solution set. */
    TIGHTEN_BOUNDS("bound-tighten", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) = PassResult(Presolve.tightenBounds(problem))
    },

    /** Affine singleton elimination (#318) — reconstructs the eliminated variable. The eliminated
     *  variable is left unconstrained in the presolved problem (its value is rebuilt from its partner
     *  on the way back), so a complete enumerator would branch over its domain and over-count each
     *  real solution (#507). Hence solution-set-sensitive (`preservesSolutionSet = false`): gated off
     *  under `-a` / `-n N>1`, while solve/optimize still benefit. */
    ELIMINATE_AFFINE_SINGLETONS(
        "affine",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
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

    /** Duplicate / parallel column aggregation — the column-side mirror of [REMOVE_REDUNDANT]. Folds
     *  two integer variables with coinciding columns into one aggregate, reconstructing the dropped
     *  variable from the aggregate's value. Like [ELIMINATE_AFFINE_SINGLETONS] the dropped variable is
     *  left unconstrained in the presolved problem, so a complete enumerator would mis-count; hence
     *  solution-set-sensitive (`preservesSolutionSet = false`), gated off under `-a` / `-n N>1`. */
    MERGE_DUPLICATE_COLUMNS(
        "dup-columns",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext): PassResult {
            val merge = Presolve.mergeDuplicateColumns(problem, ctx.objectiveIntVars)
            return PassResult(merge.problem, merge::reconstruct)
        }
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

    /** Dual fixing / dominated-variable reductions (#448) — pins a variable to a bound when the
     *  objective and constraint structure guarantee an optimum there. Solution-set altering, so
     *  auto-disabled for solution-set-sensitive queries. */
    DUAL_FIX("dual-fix", Stage.PROBLEM, PresolveTiming.MEDIUM, preservesSolutionSet = false, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            PassResult(Presolve.fixDominatedVariables(problem, ctx.objectiveIntCoeffs, ctx.objectiveBoolCoeffs))
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

    /** Probing to fixpoint: tentatively pin each free Boolean, propagate, and keep only the
     *  deductions that hold in every solution — failed literals (emitted as unit clauses) and
     *  common-bound tightenings. Solution-preserving, so it needs no objective-variable exclusion. */
    PROBE("probe", Stage.PROBLEM, PresolveTiming.EXHAUSTIVE, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            PassResult(Presolve.probe(problem, PROBE_PASS_MAX_CANDIDATES, Cancellation.Never))
    },

    /** Binary implication graph: harvest `lit -> lit` implications by probing-style pinning, collapse
     *  same-polarity equivalent literals (mutual-implication cycles) to one representative, and drop
     *  transitively-redundant binary clauses. Substitution leaves a merged variable unconstrained and
     *  rebuilds it on reconstruct, so — like affine elimination — it inflates a complete enumerator's
     *  count (#507) and is marked solution-set-sensitive. */
    IMPLICATION_GRAPH(
        "impl-graph",
        Stage.PROBLEM,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext): PassResult {
            val reduction = Presolve.reduceImplicationGraph(
                problem,
                IMPLICATION_GRAPH_MAX_CANDIDATES,
                Cancellation.Never,
                ctx.objectiveBoolVars,
            )
            return PassResult(reduction.problem, reduction::reconstruct)
        }
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

    /** Lookup by serializable [id] and the id listing for spec parsing / errors. */
    companion object {
        /** The pass whose [id] equals [id], or `null` if none matches. */
        fun fromId(id: String): PresolvePass? = entries.firstOrNull { it.id == id }

        /** Canonical pass ids joined for `--help` / error messages: `strengthen | affine | …`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}

/**
 * Preset effort levels, mirroring SCIP's presolving emphases / the Gurobi `Presolve 0/1/2` dial.
 * Each level is just a bundle: which [PresolveTiming] tiers run, and how many round-to-fixpoint
 * iterations the engine may take (1 = a single pass, no iteration). Per-pass overrides on
 * [PresolveConfig] sit on top of the level.
 */
enum class PresolveEmphasis(
    /** Canonical token shown in `--presolve` / `--help` and error messages. */
    val id: String,
    /** Cost tiers this level lets run. */
    val timings: Set<PresolveTiming>,
    /** Round-to-fixpoint cap (`0` = no presolve, `1` = a single non-iterating pass). */
    val maxRounds: Int,
    /** Per-var cap on bake-time SAC `propagate` calls — the EXHAUSTIVE-tier work budget. */
    val probeBudgetPerVar: Int,
    /** Total cap on bake-time SAC `propagate` calls across all vars. */
    val probeTotalBudget: Int,
    /** Accepted spellings besides [id]. */
    private val aliases: List<String> = emptyList(),
) {
    /** No presolve. */
    OFF("off", emptySet(), 0, CAPPED_PROBE_BUDGET_PER_VAR, CAPPED_PROBE_TOTAL_BUDGET, aliases = listOf("none")),

    /** Cheap FAST reductions only, applied once — no symmetry, no iteration. */
    CONSERVATIVE(
        "conservative",
        setOf(PresolveTiming.FAST),
        1,
        CAPPED_PROBE_BUDGET_PER_VAR,
        CAPPED_PROBE_TOTAL_BUDGET,
        aliases = listOf("fast"),
    ),

    /** FAST + MEDIUM (adds symmetry), iterated to a fixpoint. The shipped default. */
    DEFAULT(
        "default",
        setOf(PresolveTiming.FAST, PresolveTiming.MEDIUM),
        MAX_PRESOLVE_ROUNDS,
        CAPPED_PROBE_BUDGET_PER_VAR,
        CAPPED_PROBE_TOTAL_BUDGET,
        aliases = listOf("auto"),
    ),

    /** FAST + MEDIUM + EXHAUSTIVE (adds SAC probing), iterated to a fixpoint. */
    AGGRESSIVE(
        "aggressive",
        setOf(PresolveTiming.FAST, PresolveTiming.MEDIUM, PresolveTiming.EXHAUSTIVE),
        MAX_PRESOLVE_ROUNDS,
        AGGRESSIVE_PROBE_BUDGET_PER_VAR,
        AGGRESSIVE_PROBE_TOTAL_BUDGET,
    ),
    ;

    /** Token lookup and the id listing for spec parsing / help (single source of truth). */
    companion object {
        private val byToken: Map<String, PresolveEmphasis> =
            entries.flatMap { e -> (listOf(e.id) + e.aliases).map { it to e } }.toMap()

        /** `null`/blank/`default`/`auto` → [DEFAULT]; `off`/`none` → [OFF]; `conservative`/`fast` →
         *  [CONSERVATIVE]; `aggressive` → [AGGRESSIVE]; otherwise `null`. */
        fun fromId(id: String?): PresolveEmphasis? {
            val token = id?.trim()?.lowercase()
            return if (token.isNullOrEmpty()) DEFAULT else byToken[token]
        }

        /** Canonical ids joined for `--help` / error messages: `off | conservative | default | aggressive`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
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
    /** Per-var SAC probe budget; `null` follows the [emphasis] tier. The benchmarking knob. */
    private val probeBudgetPerVarOverride: Int? = null,
    /** Total SAC probe budget; `null` follows the [emphasis] tier. */
    private val probeTotalBudgetOverride: Int? = null,
) {

    /** Per-var cap on bake-time SAC `propagate` calls: an explicit override, else the [emphasis] tier. */
    fun probeBudgetPerVar(): Int = probeBudgetPerVarOverride ?: emphasis.probeBudgetPerVar

    /** Total cap on bake-time SAC `propagate` calls: an explicit override, else the [emphasis] tier. */
    fun probeTotalBudget(): Int = probeTotalBudgetOverride ?: emphasis.probeTotalBudget

    /** Whether [pass] runs under [context]: an explicit override wins, else the emphasis rule. */
    fun resolved(pass: PresolvePass, context: PresolveContext): Boolean = overrides[pass] ?: auto(pass, context)

    /** Emphasis rule: an auto-eligible pass whose tier the level enables, unless it would drop
     *  solutions on a solution-set-sensitive query, or it is symmetry breaking the model already
     *  does itself. */
    private fun auto(pass: PresolvePass, context: PresolveContext): Boolean = pass.autoEligible &&
        pass.timing in emphasis.timings &&
        (pass.preservesSolutionSet || !context.solutionSetSensitive) &&
        !(pass == PresolvePass.BREAK_SYMMETRIES && context.modelBreaksSymmetry)

    /** The [PresolvePass.Stage.PROBLEM] passes that run under [context], in enum (priority) order. */
    fun problemPasses(context: PresolveContext): List<PresolvePass> =
        PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM && resolved(it, context) }

    /** Force the solution-set-*collapsing* passes off (symmetry breaking, value precedence, dual
     *  fixing) — for a pure local-search engine, where the ordering constraints they add fight the
     *  search and it gains nothing from collapsing symmetric solutions. The cheap solution-preserving
     *  reductions (strengthening, substitution, probing) stay on; affine substitution counts as one
     *  here — it only *inflates* the count for a complete enumerator (#507), which LS never does, so
     *  it stays on and keeps shrinking the problem. */
    fun forLocalSearch(): PresolveConfig = PresolveConfig(
        emphasis,
        overrides + PresolvePass.entries
            .filter { !it.preservesSolutionSet && it != PresolvePass.ELIMINATE_AFFINE_SINGLETONS }
            .associateWith { false },
        probeBudgetPerVarOverride,
        probeTotalBudgetOverride,
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
         * Parse a `--presolve` / `klause.presolve` spec into an [emphasis] + per-pass [overrides]:
         *  - an emphasis level alone (`default` / `auto`, `off` / `none`, `conservative` / `fast`,
         *    `aggressive`);
         *  - an emphasis level followed by `+<pass>` / `-<pass>` deltas that force individual passes
         *    on/off on top of it — e.g. `default,-symmetry` (defaults but no symmetry breaking),
         *    `off,+symmetry` (no presolve except symmetry breaking). This is what enables/disables a
         *    single pass, which a bare list cannot;
         *  - `all` — every pass forced on;
         *  - a bare comma-separated pass-id list — each forced on, all others off (force-exactly).
         *
         * An unknown token throws.
         */
        fun parse(spec: String?): PresolveConfig {
            val s = spec?.trim()?.lowercase().orEmpty()
            if (s.isEmpty()) return AUTO
            if (s == "all") return PresolveConfig(overrides = PresolvePass.entries.associateWith { true })
            val tokens = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // Emphasis-base form: first token is a level, the rest are +/- deltas.
            PresolveEmphasis.fromId(tokens.first())?.let { base ->
                val overrides = HashMap<PresolvePass, Boolean>()
                for (tok in tokens.drop(1)) {
                    val on = when (tok.firstOrNull()) {
                        '+' -> true
                        '-' -> false
                        else -> error("after an emphasis, `$tok` must be +<pass> or -<pass>")
                    }
                    overrides[passOf(tok.substring(1))] = on
                }
                return PresolveConfig(base, overrides)
            }
            // Bare force-list: the named passes on, all others off.
            val on = tokens.map { passOf(it) }.toSet()
            return PresolveConfig(overrides = PresolvePass.entries.associateWith { it in on })
        }

        private fun passOf(id: String): PresolvePass =
            PresolvePass.fromId(id) ?: error("unknown presolve pass `$id`; expected ${PresolvePass.ids()}")
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
     *  reconstruct mapping its solutions back to the original. [cancellation] is polled between passes
     *  and rounds: a fired deadline returns the partial result so far — every pass is individually
     *  sound, so stopping early only forgoes further reduction, never correctness. */
    fun run(
        problem: Problem,
        config: PresolveConfig,
        context: PresolveContext = PresolveContext.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
    ): Presolved {
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
        var roundStartComplexity = complexity(current)
        while (round < maxRounds && !cancellation()) {
            var ranAny = false
            for (pass in passes) {
                if (cancellation()) break
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
            // Effectiveness-based abort (SCIP `abortfac`): a round that simplified the problem, but by
            // less than [PRESOLVE_ABORT_FRACTION] of it, isn't worth another full sweep. A round that
            // grew the problem (e.g. symmetry adding ordering constraints) or left it unchanged is left
            // to the fixpoint check above.
            val now = complexity(current)
            val reduced = roundStartComplexity - now
            if (reduced > 0 && reduced.toDouble() < PRESOLVE_ABORT_FRACTION * roundStartComplexity) break
            roundStartComplexity = now
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
