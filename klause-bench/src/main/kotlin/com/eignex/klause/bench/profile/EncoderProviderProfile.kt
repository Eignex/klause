package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.PreparedPlanDecline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingAffineExpression
import com.eignex.klause.theory.EncodingLinearTerm
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.LinearEncodingFormula
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Compare default and private equivalent encoders through source routing and independent SMT witness checks. */
object EncoderProviderProfile {
    /** Repeated preparation/execution controls over caller-supplied, factor-admitted SMT source files. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("inputs", "repeats", "warmups", "seed", "parser") })
        val repeats = options["repeats"]?.toInt() ?: 3
        val warmups = options["warmups"]?.toInt() ?: 1
        val parser = options["parser"] ?: "admission"
        require(parser in setOf("admission", "factor"))
        val seed = options["seed"]?.toLong() ?: 1L
        require(repeats in 1..100 && warmups in 0..10)
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_ENCODER_MEASURED_JVM") != "1") {
            launch(args, output)
            return
        }
        val inputs = requireNotNull(options["inputs"]) { "inputs=<comma-separated SMT paths> is required" }
            .split(',').map { path -> File(path).takeIf { it.isAbsolute } ?: File(CorpusFetcher.workspaceRoot(), path) }
        require(inputs.isNotEmpty() && inputs.all(File::isFile))
        output.resolve("provenance.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("javaOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("parser", parser)
            put("seed", seed)
            put("repeats", repeats)
            put("warmups", warmups)
            put("classpath", buildJsonArray {
                System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
                    .filter(File::isFile).forEach { file -> add(buildJsonObject {
                        put("name", file.name)
                        put("sha256", sha256(file.readBytes()))
                    }) }
            })
        }.toString())
        val checker = SmtStreamChecker(output)
        for ((index, input) in inputs.withIndex()) {
            val bytes = input.readBytes()
            val problem = if (parser == "factor") SmtLib.parse(bytes.decodeToString()) else {
                val parsed = SmtLibAdmission.parse(bytes.decodeToString())
                require(parsed is SmtLibInput.Linear) { "input is not a factor-admitted SMT source: $input" }
                parsed.problem
            }
            resourceControl(problem, bytes, output.resolve("$index-resource").apply { mkdirs() })
            for (round in -warmups until repeats) {
                val results = ArrayList<String>()
                for (alternate in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val directory = output.resolve("$index-${if (alternate) "private" else "default"}-$round")
                        .apply { mkdirs() }
                    val source = directory.resolve("source.smt2").apply { writeBytes(bytes) }
                    var work = 0L
                    var installations = 0
                    val deadline = Cancellation.after(10.seconds)
                    val stop = object : Cancellation {
                        override fun isCancelled(): Boolean = deadline()
                        override fun deadline() = deadline.deadline()
                        override fun workMeter(): WorkMeter = WorkMeter { work += it }
                    }
                    val providers = providers(alternate) { installations++ }
                    val started = System.nanoTime()
                    val route = problem.model.pipelineRoute(providers, routePureRealToTheory = true,
                        routeLinearToTheory = true, boundCancellation = stop)
                    val result = when (route) {
                        is SourceProblemRoute.OpenTheory -> {
                            val execution = OpenTheoryPipeline.execute(route.request,
                                TheoryParams(cancellation = stop, randomSeed = seed), FiniteEngine.BACKTRACK)
                            require(execution is OpenTheoryExecution.Satisfy)
                            execution.result
                        }
                        is SourceProblemRoute.PolynomialTheory -> OpenTheoryPipeline.execute(route.request,
                            PolynomialTheoryParams(cancellation = stop, strategy = PolynomialStrategyParams(
                                backtrack = BacktrackParams(randomSeed = seed))), FiniteEngine.BACKTRACK).result
                        is SourceProblemRoute.Finite -> BacktrackSolver.source(problem.model)
                            .withProjectionProviders(providers).solve(BacktrackParams(cancellation = stop,
                                randomSeed = seed))
                        SourceProblemRoute.Refuted -> OpenTheoryResult.Unsat(SolveStats.EMPTY)
                        is SourceProblemRoute.UnsupportedOpen ->
                            error("unsupported source route: ${route.preparedPlan.decline}")
                    }
                    val elapsed = System.nanoTime() - started
                    val verdict = when (result) {
                        is OpenTheoryResult.Sat -> "sat"
                        is OpenTheoryResult.Unsat -> "unsat"
                        is OpenTheoryResult.Unknown -> "unknown"
                    }
                    results += verdict
                    val stdout = if (result is OpenTheoryResult.Sat) render(problem, result.assignment)
                        else "$verdict\n"
                    directory.resolve("stdout").writeText(stdout)
                    directory.resolve("measurement.json").writeText(buildJsonObject {
                        put("revision", Reports.readGitSha())
                        put("sourceSha256", sha256(bytes))
                        put("stdoutSha256", sha256(stdout.toByteArray()))
                        put("mode", if (alternate) "private" else "default")
                        put("round", round)
                        put("verdict", verdict)
                        put("elapsedNs", elapsed)
                        put("nativeWork", work)
                        put("installations", installations)
                        put("seed", seed)
                        put("timeoutMs", 10000)
                        put("independentProofCheck", false)
                    }.toString())
                    if (result is OpenTheoryResult.Sat) checker.check(directory, source,
                        interrupted = false, minimumWitnesses = 1, provision = true)
                    println("source=$index alternate=$alternate round=$round verdict=$verdict " +
                        "elapsedNs=$elapsed nativeWork=$work installations=$installations")
                }
                check(results.distinct().size == 1) { "encoder modes disagreed for $input round=$round: $results" }
            }
        }
    }

    private fun resourceControl(problem: SmtLibProblem, source: ByteArray, directory: File) {
        var cancelled = false
        var installations = 0
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val descriptor = BuiltInEncoderProvider.select(factor, target)
            if (!descriptor.enforcesSource) descriptor else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installations++
                val result = descriptor.install(view, encoding)
                encoding.cancellation.charge(1)
                cancelled = true
                check(encoding.cancellation()) { "installer cannot observe the containing token" }
                result
            }
        })
        val route = problem.model.pipelineRoute(selected, routeLinearToTheory = true, boundCancellation = stop)
        val decline = (route as? SourceProblemRoute.UnsupportedOpen)?.preparedPlan?.decline
        if (installations != 0) check(decline == PreparedPlanDecline.RESOURCE) {
            "interrupted construction was admitted"
        }
        directory.resolve("source.smt2").writeBytes(source)
        directory.resolve("measurement.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(source))
            put("nativeWork", work)
            put("installations", installations)
            put("outcome", if (installations == 0) "inapplicable" else "resource")
            put("independentProofCheck", false)
        }.toString())
    }

    private fun providers(alternate: Boolean, installed: () -> Unit): FactorProjectionProviders =
        FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (alternate && target == EncodingTarget.POLYNOMIAL && selected.enforcesSource) {
                Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                    installed()
                    selected.install(view, encoding)
                }
            } else if (alternate && target == EncodingTarget.DIFFERENCE) Encoder(EncoderSupport.UNSUPPORTED)
            else if (!alternate || target != EncodingTarget.LINEAR || !selected.enforcesSource ||
                factor.linearForm?.rows?.any { it.activator != LinearRow.ALWAYS } != false
            ) selected else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installed()
                val form = requireNotNull(view.linearForm)
                val comparisons = form.rows.map { row ->
                    val private = encoding.realAuxiliary()
                    val coefficients = List(row.size) { coefficient(row, it) }
                    val definition = List(row.size) { index ->
                        EncodingLinearTerm.source(row.ref(index), coefficients[index].negated())
                    } + EncodingLinearTerm.auxiliary(private, BigFraction.ONE)
                    encoding.linearFormula(LinearEncodingFormula.Comparison(EncodingAffineExpression(definition),
                        LinearOp.EQ, BigFraction.ZERO))
                    LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
                        EncodingLinearTerm.auxiliary(private, BigFraction.ONE),
                    )), row.relation, bound(row), row.strict)
                }
                encoding.linearFormula(if (form is LinearForm.Disjunction) LinearEncodingFormula.Or(comparisons)
                    else LinearEncodingFormula.And(comparisons))
                null
            }
        })

    private fun coefficient(row: LinearRow, index: Int): BigFraction = when (val constants = row.constants) {
        is IntegralConstants -> BigFraction.of(constants.exactCoeff(index), BIG_ONE)
        is RealConstants -> requireNotNull(BigFraction.ofDouble(if (index < constants.intCoefficients.size) {
            constants.intCoefficients.at(index)
        } else constants.realCoefficients.at(index - constants.intCoefficients.size)))
    }

    private fun bound(row: LinearRow): BigFraction = when (val constants = row.constants) {
        is IntegralConstants -> BigFraction.of(constants.exactBound, BIG_ONE)
        is RealConstants -> requireNotNull(BigFraction.ofDouble(constants.bound))
    }

    private fun render(source: SmtLibProblem, assignment: OpenTheoryAssignment): String = buildString {
        append("sat\n(model\n")
        for ((name, id) in source.boolVarNames) append("(define-fun $name () Bool ${assignment.boolValue(id)})\n")
        for ((name, id) in source.intVarNames) append("(define-fun $name () Int ${signed(assignment.intValue(id))})\n")
        for ((name, id) in source.realVarNames) {
            val parts = assignment.realValue(id).split('/')
            append("(define-fun $name () Real (/ ${signed(parts[0])} ${parts.getOrElse(1) { "1" }}))\n")
        }
        append(")\n")
    }

    private fun signed(value: String): String = if (value.startsWith('-')) "(- ${value.drop(1)})" else value

    private fun launch(args: Array<String>, output: File) {
        val command = arrayListOf(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "--enable-native-access=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector")
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        output.resolve("launcher.json").writeText(buildJsonObject {
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        }.toString())
        val builder = ProcessBuilder(command).inheritIO()
        builder.environment()["KLAUSE_ENCODER_MEASURED_JVM"] = "1"
        check(builder.start().waitFor() == 0) { "encoder measurement JVM failed" }
    }
}
