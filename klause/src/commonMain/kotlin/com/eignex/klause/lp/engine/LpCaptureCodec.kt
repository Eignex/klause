package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.signum

internal class CaptureWriter(
    private val valueLimit: Int = Int.MAX_VALUE,
    private val byteLimit: Int = Int.MAX_VALUE,
    private val cancellation: ExecutionControl? = null,
) {
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
        if (values == valueLimit || cancellation?.isCancelled() == true) throw CaptureBudgetExceeded()
        values++
    }

    fun string(value: String) {
        if (value.length > byteLimit - size) throw CaptureBudgetExceeded()
        val bytes = value.encodeToByteArray()
        int(bytes.size)
        bytes(bytes)
    }

    fun rationalPart(value: BigInt) {
        if (byteLimit != Int.MAX_VALUE && value.magnitudeBitLength().toLong() > (byteLimit - size).toLong() * 4L) {
            throw CaptureBudgetExceeded()
        }
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

internal class CaptureReader(private val data: ByteArray) {
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

internal fun capturedStatusWireCode(status: VarStatus): Int = when (status) {
    VarStatus.BASIC -> 1
    VarStatus.AT_LOWER -> 2
    VarStatus.AT_UPPER -> 3
    VarStatus.FREE, VarStatus.FIXED -> throw IllegalArgumentException("native status requires exact capture")
}

internal fun capturedStatusFromWireCode(code: Int): VarStatus = when (code) {
    1 -> VarStatus.BASIC
    2 -> VarStatus.AT_LOWER
    3 -> VarStatus.AT_UPPER
    else -> error("unknown variable status $code")
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

internal fun CaptureWriter.exactState(state: LpExactState) {
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

internal fun CaptureReader.exactState(version: Int): LpExactState {
    val model = exactModel(version)
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

internal class CaptureBudgetExceeded : RuntimeException()

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
    rowSlack(row.metadata.slack)
    exactNumber(row.cost)
}

private fun CaptureReader.scopedRow(version: Int): LpScopedRow {
    val id = long()
    val terms = List(collectionCount()) { int() to exactNumber() }
    val rhs = exactNumber()
    val logical = exactColumn()
    val metadata = ExactLpRow(bool(), bool(), exactPremises(), if (version >= 4) rowSlack() else null)
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

internal fun CaptureWriter.exactModel(model: ExactLpModel) {
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
        rowSlack(row.slack)
    }
    exactObjectivePayload(model.objective)
}

private fun CaptureReader.exactModel(version: Int): ExactLpModel {
    val matrix = List(collectionCount()) {
        List(collectionCount()) { ExactLpEntry(int(), exactNumber()) }
    }
    val rhs = List(collectionCount()) { exactNumber() }
    val columns = List(collectionCount()) {
        ExactLpColumn(ExactLpBounds(exactSide(), exactSide()), exactNumber(), bool(), int())
    }
    val rows = List(rhs.size) {
        ExactLpRow(bool(), bool(), exactPremises(), if (version >= 4) rowSlack() else null)
    }
    return ExactLpModel(matrix, rhs, columns, rows, exactObjectivePayload())
}

private fun CaptureWriter.rowSlack(slack: LpRowSlack?) {
    bool(slack != null)
    if (slack != null) {
        int(slack.size)
        repeat(slack.size) {
            long(slack.multiplier(it).toRawBits())
            long(slack.magnitude(it).toRawBits())
        }
    }
}

private fun CaptureReader.rowSlack(): LpRowSlack? {
    if (!bool()) return null
    val count = collectionCount()
    val multipliers = DoubleArray(count)
    val magnitudes = DoubleArray(count)
    repeat(count) {
        multipliers[it] = Double.fromBits(long())
        magnitudes[it] = Double.fromBits(long())
    }
    return LpRowSlack(multipliers, magnitudes)
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

internal fun CaptureWriter.exactEvent(event: LpExactReplayEvent) {
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

internal fun CaptureReader.exactEvent(captureVersion: Int): LpExactReplayEvent {
    val code = int()
    require(int() == LP_EXACT_EVENT_VERSION) { "unsupported exact LP event version" }
    return when (code) {
        1 -> LpExactReplayEvent.Push()

        2 -> LpExactReplayEvent.Assert(int(), bool(), requireNotNull(exactSide()), long())

        3 -> LpExactReplayEvent.Pop(int())

        4 -> LpExactReplayEvent.Objective(exactObjectivePayload())

        5 -> LpExactReplayEvent.Recenter(List(collectionCount()) { exactNumber() })

        6 -> LpExactReplayEvent.Solve(exactBasis())

        7 -> LpExactReplayEvent.Append(scopedRow(captureVersion), bool())

        8 -> LpExactReplayEvent.Deactivate(long())

        9 -> LpExactReplayEvent.Compact()

        10 -> {
            require(captureVersion >= 3) { "row suspension requires exact LP capture version 3" }
            LpExactReplayEvent.Suspend(longs().toSet())
        }

        11, 12 -> {
            require(captureVersion >= 3) { "structural extension requires exact LP capture version 3" }
            val columns = List(collectionCount()) { LpStructuralColumn(exactColumn(), exactNumber()) }
            val rows = List(collectionCount()) { scopedRow(captureVersion) }
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
