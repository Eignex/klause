package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl

internal const val LP_CAPTURE_VERSION: Int = 2
internal const val LP_EVENT_VERSION: Int = 1

// A double view preserves the IEEE bits presented to the engine; it does not reconstruct source decimal or
// rational input.
internal enum class LpNumericAuthority(val wireCode: Int) {
    LONG_EXACT(1),
    IEEE754_BITS_SOURCE_RATIONAL_ABSENT(2),
}

internal enum class LpReplaySolverKind(val wireCode: Int) {
    GENERAL(1),
    TABLEAU(2),
    PERSISTENT(3),
}

// A replay creates exactly one engine; persistent events reuse its basis and factors, while a portable warm start
// holds only basis columns and statuses. [cancellationPollLimit] is the initial deterministic token, zero meaning
// never cancel; a rebind replaces it only when that event carries an explicit override.
internal class LpReplaySettings(
    val label: String,
    val seed: Long,
    val solverKind: LpReplaySolverKind = LpReplaySolverKind.GENERAL,
    val componentSplit: Boolean = true,
    val cancellationPollLimit: Int = 0,
    val pivotLimit: Int = 0,
    val workLimit: Long = 0L,
    val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    val trackDegeneracy: Boolean = false,
)

internal class LpCapturedPremises(
    val vars: IntArray,
    val isUpper: BooleanArray,
    val thresholds: LongArray,
    val boolLits: IntArray,
) {
    fun matches(premises: LpRowPremises): Boolean = vars.contentEquals(premises.vars) &&
        isUpper.contentEquals(premises.isUpper) && thresholds.contentEquals(premises.thresholds) &&
        boolLits.contentEquals(premises.boolLits)

    fun copy(): LpCapturedPremises = LpCapturedPremises(
        vars.copyOf(),
        isUpper.copyOf(),
        thresholds.copyOf(),
        boolLits.copyOf(),
    )
}

// The CSC is independent of the Long placeholder CSC because a real-only coefficient can be absent from the integer
// view; both keep ascending rows. Exact values of shifted entries that rounded travel with the bits, so a replayed
// view certifies what its source did.
internal class LpCapturedDoubleView(
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colValBits: LongArray,
    val rhsBits: LongArray,
    val costBits: LongArray,
    val upperBits: LongArray,
    val hasUpper: BooleanArray,
    val objConstantBits: Long,
    val loShiftBits: LongArray,
    val inexactCoefficients: Boolean = false,
    val shifts: Lazy<LpExactShifts> = lazyOf(LpExactShifts.NONE),
) {
    fun copy(): LpCapturedDoubleView = LpCapturedDoubleView(
        colPtr.copyOf(),
        rowIdx.copyOf(),
        colValBits.copyOf(),
        rhsBits.copyOf(),
        costBits.copyOf(),
        upperBits.copyOf(),
        hasUpper.copyOf(),
        objConstantBits,
        loShiftBits.copyOf(),
        inexactCoefficients,
        shifts,
    )
}

