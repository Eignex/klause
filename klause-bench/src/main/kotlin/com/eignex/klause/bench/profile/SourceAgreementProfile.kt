package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.pipeline.ComponentResponsibility
import com.eignex.klause.solver.pipeline.ExecutionComponentDescriptor
import com.eignex.klause.solver.pipeline.PlannedSearch
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SourceAgreementProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("repeats", "warmups", "cases") })
        val repeats = options["repeats"]?.toInt() ?: 3
        val warmups = options["warmups"]?.toInt() ?: 1
        require(repeats in 1..100 && warmups in 0..10)
        val selected = options["cases"]?.split(',')?.toSet()
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val cases = cases().filter { selected == null || it.name in selected }
        require(cases.isNotEmpty() && (selected == null || cases.map { it.name }.toSet() == selected))
        output.resolve("provenance.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("javaOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
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
        val access = RegistrationAccess()
        val checker = SmtStreamChecker(output)
        for (round in -warmups until repeats) {
            for (case in cases) {
                val modes = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
                for (registered in modes) {
                    run(case, registered, round, access, checker, output)
                }
            }
        }
    }

    private fun run(
        case: SourceCase,
        registered: Boolean,
        round: Int,
        access: RegistrationAccess,
        checker: SmtStreamChecker,
        output: File,
    ) {
        val mode = if (registered) "registered" else "combined"
        val directory = output.resolve("${case.name}-$mode-$round").apply { mkdirs() }
        val sourceFile = directory.resolve("input.smt2").apply { writeText(case.original) }
        var work = 0L
        val deadline = Cancellation.after(10.seconds)
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = deadline()
            override fun deadline() = deadline.deadline()
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val started = System.nanoTime()
        val points = ArrayList<AssembledSearchModel>()
        var result: String
        var checks: Long
        var stopCause: String?
        if (registered) {
            access.open(case.model, stop, case.checkLimit).use { search ->
                result = execute(case, search.session, points)
                checks = access.checks(search.session)
                stopCause = search.session.executionControls.stopCause?.name
            }
        } else {
            ExactLiraSearchComponent(case.model) { assignment, model -> contribute(case.model, assignment, model) }
                .use { component ->
                    val session = SearchComponentSet(listOf(component)).session(case.checkLimit, stop,
                        SearchLearnedDbParams(), SearchAtomRegistry(case.model.numBoolVars))
                    result = execute(case, session, points)
                    checks = access.checks(session)
                    stopCause = session.executionControls.stopCause?.name
                }
        }
        val elapsed = System.nanoTime() - started
        val stdout = if (points.isEmpty()) (if (result == "exhausted") "unsat\n" else "unknown\n")
            else points.map { point -> render(case, point) }.joinToString("")
        directory.resolve("stdout").writeText(stdout)
        directory.resolve("measurement.json").writeText(buildJsonObject {
            put("case", case.name)
            put("mode", mode)
            put("round", round)
            put("result", result)
            put("elapsedNs", elapsed)
            put("nativeWork", work)
            put("checks", checks)
            put("checkLimit", case.checkLimit)
            put("timeoutMs", 10000)
            put("stopCause", stopCause)
            put("sourceSha256", sha256(case.original.toByteArray()))
            put("stdoutSha256", sha256(stdout.toByteArray()))
            put("witnesses", points.size)
            put("independentProofCheck", false)
        }.toString())
        if (points.isNotEmpty()) checker.check(directory, sourceFile,
            interrupted = false, minimumWitnesses = points.size, provision = true)
        println("case=${case.name} mode=$mode round=$round result=$result " +
            "elapsedNs=$elapsed nativeWork=$work checks=$checks")
    }

    private fun execute(
        case: SourceCase,
        session: SearchSession,
        points: MutableList<AssembledSearchModel>,
    ): String {
        if (case.guarded) {
            if (session.initialize() != ComponentResult.Consistent) return "indeterminate"
            for (value in listOf(true, false)) {
                session.popTo(0)
                if (session.push(SearchDecision.Bool(if (value) 0 else 1)) != ComponentResult.Consistent ||
                    session.check() != ComponentCheck.Feasible
                ) return "indeterminate"
                points += session.model()
            }
            return "satisfied"
        }
        return when (val result = session.solve(case.model.numBoolVars)) {
            is SearchResult.Satisfied -> { points += result.model; "satisfied" }
            SearchResult.Exhausted -> "exhausted"
            SearchResult.Indeterminate -> "indeterminate"
        }
    }

    private fun contribute(source: Problem, assignment: ExactLiraAssignment, model: SearchModel) {
        assignment.ints.forEachIndexed { index, value ->
            model.contribute(SearchExactIntValue(SourceIntId(index)), value)
        }
        assignment.reals.forEachIndexed { index, value ->
            model.contribute(SearchExactRealValue(SourceRealId(index)), value)
        }
        require(assignment.ints.size == source.numIntVars && assignment.reals.size == source.numRealVars)
    }

    private fun render(case: SourceCase, point: AssembledSearchModel): String = buildString {
        append("sat\n(model\n")
        if (case.guarded) {
            val value = requireNotNull(point.valueOf(SearchBoolValue(0)))
            append("  (define-fun b () Bool $value)\n")
        }
        if (case.model.numIntVars != 0) {
            val value = requireNotNull(point.valueOf(SearchExactIntValue(SourceIntId(0)))).toString()
            append("  (define-fun x () Int ${if (value.startsWith('-')) "(- ${value.drop(1)})" else value})\n")
        } else {
            val value = requireNotNull(point.valueOf(SearchExactRealValue(SourceRealId(0))))
            val numerator = value.num.toString()
            val text = if (numerator.startsWith('-')) "(- ${numerator.drop(1)})" else numerator
            append("  (define-fun x () Real (/ $text ${value.den}))\n")
        }
        append(")\n")
    }

    // Internal registration measurement stays outside the published solver API.
    private class RegistrationAccess {
        private val checkCounter = SearchSession::class.java.getDeclaredField("checks").apply { isAccessible = true }
        fun checks(session: SearchSession): Long = checkCounter.getLong(session)

        private val registration = Class.forName("com.eignex.klause.solver.pipeline.PreparedSearchRegistration")
        private val registrations = Class.forName("com.eignex.klause.solver.pipeline.PreparedSearchRegistrations")
        private val descriptor = ExecutionComponentDescriptor::class.java.declaredConstructors
            .single { it.parameterCount == 9 }
        private val factory = registration.getDeclaredConstructor(Problem::class.java,
            ExecutionComponentDescriptor::class.java, kotlin.jvm.functions.Function1::class.java)
        private val collection = registrations.getDeclaredConstructor(Problem::class.java, List::class.java)
        private val open = registrations.getMethod("open", java.lang.Long.TYPE, Cancellation::class.java,
            SearchLearnedDbParams::class.java, SmtStatsSink::class.java, Cancellation::class.java)
        fun open(source: Problem, stop: Cancellation, maxChecks: Long): PlannedSearch {
            val values = source.factors.indices.map { index ->
                val fragment = source.withFactors(arrayOf(source.factors[index]))
                val description = descriptor.newInstance("linear-$index", ComponentResponsibility.COMPLETION,
                    listOf(index), if (index == 0) (0 until source.numIntVars).toList() else emptyList<Int>(),
                    if (index == 0) (0 until source.numRealVars).toList() else emptyList<Int>(),
                    if (index == 0) (0 until source.numBoolVars).toList() else emptyList<Int>(), null, 64, null)
                val opening: (Any) -> List<SearchComponent> = { scope ->
                    val component = ExactLiraSearchComponent(fragment) { assignment, model ->
                        if (index == 0) contribute(source, assignment, model)
                    }
                    scope.javaClass.getMethod("own", AutoCloseable::class.java).invoke(scope, component)
                    listOf(component)
                }
                factory.newInstance(source, description, opening)
            }
            return open.invoke(collection.newInstance(source, values), maxChecks, stop,
                SearchLearnedDbParams(), null, null) as PlannedSearch
        }
    }

    private data class SourceCase(
        val name: String,
        val original: String,
        val model: Problem,
        val guarded: Boolean = false,
        val checkLimit: Long = Long.MAX_VALUE,
    )

    private fun cases(): List<SourceCase> {
        fun real(name: String, owner: LinearOp, ownerBound: Double, peer: LinearOp, peerBound: Double,
            strict: Boolean = false, checkLimit: Long = Long.MAX_VALUE): SourceCase {
            val symbol = when (peer) {
                LinearOp.EQ -> "="
                LinearOp.GE -> if (strict) ">" else ">="
                else -> "<="
            }
            val first = if (owner == LinearOp.GE) ">=" else if (owner == LinearOp.EQ) "=" else "<="
            val source = "(set-logic QF_LRA)\n(declare-const x Real)\n(assert ($first x $ownerBound))\n" +
                "(assert ($symbol x $peerBound))\n(check-sat)\n(get-model)\n"
            return SourceCase(name, source, Problem(0, 0, emptyArray(), arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), owner, ownerBound),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), peer, peerBound, strict),
            ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)), checkLimit = checkLimit)
        }
        val result = arrayListOf(
            real("compatible-real", LinearOp.GE, 0.0, LinearOp.EQ, 1.0),
            real("conflicting-real", LinearOp.EQ, 0.0, LinearOp.EQ, 1.0),
            real("strict-real", LinearOp.GE, 0.5, LinearOp.GE, 0.5, strict = true),
            real("check-zero", LinearOp.GE, 0.0, LinearOp.EQ, 1.0, checkLimit = 0),
        )
        for (negative in listOf(false, true)) {
            val lower = parseBigInt(if (negative) "-9223372036854775810" else "9223372036854775808")
            val expected = lower + BIG_ONE
            val bound = expected.toString().let { if (it.startsWith('-')) "(- ${it.drop(1)})" else it }
            val start = lower.toString().let { if (it.startsWith('-')) "(- ${it.drop(1)})" else it }
            val source = "(set-logic QF_LIA)\n(declare-const x Int)\n(assert (>= x $start))\n" +
                "(assert (= x $bound))\n(check-sat)\n(get-model)\n"
            val declarations = SmtLib.parse("(declare-const x Int)").model
            result += SourceCase(if (negative) "wide-negative" else "wide-positive", source,
                Problem(0, intBounds = declarations.intBounds, factors = arrayOf(
                    Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.GE, lower),
                    Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, expected),
                )))
            val conflicting = "(set-logic QF_LIA)\n(declare-const x Int)\n(assert (= x $start))\n" +
                "(assert (= x $bound))\n(check-sat)\n"
            result += SourceCase(if (negative) "conflicting-wide-negative" else "conflicting-wide-positive",
                conflicting, Problem(0, intBounds = declarations.intBounds, factors = arrayOf(
                    Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, lower),
                    Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, expected),
                )))
        }
        val guarded = "(set-logic QF_LRA)\n(declare-const b Bool)\n(declare-const x Real)\n" +
            "(assert (>= x 0))\n(assert (<= x 2))\n(assert (= b (>= x 1)))\n(check-sat)\n(get-model)\n"
        result += SourceCase("guarded-retraction", guarded, Problem(1, 0, emptyArray(), arrayOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0),
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0)), guarded = true)
        return result
    }
}
