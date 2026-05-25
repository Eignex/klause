package com.eignex.klause.compile

import com.eignex.klause.ast.SearchAnnotation
import com.eignex.klause.ast.ValSearchStrategy
import com.eignex.klause.ast.VarSearchStrategy
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.IndomainMax
import com.eignex.klause.solver.backtrack.IndomainMiddle
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.IndomainRandom
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.backtrack.LargestDomain
import com.eignex.klause.solver.backtrack.RandomVariable
import com.eignex.klause.solver.backtrack.SmallestDomain
import com.eignex.klause.solver.backtrack.ValueHeuristic
import com.eignex.klause.solver.backtrack.VariableHeuristic

/**
 * Translate a schema-level [SearchAnnotation] into the corresponding [BacktrackParams].
 * The enum cases mirror the MiniZinc-named strategies the FlatZinc importer already
 * recognises (`FlatZincCompiler.mapVariableStrategy` / `mapValueStrategy`), so MiniZinc
 * users and Kotlin schema users get matched semantics.
 */
internal fun searchAnnotationToParams(ann: SearchAnnotation): BacktrackParams =
    BacktrackParams(
        variableHeuristic = mapVarStrategy(ann.variableStrategy),
        valueHeuristic = mapValStrategy(ann.valueStrategy),
        phaseSaving = ann.phaseSaving,
        lubyRestartBase = ann.lubyRestartBase,
        maxDecisions = ann.maxDecisions,
    )

private fun mapVarStrategy(s: VarSearchStrategy): VariableHeuristic = when (s) {
    VarSearchStrategy.Default -> RandomVariable
    VarSearchStrategy.InputOrder -> InputOrder
    VarSearchStrategy.SmallestDomain -> SmallestDomain
    VarSearchStrategy.LargestDomain -> LargestDomain
    VarSearchStrategy.Random -> RandomVariable
}

private fun mapValStrategy(s: ValSearchStrategy): ValueHeuristic = when (s) {
    ValSearchStrategy.Default -> IndomainRandom
    ValSearchStrategy.Min -> IndomainMin
    ValSearchStrategy.Max -> IndomainMax
    ValSearchStrategy.Middle -> IndomainMiddle
    ValSearchStrategy.Random -> IndomainRandom
}
