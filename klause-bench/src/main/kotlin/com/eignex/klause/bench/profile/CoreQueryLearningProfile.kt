package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal object CoreQueryLearningProfile {
    private data class Query(val handle: Any, val model: BakedProblem, val params: BacktrackParams)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.contentEquals(arrayOf("confirm=true")))
        val repeats = if (args.isEmpty()) 0..2 else 1..1
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val lifetime = Cancellation.after(120.seconds)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val clauses = buildList {
            for (p in listOf(-2, 2)) for (r in listOf(-3, 3)) add("1 $p $r 0")
            for (t in listOf(-6, 6)) for (w in listOf(-7, 7)) add("4 5 $t $w 0")
        }
        val sourceText = "p cnf 7 8\n${clauses.joinToString("\n")}\n"
        out.resolve("source.cnf").writeText(sourceText)
        val objective = LinearObjective(longArrayOf(0, 0, 0, 1, 1, 0, 0))
        out.resolve("objective.txt").writeText("0,0,0,1,1,0,0")
        val source = Dimacs.parse(sourceText).toProblem()
        val sourceBaked = source.bake()
        out.resolve("learning.jsonl").bufferedWriter().use { output ->
            for (prepared in listOf(false, true)) for (repeat in repeats) {
                if (lifetime()) break
                val before = bean.getThreadAllocatedBytes(thread)
                val started = System.nanoTime()
                val preparationStart = System.nanoTime()
                val ordinary = if (prepared) FinitePipeline.prepare(FinitePipelineRequest(
                    source, FiniteEngine.BACKTRACK, objective,
                    cancellation = lifetime or Cancellation.after(2.seconds),
                )) else null
                val preparationNs = System.nanoTime() - preparationStart
                val model = ordinary?.problem as? BakedProblem ?: sourceBaked
                val cost = ordinary?.objective ?: objective
                val queries = ArrayList<Query>()
                val openedPins = ArrayList<String>()
                var owner: ResumableSearch? = null
                val stop = lifetime or Cancellation.after(2.seconds) or Cancellation {
                    val live = owner
                    if (live != null && field(live, "queryModel") != null) {
                        val pins = (field(live, "querySofts") as IntArray).joinToString(",")
                        if (openedPins.lastOrNull() != pins) openedPins += pins
                    }
                    val handle = live?.let { field(it, "query") }
                    if (handle != null && queries.none { it.handle === handle }) {
                        check(queries.size < 8)
                        queries += Query(handle, field(requireNotNull(live), "queryModel") as BakedProblem,
                            field(handle, "params") as BacktrackParams)
                    }
                    false
                }
                val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin,
                    nativeSat = false, pbLearning = false, cancellation = stop)
                val type = Class.forName("com.eignex.klause.meta.coreguided.CoreGuidedSearch")
                val constructor = type.declaredConstructors.single { it.parameterCount == 8 }
                    .apply { isAccessible = true }
                val handle = constructor.newInstance(model, cost, params, false, null, 2_000L,
                    TimeSource.Monotonic, CoreQueryProfile.refinementMode("NONE")) as ResumableSearch
                owner = handle
                val captureResult = handle.use { it.runSlice(stop, 2_000L, -1L) {} }
                val captureNs = System.nanoTime() - started
                val captureBytes = bean.getThreadAllocatedBytes(thread) - before
                val capturePoint = (captureResult as? MinimizeResult.WithSample)?.sample?.let {
                    ordinary?.reconstruct?.invoke(it) ?: it
                }
                val captureAccepted = capturePoint?.let { CoreQueryProfile.checkPoint(sourceBaked, it) }
                if (capturePoint != null) check(captureAccepted == true)
                var verificationNs = 0L
                var verificationBytes = 0L
                var validationNodes = 0L
                val retained = ArrayList<IntArray>()
                for ((index, captured) in queries.withIndex()) {
                    val candidates = learnedUnits(captured.handle).filter { literal ->
                        // q is incidental to the objective core; private selectors and other atoms decline.
                        Lit.variable(literal) == 0 && model === sourceBaked
                    }.distinct()
                    val available = retained.map { it.copyOf() }
                    for (replay in listOf(false, true)) {
                        var falseQVisits = 0
                        val observer = object : SearchComponent {
                            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
                                if (!prepared && decision is SearchDecision.Bool &&
                                    decision.literal == Lit.make(0, false)
                                ) falseQVisits++
                                return ComponentResult.Consistent
                            }
                        }
                        val beginBytes = bean.getThreadAllocatedBytes(thread)
                        val begin = System.nanoTime()
                        val target = if (replay && available.isNotEmpty()) Problem(
                            captured.model.numBoolVars, 0, emptyArray(),
                            captured.model.factors.toList() + available.map(::Clause),
                            settings = captured.model.settings, semantics = captured.model.semantics,
                        ).bake(lifetime) else captured.model
                        val result = BacktrackSolver(target).solve(captured.params.copy(
                            cancellation = lifetime or Cancellation.after(500.milliseconds),
                            componentFactory = { listOf(observer) }, maxDecisions = 512L,
                        ))
                        val elapsed = System.nanoTime() - begin
                        val allocated = bean.getThreadAllocatedBytes(thread) - beginBytes
                        val checkStart = System.nanoTime()
                        val point = (result as? SolveResult.Sat)?.assignment?.let { assignment ->
                            val local = Sample(BooleanArray(model.numBoolVars) { assignment.boolValue(it) },
                                LongArray(model.numIntVars) { assignment.intValue(it) })
                            ordinary?.reconstruct?.invoke(local) ?: local
                        }
                        val accepted = point?.let { CoreQueryProfile.checkPoint(sourceBaked, it) }
                        if (point != null) check(accepted == true)
                        val checkNs = System.nanoTime() - checkStart
                        val record = buildJsonObject {
                            put("sourceSha256", sha256(sourceText.encodeToByteArray()))
                            put("objectiveSha256", sha256("0,0,0,1,1,0,0".encodeToByteArray()))
                            put("prepared", prepared)
                            put("repeat", repeat)
                            put("warmup", repeat == 0)
                            put("preparationNs", preparationNs)
                            put("captureNsIncludingDisposal", captureNs)
                            put("captureThreadBytesIncludingDisposal", captureBytes)
                            put("captureResult", captureResult?.javaClass?.simpleName)
                            put("captureSourceAccepted", captureAccepted)
                            put("captureSourceObjective", capturePoint?.let { objective.evaluateLong(it) })
                            put("preparedBooleans", model.numBoolVars)
                            put("preparedFactors", model.numFactors)
                            put("query", index)
                            put("queryCount", queries.size)
                            put("openedQueryPins", JsonArray(openedPins.map(::JsonPrimitive)))
                            put("pins", captured.params.assumptions.toString())
                            put("replay", replay)
                            put("availableUnits", JsonArray(available.map { JsonPrimitive(it.single()) }))
                            put("learnedCandidates", JsonArray(candidates.map(::JsonPrimitive)))
                            put("nativeResult", result.javaClass.simpleName)
                            put("originalQueryResult", (field(captured.handle, "done") as? SolveResult)
                                ?.javaClass?.simpleName)
                            put("nodes", result.stats.search.nodes.sum)
                            put("propagation", result.stats.search.propagationWork.sum)
                            put("falseQVisits", if (prepared) null else falseQVisits)
                            put("sourceQCoordinatesAvailable", !prepared)
                            put("sourceAccepted", accepted)
                            put("sourceObjective", point?.let { objective.evaluateLong(it) })
                            put("sourceCheckNs", checkNs)
                            put("elapsedNsIncludingConstruction", elapsed)
                            put("threadBytesIncludingConstruction", allocated)
                            put("priorVerificationNs", verificationNs)
                            put("priorVerificationThreadBytes", verificationBytes)
                            put("priorValidationNodes", validationNodes)
                            put("independentProofCheck", false)
                        }
                        output.appendLine(record.toString())
                        output.flush()
                    }
                    for (literal in candidates) {
                        if (retained.any { it.single() == literal }) continue
                        val beginBytes = bean.getThreadAllocatedBytes(thread)
                        val begin = System.nanoTime()
                        val checked = BacktrackSolver(sourceBaked).solve(params.copy(
                            assumptions = Assumptions(bools = mapOf(Lit.variable(literal) to !Lit.isPositive(literal))),
                            cancellation = lifetime or Cancellation.after(500.milliseconds), maxDecisions = 512L,
                        ))
                        verificationNs += System.nanoTime() - begin
                        verificationBytes += bean.getThreadAllocatedBytes(thread) - beginBytes
                        validationNodes += checked.stats.search.nodes.sum.toLong()
                        if (checked is SolveResult.Unsat) retained += intArrayOf(literal)
                    }
                }
                if (queries.isEmpty()) output.appendLine(buildJsonObject {
                    put("prepared", prepared)
                    put("repeat", repeat)
                    put("captureResult", captureResult?.javaClass?.simpleName)
                    put("captureSourceAccepted", captureAccepted)
                    put("captureSourceObjective", capturePoint?.let { objective.evaluateLong(it) })
                    put("queryCount", 0)
                    put("openedQueryPins", JsonArray(openedPins.map(::JsonPrimitive)))
                }.toString())
                output.flush()
            }
            val conditioned = Problem(2, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            )).bake()
            val rejectedUnit = BacktrackSolver(conditioned).solve(BacktrackParams(
                assumptions = Assumptions(bools = mapOf(0 to false)),
                cancellation = lifetime or Cancellation.after(500.milliseconds),
            ))
            check(rejectedUnit is SolveResult.Sat)
            out.resolve("conditioned-unit.json").writeText(buildJsonObject {
                put("source", "q or a")
                put("candidate", "q, implied under a=false")
                put("sourceNegationResult", rejectedUnit.javaClass.simpleName)
                put("retained", false)
                put("privateAuxiliariesAdmitted", false)
                put("crossSourceImportAdmitted", false)
            }.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun learnedUnits(handle: Any): List<Int> {
        val traversal = requireNotNull(field(handle, "traversal"))
        val cp = field(traversal, "cp") as CpSearchComponent
        val native = cp.session.exportGlueClauses(2, 1, skipPermanent = true).flatMap { clause ->
            val atoms = field(clause, "atomQuads") as LongArray
            if (atoms.isEmpty()) (field(clause, "boolLits") as IntArray).toList() else emptyList()
        }
        val run = field(traversal, "run") ?: return native
        val session = requireNotNull(field(run, "session"))
        val learning = requireNotNull(field(session, "learning"))
        val store = requireNotNull(field(learning, "learned"))
        val shared = (field(store, "clauses") as List<IntArray>).filter { it.size == 1 }.map { it.single() }
        return native + shared
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}
