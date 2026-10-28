package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.IndomainRandom
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.ProbingSelectors
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal object TaskOrderProfile {
    private const val DECISIONS = 256L
    private const val TRACE_LIMIT = 128

    private class Arm(
        val name: String,
        val orders: Boolean = false,
        val prepare: String = "off",
        val values: ValueSelector = IndomainMin,
        val variables: VariableSelector = InputOrder,
        val lp: Boolean = false,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        out.resolve("runtime.txt").writeText(listOf(
            System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"),
            ManagementFactory.getRuntimeMXBean().inputArguments.toString(),
        ).joinToString("\n"))
        val arms = listOf(
            Arm("minimum"), Arm("midpoint", values = IndomainMiddle),
            Arm("impact", values = ProbingSelectors.impact()), Arm("lp", lp = true),
            Arm("prepared", prepare = "default"), Arm("probing", prepare = "aggressive"),
            Arm("default-search", prepare = "default", values = IndomainRandom, variables = Vsids()),
            Arm("orders", orders = true), Arm("orders-prepared", orders = true, prepare = "default"),
        )
        for (equal in listOf(false, true)) {
            val factors = arrayListOf<Factor>(Cumulative.unary(intArrayOf(0, 1), longArrayOf(2, 2)))
            if (equal) factors += Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)
            val source = Problem(0, 2, Array(2) { IntDomain(0, 31) }, factors.toTypedArray())
            val name = if (equal) "equal-starts" else "free-pair"
            for (arm in arms) capture(source, name, arm, out)
            prefixes(source, name, out)
        }
        if (args.isNotEmpty()) inspectSource(args, out)
    }

    private fun capture(
        source: Problem,
        name: String,
        arm: Arm,
        out: File,
        annotated: BacktrackParams? = null,
    ) {
        val directory = out.resolve("$name/${arm.name}").apply { mkdirs() }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val allocation = bean.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        val stop = Cancellation.after(5.seconds)
        val model = if (arm.orders) withOrderPair(source) else source
        val prepared = PresolvePipeline.run(model, null, PresolveConfig.parse(arm.prepare),
            solutionSetSensitive = true, cancellation = stop, randomSeed = 1)
        val baked = prepared.problem.bake(cancellation = stop)
        val preparationNs = System.nanoTime() - started
        val trace = ArrayList<String>()
        val base = annotated ?: BacktrackParams(variableSelector = arm.variables, valueSelector = arm.values)
        val values = TracedValues(base.valueSelector, trace)
        val variables = if (arm.orders && model !== source) PairFirst(source.numBoolVars) else base.variableSelector
        val result = BacktrackSolver(baked).solve(base.copy(
            maxDecisions = DECISIONS, randomSeed = 1, cancellation = stop, valueSelector = values,
            variableSelector = variables,
            lpConfig = if (arm.lp) LpConfig.AGGRESSIVE else null,
        ))
        val solveNs = System.nanoTime() - started - preparationNs
        val sample = (result as? SolveResult.Sat)?.assignment?.let(prepared.reconstruct)
        val checked = sample?.let { FiniteConflictSelection.checkSource(model.bake(cancellation = stop), it, stop) }
        directory.resolve("trace.json").writeText(JsonArray(trace.map(::JsonPrimitive)).toString())
        directory.resolve("summary.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("source", name)
            put("arm", arm.name)
            put("decisionCap", DECISIONS)
            put("timeoutSeconds", 5)
            put("annotationSelected", annotated != null)
            put("sourceBooleans", source.numBoolVars)
            put("addedOrderBooleans", model.numBoolVars - source.numBoolVars)
            put("addedOrderFactors", model.factors.size - source.factors.size)
            put("preparedUnaryFactors", baked.factors.filterIsInstance<Cumulative>().count { it.unary })
            put("preparedUnaryShapes", JsonArray(baked.factors.filterIsInstance<Cumulative>()
                .filter { it.unary }.map { JsonPrimitive(unaryShape(it)) }))
            put("preparedDifferenceSystems", baked.factors.count { it.javaClass.simpleName == "DifferenceSystem" })
            put("preparedFactors", baked.factors.size)
            put("preparationNs", preparationNs)
            put("solveNs", solveNs)
            put("sourceCheckNs", System.nanoTime() - started - preparationNs - solveNs)
            put("threadAllocatedBytesIncludingCheck", bean.getThreadAllocatedBytes(thread) - allocation)
            put("result", result.javaClass.simpleName)
            put("nativeWitnessAccepted", checked)
            put("independentSourceProof", false)
            put("nodes", result.stats.search.nodes.sum)
            put("fails", result.stats.search.fails.sum)
            put("learnedClauses", result.stats.search.learnedClauses.sum)
            put("propagationWork", result.stats.search.propagationWork.sum)
            put("traceCapped", trace.size >= TRACE_LIMIT)
            put("deadlineReached", stop())
        }.toString())
    }

    private fun prefixes(source: Problem, name: String, out: File) {
        val ordered = withOrderPair(source)
        val rows = ArrayList<String>()
        for (value in listOf(false, true)) {
            val session = PropagationSession(ordered)
            val before = domains(session)
            val result = session.pinBool(source.numBoolVars, value)
            val after = domains(session)
            session.popToLevel(0)
            rows += buildJsonObject {
                put("forward", value)
                put("result", result.javaClass.simpleName)
                put("before", before)
                put("after", after)
                put("afterUndo", domains(session))
            }.toString()
        }
        out.resolve("$name/prefixes.jsonl").writeText(rows.joinToString("\n", postfix = "\n"))
    }

    internal fun withOrderPair(source: Problem): Problem {
        val pair = source.factors.filterIsInstance<Cumulative>().firstOrNull {
            it.unary && it.presents.isEmpty() && it.durationVars.isEmpty() && it.n >= 2 &&
                it.durations.all { duration -> duration > 0 } && it.starts.toSet().size == it.n
        } ?: return source
        val a = pair.starts[0]
        val b = pair.starts[1]
        val bool = source.numBoolVars
        val factors = source.factors + arrayOf<Factor>(
            ReifiedLinear(bool, longArrayOf(1, -1), intArrayOf(a, b), LinearOp.LE, -pair.durations[0]),
            ReifiedLinear(bool + 1, longArrayOf(-1, 1), intArrayOf(a, b), LinearOp.LE, -pair.durations[1]),
            Clause(intArrayOf(Lit.make(bool, true), Lit.make(bool + 1, true))),
        )
        return Problem(
            source.numBoolVars + 2, source.numIntVars, source.declaredIntDomains, factors,
            impliedFactorMask = source.impliedFactorMask?.copyOf(factors.size),
            hasSymmetryBreaking = source.hasSymmetryBreaking,
            numRealVars = source.numRealVars, realLower = source.realLower, realUpper = source.realUpper,
            settings = source.settings, semantics = source.semantics,
        )
    }

    private fun inspectSource(args: Array<String>, out: File) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "modelSha256", "dataSha256"))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        for ((label, input) in listOf("model" to reference.source, "data" to requireNotNull(reference.data))) {
            val bytes = CorpusFiles.open(CorpusFetcher.resolve(input)).use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024 && sha256(bytes) == options.getValue("${label}Sha256"))
            out.resolve("source-$label.txt").writeBytes(bytes)
        }
        val resolved = Runners.resolve(reference)
        val compiler = MiniZincRunner()
        compiler.compileFzn(reference).copyTo(out.resolve("source.fzn"))
        compiler.compileOutput(reference).copyTo(out.resolve("source.ozn"))
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        val source = resolved.problem
        out.resolve("source-shape.json").writeText(buildJsonObject {
            put("suite", options.getValue("suite"))
            put("problem", reference.name)
            put("modelSha256", options.getValue("modelSha256"))
            put("dataSha256", options.getValue("dataSha256"))
            put("hasAnnotation", resolved.searchParams != null)
            put("unaryFactors", source.factors.filterIsInstance<Cumulative>().count { it.unary })
            put("eligiblePairFound", withOrderPair(source).numBoolVars > source.numBoolVars)
            put("objective", resolved.objective.toString())
            put("scope", "satisfaction projection; no objective or original MiniZinc proof claim")
        }.toString())
        capture(source, "original", Arm("annotated", prepare = "default"), out, resolved.searchParams)
        for (arm in listOf(Arm("prepared", prepare = "default"),
            Arm("default-search", prepare = "default", values = IndomainRandom, variables = Vsids()),
            Arm("orders-prepared", orders = true, prepare = "default"))) {
            capture(source, "original", arm, out)
        }
    }

    private class TracedValues(private val delegate: ValueSelector, private val trace: MutableList<String>) :
        ValueSelector by delegate {
        override fun fresh(): ValueSelector = TracedValues(delegate.fresh(), trace)

        override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> {
            record("choose $varRef level=${session.decisionLevel} domains=${domains(session)}")
            return delegate.values(session, varRef, rng)
        }

        override fun onCommit(varRef: VarRef, value: Long) {
            record("commit $varRef=$value")
            delegate.onCommit(varRef, value)
        }

        override fun onConflict(varRef: VarRef, value: Long) {
            record("conflict $varRef=$value")
            delegate.onConflict(varRef, value)
        }

        private fun record(value: String) {
            if (trace.size < TRACE_LIMIT) trace += value
        }
    }

    private class PairFirst(private val bool: Int) : VariableSelector by InputOrder {
        override fun fresh(): VariableSelector = PairFirst(bool)

        override fun pick(session: PropagationSession, rng: Random): VarRef? =
            if (session.needsBoolDecision(bool)) VarRef.Bool(bool) else InputOrder.pick(session, rng)
    }

    private fun domains(session: PropagationSession): String =
        (0 until minOf(session.problem.numIntVars, 32)).joinToString(",") {
            val domain = session.intDomain(it)
            "$it:${domain.min}..${domain.max}"
        }

    private fun unaryShape(factor: Cumulative): String =
        "tasks=${factor.n},presence=${factor.presents.size},durationVars=${factor.durationVars.size}," +
            "zeros=${factor.durations.count { it == 0L }},sharedStarts=${factor.n - factor.starts.toSet().size}"
}
