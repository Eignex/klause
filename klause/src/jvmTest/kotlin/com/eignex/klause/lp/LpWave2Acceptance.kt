package com.eignex.klause.lp

import com.eignex.klause.lp.engine.B5bIndependentExactSourceValidator
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.certifyLpResult
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val SCHEMA = 1
private const val POLICY_SEED = 21L
private const val TRACE_21 = "session-2.1a-40-update"
private const val TRACE_22 = "session-2.2-32-revision"
private const val HISTORICAL_REVISION = "cd66668668851eea37350db5af0d416fb27a7a0e"

// Explicit integration entry point; ordinary JVM test discovery never runs this campaign.
fun main(arguments: Array<String>) {
    require(arguments.isNotEmpty()) { "usage: measure|compare key=value ..." }
    val options = arguments.drop(1).associate { argument ->
        val separator = argument.indexOf('=')
        require(separator > 0) { "expected key=value, got $argument" }
        argument.substring(0, separator) to argument.substring(separator + 1)
    }
    when (arguments.first()) {
        "measure" -> measure(options)
        "compare" -> compare(options)
        else -> error("unknown command ${arguments.first()}")
    }
}

private fun measure(options: Map<String, String>) {
    val repetition = options.required("repetition")
    val manifest = Path.of(options.required("manifest"))
    check(Files.isRegularFile(manifest))
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
    records.forEach { println("LpWave2Acceptance ${it.json()}") }
}

private fun measureTrace21(repetition: String, provenance: Provenance): Measurement {
    val source = exactTrace21Model()
    val trail = LpBoundTrail(source)
    val solver = newCandidateSolver(trail)
    val initial = checkNotNull(solver.solve())
    validateCandidate(trail, certifyLpResult(checkNotNull(trail.state.toWorkingModel()), solver, initial), TRACE_21)
    val initialWork = solver.lastWorkOps
    val initialFactors = solver.lastRefactorizations.toLong()
    val initialBasis = solver.basisLifecycleWork
    val stateDigest = MessageDigest.getInstance("SHA-256")
    val outcomeDigest = MessageDigest.getInstance("SHA-256")
    var elapsedNanos = 0L
    var engineWork = 0L
    var factors = 0L
    var solved = 0
    var exactAccepted = 0
    var attempts = 0
    repeat(8) { cycle ->
        for (step in 0..4) {
            val start = System.nanoTime()
            when (step) {
                0 -> {
                    check(trail.push())
                    check(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(1L)), cycle * 3L))
                }
                1 -> {
                    check(trail.push())
                    check(trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(3L)), cycle * 3L + 1L))
                }
                2 -> {
                    check(trail.push())
                    check(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), cycle * 3L + 2L))
                }
                3 -> check(trail.pop(1))
                else -> check(trail.pop(0))
            }
            check(solver.adopt(trail.state, Cancellation.Never))
            val result = solver.resolveBounds()
            elapsedNanos += System.nanoTime() - start
            val lower = longArrayOf(if (step == 2) 1L else 0L, if (step in 1..2) 3L else 0L)
            val upper = longArrayOf(if (step == 4) 10L else 1L, 10L)
            recordCandidateState(stateDigest, TRACE_21, attempts, trail, lower, upper)
            attempts++
            engineWork += solver.lastWorkOps
            factors += solver.lastRefactorizations
            if (result == null) {
                recordOutcome(outcomeDigest, attempts, "DECLINED", "NONE")
                continue
            }
            solved++
            val certified = certifyLpResult(checkNotNull(trail.state.toWorkingModel()), solver, result)
            validateCandidate(trail, certified, TRACE_21)
            val expectedX = maxOf(lower[0], minOf(upper[0], 3L - lower[1]))
            val expectedY = maxOf(lower[1], 3L - expectedX)
            check(certified.exactPrimal == listOf(expectedX, expectedY).map(BigFraction::ofLong))
            check(certified.lowerBound == BigFraction.ofLong(expectedX + 2L * expectedY))
            exactAccepted++
            recordOutcome(outcomeDigest, attempts, certified.verdict.name, checkNotNull(certified.lowerBound).toString())
        }
    }
    return finishCandidateMeasurement(
        repetition, TRACE_21, attempts, solved, exactAccepted, initialWork, initialFactors,
        engineWork, factors, elapsedNanos, initialBasis?.units, initialBasis?.complete, solver,
        stateDigest, outcomeDigest, provenance,
    )
}

