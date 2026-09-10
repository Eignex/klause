package com.eignex.klause.simplex.basis

import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.toExactLpModel
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.maximizeInt
import com.eignex.klause.solver.objective.minimizeInt
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.parseFlatZincExecution
import com.eignex.klause.theory.qflra.QfLraSystem
import com.eignex.klause.util.Cancellation
import com.eignex.koblas.SparseMatrix
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

private const val CAPTURE_SOURCE_LIMIT = 2 * 1024 * 1024L
private const val CAPTURE_ITERATION_LIMIT = 4096
private const val CAPTURE_WORK_LIMIT = 50_000_000L
private const val CAPTURE_REFACTOR_UPDATE_LIMIT = 8
private const val CAPTURE_DEADLINE_NANOS = 10_000_000_000L
private const val COMPILE_DEADLINE_SECONDS = 30L

internal data class BasisCaptureRequest(
    val id: String,
    val format: BasisTraceFormat,
    val source: Path,
    val sourceLabel: String,
    val license: String,
    val output: Path,
    val seed: Long = 0L,
)

internal data class BasisCaptureResult(val trace: BasisTrace, val encodedBytes: Int)

internal object BasisTraceCapture {
    fun capture(request: BasisCaptureRequest): BasisCaptureResult {
        val sourceSize = Files.size(request.source)
        require(sourceSize <= CAPTURE_SOURCE_LIMIT) {
            "source is $sourceSize bytes, limit is $CAPTURE_SOURCE_LIMIT"
        }
        val sourceBytes = Files.readAllBytes(request.source)
        val sourceSha = sha256(sourceBytes)
        val prepared = prepare(request, sourceBytes)
        require(prepared.model.m in 1..BASIS_TRACE_MAX_ROWS) { "LP rows ${prepared.model.m} exceed capture limit" }
        require(prepared.model.numVars <= BASIS_TRACE_MAX_COLUMNS) {
            "LP columns ${prepared.model.numVars} exceed capture limit"
        }
        val deadline = System.nanoTime() + CAPTURE_DEADLINE_NANOS
        val token = Cancellation { System.nanoTime() >= deadline }
        var recorder: RecordingBasisSolver? = null
        val solver = RevisedSimplex(
            prepared.model,
            token,
            refactorUpdateLimit = CAPTURE_REFACTOR_UPDATE_LIMIT,
            iterationLimit = CAPTURE_ITERATION_LIMIT,
            workLimit = CAPTURE_WORK_LIMIT,
            basisSolverFactory = { matrix ->
                RecordingBasisSolver(matrix, prepared.model.n).also { recorder = it }
            },
        )
        val solved = try {
            solver.solve()
        } finally {
            solver.close()
        }
        val recording = requireNotNull(recorder) { "source produced no LP factorization" }
        val termination = when {
            System.nanoTime() >= deadline -> "deadline"
            recording.truncated -> "recording-limit"
            solved?.optimal == false && solver.lastWorkOps >= CAPTURE_WORK_LIMIT -> "work-limit"
            solved?.optimal == false -> "iteration-limit"
            solved == null -> "solver-null"
            else -> "completed"
        }
        val metadata = BasisTraceMetadata(
            request.id,
            request.format,
            prepared.route,
            request.sourceLabel,
            request.license,
            sourceSha,
            captureRevision(),
            prepared.frontend,
            "refactorUpdateLimit=$CAPTURE_REFACTOR_UPDATE_LIMIT;iterationLimit=$CAPTURE_ITERATION_LIMIT;" +
                "workLimit=$CAPTURE_WORK_LIMIT;deadlineSeconds=10",
            prepared.scaling,
            rootStateHash(prepared.model),
            request.seed,
            termination,
            recording.truncated,
        )
        val trace = recording.finish(metadata, prepared.origins, prepared.model.rowStrict)
        val encoded = BasisTraceCodec.encode(trace)
        BasisTraceCodec.write(trace, request.output)
        return BasisCaptureResult(trace, encoded.size)
    }

    private fun prepare(request: BasisCaptureRequest, sourceBytes: ByteArray): PreparedCapture = when (request.format) {
        BasisTraceFormat.MPS -> prepareMps(sourceBytes.decodeToString())
        BasisTraceFormat.MINIZINC -> prepareMiniZinc(request.source, sourceBytes.decodeToString())
        BasisTraceFormat.SMTLIB -> prepareSmt(sourceBytes.decodeToString())
    }

