package com.eignex.klause.lp.engine

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.toExactLpModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStatus
import com.eignex.klause.simplex.exact.ExactContinuation
import com.eignex.klause.simplex.exact.ExactContinuationInput
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.theory.qflra.QfLraSystem
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object ExactContinuationCoverage {
    @JvmStatic
    fun main(args: Array<String>) {
        verifyForeignStateRecovery()
        `higher effort finds a conflict after prior feasibility pivots`()
        val root = Path.of(args.single())
        val inputs = listOf(
            "mps/blend-tiny.mps", "mps/feasible-tiny.mps", "mps/infeasible-tiny.mps", "mps/float-tiny.mps",
            "smtlib/lra-rational.smt2", "smtlib/lia-basic.smt2", "smtlib/lia-unsat.smt2", "smtlib/lia-wide-span.smt2",
            "boundary/25-feasible", "boundary/25-infeasible", "boundary/129-dimension",
        )
        for (input in inputs) {
            val model = if (input.startsWith("boundary/")) {
                val n = if (input.contains("129")) 129 else 25
                val extra = if (input.endsWith("infeasible")) 1 else 0
                val zero = ExactLpNumber.of(0L)
                val one = ExactLpNumber.of(1L)
                val m = n + extra
                val exact = ExactLpModel(
                    List(n) { j ->
                        listOf(ExactLpEntry(j, ExactLpNumber.of(-1L))) +
                            if (extra == 1) listOf(ExactLpEntry(n, one)) else emptyList()
                    },
                    List(n) { ExactLpNumber.of(-1L) } +
                        if (extra == 1) listOf(ExactLpNumber.of((n - 1).toLong())) else emptyList(),
                    List(n) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L)))) } +
                        List(m) { ExactLpColumn(ExactLpBounds(lower = ExactLpSide(zero))) },
                    List(m) { ExactLpRow() },
                    ExactLpObjective(List(n + m) { zero }),
                )
                LpExactState(exact).toWorkingModel()
            } else {
                val source = Files.readString(root.resolve("klause-bench/smoke-corpus/$input"))
                if (input.endsWith(".mps")) {
                    val compiled = Mps.parse(source).toProblem()
                    CpToLpRelaxation(compiled.model, compiled.objective?.toLinearObjective())
                        .build(PropagationSession(compiled.model)).model
                } else {
                    val parsed = SmtLib.parse(source)
                    if (parsed.model.numBoolVars != 0) {
                        println(
                            buildJsonObject {
                                put("input", input)
                                put("ineligible", "BOOLEAN_BRANCH")
                            },
                        )
                        continue
                    }
                    LpExactState(QfLraSystem(parsed.model).build { null }.toExactLpModel()).toWorkingModel()
                }
            }
            if (model == null) {
                println(
                    buildJsonObject {
                        put("input", input)
                        put("ineligible", "SOURCE_PROJECTION")
                    },
                )
                continue
            }
            val start = System.nanoTime()
            val token = Cancellation { System.nanoTime() - start >= 10_000_000_000L }
            RevisedSimplex(
                model,
                token,
                iterationLimit = 4096,
                workLimit = if (input.startsWith("boundary/")) 1L else 50_000_000L,
            ).use { solver ->
                val candidate = solver.solve()
                val target = solver.continuationBasis(model) ?: candidate?.basis ?: solver.infeasibleBasis
                println(authority(input, model, target))
                val live = certifyLpResult(model, solver, candidate, token)
                live.witness?.let { checkPoint(model, it) }
                live.rationalConflict?.let { checkConflict(model, it) }
                println(
                    buildJsonObject {
                        put("input", input)
                        put("role", "live-verdict")
                        put("verdict", live.verdict.name)
                        put("floatPivots", solver.lastPivots)
                        put("floatWork", solver.lastWorkOps)
                        put("continuationWork", live.continuation?.work ?: 0L)
                        put("continuationNs", live.continuation?.elapsedNs ?: 0L)
                    },
                )
                val cache = LpExactContinuationCache()
                for ((slice, ceiling) in listOf("short" to 20, "full" to 60)) {
                    val result = continueExactLp(
                        model,
                        target,
                        cache,
                        token,
                        ExactContinuationLimits(maxPivots = ceiling),
                        fullEffort = slice == "full",
                    )
                    result.witness?.let { checkPoint(model, it) }
                    result.conflict?.let { checkConflict(model, it) }
                    println(record(input, slice, result))
                }
            }
        }
    }

    private fun record(input: String, slice: String, result: LpContinuationVerification): JsonObject = buildJsonObject {
        val m = result.metrics
        put("input", input)
        put("role", "result")
        put("slice", slice)
        put("eligible", m.eligible)
        put("builds", m.builds)
        put("imports", m.imports)
        put("pivots", m.pivots)
        put("retainedPivots", m.retainedPivots)
        put("importPosition", m.importPosition)
        put("repairs", m.repairs)
        put("restarts", m.restarts)
        put("resumed", m.resumed)
        put("work", m.work)
        put("allocation", m.allocation)
        put("elapsedNs", m.elapsedNs)
        put("phase", m.phase.name)
        put("decline", m.decline?.name ?: "NONE")
        put("workByPhase", buildJsonObject { for ((phase, value) in m.workByPhase) put(phase.name, value) })
        put("allocationByPhase", buildJsonObject { for ((phase, value) in m.allocationByPhase) put(phase.name, value) })
        put("witness", result.witness != null)
        put("conflict", result.conflict != null)
        result.witness?.let {
            put("primal", fractions(it.primal))
            put("objective", fraction(it.objective))
        }
        result.conflict?.let {
            put("conflictRows", JsonArray(it.rows.map(::JsonPrimitive)))
            put("multipliers", fractions(it.multipliers))
        }
    }

    private fun fraction(value: BigFraction): String = "${value.num}/${value.den}"

    private fun fractions(values: List<BigFraction>): JsonArray = JsonArray(values.map { JsonPrimitive(fraction(it)) })

    private fun authority(input: String, model: LpModel, basis: Basis?): JsonObject = buildJsonObject {
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
        put(
            "lower",
            JsonArray(
                bounds.map {
                    it.lower?.let { side -> JsonPrimitive(fraction(side.number.value)) } ?: JsonNull
                },
            ),
        )
        put(
            "upper",
            JsonArray(
                bounds.map {
                    it.upper?.let { side -> JsonPrimitive(fraction(side.number.value)) } ?: JsonNull
                },
            ),
        )
        put("lowerStrict", JsonArray(bounds.map { JsonPrimitive(it.lower?.strict == true) }))
        put("upperStrict", JsonArray(bounds.map { JsonPrimitive(it.upper?.strict == true) }))
        put("headings", JsonArray(basis?.basicVars?.map(::JsonPrimitive) ?: emptyList()))
        put("statuses", JsonArray(basis?.status?.map { JsonPrimitive(it.name) } ?: emptyList()))
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
    private fun `higher effort finds a conflict after prior feasibility pivots`() {
        val n = 4
        val input = ExactContinuationInput(
            List(n) { listOf(it to BigFraction.MINUS_ONE, n to BigFraction.ONE) },
            List(n) { BigFraction.MINUS_ONE } + BigFraction.ofLong(3),
            List(2 * n + 1) { BigFraction.ZERO },
            List(2 * n + 1) { null },
            List(n + 1) { n + it },
            List(2 * n + 1) { if (it < n) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
        )
        val session = ExactContinuation(input)

        val short = session.resume(ExactContinuationLimits(maxPivots = 2))
        val full = session.resume(ExactContinuationLimits(maxPivots = 4))

        assertEquals(ContinuationDecline.PIVOTS, short.metrics.decline)
        assertEquals(2, full.metrics.pivots)
        val ray = assertNotNull(full.ray)
        val rhs = ray.indices.fold(BigFraction.ZERO) { sum, i -> sum + ray[i] * input.rhs[i] }
        for (j in 0 until n) {
            val coefficient = input.columns[j].fold(BigFraction.ZERO) { sum, (row, value) -> sum + ray[row] * value }
            assertEquals(BigFraction.ZERO, coefficient)
        }
        assertTrue(ray.all { it <= BigFraction.ZERO })
        assertTrue(rhs > BigFraction.ZERO)
    }
    private fun verifyForeignStateRecovery() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val first = LpExactState(source)
        val foreign = LpExactState(source)
        RevisedSimplex(assertNotNull(first.toWorkingModel())).use { solver ->
            val hint = assertNotNull(solver.solve())

            val wrongResult = certifyLpResult(assertNotNull(foreign.toWorkingModel()), solver, hint)
            val missing = certifyLpResult(assertNotNull(foreign.toWorkingModel()), solver, null)

            assertEquals(LpVerdict.INDETERMINATE, wrongResult.verdict)
            assertNull(wrongResult.continuation)
            assertEquals(LpVerdict.INDETERMINATE, missing.verdict)
            assertEquals(ContinuationDecline.NO_BASIS, assertNotNull(missing.continuation).decline)
        }
    }
}
