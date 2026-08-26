package com.eignex.klause.presolve

import com.eignex.klause.presolve.structural.RedundantConstraints
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeIncremental
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Cancellation

private const val PROBE_PASS_MAX_CANDIDATES = 2_048
private const val IMPLICATION_GRAPH_MAX_CANDIDATES = 2_048

/**
 * The catalogue of presolve passes. Each entry co-locates its metadata with [apply], so adding or
 * toggling a pass is a single self-contained place. Metadata:
 *
 * @property id serializable form for [PresolveConfig.parse] and the CLI `--presolve` flag.
 * @property stage [Stage.PROBLEM] passes are run by the [Presolver] round engine; [Stage.CONSTRUCTION]
 *  passes (SAC probing) are folded into `Problem.baked` at build time; [Stage.EXTERNAL] passes (the LP
 *  harvest) run in the CLI's presolve↔harvest fixpoint — both are only read via [PresolveConfig.resolved].
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
 * @property skipAfterEmpty whether the round engine stops re-running this pass once it has run and
 *  changed nothing. The engine re-enables every pass when another pass changes the problem; for an
 *  expensive search that overwhelmingly finds nothing to add (symmetry detection, xor unit
 *  derivation) repeating it after each reduction is wasted work, so it runs at most until its first
 *  empty result. Cheap reductions leave it `false` — they must re-run as the problem shrinks.
 */
