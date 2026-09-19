package com.eignex.klause.lp.engine

import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.sourceDescendingDirection
import com.eignex.klause.lp.sourceDoubleBoundedSplit
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.objective.maximizeInt
import com.eignex.klause.solver.objective.minimizeInt
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.parseFlatZincExecution
import com.eignex.klause.theory.qflra.QfLraSystem
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal object OrderingReuseInvestigation {
    @JvmStatic
    fun main(args: Array<String>) {
        val kind = args[0]
        val path = Path.of(args[1])
        val rounds = if (args.getOrNull(2) == "--metadata-only") 0..0 else -1..2
        for (round in rounds) {
            for (enabled in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                val id = "${path.fileName}/$round/$enabled"
                if (kind.startsWith("smt")) source(id, path, enabled) else scoped(id, path, kind, enabled)
            }
        }
    }

    private class Observation(val enabled: Boolean) : LpCertificationObserver {
        val basis = ArrayList<Map<String, Any?>>()
        val owners = ArrayList<KotlinBasisSolver>()
        val solvers = ArrayList<RevisedSimplex>()
        var created = 0
        var closed = 0
        var role = "first"
        override fun observe(certifier: LpCertifier, success: Boolean) = Unit
        override fun observeExactInput(accepted: Boolean) = Unit
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        override fun observeBasisVerification(metrics: ExactBasisMetrics) = recordBasis(metrics, "direct")
        private fun recordBasis(metrics: ExactBasisMetrics, via: String) {
            basis += metric(metrics) + mapOf(
                "role" to role,
                "ownerUpdatesAtObservation" to owners.map { it.updateCount },
            )
        }

        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val solver = RevisedSimplex(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy,
                    basisSolverFactory = {
                        val owner = KotlinBasisSolver(it).also(owners::add)
                        object : BasisSolver by owner {
                            override fun close() {
                                owners.remove(owner)
                                owner.close()
                            }
                        }
                    },
                    pricing = pricing, reuseRationalOrder = enabled,
                )
                created++
                solvers += solver
                return object : PersistentLpSolver by solver {
                    private var disposed = false
                    override fun close() {
                        if (!disposed) {
                            disposed = true
                            try {
                                solver.close()
                            } finally {
                                closed++
                            }
                        }
                    }
                }
            }
        }
        val context = LpSolveContext(
            engineFactory = factory,
            onRefinementBasisVerification = { recordBasis(it, "refinement") },
        )
    }

    private fun scoped(id: String, path: Path, kind: String, enabled: Boolean) {
        val observation = Observation(enabled)
        val results = ArrayList<CertifiedLpResult?>()
        val durations = ArrayList<Long>()
        val states = ArrayList<List<Int>>()
        var preparation = 0L
        var floatWork = 0L
        val start = System.nanoTime()
        val token = Cancellation { System.nanoTime() - start >= 10_000_000_000L }
        val text = Files.readString(path)
        val model = if (kind == "mps") {
            val compiled = Mps.parse(text).toProblem()
            CpToLpRelaxation(compiled.model, compiled.objective?.toLinearObjective())
                .build(PropagationSession(compiled.model)).model
        } else {
            val program = parseFlatZincExecution(text).program
            val objective = when (val solve = program.solve) {
                SolveDirective.Satisfy -> null
                is SolveDirective.Minimize -> program.problem.minimizeInt(program.intVarsByName.getValue(solve.objVar))
                is SolveDirective.Maximize -> program.problem.maximizeInt(program.intVarsByName.getValue(solve.objVar))
            }
            CpToLpRelaxation(program.problem, objective).build(PropagationSession(program.problem)).model
        }
        val exact = model.authoritativeModel()
        var ownerMetrics: LpScopedMetrics? = null
        if (exact != null) {
            val scoped = LpScopedSolver(
                LpExactState(exact),
                token,
                observation.context,
                iterationLimit = 1024,
                workLimit = 2_000_000L,
                pricing = LpPricingOptions(tieSeed = 606),
            )
            scoped.use {
                repeat(2) { call ->
                    observation.role = if (call == 0) "first" else "repeat"
                    val solveStart = System.nanoTime()
                    results += scoped.solve(token = token, observer = observation)
                    durations += System.nanoTime() - solveStart
                    states += observation.owners.map { it.updateCount }
                    floatWork += scoped.lastMetrics.workOps
                }
                preparation = scoped.metrics.preparationWork
            }
            ownerMetrics = scoped.metrics
        }
        val nanos = System.nanoTime() - start
        check(observation.created == observation.closed)
        emit(
            mapOf(
                "id" to id, "role" to "consumer", "route" to "scoped-$kind", "nanos" to nanos,
                "decline" to if (exact == null) "NO_RELAXATION" else null,
                "callNanos" to durations, "ownerUpdatesAtReturn" to states,
                "preparationWork" to preparation, "floatWork" to floatWork,
                "created" to observation.created, "closed" to observation.closed,
                "preparationAttempts" to ownerMetrics?.preparationAttempts,
                "preparationSuccesses" to ownerMetrics?.preparationSuccesses,
                "basis" to observation.basis, "results" to results.map(::result),
                "authority" to authority(model),
            ),
        )
        if (exact != null) diagnostic(id, exact, enabled)
    }

    private fun source(id: String, path: Path, enabled: Boolean) {
        val observation = Observation(enabled)
        val start = System.nanoTime()
        val token = Cancellation { System.nanoTime() - start >= 10_000_000_000L }
        val parsed = SmtLib.parse(Files.readString(path))
        if (parsed.model.numBoolVars != 0 || parsed.model.factors.any { it.linearForm is LinearForm.Disjunction }) {
            emit(
                mapOf(
                "id" to id,
                "role" to "consumer",
                "route" to "source",
                "decline" to "BOOLEAN_OR_DISJUNCTION",
                "nanos" to System.nanoTime() - start,
            )
            )
            return
        }
        val source = QfLraSystem(parsed.model).build { null }
        val rows = source.sourceRows.map { it.inequality }.toMutableList()
        source.sourceColumns.forEachIndexed { j, column ->
            column.lower?.let { rows += exactColumnLower(j, it.value + column.origin.value) }
            column.upper?.let { rows += exactColumnUpper(j, it.value + column.origin.value) }
        }
        val variables = source.sourceColumns.size
        val budget = SourceLpBudget(
            solveContext = { observation.context },
            onBasisVerification = observation::observeBasisVerification,
        )
        val results = ArrayList<CertifiedLpResult?>()
        val durations = ArrayList<Long>()
        val states = ArrayList<List<Int>>()
        SourceLp(rows, variables, budget).use { lp ->
            for (role in listOf("first", "repeat", "activity")) {
                observation.role = role
                val solveStart = System.nanoTime()
                results += if (role == "activity" && rows.isNotEmpty()) {
                    lp.solve(token, activity = 0)
                } else {
                    lp.solve(token)
                }
                durations += System.nanoTime() - solveStart
                states += observation.owners.map { it.updateCount }
            }
        }
        val nanos = System.nanoTime() - start
        check(observation.created == observation.closed)
        emit(
            mapOf(
                "id" to id, "role" to "consumer", "route" to "source", "nanos" to nanos,
                "callNanos" to durations, "ownerUpdatesAtReturn" to states,
                "rows" to rows.map(::row), "variables" to variables,
                "realNames" to parsed.realVarNames, "intNames" to parsed.intVarNames,
                "results" to results.map(::result), "basis" to observation.basis,
                "created" to observation.created, "closed" to observation.closed, "budget" to budget(budget),
            ),
        )
        for (helper in listOf("split", "direction")) {
            observation.role = helper
            val priorMetrics = observation.basis.size
            val helperStart = System.nanoTime()
            val helperToken = Cancellation { System.nanoTime() - helperStart >= 10_000_000_000L }
            val helperBudget = SourceLpBudget(
                solveContext = { observation.context },
                onBasisVerification = observation::observeBasisVerification,
            )
            val value = if (helper == "split") {
                when (val split = sourceDoubleBoundedSplit(rows, variables, helperBudget, helperToken)) {
                    ExactDoubleBoundedSplit.Unknown -> mapOf("status" to "UNKNOWN")

                    ExactDoubleBoundedSplit.Infeasible -> mapOf("status" to "INFEASIBLE")

                    is ExactDoubleBoundedSplit.Split -> mapOf(
                        "status" to "SPLIT",
                        "bounded" to split.bounded.map { mapOf("row" to it.index, "lower" to f(it.lower)) },
                        "unbounded" to split.unbounded,
                    )
                }
            } else {
                mapOf(
                    "descending" to rows.firstOrNull()?.let {
                    sourceDescendingDirection(rows, it, variables, helperToken, helperBudget)
                }
                )
            }
            val helperNanos = System.nanoTime() - helperStart
            check(observation.created == observation.closed)
            emit(
                mapOf(
                "id" to id,
                "role" to helper,
                "nanos" to helperNanos,
                "result" to value,
                "budget" to budget(helperBudget),
                "basis" to observation.basis.drop(priorMetrics),
                "created" to observation.created,
                "closed" to observation.closed,
            )
            )
        }
    }

    private fun diagnostic(id: String, exact: ExactLpModel, enabled: Boolean) {
        val model = LpExactState(exact).toWorkingModel() ?: return
        val start = System.nanoTime()
        val token = Cancellation { System.nanoTime() - start >= 10_000_000_000L }
        var owner: KotlinBasisSolver? = null
        RevisedSimplex(
            model,
            token,
            iterationLimit = 1024,
            workLimit = 2_000_000L,
            basisSolverFactory = { KotlinBasisSolver(it).also { value -> owner = value } },
            pricing = LpPricingOptions(tieSeed = 606),
            reuseRationalOrder = enabled,
        ).use { solver ->
            val candidate = solver.solve()
            val basis = candidate?.basis ?: solver.infeasibleBasis
            if (basis == null) {
                emit(mapOf("id" to id, "role" to "forced", "decline" to "NO_BASIS"))
                return
            }
            val updates = owner?.updateCount
            val exportStart = System.nanoTime()
            val exported = owner?.ordering()
            val exportNanos = System.nanoTime() - exportStart
            repeat(2) { call ->
                val verifyStart = System.nanoTime()
                val verified = verifyExactBasis(
                    model,
                    basis,
                    if (candidate == null) solver.infeasibleRow else null,
                    solver.exactBasisCache,
                    token,
                )
                val verifyNanos = System.nanoTime() - verifyStart
                emit(
                    mapOf(
                        "id" to id, "role" to if (call == 0) "forced" else "forced-cache", "nanos" to verifyNanos,
                        "ownerUpdates" to updates, "exportNanos" to exportNanos, "exported" to (exported != null),
                        "basis" to listOf(metric(verified.metrics)), "authority" to authority(model),
                        "result" to mapOf(
                            "primal" to verified.witness?.primal?.map(::f),
                            "objective" to verified.witness?.objective?.let(::f),
                            "bound" to verified.bound?.value?.let(::f),
                            "conflictRows" to verified.conflict?.rows?.toList(),
                            "multipliers" to verified.conflict?.multipliers?.map(::f),
                            "complementary" to verified.complementary,
                        ),
                    ),
                )
            }
        }
    }

    private fun budget(value: SourceLpBudget) = mapOf(
        "operations" to value.operations,
        "reservedWork" to value.reservedWork,
        "reservedAllocation" to value.reservedAllocation,
        "activeNanos" to value.activeNanos,
        "preparationWork" to value.measuredPreparationWork,
        "floatWork" to value.measuredFloatWork,
        "continuationWork" to value.measuredContinuationWork,
        "refinementWork" to value.measuredRefinementWork,
    )

    private fun metric(value: ExactBasisMetrics): Map<String, Any?> = mapOf(
        "eligible" to value.eligible, "factoryCalls" to value.factoryCalls, "builds" to value.builds,
        "reuse" to value.reuse, "solves" to value.solves, "restarts" to value.restarts,
        "checks" to value.verificationChecks, "work" to value.work, "allocation" to value.allocation,
        "orderOffers" to value.orderOffers, "orderProposals" to value.orderProposals,
        "orderAttempts" to value.orderAttempts, "orderFallbacks" to value.orderFallbacks,
        "orderDecline" to value.orderDecline?.name, "decline" to value.decline?.name,
        "phase" to value.phase.name,
        "operations" to value.operations.associate {
            it.phase.name to mapOf("work" to it.work, "allocation" to it.allocation)
        },
    )

    private fun result(value: CertifiedLpResult?): Map<String, Any?>? = value?.let {
        mapOf(
            "verdict" to it.verdict.name, "primal" to it.exactPrimal?.map(::f),
            "objective" to it.witness?.objective?.let(::f), "bound" to it.lowerBound?.let(::f),
            "conflictRows" to it.rationalConflict?.rows?.toList(),
            "multipliers" to it.rationalConflict?.multipliers?.map(::f),
            "farkas" to it.farkasRay?.toList(), "direction" to it.unboundedness?.direction?.map(::f),
            "continuationWork" to it.continuation?.work, "refinementWork" to it.refinement?.work,
            "refinementLuFactories" to it.refinement?.luFactories, "refinementLuBuilds" to it.refinement?.luBuilds,
            "refinementLuReuse" to it.refinement?.luReuse, "refinementLuSolves" to it.refinement?.luSolves,
            "refinementLuWork" to it.refinement?.luWork,
        )
    }

    private fun row(value: ExactRationalInequality) = mapOf(
        "columns" to value.columns.toList(),
        "coefficients" to value.coefficients.map(::f),
        "rhs" to f(value.rhs),
        "strict" to value.strict,
    )

    private fun authority(model: LpModel): Map<String, Any?> {
        val matrix = List(model.m) { MutableList(model.numVars) { BigFraction.ZERO } }
        for (j in 0 until model.numVars) model.forEachRationalColumn(j) { i, v -> matrix[i][j] = v }
        val bounds = List(model.numVars) { model.exactBounds(it) }
        return mapOf(
            "n" to model.n, "m" to model.m, "matrix" to matrix.map { it.map(::f) },
            "rhs" to List(model.m) { f(model.exactRhs(it)) }, "costs" to List(model.numVars) { f(model.exactCost(it)) },
            "origins" to List(model.n) { f(model.exactShift(it)) }, "constant" to f(model.exactConstant()),
            "scale" to f(model.exactState?.model?.objective?.scale?.value ?: BigFraction.ONE),
            "external" to f(model.exactState?.model?.objective?.externalConstant?.value ?: BigFraction.ZERO),
            "lower" to bounds.map { it.lower?.number?.value?.let(::f) },
            "upper" to bounds.map { it.upper?.number?.value?.let(::f) },
            "lowerStrict" to bounds.map { it.lower?.strict == true },
            "upperStrict" to bounds.map { it.upper?.strict == true },
        )
    }

    private fun f(value: BigFraction): String = "${value.num}/${value.den}"
    private fun emit(value: Map<String, Any?>) = println(json(value))
    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonArray(value.map(::json))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
