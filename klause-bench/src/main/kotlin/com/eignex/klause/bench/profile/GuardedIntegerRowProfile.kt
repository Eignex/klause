package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GuardedIntegerRowProfile {
    private class Meter {
        private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        private val thread = Thread.currentThread().threadId()
        private val started = System.nanoTime()
        private val before: Long
        init {
            bean.isThreadAllocatedMemoryEnabled = true
            before = bean.getThreadAllocatedBytes(thread)
        }
        fun elapsed(): Long = System.nanoTime() - started
        fun allocated(): Long = bean.getThreadAllocatedBytes(thread) - before
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.toList() == listOf("producer=element"))
        val element = args.isNotEmpty()
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("source.txt").writeText(if (element) {
            "r=Element([a,b],i); 2*x+a<=z; 2*x-r<=0; i=0..1; x=0..6; a,b,r=0..12; z=0..24\n"
        } else "r iff 2*x+y<=z (positive) or 2*x+y>=z+1 (negative); " +
            "2*x-y<=0; x=0..6; y=0..12; z=0..24\n")
        val records = out.resolve("guarded-rows.jsonl").apply { writeText("") }
        for (warmup in listOf(true, false)) {
            for (truth in if (element) listOf(true) else listOf(true, false)) {
                for (learning in listOf(false, true)) lifecycle(truth, learning, warmup, records, element)
                ordinary(truth, warmup, records, element)
            }
        }
    }

    private fun source(truth: Boolean, element: Boolean): Problem = if (element) {
        Problem(0, 6, arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 12),
            IntDomain(0, 24), IntDomain(0, 1)), arrayOf(
                Element(5, 3, longArrayOf(1, 2), true, 0),
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 4), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 3), LinearOp.LE, 0),
            ))
    } else Problem(1, 3,
        arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
            ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2),
                if (truth) LinearOp.LE else LinearOp.GE, if (truth) 0 else 1),
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
        ))

    private fun lifecycle(truth: Boolean, learning: Boolean, warmup: Boolean, records: File, element: Boolean) {
        val total = Meter()
        val problem = source(truth, element)
        val native = PropagationSession(problem, pbLearning = false)
        if (learning) native.enableIntegerRowLearning()
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(problem.numBoolVars))
        check(shared.initialize() is ComponentResult.Consistent)
        val constructionNs = total.elapsed()
        val guard = if (element) SearchDecision.IntAtMost(5, 0) else SearchDecision.Bool(Lit.make(0, truth))
        val z = if (element) 4 else 2
        check(shared.push(guard) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtMost(z, 7)) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtLeast(0, 2)) is ComponentResult.Conflict)
        val backjump = (cp.resolveConflict(shared) as SearchConflictResolution.Backjump).conflict
        shared.popTo(backjump.decisionLevel)
        check(backjump.apply(shared) == SearchLearnedConflictResult.Resume)
        check(native.intDomain(0).max == 1L)
        val acquisitionNs = total.elapsed() - constructionNs
        val state = native.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(native)
        val storedGetter = state.javaClass.declaredMethods.single {
            it.name.startsWith("getLearnedClauses") && it.parameterCount == 0
        }
        val workGetter = state.javaClass.declaredMethods.single {
            it.name.startsWith("getWork") && it.parameterCount == 0
        }
        val reasons = state.javaClass.getMethod("getIntMaxReason").invoke(state) as IntArray
        for (upper in listOf(11L, 15L)) {
            shared.popTo(0)
            check(shared.push(SearchDecision.IntAtMost(z, upper)) is ComponentResult.Consistent)
            check(native.intDomain(0).max == if (element) minOf(6L, upper / 2) else 6L)
            check(shared.push(guard) is ComponentResult.Consistent)
            val expected = if (learning) upper / 4 else minOf(6L, upper / 2)
            check(native.intDomain(0).max == expected)
            val stored = storedGetter.invoke(state) as List<*>
            val rows = stored.indices.filter { stored[it]?.javaClass?.simpleName == "LearnedIntegerPropagator" }
            check(!learning || reasons[0] - problem.factors.size in rows)
            val point = if (element) longArrayOf(2, 4, 0, 4, upper, 0) else longArrayOf(2, 4, upper)
            val assumptions = if (element) Assumptions.None else Assumptions.None.withBool(0, truth)
            check(PropagationSession(problem.withIntDomains(Array(point.size) { IntDomain(point[it], point[it]) }))
                .seed(assumptions) is PropagationResult.Implied)
            val record = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("kind", if (element) "retained-element" else "retained-reified")
                put("warmup", warmup)
                put("guardTruth", truth)
                put("guardSource", if (element) "index=0" else "indicator=$truth")
                put("integerRowLearning", learning)
                put("pbLearning", false)
                put("zUpper", upper)
                put("xUpper", native.intDomain(0).max)
                put("storedRows", rows.size)
                put("rowReason", reasons[0] - problem.factors.size in rows)
                put("nativeStoredConstraints", native.learnedClauseCount)
                put("sharedStoredClauses", shared.learnedClauseCount)
                put("nativeSourcePointCheck", true)
                put("independentWitnessOrProofCheck", false)
                put("constructionNs", constructionNs)
                put("acquisitionNs", acquisitionNs)
                put("cumulativeWork", workGetter.invoke(state) as Long)
                put("cumulativeElapsedNs", total.elapsed())
                put("cumulativeThreadAllocatedBytes", total.allocated())
            }
            records.appendText("$record\n")
        }
        shared.popTo(0)
        check(shared.push(if (element) SearchDecision.IntAtLeast(5, 1) else
            SearchDecision.Bool(Lit.make(0, !truth))) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtMost(z, if (element) 4 else 0)) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtLeast(0, 2)) is ComponentResult.Consistent)
        val oppositePoint = if (element) longArrayOf(2, 0, 4, 4, 4, 1) else longArrayOf(2, 4, 0)
        val oppositeAssumptions = if (element) Assumptions.None else Assumptions.None.withBool(0, !truth)
        check(PropagationSession(problem.withIntDomains(Array(oppositePoint.size) {
            IntDomain(oppositePoint[it], oppositePoint[it])
        })).seed(oppositeAssumptions) is PropagationResult.Implied)
        val disposal = Meter()
        shared.popTo(0)
        native.forgetLearnedClauses { _, _ -> false }
        check(native.learnedClauseCount == 0)
        val record = buildJsonObject {
            put("kind", if (element) "retained-element-total" else "retained-reified-total")
            put("warmup", warmup)
            put("guardTruth", truth)
            put("integerRowLearning", learning)
            put("falseGuardFeasible", true)
            put("disposalNs", disposal.elapsed())
            put("disposedConstraints", native.learnedClauseCount)
            put("totalElapsedNs", total.elapsed())
            put("totalThreadAllocatedBytes", total.allocated())
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("javaVersion", System.getProperty("java.version"))
        }
        records.appendText("$record\n")
        println(record)
    }

    private fun ordinary(truth: Boolean, warmup: Boolean, records: File, element: Boolean) {
        val total = Meter()
        val source = source(truth, element)
        val z = if (element) 4 else 2
        val domains = Array(source.numIntVars) { requireNotNull(source.intDomainOrNull(it)) }.also {
            it[z] = IntDomain(0, 11)
            if (element) it[5] = IntDomain(0, 0)
        }
        val guarded = (if (element) source else
            source.withFactors(source.factors + Clause(intArrayOf(Lit.make(0, truth))))).withIntDomains(domains)
        val stop = Cancellation.after(10.seconds)
        val budget = PresolveBudget(1_000_000L)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(guarded, FiniteEngine.BACKTRACK,
            solutionSetSensitive = true, cancellation = stop, solveCancellation = stop, presolveBudget = budget,
            randomSeed = 3L))
        check(!stop() && prepared.presolve?.infeasible != true)
        val preparationNs = total.elapsed()
        val aggressiveBudget = PresolveBudget(1_000_000L)
        val aggressiveStart = total.elapsed()
        val aggressive = FinitePipeline.prepare(FinitePipelineRequest(guarded, FiniteEngine.BACKTRACK,
            presolveConfig = PresolveConfig.parse("aggressive"), explicitPresolveConfig = true,
            solutionSetSensitive = true, cancellation = stop, solveCancellation = stop,
            presolveBudget = aggressiveBudget, randomSeed = 3L))
        check(!stop() && aggressive.presolve?.infeasible != true)
        val aggressiveNs = total.elapsed() - aggressiveStart
        val relaxation = MpsLpAdmission.rootRelaxation(guarded.bake(stop),
            LinearObjective(intCoefficients = LongArray(source.numIntVars) { if (it == 0) -1L else 0L }),
            false, stop, element = element)
        val model = relaxation.javaClass.getMethod("getModel").invoke(relaxation)
        val solve = Class.forName("com.eignex.klause.lp.engine.LpSolveKt").methods.single {
            it.name == "solveAndCertify\$default" && it.parameterTypes[0].simpleName == "LpModel"
        }
        val result = solve.invoke(null, model, null, stop, 200_000L, false, null, null, null, null, null, null, 0.0,
            4082, null)
        val integerBound = result.javaClass.getMethod("getIntegerObjectiveLowerBound").invoke(result)
        check(integerBound == -2L)
        val record = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("kind", "ordinary-preparation-and-lp")
            put("warmup", warmup)
            put("guardTruth", truth)
            put("conditionedRoot", "guard pinned; z<=11; conditional authority only")
            put("preparedFactors", JsonArray(prepared.problem.factors.map { JsonPrimitive(it.javaClass.simpleName) }))
            put("preparedDomains", JsonArray((0 until prepared.problem.numIntVars).map {
                JsonPrimitive(requireNotNull(prepared.problem.intDomainOrNull(it)).toString())
            }))
            put("preparationWork", budget.spent())
            put("preparationNs", preparationNs)
            put("aggressivePreparedFactors", JsonArray(aggressive.problem.factors.map {
                JsonPrimitive(it.javaClass.simpleName)
            }))
            put("aggressivePreparedDomains", JsonArray((0 until aggressive.problem.numIntVars).map {
                JsonPrimitive(requireNotNull(aggressive.problem.intDomainOrNull(it)).toString())
            }))
            put("aggressivePreparationWork", aggressiveBudget.spent())
            put("aggressivePreparationNs", aggressiveNs)
            put("forcedElementHull", element)
            put("lpIntegerUpperX", -(integerBound as Long))
            put("lpLowerBound", result.javaClass.getMethod("getLowerBound").invoke(result).toString())
            put("totalElapsedNs", total.elapsed())
            put("totalThreadAllocatedBytes", total.allocated())
            put("independentWitnessOrProofCheck", false)
        }
        records.appendText("$record\n")
        println(record)
    }
}
