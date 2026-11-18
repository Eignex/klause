package com.eignex.klause

import com.eignex.klause.arithmetic.json.ExactRealJson
import com.eignex.klause.arithmetic.quadratic.QuadraticCandidates
import com.eignex.klause.arithmetic.quadratic.QuadraticModel
import com.eignex.klause.arithmetic.real.compareExactly
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.PbOp
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.skema.SchemaDef
import com.eignex.util.numbers.BigFraction

internal object SolverTestRuntime {
    private val startup = lazy {
        initializeArithmetic()
        initializeSchema()
        initializeSearch()
        initializeLp()
        initializeSmt()
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
        val pb = Problem(2, 0, emptyArray(), arrayOf(
            PseudoBoolean(longArrayOf(2, 1), intArrayOf(0, 2), PbOp.GE, 1),
        ))
        val params = BacktrackParams(randomSeed = 0, pbLearning = true)
        check(BacktrackSolver(pb.bake()).solve(params) is SolveResult.Sat)
        check(BacktrackSolver(pb.bake()).minimize(
            LinearObjective(boolWeights = longArrayOf(-1, 1)), params,
        ) is MinimizeResult.Optimal)
    }

    private fun initializeLp() {
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.GE, 1),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val params = BacktrackParams(randomSeed = 0, lpConfig = LpConfig.AGGRESSIVE)
        check(BacktrackSolver(problem.bake()).solve(params) is SolveResult.Sat)
        check(BacktrackSolver(problem.bake()).minimize(
            LinearObjective(realCoefficients = doubleArrayOf(1.0)), params,
        ) is MinimizeResult.Optimal)
    }

    private fun initializeSmt() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-fun f (Int) Int) " +
                "(assert (= x 1)) (assert (= (f x) 2))",
        ).model
        check(BacktrackSolver.source(source).solve() is OpenTheoryResult.Sat)
    }
}
