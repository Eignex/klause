package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.signum

internal const val LP_CAPTURE_VERSION: Int = 2
internal const val LP_EVENT_VERSION: Int = 1

/** Which captured numeric view is authoritative. A double view preserves the IEEE bits presented to
 * the engine; it deliberately does not claim to reconstruct source decimal or rational input. */
internal enum class LpNumericAuthority(val wireCode: Int) {
    LONG_EXACT(1),
    IEEE754_BITS_SOURCE_RATIONAL_ABSENT(2),
}

internal enum class LpReplaySolverKind(val wireCode: Int) {
    GENERAL(1),
    TABLEAU(2),
    PERSISTENT(3),
}

/** Fixed engine capabilities and budgets for one solver lifetime. A replay creates exactly one engine;
 * persistent events reuse its basis/factors, while a portable warm start contains only discrete basis
 * columns and statuses. [cancellationPollLimit] is the initial deterministic token, zero means never
 * cancel, and a rebind replaces it only when that event carries an explicit override. */
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

/** Authoritative IEEE-754 input. Its CSC is independent of the Long placeholder CSC because a
 * real-only coefficient can be absent from the integer view; both stores preserve ascending rows. The exact
 * values of shifted entries that rounded travel with the bits, so a replayed view certifies what its source did. */
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
            requireNotNull(model.toLegacy()) { "exact model is outside capture v1 authority" },
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
            statusFromWireCode(statuses[i])
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
                IntArray(basis.status.size) { statusWireCode(basis.status[it]) },
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
        /** Optional replacement cancellation budget for this and later events; null preserves the
         * lifetime token and its consumed poll count. */
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
    /** Encode the complete snapshot and event stream. Every nested array was copied by [capture], so
     * later model, basis, premise, objective or event-buffer mutations cannot alter these bytes. */
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
        /** Freeze [model] and caller-recorded [events] at this call. Wave 0.4 intentionally has no
         * instrumented producer: callers record only events exposed by their public engine seam. The
         * capture contains no factorization snapshot or other solver-private cache. */
        fun capture(model: ExactLpModel, settings: LpReplaySettings, events: List<LpReplayEvent>): LpCapture = capture(
            requireNotNull(model.toLegacy()) { "exact model is outside capture v1 authority" },
            settings,
            events,
        )

        fun capture(model: LpModel, settings: LpReplaySettings, events: List<LpReplayEvent>): LpCapture = LpCapture(
            LP_CAPTURE_VERSION,
            settings.copy(),
            LpCapturedModel.capture(model),
            events.map(LpReplayEvent::copyForCapture),
        ).also { it.validateFormat() }

        /** Decode and validate a whole capture before returning it; malformed or unknown input never
         * reaches replay and therefore cannot partially execute an event prefix. */
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

private class CaptureWriter(private val valueLimit: Int = Int.MAX_VALUE, private val byteLimit: Int = Int.MAX_VALUE) {
    private var data = ByteArray(256)
    private var size = 0
    private var values = 0
    private val rationalStrings = HashMap<BigInt, String>()

    fun toByteArray(): ByteArray = data.copyOf(size)
    fun bytes(value: ByteArray) = value.forEach { byte(it.toInt()) }
    fun bool(value: Boolean) {
        countValue()
        byte(if (value) 1 else 0)
    }

    fun int(value: Int) {
        countValue()
        long(value.toLong(), 4)
    }

    fun long(value: Long) {
        countValue()
        long(value, 8)
    }

    private fun countValue() {
        if (values == valueLimit) throw CaptureBudgetExceeded()
        values++
    }

    fun string(value: String) {
        val bytes = value.encodeToByteArray()
        int(bytes.size)
        bytes(bytes)
    }

    fun rationalPart(value: BigInt) {
        string(rationalStrings.getOrPut(value) { value.toString() })
    }

    fun ints(value: IntArray) {
        int(value.size)
        value.forEach(::int)
    }

    fun longs(value: LongArray) {
        int(value.size)
        value.forEach(::long)
    }

    fun bools(value: BooleanArray) {
        int(value.size)
        value.forEach(::bool)
    }

