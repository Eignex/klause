package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.minimizeBool
import com.eignex.klause.solver.objective.minimizeInt

/** Build the minimize-oriented objective for a FlatZinc solve directive. */
fun FlatZincProgram.linearObjective(): LinearObjective? {
    val (name, maximize) = when (val directive = solve) {
        is SolveDirective.Minimize -> directive.objVar to false
        is SolveDirective.Maximize -> directive.objVar to true
        SolveDirective.Satisfy -> return null
    }
    val objective = floatVarsByName[name]?.let { real ->
        LinearObjective(realCoefficients = DoubleArray(problem.numRealVars).also { it[real.varId] = 1.0 })
    } ?: boolVarsByName[name]?.let { problem.minimizeBool(it) }
        ?: problem.minimizeInt(intVarsByName.getValue(name))
    return if (maximize) objective.negated() else objective
}