enum class PresolvePass(
    val id: String,
    val stage: Stage,
    val timing: PresolveTiming,
    val preservesSolutionSet: Boolean,
    val autoEligible: Boolean,
    val skipAfterEmpty: Boolean = false,
) {
    /** GCD + bounded-integer coefficient strengthening. */
    STRENGTHEN_COEFFICIENTS("strengthen", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.strengthenCoefficients(problem, ctx.cancellation)
    },

    /** Per-variable modular (Diophantine) bound tightening for integer equalities: `Σ aᵢxᵢ = b` confines
     *  each `xⱼ` to a residue class mod `gcd(aᵢ : i ≠ j)`, moving its bounds inward. Solution-set exact. */
    REDUCE_DIOPHANTINE(
        "diophantine",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.reduceDiophantine(problem)
    },

    /** One-shot GF(2) elimination over all xor factors: emit implied root unit clauses. */
    DERIVE_XOR_UNITS(
        "xor-units",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        true,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.deriveXorUnits(problem)
    },

    /** Cross-direction linear bound fusion — over the linear rows on one coefficient vector, an upper
     *  and lower bound that meet (`l = u`) collapse into an equality (which affine elimination then
     *  pivots on), and one that crosses (`l > u`) proves infeasibility. Solution-set exact; runs before
     *  [ELIMINATE_AFFINE_SINGLETONS] so the equalities it mints are available the same round. */
    FUSE_LINEAR_BOUNDS("fuse-bounds", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.fuseLinearBounds(problem)
    },

    /** Affine singleton elimination — reconstructs the eliminated variable. The eliminated
     *  variable is left unconstrained in the presolved problem (its value is rebuilt from its partner
     *  on the way back), so a complete enumerator would branch over its domain and over-count each
     *  real solution. Hence solution-set-sensitive (`preservesSolutionSet = false`): gated off
     *  under `-a` / `-n N>1`, while solve/optimize still benefit. */
    ELIMINATE_AFFINE_SINGLETONS(
        "affine",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.eliminateAffineSingletons(
            problem,
            ctx.objectiveIntVars,
            ctx.cancellation,
            ctx.sharedIntOcc,
            ctx.affineUnderdetermined,
            ctx.affineTouchedVars,
            ctx.affinePivotOrder,
        )
    },

    /** Common linear sub-sum extraction — the contract-direction mirror of [ELIMINATE_AFFINE_SINGLETONS].
     *  A sub-sum a unit-pivot equality defines as a single variable is folded back into every other row
     *  that contains it, collapsing the partner terms into one variable. Solution-set exact (the defining
     *  equality is retained, no variable eliminated). Runs before [REMOVE_REDUNDANT] so shrunk rows dedup. */
    AGGREGATE_SUB_SUMS(
        "aggregate",
        Stage.PROBLEM,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.aggregateSubSums(problem)
    },

    /** Constraint subsumption / redundant-constraint removal — drops duplicate factors and
     *  dominated linear inequalities. Runs after the simplifying passes so proportional rows are
     *  already GCD-normalised. */
    REMOVE_REDUNDANT("subsume", Stage.PROBLEM, PresolveTiming.FAST, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) = RedundantConstraints.removeRedundantConstraints(
            problem,
            ctx.subsumeIncremental as? SubsumeIncremental,
            ctx.cancellation,
        )
    },

    /** Per-factor structural self-reduction — each factor rewrites itself into simpler / lower-arity
     *  factors when its structure pins it (e.g. an Element with a fixed index becomes a plain equality),
     *  removing the global. Solution-set exact, so it stays on for solution-set-sensitive queries. */
    REDUCE_STRUCTURAL(
        "structural",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.reduceStructural(problem)
    },

    /** Fold a reified comparison disjunction — a clause over sole-use single-variable reified-comparison
     *  indicators — into one [com.eignex.klause.factor.arithmetic.ComparisonClause], dropping the
     *  indicator auxiliaries. The XCSP3 front-end emits the clause directly; this catches the reified
     *  form other front-ends produce. Not solution-set preserving (the dropped indicators are left
     *  unconstrained, like affine elimination), so it is gated off under `-a` / `-n N>1`. */
    FOLD_COMPARISON_CLAUSES(
        "comparison-clause",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.foldComparisonClauses(problem)
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
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.mergeDuplicateColumns(problem, ctx.objectiveIntVars, ctx.sharedIntOcc, ctx.dupColumnsTouchedVars)
    },

    /** Fourier-Motzkin projection of a variable occurring in exactly one linear inequality (and not the
     *  objective): eliminate it, rewriting the inequality over the remaining terms, and rebuild it at its
     *  most-permissive bound on reconstruct. Solution-set altering (the pinned rebuild collapses the
     *  variable's feasible range), so gated off for solution-set-sensitive queries. */
    PROJECT_SINGLETON_INEQUALITIES(
        "singleton-column",
        Stage.PROBLEM,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.projectSingletonInequalities(problem, ctx.objectiveIntVars)
    },

    /** Interchangeable-variable / block / value symmetry breaking. */
    BREAK_SYMMETRIES(
        "symmetry",
        Stage.PROBLEM,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.breakSymmetries(
            problem,
            ctx.objectiveIntVars,
            ctx.objectiveBoolVars,
            ctx.cancellation,
        )
    },

    /** Law–Lee value precedence — the strong value-symmetry break. Opt-in: stacking it
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
            Presolve.breakValuePrecedence(problem, ctx.objectiveIntVars)
    },

    /** Dual fixing / dominated-variable reductions — pins a variable to a bound when the
     *  objective and constraint structure guarantee an optimum there. Solution-set altering, so
     *  auto-disabled for solution-set-sensitive queries. */
    DUAL_FIX("dual-fix", Stage.PROBLEM, PresolveTiming.MEDIUM, preservesSolutionSet = false, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.fixDominatedVariables(problem, ctx.objectiveIntCoeffs, ctx.objectiveBoolCoeffs)
    },

    /** Construction-time failed-literal SAC: folded into `Problem.baked` at build, read via
     *  [PresolveConfig.resolved] — a [Stage.CONSTRUCTION] pass with no engine [apply]. */
    PROBE_FAILED_LITERALS(
        "probe-failed-literals",
        Stage.CONSTRUCTION,
        PresolveTiming.EXHAUSTIVE,
        true,
        autoEligible = true,
    ),

    /** Construction-time bound SAC. */
    PROBE_INT_BOUNDS("probe-int-bounds", Stage.CONSTRUCTION, PresolveTiming.EXHAUSTIVE, true, autoEligible = true),

    /** Construction-time interior-hole SAC; implies [PROBE_INT_BOUNDS]. */
    PROBE_INT_HOLES("probe-int-holes", Stage.CONSTRUCTION, PresolveTiming.EXHAUSTIVE, true, autoEligible = true),

    /** Probing to fixpoint: tentatively pin each free Boolean, propagate, and keep only the
     *  deductions that hold in every solution — failed literals (emitted as unit clauses) and
     *  common-bound tightenings. Solution-preserving, so it needs no objective-variable exclusion. */
    PROBE("probe", Stage.PROBLEM, PresolveTiming.EXHAUSTIVE, true, autoEligible = true) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.probe(problem, PROBE_PASS_MAX_CANDIDATES, Cancellation.Never)
    },

    /** Binary implication graph: harvest `lit -> lit` implications by probing-style pinning, collapse
     *  same-polarity equivalent literals (mutual-implication cycles) to one representative, and drop
     *  transitively-redundant binary clauses. Substitution leaves a merged variable unconstrained and
     *  rebuilds it on reconstruct, so — like affine elimination — it inflates a complete enumerator's
     *  count and is marked solution-set-sensitive. */
    IMPLICATION_GRAPH(
        "impl-graph",
        Stage.PROBLEM,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.reduceImplicationGraph(
            problem,
            IMPLICATION_GRAPH_MAX_CANDIDATES,
            Cancellation.Never,
            ctx.objectiveBoolVars,
        )
    },

    /** Bounded variable elimination over the pure-SAT part: resolve out a Boolean variable that
     *  occurs only in all-Boolean clauses when doing so does not grow the clause count. The eliminated
     *  variable is left unconstrained and rebuilt on reconstruct, so — like implication-graph merges —
     *  it inflates a complete enumerator's count and is solution-set-sensitive. */
    ELIMINATE_BOOL_VARS(
        "bve",
        Stage.PROBLEM,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.eliminateBoolVars(problem, ctx.objectiveBoolVars, ctx.cancellation)
    },

    /** Blocked-clause elimination over the pure-SAT part: drop a clause blocked on an eligible
     *  literal (every opposite clause clashes elsewhere). The clause is satisfiability-redundant and
     *  rebuilt on reconstruct, so like BVE it is solution-set-sensitive. */
    ELIMINATE_BLOCKED_CLAUSES(
        "bce",
        Stage.PROBLEM,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) =
            Presolve.eliminateBlockedClauses(problem, ctx.objectiveBoolVars, ctx.cancellation)
    },

    /** At-most-one clique merging: grow the pairwise exclusion constraints into maximal cliques and
     *  materialise each as one `Cardinality(max = 1)`, dropping the binary clauses / smaller at-most-ones
     *  it subsumes. Solution-set exact (the clique at-most-one equals the pairwise exclusions). */
    MERGE_AMO_CLIQUES(
        "amo-clique",
        Stage.PROBLEM,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun apply(problem: Problem, ctx: PresolveContext) = Presolve.mergeAmoCliques(problem, ctx.cancellation)
    },

    /** Substitute an integer column whose domain is exactly `{0, 1}` for a fresh Boolean literal, rewriting
     *  the rows over such columns into [com.eignex.klause.factor.bool.Clause] /
     *  [com.eignex.klause.factor.bool.Cardinality] / [com.eignex.klause.factor.bool.PseudoBoolean] so they
     *  reach the pseudo-Boolean lane (division-based conflict learning, at-most-one clique merging,
     *  coefficient strengthening over literals). Run outside this enum's round engine — it mints variables,
     *  which the incremental session's persistent state, sized once to the input's variable count, cannot
     *  express — and ahead of it, so every round pass reads the model in the lane it will be solved in.
     *  Solution-set exact: the substituted column is pinned and its value carried by its literal, so
     *  solutions map one-to-one. See [BinaryColumnSubstitution]. */
    SUBSTITUTE_BINARY_COLUMNS(
        "binary-columns",
        Stage.EXTERNAL,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ),

    /** LP-relaxation harvest: fold the LP's proven domain tightenings, redundant-row removals, root
     *  infeasibility and implied equalities into the problem. Run outside this enum's round engine (it
     *  needs the backtrack-layer LP engine, which `solver/presolve` may not depend on), so it has no
     *  engine [apply] and the work lives in the CLI's presolve↔harvest fixpoint, gated on this entry.
     *  `EXHAUSTIVE` (it does an LP solve per shave/redundancy probe), so the aggressive level turns it on.
     *  The harvest itself then self-limits on the *built relaxation's* size (cols/rows/nnz): on a large
     *  relaxation, where the per-candidate cost would lose instances the search would otherwise solve, it
     *  no-ops — its gains are on small/medium models. */
    LP_HARVEST(
        "lp-harvest",
        Stage.EXTERNAL,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = true,
        autoEligible = true,
    ),

    /** Post the model's reified difference rows as one joint difference-constraint system, so the search
     *  can refute a row's aux from a negative cycle the asserted rows already carry. Run outside this
     *  enum's round engine: the system mentions the variables it reads, which would keep them alive
     *  against affine elimination, so it is appended once the round fixpoint is reached and can no longer
     *  block a pass. Redundant with the rows it reads, which stay posted. */
    POST_DIFFERENCE_SYSTEM(
        "difference-system",
        Stage.EXTERNAL,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ),
    ;

    /** Transform [problem] under [ctx], returning the change as a [PassDelta] against [problem]'s factor
     *  list. An empty delta ([PassDelta.isEmpty]) signals the pass found nothing to do this round. Defined
     *  only for [Stage.PROBLEM] passes — the round engine runs no other stage. [Stage.CONSTRUCTION]
     *  (bake-time SAC) and [Stage.EXTERNAL] (LP harvest) passes are eligibility markers whose work runs
     *  elsewhere, so calling this on one is a programming error. */
    open fun apply(problem: Problem, ctx: PresolveContext): PassDelta =
        error("PresolvePass.apply is defined only for Stage.PROBLEM passes; $name is a $stage pass")

    /** Where a pass runs. */
    enum class Stage {
        /** Folded into `Problem.baked` at construction (SAC probing). */
        CONSTRUCTION,

        /** A problem-to-problem transform run before solving, via [Presolver.run]. */
        PROBLEM,

        /** Run outside [Presolver.run] — by the CLI's presolve↔LP-harvest fixpoint, which alone bridges
         *  to the backtrack-layer LP engine; this enum only carries the config toggle. */
        EXTERNAL,
    }

    /** Lookup by serializable [id] and the id listing for spec parsing / errors. */
    companion object {
        /** The pass whose [id] equals [id], or `null` if none matches. */
        fun fromId(id: String): PresolvePass? = entries.firstOrNull { it.id == id }

        /** Canonical pass ids joined for `--help` / error messages: `strengthen | affine | …`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}
