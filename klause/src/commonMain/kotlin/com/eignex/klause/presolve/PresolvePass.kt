package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.linear.AffineSingletons
import com.eignex.klause.presolve.linear.LinearSubSumAggregation
import com.eignex.klause.presolve.structural.RedundantConstraints
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeIncremental
import com.eignex.klause.presolve.structural.StructuralReduction
import com.eignex.klause.propagation.BakedProblem

private const val PROBE_PASS_MAX_CANDIDATES = 2_048
private const val IMPLICATION_GRAPH_MAX_CANDIDATES = 2_048

/**
 * The catalogue of presolve passes; each entry co-locates its metadata with the transform it runs.
 *
 * @property id serializable form for [PresolveConfig.parse] and the CLI `--presolve` flag.
 * @property stage where the pass runs; [Stage.CONSTRUCTION] and [Stage.EXTERNAL] passes are only read via
 *  [PresolveConfig.resolved].
 * @property capability the least input representation the pass needs.
 * @property timing cost tier; a [PresolveEmphasis] enables a set of tiers.
 * @property preservesSolutionSet whether the pass leaves the solution set and count exactly intact. A pass
 *  may instead collapse the set (symmetry breaking, dual fixing) or inflate the count by leaving an
 *  eliminated variable unconstrained (affine elimination); such passes are disabled for
 *  solution-set-sensitive queries, including explicit overrides.
 * @property autoEligible whether emphasis may enable it; opt-in passes need an explicit override.
 * @property skipAfterEmpty whether the round engine parks the pass after a completed miss until the host
 *  reports a changed relevant input revision.
 */