    fun settings(value: LpReplaySettings) {
        string(value.label)
        long(value.seed)
        int(value.solverKind.wireCode)
        bool(value.componentSplit)
        int(value.cancellationPollLimit)
        int(value.pivotLimit)
        long(value.workLimit)
        int(value.refactorUpdateLimit)
        bool(value.trackDegeneracy)
    }

    fun model(value: LpCapturedModel) {
        int(value.n)
        int(value.m)
        ints(value.colPtr)
        ints(value.rowIdx)
        longs(value.colVal)
        longs(value.rhs)
        longs(value.cost)
        longs(value.upper)
        bools(value.hasUpper)
        longs(value.loShift)
        long(value.objConstant)
        int(senseWireCode(value.sense))
        ints(value.tag)
        bools(value.rowGlobal)
        bools(value.rowStrict)
        int(value.rowPremises.size)
        value.rowPremises.forEach { premise ->
            bool(premise != null)
            if (premise != null) {
                ints(premise.vars)
                bools(premise.isUpper)
                longs(premise.thresholds)
                ints(premise.boolLits)
            }
        }
        longs(value.flippedRhs)
        bools(value.probeClampedLo)
        bools(value.probeClampedHi)
        bools(value.colContinuous)
        int(value.numericAuthority.wireCode)
        bool(value.doubleView != null)
        value.doubleView?.let {
            ints(it.colPtr)
            ints(it.rowIdx)
            longs(it.colValBits)
            longs(it.rhsBits)
            longs(it.costBits)
            longs(it.upperBits)
            bools(it.hasUpper)
            long(it.objConstantBits)
            longs(it.loShiftBits)
            bool(it.inexactCoefficients)
            exactShifts(it.shifts.value)
        }
    }

    fun basis(value: LpCapturedBasis?) {
        bool(value != null)
        if (value != null) {
            ints(value.basicVars)
            ints(value.statuses)
        }
    }

    fun event(value: LpReplayEvent) {
        int(eventId(value))
        int(value.eventVersion)
        when (value) {
            is LpReplayEvent.Solve -> basis(value.warm)

            is LpReplayEvent.SolvePrimal -> basis(value.warm)

            is LpReplayEvent.Rebind -> {
                longs(value.lo)
                longs(value.hi)
                nullableInt(value.cancellationPollLimit)
            }

            is LpReplayEvent.ResolveBounds -> Unit

            is LpReplayEvent.ResolveGated -> bools(value.enforced)

            is LpReplayEvent.BoundWrite -> {
                int(value.column)
                bool(value.upperSide)
                long(value.value)
                bool(value.strict)
                int(value.sourceLiteral)
            }

            is LpReplayEvent.Push -> int(value.level)

            is LpReplayEvent.Pop -> int(value.level)

            is LpReplayEvent.RowActivation -> {
                int(value.row)
                bool(value.active)
            }

            is LpReplayEvent.RowsAppended -> model(value.successor)

            is LpReplayEvent.ObjectiveSwap -> {
                longs(value.cost)
                nullableLongs(value.doubleCostBits)
                long(value.objConstant)
                nullableLong(value.doubleObjConstantBits)
            }

            is LpReplayEvent.Epoch -> {
                long(value.epoch)
                long(value.matrixRevision)
                long(value.boundRevision)
                long(value.objectiveRevision)
            }
        }
    }

    private fun nullableLong(value: Long?) {
        bool(value != null)
        if (value != null) long(value)
    }

    private fun nullableInt(value: Int?) {
        bool(value != null)
        if (value != null) int(value)
    }

    private fun nullableLongs(value: LongArray?) {
        bool(value != null)
        if (value != null) longs(value)
    }

    private fun byte(value: Int) {
        ensure(1)
        data[size++] = value.toByte()
    }

    private fun long(value: Long, bytes: Int) {
        ensure(bytes)
        for (shift in (bytes - 1) * 8 downTo 0 step 8) data[size++] = (value ushr shift).toByte()
    }

    private fun ensure(extra: Int) {
        val required = size.toLong() + extra.toLong()
        if (required > byteLimit) throw CaptureBudgetExceeded()
        require(required <= Int.MAX_VALUE) { "LP capture exceeds the maximum encodable size" }
        if (required <= data.size.toLong()) return
        var next = data.size
        while (next.toLong() < required) next = minOf(Int.MAX_VALUE.toLong(), next.toLong() * 2L).toInt()
        data = data.copyOf(next)
    }
}

