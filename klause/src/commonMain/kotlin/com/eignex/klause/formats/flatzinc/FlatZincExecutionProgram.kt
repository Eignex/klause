package com.eignex.klause.formats.flatzinc

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_FLOAT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_FLOAT_LO
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.minimizeBool
import com.eignex.klause.solver.objective.minimizeInt
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** Compatibility request for native local-search views of one retained FlatZinc source program. */
data class FlatZincExecutionProgram(
    /** Engine-neutral lowered program. */
    val program: FlatZincProgram,
    /** Local-search objective when its defining cone can be represented exactly. */
    val localSearchObjective: IncrementalObjective?,
    /** Definitions to maintain while evaluating local-search moves. */
    val definitionalSweep: DefinitionalSweep?,
)

/** Build the minimize-oriented objective for a FlatZinc solve directive. */
fun FlatZincProgram.linearObjective(): LinearObjective? {
    val (name, maximize) = when (val directive = solve) {
        is SolveDirective.Minimize -> directive.objVar to false
        is SolveDirective.Maximize -> directive.objVar to true
        SolveDirective.Satisfy -> return null
    }
    val objective = floatVarsByName[name]?.takeIf { it.lpOnly }?.let { real ->
        LinearObjective(realCoefficients = DoubleArray(problem.numRealVars).also { it[real.varId] = 1.0 })
    } ?: boolVarsByName[name]?.let { problem.minimizeBool(it) }
        ?: problem.minimizeInt(intVarsByName.getValue(name))
    return if (maximize) objective.negated() else objective
}

/** Parse one source program and request its native local-search views. */
fun parseFlatZincExecution(
    source: String,
    floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
    forLocalSearch: Boolean = false,
    unboundedIntLo: Long? = null,
    unboundedIntHi: Long? = null,
    unboundedFloatLo: Double = DEFAULT_UNBOUNDED_FLOAT_LO,
    unboundedFloatHi: Double = DEFAULT_UNBOUNDED_FLOAT_HI,
    exactFloats: Boolean = false,
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
): FlatZincExecutionProgram = parseFlatZincExecution(
    StringCharSource(source),
    floatBuckets,
    floatScale,
    forLocalSearch,
    unboundedIntLo,
    unboundedIntHi,
    unboundedFloatLo,
    unboundedFloatHi,
    exactFloats,
    settings,
    semanticOverrides,
)

/** Parse a streamed source program and request its native local-search views. */
fun parseFlatZincExecution(
    source: CharSource,
    floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
    forLocalSearch: Boolean = false,
    unboundedIntLo: Long? = null,
    unboundedIntHi: Long? = null,
    unboundedFloatLo: Double = DEFAULT_UNBOUNDED_FLOAT_LO,
    unboundedFloatHi: Double = DEFAULT_UNBOUNDED_FLOAT_HI,
    exactFloats: Boolean = false,
    settings: ProblemSettings = KlauseConfig.current.problemSettings(),
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
): FlatZincExecutionProgram {
    val program = parseFlatZinc(
        source,
        floatBuckets,
        floatScale,
        forLocalSearch,
        unboundedIntLo,
        unboundedIntHi,
        unboundedFloatLo,
        unboundedFloatHi,
        exactFloats,
        settings,
        semanticOverrides,
    )
    val sweep = DefinitionalSweep.fromSource(program.problem, program.definitionHints)
    val objective = program.linearObjective()?.let { sweep?.functionalObjective(it) }
    return FlatZincExecutionProgram(program, objective, sweep)
}
