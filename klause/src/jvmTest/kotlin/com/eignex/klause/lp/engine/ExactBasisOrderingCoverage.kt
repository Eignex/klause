package com.eignex.klause.lp.engine

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.toExactLpModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.basis.BasisTraceCodec
import com.eignex.klause.simplex.basis.BasisTraceOperation
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.basis.RationalBasisOrder
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.basis.headingColumn
import com.eignex.koblas.SparseMatrix
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
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
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertEquals

internal object ExactBasisOrderingCoverage {
    @JvmStatic
    fun main(args: Array<String>) {
        verifyNonsymmetricOrder()
        val root = Path.of(args.single())
        val inputs = listOf(
            "mps/blend-tiny.mps", "mps/feasible-tiny.mps", "mps/infeasible-tiny.mps", "mps/float-tiny.mps",
            "smtlib/lia-basic.smt2", "smtlib/lia-unsat.smt2", "smtlib/lia-wide-span.smt2",
            "boundary/49-diagonal", "boundary/129-diagonal",
        )
        for (input in inputs) {
            val source = if (input.startsWith("boundary/")) {
                ""
            } else {
                Files.readString(
                    root.resolve("klause-bench/smoke-corpus/$input"),
                )
            }
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
                    println(
                        buildJsonObject {
                            put("input", input)
                            put("ineligible", "BOOLEAN_BRANCH")
                        },
                    )
                    continue
                }
                val exact = QfLraSystem(parsed.model).build { null }.toExactLpModel()
                LpExactState(exact).toWorkingModel()
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
            for (round in -1..2) {
                for (enabled in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val started = System.nanoTime()
                    val token = Cancellation { System.nanoTime() - started >= 10_000_000_000L }
                    var floatOwner: KotlinBasisSolver? = null
                    RevisedSimplex(model, token, iterationLimit = 4096, workLimit = 50_000_000L,
                        basisSolverFactory = { KotlinBasisSolver(it).also { owner -> floatOwner = owner } },
                        reuseRationalOrder = enabled).use { solver ->
                        val candidate = solver.solve()
                        val basis = candidate?.basis ?: solver.infeasibleBasis
                        val row = if (candidate == null) solver.infeasibleRow else null
                        val id = "$input/$round/$enabled"
                        val eligible = model.exactState != null && basis != null &&
                            floatOwner?.singular == false && floatOwner.updateCount == 0
                        val observer = object : LpCertificationObserver {
                            override fun observe(certifier: LpCertifier, success: Boolean) = Unit
                            override fun observeExactInput(accepted: Boolean) = Unit
                            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
                            override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                                println(record(id, "real-certify", metrics))
                            }
                        }
                        val certifyStart = System.nanoTime()
                        val certified = certifyLpResult(model, solver, candidate, token, observer)
                        val certifyNanos = System.nanoTime() - certifyStart
                        certified.witness?.let { checkPoint(model, it) }
                        certified.rationalConflict?.let { checkConflict(model, it) }
                        println(buildJsonObject {
                            put("input", id)
                            put("role", "real-verdict")
                            put("verdict", certified.verdict.name)
                            put("nanos", certifyNanos)
                            put("exactState", model.exactState != null)
                            put("orderingEligibleBeforeVerification", eligible)
                            put("updateCount", floatOwner?.updateCount ?: -1)
                            put("basis", basis != null)
                            put("floatWork", solver.lastWorkOps)
                            put("pivots", solver.lastPivots)
                        })
                        if (basis != null) {
                            solver.exactBasisCache.clear()
                            measured(id, "real-offer", model, basis, row, solver.exactBasisCache)
                            measured(id, "real-reuse", model, basis, row, solver.exactBasisCache)
                        }
                    }
                }
            }
        }
        traces(root)
    }

    private fun measured(input: String, role: String, model: LpModel, basis: Basis, row: Int?, cache: ExactBasisCache) {
        println(authority(input, model, basis))
        val started = System.nanoTime()
        val checked = verifyExactBasis(model, basis, row, cache)
        val nanos = System.nanoTime() - started
        println(record(input, role, checked.metrics))
        println(buildJsonObject {
            put("input", input)
            put("role", "$role-time")
            put("nanos", nanos)
        })
        checked.witness?.let { checkPoint(model, it) }
        checked.conflict?.let { checkConflict(model, it) }
        println(strength(input, role, checked))
    }

    private fun traces(root: Path) {
        val corpus = root.resolve("klause/src/jvmTest/resources/basis-corpus")
        val names = listOf("mps-afiro", "mps-adlittle", "mzn-graph-coloring", "mzn-timetabling",
            "smt-lia-wide-span", "smt-lia-unsat")
        for (name in names) {
            val trace = BasisTraceCodec.read(corpus.resolve("$name.kbtrace"))
            val source = trace.matrix
            val matrix = SparseMatrix.wrap(source.rows, source.columns, source.copyColumnPointers(),
                source.copyRowIndices(), DoubleArray(source.entries) { source.valueAt(it) })
            val builds = trace.operations.indices.filter {
                (trace.operations[it] as? BasisTraceOperation.Factorize)?.success == true
            }
            val update = trace.operations.indexOfFirst { it is BasisTraceOperation.Update && it.outcome != BasisUpdate.SINGULAR }
            val samples = (listOfNotNull(builds.firstOrNull(), builds.lastOrNull()) + listOf(update)).toSet()
            for (round in -1..2) {
                KotlinBasisSolver(matrix).use { solver ->
                    var headings: IntArray? = null
                    for ((index, operation) in trace.operations.withIndex()) {
                        when (operation) {
                            is BasisTraceOperation.Factorize -> {
                                val current = operation.headings.map { headingColumn(it, trace.sourceColumns) }.toIntArray()
                                val success = solver.refactorize(current)
                                check(success == operation.success) { "$name/$index build divergence" }
                                headings = current.takeIf { success }
                            }
                            is BasisTraceOperation.Update -> {
                                val current = headings ?: continue
                                val entering = headingColumn(operation.entering, trace.sourceColumns)
                                val spike = IndexedVector(source.rows).also { it.scatterColumn(matrix, entering) }
                                solver.ftran(spike)
                                val outcome = solver.update(operation.leavingSlot, entering, spike)
                                check((outcome == BasisUpdate.SINGULAR) == (operation.outcome == BasisUpdate.SINGULAR)) {
                                    "$name/$index update divergence"
                                }
                                if (outcome != BasisUpdate.SINGULAR) current[operation.leavingSlot] = entering
                            }
                            is BasisTraceOperation.Solve -> Unit
                        }
                        val current = headings ?: continue
                        if (index !in samples) continue
                        val rhs = trace.operations.drop(index + 1).filterIsInstance<BasisTraceOperation.Solve>()
                            .firstOrNull { !it.transpose }?.rhs?.values() ?: DoubleArray(source.rows)
                        val exact = ExactLpModel(List(source.columns) { column ->
                            (source.columnStart(column) until source.columnEnd(column)).map { entry ->
                                ExactLpEntry(source.rowAt(entry), ExactLpNumber.ofIeee(source.valueAt(entry)))
                            }
                        }, rhs.map(ExactLpNumber::ofIeee), List(source.columns + source.rows) {
                            ExactLpColumn(ExactLpBounds())
                        }, List(source.rows) { ExactLpRow() },
                            ExactLpObjective(List(source.columns + source.rows) { ExactLpNumber.of(0L) }))
                        val model = requireNotNull(LpExactState(exact).toWorkingModel())
                        val statuses = Array(model.numVars) { VarStatus.FREE }
                        current.forEach { statuses[it] = VarStatus.BASIC }
                        val basis = Basis(current.copyOf(), statuses)
                        for (enabled in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                            val provider: ((ExactBasisAuthority) -> RationalBasisOrder?)? = if (!enabled) null else {
                                authority ->
                                authority.meter.charge(16L * source.rows, 768L + 48L * source.rows)
                                val snapshot = solver.ordering()
                                if (snapshot == null) {
                                    authority.meter.orderDecline = ExactBasisOrderDecline.UPDATED
                                    null
                                } else {
                                    check(snapshot.columns.contentEquals(current))
                                    check(snapshot.unitRows.all { it == -1 })
                                    RationalBasisOrder(snapshot.rows, snapshot.slots)
                                }
                            }
                            val cache = ExactBasisCache(provider)
                            val id = "trace/$name/$index/$round/$enabled"
                            measured(id, "trace-offer", model, basis, null, cache)
                            val second = ExactLpModel(List(exact.n) { exact.entries(it) },
                                List(exact.m) { ExactLpNumber.of(exact.rhs(it).value + BigFraction.ONE) },
                                List(exact.numVars) { exact.column(it) }, List(exact.m) { exact.row(it) }, exact.objective)
                            measured("$id/rhs", "trace-rhs", requireNotNull(LpExactState(second).toWorkingModel()), basis, null, cache)
                        }
                    }
                }
            }
        }
    }

    private fun verifyNonsymmetricOrder() {
        val matrix = SparseMatrix.ofColumns(3, 3, listOf(
            listOf(0 to 3.0, 1 to 1.0, 2 to 2.0),
            listOf(0 to 2.0, 1 to 4.0, 2 to 1.0),
            listOf(0 to 1.0, 1 to 3.0, 2 to 5.0),
        ))
        KotlinBasisSolver(matrix).use { solver ->
            val headings = intArrayOf(2, 0, 1)
            assertTrue(solver.refactorize(headings))
            val order = assertNotNull(solver.ordering())
            val exact = listOf(listOf(1L, 3L, 2L), listOf(3L, 1L, 4L), listOf(5L, 2L, 1L))
                .map { it.map(BigFraction::ofLong) }

            val built = assertIs<RationalBasisBuild.Ready>(
                RationalBasisFactors.factor(exact, RationalBasisOrder(order.rows, order.slots)),
            )
            val normal = assertIs<RationalBasisSolve.Solved>(
                built.factors.solve(listOf(13L, 23L, 16L).map(BigFraction::ofLong)),
            )
            val transpose = assertIs<RationalBasisSolve.Solved>(
                built.factors.solve(listOf(22L, 11L, 13L).map(BigFraction::ofLong), transpose = true),
            )

            assertFalse(order.rows.contentEquals(order.slots))
            assertEquals(0, built.stats.fallbacks)
            assertEquals(listOf(2L, 1L, 4L).map(BigFraction::ofLong), normal.values)
            assertEquals(listOf(1L, 2L, 3L).map(BigFraction::ofLong), transpose.values)
        }
    }

    private fun record(input: String, role: String, metrics: ExactBasisMetrics): JsonObject = buildJsonObject {
        put("input", input)
        put("role", role)
        put("eligible", metrics.eligible)
        put("orderOffers", metrics.orderOffers)
        put("orderProposals", metrics.orderProposals)
        put("orderAttempts", metrics.orderAttempts)
        put("orderFallbacks", metrics.orderFallbacks)
        put("orderDecline", metrics.orderDecline?.name ?: "NONE")
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
        put(
            "operations",
            buildJsonObject {
                for (operation in metrics.operations) {
                    put(
                        operation.phase.name,
                        buildJsonObject {
                            put("work", operation.work)
                            put("allocation", operation.allocation)
                        },
                    )
                }
            },
        )
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
        put("singularRank", exact.singularRank?.let(::JsonPrimitive) ?: JsonNull)
        put("decline", exact.metrics.decline?.name ?: "NONE")
        put("supportRows", exact.conflictSupport?.rows?.map { it.first }?.joinToString(",") ?: "NONE")
        put("supportSides", exact.conflictSupport?.sides?.joinToString { "${it.column}:${it.upper}:${it.side.number.value}:${it.side.strict}:${it.witness}" } ?: "NONE")
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
