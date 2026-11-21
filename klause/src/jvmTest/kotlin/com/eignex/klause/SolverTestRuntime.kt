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
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.PbOp
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteNumericalCompanions
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PortfolioInvocationResources
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.finiteNumericalCompanionEligibility
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
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
        val dimension = 33
        val model = QuadraticModel(
            List(dimension) { BigFraction.ZERO },
            List(dimension) { row ->
                List(dimension) { column -> if (row == column) BigFraction.ONE else BigFraction.ZERO }
            },
        )
        check(QuadraticCandidates().proposeActiveRows(model, List(dimension) { 0.0 }, iterations = 1) != null)
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
        val mixed = Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray(),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val plan = mixed.prepareExecutionPlan(objective, preferFinite = true)
        val theory = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1)
        val cost = finiteNumericalCompanionEligibility(mixed, PortfolioScenario.sequential(Kind.COP),
            objective, theory).requirements.construction
        val companions = requireNotNull(FiniteNumericalCompanions.prepare(mixed, objective, theory,
            ExecutionControl.Never, cost, incumbentCompletionCheck = { _, _, _ -> true }).companions)
        companions.incumbents.exchange.offer(Sample(booleanArrayOf(), longArrayOf(1, 1), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO)), BigFraction.ofLong(2))
        PortfolioInvocationResources(2, 1, 0, ExecutionControl.Never, null).use { resources ->
            companions.factories(plan, 0).last().materialize(plan, resources.slots[1]).use { worker ->
                requireNotNull(worker.newResumableSearch { 2.0 }).use { search ->
                    search.runSlice(ExecutionControl.Never, 1_000, 5_000) { }
                }
            }
        }
    }

    private fun initializeSmt() {
        val source = SmtLib.parse(
            "(declare-const x Int) (declare-fun f (Int) Int) " +
                "(assert (= x 1)) (assert (= (f x) 2))",
        ).model
        check(BacktrackSolver.source(source).solve() is OpenTheoryResult.Sat)
        val wide = SmtLib.parse(
            "(declare-const x Int) (assert (= x 9223372036854775808))",
        ).model
        check(BacktrackSolver.source(wide).solve() is OpenTheoryResult.Sat)
    }
}
