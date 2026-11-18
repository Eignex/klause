package com.eignex.klause

import com.eignex.klause.arithmetic.json.ExactRealJson
import com.eignex.klause.arithmetic.quadratic.QuadraticCandidates
import com.eignex.klause.arithmetic.quadratic.QuadraticModel
import com.eignex.klause.arithmetic.real.compareExactly
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.skema.SchemaDef
import com.eignex.util.numbers.BigFraction

internal object SolverTestRuntime {
    private val startup = lazy {
        initializeArithmetic()
        initializeSchema()
        initializeSearch()
    }

    val isInitialized: Boolean get() = startup.isInitialized()

    fun initialize() {
        startup.value
    }

    private fun initializeArithmetic() {
        val model = QuadraticModel(listOf(BigFraction.ZERO), listOf(listOf(BigFraction.ONE)))
        check(QuadraticCandidates().proposeActiveRows(model, listOf(0.0), iterations = 1) != null)
        val value = ExactRealJson.decodeFromString(
            """{"add":[{"root":{"coefficients":["-2","0","1"],"index":2}},{"pi":{}}]}""",
        )
        val decoded = ExactRealJson.decodeFromJsonElement(ExactRealJson.encodeToJsonElement(value))
        check(value.compareExactly(decoded) == 0)
    }

    private fun initializeSchema() {
        val x = FloatHandle("x", -1.0, 1.0)
        val y = FloatHandle("y", 0.0, 1.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-1.0, 1.0),
            "y" to FloatSpec(0.0, 1.0),
            "square" to NamedConstraint((x * x) eq y),
        )).compile()
        check(compiled.problem.pipelineRoute(compiled.minimize(y)) is SourceProblemRoute.PolynomialTheory)
    }

    private fun initializeSearch() {
        val problem = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        check(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0)) is SolveResult.Sat)
    }
}