private class CaptureReader(private val data: ByteArray) {
    private var position = 0

    fun exhausted(): Boolean = position == data.size

    fun bytes(size: Int): ByteArray {
        requireAvailable(size)
        return data.copyOfRange(position, position + size).also { position += size }
    }

    fun bool(): Boolean = when (val value = byte()) {
        0 -> false
        1 -> true
        else -> error("invalid Boolean value $value")
    }

    fun int(): Int = long(4).toInt()
    fun long(): Long = long(8)
    fun string(): String = bytes(count()).decodeToString(throwOnInvalidSequence = true)

    fun ints(): IntArray {
        val size = count()
        requireItems(size, Int.SIZE_BYTES)
        return IntArray(size) { int() }
    }

    fun longs(): LongArray {
        val size = count()
        requireItems(size, Long.SIZE_BYTES)
        return LongArray(size) { long() }
    }

    fun bools(): BooleanArray {
        val size = count()
        requireItems(size, 1)
        return BooleanArray(size) { bool() }
    }

    fun collectionCount(): Int = count().also { requireItems(it, 1) }

    fun settings(): LpReplaySettings = LpReplaySettings(
        string(),
        long(),
        solverKindFromWireCode(int()),
        bool(),
        int(),
        int(),
        long(),
        int(),
        bool(),
    )

    fun model(): LpCapturedModel {
        val n = int()
        val m = int()
        val colPtr = ints()
        val rowIdx = ints()
        val colVal = longs()
        val rhs = longs()
        val cost = longs()
        val upper = longs()
        val hasUpper = bools()
        val loShift = longs()
        val objConstant = long()
        val sense = senseFromWireCode(int())
        val tag = ints()
        val rowGlobal = bools()
        val rowStrict = bools()
        val premiseCount = count()
        requireItems(premiseCount, 1)
        val premises = Array<LpCapturedPremises?>(premiseCount) {
            if (bool()) LpCapturedPremises(ints(), bools(), longs(), ints()) else null
        }
        val flippedRhs = longs()
        val probeLo = bools()
        val probeHi = bools()
        val continuous = bools()
        val authority = numericAuthorityFromWireCode(int())
        val dv = if (bool()) {
            LpCapturedDoubleView(
                ints(), ints(), longs(), longs(), longs(), longs(), bools(), long(), longs(), bool(),
                lazyOf(exactShifts()),
            )
        } else {
            null
        }
        return LpCapturedModel(
            n, m, colPtr, rowIdx, colVal, rhs, cost, upper, hasUpper, loShift, objConstant, sense, tag,
            rowGlobal, rowStrict, premises, flippedRhs, probeLo, probeHi, continuous, authority, dv,
        )
    }

    fun events(): List<LpReplayEvent> {
        val size = count()
        requireItems(size, 2 * Int.SIZE_BYTES)
        return List(size) {
            val id = int()
            val version = int()
            require(version == LP_EVENT_VERSION) { "unsupported LP event version $version at event $it" }
            when (id) {
                1 -> LpReplayEvent.Solve(basis(), version)
                2 -> LpReplayEvent.SolvePrimal(basis(), version)
                3 -> LpReplayEvent.Rebind(longs(), longs(), nullableInt(), version)
                4 -> LpReplayEvent.ResolveBounds(version)
                5 -> LpReplayEvent.ResolveGated(bools(), version)
                6 -> LpReplayEvent.BoundWrite(int(), bool(), long(), bool(), int(), version)
                7 -> LpReplayEvent.Push(int(), version)
                8 -> LpReplayEvent.Pop(int(), version)
                9 -> LpReplayEvent.RowActivation(int(), bool(), version)
                10 -> LpReplayEvent.RowsAppended(model(), version)
                11 -> LpReplayEvent.ObjectiveSwap(longs(), nullableLongs(), long(), nullableLong(), version)
                12 -> LpReplayEvent.Epoch(long(), long(), long(), long(), version)
                else -> error("unknown LP event type $id at event $it")
            }
        }
    }