private fun measureTrace22(repetition: String, provenance: Provenance): Measurement {
    val source = exactTrace22Model()
    val trail = LpBoundTrail(source)
    val solver = newCandidateSolver(trail)
    val initial = checkNotNull(solver.solve())
    validateCandidate(trail, certifyLpResult(checkNotNull(trail.state.toWorkingModel()), solver, initial), TRACE_22)
    val initialWork = solver.lastWorkOps
    val initialFactors = solver.lastRefactorizations.toLong()
    val initialBasis = solver.basisLifecycleWork
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
        val lowerValue = 1L + pair % 5
        repeat(2) { phase ->
            val start = System.nanoTime()
            if (phase == 0) {
                check(trail.push())
                check(trail.assertBound(column, false, ExactLpSide(ExactLpNumber.of(lowerValue)), pair.toLong()))
            } else {
                check(trail.pop(0))
            }
            check(solver.adopt(trail.state, Cancellation.Never))
            val result = solver.resolveBounds()
            elapsedNanos += System.nanoTime() - start
            val lower = LongArray(12)
            if (phase == 0) lower[column] = lowerValue
            recordCandidateState(stateDigest, TRACE_22, attempts, trail, lower, LongArray(12) { 40L })
            attempts++
            engineWork += solver.lastWorkOps
            factors += solver.lastRefactorizations
            if (result == null) {
                recordOutcome(outcomeDigest, attempts, "DECLINED", "NONE")
                return@repeat
            }
            solved++
            val certified = certifyLpResult(checkNotNull(trail.state.toWorkingModel()), solver, result)
            validateCandidate(trail, certified, TRACE_22)
            exactAccepted++
            recordOutcome(outcomeDigest, attempts, certified.verdict.name, checkNotNull(certified.lowerBound).toString())
        }
    }
    return finishCandidateMeasurement(
        repetition, TRACE_22, attempts, solved, exactAccepted, initialWork, initialFactors,
        engineWork, factors, elapsedNanos, initialBasis?.units, initialBasis?.complete, solver,
        stateDigest, outcomeDigest, provenance,
    )
}

private fun newCandidateSolver(trail: LpBoundTrail): PersistentLpSolver =
    ProductionLpEngineFactory.newPersistentSolver(
        checkNotNull(trail.state.toWorkingModel()),
        Cancellation.Never,
        64,
        0,
        0L,
        false,
        LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, POLICY_SEED),
    )

private fun finishCandidateMeasurement(
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
    initialBasisUnits: Long?,
    initialBasisComplete: Boolean?,
    solver: PersistentLpSolver,
    stateDigest: MessageDigest,
    outcomeDigest: MessageDigest,
    provenance: Provenance,
): Measurement {
    val endBasis = solver.basisLifecycleWork
    val basisUnits = if (initialBasisUnits != null && endBasis != null && endBasis.units >= initialBasisUnits) {
        endBasis.units - initialBasisUnits
    } else {
        -1L
    }
    val simplex = solver as RevisedSimplex
    val refactor = simplex.lastRefactorPolicyMetrics
    val repair = simplex.lastBasisRepairMetrics
    solver.close()
    val closedBasis = solver.basisLifecycleWork
    val basisComplete = initialBasisComplete == true && endBasis?.complete == true &&
        closedBasis?.complete == true && !endBasis.saturated && !closedBasis.saturated
    return Measurement(
        arm = "candidate",
        repetition = repetition,
        workload = workload,
        attempts = attempts,
        solved = solved,
        exactAccepted = exactAccepted,
        initialEngineWork = initialWork,
        initialFactorizations = initialFactors,
        engineWork = engineWork,
        basisWork = basisUnits,
        basisWorkAvailable = basisUnits >= 0L,
        basisWorkComplete = basisComplete,
        factorizations = factors,
        prepSolveNanos = elapsedNanos,
        stateSha256 = stateDigest.hex(),
        outcomeSha256 = outcomeDigest.hex(),
        policy = "MIN_BOUND_SUPPORT_NONZERO_OBJECTIVE_INACTIVE",
        seed = POLICY_SEED.toString(),
        backend = "KOTLIN_PRODUCT_FORM",
        pricingAttempts = 0L,
        refactorReasons = refactor.triggers.entries.sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name}:${it.value}" },
        repairAttempts = repair.attempts,
        fallbacks = repair.logicalFallbacks,
        unknownWork = refactor.unknownWorkEvents,
        saturatedWork = refactor.saturatedWorkEvents,
        ownerClosed = closedBasis != null,
        provenance = provenance,
    )
}

