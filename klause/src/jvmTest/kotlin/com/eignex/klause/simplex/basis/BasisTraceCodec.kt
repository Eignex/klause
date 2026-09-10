package com.eignex.klause.simplex.basis

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal const val BASIS_TRACE_VERSION = 1
internal const val BASIS_TRACE_MAX_PAYLOAD = 4 * 1024 * 1024
internal const val BASIS_TRACE_MAX_ROWS = 512
internal const val BASIS_TRACE_MAX_COLUMNS = 2048
internal const val BASIS_TRACE_MAX_ENTRIES = 100_000
internal const val BASIS_TRACE_MAX_OPERATIONS = 256
private const val MAX_STRING_BYTES = 16 * 1024
private const val MAGIC = 0x4b42545241434531L // KBTRACE1

internal class BasisTraceFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException("basis trace: $message", cause)

internal enum class BasisTraceFormat { MPS, MINIZINC, SMTLIB }
internal enum class BasisTraceRoute { PRODUCTION_RELAXATION, SMT_SOURCE_DERIVED }
internal enum class BasisColumnKind { INTEGER, BOOLEAN, REAL, AUXILIARY, SYNTHESIZED_UNIT }
internal enum class BasisVectorRole { GENERAL, ENTERING_SPIKE, PIVOT_ROW }

internal data class BasisTraceMetadata(
    val id: String,
    val format: BasisTraceFormat,
    val route: BasisTraceRoute,
    val source: String,
    val license: String,
    val sourceSha256: String,
    val captureRevision: String,
    val frontend: String,
    val configuration: String,
    val sourceScaling: String,
    val rootStateSha256: String,
    val seed: Long,
    val termination: String,
    val truncated: Boolean,
)

internal class BasisTraceMatrix(
    val rows: Int,
    val columns: Int,
    columnPointers: IntArray,
    rowIndices: IntArray,
    valueBits: LongArray,
) {
    private val pointers = columnPointers.copyOf()
    private val indices = rowIndices.copyOf()
    private val bits = valueBits.copyOf()

    val entries: Int get() = bits.size
    fun copyColumnPointers(): IntArray = pointers.copyOf()
    fun copyRowIndices(): IntArray = indices.copyOf()
    fun copyValueBits(): LongArray = bits.copyOf()
    fun valueAt(entry: Int): Double = Double.fromBits(bits[entry])
    fun columnStart(column: Int): Int = pointers[column]
    fun columnEnd(column: Int): Int = pointers[column + 1]
    fun rowAt(entry: Int): Int = indices[entry]
}

internal data class BasisColumnOrigin(
    val kind: BasisColumnKind,
    val sourceId: Int,
    val sign: Int,
    val lowerBits: Long?,
    val upperBits: Long?,
    val objectiveBits: Long,
)

internal sealed interface BasisHeading {
    val coordinate: Int

    data class Source(override val coordinate: Int) : BasisHeading
    data class Unit(override val coordinate: Int) : BasisHeading
}

internal class BasisTraceVector(values: DoubleArray, support: IntArray) {
    private val valueBits = LongArray(values.size) { values[it].toRawBits() }
    private val stored = support.copyOf()

    val size: Int get() = valueBits.size
    fun values(): DoubleArray = DoubleArray(valueBits.size) { Double.fromBits(valueBits[it]) }
    fun bits(): LongArray = valueBits.copyOf()
    fun support(): IntArray = stored.copyOf()
}

internal sealed interface BasisTraceOperation {
    data class Factorize(val headings: List<BasisHeading>, val success: Boolean) : BasisTraceOperation

    class Solve(
        val carrier: Int,
        val transpose: Boolean,
        val role: BasisVectorRole,
        val expectedDensityBits: Long,
        rhs: BasisTraceVector,
    ) : BasisTraceOperation {
        val rhs = BasisTraceVector(rhs.values(), rhs.support())
    }

    class Update(
        val leavingSlot: Int,
        val entering: BasisHeading,
        val spikeOperation: Int,
        val pivotOperation: Int?,
        spikeEvidence: BasisTraceVector,
        pivotEvidence: BasisTraceVector?,
        val outcome: BasisUpdate,
    ) : BasisTraceOperation {
        val spikeEvidence = BasisTraceVector(spikeEvidence.values(), spikeEvidence.support())
        val pivotEvidence = pivotEvidence?.let { BasisTraceVector(it.values(), it.support()) }
    }
}