    private fun basis(): LpCapturedBasis? = if (bool()) LpCapturedBasis(ints(), ints()) else null
    private fun nullableInt(): Int? = if (bool()) int() else null
    private fun nullableLong(): Long? = if (bool()) long() else null
    private fun nullableLongs(): LongArray? = if (bool()) longs() else null

    private fun count(): Int {
        val value = int()
        require(value >= 0) { "invalid capture item count $value" }
        return value
    }

    private fun requireItems(count: Int, bytesPerItem: Int) {
        val bytes = count.toLong() * bytesPerItem.toLong()
        require(bytes <= Int.MAX_VALUE && bytes <= data.size.toLong() - position) {
            "truncated LP capture at byte $position"
        }
    }

    private fun byte(): Int {
        requireAvailable(1)
        return data[position++].toInt() and 0xff
    }

    private fun long(bytes: Int): Long {
        requireAvailable(bytes)
        var value = 0L
        repeat(bytes) { value = (value shl 8) or byte().toLong() }
        return value
    }

    private fun requireAvailable(size: Int) {
        require(size >= 0 && position <= data.size - size) { "truncated LP capture at byte $position" }
    }
}

private fun solverKindFromWireCode(code: Int): LpReplaySolverKind =
    LpReplaySolverKind.entries.firstOrNull { it.wireCode == code } ?: error("unknown solver kind $code")

private fun numericAuthorityFromWireCode(code: Int): LpNumericAuthority =
    LpNumericAuthority.entries.firstOrNull { it.wireCode == code } ?: error("unknown numeric authority $code")

private fun senseWireCode(sense: Sense): Int = when (sense) {
    Sense.MINIMIZE -> 1
    Sense.MAXIMIZE -> 2
}

private fun senseFromWireCode(code: Int): Sense = when (code) {
    1 -> Sense.MINIMIZE
    2 -> Sense.MAXIMIZE
    else -> error("unknown objective sense $code")
}

private fun statusWireCode(status: VarStatus): Int = when (status) {
    VarStatus.BASIC -> 1
    VarStatus.AT_LOWER -> 2
    VarStatus.AT_UPPER -> 3
    VarStatus.FREE, VarStatus.FIXED -> throw IllegalArgumentException("native status requires exact capture")
}

private fun statusFromWireCode(code: Int): VarStatus = when (code) {
    1 -> VarStatus.BASIC
    2 -> VarStatus.AT_LOWER
    3 -> VarStatus.AT_UPPER
    else -> error("unknown variable status $code")
}

private fun requireStrictlyAscendingRows(colPtr: IntArray, rowIdx: IntArray, label: String) {
    for (column in 0 until colPtr.lastIndex) {
        for (position in colPtr[column] + 1 until colPtr[column + 1]) {
            require(rowIdx[position - 1] < rowIdx[position]) {
                "$label CSC row indices are not strictly ascending in column $column"
            }
        }
    }
}

private fun eventId(event: LpReplayEvent): Int = when (event) {
    is LpReplayEvent.Solve -> 1
    is LpReplayEvent.SolvePrimal -> 2
    is LpReplayEvent.Rebind -> 3
    is LpReplayEvent.ResolveBounds -> 4
    is LpReplayEvent.ResolveGated -> 5
    is LpReplayEvent.BoundWrite -> 6
    is LpReplayEvent.Push -> 7
    is LpReplayEvent.Pop -> 8
    is LpReplayEvent.RowActivation -> 9
    is LpReplayEvent.RowsAppended -> 10
    is LpReplayEvent.ObjectiveSwap -> 11
    is LpReplayEvent.Epoch -> 12
}

private fun DoubleArray.toRawBitsArray(): LongArray = LongArray(size) { this[it].toRawBits() }
private fun LongArray.toDoubleArray(): DoubleArray = DoubleArray(size) { Double.fromBits(this[it]) }

internal const val LP_EXACT_CAPTURE_VERSION: Int = 3
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