private fun validateCandidate(trail: LpBoundTrail, result: CertifiedLpResult, workload: String) {
    check(result.verdict == LpVerdict.ATTAINED_OPTIMUM)
    B5bIndependentExactSourceValidator.validate(trail.state, result)
    val primal = checkNotNull(result.exactPrimal)
    when (workload) {
        TRACE_21 -> check(primal[0] + primal[1] >= BigFraction.ofLong(3L))
        TRACE_22 -> repeat(8) { row ->
            var lhs = BigFraction.ZERO
            repeat(12) { column -> lhs += BigFraction.ofLong(trace22Coefficient(row, column)) * primal[column] }
            check(lhs >= BigFraction.ofLong(11L + 2L * row))
        }
    }
    check(result.lowerBound == result.witness?.objective)
}

private fun exactTrace21Model(): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val box = ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))
    return ExactLpModel(
        List(2) { listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))) },
        listOf(ExactLpNumber.of(-3L)),
        listOf(ExactLpColumn(box), ExactLpColumn(box), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
        listOf(ExactLpRow()),
        ExactLpObjective(listOf(ExactLpNumber.of(1L), ExactLpNumber.of(2L), zero)),
    )
}

private fun exactTrace22Model(): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val structural = List(12) {
        ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(40L))))
    }
    val logical = List(8) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) }
    return ExactLpModel(
        List(12) { column ->
            List(8) { row -> ExactLpEntry(row, ExactLpNumber.of(-trace22Coefficient(row, column))) }
        },
        List(8) { row -> ExactLpNumber.of(-(11L + 2L * row)) },
        structural + logical,
        List(8) { ExactLpRow() },
        ExactLpObjective(List(12) { ExactLpNumber.of(1L + it % 4) } + List(8) { zero }),
    )
}

private fun trace22Coefficient(row: Int, column: Int): Long =
    if (row == column) 17L else ((3 * row + 5 * column) % 7 - 3).toLong()

