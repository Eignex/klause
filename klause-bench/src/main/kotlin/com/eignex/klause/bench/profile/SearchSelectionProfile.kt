package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtLibProblem
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.search.CardinalitySearchComponent
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBranchKind
import com.eignex.klause.solver.search.SearchBranchProposal
import com.eignex.klause.solver.search.SearchBranchSelection
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SearchSelectionProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            val parts = argument.split('=', limit = 2)
            require(parts.size == 2) { "expected key=value" }
            parts[0] to parts[1]
        }
        require(options.keys.all { it in setOf("source64", "finite", "repeats", "warmups") })
        val source = Base64.getDecoder().decode(requireNotNull(options["source64"]))
        val parsed = SmtLib.parse(source.toString(Charsets.UTF_8))
        require(parsed.objective == null) { "selection profile accepts satisfaction sources" }
        val plan = parsed.model.componentPlan()
        require(!plan.hasCpComponent && plan.theoryPipeline in setOf(
            ProblemPipeline.EXACT_LRA, ProblemPipeline.EXACT_LIRA, ProblemPipeline.DIFFERENCE_THEORY,
        )) { "selection profile requires a complete linear source plan without CP-owned factors" }
        val clauses = parsed.model.factors.filterIsInstance<Clause>()
        val cardinalities = parsed.model.factors.filterIsInstance<Cardinality>()
        val finite = parsed.intVarNames.getValue(requireNotNull(options["finite"]))
        require(parsed.model.intBounds.hasLower(finite) && parsed.model.intBounds.hasUpper(finite))
        val sourceLower = parsed.model.intBounds.lower(finite)
        val sourceUpper = parsed.model.intBounds.upper(finite)
        require(sourceLower >= -1_000_000 && sourceUpper <= 1_000_000 && sourceUpper - sourceLower in 1..1024) {
            "finite proposal needs a small bounded source domain"
        }
        val repeats = options["repeats"]?.toInt() ?: 3
        val warmups = options["warmups"]?.toInt() ?: 1
        require(repeats in 1..100 && warmups in 0..10)
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        output.resolve("source.smt2").writeBytes(source)
        output.resolve("provenance.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(source))
            put("javaVersion", System.getProperty("java.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("timeoutMs", 10_000)
            put("javaOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("classpath", buildJsonArray {
                for (path in System.getProperty("java.class.path").split(File.pathSeparator)) {
                    val file = File(path)
                    if (file.isFile) add(buildJsonObject {
                        put("name", file.name)
                        put("sha256", sha256(file.readBytes()))
                    })
                }
            })
        }.toString())
        val kinds = SearchBranchKind.entries
        val policies = listOf("default" to SearchBranchSelection.Default) + kinds.flatMap { first ->
            kinds.filter { it != first }.map { second ->
                val order = listOf(first, second, kinds.single { it != first && it != second })
                order.joinToString(",") to SearchBranchSelection.Priority(order)
            }
        }
        val checker = SmtStreamChecker(output)
        for (round in -warmups until repeats) {
            val ordered = if (round % 2 == 0) policies else policies.reversed()
            for ((name, selection) in ordered) {
                val directory = output.resolve("round-$round-${name.replace(',', '-')}").apply { mkdirs() }
                val key = SearchModelKey(ExactLiraAssignment::class)
                val choices = ArrayList<String>()
                val stop = Cancellation.after(10.seconds)
                val started = System.nanoTime()
                val component = ExactLiraSearchComponent(parsed.model) { value, model -> model.contribute(key, value) }
                component.use { arithmetic ->
                    val proposal = object : SearchBranchProposal {
                        override val branchKind = SearchBranchKind.Finite
                        override fun nextBranch(context: SearchContext): List<SearchDecision>? {
                            val lower = maxOf(sourceLower, context.intLowerBound(finite) ?: sourceLower)
                            val upper = minOf(sourceUpper, context.intUpperBound(finite) ?: sourceUpper)
                            if (lower == upper) return null
                            if (lower > upper) return emptyList()
                            val middle = lower + (upper - lower) / 2
                            return listOf(SearchDecision.IntAtMost(finite, middle),
                                SearchDecision.IntAtLeast(finite, middle + 1))
                        }
                    }
                    val components = buildList<SearchComponent> {
                        add(ClauseSearchComponent(clauses))
                        if (cardinalities.isNotEmpty()) add(CardinalitySearchComponent(cardinalities))
                        add(arithmetic)
                    }
                    val session = SearchComponentSet(components, proposals = listOf(proposal))
                        .session(cancellation = stop, atoms = SearchAtomRegistry(parsed.model.numBoolVars))
                    val initialized = session.initialize()
                    val run = session.openRun(parsed.model.numBoolVars, branchSelection = selection,
                        observer = object : SearchRunObserver {
                            override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                                choices += decision.toString()
                            }
                        })
                    val result = if (initialized == ComponentResult.Consistent) run.next() else null
                    val elapsed = System.nanoTime() - started
                    val point = (result as? SearchRunEvent.Satisfied)?.model?.valueOf(key)
                    val stdout = if (point == null) "unknown\n" else render(parsed, point)
                    directory.resolve("input.smt2").writeBytes(source)
                    directory.resolve("stdout").writeText(stdout)
                    directory.resolve("measurement.json").writeText(buildJsonObject {
                        put("round", round)
                        put("policy", name)
                        put("elapsedNs", elapsed)
                        put("witness", point != null)
                        put("sourceSha256", sha256(source))
                        put("stdoutSha256", sha256(stdout.toByteArray()))
                        put("decisions", buildJsonArray { choices.forEach { add(JsonPrimitive(it)) } })
                        put("stopCause", session.executionControls.stopCause?.name)
                        put("independentProofCheck", false)
                    }.toString())
                    if (point != null) checker.check(directory, directory.resolve("input.smt2"),
                        interrupted = false, minimumWitnesses = 1, provision = true)
                    println("round=$round policy=$name elapsedNs=$elapsed witness=${point != null}")
                }
            }
        }
    }

    private fun render(source: SmtLibProblem, point: ExactLiraAssignment): String = buildString {
        append("sat\n(model\n")
        for ((name, index) in source.boolVarNames) {
            append("  (define-fun $name () Bool ${point.bools[index]})\n")
        }
        for ((name, index) in source.intVarNames) {
            val value = point.ints[index].toString()
            val literal = if (value.startsWith('-')) "(- ${value.drop(1)})" else value
            append("  (define-fun $name () Int $literal)\n")
        }
        for ((name, index) in source.realVarNames) {
            val value = point.reals[index]
            val numerator = value.num.toString()
            val literal = if (numerator.startsWith('-')) "(- ${numerator.drop(1)})" else numerator
            append("  (define-fun $name () Real (/ $literal ${value.den}))\n")
        }
        append(")\n")
    }
}