private fun CaptureWriter.exactState(state: LpExactState) {
    exactModel(state.baseModel)
    long(state.matrixRevision)
    long(state.boundRevision)
    long(state.objectiveRevision)
    long(state.popRevision)
    long(state.rowRevision)
    long(state.rows.lastId)
    int(state.rows.size)
    state.rows.entries().forEach {
        long(it.id)
        int(it.depth ?: -1)
        bool(it.active)
        int(it.suspendedAt ?: -1)
    }
    ints(state.scopes.toIntArray())
    ints(state.changedColumns.toIntArray())
    int(state.assertions.size)
    state.assertions.forEach {
        int(it.column)
        bool(it.upper)
        exactSide(it.side)
        long(it.witness)
        int(it.depth)
    }
}

private fun CaptureReader.exactState(version: Int): LpExactState {
    val model = exactModel()
    val matrixRevision = long()
    val boundRevision = long()
    val objectiveRevision = long()
    val popRevision = long()
    val rowRevision = long()
    val lastId = long()
    val rows = List(collectionCount()) {
        val id = long()
        val depth = int()
        require(depth >= -1) { "invalid row lifetime" }
        val active = bool()
        val suspendedAt = if (version >= 3) int() else -1
        require(suspendedAt >= -1) { "invalid row suspension lifetime" }
        LpRowIdentity(id, depth.takeIf { it >= 0 }, active, suspendedAt.takeIf { it >= 0 })
    }
    val scopes = ints().toList()
    val changed = ints().toList()
    val assertions = List(collectionCount()) {
        LpBoundAssertion(int(), bool(), requireNotNull(exactSide()), long(), int())
    }
    return LpExactState(
        model, assertions, scopes, matrixRevision, boundRevision, objectiveRevision, popRevision,
        changed, LpScopedRows(rows, lastId), rowRevision,
    )
}

private class CaptureBudgetExceeded : RuntimeException()
private val EXACT_CAPTURE_MAGIC: ByteArray = byteArrayOf(0x4b, 0x4c, 0x50, 0x45, 0x58, 0x41, 0x43, 0x54)

private fun CaptureWriter.exactShifts(shifts: LpExactShifts) {
    ints(shifts.rows)
    shifts.rhs.forEach { fraction(it) }
    ints(shifts.columns)
    shifts.upper.forEach { fraction(it) }
}

private fun CaptureReader.exactShifts(): LpExactShifts {
    val rows = ints()
    val rhs = List(rows.size) { fraction() }
    val columns = ints()
    return LpExactShifts(rows, rhs, columns, List(columns.size) { fraction() })
}

private fun CaptureWriter.fraction(value: BigFraction) {
    rationalPart(value.num)
    rationalPart(value.den)
}

private fun CaptureReader.fraction(): BigFraction {
    val numerator = parseBigInt(string())
    val denominator = parseBigInt(string())
    require(denominator.signum() > 0) { "exact LP denominator must be positive" }
    return BigFraction.of(numerator, denominator)
}

private fun CaptureWriter.exactNumber(number: ExactLpNumber) {
    bool(number.ieeeBits != null)
    if (number.ieeeBits != null) {
        long(number.ieeeBits)
    } else {
        fraction(number.value)
    }
}

private fun CaptureReader.exactNumber(): ExactLpNumber = if (bool()) {
    ExactLpNumber.ofIeee(Double.fromBits(long()))
} else {
    ExactLpNumber.of(fraction())
}

private fun CaptureWriter.exactPremises(premises: ExactLpPremises?) {
    bool(premises != null)
    if (premises != null) {
        val bounds = premises.boundEntries()
        int(bounds.size)
        bounds.forEach {
            int(it.variable)
            bool(it.upper)
            exactNumber(it.threshold)
        }
        ints(premises.literalEntries().toIntArray())
    }
}

private fun CaptureReader.exactPremises(): ExactLpPremises? = if (bool()) {
    val bounds = List(collectionCount()) { ExactLpPremise(int(), bool(), exactNumber()) }
    ExactLpPremises(bounds, ints().toList())
} else {
    null
}

private fun CaptureWriter.exactSide(side: ExactLpSide?) {
    bool(side != null)
    if (side != null) {
        exactNumber(side.number)
        bool(side.strict)
        exactPremises(side.premises)
    }
}

private fun CaptureReader.exactSide(): ExactLpSide? = if (bool()) {
    ExactLpSide(exactNumber(), bool(), exactPremises())
} else {
    null
}