private fun compare(options: Map<String, String>) {
    val failures = ArrayList<String>()
    val historical = readMeasurements(Path.of(options.required("historical")), "historical", failures)
    val candidate = readMeasurements(Path.of(options.required("candidate")), "candidate", failures)
    val manifest = Path.of(options.required("manifest"))
    check(Files.isRegularFile(manifest))
    val expectedManifest = sha256(manifest)
    val expectedCandidate = provenance(manifest)
    val root = Path.of(checkNotNull(System.getProperty("klause.workspace.root")))
    val historicalSource = root.resolve(
        "klause/src/lpWave2Historical/kotlin/com/eignex/klause/lp/LpWave2HistoricalAcceptance.kt",
    )
    val expectedHistorical = Provenance(
        HISTORICAL_REVISION,
        git(root, "rev-parse", "$HISTORICAL_REVISION^{tree}"),
        sha256(historicalSource),
        expectedManifest,
    )
    options["armFailures"]?.let { failurePath ->
        val path = Path.of(failurePath)
        if (Files.isRegularFile(path)) failures += Files.readAllLines(path).filter(String::isNotBlank)
    }
    val rows = ArrayList<Comparison>()
    for (workload in listOf(TRACE_21, TRACE_22)) {
        val expectedAttempts = if (workload == TRACE_21) 40 else 32
        val controls = historical.filter { it.workload == workload }
        val candidates = candidate.filter { it.workload == workload }
        val controlByRepetition = controls.groupBy(Measurement::repetition)
        val candidateByRepetition = candidates.groupBy(Measurement::repetition)
        for (repetition in 0..2) {
            val key = repetition.toString()
            if (controlByRepetition[key]?.size != 1) {
                failures += "$workload historical repetition $repetition is missing or duplicated"
            }
            if (candidateByRepetition[key]?.size != 1) {
                failures += "$workload candidate repetition $repetition is missing or duplicated"
            }
        }
        val pairs = (0..2).mapNotNull { repetition ->
            val key = repetition.toString()
            val control = controlByRepetition[key]?.singleOrNull()
            val proposed = candidateByRepetition[key]?.singleOrNull()
            if (control == null || proposed == null) null else control to proposed
        }
        pairs.forEach { (control, proposed) ->
            if (control.attempts != expectedAttempts || proposed.attempts != expectedAttempts) {
                failures += "$workload repetition ${proposed.repetition} violates the fixed attempt denominator"
            }
            if (control.stateSha256 != proposed.stateSha256) {
                failures += "$workload repetition ${proposed.repetition} source state mismatch"
            }
            if (control.outcomeSha256 != proposed.outcomeSha256) {
                failures += "$workload repetition ${proposed.repetition} exact outcome mismatch"
            }
            if (control.solved != control.attempts || proposed.solved != proposed.attempts ||
                control.exactAccepted != control.attempts || proposed.exactAccepted != proposed.attempts
            ) {
                failures += "$workload repetition ${proposed.repetition} has a solve or exact decline"
            }
            if (proposed.policy != "MIN_BOUND_SUPPORT_NONZERO_OBJECTIVE_INACTIVE" || proposed.seed != "21" ||
                control.policy != "HARRIS_LARGEST_PIVOT_IMPLICIT" || control.seed != "UNSUPPORTED_INACTIVE" ||
                control.backend != proposed.backend
            ) {
                failures += "$workload repetition ${proposed.repetition} execution identity mismatch"
            }
            if (control.arm != "historical" || proposed.arm != "candidate" ||
                control.provenance != expectedHistorical ||
                proposed.provenance != expectedCandidate ||
                proposed.provenance.manifestSha256 != expectedManifest
            ) {
                failures += "$workload repetition ${proposed.repetition} provenance mismatch"
            }
            if (!proposed.basisWorkAvailable || !proposed.basisWorkComplete || proposed.unknownWork != 0L ||
                proposed.saturatedWork != 0L || !proposed.ownerClosed
            ) {
                failures += "$workload repetition ${proposed.repetition} candidate work or owner accounting incomplete"
            }
        }
        val fixedCountComplete = controls.size == 3 && candidates.size == 3 && pairs.size == 3
        val historicalWork = if (fixedCountComplete) controls.map(Measurement::engineWork).median() else 0L
        val candidateWork = if (fixedCountComplete) candidates.map(Measurement::engineWork).median() else 0L
        val historicalTime = if (fixedCountComplete) controls.map(Measurement::prepSolveNanos).median() else 0L
        val candidateTime = if (fixedCountComplete) candidates.map(Measurement::prepSolveNanos).median() else 0L
        val workInputsValid = fixedCountComplete && historicalWork > 0L && candidateWork >= 0L
        val timeInputsValid = fixedCountComplete && historicalTime > 0L && candidateTime > 0L
        if (!workInputsValid) failures += "$workload engine-work comparison has invalid failed-arm values"
        if (!timeInputsValid) failures += "$workload wall-time comparison has invalid failed-arm values"
        val workRatio = if (workInputsValid) candidateWork.toDouble() / historicalWork else 0.0
        val timeRatio = if (timeInputsValid) candidateTime.toDouble() / historicalTime else 0.0
        if (workInputsValid && workRatio > 0.95) failures += "$workload engine-work ratio $workRatio exceeds 0.95"
        if (timeInputsValid && timeRatio > 1.05) failures += "$workload wall-time ratio $timeRatio exceeds 1.05"
        rows += Comparison(
            workload, controls.size, candidates.size, fixedCountComplete && workInputsValid && timeInputsValid,
            historicalWork, candidateWork, workRatio, historicalTime, candidateTime, timeRatio,
        )
    }
    if (rows.size == 2 && rows.all { it.historicalWork > 0L && it.candidateWork >= 0L }) {
        val aggregateRatio = rows.sumOf { it.candidateWork }.toDouble() / rows.sumOf { it.historicalWork }
        if (aggregateRatio > 0.95) failures += "aggregate engine-work ratio $aggregateRatio exceeds 0.95"
    }
    failures += "captured bound-only CP factorization reduction is not established by direct-engine source-derived traces"
    failures += "rule-8 wall-time performance is not established by descriptive busy-host timings"
    val passed = failures.isEmpty()
    val summary = buildString {
        append("{\"schema\":$SCHEMA,\"passed\":$passed,\"capturedCpFactorization\":\"NOT_ESTABLISHED\"")
        append(",\"wallTimeEvidence\":\"DESCRIPTIVE_NOT_ESTABLISHED\"")
        append(",\"manifestSha256\":\"").append(expectedManifest).append("\"")
        append(",\"comparisons\":[")
        append(rows.joinToString(",", transform = Comparison::json))
        append("],\"failures\":[")
        append(failures.joinToString(",") { "\"${escape(it)}\"" })
        append("]}")
    }
    val output = Path.of(options.required("summary"))
    Files.createDirectories(output.parent)
    Files.writeString(output, "$summary\n")
    println(summary)
    check(passed) { failures.joinToString("; ") }
}

