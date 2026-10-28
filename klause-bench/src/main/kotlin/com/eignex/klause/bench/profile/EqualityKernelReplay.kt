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
import java.io.File
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object EqualityKernelReplay {
    private val zero = BigInteger.ZERO
    private val one = BigInteger.ONE
    private const val MAX_COLUMNS = 40
    private const val MAX_ROWS = 64
    private const val MAX_DIRECTIONS = 16
    private const val MAX_WORK = 2_000_000L

    private class Matrix(val columns: List<Int>, val rows: List<Array<BigInteger>>)
    private class Arc(val from: Int, val to: Int, val column: Int, val sign: Int)

    fun capture(view: TrajectoryView, file: File, score: (Sample) -> BigFraction,
        verify: (Sample, Cancellation) -> Boolean,
    ) {
        val started = System.nanoTime()
        val allocated = trajectoryThreadBytes()
        var work = 0L
        val clock = Cancellation.after(2.seconds)
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= MAX_WORK || clock()
            override fun charge(units: Long) { work += units }
        }
        val matrix = matrix(view, stop)
        val center = view.reconstruct(view.state.assignment.snapshot())
        val records = buildJsonArray {
            if (matrix != null) for (mode in listOf("network", "hermite")) {
                val extractionStart = System.nanoTime()
                val extractionBytes = trajectoryThreadBytes()
                val directions = if (mode == "network") network(matrix, view, stop) else hermite(matrix, stop)
                val extractionNs = System.nanoTime() - extractionStart
                val extractionAllocation = trajectoryThreadBytes() - extractionBytes
                var selected: Move? = null
                var best = score(center)
                add(buildJsonObject {
                    put("mode", mode)
                    put("extractionNs", extractionNs)
                    put("extractionThreadBytes", extractionAllocation)
                    put("declined", directions == null)
                    put("directions", buildJsonArray {
                        for (direction in directions.orEmpty()) {
                            check(matrix.rows.all { row -> row.indices.fold(zero) { sum, i ->
                                sum + row[i] * direction[i]
                            } == zero })
                            add(buildJsonObject {
                                put("g", JsonArray(direction.map { JsonPrimitive(it.toString()) }))
                                put("exactAgZero", true)
                                put("candidates", candidates(matrix, direction, view, stop, verify) { move, point ->
                                    val value = score(point)
                                    if (value < best) { best = value; selected = move }
                                })
                            })
                        }
                    })
                    put("consumed", selected?.takeUnless { stop() }?.let { move ->
                        view.state.apply(move)
                        val point = view.reconstruct(view.state.assignment.snapshot())
                        check(view.state.cost == 0L && verify(point, stop))
                        val record = buildJsonObject {
                            put("source", trajectorySource(point))
                            put("sourceObjective", score(point).toString())
                            put("sourceChanges", (0 until center.numIntVars).count {
                                center.intValue(it) != point.intValue(it)
                            })
                            put("nativeSourceCheck", true)
                        }
                        check(view.load(center) == null)
                        record
                    } ?: JsonNull)
                })
            }
        }
        file.writeText(buildJsonObject {
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
        }.toString())
    }

    private fun matrix(view: TrajectoryView, stop: Cancellation): Matrix? {
        val state = view.state
        if (state.problem.semantics.effective.feasibilityTolerance?.isExact == false) return null
        val sparse = ArrayList<Map<Int, BigInteger>>()
        for (factor in state.problem.factors) for (row in factor.impliedLinearRows) {
            stop.charge(1L + row.size)
            if (stop()) return null
            if (row.relation != LinearOp.EQ || row.activator != LinearRow.ALWAYS || row.strict) continue
            val constants = row.constants as? IntegralConstants ?: continue
            if (!row.isIntegerOnly || row.size > MAX_COLUMNS) continue
            val terms = HashMap<Int, BigInteger>()
            for (i in 0 until row.size) {
                val coefficient = BigInteger(constants.exactCoeff(i).toString())
                if (coefficient.bitLength() > 16) return null
                val id = Term.intVar(row.ref(i))
                terms[id] = (terms[id] ?: zero) + coefficient
            }
            sparse += terms.filterValues { it != zero }
            if (sparse.size > MAX_ROWS) return null
        }
        val columns = sparse.flatMap { it.keys }.distinct().sorted()
        if (columns.isEmpty() || columns.size > MAX_COLUMNS) return null
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
                }) continue
            val direction = Array(matrix.columns.size) { entry.invoke(v, it, column) as BigInteger }
            if (direction.any { it.bitLength() > 256 }) return null
            result += direction
            if (result.size == MAX_DIRECTIONS) break
        }
        return result
    }

    private fun network(matrix: Matrix, view: TrajectoryView, stop: Cancellation): List<Array<BigInteger>>? {
        val incidence = matrix.columns.indices.map { column -> matrix.rows.indices.filter {
            matrix.rows[it][column] != zero
        } }
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
                    if (signs[peer] == 0) { signs[peer] = sign; queue.addLast(peer) }
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
            if (current < BigInteger.valueOf(domain.max) && (current + one).toLong() in domain)
                arcs += Arc(negative, positive, column, 1)
            if (current > BigInteger.valueOf(domain.min) && (current - one).toLong() in domain)
                arcs += Arc(positive, negative, column, -1)
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
            val canonical = if (direction.first { it != zero } < zero) Array(direction.size) { -direction[it] }
                else direction
            directions[canonical.toList()] = canonical
            if (directions.size == MAX_DIRECTIONS) break
        }
        return directions.values.toList()
    }

    private fun candidates(matrix: Matrix, direction: Array<BigInteger>, view: TrajectoryView,
        stop: Cancellation, check: (Sample, Cancellation) -> Boolean,
        improving: (Move, Sample) -> Unit,
    ) = buildJsonArray {
        val state = view.state
        for (sign in listOf(one, -one)) {
            val g = direction.map { it * sign }
            var maximum: BigInteger? = null
            for ((i, id) in matrix.columns.withIndex()) {
                if (g[i] == zero) continue
                val current = BigInteger.valueOf(state.assignment.intValue(id))
                val domain = state.rootDomains[id]
                val limit = if (g[i] > zero) (BigInteger.valueOf(domain.max) - current) / g[i]
                    else (current - BigInteger.valueOf(domain.min)) / -g[i]
                maximum = maximum?.min(limit) ?: limit
            }
            val cap = maximum ?: continue
            for (step in listOf(one, cap).distinct().filter { it > zero && it <= cap }) {
                if (stop()) return@buildJsonArray
                val parts = ArrayList<Move>()
                var admissible = true
                for ((i, id) in matrix.columns.withIndex()) {
                    if (g[i] == zero) continue
                    val target = BigInteger.valueOf(state.assignment.intValue(id)) + step * g[i]
                    if (target < BigInteger.valueOf(Long.MIN_VALUE) || target > BigInteger.valueOf(Long.MAX_VALUE) ||
                        target.toLong() !in state.rootDomains[id] || state.assumptions.isFrozenInt(id) ||
                        state.invariants?.isDefinedInt(id) == true || (state.seeding.ownerInt?.get(id) ?: -1) >= 0
                    ) { admissible = false; break }
                    parts += Move.IntSet(id, target.toLong())
                }
                if (!admissible || parts.size < 2) continue
                val sink = view.proposalSink()
                sink.addCompound(parts)
                val move = sink.list.singleOrNull() as? Move.Compound ?: continue
                if (move.parts != parts) continue
                val inspection = state.inspectMove(move, view.objective)
                val source = view.reconstruct(inspection.assignment)
                val accepted = check(source, stop)
                if (inspection.netDelta == 0L && accepted) improving(move, source)
                add(buildJsonObject {
                    put("step", (step * sign).toString())
                    put("move", move.toString())
                    put("netDelta", inspection.netDelta)
                    put("source", trajectorySource(source))
                    put("nativeSourceCheck", accepted)
                    put("objectiveDelta", inspection.objectiveDelta.toString())
                    put("wholeSinkAdmission", true)
                })
            }
        }
    }
}