private fun CaptureWriter.exactColumn(column: ExactLpColumn) {
    exactSide(column.bounds.lower)
    exactSide(column.bounds.upper)
    exactNumber(column.origin)
    bool(column.integral)
    int(column.tag)
}

private fun CaptureReader.exactColumn(): ExactLpColumn =
    ExactLpColumn(ExactLpBounds(exactSide(), exactSide()), exactNumber(), bool(), int())

private fun CaptureWriter.scopedRow(row: LpScopedRow) {
    long(row.id)
    val terms = row.coefficients()
    int(terms.size)
    terms.forEach { (column, number) ->
        int(column)
        exactNumber(number)
    }
    exactNumber(row.rhs)
    exactColumn(row.logical)
    bool(row.metadata.global)
    bool(row.metadata.strict)
    exactPremises(row.metadata.premises)
    exactNumber(row.cost)
}

private fun CaptureReader.scopedRow(): LpScopedRow {
    val id = long()
    val terms = List(collectionCount()) { int() to exactNumber() }
    val rhs = exactNumber()
    val logical = exactColumn()
    val metadata = ExactLpRow(bool(), bool(), exactPremises())
    return LpScopedRow(id, terms, rhs, logical, metadata, exactNumber())
}

private fun CaptureWriter.exactObjectivePayload(objective: ExactLpObjective) {
    int(objective.size)
    repeat(objective.size) { exactNumber(objective.cost(it)) }
    exactNumber(objective.constant)
    exactNumber(objective.scale)
    exactNumber(objective.externalConstant)
    int(senseWireCode(objective.sense))
}

private fun CaptureReader.exactObjectivePayload(): ExactLpObjective = ExactLpObjective(
    List(collectionCount()) { exactNumber() },
    exactNumber(),
    exactNumber(),
    exactNumber(),
    senseFromWireCode(int()),
)

private fun CaptureWriter.exactModel(model: ExactLpModel) {
    int(model.n)
    repeat(model.n) { column ->
        val entries = model.entries(column)
        int(entries.size)
        entries.forEach {
            int(it.row)
            exactNumber(it.number)
        }
    }
    int(model.m)
    repeat(model.m) { exactNumber(model.rhs(it)) }
    int(model.numVars)
    repeat(model.numVars) {
        val column = model.column(it)
        exactSide(column.bounds.lower)
        exactSide(column.bounds.upper)
        exactNumber(column.origin)
        bool(column.integral)
        int(column.tag)
    }
    repeat(model.m) {
        val row = model.row(it)
        bool(row.global)
        bool(row.strict)
        exactPremises(row.premises)
    }
    exactObjectivePayload(model.objective)
}

private fun CaptureReader.exactModel(): ExactLpModel {
    val matrix = List(collectionCount()) {
        List(collectionCount()) { ExactLpEntry(int(), exactNumber()) }
    }
    val rhs = List(collectionCount()) { exactNumber() }
    val columns = List(collectionCount()) {
        ExactLpColumn(ExactLpBounds(exactSide(), exactSide()), exactNumber(), bool(), int())
    }
    val rows = List(rhs.size) { ExactLpRow(bool(), bool(), exactPremises()) }
    return ExactLpModel(matrix, rhs, columns, rows, exactObjectivePayload())
}

private fun CaptureWriter.exactBasis(basis: Basis?) {
    bool(basis != null)
    if (basis != null) {
        ints(basis.basicVars)
        ints(IntArray(basis.status.size) { exactStatusCode(basis.status[it]) })
        bool(basis.captureEligible)
    }
}

private fun CaptureReader.exactBasis(): Basis? = if (bool()) {
    val headings = ints()
    val statuses = ints().map(::exactStatusFromCode).toTypedArray()
    Basis(headings, statuses, bool())
} else {
    null
}

private fun exactStatusCode(status: VarStatus): Int = when (status) {
    VarStatus.BASIC -> 1
    VarStatus.AT_LOWER -> 2
    VarStatus.AT_UPPER -> 3
    VarStatus.FIXED -> 4
    VarStatus.FREE -> 5
}

