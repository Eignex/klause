package com.eignex.klause.lp.engine

internal const val LP_CAPTURE_VERSION: Int = 1
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
    fun copy(): LpCapturedPremises = LpCapturedPremises(
        vars.copyOf(),
        isUpper.copyOf(),
        thresholds.copyOf(),
        boolLits.copyOf(),
    )
}

/** Authoritative IEEE-754 input. Its CSC is independent of the Long placeholder CSC because a
 * real-only coefficient can be absent from the integer view; both stores preserve ascending rows. */
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

        fun captureOrNull(model: LpModel): LpCapturedModel? = try {
            capture(model).also { it.validate() }
        } catch (_: IllegalArgumentException) {
            null
        }

        fun capture(model: LpModel): LpCapturedModel {
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
            ).also { it.validate() }
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
        fun capture(basis: Basis): LpCapturedBasis = LpCapturedBasis(
            basis.basicVars.copyOf(),
            IntArray(basis.status.size) { statusWireCode(basis.status[it]) },
        )
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
            requireNotNull(model.toLegacy()) { "exact model is outside capture v1 authority" }, settings, events,
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

private class CaptureWriter {
    private var data = ByteArray(256)
    private var size = 0

    fun toByteArray(): ByteArray = data.copyOf(size)
    fun bytes(value: ByteArray) = value.forEach { byte(it.toInt()) }
    fun bool(value: Boolean) = byte(if (value) 1 else 0)
    fun int(value: Int) = long(value.toLong(), 4)
    fun long(value: Long) = long(value, 8)

    fun string(value: String) {
        val bytes = value.encodeToByteArray()
        int(bytes.size)
        bytes(bytes)
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
            LpCapturedDoubleView(ints(), ints(), longs(), longs(), longs(), longs(), bools(), long(), longs())
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