private data class Comparison(
    val workload: String,
    val historicalRepetitions: Int,
    val candidateRepetitions: Int,
    val metricsValid: Boolean,
    val historicalWork: Long,
    val candidateWork: Long,
    val workRatio: Double,
    val historicalTime: Long,
    val candidateTime: Long,
    val timeRatio: Double,
) {
    fun json(): String = "{\"workload\":\"$workload\",\"requiredRepetitions\":3," +
        "\"historicalRepetitions\":$historicalRepetitions,\"candidateRepetitions\":$candidateRepetitions," +
        "\"metricsValid\":$metricsValid,\"historicalEngineWork\":$historicalWork," +
        "\"candidateEngineWork\":$candidateWork,\"engineWorkRatio\":$workRatio," +
        "\"historicalPrepSolveNanos\":$historicalTime,\"candidatePrepSolveNanos\":$candidateTime," +
        "\"prepSolveTimeRatio\":$timeRatio}"
}

private data class Provenance(
    val revision: String,
    val tree: String,
    val sourceSha256: String,
    val manifestSha256: String,
)

private data class Measurement(
    val arm: String,
    val repetition: String,
    val workload: String,
    val attempts: Int,
    val solved: Int,
    val exactAccepted: Int,
    val initialEngineWork: Long,
    val initialFactorizations: Long,
    val engineWork: Long,
    val basisWork: Long,
    val basisWorkAvailable: Boolean,
    val basisWorkComplete: Boolean,
    val factorizations: Long,
    val prepSolveNanos: Long,
    val stateSha256: String,
    val outcomeSha256: String,
    val policy: String,
    val seed: String,
    val backend: String,
    val pricingAttempts: Long,
    val refactorReasons: String,
    val repairAttempts: Long,
    val fallbacks: Long,
    val unknownWork: Long,
    val saturatedWork: Long,
    val ownerClosed: Boolean,
    val provenance: Provenance,
) {
    fun json(): String = buildString {
        append("{\"schema\":$SCHEMA,\"arm\":\"$arm\",\"repetition\":\"$repetition\"")
        append(",\"workload\":\"$workload\",\"route\":\"DIRECT_ENGINE_SOURCE_DERIVED\"")
        append(",\"revision\":\"${provenance.revision}\",\"tree\":\"${provenance.tree}\"")
        append(",\"sourceSha256\":\"${provenance.sourceSha256}\"")
        append(",\"manifestSha256\":\"${provenance.manifestSha256}\"")
        append(",\"policy\":\"$policy\",\"seed\":\"$seed\",\"backend\":\"$backend\"")
        append(",\"attempts\":$attempts,\"solved\":$solved,\"declines\":${attempts - solved}")
        append(",\"exactAccepted\":$exactAccepted,\"exactDeclines\":${attempts - exactAccepted}")
        append(",\"initialEngineWork\":$initialEngineWork,\"initialFactorizations\":$initialFactorizations")
        append(",\"engineWork\":$engineWork,\"basisWork\":$basisWork")
        append(",\"basisWorkAvailable\":$basisWorkAvailable,\"basisWorkComplete\":$basisWorkComplete")
        append(",\"factorizations\":$factorizations,\"prepSolveNanos\":$prepSolveNanos")
        append(",\"stateSha256\":\"$stateSha256\",\"outcomeSha256\":\"$outcomeSha256\"")
        append(",\"pricingAttempts\":$pricingAttempts,\"refactorReasons\":\"${escape(refactorReasons)}\"")
        append(",\"repairAttempts\":$repairAttempts,\"fallbacks\":$fallbacks")
        append(",\"unknownWork\":$unknownWork,\"saturatedWork\":$saturatedWork")
        append(",\"ownerClosed\":$ownerClosed}")
    }
}