    private fun prepareMps(text: String): PreparedCapture {
        val compiled = Mps.parse(text).toProblem()
        val relaxation = productionRelaxation(compiled.model, compiled.objective?.toLinearObjective())
        return PreparedCapture(
            relaxation.model,
            BasisTraceRoute.PRODUCTION_RELAXATION,
            "klause MPS parser and CP/MIP LP relaxation",
            "objectiveScale=${compiled.objectiveScale};normalized-owner-matrix",
            origins(relaxation),
        )
    }

    private fun prepareMiniZinc(source: Path, sourceText: String): PreparedCapture {
        val precompiled = source.fileName.toString().endsWith(".fzn")
        val compiler = if (precompiled) null else miniZincVersion()
        val flatZinc = if (precompiled) sourceText else flatten(source)
        val execution = parseFlatZincExecution(flatZinc)
        val program = execution.program
        val objective: LinearObjective? = when (val solve = program.solve) {
            SolveDirective.Satisfy -> null

            is SolveDirective.Minimize -> {
                require(solve.kind == SolveDirective.ObjKind.Int) { "float MiniZinc objectives are unsupported" }
                program.problem.minimizeInt(requireNotNull(program.intVarsByName[solve.objVar]))
            }

            is SolveDirective.Maximize -> {
                require(solve.kind == SolveDirective.ObjKind.Int) { "float MiniZinc objectives are unsupported" }
                program.problem.maximizeInt(requireNotNull(program.intVarsByName[solve.objVar]))
            }
        }
        val relaxation = productionRelaxation(program.problem, objective)
        return PreparedCapture(
            relaxation.model,
            BasisTraceRoute.PRODUCTION_RELAXATION,
            if (compiler == null) {
                "precompiled FlatZinc input; klause FlatZinc parser and CP/MIP LP relaxation"
            } else {
                "$compiler; klause FlatZinc parser and CP/MIP LP relaxation"
            },
            "defaultFloatBuckets;defaultFloatScale;fznSha256=${sha256(flatZinc.encodeToByteArray())}",
            origins(relaxation),
        )
    }

    private fun prepareSmt(text: String): PreparedCapture {
        val parsed = SmtLib.parse(text, strictBounds = true)
        require(parsed.objective == null) { "SMT source-derived optimization capture is unsupported" }
        require(parsed.model.numBoolVars == 0) {
            "SMT source-derived capture requires one authoritative asserted branch; Boolean choices are unsupported"
        }
        val exact = QfLraSystem(parsed.model).build { null }.toExactLpModel()
        val model = requireNotNull(LpExactState(exact).toWorkingModel()) { "SMT exact source has no float projection" }
        val origins = List(model.numVars) { column ->
            if (column < model.n) {
                val exactColumn = exact.column(column)
                BasisColumnOrigin(
                    if (exactColumn.integral) BasisColumnKind.INTEGER else BasisColumnKind.REAL,
                    exactColumn.tag,
                    1,
                    exactColumn.bounds.lower?.number?.projectedBits(),
                    exactColumn.bounds.upper?.number?.projectedBits(),
                    model.costD(column).toRawBits(),
                )
            } else {
                unitOrigin(column - model.n, model)
            }
        }
        return PreparedCapture(
            model,
            BasisTraceRoute.SMT_SOURCE_DERIVED,
            "klause SMT-LIB parser; asserted QF_LRA exact source projected into float benchmark owner",
            "exact-rational-source;strictBounds=true;not-production-float-theory",
            origins,
        )
    }