internal class BasisTrace(
    val metadata: BasisTraceMetadata,
    val matrix: BasisTraceMatrix,
    val sourceColumns: Int,
    origins: List<BasisColumnOrigin>,
    rowStrict: BooleanArray,
    operations: List<BasisTraceOperation>,
) {
    val origins = origins.toList()
    private val strict = rowStrict.copyOf()
    val operations = operations.map { operation ->
        when (operation) {
            is BasisTraceOperation.Factorize -> operation.copy(headings = operation.headings.toList())

            is BasisTraceOperation.Solve -> BasisTraceOperation.Solve(
                operation.carrier,
                operation.transpose,
                operation.role,
                operation.expectedDensityBits,
                operation.rhs,
            )

            is BasisTraceOperation.Update -> BasisTraceOperation.Update(
                operation.leavingSlot,
                operation.entering,
                operation.spikeOperation,
                operation.pivotOperation,
                operation.spikeEvidence,
                operation.pivotEvidence,
                operation.outcome,
            )
        }
    }

    fun rowStrict(): BooleanArray = strict.copyOf()
}

internal object BasisTraceCodec {
    fun encode(trace: BasisTrace): ByteArray {
        validate(trace)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> write(trace, output) }
        return bytes.toByteArray().also {
            if (it.size > BASIS_TRACE_MAX_PAYLOAD) malformed("encoded payload exceeds $BASIS_TRACE_MAX_PAYLOAD bytes")
        }
    }

    fun write(trace: BasisTrace, path: Path) {
        val encoded = encode(trace)
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.write(path, encoded)
    }

    fun read(path: Path): BasisTrace {
        val size = Files.size(path)
        if (size > BASIS_TRACE_MAX_PAYLOAD) malformed("payload is $size bytes, limit is $BASIS_TRACE_MAX_PAYLOAD")
        return decode(Files.readAllBytes(path))
    }

    fun decode(bytes: ByteArray): BasisTrace {
        if (bytes.size > BASIS_TRACE_MAX_PAYLOAD) malformed("payload exceeds $BASIS_TRACE_MAX_PAYLOAD bytes")
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readLong() != MAGIC) malformed("bad magic")
                val version = input.readInt()
                if (version != BASIS_TRACE_VERSION) malformed("unsupported version $version")
                val metadata = readMetadata(input)
                val rows = checkedCount(input.readInt(), BASIS_TRACE_MAX_ROWS, "rows")
                val columns = checkedCount(input.readInt(), BASIS_TRACE_MAX_COLUMNS, "columns")
                val entries = checkedCount(input.readInt(), BASIS_TRACE_MAX_ENTRIES, "entries")
                val pointers = IntArray(checkedArrayLength(columns, 1, "column pointers")) { input.readInt() }
                val indices = IntArray(entries) { input.readInt() }
                val bits = LongArray(entries) { input.readLong() }
                val matrix = BasisTraceMatrix(rows, columns, pointers, indices, bits)
                val sourceColumns = input.readInt()
                val originCount = checkedCount(input.readInt(), BASIS_TRACE_MAX_COLUMNS, "column origins")
                val origins = List(originCount) {
                    BasisColumnOrigin(
                        enumValue<BasisColumnKind>(input.readUnsignedByte(), "column kind"),
                        input.readInt(),
                        input.readInt(),
                        readNullableLong(input),
                        readNullableLong(input),
                        input.readLong(),
                    )
                }
                val strictCount = checkedCount(input.readInt(), BASIS_TRACE_MAX_ROWS, "row strictness")
                val strict = BooleanArray(strictCount) { input.readBoolean() }
                val operationCount = checkedCount(input.readInt(), BASIS_TRACE_MAX_OPERATIONS, "operations")
                val operations = List(operationCount) { readOperation(input, rows) }
                if (input.read() != -1) malformed("trailing bytes")
                return BasisTrace(metadata, matrix, sourceColumns, origins, strict, operations).also(::validate)
            }
        } catch (error: Exception) {
            decodeFailure(error)
        }
    }

    fun validate(trace: BasisTrace) {
        val matrix = trace.matrix
        checkedCount(matrix.rows, BASIS_TRACE_MAX_ROWS, "rows")
        checkedCount(matrix.columns, BASIS_TRACE_MAX_COLUMNS, "columns")
        checkedCount(matrix.entries, BASIS_TRACE_MAX_ENTRIES, "entries")
        if (matrix.rows <= 0 || matrix.columns < matrix.rows) malformed("matrix dimensions are not a basis owner")
        if (trace.sourceColumns !in 0..matrix.columns) malformed("source column count out of range")
        if (matrix.columns != trace.sourceColumns + matrix.rows) {
            malformed("owner matrix must contain one synthesized unit per row")
        }
        val pointers = matrix.copyColumnPointers()
        if (pointers.size != matrix.columns + 1 || pointers.firstOrNull() != 0 || pointers.last() != matrix.entries) {
            malformed("invalid CSC pointers")
        }
        for (column in 0 until matrix.columns) {
            if (pointers[column] > pointers[column + 1]) malformed("non-monotone CSC pointers")
        }
        for (entry in 0 until matrix.entries) {
            if (matrix.rowAt(entry) !in 0 until matrix.rows) malformed("row index out of range")
            if (!matrix.valueAt(entry).isFinite()) malformed("nonfinite matrix value")
        }
        if (trace.origins.size != matrix.columns) malformed("column origin count mismatch")
        if (trace.rowStrict().size != matrix.rows) malformed("row strictness count mismatch")
        for (column in trace.origins.indices) validateOrigin(trace.origins[column], column, trace.sourceColumns, matrix)
        validateStrings(trace.metadata)
        if (trace.operations.size > BASIS_TRACE_MAX_OPERATIONS) malformed("too many operations")
        validateOperations(trace)
    }

    private fun validateOperations(trace: BasisTrace) {
        var headings: List<BasisHeading>? = null
        val solves = HashMap<Int, BasisTraceOperation.Solve>()
        val latestSolveByCarrier = HashMap<Int, Int>()
        val consumedPreparation = HashSet<Int>()
        for ((index, operation) in trace.operations.withIndex()) {
            when (operation) {
                is BasisTraceOperation.Factorize -> {
                    validateHeadings(operation.headings, trace)
                    headings = operation.headings.takeIf { operation.success }
                    solves.clear()
                    latestSolveByCarrier.clear()
                    consumedPreparation.clear()
                }

                is BasisTraceOperation.Solve -> {
                    if (headings == null) malformed("operation $index solves without a factorized basis")
                    if (operation.carrier !in 0 until BASIS_TRACE_MAX_OPERATIONS) {
                        malformed("operation $index carrier out of range")
                    }
                    validateVector(operation.rhs, trace.matrix.rows, "operation $index rhs")
                    val density = Double.fromBits(operation.expectedDensityBits)
                    if (!density.isFinite() || density !in 0.0..1.0) malformed("operation $index has invalid density")
                    solves[index] = operation
                    latestSolveByCarrier[operation.carrier] = index
                }

                is BasisTraceOperation.Update -> {
                    val current = headings ?: malformed("operation $index updates without a factorized basis")
                    if (operation.leavingSlot !in current.indices) {
                        malformed("operation $index leaving slot out of range")
                    }
                    validateHeading(operation.entering, trace)
                    val spike = solves[operation.spikeOperation]
                        ?: malformed("operation $index references no preceding spike solve")
                    if (operation.spikeOperation >= index || spike.transpose ||
                        spike.role != BasisVectorRole.ENTERING_SPIKE ||
                        latestSolveByCarrier[spike.carrier] != operation.spikeOperation ||
                        !consumedPreparation.add(operation.spikeOperation)
                    ) {
                        malformed("operation $index has invalid spike reference")
                    }
                    validatePreparationRhs(spike.rhs, operation.entering, trace, "operation $index spike")
                    val pivot = operation.pivotOperation?.let {
                        solves[it] ?: malformed("operation $index references no preceding pivot solve")
                    }
                    if (operation.pivotOperation != null &&
                        (
                            operation.pivotOperation >= index || pivot?.transpose != true ||
                                pivot.role != BasisVectorRole.PIVOT_ROW ||
                                latestSolveByCarrier[pivot.carrier] != operation.pivotOperation ||
                                !consumedPreparation.add(operation.pivotOperation)
                            )
                    ) {
                        malformed("operation $index has invalid pivot reference")
                    }
                    pivot?.let {
                        validateUnitRhs(it.rhs, operation.leavingSlot, trace.matrix.rows, "operation $index pivot")
                    }
                    validateVector(operation.spikeEvidence, trace.matrix.rows, "operation $index spike")
                    operation.pivotEvidence?.let {
                        validateVector(it, trace.matrix.rows, "operation $index pivot")
                        if (pivot == null) malformed("operation $index has unreferenced pivot evidence")
                    }
                    if ((pivot == null) != (operation.pivotEvidence == null)) {
                        malformed("operation $index pivot evidence/reference mismatch")
                    }
                    if (operation.outcome != BasisUpdate.SINGULAR) {
                        headings = current.toMutableList().also { it[operation.leavingSlot] = operation.entering }
                    }
                }
            }
        }
    }

    private fun validateHeadings(headings: List<BasisHeading>, trace: BasisTrace) {
        if (headings.size != trace.matrix.rows) malformed("incomplete basis heading")
        headings.forEach { validateHeading(it, trace) }
        val raw = headings.map { headingColumn(it, trace.sourceColumns) }
        if (raw.distinct().size != raw.size) malformed("duplicate basis heading")
    }

    private fun validateHeading(heading: BasisHeading, trace: BasisTrace) {
        when (heading) {
            is BasisHeading.Source -> if (heading.coordinate !in 0 until trace.sourceColumns) {
                malformed("source heading out of range")
            }

            is BasisHeading.Unit -> if (heading.coordinate !in 0 until trace.matrix.rows) {
                malformed("unit heading out of range")
            }
        }
    }

    private fun validateOrigin(origin: BasisColumnOrigin, column: Int, sourceColumns: Int, matrix: BasisTraceMatrix) {
        if (origin.sign != -1 && origin.sign != 1) malformed("column $column has invalid source sign")
        listOfNotNull(origin.lowerBits, origin.upperBits, origin.objectiveBits).forEach {
            if (!Double.fromBits(it).isFinite()) malformed("column $column has nonfinite metadata")
        }
        if (column < sourceColumns) {
            if (origin.kind == BasisColumnKind.SYNTHESIZED_UNIT) malformed("source column tagged as a unit")
        } else {
            val row = column - sourceColumns
            if (origin.kind != BasisColumnKind.SYNTHESIZED_UNIT || origin.sourceId != row) {
                malformed("unit column $column has inconsistent origin")
            }
            val entries = matrix.columnEnd(column) - matrix.columnStart(column)
            if (entries != 1) malformed("unit column $column is not stored as one entry")
            val entry = matrix.columnStart(column)
            if (matrix.rowAt(entry) != row || matrix.valueAt(entry).toRawBits() != 1.0.toRawBits()) {
                malformed("unit column $column does not equal e($row)")
            }
        }
    }

    private fun validateVector(vector: BasisTraceVector, size: Int, label: String) {
        if (vector.size != size) malformed("$label dimension mismatch")
        val values = vector.values()
        if (values.any { !it.isFinite() }) malformed("$label contains nonfinite values")
        val support = vector.support()
        if (support.any { it !in 0 until size } || support.distinct().size != support.size) {
            malformed("$label has invalid support")
        }
        if (support.any { values[it] == 0.0 }) malformed("$label support names a zero")
        if (values.indices.any { values[it] != 0.0 && it !in support }) malformed("$label omits a nonzero")
    }

    private fun validatePreparationRhs(
        vector: BasisTraceVector,
        entering: BasisHeading,
        trace: BasisTrace,
        label: String,
    ) {
        val expected = DoubleArray(trace.matrix.rows)
        val column = headingColumn(entering, trace.sourceColumns)
        for (entry in trace.matrix.columnStart(column) until trace.matrix.columnEnd(column)) {
            expected[trace.matrix.rowAt(entry)] = trace.matrix.valueAt(entry)
        }
        val values = vector.values()
        if (values.indices.any { values[it] != expected[it] }) malformed("$label rhs is not the entering column")
    }

    private fun validateUnitRhs(vector: BasisTraceVector, row: Int, size: Int, label: String) {
        val expected = DoubleArray(size)
        expected[row] = 1.0
        val values = vector.values()
        if (values.indices.any { values[it] != expected[it] }) malformed("$label rhs is not e($row)")
    }

    private fun write(trace: BasisTrace, output: DataOutputStream) {
        output.writeLong(MAGIC)
        output.writeInt(BASIS_TRACE_VERSION)
        writeMetadata(trace.metadata, output)
        val matrix = trace.matrix
        output.writeInt(matrix.rows)
        output.writeInt(matrix.columns)
        output.writeInt(matrix.entries)
        matrix.copyColumnPointers().forEach(output::writeInt)
        matrix.copyRowIndices().forEach(output::writeInt)
        matrix.copyValueBits().forEach(output::writeLong)
        output.writeInt(trace.sourceColumns)
        output.writeInt(trace.origins.size)
        for (origin in trace.origins) {
            output.writeByte(origin.kind.ordinal)
            output.writeInt(origin.sourceId)
            output.writeInt(origin.sign)
            writeNullableLong(output, origin.lowerBits)
            writeNullableLong(output, origin.upperBits)
            output.writeLong(origin.objectiveBits)
        }
        val strict = trace.rowStrict()
        output.writeInt(strict.size)
        strict.forEach(output::writeBoolean)
        output.writeInt(trace.operations.size)
        trace.operations.forEach { writeOperation(it, output) }
    }

    private fun writeOperation(operation: BasisTraceOperation, output: DataOutputStream) {
        when (operation) {
            is BasisTraceOperation.Factorize -> {
                output.writeByte(0)
                output.writeBoolean(operation.success)
                output.writeInt(operation.headings.size)
                operation.headings.forEach { writeHeading(it, output) }
            }

            is BasisTraceOperation.Solve -> {
                output.writeByte(1)
                output.writeInt(operation.carrier)
                output.writeBoolean(operation.transpose)
                output.writeByte(operation.role.ordinal)
                output.writeLong(operation.expectedDensityBits)
                writeVector(operation.rhs, output)
            }

            is BasisTraceOperation.Update -> {
                output.writeByte(2)
                output.writeInt(operation.leavingSlot)
                writeHeading(operation.entering, output)
                output.writeInt(operation.spikeOperation)
                output.writeInt(operation.pivotOperation ?: -1)
                writeVector(operation.spikeEvidence, output)
                output.writeBoolean(operation.pivotEvidence != null)
                operation.pivotEvidence?.let { writeVector(it, output) }
                output.writeByte(operation.outcome.ordinal)
            }
        }
    }

    private fun readOperation(input: DataInputStream, rows: Int): BasisTraceOperation =
        when (input.readUnsignedByte()) {
            0 -> {
                val success = input.readBoolean()
                val headings = List(checkedCount(input.readInt(), BASIS_TRACE_MAX_ROWS, "heading count")) {
                    readHeading(input)
                }
                BasisTraceOperation.Factorize(headings, success)
            }

            1 -> BasisTraceOperation.Solve(
                input.readInt(),
                input.readBoolean(),
                enumValue(input.readUnsignedByte(), "vector role"),
                input.readLong(),
                readVector(input, rows),
            )

            2 -> {
                val leaving = input.readInt()
                val entering = readHeading(input)
                val spike = input.readInt()
                val pivot = input.readInt().takeUnless { it == -1 }
                val spikeEvidence = readVector(input, rows)
                val pivotEvidence = if (input.readBoolean()) readVector(input, rows) else null
                val outcome = enumValue<BasisUpdate>(input.readUnsignedByte(), "update outcome")
                BasisTraceOperation.Update(leaving, entering, spike, pivot, spikeEvidence, pivotEvidence, outcome)
            }

            else -> malformed("unknown operation tag")
        }

    private fun writeVector(vector: BasisTraceVector, output: DataOutputStream) {
        output.writeInt(vector.size)
        vector.bits().forEach(output::writeLong)
        val support = vector.support()
        output.writeInt(support.size)
        support.forEach(output::writeInt)
    }

    private fun readVector(input: DataInputStream, expectedRows: Int): BasisTraceVector {
        val size = checkedCount(input.readInt(), BASIS_TRACE_MAX_ROWS, "vector length")
        if (size != expectedRows) malformed("vector length $size does not match $expectedRows rows")
        val values = DoubleArray(size) { Double.fromBits(input.readLong()) }
        val supportSize = checkedCount(input.readInt(), size, "vector support")
        return BasisTraceVector(values, IntArray(supportSize) { input.readInt() })
    }

    private fun writeHeading(heading: BasisHeading, output: DataOutputStream) {
        output.writeByte(if (heading is BasisHeading.Source) 0 else 1)
        output.writeInt(heading.coordinate)
    }

    private fun readHeading(input: DataInputStream): BasisHeading = when (input.readUnsignedByte()) {
        0 -> BasisHeading.Source(input.readInt())
        1 -> BasisHeading.Unit(input.readInt())
        else -> malformed("unknown heading tag")
    }

    private fun writeMetadata(metadata: BasisTraceMetadata, output: DataOutputStream) {
        writeString(output, metadata.id)
        output.writeByte(metadata.format.ordinal)
        output.writeByte(metadata.route.ordinal)
        writeString(output, metadata.source)
        writeString(output, metadata.license)
        writeString(output, metadata.sourceSha256)
        writeString(output, metadata.captureRevision)
        writeString(output, metadata.frontend)
        writeString(output, metadata.configuration)
        writeString(output, metadata.sourceScaling)
        writeString(output, metadata.rootStateSha256)
        output.writeLong(metadata.seed)
        writeString(output, metadata.termination)
        output.writeBoolean(metadata.truncated)
    }

    private fun readMetadata(input: DataInputStream) = BasisTraceMetadata(
        readString(input),
        enumValue(input.readUnsignedByte(), "format"),
        enumValue(input.readUnsignedByte(), "route"),
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        readString(input),
        input.readLong(),
        readString(input),
        input.readBoolean(),
    )

    private fun validateStrings(metadata: BasisTraceMetadata) {
        listOf(
            metadata.id,
            metadata.source,
            metadata.license,
            metadata.sourceSha256,
            metadata.captureRevision,
            metadata.frontend,
            metadata.configuration,
            metadata.sourceScaling,
            metadata.rootStateSha256,
            metadata.termination,
        ).forEach { if (it.toByteArray(StandardCharsets.UTF_8).size > MAX_STRING_BYTES) malformed("string too long") }
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) malformed("string too long")
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = checkedCount(input.readInt(), MAX_STRING_BYTES, "string length")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun writeNullableLong(output: DataOutputStream, value: Long?) {
        output.writeBoolean(value != null)
        if (value != null) output.writeLong(value)
    }

    private fun readNullableLong(input: DataInputStream): Long? = if (input.readBoolean()) input.readLong() else null

    private inline fun <reified E : Enum<E>> enumValue(ordinal: Int, label: String): E =
        enumValues<E>().getOrNull(ordinal) ?: malformed("unknown $label $ordinal")

    private fun decodeFailure(error: Exception): Nothing {
        val failure = when (error) {
            is BasisTraceFormatException -> error
            is EOFException -> BasisTraceFormatException("truncated payload", error)
            else -> BasisTraceFormatException(error.message ?: "invalid payload", error)
        }
        throw failure
    }

    private fun checkedCount(value: Int, maximum: Int, label: String): Int {
        if (value !in 0..maximum) malformed("$label $value exceeds 0..$maximum")
        return value
    }

    private fun checkedArrayLength(value: Int, extra: Int, label: String): Int {
        if (value > Int.MAX_VALUE - extra) malformed("$label length overflows")
        return value + extra
    }

    private fun malformed(message: String): Nothing = throw BasisTraceFormatException(message)
}

internal fun headingColumn(heading: BasisHeading, sourceColumns: Int): Int = when (heading) {
    is BasisHeading.Source -> heading.coordinate
    is BasisHeading.Unit -> sourceColumns + heading.coordinate
}
