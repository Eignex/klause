package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.MZN_RANDOM_SEED
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.Lit
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal const val PINNED_SOURCE_POLICY = "minizinc-source-witness-v2"
internal const val REPORTED_RESULT_POLICY = "reported-result-v1"

@Serializable
internal data class SourceValidation(
    val status: String,
    val reason: String,
    val sourceHashes: Map<String, String> = emptyMap(),
    val scope: String = "minizinc-source",
)

internal object MiniZincSourceValidation {
    private const val TIMEOUT_SECONDS = 10L
    private val ASSIGNMENT = Regex("^\\s*[A-Za-z_][A-Za-z_0-9]*\\s*=")

    fun validate(ref: ProblemRef, rawOutput: String, objective: Double?, exactObjective: String? = null): SourceValidation {
        val hashes = SolveEvidence.sourceHashes(ref)
        val candidate = candidate(rawOutput) ?: if (exactCoordinates(rawOutput) != null) "" else
            return SourceValidation("unknown", "no complete DZN solution", hashes)
        val directory = Files.createTempDirectory("klause-source-check-").toFile()
        return runCatching {
            val runner = MiniZincRunner()
            val exact = exactCoordinates(rawOutput)
            if (exact != null) {
                return@runCatching ExactFlatZincValidation.inspect(
                    runner.compileFzn(ref).readText(), exact,
                    exactObjective ?: exact.lineSequence().firstOrNull { it.startsWith("_objective = ") }
                        ?.substringAfter('=' )?.trim()?.removeSuffix(";") ?: objective?.toString(),
                )
            }
            val mapped = if (Regex("\\bX_INTRODUCED_[0-9]+_").containsMatchIn(candidate)) {
                reconstruct(runner.compileOutput(ref), candidate, directory)
            } else {
                candidate
            }
            val pins = mapped.replace(Regex("(?m)^\\s*_objective\\s*=\\s*[^;]+;"), "")
            val data = File(directory, "candidate.dzn").apply { writeText(pins) }
            val fzn = File(directory, "checked.fzn")
            val log = File(directory, "compile.log")
            val command = buildList {
                addAll(listOf("minizinc", "--solver", "org.minizinc.mzn-fzn", "-c", "--allow-multiple-assignments"))
                addAll(listOf("--random-seed", MZN_RANDOM_SEED.toString(), "--output-fzn-to-file", fzn.absolutePath))
                if (ref.source is ProblemSource.External) {
                    val source = ref.source
                    val root = CorpusFetcher.ensure(source.collection)
                    for (include in source.collection.includeDirs) {
                        val dir = File(root, include)
                        if (dir.isDirectory) addAll(listOf("-I", dir.absolutePath))
                    }
                }
                add(CorpusFetcher.resolve(ref.source).absolutePath)
                ref.data?.let { add(CorpusFetcher.resolve(it).absolutePath) }
                add(data.absolutePath)
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            try {
                when {
                    !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) ->
                        SourceValidation("unknown", "source compilation timed out")

                    process.exitValue() != 0 || !fzn.exists() ->
                        SourceValidation("unknown", "source compilation failed: ${log.readText().take(500)}")

                    else -> inspect(fzn.readText(), objective, exactObjective)
                }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }.getOrElse { failure ->
            SourceValidation("unknown", "source checking failed: ${failure.message.orEmpty().take(500)}")
        }.also { directory.deleteRecursively() }.let { validation ->
            if (SolveEvidence.sourceHashes(ref) != hashes) {
                SourceValidation("unknown", "source files changed during validation", hashes)
            } else {
                validation.copy(sourceHashes = hashes)
            }
        }
    }

    private fun reconstruct(ozn: File, candidate: String, directory: File): String {
        val input = File(directory, "flat.dzn").apply { writeText("$candidate\n----------\n") }
        val output = File(directory, "source.out")
        val log = File(directory, "output.log")
        val process = ProcessBuilder("minizinc", "--ozn-file", ozn.absolutePath)
            .redirectInput(input).redirectOutput(output).redirectError(log).start()
        try {
            require(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "source output reconstruction timed out" }
            require(process.exitValue() == 0) { "source output reconstruction failed: ${log.readText().take(300)}" }
            return requireNotNull(candidate(output.readText())) { "no reconstructed source solution" }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }

    fun exactCoordinates(rawOutput: String): String? = rawOutput.split("----------").dropLast(1).lastOrNull()
        ?.lineSequence()?.filter { it.startsWith("% klause-exact: ") }
        ?.map { it.removePrefix("% klause-exact: ") }?.toList()?.takeIf { it.isNotEmpty() }?.joinToString("\n")

    fun retainedWitness(rawOutput: String): String? = (candidate(rawOutput) ?:
        if (exactCoordinates(rawOutput) != null) "" else null)?.let { candidate ->
        val exact = exactCoordinates(rawOutput)
        if (exact == null) candidate else candidate + "\n" + exact.lineSequence().joinToString("\n") { "% klause-exact: $it" }
    }

    fun candidate(rawOutput: String): String? {
        val complete = rawOutput.split("----------").dropLast(1).lastOrNull() ?: return null
        val lines = complete.lineSequence().filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith('%') && !trimmed.startsWith("=====")
        }.toList()
        val start = lines.indexOfFirst { ASSIGNMENT.containsMatchIn(it) }
        return if (start < 0) null else lines.drop(start).joinToString("\n")
    }

    fun inspect(flatZinc: String, objective: Double?, exactObjective: String? = null): SourceValidation = runCatching {
        inspectProgram(parseFlatZinc(flatZinc, exactFloats = true), objective, exactObjective)
    }.getOrElse { failure ->
        SourceValidation(
            "unknown",
            "source checking cannot inspect compiled model: ${failure.message.orEmpty().take(500)}",
        )
    }

    private fun inspectProgram(program: FlatZincProgram, objective: Double?, exactObjective: String?): SourceValidation {
        if (program.solve != SolveDirective.Satisfy && objective == null && exactObjective == null) {
            return SourceValidation("unknown", "missing reported source objective")
        }
        val problem = program.problem
        val values = HashMap<Int, Boolean>()
        val clauses = problem.factors.filterIsInstance<Clause>()
        for (clause in clauses.filter { it.literals.size == 1 }) {
            val literal = clause.literals.single()
            val value = Lit.isPositive(literal)
            val previous = values.put(Lit.variable(literal), value)
            if (previous != null && previous != value) {
                return SourceValidation("invalid", "source constraints reject candidate")
            }
        }
        val falseClause = clauses.any { clause ->
            clause.literals.all { literal ->
                values[Lit.variable(literal)]?.let { !Lit.evaluate(literal, it) } == true
            }
        }
        if (falseClause) return SourceValidation("invalid", "source constraints reject candidate")
        val fixedIntegers = (0 until problem.numIntVars).all { v ->
            problem.intBounds.hasLower(v) && problem.intBounds.hasUpper(v) &&
                problem.intBounds.lower(v) == problem.intBounds.upper(v)
        }
        val fixedReals = problem.realLower.indices.all { v ->
            problem.realLower[v].isFinite() && problem.realLower[v] == problem.realUpper[v]
        }
        val trueClauses = clauses.all { clause ->
            clause.literals.any { literal ->
                values[Lit.variable(literal)]?.let { Lit.evaluate(literal, it) } == true
            }
        }
        return if (!fixedIntegers || !fixedReals || values.size != problem.numBoolVars ||
            clauses.size != problem.factors.size || !trueClauses
        ) {
            SourceValidation("unknown", "source compilation leaves residual variables or constraints")
        } else if ((objective != null || exactObjective != null) &&
            ExactObjective.parse(fixedObjective(program, values).orEmpty())?.compareTo(
                ExactObjective.value(exactObjective, objective) ?: return SourceValidation("unknown", "malformed reported objective"),
            ) != 0) {
            SourceValidation("invalid", "reported objective differs from pinned source objective")
        } else {
            SourceValidation("valid", "source constraints evaluate true with candidate pinned")
        }
    }

    private fun fixedObjective(program: FlatZincProgram, bools: Map<Int, Boolean>): String? {
        val name = when (val solve = program.solve) {
            is SolveDirective.Minimize -> solve.objVar
            is SolveDirective.Maximize -> solve.objVar
            SolveDirective.Satisfy -> return null
        }
        program.floatVarsByName[name]?.let { return it.lo.toString() }
        program.intVarsByName[name]?.let { return program.problem.intBounds.lower(it).toString() }
        return program.boolVarsByName[name]?.let { if (bools[it] == true) "1" else "0" }
    }
}

internal fun SolveRecord.sourceChecked(validation: SourceValidation, approximation: Boolean): SolveRecord {
    val reject = validation.status == "invalid" ||
        (approximation && (feasible == false || (feasible == true && validation.status != "valid")))
    val verifiedStats = stats + mapOf(
        "sourceValidation" to validation.status,
        "sourceValidationReason" to validation.reason,
        "sourceValidationScope" to validation.scope,
        "floatApproximation" to approximation.toString(),
        "reportedFeasible" to feasible.toString(),
        "reportedObjective" to objective.toString(),
        "reportedExactObjective" to exactObjective.toString(),
        "reportedProven" to proven.toString(),
    )
    return copy(
        feasible = if (reject) null else feasible,
        objective = if (reject) null else objective,
        exactObjective = if (reject) null else exactObjective,
        proven = proven && !reject && !approximation,
        timeToBestMs = if (reject) null else timeToBestMs,
        processTimeToFirstFeasibleMs = if (reject) null else processTimeToFirstFeasibleMs,
        processTimeToBestMs = if (reject) null else processTimeToBestMs,
        timeToFirstFeasibleMs = if (reject) null else timeToFirstFeasibleMs,
        attribution = if (reject) {
            emptyList()
        } else {
            attribution.filter { incumbent ->
                val retained = ExactObjective.value(exactObjective, objective)
                val attributed = ExactObjective.value(
                    incumbent.exactObjective.takeIf { incumbent.continuousObjective == null },
                    incumbent.continuousObjective ?: incumbent.objective,
                )
                retained == null || attributed?.compareTo(retained) == 0
            }
        },
        stats = verifiedStats,
    )
}