internal class LpCapturedModel(
    val n: Int,
    val m: Int,
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colVal: LongArray,
    val rhs: LongArray,
    val cost: LongArray,
    val upper: LongArray,
    val hasUpper: BooleanArray,
    val loShift: LongArray,
    val objConstant: Long,
    val sense: Sense,
    val tag: IntArray,
    val rowGlobal: BooleanArray,
    val rowStrict: BooleanArray,
    val rowPremises: Array<LpCapturedPremises?>,
    val flippedRhs: LongArray,
    val probeClampedLo: BooleanArray,
    val probeClampedHi: BooleanArray,
    val colContinuous: BooleanArray,
    val numericAuthority: LpNumericAuthority,
    val doubleView: LpCapturedDoubleView?,
) {
    val integerAuthorityUnits: Long = colPtr.size.toLong() + rowIdx.size + colVal.size + rhs.size + cost.size +
        upper.size + hasUpper.size + loShift.size + tag.size + rowGlobal.size + rowStrict.size + rowPremises.size +
        flippedRhs.size + probeClampedLo.size + probeClampedHi.size + colContinuous.size + 2L +
        rowPremises.sumOf { (it?.vars?.size ?: 0).toLong() * 3L + (it?.boolLits?.size ?: 0) }

    // Integer Lagrangian data remains valid after an integrality flag changes; deductions check their own lattice.
    fun matchesIntegerAuthority(model: LpModel): Boolean = numericAuthority == LpNumericAuthority.LONG_EXACT &&
        model.exactState == null && model.doubleView == null && n == model.n && m == model.m &&
        matchesIntegerMatrix(model) && matchesIntegerVectors(model) && matchesIntegerSources(model)

    private fun matchesIntegerMatrix(model: LpModel): Boolean = colPtr.contentEquals(model.csc.colPtr) &&
        rowIdx.contentEquals(model.csc.rowIdx) && colVal.contentEquals(model.csc.colVal)

    private fun matchesIntegerVectors(model: LpModel): Boolean =
        rhs.contentEquals(model.rhs) && cost.contentEquals(model.cost) &&
            upper.contentEquals(model.upper) && hasUpper.contentEquals(model.hasUpper) &&
            loShift.contentEquals(model.loShift) && objConstant == model.objConstant && sense == model.sense &&
            flippedRhs.contentEquals(model.flippedRhs) && probeClampedLo.contentEquals(model.probeClampedLo) &&
            probeClampedHi.contentEquals(model.probeClampedHi)

    private fun matchesIntegerSources(model: LpModel): Boolean =
        tag.contentEquals(model.tag) && rowGlobal.contentEquals(model.rowGlobal) &&
            rowStrict.contentEquals(model.rowStrict) &&
            rowPremises.size == model.rowPremises.size && rowPremises.indices.all { row ->
                val before = rowPremises[row]
                val after = model.rowPremises[row]
                if (before == null) after == null else after != null && before.matches(after)
            }

    fun copy(): LpCapturedModel = LpCapturedModel(
        n,
        m,
        colPtr.copyOf(),
        rowIdx.copyOf(),
        colVal.copyOf(),
        rhs.copyOf(),
        cost.copyOf(),
        upper.copyOf(),
        hasUpper.copyOf(),
        loShift.copyOf(),
        objConstant,
        sense,
        tag.copyOf(),
        rowGlobal.copyOf(),
        rowStrict.copyOf(),
        Array(rowPremises.size) { rowPremises[it]?.copy() },
        flippedRhs.copyOf(),
        probeClampedLo.copyOf(),
        probeClampedHi.copyOf(),
        colContinuous.copyOf(),
        numericAuthority,
        doubleView?.copy(),
    )

    fun toModel(): LpModel {
        validate()
        val dv = doubleView?.let {
            LpDoubleView(
                it.colPtr.copyOf(),
                it.rowIdx.copyOf(),
                it.colValBits.toDoubleArray(),
                it.rhsBits.toDoubleArray(),
                it.costBits.toDoubleArray(),
                it.upperBits.toDoubleArray(),
                it.hasUpper.copyOf(),
                Double.fromBits(it.objConstantBits),
                it.loShiftBits.toDoubleArray(),
                it.inexactCoefficients,
                it.shifts,
            )
        }
        return LpModel(
            n = n,
            m = m,
            csc = Csc(colPtr.copyOf(), rowIdx.copyOf(), colVal.copyOf()),
            rhs = rhs.copyOf(),
            cost = cost.copyOf(),
            upper = upper.copyOf(),
            hasUpper = hasUpper.copyOf(),
            loShift = loShift.copyOf(),
            objConstant = objConstant,
            sense = sense,
            tag = tag.copyOf(),
            rowGlobal = rowGlobal.copyOf(),
            rowStrict = rowStrict.copyOf(),
            rowPremises = Array(rowPremises.size) { i ->
                rowPremises[i]?.let {
                    LpRowPremises(
                        it.vars.copyOf(),
                        it.isUpper.copyOf(),
                        it.thresholds.copyOf(),
                        it.boolLits.copyOf(),
                    )
                }
            },
            flippedRhs = flippedRhs.copyOf(),
            probeClampedLo = probeClampedLo.copyOf(),
            probeClampedHi = probeClampedHi.copyOf(),
            colContinuous = colContinuous.copyOf(),
            doubleView = dv,
        )
    }

    fun validate() {
        require(n >= 0 && m >= 0) { "negative LP dimensions $n x $m" }
        require(colPtr.size == n + 1) { "column pointer count ${colPtr.size} != ${n + 1}" }
        require(colPtr.isNotEmpty() && colPtr[0] == 0) { "column pointers must start at zero" }
        require((0 until colPtr.lastIndex).all { colPtr[it] <= colPtr[it + 1] }) {
            "column pointers are not monotone"
        }
        val nnz = colPtr.last()
        require(nnz >= 0 && rowIdx.size == nnz && colVal.size == nnz) { "sparse array lengths disagree" }
        require(rowIdx.all { it in 0 until m }) { "sparse row index outside 0 until $m" }
        requireStrictlyAscendingRows(colPtr, rowIdx, "Long")
        val numVars = checkedNumVars()
        require(rhs.size == m && flippedRhs.size == m) { "right-hand side length differs from $m" }
        require(cost.size == numVars && upper.size == numVars && hasUpper.size == numVars) {
            "column data length differs from $numVars"
        }
        require(loShift.size == n && tag.size == n) { "structural column metadata length differs from $n" }
        require(rowGlobal.size == m && rowStrict.size == m && rowPremises.size == m) {
            "row metadata length differs from $m"
        }
        require(probeClampedLo.size == n && probeClampedHi.size == n && colContinuous.size == n) {
            "bound/source metadata length differs from $n"
        }
        rowPremises.forEachIndexed { row, premise ->
            if (premise != null) {
                require(premise.vars.size == premise.isUpper.size && premise.vars.size == premise.thresholds.size) {
                    "row $row premise arrays disagree"
                }
                require(premise.vars.all { it >= 0 }) { "row $row has a negative source variable" }
            }
        }
        require((doubleView == null) == (numericAuthority == LpNumericAuthority.LONG_EXACT)) {
            "numeric authority does not match the double view"
        }
        require(doubleView != null || colContinuous.none { it }) {
            "continuous column flags require an authoritative double view"
        }
        require(doubleView != null || rowStrict.none { it }) { "strict rows require an authoritative double view" }
        doubleView?.validate(n, m, numVars)
    }

    private fun checkedNumVars(): Int {
        val count = n.toLong() + m.toLong()
        require(count <= Int.MAX_VALUE) { "LP variable count overflows Int" }
        return count.toInt()
    }

    companion object {
        fun capture(model: ExactLpModel): LpCapturedModel = capture(
            requireNotNull(model.toIntegerModel()) { "exact model is outside capture v1 authority" },
        )

        fun captureOrNull(model: LpModel): LpCapturedModel? {
            if (model.rowPremises.size != model.m) return null
            return try {
                capture(model).also { it.validate() }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun capture(model: LpModel): LpCapturedModel = snapshot(model).also { it.validate() }

        // Integer coefficients admit a Lagrangian over continuous columns without a replayable Long lattice.
        fun snapshotIntegerAuthority(model: LpModel): LpCapturedModel {
            require(model.doubleView == null)
            return snapshot(model)
        }

        private fun snapshot(model: LpModel): LpCapturedModel {
            require(model.exactState == null) { "exact working state requires exact capture" }
            val dv = model.doubleView?.let {
                LpCapturedDoubleView(
                    it.colPtr.copyOf(),
                    it.rowIdx.copyOf(),
                    it.colVal.toRawBitsArray(),
                    it.rhs.toRawBitsArray(),
                    it.cost.toRawBitsArray(),
                    it.upper.toRawBitsArray(),
                    it.hasUpper.copyOf(),
                    it.objConstant.toRawBits(),
                    it.loShift.toRawBitsArray(),
                    it.inexactCoefficients,
                    it.shifts,
                )
            }
            val authority = if (dv == null) {
                LpNumericAuthority.LONG_EXACT
            } else {
                LpNumericAuthority.IEEE754_BITS_SOURCE_RATIONAL_ABSENT
            }
            return LpCapturedModel(
                model.n,
                model.m,
                model.csc.colPtr.copyOf(),
                model.csc.rowIdx.copyOf(),
                model.csc.colVal.copyOf(),
                model.rhs.copyOf(),
                model.cost.copyOf(),
                model.upper.copyOf(),
                model.hasUpper.copyOf(),
                model.loShift.copyOf(),
                model.objConstant,
                model.sense,
                model.tag.copyOf(),
                model.rowGlobal.copyOf(),
                model.rowStrict.copyOf(),
                Array(model.m) { row ->
                    model.rowPremises[row]?.let {
                        LpCapturedPremises(
                            it.vars.copyOf(),
                            it.isUpper.copyOf(),
                            it.thresholds.copyOf(),
                            it.boolLits.copyOf(),
                        )
                    }
                },
                model.flippedRhs.copyOf(),
                model.probeClampedLo.copyOf(),
                model.probeClampedHi.copyOf(),
                model.colContinuous.copyOf(),
                authority,
                dv,
            )
        }
    }
}

private fun LpCapturedDoubleView.validate(n: Int, m: Int, numVars: Int) {
    require(colPtr.size == n + 1 && colPtr.isNotEmpty() && colPtr[0] == 0) {
        "double column pointers do not describe $n columns"
    }
    require((0 until colPtr.lastIndex).all { colPtr[it] <= colPtr[it + 1] }) {
        "double column pointers are not monotone"
    }
    require(colPtr.last() >= 0 && rowIdx.size == colPtr.last() && colValBits.size == rowIdx.size) {
        "double sparse arrays disagree"
    }
    require(rowIdx.all { it in 0 until m }) { "double sparse row index outside 0 until $m" }
    requireStrictlyAscendingRows(colPtr, rowIdx, "double")
    require(rhsBits.size == m) { "double right-hand side length differs from $m" }
    require(costBits.size == numVars && upperBits.size == numVars && hasUpper.size == numVars) {
        "double column data length differs from $numVars"
    }
    require(loShiftBits.size == n) { "double shift length differs from $n" }
    if (shifts.isInitialized()) {
        val exact = shifts.value
        require(exact.rows.all { it in 0 until m } && exact.columns.all { it in 0 until n }) {
            "exact shift index outside the model"
        }
    }
}

internal class LpCapturedBasis(val basicVars: IntArray, val statuses: IntArray) {
    fun copy(): LpCapturedBasis = LpCapturedBasis(basicVars.copyOf(), statuses.copyOf())

    fun toBasis(hasUpper: BooleanArray, rows: Int): Basis {
        val numVars = hasUpper.size
        require(basicVars.size == rows) { "warm basis has ${basicVars.size} rows, expected $rows" }
        require(statuses.size == numVars) { "warm status count ${statuses.size}, expected $numVars" }
        require(basicVars.all { it in 0 until numVars }) { "warm basis column outside 0 until $numVars" }
        val basicSeen = BooleanArray(numVars)
        for (column in basicVars) {
            require(!basicSeen[column]) { "warm basis repeats column $column" }
            basicSeen[column] = true
        }
        val decoded = Array(numVars) { i ->
            capturedStatusFromWireCode(statuses[i])
        }
        require(basicVars.all { decoded[it] == VarStatus.BASIC }) { "warm basic column is not BASIC" }
        require(decoded.count { it == VarStatus.BASIC } == rows) { "warm status vector has the wrong basis size" }
        require(decoded.indices.all { decoded[it] != VarStatus.AT_UPPER || hasUpper[it] }) {
            "warm basis seats an unbounded column at its upper bound"
        }
        return Basis(basicVars.copyOf(), decoded)
    }

    companion object {
        fun capture(basis: Basis): LpCapturedBasis {
            require(basis.captureEligible) { "basis declaration is outside capture v1 statuses" }
            return LpCapturedBasis(
                basis.basicVars.copyOf(),
                IntArray(basis.status.size) { capturedStatusWireCode(basis.status[it]) },
            )
        }
    }
}

internal sealed class LpReplayEvent(open val eventVersion: Int) {
    class Solve(val warm: LpCapturedBasis? = null, override val eventVersion: Int = LP_EVENT_VERSION) :
        LpReplayEvent(eventVersion)

    class SolvePrimal(val warm: LpCapturedBasis? = null, override val eventVersion: Int = LP_EVENT_VERSION) :
        LpReplayEvent(eventVersion)

    class Rebind(
        val lo: LongArray,
        val hi: LongArray,
        // Null preserves the lifetime token and its consumed poll count.
        val cancellationPollLimit: Int? = null,
        override val eventVersion: Int = LP_EVENT_VERSION,
    ) : LpReplayEvent(eventVersion)

    class ResolveBounds(override val eventVersion: Int = LP_EVENT_VERSION) : LpReplayEvent(eventVersion)

    class ResolveGated(val enforced: BooleanArray, override val eventVersion: Int = LP_EVENT_VERSION) :
        LpReplayEvent(eventVersion)

    class BoundWrite(
        val column: Int,
        val upperSide: Boolean,
        val value: Long,
        val strict: Boolean,
        val sourceLiteral: Int,
        override val eventVersion: Int = LP_EVENT_VERSION,
    ) : LpReplayEvent(eventVersion)

    class Push(val level: Int, override val eventVersion: Int = LP_EVENT_VERSION) : LpReplayEvent(eventVersion)
    class Pop(val level: Int, override val eventVersion: Int = LP_EVENT_VERSION) : LpReplayEvent(eventVersion)

    class RowActivation(val row: Int, val active: Boolean, override val eventVersion: Int = LP_EVENT_VERSION) :
        LpReplayEvent(eventVersion)

    class RowsAppended(val successor: LpCapturedModel, override val eventVersion: Int = LP_EVENT_VERSION) :
        LpReplayEvent(eventVersion)

    class ObjectiveSwap(
        val cost: LongArray,
        val doubleCostBits: LongArray?,
        val objConstant: Long,
        val doubleObjConstantBits: Long?,
        override val eventVersion: Int = LP_EVENT_VERSION,
    ) : LpReplayEvent(eventVersion)

    class Epoch(
        val epoch: Long,
        val matrixRevision: Long,
        val boundRevision: Long,
        val objectiveRevision: Long,
        override val eventVersion: Int = LP_EVENT_VERSION,
    ) : LpReplayEvent(eventVersion)
}

internal class LpCapture internal constructor(
    val version: Int,
    val settings: LpReplaySettings,
    val model: LpCapturedModel,
    val events: List<LpReplayEvent>,
) {
    // Every nested array was copied by [capture], so later mutations cannot alter these bytes.
    fun encode(): ByteArray {
        validateFormat()
        val out = CaptureWriter()
        out.bytes(CAPTURE_MAGIC)
        out.int(version)
        out.settings(settings)
        out.model(model)
        out.int(events.size)
        events.forEach(out::event)
        return out.toByteArray()
    }

    fun validateFormat() {
        require(version == LP_CAPTURE_VERSION) { "unsupported LP capture version $version" }
        require(settings.label.isNotEmpty()) { "capture label is empty" }
        require(settings.cancellationPollLimit >= 0) { "negative cancellation poll limit" }
        require(settings.pivotLimit >= 0 && settings.workLimit >= 0L) { "negative solve budget" }
        require(settings.refactorUpdateLimit > 0) { "non-positive refactor update limit" }
        model.validate()
        events.forEachIndexed { index, event ->
            require(event.eventVersion == LP_EVENT_VERSION) {
                "unsupported LP event version ${event.eventVersion} at event $index"
            }
            validateEventShape(event, model, index)
        }
    }

    companion object {
        // Callers record only events exposed by their engine seam. The capture holds no factorization snapshot or
        // other solver-private cache.
        fun capture(model: ExactLpModel, settings: LpReplaySettings, events: List<LpReplayEvent>): LpCapture = capture(
            requireNotNull(model.toIntegerModel()) { "exact model is outside capture v1 authority" },
            settings,
            events,
        )

        fun capture(model: LpModel, settings: LpReplaySettings, events: List<LpReplayEvent>): LpCapture = LpCapture(
            LP_CAPTURE_VERSION,
            settings.copy(),
            LpCapturedModel.capture(model),
            events.map(LpReplayEvent::copyForCapture),
        ).also { it.validateFormat() }

        // Validates the whole capture first, so malformed input never partially executes an event prefix.
        fun decode(bytes: ByteArray): LpCapture {
            val input = CaptureReader(bytes)
            require(input.bytes(CAPTURE_MAGIC.size).contentEquals(CAPTURE_MAGIC)) { "invalid LP capture magic" }
            val version = input.int()
            require(version == LP_CAPTURE_VERSION) { "unsupported LP capture version $version" }
            val capture = LpCapture(version, input.settings(), input.model(), input.events())
            require(input.exhausted()) { "trailing bytes after LP capture" }
            capture.validateFormat()
            return capture
        }
    }
}

private fun LpReplaySettings.copy(): LpReplaySettings = LpReplaySettings(
    label,
    seed,
    solverKind,
    componentSplit,
    cancellationPollLimit,
    pivotLimit,
    workLimit,
    refactorUpdateLimit,
    trackDegeneracy,
)

private fun LpReplayEvent.copyForCapture(): LpReplayEvent = when (this) {
    is LpReplayEvent.Solve -> LpReplayEvent.Solve(warm?.copy(), eventVersion)

    is LpReplayEvent.SolvePrimal -> LpReplayEvent.SolvePrimal(warm?.copy(), eventVersion)

    is LpReplayEvent.Rebind -> LpReplayEvent.Rebind(lo.copyOf(), hi.copyOf(), cancellationPollLimit, eventVersion)

    is LpReplayEvent.ResolveBounds -> LpReplayEvent.ResolveBounds(eventVersion)

    is LpReplayEvent.ResolveGated -> LpReplayEvent.ResolveGated(enforced.copyOf(), eventVersion)

    is LpReplayEvent.BoundWrite -> LpReplayEvent.BoundWrite(
        column,
        upperSide,
        value,
        strict,
        sourceLiteral,
        eventVersion,
    )

    is LpReplayEvent.Push -> LpReplayEvent.Push(level, eventVersion)

    is LpReplayEvent.Pop -> LpReplayEvent.Pop(level, eventVersion)

    is LpReplayEvent.RowActivation -> LpReplayEvent.RowActivation(row, active, eventVersion)

    is LpReplayEvent.RowsAppended -> LpReplayEvent.RowsAppended(successor.copy(), eventVersion)

    is LpReplayEvent.ObjectiveSwap -> LpReplayEvent.ObjectiveSwap(
        cost.copyOf(),
        doubleCostBits?.copyOf(),
        objConstant,
        doubleObjConstantBits,
        eventVersion,
    )

    is LpReplayEvent.Epoch -> LpReplayEvent.Epoch(
        epoch,
        matrixRevision,
        boundRevision,
        objectiveRevision,
        eventVersion,
    )
}

private fun validateEventShape(event: LpReplayEvent, model: LpCapturedModel, index: Int) {
    val numVars = model.cost.size
    when (event) {
        is LpReplayEvent.Solve -> event.warm?.toBasis(model.hasUpper, model.m)

        is LpReplayEvent.SolvePrimal -> event.warm?.toBasis(model.hasUpper, model.m)

        is LpReplayEvent.Rebind -> {
            require(
                event.lo.size == model.n && event.hi.size == model.n,
            ) { "event $index rebind arity differs from ${model.n}" }
            require(
                event.lo.indices.all { event.lo[it] <= event.hi[it] },
            ) { "event $index contains an empty rebind bound" }
            require(event.cancellationPollLimit == null || event.cancellationPollLimit >= 0) {
                "event $index has a negative cancellation poll limit"
            }
        }

        is LpReplayEvent.ResolveGated -> require(event.enforced.size == model.m) {
            "event $index gate count differs from ${model.m}"
        }

        is LpReplayEvent.BoundWrite -> require(event.column in 0 until numVars) {
            "event $index bound column is outside the model"
        }

        is LpReplayEvent.Push -> require(event.level >= 0) { "event $index has a negative push level" }

        is LpReplayEvent.Pop -> require(event.level >= 0) { "event $index has a negative pop level" }

        is LpReplayEvent.RowActivation -> require(event.row in 0 until model.m) {
            "event $index row is outside the model"
        }

        is LpReplayEvent.RowsAppended -> event.successor.validate()

        is LpReplayEvent.ObjectiveSwap -> {
            require(event.cost.size == numVars) { "event $index objective arity differs from the model" }
            require(event.doubleCostBits == null || event.doubleCostBits.size == numVars) {
                "event $index double objective arity differs from the model"
            }
        }

        is LpReplayEvent.Epoch, is LpReplayEvent.ResolveBounds -> Unit
    }
}

private val CAPTURE_MAGIC: ByteArray = byteArrayOf(0x4b, 0x4c, 0x50, 0x43, 0x41, 0x50, 0x54, 0x52)

private fun requireStrictlyAscendingRows(colPtr: IntArray, rowIdx: IntArray, label: String) {
    for (column in 0 until colPtr.lastIndex) {
        for (position in colPtr[column] + 1 until colPtr[column + 1]) {
            require(rowIdx[position - 1] < rowIdx[position]) {
                "$label CSC row indices are not strictly ascending in column $column"
            }
        }
    }
}

private fun DoubleArray.toRawBitsArray(): LongArray = LongArray(size) { this[it].toRawBits() }
private fun LongArray.toDoubleArray(): DoubleArray = DoubleArray(size) { Double.fromBits(this[it]) }

internal const val LP_EXACT_CAPTURE_VERSION: Int = 4
internal const val LP_EXACT_EVENT_VERSION: Int = 2

internal sealed class LpExactReplayEvent(val eventVersion: Int = LP_EXACT_EVENT_VERSION) {
    class Append(val row: LpScopedRow, val scoped: Boolean) : LpExactReplayEvent()
    class Deactivate(val id: Long) : LpExactReplayEvent()
    class Suspend(ids: Set<Long>) : LpExactReplayEvent() {
        val ids: Set<Long> = ids.toSet()
    }
    class Extend(
        columns: List<LpStructuralColumn>,
        rows: List<LpScopedRow>,
        val scoped: Boolean,
        permanentRows: Set<Long> = emptySet(),
    ) : LpExactReplayEvent() {
        val columns: List<LpStructuralColumn> = columns.toList()
        val rows: List<LpScopedRow> = rows.toList()
        val permanentRows: Set<Long> = permanentRows.toSet()
    }
    class Compact : LpExactReplayEvent()
    class CompactColumns(columns: List<Int>) : LpExactReplayEvent() {
        val columns: List<Int> = columns.toList()
    }
    class Push : LpExactReplayEvent()
    class Assert(val column: Int, val upper: Boolean, val side: ExactLpSide, val witness: Long) : LpExactReplayEvent()
    class Pop(val targetDepth: Int) : LpExactReplayEvent()
    class Objective(val objective: ExactLpObjective) : LpExactReplayEvent()
    class Recenter(origins: List<ExactLpNumber>) : LpExactReplayEvent() {
        val origins: List<ExactLpNumber> = origins.toList()
    }
    class Solve(warm: Basis? = null) : LpExactReplayEvent() {
        private val basis = warm?.let { Basis(it.basicVars.copyOf(), it.status.copyOf(), it.captureEligible) }
        val warm: Basis? get() = basis?.let { Basis(it.basicVars.copyOf(), it.status.copyOf(), it.captureEligible) }
    }
}

internal class LpExactCapture private constructor(
    val version: Int,
    val settings: LpReplaySettings,
    val initialState: LpExactState,
    events: List<LpExactReplayEvent>,
    val maxRetainedRows: Int,
) {
    val model: ExactLpModel get() = initialState.baseModel
    val events: List<LpExactReplayEvent> = events.toList()

    fun encode(): ByteArray {
        validateFormat()
        return CaptureWriter().apply {
            bytes(EXACT_CAPTURE_MAGIC)
            int(version)
            settings(settings)
            int(maxRetainedRows)
            exactState(initialState)
            int(events.size)
            events.forEach { exactEvent(it) }
        }.toByteArray()
    }

    fun encodeWithin(valueLimit: Int, byteLimit: Int, cancellation: ExecutionControl): ByteArray? {
        require(valueLimit >= 0 && byteLimit >= 0)
        if (cancellation() || events.size > valueLimit) return null
        validateFormat()
        return try {
            CaptureWriter(valueLimit, byteLimit, cancellation).apply {
                bytes(EXACT_CAPTURE_MAGIC)
                int(version)
                settings(settings)
                int(maxRetainedRows)
                exactState(initialState)
                int(events.size)
                events.forEach { exactEvent(it) }
            }.toByteArray()
        } catch (_: CaptureBudgetExceeded) {
            null
        }
    }

    fun validateFormat() {
        require(version == LP_EXACT_CAPTURE_VERSION) { "unsupported exact LP capture version $version" }
        require(settings.label.isNotEmpty() && settings.cancellationPollLimit >= 0)
        require(settings.pivotLimit >= 0 && settings.workLimit >= 0L && settings.refactorUpdateLimit > 0)
        require(maxRetainedRows >= 0 && initialState.model.m <= maxRetainedRows)
        require(events.all { it.eventVersion == LP_EXACT_EVENT_VERSION }) { "unsupported exact LP event version" }
    }

    companion object {
        fun capture(model: ExactLpModel, settings: LpReplaySettings, events: List<LpExactReplayEvent>): LpExactCapture =
            capture(LpExactState(model), settings, events)

        fun capture(
            state: LpExactState,
            settings: LpReplaySettings,
            events: List<LpExactReplayEvent>,
            maxRetainedRows: Int = Int.MAX_VALUE,
        ): LpExactCapture = LpExactCapture(
            LP_EXACT_CAPTURE_VERSION,
            settings.copy(),
            state,
            events,
            maxRetainedRows,
        ).also { it.validateFormat() }

        fun decode(bytes: ByteArray): LpExactCapture {
            val input = CaptureReader(bytes)
            require(input.bytes(EXACT_CAPTURE_MAGIC.size).contentEquals(EXACT_CAPTURE_MAGIC)) {
                "invalid exact LP capture magic"
            }
            val version = input.int()
            require(version in 2..LP_EXACT_CAPTURE_VERSION) { "unsupported exact LP capture version $version" }
            val settings = input.settings()
            val maxRetainedRows = input.int()
            val state = input.exactState(version)
            val events = List(input.collectionCount()) { input.exactEvent(version) }
            require(input.exhausted()) { "trailing bytes after exact LP capture" }
            return LpExactCapture(LP_EXACT_CAPTURE_VERSION, settings, state, events, maxRetainedRows)
                .also { it.validateFormat() }
        }

        fun stateKey(state: LpExactState): ByteArray? = try {
            CaptureWriter(valueLimit = 4096, byteLimit = 64 * 1024).apply {
                bytes(EXACT_CAPTURE_MAGIC)
                int(LP_EXACT_CAPTURE_VERSION)
                exactState(state)
                exactModel(state.model)
            }.toByteArray()
        } catch (_: CaptureBudgetExceeded) {
            null
        }
    }
}

private val EXACT_CAPTURE_MAGIC: ByteArray = byteArrayOf(0x4b, 0x4c, 0x50, 0x45, 0x58, 0x41, 0x43, 0x54)
