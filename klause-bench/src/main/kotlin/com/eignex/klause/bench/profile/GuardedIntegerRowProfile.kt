package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.Assumptions
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
import com.eignex.klause.propagation.CpSearchComponent
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
        require(args.isEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("source.txt").writeText("r iff 2*x+y<=z (positive) or 2*x+y>=z+1 (negative); " +
            "2*x-y<=0; x=0..6; y=0..12; z=0..24\n")
        val records = out.resolve("guarded-rows.jsonl").apply { writeText("") }
        for (warmup in listOf(true, false)) {
            for (truth in listOf(true, false)) {
                for (learning in listOf(false, true)) lifecycle(truth, learning, warmup, records)
                ordinary(truth, warmup, records)
            }
        }
    }

    private fun source(truth: Boolean): Problem = Problem(1, 3,
        arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
            ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2),
                if (truth) LinearOp.LE else LinearOp.GE, if (truth) 0 else 1),
            Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
        ))

    private fun lifecycle(truth: Boolean, learning: Boolean, warmup: Boolean, records: File) {
        val total = Meter()
        val problem = source(truth)
        val native = PropagationSession(problem, pbLearning = false)
        if (learning) native.enableIntegerRowLearning()
        val cp = CpSearchComponent(native)
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
        check(shared.initialize() is ComponentResult.Consistent)
        val constructionNs = total.elapsed()
        val guard = Lit.make(0, truth)
        check(shared.push(SearchDecision.Bool(guard)) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtMost(2, 7)) is ComponentResult.Consistent)
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
            check(shared.push(SearchDecision.IntAtMost(2, upper)) is ComponentResult.Consistent)
            check(native.intDomain(0).max == 6L)
            check(shared.push(SearchDecision.Bool(guard)) is ComponentResult.Consistent)
            val expected = if (learning) upper / 4 else minOf(6L, upper / 2)
            check(native.intDomain(0).max == expected)
            val stored = storedGetter.invoke(state) as List<*>
            val rows = stored.indices.filter { stored[it]?.javaClass?.simpleName == "LearnedIntegerPropagator" }
            check(!learning || reasons[0] - problem.factors.size in rows)
            check(PropagationSession(problem.withIntDomains(arrayOf(IntDomain(2, 2), IntDomain(4, 4),
                IntDomain(upper, upper)))).seed(Assumptions.None.withBool(0, truth)) is PropagationResult.Implied)
            val record = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("kind", "retained-reified")
                put("warmup", warmup)
                put("guardTruth", truth)
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
        check(shared.push(SearchDecision.Bool(guard xor 1)) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtMost(2, 0)) is ComponentResult.Consistent)
        check(shared.push(SearchDecision.IntAtLeast(0, 2)) is ComponentResult.Consistent)
        val disposal = Meter()
        shared.popTo(0)
        native.forgetLearnedClauses { _, _ -> false }
        check(native.learnedClauseCount == 0)
        val record = buildJsonObject {
            put("kind", "retained-reified-total")
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

    private fun ordinary(truth: Boolean, warmup: Boolean, records: File) {
        val total = Meter()
        val source = source(truth)
        val guarded = source.withFactors(source.factors + Clause(intArrayOf(Lit.make(0, truth))))
            .withIntDomains(arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 11)))
        val stop = Cancellation.after(10.seconds)
        val budget = PresolveBudget(1_000_000L)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(guarded, FiniteEngine.BACKTRACK,
            solutionSetSensitive = true, cancellation = stop, solveCancellation = stop, presolveBudget = budget,
            randomSeed = 3L))
        check(!stop() && prepared.presolve?.infeasible != true)
        val preparationNs = total.elapsed()
        val relaxation = MpsLpAdmission.rootRelaxation(guarded.bake(stop),
            LinearObjective(intCoefficients = longArrayOf(-1, 0, 0)), false, stop)
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
            put("preparedDomains", JsonArray(prepared.problem.rootIntDomains().map { JsonPrimitive(it.toString()) }))
            put("preparationWork", budget.spent())
            put("preparationNs", preparationNs)
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