enum class PresolvePass(
    val id: String,
    val stage: Stage,
    internal val capability: Capability,
    val timing: PresolveTiming,
    val preservesSolutionSet: Boolean,
    val autoEligible: Boolean,
    val skipAfterEmpty: Boolean = false,
) {
    /** GCD + bounded-integer coefficient strengthening. */
    STRENGTHEN_COEFFICIENTS(
        "strengthen",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        true,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.strengthenSourceCoefficients(problem, ctx.cancellation)

        // The bounded-integer half reads how far each column can actually range, so it exists only once
        // a finite projection does; the gcd half above is all a declaration alone supports.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.strengthenCoefficients(problem, ctx.cancellation)
    },

    /** Modular bound tightening: `Σ aᵢxᵢ = b` confines each `xⱼ` to a residue class mod `gcd(aᵢ : i ≠ j)`. */
    REDUCE_DIOPHANTINE(
        "diophantine",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.reduceSourceDiophantine(problem, ctx.cancellation)

        // A finite domain can hold the residue class itself, so the finite lane carves the off-class values
        // a declared range alone can only shave off its ends.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.reduceDiophantine(problem, ctx.cancellation)
    },

    /** One-shot GF(2) elimination over xor factors and integer-equality parities, emitting forced units. */
    DERIVE_XOR_UNITS(
        "xor-units",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        true,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.deriveSourceXorUnits(problem, ctx.cancellation)
    },

    /**
     * Collapses linear rows on one coefficient vector whose bounds meet into an equality, or refutes when
     * they cross. Runs before [ELIMINATE_AFFINE_SINGLETONS] so its equalities are pivots the same round.
     */
    FUSE_LINEAR_BOUNDS(
        "fuse-bounds",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        true,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.fuseSourceLinearBounds(problem, ctx.cancellation)
    },

    /**
     * Affine singleton elimination. The eliminated variable is left unconstrained and rebuilt from its
     * partner, so a complete enumerator would over-count; hence not solution-set preserving.
     */
    ELIMINATE_AFFINE_SINGLETONS(
        "affine",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        // The source form states a bound row only for a side the model bounds and leaves the
        // residue-class doubletons alone, since restricting a partner to a residue class is a value set
        // a range cannot state. The finite form below keeps both.
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            AffineSingletons.eliminateSourceAffineSingletons(
                problem,
                ctx.objectiveIntVars,
                ctx.cancellation,
                pivotOrder = ctx.affinePivotOrder,
                objective = ctx.linearObjective,
                preserveStructure = ctx.preserveStructure,
            )

        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            AffineSingletons.eliminateAffineSingletons(
                problem,
                ctx.objectiveIntVars,
                ctx.cancellation,
                ctx.sharedIntOcc,
                ctx.affineUnderdetermined,
                ctx.affineTouchedVars,
                pivotOrder = ctx.affinePivotOrder,
                objective = ctx.linearObjective,
                preserveStructure = ctx.preserveStructure,
            )
    },

    /**
     * Folds a sub-sum that a unit-pivot equality defines as one variable back into every other row holding
     * it; the defining equality is retained. Runs before [REMOVE_REDUNDANT] so shrunk rows dedup.
     */
    AGGREGATE_SUB_SUMS(
        "aggregate",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.aggregateSourceSubSums(problem, ctx.cancellation)
    },

    /** Exact row subtraction to strictly fewer nonzero terms; opt-in since density alone does not predict cost. */
    SPARSIFY_LINEAR_ROWS(
        "sparsify",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = false,
        skipAfterEmpty = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.sparsifySourceLinearRows(problem, ctx.cancellation)
    },

    /** Drops duplicate factors and dominated linear inequalities, after the GCD-normalising passes. */
    REMOVE_REDUNDANT("subsume", Stage.PROBLEM, Capability.SOURCE, PresolveTiming.FAST, true, autoEligible = true) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            RedundantConstraints.removeRedundantSourceConstraints(
                problem, ctx.cancellation, ctx.subsumeIncremental as? RedundantConstraints.SubsumeMemo,
            )

        // The finite lane charges the same phases over root-propagated domains, which are narrower than
        // the source states, and carries the round engine's persistent phase-1/2 indices.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            RedundantConstraints.removeRedundantConstraints(
                problem,
                ColumnRanges.of(problem.rootIntDomainsInPlace),
                ctx.subsumeIncremental as? SubsumeIncremental,
                ctx.cancellation,
            )
    },

    /**
     * Drops equalities the other equalities imply and refutes a block with no rational solution, catching
     * dependence through three or more rows that pairwise fusion and subsumption miss.
     */
    DROP_DEPENDENT_EQUALITIES(
        "dependent-rows",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.dropSourceDependentEqualities(problem, ctx.cancellation)
    },

    /** Each factor rewrites itself into simpler factors when its structure pins it. Solution-set exact. */
    REDUCE_STRUCTURAL(
        "structural",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            StructuralReduction.reduceSource(problem, ctx.cancellation, ctx.preserveStructure)

        // Root-propagated domains are narrower than the declarations, so the finite lane hands every factor
        // the tighter view rather than offering it only to factors over closed columns.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            StructuralReduction.reduce(problem, ctx.cancellation, ctx.preserveStructure)
    },

    /** Bounded joint-tuple filtering of overlapping tables and table-to-MDD root composition. */
    COMPOSE_EXTENSIONAL(
        "extensional",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = false,
        skipAfterEmpty = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.composeExtensional(problem, ctx.cancellation).asSourceDelta()
    },

    /**
     * Folds a clause over sole-use single-variable reified-comparison indicators into one
     * [com.eignex.klause.factor.arithmetic.ComparisonClause]. Finite preparation keeps clauses over columns
     * binary-column substitution converts unless enabled explicitly; see [PresolveConfig.keepsBinaryComparisons].
     * The dropped indicators are left unconstrained, so not solution-set preserving.
     */
    FOLD_COMPARISON_CLAUSES(
        "comparison-clause",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) = Presolve.foldSourceComparisonClauses(
            problem, ctx.objectiveBoolVars, ctx.cancellation, ctx.keepBinaryComparisons, ctx.objectiveIntVars,
        )

        // Root propagation fixes columns the declarations leave free, and a fixed column is what lets a
        // multi-term row reduce to a single-variable comparison.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) = Presolve.foldComparisonClauses(
            problem, ctx.objectiveBoolVars, ctx.cancellation, ctx.keepBinaryComparisons, ctx.objectiveIntVars,
        )
    },

    /**
     * Folds two integer columns with coinciding coefficients into one aggregate, rebuilding the dropped
     * one from it. The dropped variable is left unconstrained, so not solution-set preserving.
     */
    MERGE_DUPLICATE_COLUMNS(
        "dup-columns",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.mergeSourceDuplicateColumns(problem, ctx.objectiveIntVars, ctx.cancellation)

        // A finite delta can widen the representative to the Minkowski sum, which a source range cannot.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.mergeDuplicateColumns(
                problem, ctx.objectiveIntVars, ctx.sharedIntOcc, ctx.dupColumnsTouchedVars, ctx.cancellation,
            )
    },

    /**
     * Fourier-Motzkin projection of a non-objective variable occurring in exactly one linear inequality,
     * rebuilt at its most permissive bound. Not solution-set preserving.
     */
    PROJECT_SINGLETON_INEQUALITIES(
        "singleton-column",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.FAST,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.projectSourceSingletonInequalities(problem, ctx.objectiveIntVars, ctx.cancellation)

        // Root-propagated domains can close a side the declaration left open, so the finite lane pins a
        // column the source form would have to rebuild from its row.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.projectSingletonInequalities(problem, ctx.objectiveIntVars, ctx.cancellation)
    },

    /** Verified finite variable symmetry preserving the exact current objective. */
    BREAK_OBJECTIVE_SYMMETRIES(
        "objective-symmetry",
        Stage.PROBLEM,
        Capability.FINITE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext): PassDelta =
            if (ctx.solutionSetSensitive || ctx.modelBreaksSymmetry) {
                PassDelta()
            } else if (SymmetryBreaking.supportsObjectiveSymmetries(problem, ctx)) {
                SymmetryBreaking.breakObjectiveSymmetries(problem, ctx)
            } else {
                Presolve.breakSymmetries(problem, ctx.objectiveIntVars, ctx.objectiveBoolVars, ctx.cancellation)
            }
    },

    /**
     * Interchangeable-variable, block and value symmetry breaking. On a source model only the breaks a
     * theory can own are posted; see [SymmetryBreaking.breakSourceSymmetries].
     */
    BREAK_SYMMETRIES(
        "symmetry",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = true,
        skipAfterEmpty = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) = Presolve.breakSourceSymmetries(
            problem,
            ctx.objectiveIntVars,
            ctx.objectiveBoolVars,
            ctx.cancellation,
        )

        // Root-propagated domains can make columns interchangeable that their declarations tell apart, and
        // once every column is finite the generator lex and the value-precedence chain have an owner.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) = Presolve.breakSymmetries(
            problem,
            ctx.objectiveIntVars,
            ctx.objectiveBoolVars,
            ctx.cancellation,
        )
    },

    /**
     * Value precedence (Y. C. Law and J. H. M. Lee, "Global Constraints for Integer and Set Value
     * Precedence", CP 2004, https://doi.org/10.1007/978-3-540-30201-8_28). Opt-in: each of this and
     * [BREAK_SYMMETRIES] disables the other's detection, so it is an alternative to the value pin.
     */
    VALUE_PRECEDENCE(
        "value-precede",
        Stage.PROBLEM,
        Capability.FINITE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = false,
    ) {
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.breakValuePrecedence(problem, ctx.objectiveIntVars, ctx.cancellation)
    },

    /**
     * Dual fixing: pins a variable to a bound when the objective and constraint structure guarantee an
     * optimum there. Not solution-set preserving.
     */
    DUAL_FIX(
        "dual-fix",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.fixDominatedSourceVariables(
                problem, ctx.objectiveIntCoeffs, ctx.objectiveBoolCoeffs, ctx.cancellation,
            )

        // The finite lane pins into root-propagated domains, which are narrower than the model states,
        // so it fixes columns the declaration alone leaves too wide.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.fixDominatedVariables(problem, ctx.objectiveIntCoeffs, ctx.objectiveBoolCoeffs, ctx.cancellation)
    },

    /** Strict-cost replacement across every unconditional ground Table reading a coordinate. */
    SUBSTITUTE_TABLE_VALUES(
        "table-substitution",
        Stage.PROBLEM,
        Capability.FINITE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = false,
        autoEligible = false,
        skipAfterEmpty = true,
    ) {
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            TableValueSubstitution.reduce(problem, ctx)
    },

    /** Construction-time failed-literal probing, run by [RootBaker]. */
    PROBE_FAILED_LITERALS(
        "probe-failed-literals",
        Stage.CONSTRUCTION,
        Capability.FINITE,
        PresolveTiming.EXHAUSTIVE,
        true,
        autoEligible = true,
    ),

    /** Construction-time bound SAC. */
    PROBE_INT_BOUNDS(
        "probe-int-bounds",
        Stage.CONSTRUCTION,
        Capability.FINITE,
        PresolveTiming.EXHAUSTIVE,
        true,
        autoEligible = true,
    ),

    /** Construction-time interior-hole SAC; implies [PROBE_INT_BOUNDS]. */
    PROBE_INT_HOLES(
        "probe-int-holes",
        Stage.CONSTRUCTION,
        Capability.FINITE,
        PresolveTiming.EXHAUSTIVE,
        true,
        autoEligible = true,
    ),

    /**
     * [PROBE]'s source form alone, run while some column is still open; the plan drops it wherever [PROBE]
     * runs.
     */
    PROBE_OPEN_RANGES(
        "probe-open",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        true,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext): SourceDelta {
            if (!namespaceEligible(problem)) return SourceDelta()
            return Presolve.probeSource(problem, PROBE_PASS_MAX_CANDIDATES, ctx.cancellation)
        }
    },

    /** Failed-literal and common-bound probing to fixpoint. Solution-preserving. */
    PROBE("probe", Stage.PROBLEM, Capability.SOURCE, PresolveTiming.EXHAUSTIVE, true, autoEligible = true) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.probeSource(problem, PROBE_PASS_MAX_CANDIDATES, ctx.cancellation)

        // The finite lane probes with the engine's own propagators over root domains, which see every
        // factor and the holes a range cannot state.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) =
            Presolve.probe(problem, PROBE_PASS_MAX_CANDIDATES, ctx.cancellation)
    },

    /**
     * Collapses mutual-implication cycles to one representative and drops transitively redundant binary
     * clauses. A merged variable is left unconstrained, so not solution-set preserving.
     */
    IMPLICATION_GRAPH(
        "impl-graph",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.reduceSourceImplicationGraph(problem, ctx.cancellation, ctx.objectiveBoolVars)

        // Pinning a literal and propagating needs finite domains, so only the finite lane harvests the
        // implications that longer clauses and other factor kinds carry; the source graph is the binaries.
        override fun applyFinite(problem: BakedProblem, ctx: PresolveContext) = Presolve.reduceImplicationGraph(
            problem,
            IMPLICATION_GRAPH_MAX_CANDIDATES,
            ctx.cancellation,
            ctx.objectiveBoolVars,
        )
    },

    /**
     * Bounded variable elimination over the pure-SAT part. The eliminated variable is left unconstrained,
     * so not solution-set preserving.
     */
    ELIMINATE_BOOL_VARS(
        "bve",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.eliminateSourceBoolVars(problem, ctx.objectiveBoolVars, ctx.cancellation)
    },

    /** Subsumption and self-subsuming resolution of original short clauses, preserving every assignment. */
    SUBSUME_CLAUSES(
        "clause-subsume",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = false,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            OriginalClauseSubsumption.simplify(problem, ctx.cancellation)
    },

    /** Blocked-clause elimination over the pure-SAT part. Satisfiability-preserving only. */
    ELIMINATE_BLOCKED_CLAUSES(
        "bce",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = false,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.eliminateSourceBlockedClauses(problem, ctx.objectiveBoolVars, ctx.cancellation)
    },

    /** Merges pairwise exclusions into maximal at-most-one cliques. Solution-set exact. */
    MERGE_AMO_CLIQUES(
        "amo-clique",
        Stage.PROBLEM,
        Capability.SOURCE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = true,
    ) {
        override fun applySource(problem: Problem, ctx: PresolveContext) =
            Presolve.mergeAmoCliques(problem, ctx.cancellation).asSourceDelta()
    },

    /**
     * Substitutes each `{0, 1}` integer column with a Boolean literal; see [BinaryColumnSubstitution]. Runs
     * ahead of the round engine, whose session state cannot grow the variable count. Solution-set exact.
     */
    SUBSTITUTE_BINARY_COLUMNS(
        "binary-columns",
        Stage.EXTERNAL,
        Capability.FINITE,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ),

    /** Replace exclusively owned Table partitions of plain AllDifferent by Boolean placement choices. */
    SUBSTITUTE_TUPLE_PACKING(
        "tuple-packing",
        Stage.EXTERNAL,
        Capability.FINITE,
        PresolveTiming.MEDIUM,
        preservesSolutionSet = true,
        autoEligible = false,
    ),

    /**
     * LP-relaxation harvest, run by [PresolvePipeline] between round-engine fixpoints. Exhaustive tier since
     * it solves an LP per probe; it skips a relaxation too large to probe within the search's budget.
     */
    LP_HARVEST(
        "lp-harvest",
        Stage.EXTERNAL,
        Capability.FINITE,
        PresolveTiming.EXHAUSTIVE,
        preservesSolutionSet = true,
        autoEligible = true,
    ),

    /**
     * Posts the reified difference rows as one joint difference-constraint system after the round fixpoint,
     * since its variable mentions would block affine elimination. Redundant with the rows it reads.
     */
    POST_DIFFERENCE_SYSTEM(
        "difference-system",
        Stage.EXTERNAL,
        Capability.FINITE,
        PresolveTiming.FAST,
        preservesSolutionSet = true,
        autoEligible = true,
    ),
    ;

    internal val tracksLinearInputs: Boolean
        get() = this == STRENGTHEN_COEFFICIENTS || this == SPARSIFY_LINEAR_ROWS

    internal fun namespaceEligible(problem: Problem, factorCount: Int = problem.factors.size): Boolean = when (this) {
        SUBSUME_CLAUSES -> OriginalClauseSubsumption.eligible(problem.numBoolVars, factorCount)
        PROBE_OPEN_RANGES -> problem.numRealVars > 0 || problem.intBounds.hasOpenSides
        AGGREGATE_SUB_SUMS -> problem.numIntVars >= 3
        SPARSIFY_LINEAR_ROWS -> problem.numIntVars > 0 && LinearSubSumAggregation.sparsificationEligible(factorCount)
        FOLD_COMPARISON_CLAUSES -> problem.numBoolVars > 0 && problem.numIntVars > 0
        DROP_DEPENDENT_EQUALITIES -> problem.numIntVars > 0 || problem.numRealVars > 0
        ELIMINATE_BOOL_VARS, ELIMINATE_BLOCKED_CLAUSES, IMPLICATION_GRAPH,
        PROBE_FAILED_LITERALS, -> problem.numBoolVars > 0
        ELIMINATE_AFFINE_SINGLETONS, REDUCE_DIOPHANTINE, PROJECT_SINGLETON_INEQUALITIES,
        FUSE_LINEAR_BOUNDS,
        VALUE_PRECEDENCE, PROBE_INT_BOUNDS, PROBE_INT_HOLES, SUBSTITUTE_BINARY_COLUMNS, -> problem.numIntVars > 0
        MERGE_DUPLICATE_COLUMNS -> problem.numIntVars >= 2
        else -> true
    }

    /** Semantic and multiplicity guarantees under a protected or exactly substituted objective. */
    val guarantees: TransformationGuarantees
        get() = when {
            preservesSolutionSet -> TransformationGuarantees.IDENTITY
            this == ELIMINATE_AFFINE_SINGLETONS || this == IMPLICATION_GRAPH || this == FOLD_COMPARISON_CLAUSES ->
                TransformationGuarantees(true, true, true, false, true)
            else -> TransformationGuarantees(true, true, true, false, false)
        }

    // Defined only for [Stage.PROBLEM] [Capability.SOURCE] passes: the result cannot name a finite domain or
    // lift a sample, so such a pass may run before any finite projection exists.
    internal open fun applySource(problem: Problem, ctx: PresolveContext): SourceDelta =
        error("$name has no source form: it is a $stage pass needing $capability")

    // A [Capability.SOURCE] pass runs its source form here unless it overrides. An empty delta signals the
    // pass found nothing to do.
    internal open fun applyFinite(problem: BakedProblem, ctx: PresolveContext): PassDelta = when {
        stage != Stage.PROBLEM -> error(
            "PresolvePass.applyFinite is defined only for Stage.PROBLEM passes; $name is a $stage pass",
        )

        capability == Capability.SOURCE -> applySource(problem, ctx).asPassDelta(problem.rootIntDomainsInPlace)

        else -> error("$name is a Stage.PROBLEM pass needing $capability but defines no finite form")
    }

    /** Where a pass runs. */
    enum class Stage {
        /** Run by [RootBaker] during the root bake. */
        CONSTRUCTION,

        /** A problem-to-problem transform run before solving, via [Presolver.run]. */
        PROBLEM,

        /** Run by [PresolvePipeline] around the round engine; the enum only carries the config toggle. */
        EXTERNAL,
    }

    internal enum class Capability {
        // Declared bounds and factors only; safe before a finite projection exists.
        SOURCE,

        // Finite domains or root-propagation state.
        FINITE,
        ;

        fun supports(required: Capability): Boolean = this == FINITE || required == SOURCE
    }

    /** Lookup by serializable [id] and the id listing for spec parsing / errors. */
    companion object {
        /** The pass whose [id] equals [id], or `null` if none matches. */
        fun fromId(id: String): PresolvePass? = entries.firstOrNull { it.id == id }

        /** Canonical pass ids joined for `--help` / error messages: `strengthen | affine | …`. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}