    private fun productionRelaxation(
        problem: com.eignex.klause.ir.Problem,
        objective: LinearObjective?,
    ): LpRelaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))

    private fun origins(relaxation: LpRelaxation): List<BasisColumnOrigin> {
        val model = relaxation.model
        return List(model.numVars) { column ->
            if (column >= model.n) return@List unitOrigin(column - model.n, model)
            val kind = when {
                relaxation.colRealId[column] >= 0 -> BasisColumnKind.REAL
                relaxation.colIsBool[column] -> BasisColumnKind.BOOLEAN
                relaxation.colVarId[column] >= 0 -> BasisColumnKind.INTEGER
                else -> BasisColumnKind.AUXILIARY
            }
            val sourceId = when (kind) {
                BasisColumnKind.REAL -> relaxation.colRealId[column]
                BasisColumnKind.BOOLEAN, BasisColumnKind.INTEGER -> relaxation.colVarId[column]
                else -> -1
            }
            val lower = model.loShiftD(column)
            val upper = if (model.hasFiniteUpper(column)) lower + model.upperD(column) else null
            BasisColumnOrigin(
                kind,
                sourceId,
                relaxation.colRealSign[column],
                lower.toRawBits(),
                upper?.toRawBits(),
                model.costD(column).toRawBits(),
            )
        }
    }

    private fun unitOrigin(row: Int, model: LpModel) = BasisColumnOrigin(
        BasisColumnKind.SYNTHESIZED_UNIT,
        row,
        1,
        0.0.toRawBits(),
        if (model.hasFiniteUpper(model.n + row)) model.upperD(model.n + row).toRawBits() else null,
        model.costD(model.n + row).toRawBits(),
    )

    private fun flatten(source: Path): String {
        val workspace = workspaceRoot()
        val solver = workspace.resolve("klause-mzn-lib/share/minizinc/solvers/klause.msc")
        val output = Files.createTempFile("basis-trace-", ".fzn")
        try {
            val process = ProcessBuilder(
                "minizinc",
                "--solver",
                solver.toString(),
                "--compile",
                "--output-fzn-to-file",
                output.toString(),
                source.toString(),
            ).redirectErrorStream(true).start()
            val completed = process.waitFor(COMPILE_DEADLINE_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                error("MiniZinc compilation exceeded $COMPILE_DEADLINE_SECONDS seconds")
            }
            val diagnostic = process.inputStream.bufferedReader().readText()
            check(process.exitValue() == 0) { "MiniZinc compilation failed: ${diagnostic.trim()}" }
            return Files.readString(output)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun miniZincVersion(): String = runCommand(workspaceRoot(), "minizinc", "--version")
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?: error("MiniZinc version output was empty")

    private fun captureRevision(): String = runCommand(workspaceRoot(), "git", "rev-parse", "HEAD").trim()

    private fun workspaceRoot(): Path = Path.of(
        System.getProperty("klause.workspace.root") ?: Path.of("").toAbsolutePath().toString(),
    )

    private fun runCommand(directory: Path, vararg command: String): String {
        val process = ProcessBuilder(*command).directory(directory.toFile()).redirectErrorStream(true).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "command timed out: ${command.joinToString(" ")}" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "command failed: ${command.joinToString(" ")}: ${output.trim()}" }
        return output
    }
}

private data class PreparedCapture(
    val model: LpModel,
    val route: BasisTraceRoute,
    val frontend: String,
    val scaling: String,
    val origins: List<BasisColumnOrigin>,
)

private class RecordingBasisSolver(matrix: SparseMatrix, private val sourceColumns: Int) : BasisSolver {
    private val delegate = KotlinBasisSolver(matrix)
    private val matrix = BasisTraceMatrix(
        matrix.rows,
        matrix.cols,
        matrix.copyColumnPointers(),
        matrix.copyRowIndices(),
        LongArray(matrix.values.size) { matrix.values[it].toRawBits() },
    )
    private val operations = ArrayList<BasisTraceOperation>()
    private val carriers = IdentityHashMap<IndexedVector, Int>()
    private val lastFtran = IdentityHashMap<IndexedVector, Int>()
    private val lastBtran = IdentityHashMap<IndexedVector, Int>()
    private var nextCarrier = 0
    private var estimatedBytes = 0
    var truncated = false
        private set

    override val n: Int get() = delegate.n
    override val nnz: Int get() = delegate.nnz
    override val updateCount: Int get() = delegate.updateCount
    override val singular: Boolean get() = delegate.singular
    override val rcond: Double get() = delegate.rcond
    override val refactorizeReason: RefactorizeReason? get() = delegate.refactorizeReason
    override val kernel: BasisKernel? get() = delegate.kernel
    override val basisWork: BasisWork? get() = delegate.basisWork
    override val basisOperationWork: BasisOperationWork? get() = delegate.basisOperationWork

    override fun refactorize(basicIndex: IntArray): Boolean {
        val headings = basicIndex.map(::heading)
        val result = delegate.refactorize(basicIndex)
        append(BasisTraceOperation.Factorize(headings, result), 8 + 5 * headings.size)
        lastFtran.clear()
        lastBtran.clear()
        return result
    }