private fun exactStatusFromCode(code: Int): VarStatus = when (code) {
    1 -> VarStatus.BASIC
    2 -> VarStatus.AT_LOWER
    3 -> VarStatus.AT_UPPER
    4 -> VarStatus.FIXED
    5 -> VarStatus.FREE
    else -> error("unknown exact LP status $code")
}

private fun CaptureWriter.exactEvent(event: LpExactReplayEvent) {
    int(
        when (event) {
            is LpExactReplayEvent.Push -> 1
            is LpExactReplayEvent.Assert -> 2
            is LpExactReplayEvent.Pop -> 3
            is LpExactReplayEvent.Objective -> 4
            is LpExactReplayEvent.Recenter -> 5
            is LpExactReplayEvent.Solve -> 6
            is LpExactReplayEvent.Append -> 7
            is LpExactReplayEvent.Deactivate -> 8
            is LpExactReplayEvent.Compact -> 9
            is LpExactReplayEvent.Suspend -> 10
            is LpExactReplayEvent.Extend -> if (event.permanentRows.isEmpty()) 11 else 12
            is LpExactReplayEvent.CompactColumns -> 13
        },
    )
    int(event.eventVersion)
    when (event) {
        is LpExactReplayEvent.Push -> Unit

        is LpExactReplayEvent.Assert -> {
            int(event.column)
            bool(event.upper)
            exactSide(event.side)
            long(event.witness)
        }

        is LpExactReplayEvent.Pop -> int(event.targetDepth)

        is LpExactReplayEvent.Objective -> exactObjectivePayload(event.objective)

        is LpExactReplayEvent.Recenter -> {
            int(event.origins.size)
            event.origins.forEach { exactNumber(it) }
        }

        is LpExactReplayEvent.Solve -> exactBasis(event.warm)

        is LpExactReplayEvent.Append -> {
            scopedRow(event.row)
            bool(event.scoped)
        }

        is LpExactReplayEvent.Deactivate -> long(event.id)

        is LpExactReplayEvent.Suspend -> longs(event.ids.sorted().toLongArray())

        is LpExactReplayEvent.Extend -> {
            int(event.columns.size)
            event.columns.forEach {
                exactColumn(it.column)
                exactNumber(it.cost)
            }
            int(event.rows.size)
            event.rows.forEach { scopedRow(it) }
            bool(event.scoped)
            if (event.permanentRows.isNotEmpty()) longs(event.permanentRows.sorted().toLongArray())
        }

        is LpExactReplayEvent.Compact -> Unit

        is LpExactReplayEvent.CompactColumns -> ints(event.columns.toIntArray())
    }
}

private fun CaptureReader.exactEvent(captureVersion: Int): LpExactReplayEvent {
    val code = int()
    require(int() == LP_EXACT_EVENT_VERSION) { "unsupported exact LP event version" }
    return when (code) {
        1 -> LpExactReplayEvent.Push()

        2 -> LpExactReplayEvent.Assert(int(), bool(), requireNotNull(exactSide()), long())

        3 -> LpExactReplayEvent.Pop(int())

        4 -> LpExactReplayEvent.Objective(exactObjectivePayload())

        5 -> LpExactReplayEvent.Recenter(List(collectionCount()) { exactNumber() })

        6 -> LpExactReplayEvent.Solve(exactBasis())

        7 -> LpExactReplayEvent.Append(scopedRow(), bool())

        8 -> LpExactReplayEvent.Deactivate(long())

        9 -> LpExactReplayEvent.Compact()

        10 -> {
            require(captureVersion >= 3) { "row suspension requires exact LP capture version 3" }
            LpExactReplayEvent.Suspend(longs().toSet())
        }

        11, 12 -> {
            require(captureVersion >= 3) { "structural extension requires exact LP capture version 3" }
            val columns = List(collectionCount()) { LpStructuralColumn(exactColumn(), exactNumber()) }
            val rows = List(collectionCount()) { scopedRow() }
            val scoped = bool()
            val permanent = if (code == 12) longs().toSet() else emptySet()
            LpExactReplayEvent.Extend(columns, rows, scoped, permanent)
        }

        13 -> {
            require(captureVersion >= 3) { "column compaction requires exact LP capture version 3" }
            LpExactReplayEvent.CompactColumns(ints().toList())
        }

        else -> error("unknown exact LP event type $code")
    }
}
