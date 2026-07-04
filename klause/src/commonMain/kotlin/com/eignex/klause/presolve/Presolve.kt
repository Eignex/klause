package com.eignex.klause.presolve

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem

/**
 * Problem-level presolve transforms. Each takes a [Problem] and returns an equivalent one with
 * a smaller / tighter formulation. Pure (no solving); the caller decides when to apply them.
 *
 * The passes live in focused per-technique units in this package; this object is the stable
 * entry surface the presolve pipeline and tests call.
 */
object Presolve {

    /** GCD coefficient strengthening for [com.eignex.klause.factor.arithmetic.Linear] and
     *  pseudo-Boolean constraints. See [CoefficientStrengthening]. */
    fun strengthenCoefficients(problem: Problem): PassDelta = CoefficientStrengthening.strengthenCoefficients(problem)

    /** One-shot GF(2) elimination over all xor factors. See [XorUnits]. */
    fun deriveXorUnits(problem: Problem): PassDelta = XorUnits.deriveXorUnits(problem)

    /** Affine variable elimination. See [AffineSingletons]. [incrementalTouchedVars] (from the incremental
     *  round engine) restricts a re-run's candidate scan to the variables the delta changed. */
    fun eliminateAffineSingletons(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
        sharedIntOcc: SharedIntOccurrence? = null,
        capWide: Boolean = false,
        incrementalTouchedVars: IntArray? = null,
    ): PassDelta = AffineSingletons.eliminateAffineSingletons(
        problem,
        objectiveIntVars,
        cancellation,
        sharedIntOcc,
        capWide,
        incrementalTouchedVars,
    )

    /** Constraint subsumption / redundant-constraint removal. See [RedundantConstraints]. */
    fun removeRedundantConstraints(problem: Problem): PassDelta =
        RedundantConstraints.removeRedundantConstraints(problem)

    /** Per-factor structural self-reduction via [com.eignex.klause.solver.Factor.structuralReduce].
     *  See [StructuralReduction]. */
    fun reduceStructural(problem: Problem): PassDelta = StructuralReduction.reduce(problem)

    /** Maximal at-most-one cliques (Lit-encoded, at most one satisfied) recognised from [problem]'s
     *  factors — including those implied by pseudo-Boolean knapsacks — and grown into maximal cliques,
     *  for clique-aware consumers such as local search. See [PresolveShared]. */
    fun amoCliques(problem: Problem): List<Set<Int>> = PresolveShared.maximalAmoCliques(problem.factors.asList())

    /** Duplicate / parallel integer-column aggregation. See [DuplicateColumns]. */
    fun mergeDuplicateColumns(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        sharedIntOcc: SharedIntOccurrence? = null,
    ): PassDelta = DuplicateColumns.mergeDuplicateColumns(problem, objectiveIntVars, sharedIntOcc)

    /** Symmetry breaking by detecting interchangeable variables. See [SymmetryBreaking]. */
    fun breakSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta = SymmetryBreaking.breakSymmetries(problem, objectiveIntVars, objectiveBoolVars, cancellation)

    /** Value-precedence breaking over interchangeable value orbits. See [SymmetryBreaking]. */
    fun breakValuePrecedence(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): PassDelta =
        SymmetryBreaking.breakValuePrecedence(problem, objectiveIntVars)

    /** Dual fixing / dominated-variable reductions. See [DominatedVariables]. */
    fun fixDominatedVariables(
        problem: Problem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    ): PassDelta = DominatedVariables.fixDominatedVariables(problem, objectiveIntCoeffs, objectiveBoolCoeffs)

    /** Failed-literal and common-bound probing to fixpoint. See [Probing]. */
    fun probe(problem: Problem, maxCandidates: Int, cancellation: Cancellation = Cancellation.Never): PassDelta =
        Probing.probe(problem, maxCandidates, cancellation)

    /** Binary implication graph: equivalent-literal substitution and transitive reduction. See
     *  [ImplicationGraph]. */
    fun reduceImplicationGraph(
        problem: Problem,
        maxCandidates: Int,
        cancellation: Cancellation = Cancellation.Never,
        objectiveBoolVars: Set<Int> = emptySet(),
    ): PassDelta = ImplicationGraph.reduce(problem, maxCandidates, cancellation, objectiveBoolVars)

    /** Binary-implication graph (literal-indexed adjacency `lit -> forced lits`) for implication-aware
     *  consumers such as local search. See [ImplicationGraph]. */
    fun implicationGraph(
        problem: Problem,
        maxCandidates: Int,
        cancellation: Cancellation = Cancellation.Never,
    ): Array<IntArray> = ImplicationGraph.implicationGraph(problem, maxCandidates, cancellation)

    internal fun refineColoursForTest(problem: Problem): Pair<IntArray, IntArray> =
        SymmetryBreaking.refineColoursForTest(problem)
}
