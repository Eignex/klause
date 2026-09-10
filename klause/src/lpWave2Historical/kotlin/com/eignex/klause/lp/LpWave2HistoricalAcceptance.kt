package com.eignex.klause.lp

import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.certifyLpResult
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private const val SCHEMA = 1
private const val TRACE_21 = "session-2.1a-40-update"
private const val TRACE_22 = "session-2.2-32-revision"

// Compiled only into the detached Wave 1 worktree by the isolated acceptance init script.
fun main(arguments: Array<String>) {
    require(arguments.firstOrNull() == "measure")
    val options = arguments.drop(1).associate { argument ->
        val separator = argument.indexOf('=')
        require(separator > 0)
        argument.substring(0, separator) to argument.substring(separator + 1)
    }
    val repetition = options.required("repetition")
    val manifest = Path.of(options.required("manifest"))
    val provenance = provenance(manifest)
    val records = listOf(
        measureSafely(repetition, TRACE_21, 40, provenance) { measureTrace21(repetition, provenance) },
        measureSafely(repetition, TRACE_22, 32, provenance) { measureTrace22(repetition, provenance) },
    )
    if (repetition != "warmup") {
        val output = Path.of(options.required("output"))
        Files.createDirectories(output.parent)
        Files.writeString(
            output,
            records.joinToString(separator = "\n", postfix = "\n", transform = Measurement::json),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
    records.forEach { println("LpWave2HistoricalAcceptance ${it.json()}") }
}

private fun measureTrace21(repetition: String, provenance: Provenance): Measurement {
    val base = LpBuilder().apply {
        val x = addVar(0L, 10L, cost = 1L)
        val y = addVar(0L, 10L, cost = 2L)
        addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
    }.build(Sense.MINIMIZE)
    val solver = RevisedSimplex(base)
    val initial = checkNotNull(solver.solve())
    independentOptimum(base, initial)
    val initialWork = solver.lastWorkOps
    val initialFactors = solver.lastRefactorizations.toLong()
    val stateDigest = MessageDigest.getInstance("SHA-256")
    val outcomeDigest = MessageDigest.getInstance("SHA-256")
    var elapsedNanos = 0L
    var engineWork = 0L
    var factors = 0L
    var solved = 0
    var exactAccepted = 0
    var attempts = 0
    repeat(8) {
        for (step in 0..4) {
            val lower = longArrayOf(if (step == 2) 1L else 0L, if (step in 1..2) 3L else 0L)
            val upper = longArrayOf(if (step == 4) 10L else 1L, 10L)
            val start = System.nanoTime()
            val active = base.rebind(lower, upper)
            check(solver.rebind(active, Cancellation.Never))
            val result = solver.resolveBounds()
            elapsedNanos += System.nanoTime() - start
            recordHistoricalState(stateDigest, TRACE_21, attempts, active, lower, upper)
            attempts++
            engineWork += solver.lastWorkOps
            factors += solver.lastRefactorizations
            if (result == null) {
                recordOutcome(outcomeDigest, attempts, "DECLINED", "NONE")
                continue
            }
            solved++
            val optimum = independentOptimum(active, result)
            val certified = certifyLpResult(active, solver, result)
            check(certified.verdict == LpVerdict.ATTAINED_OPTIMUM)
            check(certified.exactPrimal == optimum.primal)
            check(certified.lowerBound == optimum.objective)
            val expectedX = maxOf(lower[0], minOf(upper[0], 3L - lower[1]))
            val expectedY = maxOf(lower[1], 3L - expectedX)
            check(optimum.primal == listOf(expectedX, expectedY).map(BigFraction::ofLong))
            check(optimum.objective == BigFraction.ofLong(expectedX + 2L * expectedY))
            exactAccepted++
            recordOutcome(outcomeDigest, attempts, LpVerdict.ATTAINED_OPTIMUM.name, optimum.objective.toString())
        }
    }
    solver.close()
    return measurement(
        repetition, TRACE_21, attempts, solved, exactAccepted, initialWork, initialFactors,
        engineWork, factors, elapsedNanos, stateDigest, outcomeDigest, provenance,
    )
}

private fun measureTrace22(repetition: String, provenance: Provenance): Measurement {
    val builder = LpBuilder()
    val columns = IntArray(12) { column -> builder.addVar(0L, 40L, cost = 1L + column % 4) }
    repeat(8) { row ->
        builder.addRow(columns, LongArray(12) { column -> trace22Coefficient(row, column) }, Relation.GE, 11L + 2L * row)
    }
    val base = builder.build(Sense.MINIMIZE)
    val solver = RevisedSimplex(base)
    val initial = checkNotNull(solver.solve())
    independentOptimum(base, initial)
    val initialWork = solver.lastWorkOps
    val initialFactors = solver.lastRefactorizations.toLong()
    val stateDigest = MessageDigest.getInstance("SHA-256")
    val outcomeDigest = MessageDigest.getInstance("SHA-256")
    var elapsedNanos = 0L
    var engineWork = 0L
    var factors = 0L
    var solved = 0
    var exactAccepted = 0
    var attempts = 0
    repeat(16) { pair ->
        val column = (5 * pair + 1) % 12
        val value = 1L + pair % 5
        repeat(2) { phase ->
            val lower = LongArray(12)
            if (phase == 0) lower[column] = value
            val upper = LongArray(12) { 40L }
            val start = System.nanoTime()
            val active = base.rebind(lower, upper)
            check(solver.rebind(active, Cancellation.Never))
            val result = solver.resolveBounds()
            elapsedNanos += System.nanoTime() - start
            recordHistoricalState(stateDigest, TRACE_22, attempts, active, lower, upper)
            attempts++
            engineWork += solver.lastWorkOps
            factors += solver.lastRefactorizations
            if (result == null) {
                recordOutcome(outcomeDigest, attempts, "DECLINED", "NONE")
                return@repeat
            }
            solved++
            val optimum = independentOptimum(active, result)
            val certified = certifyLpResult(active, solver, result)
            check(certified.verdict == LpVerdict.ATTAINED_OPTIMUM)
            check(certified.exactPrimal == optimum.primal)
            check(certified.lowerBound == optimum.objective)
            validateTrace22Source(optimum.primal, lower, upper)
            exactAccepted++
            recordOutcome(outcomeDigest, attempts, LpVerdict.ATTAINED_OPTIMUM.name, optimum.objective.toString())
        }
    }
    solver.close()
    return measurement(
        repetition, TRACE_22, attempts, solved, exactAccepted, initialWork, initialFactors,
        engineWork, factors, elapsedNanos, stateDigest, outcomeDigest, provenance,
    )
}

private data class ExactOptimum(val primal: List<BigFraction>, val objective: BigFraction)

// Independent test-only primal/dual KKT check from the returned basis and the active exact integer model.
private fun independentOptimum(model: LpModel, result: FloatLpResult): ExactOptimum {
    val basis = result.basis
    check(basis.basicVars.size == model.m && basis.status.size == model.numVars)
    val values = arrayOfNulls<BigFraction>(model.numVars)
    for (column in 0 until model.numVars) {
        values[column] = when (basis.status[column]) {
            VarStatus.BASIC -> null
            VarStatus.AT_LOWER -> BigFraction.ZERO
            VarStatus.AT_UPPER -> {
                check(model.hasUpper[column])
                BigFraction.ofLong(model.upper[column])
            }
        }
    }
    val matrix = Array(model.m) { row ->
        Array(model.m) { position -> coefficient(model, row, basis.basicVars[position]) }
    }
    val rhs = Array(model.m) { row -> BigFraction.ofLong(model.rhs[row]) }
    for (column in 0 until model.numVars) {
        val value = values[column] ?: continue
        for (row in 0 until model.m) rhs[row] -= coefficient(model, row, column) * value
    }
    val basicValues = solveExact(matrix, rhs)
    basis.basicVars.forEachIndexed { position, column -> values[column] = basicValues[position] }
    for (column in 0 until model.numVars) {
        val value = checkNotNull(values[column])
        check(value >= BigFraction.ZERO)
        if (model.hasUpper[column]) check(value <= BigFraction.ofLong(model.upper[column]))
    }

    val transpose = Array(model.m) { position -> Array(model.m) { row -> matrix[row][position] } }
    val dual = solveExact(transpose, Array(model.m) { position -> BigFraction.ofLong(model.cost[basis.basicVars[position]]) })
    for (column in 0 until model.numVars) {
        var reduced = BigFraction.ofLong(model.cost[column])
        for (row in 0 until model.m) reduced -= coefficient(model, row, column) * dual[row]
        when (basis.status[column]) {
            VarStatus.BASIC -> check(reduced.isZero)
            VarStatus.AT_LOWER -> check(reduced >= BigFraction.ZERO)
            VarStatus.AT_UPPER -> check(reduced <= BigFraction.ZERO)
        }
    }
    var objective = BigFraction.ofLong(model.objConstant)
    for (column in 0 until model.numVars) objective += BigFraction.ofLong(model.cost[column]) * checkNotNull(values[column])
    val primal = List(model.n) { column -> checkNotNull(values[column]) + BigFraction.ofLong(model.loShift[column]) }
    validateSource(model, primal)
    return ExactOptimum(primal, objective)
}

private fun solveExact(coefficients: Array<Array<BigFraction>>, constants: Array<BigFraction>): Array<BigFraction> {
    val n = constants.size
    val rows = Array(n) { row -> Array(n + 1) { column -> if (column == n) constants[row] else coefficients[row][column] } }
    for (column in 0 until n) {
        val pivot = (column until n).firstOrNull { !rows[it][column].isZero }
            ?: error("singular returned basis")
        if (pivot != column) {
            val temporary = rows[column]
            rows[column] = rows[pivot]
            rows[pivot] = temporary
        }
        val scale = rows[column][column].reciprocal()
        for (entry in column until n + 1) rows[column][entry] = rows[column][entry] * scale
        for (row in 0 until n) {
            if (row == column || rows[row][column].isZero) continue
            val multiplier = rows[row][column]
            for (entry in column until n + 1) rows[row][entry] -= multiplier * rows[column][entry]
        }
    }
    return Array(n) { rows[it][n] }
}

private fun coefficient(model: LpModel, row: Int, column: Int): BigFraction {
    if (column >= model.n) return BigFraction.ofLong(if (column - model.n == row) 1L else 0L)
    for (entry in model.csc.colPtr[column] until model.csc.colPtr[column + 1]) {
        if (model.csc.rowIdx[entry] == row) return BigFraction.ofLong(model.csc.colVal[entry])
    }
    return BigFraction.ZERO
}

private fun validateSource(model: LpModel, primal: List<BigFraction>) {
    if (model.n == 2) {
        check(primal[0] + primal[1] >= BigFraction.ofLong(3L))
    } else {
        validateTrace22Source(
            primal,
            LongArray(model.n) { model.loShift[it] },
            LongArray(model.n) { model.loShift[it] + model.upper[it] },
        )
    }
}

private fun validateTrace22Source(primal: List<BigFraction>, lower: LongArray, upper: LongArray) {
    repeat(12) { column ->
        check(primal[column] >= BigFraction.ofLong(lower[column]))
        check(primal[column] <= BigFraction.ofLong(upper[column]))
    }
    repeat(8) { row ->
        var lhs = BigFraction.ZERO
        repeat(12) { column -> lhs += BigFraction.ofLong(trace22Coefficient(row, column)) * primal[column] }
        check(lhs >= BigFraction.ofLong(11L + 2L * row))
    }
}

private fun trace22Coefficient(row: Int, column: Int): Long =
    if (row == column) 17L else ((3 * row + 5 * column) % 7 - 3).toLong()

private fun measurement(
    repetition: String,
    workload: String,
    attempts: Int,
    solved: Int,
    exactAccepted: Int,
    initialWork: Long,
    initialFactors: Long,
    engineWork: Long,
    factors: Long,
    elapsedNanos: Long,
    stateDigest: MessageDigest,
    outcomeDigest: MessageDigest,
    provenance: Provenance,
): Measurement = Measurement(
    repetition, workload, attempts, solved, exactAccepted, initialWork, initialFactors, engineWork,
    factors, elapsedNanos, stateDigest.hex(), outcomeDigest.hex(), provenance,
)

private data class Provenance(
    val revision: String,
    val tree: String,
    val sourceSha256: String,
    val manifestSha256: String,
)

private data class Measurement(
    val repetition: String,
    val workload: String,
    val attempts: Int,
    val solved: Int,
    val exactAccepted: Int,
    val initialEngineWork: Long,
    val initialFactorizations: Long,
    val engineWork: Long,
    val factorizations: Long,
    val prepSolveNanos: Long,
    val stateSha256: String,
    val outcomeSha256: String,
    val provenance: Provenance,
    val failure: String? = null,
    val ownerClosed: Boolean = true,
) {
    fun json(): String = buildString {
        append("{\"schema\":$SCHEMA,\"arm\":\"historical\",\"repetition\":\"$repetition\"")
        append(",\"workload\":\"$workload\",\"route\":\"DIRECT_ENGINE_SOURCE_DERIVED\"")
        append(",\"revision\":\"${provenance.revision}\",\"tree\":\"${provenance.tree}\"")
        append(",\"sourceSha256\":\"${provenance.sourceSha256}\"")
        append(",\"manifestSha256\":\"${provenance.manifestSha256}\"")
        append(",\"policy\":\"HARRIS_LARGEST_PIVOT_IMPLICIT\",\"seed\":\"UNSUPPORTED_INACTIVE\"")
        append(",\"backend\":\"KOTLIN_PRODUCT_FORM\",\"attempts\":$attempts,\"solved\":$solved")
        append(",\"declines\":${attempts - solved},\"exactAccepted\":$exactAccepted")
        append(",\"exactDeclines\":${attempts - exactAccepted},\"initialEngineWork\":$initialEngineWork")
        append(",\"initialFactorizations\":$initialFactorizations,\"engineWork\":$engineWork")
        append(",\"basisWork\":-1,\"basisWorkAvailable\":false,\"basisWorkComplete\":false")
        append(",\"factorizations\":$factorizations,\"prepSolveNanos\":$prepSolveNanos")
        append(",\"stateSha256\":\"$stateSha256\",\"outcomeSha256\":\"$outcomeSha256\"")
        append(",\"pricingAttempts\":0,\"refactorReasons\":\"${escape(failure ?: "UNAVAILABLE_ACCOUNTING_V1")}\"")
        append(",\"repairAttempts\":0,\"fallbacks\":0,\"unknownWork\":0,\"saturatedWork\":0")
        append(",\"ownerClosed\":$ownerClosed}")
    }
}

private fun provenance(manifest: Path): Provenance {
    val checkout = Path.of(checkNotNull(System.getProperty("klause.workspace.root")))
    val harness = Path.of(checkNotNull(System.getProperty("lpWave2.harnessRoot")))
    val source = harness.resolve("klause/src/lpWave2Historical/kotlin/com/eignex/klause/lp/LpWave2HistoricalAcceptance.kt")
    return Provenance(
        git(checkout, "rev-parse", "HEAD"),
        git(checkout, "rev-parse", "HEAD^{tree}"),
        sha256(source),
        sha256(manifest),
    )
}

private fun git(root: Path, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { output }
    return output
}

private fun recordHistoricalState(
    digest: MessageDigest,
    workload: String,
    step: Int,
    model: LpModel,
    expectedLower: LongArray,
    expectedUpper: LongArray,
) {
    check(model.n == expectedLower.size && model.n == expectedUpper.size)
    val lower = List(model.n) { model.loShift[it].toString() }
    val upper = List(model.n) { column ->
        check(model.hasUpper[column])
        (model.loShift[column] + model.upper[column]).toString()
    }
    check(lower == expectedLower.map(Long::toString))
    check(upper == expectedUpper.map(Long::toString))
    digest.record("$workload|$step|${lower.joinToString(",")}|${upper.joinToString(",")}")
}

private fun recordOutcome(digest: MessageDigest, step: Int, verdict: String, objective: String) =
    digest.record("$step|$verdict|$objective")

private fun MessageDigest.record(value: String) {
    update(value.encodeToByteArray())
    update(0.toByte())
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    .joinToString("") { "%02x".format(it) }
private fun escape(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}
private fun Map<String, String>.required(name: String): String = requireNotNull(this[name]) { "missing $name" }

private inline fun measureSafely(
    repetition: String,
    workload: String,
    attempts: Int,
    provenance: Provenance,
    block: () -> Measurement,
): Measurement = try {
    block()
} catch (failure: Throwable) {
    val reason = "RUNNER_FAILURE:${failure.javaClass.name}:${failure.message.orEmpty()}"
    val stateDigest = MessageDigest.getInstance("SHA-256").apply { record(reason) }
    val outcomeDigest = MessageDigest.getInstance("SHA-256").apply { record(reason) }
    Measurement(
        repetition = repetition,
        workload = workload,
        attempts = attempts,
        solved = 0,
        exactAccepted = 0,
        initialEngineWork = -1L,
        initialFactorizations = -1L,
        engineWork = -1L,
        factorizations = -1L,
        prepSolveNanos = 0L,
        stateSha256 = stateDigest.hex(),
        outcomeSha256 = outcomeDigest.hex(),
        provenance = provenance,
        failure = reason,
        ownerClosed = false,
    )
}
