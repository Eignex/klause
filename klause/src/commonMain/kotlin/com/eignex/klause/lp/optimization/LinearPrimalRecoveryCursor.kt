package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.reconstructRational
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntIndexedCollection
import com.eignex.util.collections.storage.borrowBackingArray
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf

internal class LinearPrimalRecoveryCursor(
    private val coordinates: List<SourceCoordinate>,
    private val point: List<BigFraction>,
    private val rows: List<ExactRationalInequality>,
    private val inactive: Set<Int>,
    private val limits: LinearPrimalRecoveryLimits,
) {
    private enum class Phase {
        Point,
        Rows,
        Reconstruct,
        InitialCheck,
        Residual,
        Component,
        LocalRows,
        ComponentCheck,
        Solve,
        Install,
        FinalCheck,
        Finished,
    }

    private var phase = Phase.Point
    private var initialized = false
    private var next = 0
    private var entry = 0
    private var rowStarted = false
    private var entries = 0L
    private val active = ArrayList<ExactRationalInequality>()
    private var endpoints = BooleanArray(0)
    private val reconstructed = ArrayList<BigFraction>()
    private val residual = ArrayList<ExactRationalInequality>()
    private var incidence = arrayOfNulls<IntArrayList>(0)
    private var rhs = BigFraction.ZERO
    private var columns = IntArrayList()
    private var coefficients = ArrayList<BigFraction>()
    private var incidenceEntry = 0
    private var seen = BooleanArray(0)
    private var selected = BooleanArray(0)
    private var local = IntArray(0)
    private var root = 0
    private var members = IntArrayList()
    private var componentRows = IntArrayList()
    private var member = 0
    private var adjacent = 0
    private var neighbor = -1
    private var sorted = false
    private var indexed = 0
    private var localRows = ArrayList<ExactRationalInequality>()
    private var localColumns: IntArray? = null
    private var localEntry = 0
    private var componentPoint: List<BigFraction>? = null
    private var values: List<BigFraction>? = null
    private var check: Satisfaction? = null
    private var result: List<BigFraction>? = null

    fun matches(coordinates: List<SourceCoordinate>, point: List<BigFraction>,
        rows: List<ExactRationalInequality>, inactive: Set<Int>, limits: LinearPrimalRecoveryLimits): Boolean =
        this.coordinates === coordinates && this.point === point && this.rows === rows && this.inactive === inactive &&
            this.limits == limits

    fun advance(meter: LinearPrimalMeter, cancellation: ExecutionControl,
        complete: (List<ExactRationalInequality>, IntIndexedCollection, ExecutionControl) -> List<BigFraction>?,
        observe: (String, Long) -> Unit): List<BigFraction>? {
        if (phase == Phase.Finished) return result
        if (!initialized) {
            if (coordinates.size != point.size) return finish()
            meter.atomic {
                meter.reserve(coordinates.size.toLong() + rows.size, 512L * coordinates.size + 192L * rows.size)
                endpoints = BooleanArray(point.size)
                incidence = arrayOfNulls(point.size)
                seen = BooleanArray(point.size)
                local = IntArray(point.size) { -1 }
                initialized = true
            }
        }
        while (phase != Phase.Finished) {
            meter.checkpoint()
            when (phase) {
                Phase.Point -> {
                    if (next == point.size) transition(Phase.Rows) else meter.atomic {
                        val value = meter.number(point[next])
                        if (coordinates[next].kind != SourceCoordinate.Kind.Real && value.den != BIG_ONE) {
                            observe("PrimalRecoveryFractional", 1L)
                            finish()
                        } else next++
                    }
                }
                Phase.Rows -> readRow(meter, limits)
                Phase.Reconstruct -> {
                    if (next == point.size) transition(Phase.InitialCheck) else meter.atomic {
                        meter.reserve(1L, 32L)
                        val value = point[next]
                        val recovered = if (coordinates[next].kind != SourceCoordinate.Kind.Real || endpoints[next])
                            value else {
                            val floating = value.toDouble()
                            if (BigFraction.ofDouble(floating)?.let(meter::number) != value) value else {
                                reconstructRational(floating, onStep = { meter.reserve(1L) })?.let {
                                    meter.number(BigFraction.of(bigIntOf(it.numerator), bigIntOf(it.denominator)))
                                } ?: value
                            }
                        }
                        reconstructed += recovered
                        next++
                    }
                }
                Phase.InitialCheck -> {
                    val checking = check ?: Satisfaction(reconstructed, active).also { check = it }
                    if (checking.advance(meter)) {
                        if (checking.satisfied) {
                            observe("PrimalRecoveryReconstructed", 1L)
                            return finish(reconstructed)
                        }
                        transition(Phase.Residual)
                    }
                }
                Phase.Residual -> substituteRow(meter, observe)
                Phase.Component -> selectComponent(meter)
                Phase.LocalRows -> prepareLocalRow(meter)
                Phase.ComponentCheck -> {
                    if (componentPoint == null) meter.atomic {
                        meter.reserve(members.size.toLong(), 32L * members.size)
                        componentPoint = List(members.size) { reconstructed[members[it]] }
                    }
                    val checking = check ?: Satisfaction(requireNotNull(componentPoint), localRows).also { check = it }
                    if (checking.advance(meter)) {
                        if (checking.satisfied) nextComponent() else {
                            observe("PrimalRecoveryComponents", 1L)
                            transition(Phase.Solve)
                        }
                    }
                }
                Phase.Solve -> {
                    values = complete(localRows, members, cancellation)
                    val solved = values ?: return null
                    if (solved.size != members.size) return finish()
                    transition(Phase.Install)
                }
                Phase.Install -> {
                    if (next == members.size) nextComponent() else meter.atomic {
                        reconstructed[members[next]] = meter.number(requireNotNull(values)[next])
                        next++
                    }
                }
                Phase.FinalCheck -> {
                    val checking = check ?: Satisfaction(reconstructed, active).also { check = it }
                    if (checking.advance(meter)) {
                        if (checking.satisfied) observe("PrimalRecoveryResidual", 1L)
                        return finish(reconstructed.takeIf { checking.satisfied })
                    }
                }
                Phase.Finished -> Unit
            }
        }
        return result
    }

    private fun readRow(meter: LinearPrimalMeter, limits: LinearPrimalRecoveryLimits) {
        if (next == rows.size) {
            transition(Phase.Reconstruct)
            return
        }
        if (next in inactive) {
            meter.atomic {
                meter.reserve(1L)
                next++
            }
            return
        }
        val row = rows[next]
        if (!rowStarted) meter.atomic {
            meter.reserve(1L)
            entries += row.columns.size + 1L
            if (entries > limits.entries) throw LinearPrimalStop("Entries")
            meter.number(row.rhs)
            rowStarted = true
        }
        if (entry < row.columns.size) meter.atomic {
            if (row.columns[entry] !in point.indices) finish() else {
                meter.number(row.coefficients[entry])
                entry++
            }
        } else meter.atomic {
            if (!row.strict && row.columns.size == 1) {
                val column = row.columns.single()
                if (coordinates[column].kind == SourceCoordinate.Kind.Real &&
                    meter.subtractProduct(row.rhs, row.coefficients.single(), point[column]).isZero) {
                    endpoints[column] = true
                }
            }
            active += row
            next++
            entry = 0
            rowStarted = false
        }
    }

    private fun substituteRow(meter: LinearPrimalMeter, observe: (String, Long) -> Unit) {
        if (next == active.size) {
            meter.atomic {
                meter.reserve(active.size.toLong(), active.size.toLong())
                selected = BooleanArray(residual.size)
                transition(Phase.Component)
            }
            return
        }
        val row = active[next]
        if (!rowStarted) meter.atomic {
            meter.reserve(row.columns.size.toLong() + 1L, 64L + 64L * row.columns.size)
            rhs = row.rhs
            columns = IntArrayList()
            coefficients = ArrayList()
            incidenceEntry = 0
            rowStarted = true
        }
        if (entry < row.columns.size) meter.atomic {
            val column = row.columns[entry]
            val coefficient = row.coefficients[entry]
            if (!coefficient.isZero) {
                if (coordinates[column].kind == SourceCoordinate.Kind.Real) {
                    columns.add(column)
                    coefficients += coefficient
                } else rhs = meter.subtractProduct(rhs, coefficient, point[column])
            }
            entry++
        } else if (incidenceEntry < columns.size) meter.atomic {
            meter.reserve(1L)
            val column = columns[incidenceEntry]
            val adjacentRows = incidence[column] ?: IntArrayList(1).also { incidence[column] = it }
            adjacentRows.add(residual.size)
            incidenceEntry++
        } else meter.atomic {
            if (columns.isEmpty()) {
                if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) {
                    observe("PrimalRecoveryDiscreteRows", 1L)
                    finish()
                    return@atomic
                }
            } else residual += ExactRationalInequality(columns.toIntArray(), coefficients, rhs, row.strict)
            next++
            entry = 0
            rowStarted = false
        }
    }

    private fun selectComponent(meter: LinearPrimalMeter) {
        if (members.isEmpty()) {
            if (root == point.size) {
                transition(Phase.FinalCheck)
                return
            }
            meter.atomic {
                meter.reserve(1L)
                if (seen[root] || incidence[root] == null) root++ else {
                    seen[root] = true
                    members.add(root)
                }
            }
            return
        }
        if (member < members.size) {
            val adjacentRows = incidence[members[member]]
            if (adjacentRows == null || adjacent == adjacentRows.size) meter.atomic {
                meter.reserve(1L, 16L)
                member++
                adjacent = 0
            } else {
                val id = adjacentRows[adjacent]
                if (neighbor < 0) meter.atomic {
                    meter.reserve(1L)
                    if (selected[id]) adjacent++ else {
                        selected[id] = true
                        componentRows.add(id)
                        neighbor = 0
                    }
                } else if (neighbor < residual[id].columns.size) meter.atomic {
                    meter.reserve(1L)
                    val column = residual[id].columns[neighbor]
                    if (!seen[column]) {
                        seen[column] = true
                        members.add(column)
                    }
                    neighbor++
                } else {
                    adjacent++
                    neighbor = -1
                }
            }
            return
        }
        if (!sorted) meter.atomic {
            meter.reserve(members.size.toLong() * (Int.SIZE_BITS - members.size.countLeadingZeroBits()))
            members.borrowBackingArray().sort(0, members.size)
            sorted = true
        } else if (indexed < members.size) meter.atomic {
            meter.reserve(1L)
            local[members[indexed]] = indexed
            indexed++
        } else transition(Phase.LocalRows)
    }

    private fun prepareLocalRow(meter: LinearPrimalMeter) {
        if (next == componentRows.size) {
            transition(Phase.ComponentCheck)
            return
        }
        val row = residual[componentRows[next]]
        if (localColumns == null) meter.atomic {
            meter.reserve(row.columns.size.toLong() + 1L, 64L + 32L * row.columns.size)
            localColumns = IntArray(row.columns.size)
            localEntry = 0
        }
        val translated = requireNotNull(localColumns)
        if (localEntry < translated.size) meter.atomic {
            meter.reserve(1L)
            translated[localEntry] = local[row.columns[localEntry]]
            localEntry++
        } else {
            localRows.add(ExactRationalInequality(translated, row.coefficients, row.rhs, row.strict))
            localColumns = null
            next++
        }
    }

    private fun nextComponent() {
        root++
        members = IntArrayList()
        componentRows = IntArrayList()
        member = 0
        adjacent = 0
        neighbor = -1
        sorted = false
        indexed = 0
        localRows = ArrayList()
        componentPoint = null
        values = null
        transition(Phase.Component)
    }

    private fun transition(nextPhase: Phase) {
        phase = nextPhase
        next = 0
        check = null
    }

    fun finish(point: List<BigFraction>? = null): List<BigFraction>? {
        phase = Phase.Finished
        result = point
        return point
    }

    private class Satisfaction(val point: List<BigFraction>, val rows: List<ExactRationalInequality>) {
        private var row = 0
        private var entry = 0
        private var rhs: BigFraction? = null
        var satisfied = true
            private set

        fun advance(meter: LinearPrimalMeter): Boolean {
            if (row == rows.size) return true
            val current = rows[row]
            if (rhs == null) rhs = current.rhs
            if (entry < current.columns.size) meter.atomic {
                rhs = meter.subtractProduct(requireNotNull(rhs), current.coefficients[entry],
                    point[current.columns[entry]])
                entry++
            } else meter.atomic {
                meter.reserve(1L)
                if (if (current.strict) requireNotNull(rhs) <= BigFraction.ZERO
                    else requireNotNull(rhs) < BigFraction.ZERO) {
                    satisfied = false
                    row = rows.size
                } else {
                    row++
                    entry = 0
                    rhs = null
                }
            }
            return row == rows.size
        }
    }
}