private fun readMeasurements(path: Path, arm: String, failures: MutableList<String>): List<Measurement> {
    if (!Files.isRegularFile(path)) {
        failures += "$arm record file is missing"
        return emptyList()
    }
    return Files.readAllLines(path).mapIndexedNotNull { index, line ->
        if (line.isBlank()) return@mapIndexedNotNull null
        try {
            val value = Json.parseToJsonElement(line) as JsonObject
            Measurement(
                arm = value.string("arm"), repetition = value.string("repetition"),
                workload = value.string("workload"), attempts = value.long("attempts").toInt(),
                solved = value.long("solved").toInt(), exactAccepted = value.long("exactAccepted").toInt(),
                initialEngineWork = value.long("initialEngineWork"),
                initialFactorizations = value.long("initialFactorizations"), engineWork = value.long("engineWork"),
                basisWork = value.long("basisWork"), basisWorkAvailable = value.boolean("basisWorkAvailable"),
                basisWorkComplete = value.boolean("basisWorkComplete"),
                factorizations = value.long("factorizations"), prepSolveNanos = value.long("prepSolveNanos"),
                stateSha256 = value.string("stateSha256"), outcomeSha256 = value.string("outcomeSha256"),
                policy = value.string("policy"), seed = value.string("seed"), backend = value.string("backend"),
                pricingAttempts = value.long("pricingAttempts"), refactorReasons = value.string("refactorReasons"),
                repairAttempts = value.long("repairAttempts"), fallbacks = value.long("fallbacks"),
                unknownWork = value.long("unknownWork"), saturatedWork = value.long("saturatedWork"),
                ownerClosed = value.boolean("ownerClosed"),
                provenance = Provenance(
                    value.string("revision"), value.string("tree"), value.string("sourceSha256"),
                    value.string("manifestSha256"),
                ),
            )
        } catch (failure: Throwable) {
            failures += "$arm record line ${index + 1} is invalid: ${failure.message.orEmpty()}"
            null
        }
    }
}

private fun JsonObject.string(name: String): String = checkNotNull(this[name]).jsonPrimitive.content
private fun JsonObject.long(name: String): Long = checkNotNull(this[name]).jsonPrimitive.long
private fun JsonObject.boolean(name: String): Boolean = checkNotNull(this[name]).jsonPrimitive.boolean
private fun List<Long>.median(): Long = sorted()[size / 2]
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
        arm = "candidate",
        repetition = repetition,
        workload = workload,
        attempts = attempts,
        solved = 0,
        exactAccepted = 0,
        initialEngineWork = -1L,
        initialFactorizations = -1L,
        engineWork = -1L,
        basisWork = -1L,
        basisWorkAvailable = false,
        basisWorkComplete = false,
        factorizations = -1L,
        prepSolveNanos = 0L,
        stateSha256 = stateDigest.hex(),
        outcomeSha256 = outcomeDigest.hex(),
        policy = "MIN_BOUND_SUPPORT_NONZERO_OBJECTIVE_INACTIVE",
        seed = POLICY_SEED.toString(),
        backend = "KOTLIN_PRODUCT_FORM",
        pricingAttempts = 0L,
        refactorReasons = reason,
        repairAttempts = 0L,
        fallbacks = 0L,
        unknownWork = 0L,
        saturatedWork = 0L,
        ownerClosed = false,
        provenance = provenance,
    )
}

private fun provenance(manifest: Path): Provenance {
    val root = Path.of(checkNotNull(System.getProperty("klause.workspace.root")))
    val source = root.resolve("klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpWave2Acceptance.kt")
    return Provenance(git(root, "rev-parse", "HEAD"), git(root, "rev-parse", "HEAD^{tree}"), sha256(source), sha256(manifest))
}

private fun git(root: Path, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0) { output }
    return output
}

private fun recordCandidateState(
    digest: MessageDigest,
    workload: String,
    step: Int,
    trail: LpBoundTrail,
    expectedLower: LongArray,
    expectedUpper: LongArray,
) {
    val lower = List(expectedLower.size) { column ->
        checkNotNull(trail.state.model.column(column).bounds.lower).number.value.toString()
    }
    val upper = List(expectedUpper.size) { column ->
        checkNotNull(trail.state.model.column(column).bounds.upper).number.value.toString()
    }
    check(lower == expectedLower.map { BigFraction.ofLong(it).toString() })
    check(upper == expectedUpper.map { BigFraction.ofLong(it).toString() })
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
