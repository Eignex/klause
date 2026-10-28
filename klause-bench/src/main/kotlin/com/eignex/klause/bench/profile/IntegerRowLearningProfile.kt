package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object IntegerRowLearningProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.contentEquals(arrayOf("sparse")))
        val holes = if (args.isEmpty()) listOf(null) else listOf(null, 2L, 4L)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val records = out.resolve("integer-rows.jsonl")
        records.writeText("")
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        for (hole in holes) for (learning in listOf(false, true)) {
            val started = System.nanoTime()
            val before = bean.getThreadAllocatedBytes(thread)
            val yDomain = IntDomain(0, 12).let { if (hole == null) it else it.excludeValue(hole) }
            val source = "2*x+y-z<=0; 2*x-y<=0; x=0..6; y=0..12; z=0..24" +
                if (hole == null) "" else "; y!=$hole"
            val problem = Problem(0, 3, arrayOf(IntDomain(0, 6), yDomain, IntDomain(0, 24)), arrayOf(
                Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ))
            val native = PropagationSession(problem, pbLearning = false)
            if (learning) native.enableIntegerRowLearning()
            val cp = CpSearchComponent(native)
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            check(shared.initialize() is ComponentResult.Consistent)
            check(shared.push(SearchDecision.IntAtMost(2, 7)) is ComponentResult.Consistent)
            check(shared.push(SearchDecision.IntAtLeast(0, 2)) is ComponentResult.Conflict)
            val backjump = (cp.resolveConflict(shared) as SearchConflictResolution.Backjump).conflict
            shared.popTo(backjump.decisionLevel)
            check(backjump.apply(shared) == SearchLearnedConflictResult.Resume)
            val state = native.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(native)
            val rowsGetter = state.javaClass.declaredMethods.single {
                it.name.startsWith("getLearnedClauses") && it.parameterCount == 0
            }
            val workGetter = state.javaClass.declaredMethods.single {
                it.name.startsWith("getWork") && it.parameterCount == 0
            }
            val reasons = state.javaClass.getMethod("getIntMaxReason").invoke(state) as IntArray
            var pointCheckWork = 0L
            for (upper in listOf(7L, 11L, 15L, 23L)) {
                if (upper != 7L) {
                    shared.popTo(0)
                    check(shared.push(SearchDecision.IntAtMost(2, upper)) is ComponentResult.Consistent)
                }
                val stored = rowsGetter.invoke(state) as List<*>
                val rowIndices = stored.indices.filter {
                    stored[it]?.javaClass?.simpleName == "LearnedIntegerPropagator"
                }
                val retained = learning && hole != 4L
                val expected = if (upper == 7L) 1L else if (retained) upper / 4 else minOf(6L, upper / 2)
                check(native.intDomain(0).max == expected)
                check(rowIndices.size == if (retained) 1 else 0)
                if (retained) check(reasons[0] - problem.factors.size in rowIndices)
                val x = if (upper == 7L) 1L else 2L
                val y = if (2 * x == hole) 2 * x + 1 else 2 * x
                val point = problem.withIntDomains(arrayOf(IntDomain(x, x), IntDomain(y, y), IntDomain(upper, upper)))
                val checker = PropagationSession(point)
                check(!checker.isUnsatAtRoot)
                val pointState = checker.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(checker)
                pointCheckWork += workGetter.invoke(pointState) as Long
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("source", source)
                    put("sourceSha256", MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                        .joinToString("") { "%02x".format(it) })
                    put("hole", hole?.toString() ?: "none")
                    put("preparation", "retained finite projection; no presolve, difference/binary substitution or LP")
                    put("integerRowLearning", learning)
                    put("pbLearning", false)
                    put("zUpper", upper)
                    put("xUpper", native.intDomain(0).max)
                    put("storedRows", rowIndices.size)
                    put("rowReason", reasons[0] - problem.factors.size in rowIndices)
                    put("nativeStoredConstraints", native.learnedClauseCount)
                    put("sharedStoredClauses", shared.learnedClauseCount)
                    put("witness", "x=$x;y=$y;z=$upper")
                    put("nativeSourceCheck", true)
                    put("independentWitnessOrProofCheck", false)
                    put("cumulativeWork", workGetter.invoke(state) as Long)
                    put("cumulativeNativePointCheckWork", pointCheckWork)
                    put("cumulativeElapsedNs", System.nanoTime() - started)
                    put("cumulativeThreadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
                    put("osName", System.getProperty("os.name"))
                    put("osArch", System.getProperty("os.arch"))
                    put("javaVersion", System.getProperty("java.version"))
                }
                records.appendText("$record\n")
                println(record)
            }
            shared.popTo(0)
            if (hole != null) check(hole !in native.intDomain(1))
            native.forgetLearnedClauses { _, _ -> false }
            check(native.learnedClauseCount == 0)
            val disposed = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("phase", "disposed")
                put("hole", hole?.toString() ?: "none")
                put("integerRowLearning", learning)
                put("cumulativeWork", workGetter.invoke(state) as Long)
                put("cumulativeNativePointCheckWork", pointCheckWork)
                put("cumulativeElapsedNs", System.nanoTime() - started)
                put("cumulativeThreadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
                put("nativeStoredConstraints", native.learnedClauseCount)
                put("sharedStoredClauses", shared.learnedClauseCount)
            }
            records.appendText("$disposed\n")
            println(disposed)
        }
    }
}
