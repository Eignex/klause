package com.eignex.klause.solver.presolve

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

    /** GCD coefficient strengthening for [com.eignex.klause.solver.factor.arithmetic.Linear] and
     *  pseudo-Boolean constraints. See [CoefficientStrengthening]. */
    fun strengthenCoefficients(problem: Problem): Problem = CoefficientStrengthening.strengthenCoefficients(problem)

    /** One-shot GF(2) elimination over all xor factors. See [XorUnits]. */
    fun deriveXorUnits(problem: Problem): Problem = XorUnits.deriveXorUnits(problem)

    /** Affine variable elimination. See [AffineSingletons]. */
    fun eliminateAffineSingletons(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): AffineElimination =
        AffineSingletons.eliminateAffineSingletons(problem, objectiveIntVars)

    /** Constraint subsumption / redundant-constraint removal. See [RedundantConstraints]. */
    fun removeRedundantConstraints(problem: Problem): Problem = RedundantConstraints.removeRedundantConstraints(problem)

    /** At-most-one cliques (Lit-encoded, at most one satisfied) recognised from [problem]'s factors,
     *  for clique-aware consumers such as local search. See [PresolveShared]. */
    fun amoCliques(problem: Problem): List<Set<Int>> = PresolveShared.amoCliques(problem.factors.asList())

    /** Duplicate / parallel integer-column aggregation. See [DuplicateColumns]. */
    fun mergeDuplicateColumns(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): DuplicateColumnMerge =
        DuplicateColumns.mergeDuplicateColumns(problem, objectiveIntVars)

    /** Symmetry breaking by detecting interchangeable variables. See [SymmetryBreaking]. */
    fun breakSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
    ): Problem = SymmetryBreaking.breakSymmetries(problem, objectiveIntVars, objectiveBoolVars)

    /** Value-precedence breaking over interchangeable value orbits. See [SymmetryBreaking]. */
    fun breakValuePrecedence(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): Problem =
        SymmetryBreaking.breakValuePrecedence(problem, objectiveIntVars)

    /** Dual fixing / dominated-variable reductions. See [DominatedVariables]. */
    fun fixDominatedVariables(
        problem: Problem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    ): Problem = DominatedVariables.fixDominatedVariables(problem, objectiveIntCoeffs, objectiveBoolCoeffs)

    /** Failed-literal and common-bound probing to fixpoint. See [Probing]. */
    fun probe(problem: Problem, maxCandidates: Int, cancellation: Cancellation = Cancellation.Never): Problem =
        Probing.probe(problem, maxCandidates, cancellation)

    /** Binary implication graph: equivalent-literal substitution and transitive reduction. See
     *  [ImplicationGraph]. */
    fun reduceImplicationGraph(
        problem: Problem,
        maxCandidates: Int,
        cancellation: Cancellation = Cancellation.Never,
        objectiveBoolVars: Set<Int> = emptySet(),
    ): ImplicationReduction = ImplicationGraph.reduce(problem, maxCandidates, cancellation, objectiveBoolVars)

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
