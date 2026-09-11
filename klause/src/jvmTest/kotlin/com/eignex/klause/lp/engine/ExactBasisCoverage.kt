package com.eignex.klause.lp.engine

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.toExactLpModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.theory.qflra.QfLraSystem
import com.eignex.klause.util.Cancellation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ExactBasisCoverage {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.single())
        val inputs = listOf(
            "mps/blend-tiny.mps", "mps/feasible-tiny.mps", "mps/infeasible-tiny.mps", "mps/float-tiny.mps",
            "smtlib/lia-basic.smt2", "smtlib/lia-unsat.smt2", "smtlib/lia-wide-span.smt2",
            "boundary/49-diagonal", "boundary/129-diagonal",
        )
        for (input in inputs) {
            val source = if (input.startsWith("boundary/")) "" else Files.readString(
                root.resolve("klause-bench/smoke-corpus/$input"),
            )
            val model = if (input.startsWith("boundary/")) {
                val rows = if (input == "boundary/49-diagonal") 49 else 129
                LpBuilder().apply {
                    repeat(rows) { j ->
                        addVar(0L, 1L)
                        addRow(intArrayOf(j), longArrayOf(3L), Relation.EQ, 1L)
                    }
                }.build(Sense.MINIMIZE)
            } else if (input.endsWith(".mps")) {
                val compiled = Mps.parse(source).toProblem()
                CpToLpRelaxation(compiled.model, compiled.objective?.toLinearObjective())
                    .build(PropagationSession(compiled.model)).model
            } else {
                val parsed = SmtLib.parse(source)
                if (parsed.model.numBoolVars != 0) {
                    println(buildJsonObject { put("input", input); put("ineligible", "BOOLEAN_BRANCH") })
                    continue
                }
                val exact = QfLraSystem(parsed.model).build { null }.toExactLpModel()
                LpExactState(exact).toWorkingModel()
            }
            if (model == null) {
                println(buildJsonObject { put("input", input); put("ineligible", "SOURCE_PROJECTION") })
                continue
            }
            val started = System.nanoTime()
            val token = Cancellation { System.nanoTime() - started >= 10_000_000_000L }
            RevisedSimplex(model, token, iterationLimit = 4096, workLimit = 50_000_000L).use { solver ->
                val candidate = solver.solve()
                val basis = candidate?.basis ?: solver.infeasibleBasis
                val row = if (candidate == null) solver.infeasibleRow else null
                var role = "live"
                val observer = object : LpCertificationObserver {
                    override fun observe(certifier: LpCertifier, success: Boolean) = Unit
                    override fun observeExactInput(accepted: Boolean) = Unit
                    override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
                    override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                        println(record(input, role, metrics))
                    }
                }
                val certified = certifyLpResult(model, solver, candidate, token, observer)
                certified.witness?.let { checkPoint(model, it) }
                certified.rationalConflict?.let { checkConflict(model, it) }
                certified.farkasRay?.let { check(sourceFarkasValid(model, it)) }
                println(buildJsonObject {
                    put("input", input)
                    put("role", "live-verdict")
                    put("verdict", certified.verdict.name)
                    put("rows", model.m)
                    put("columns", model.numVars)
                    put("pivots", solver.lastPivots)
                    put("floatWork", solver.lastWorkOps)
                    put("reconstructionWork", certified.reconstruction?.work ?: 0L)
                    put("reconstructionAllocation", certified.reconstruction?.allocation ?: 0L)
                })
                if (basis == null) {
                    println(buildJsonObject { put("input", input); put("ineligible", "NO_CANDIDATE_BASIS") })
                } else {
                    println(authority(input, model, basis))
                    role = "offered"
                    val exact = verifyExactBasis(model, basis, row, solver.exactBasisCache, token, observer = observer)
                    exact.witness?.let { checkPoint(model, it) }
                    exact.conflict?.let { checkConflict(model, it) }
                    println(strength(input, role, exact))
                    if (exact.metrics.decline == null || exact.witness != null || exact.conflict != null) {
                        role = "reuse"
                        val repeated = verifyExactBasis(
                            model, basis, row, solver.exactBasisCache, token, observer = observer,
                        )
                        repeated.witness?.let { checkPoint(model, it) }
                        repeated.conflict?.let { checkConflict(model, it) }
                        check(exact.witness?.primal == repeated.witness?.primal)
                        check(exact.bound?.value == repeated.bound?.value)
                        println(strength(input, role, repeated))
                    }
                }
            }
        }
    }

    private fun record(input: String, role: String, metrics: ExactBasisMetrics): JsonObject = buildJsonObject {
        put("input", input)
        put("role", role)
        put("eligible", metrics.eligible)
        put("factoryCalls", metrics.factoryCalls)
        put("builds", metrics.builds)
        put("reuse", metrics.reuse)
        put("solves", metrics.solves)
        put("restarts", metrics.restarts)
        put("checks", metrics.verificationChecks)
        put("work", metrics.work)
        put("allocation", metrics.allocation)
        put("maxBits", metrics.maxBits)
        put("peakFill", metrics.peakFill)
        put("decline", metrics.decline?.name ?: "NONE")
        put("phase", metrics.phase.name)
        put("operations", buildJsonObject {
            for (operation in metrics.operations) put(operation.phase.name, buildJsonObject {
                put("work", operation.work)
                put("allocation", operation.allocation)
            })
        })
    }

    private fun strength(input: String, role: String, exact: ExactBasisVerification): JsonObject = buildJsonObject {
        put("input", input)
        put("role", "$role-strength")
        put("witness", exact.witness != null)
        exact.witness?.let {
            put("primal", fractions(it.primal))
            put("objective", fraction(it.objective))
        }
        exact.conflict?.let {
            put("conflictRows", JsonArray(it.rows.map(::JsonPrimitive)))
            put("multipliers", fractions(it.multipliers))
        }
        put("bound", exact.bound?.value?.let(::fraction) ?: "NONE")
        put("conflict", exact.conflict != null)
        put("complementary", exact.complementary)
    }

    private fun fraction(value: BigFraction): String = "${value.num}/${value.den}"

    private fun fractions(values: List<BigFraction>): JsonArray = JsonArray(values.map { JsonPrimitive(fraction(it)) })

    private fun authority(input: String, model: LpModel, basis: Basis): JsonObject = buildJsonObject {
        put("input", input)
        put("role", "authority")
        put("n", model.n)
        put("m", model.m)
        val matrix = List(model.m) { MutableList(model.numVars) { BigFraction.ZERO } }
        for (j in 0 until model.numVars) model.forEachRationalColumn(j) { row, value -> matrix[row][j] = value }
        put("matrix", JsonArray(matrix.map(::fractions)))
        put("rhs", fractions(List(model.m) { model.exactRhs(it) }))
        put("costs", fractions(List(model.numVars) { model.exactCost(it) }))
        put("origins", fractions(List(model.n) { model.exactShift(it) }))
        put("constant", fraction(model.exactConstant()))
        put("scale", fraction(model.exactState?.model?.objective?.scale?.value ?: BigFraction.ONE))
        put("external", fraction(model.exactState?.model?.objective?.externalConstant?.value ?: BigFraction.ZERO))
        val bounds = List(model.numVars) { model.exactBounds(it) }
        put("lower", JsonArray(bounds.map {
            it.lower?.let { side -> JsonPrimitive(fraction(side.number.value)) } ?: JsonNull
        }))
        put("upper", JsonArray(bounds.map {
            it.upper?.let { side -> JsonPrimitive(fraction(side.number.value)) } ?: JsonNull
        }))
        put("lowerStrict", JsonArray(bounds.map { JsonPrimitive(it.lower?.strict == true) }))
        put("upperStrict", JsonArray(bounds.map { JsonPrimitive(it.upper?.strict == true) }))
        put("headings", JsonArray(basis.basicVars.map(::JsonPrimitive)))
        put("statuses", JsonArray(basis.status.map { JsonPrimitive(it.name) }))
    }

    private fun checkPoint(model: LpModel, witness: ExactLpWitness) {
        val x = MutableList(model.numVars) { BigFraction.ZERO }
        for (j in 0 until model.n) x[j] = witness.primal[j] - model.exactShift(j)
        for (i in 0 until model.m) {
            var value = model.exactRhs(i)
            for (j in 0 until model.n) {
                model.forEachRationalColumn(j) { row, a -> if (row == i) value -= a * x[j] }
            }
            x[model.n + i] = value
        }
        for (j in x.indices) {
            val bounds = model.exactBounds(j)
            bounds.lower?.let { check(x[j] > it.number.value || (x[j] == it.number.value && !it.strict)) }
            bounds.upper?.let { check(x[j] < it.number.value || (x[j] == it.number.value && !it.strict)) }
        }
        var objective = model.exactConstant()
        for (j in x.indices) objective += model.exactCost(j) * x[j]
        val source = model.exactState?.model?.objective
        if (source != null) objective = objective * source.scale.value.reciprocal() + source.externalConstant.value
        check(objective == witness.objective)
    }

    private fun checkConflict(model: LpModel, conflict: BigRationalConflict) {
        val y = MutableList(model.m) { BigFraction.ZERO }
        for (i in conflict.rows.indices) y[conflict.rows[i]] = conflict.multipliers[i]
        var rhs = BigFraction.ZERO
        for (i in y.indices) rhs += y[i] * model.exactRhs(i)
        var lower = BigFraction.ZERO
        var strict = false
        for (j in 0 until model.numVars) {
            var coefficient = BigFraction.ZERO
            model.forEachRationalColumn(j) { row, value -> coefficient += y[row] * value }
            if (coefficient.isZero) continue
            val bounds = model.exactBounds(j)
            val side = requireNotNull(if (coefficient.signum() < 0) bounds.upper else bounds.lower)
            lower += coefficient * side.number.value
            strict = strict || side.strict
        }
        check(rhs < lower || (rhs == lower && strict))
    }
}