    override fun ftran(x: IndexedVector, expectedDensity: Double) {
        val rhs = copyVector(x)
        val carrier = carrier(x)
        delegate.ftran(x, expectedDensity)
        val index = append(
            BasisTraceOperation.Solve(carrier, false, BasisVectorRole.GENERAL, expectedDensity.toRawBits(), rhs),
            32 + 12 * n,
        )
        if (index != null) lastFtran[x] = index
    }

    override fun btran(x: IndexedVector, expectedDensity: Double) {
        val rhs = copyVector(x)
        val carrier = carrier(x)
        delegate.btran(x, expectedDensity)
        val index = append(
            BasisTraceOperation.Solve(carrier, true, BasisVectorRole.GENERAL, expectedDensity.toRawBits(), rhs),
            32 + 12 * n,
        )
        if (index != null) lastBtran[x] = index
    }

    override fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector?): BasisUpdate {
        val spikeEvidence = copyVector(spike)
        val pivotEvidence = pivotEta?.let(::copyVector)
        val result = delegate.update(pivotRow, entering, spike, pivotEta)
        val spikeOperation = lastFtran[spike]
        val pivotOperation = pivotEta?.let { lastBtran[it] }
        if (spikeOperation == null || (pivotEta != null && pivotOperation == null)) {
            truncated = true
            return result
        }
        markRole(spikeOperation, BasisVectorRole.ENTERING_SPIKE)
        pivotOperation?.let { markRole(it, BasisVectorRole.PIVOT_ROW) }
        append(
            BasisTraceOperation.Update(
                pivotRow,
                heading(entering),
                spikeOperation,
                pivotOperation,
                spikeEvidence,
                pivotEvidence,
                result,
            ),
            40 + 24 * n,
        )
        return result
    }

    override fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean): BasisSolveQuality =
        delegate.solveQuality(rhs, solution, transpose)

    override fun close() = delegate.close()

    fun finish(metadata: BasisTraceMetadata, origins: List<BasisColumnOrigin>, strict: BooleanArray): BasisTrace =
        BasisTrace(metadata.copy(truncated = truncated), matrix, sourceColumns, origins, strict, operations)

    private fun markRole(index: Int, role: BasisVectorRole) {
        val solve = operations[index] as BasisTraceOperation.Solve
        operations[index] = BasisTraceOperation.Solve(
            solve.carrier,
            solve.transpose,
            role,
            solve.expectedDensityBits,
            solve.rhs,
        )
    }

    private fun carrier(vector: IndexedVector): Int = carriers.getOrPut(vector) { nextCarrier++ }

    private fun copyVector(vector: IndexedVector): BasisTraceVector {
        val support = ArrayList<Int>()
        vector.forEachStored { index, _ -> support += index }
        return BasisTraceVector(vector.toDoubleArray(), support.toIntArray())
    }

    private fun heading(column: Int): BasisHeading = if (column < sourceColumns) {
        BasisHeading.Source(column)
    } else {
        BasisHeading.Unit(column - sourceColumns)
    }

    private fun append(operation: BasisTraceOperation, byteCost: Int): Int? {
        if (truncated || operations.size >= BASIS_TRACE_MAX_OPERATIONS ||
            estimatedBytes.toLong() + byteCost > BASIS_TRACE_MAX_PAYLOAD
        ) {
            truncated = true
            return null
        }
        estimatedBytes += byteCost
        operations += operation
        return operations.lastIndex
    }
}

private fun com.eignex.klause.lp.engine.ExactLpNumber.projectedBits(): Long =
    (ieeeBits?.let(Double::fromBits) ?: value.toDouble()).toRawBits()

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun rootStateHash(model: LpModel): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun putInt(value: Int) = digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    fun putLong(value: Long) = digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    putInt(model.n)
    putInt(model.m)
    putLong(model.objConstantD.toRawBits())
    for (row in 0 until model.m) {
        putLong(model.rhsD(row).toRawBits())
        digest.update(if (model.rowStrict[row]) 1.toByte() else 0.toByte())
    }
    for (column in 0 until model.numVars) {
        putLong(model.costD(column).toRawBits())
        putLong(model.upperD(column).toRawBits())
        digest.update(if (model.hasFiniteUpper(column)) 1.toByte() else 0.toByte())
        if (column < model.n) putLong(model.loShiftD(column).toRawBits())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
