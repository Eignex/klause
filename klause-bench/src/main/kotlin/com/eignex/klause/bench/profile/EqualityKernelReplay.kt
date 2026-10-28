package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.localsearch.Move
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds

internal object EqualityKernelReplay {
    private val zero = BigInteger.ZERO
    private val one = BigInteger.ONE
    private const val MAX_COLUMNS = 40
    private const val MAX_ROWS = 64
    private const val MAX_DIRECTIONS = 16
    private const val MAX_WORK = 2_000_000L

    private class Matrix(val columns: List<Int>, val rows: List<Array<BigInteger>>)
    private class Arc(val from: Int, val to: Int, val column: Int, val sign: Int)

    fun capture(
        view: TrajectoryView,
        file: File,
        score: (Sample) -> BigFraction,
        cancellation: Cancellation = Cancellation.Never,
        verify: (Sample, Cancellation) -> Boolean,
    ): Map<String, IntArray> {
        val started = System.nanoTime()
        val allocated = trajectoryThreadBytes()
        var work = 0L
        val clock = Cancellation.after(2.seconds) or cancellation
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= MAX_WORK || clock()
            override fun charge(units: Long) {
                work += units
            }
        }
        val matrix = matrix(view, stop)
        val center = view.reconstruct(view.state.assignment.snapshot())
        val consumedScopes = LinkedHashMap<String, IntArray>()
        val records = buildJsonArray {
            if (matrix != null) {
                for (mode in listOf("network", "hermite")) {
                    val extractionStart = System.nanoTime()
                    val extractionBytes = trajectoryThreadBytes()
                    val directions = if (mode == "network") network(matrix, view, stop) else hermite(matrix, stop)
                    val extractionNs = System.nanoTime() - extractionStart
                    val extractionAllocation = trajectoryThreadBytes() - extractionBytes
                    var selected: Move? = null
                    var best = score(center)
                    add(
                        buildJsonObject {
                        put("mode", mode)
                        put("extractionNs", extractionNs)
                        put("extractionThreadBytes", extractionAllocation)
                        put("declined", directions == null)
                        put(
                            "directions",
                            buildJsonArray {
                            for (direction in directions.orEmpty()) {
                                check(
                                    matrix.rows.all { row ->
                                    row.indices.fold(zero) { sum, i -> sum + row[i] * direction[i] } == zero
                                }
                                )
                                add(
                                    buildJsonObject {
                                    put("g", JsonArray(direction.map { JsonPrimitive(it.toString()) }))
                                    put("exactAgZero", true)
                                    put(
                                        "candidates",
                                        candidates(matrix, direction, view, stop, verify) { move, point ->
                                        val value = score(point)
                                        if (value < best) {
                                            best = value
                                            selected = move
                                        }
                                    }
                                    )
                                }
                                )
                            }
                        }
                        )
                        val move = selected as? Move.Compound
                        val consumed = move?.let { EqualityKernelMove.consume(view, it, center, score, stop, verify) }
                        if (consumed != null) {
                            consumedScopes[mode] = consumed.inputs
                        }
                        put("consumed", consumed?.let {
                            buildJsonObject {
                                put("preparedInputs", JsonArray(it.inputs.map(::JsonPrimitive)))
                                put("source", trajectorySource(it.source))
                                put("sourceObjective", it.value.toString())
                                put("sourceChanges", it.changes)
                                put("nativeSourceCheck", true)
                            }
                        } ?: JsonNull)
                    }
                    )
                }
            }
        }
        file.writeText(
            buildJsonObject {
            put("eligibleMatrix", matrix != null)
            put("columns", JsonArray(matrix?.columns.orEmpty().map(::JsonPrimitive)))
            put("rows", matrix?.rows?.size ?: 0)
            put("work", work)
            put("workLimit", MAX_WORK)
            put("elapsedNs", System.nanoTime() - started)
                put("threadBytes", trajectoryThreadBytes() - allocated)
            put("interrupted", stop())
            put("scope", "cold bounded diagnostic; no retained source or default policy")
            put("records", records)
            put("connectivityOrCompletenessClaim", false)
        }.toString()
        )
        return consumedScopes
    }

    private fun matrix(view: TrajectoryView, stop: Cancellation): Matrix? {
        val state = view.state
        if (state.problem.semantics.effective.feasibilityTolerance?.isExact == false) return null
        val sparse = ArrayList<Map<Int, BigInteger>>()
        for (factor in state.problem.factors) {
            for (row in factor.impliedLinearRows) {
                stop.charge(1L + row.size)
                if (stop()) return null
                if (row.relation != LinearOp.EQ || row.activator != LinearRow.ALWAYS || row.strict) continue
                val constants = row.constants as? IntegralConstants ?: continue
                if (!row.isIntegerOnly) continue
                if (row.size > MAX_COLUMNS) return null
                val terms = HashMap<Int, BigInteger>()
                for (i in 0 until row.size) {
                    val coefficient = BigInteger(constants.exactCoeff(i).toString())
                    if (coefficient.bitLength() > 16) return null
                    val id = Term.intVar(row.ref(i))
                    terms[id] = (terms[id] ?: zero) + coefficient
                }
                val kept = terms.filterValues { it != zero }
                if (kept.values.any { it.abs().bitLength() > 16 }) return null
                sparse += kept
                if (sparse.size > MAX_ROWS) return null
            }
        }
        val columns = sparse.flatMap { it.keys }.distinct().sorted()
        if (columns.isEmpty() || columns.size > MAX_COLUMNS) return null
        if (columns.any { state.invariants?.isDefinedInt(it) == true }) return null
        return Matrix(columns, sparse.map { row -> Array(columns.size) { row[columns[it]] ?: zero } })
    }

    private fun hermite(matrix: Matrix, stop: Cancellation): List<Array<BigInteger>>? {
        val rowType = Class.forName("com.eignex.klause.lp.lattice.SparseIntRow")
        val constructor = rowType.constructors.single()
        val rows = matrix.rows.map { row ->
            val positions = row.indices.filter { row[it] != zero }
            constructor.newInstance(positions.toIntArray(), positions.map { row[it] }.toTypedArray())
        }
        val form = Class.forName("com.eignex.klause.lp.lattice.HermiteFormKt").methods.single {
            it.name == "hermiteNormalForm" && it.parameterCount == 3
        }.invoke(null, rows, matrix.columns.size, stop) ?: return null
        if (stop()) return null
        val h = LocalSearchTrajectory.getter(form, "getH") as List<*>
        val v = requireNotNull(LocalSearchTrajectory.getter(form, "getV"))
        val entry = v.javaClass.getMethod("get", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val result = ArrayList<Array<BigInteger>>()
        for (column in matrix.columns.indices) {
            stop.charge((1L + matrix.rows.size) * matrix.columns.size)
            if (stop()) return null
            if (h.filterNotNull().any {
                    it.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(it, column) != zero
                }
            ) {
                continue
            }
            val direction = Array(matrix.columns.size) { entry.invoke(v, it, column) as BigInteger }
            if (direction.any { it.abs().bitLength() > 256 }) return null
            result += direction
            if (result.size == MAX_DIRECTIONS) break
        }
        return result
    }

    private fun network(matrix: Matrix, view: TrajectoryView, stop: Cancellation): List<Array<BigInteger>>? {
        val incidence = matrix.columns.indices.map { column ->
            matrix.rows.indices.filter {
                matrix.rows[it][column] != zero
            }
        }
        if (incidence.any { it.size !in 1..2 } || matrix.rows.any { row -> row.any { it.abs() > one } }) return null
        val signs = IntArray(matrix.rows.size)
        for (root in signs.indices) {
            if (signs[root] != 0) continue
            signs[root] = 1
            val queue = ArrayDeque<Int>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val row = queue.removeFirst()
                for ((column, endpoints) in incidence.withIndex()) {
                    stop.charge(1)
                    if (stop()) return null
                    if (endpoints.size != 2 || row !in endpoints) continue
                    val peer = endpoints.first { it != row }
                    val sign = -signs[row] * matrix.rows[row][column].signum() *
                        matrix.rows[peer][column].signum()
                    if (signs[peer] != 0 && signs[peer] != sign) return null
                    if (signs[peer] == 0) {
                        signs[peer] = sign
                        queue.addLast(peer)
                    }
                }
            }
        }
        val arcs = ArrayList<Arc>()
        val ground = matrix.rows.size
        for ((column, endpoints) in incidence.withIndex()) {
            val positive = endpoints.firstOrNull { signs[it] * matrix.rows[it][column].signum() > 0 } ?: ground
            val negative = endpoints.firstOrNull { signs[it] * matrix.rows[it][column].signum() < 0 } ?: ground
            val variable = matrix.columns[column]
            val current = BigInteger.valueOf(view.state.assignment.intValue(variable))
            val domain = view.state.rootDomains[variable]
            if (current < BigInteger.valueOf(domain.max) && (current + one).toLong() in domain) {
                arcs += Arc(negative, positive, column, 1)
            }
            if (current > BigInteger.valueOf(domain.min) && (current - one).toLong() in domain) {
                arcs += Arc(positive, negative, column, -1)
            }
        }
        val directions = linkedMapOf<List<BigInteger>, Array<BigInteger>>()
        for (first in arcs) {
            val previous = arrayOfNulls<Arc>(ground + 1)
            val seen = BooleanArray(ground + 1)
            val queue = ArrayDeque<Int>().apply { add(first.to) }
            seen[first.to] = true
            while (queue.isNotEmpty() && !seen[first.from]) {
                val node = queue.removeFirst()
                for (arc in arcs) {
                    stop.charge(1)
                    if (stop()) return null
                    if (arc.column == first.column || arc.from != node || seen[arc.to]) continue
                    seen[arc.to] = true
                    previous[arc.to] = arc
                    queue.addLast(arc.to)
                }
            }
            if (!seen[first.from]) continue
            val direction = Array(matrix.columns.size) { zero }
            direction[first.column] = BigInteger.valueOf(first.sign.toLong())
            var node = first.from
            while (node != first.to) {
                val arc = checkNotNull(previous[node])
                direction[arc.column] = BigInteger.valueOf(arc.sign.toLong())
                node = arc.from
            }
            val canonical = if (direction.first { it != zero } < zero) {
                Array(direction.size) { -direction[it] }
            } else {
                direction
            }
            directions[canonical.toList()] = canonical
            if (directions.size == MAX_DIRECTIONS) break
        }
        return directions.values.toList()
    }

    private fun candidates(
        matrix: Matrix,
        direction: Array<BigInteger>,
        view: TrajectoryView,
        stop: Cancellation,
        check: (Sample, Cancellation) -> Boolean,
        improving: (Move, Sample) -> Unit,
    ) = buildJsonArray {
        val state = view.state
        val steps = EqualityKernelMove.steps(
            matrix.columns, matrix.rows, direction, state.assignment::intValue,
            { state.rootDomains[it] },
            { id ->
                !state.assumptions.isFrozenInt(id) && state.invariants?.isDefinedInt(id) != true &&
                    (state.seeding.ownerInt?.get(id) ?: -1) < 0
            },
            stop,
        )
        for (step in steps) {
            if (stop()) return@buildJsonArray
            val move = EqualityKernelMove.admit(step, view::proposalSink) ?: continue
            val probe = EqualityKernelMove.probe(view, move, stop, check) ?: continue
            val inspection = probe.inspection
            if (inspection.netDelta == 0L && probe.accepted) improving(move, probe.source)
            add(
                buildJsonObject {
                    put("step", step.scalar.toString())
                    put("move", move.toString())
                    put("netDelta", inspection.netDelta)
                    put("source", trajectorySource(probe.source))
                    put("nativeSourceCheck", probe.accepted)
                    put("objectiveDelta", inspection.objectiveDelta.toString())
                    put("wholeSinkAdmission", true)
                },
            )
        }
    }
}
