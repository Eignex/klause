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
import java.util.HexFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object IntegerReasonReroutingProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val scale = args.singleOrNull()?.toInt() ?: 8
        require(scale in 2..32)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val records = out.resolve("integer-reasons.jsonl").apply { writeText("") }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val traceClass = try {
            Class.forName("com.eignex.klause.propagation.ArithmeticLearningTrace")
        } catch (_: ClassNotFoundException) {
            null
        }
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) for (arm in listOf("clauses", "original", "eager", "numeric-surrogate")) {
            val started = System.nanoTime()
            val before = bean.getThreadAllocatedBytes(thread)
            val source = "${scale}*x+y<=z; x-y<=0; z-${scale}*x<=w; " +
                "x=0..$scale; y=0..$scale; z=0..${scale * (scale + 1)}; w=0..${scale * (scale + 1)}"
            val factors = arrayOf(
                Linear(intArrayOf(scale, 1, -1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                Linear(intArrayOf(1, -scale, -1), intArrayOf(2, 0, 3), LinearOp.LE, 0),
            )
            val selected = order.map { factors[it] }.toMutableList()
            if (arm == "numeric-surrogate") {
                selected.add(Linear(intArrayOf(scale + 1, -1), intArrayOf(0, 2), LinearOp.LE, 0))
            }
            val problem = Problem(0, 4, arrayOf(IntDomain(0, scale.toLong()), IntDomain(0, scale.toLong()),
                IntDomain(0, scale.toLong() * (scale + 1)), IntDomain(0, scale.toLong() * (scale + 1))),
                selected.toTypedArray())
            val native = PropagationSession(problem, pbLearning = false)
            if (arm != "clauses") native.enableIntegerRowLearning()
            val cp = CpSearchComponent(native)
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
            check(shared.initialize() is ComponentResult.Consistent)
            val state = native.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(native)
            val workGetter = state.javaClass.declaredMethods.single {
                it.name.startsWith("getWork") && it.parameterCount == 0
            }
            val rowsGetter = state.javaClass.declaredMethods.single {
                it.name.startsWith("getLearnedClauses") && it.parameterCount == 0
            }
            val traceField = if (traceClass == null) null else {
                state.javaClass.getDeclaredField("arithmeticLearningTrace").apply { isAccessible = true }
            }
            if (arm == "eager") {
                check(shared.push(SearchDecision.IntAtMost(2, scale.toLong())) is ComponentResult.Consistent)
                check(shared.push(SearchDecision.IntAtLeast(0, 1)) is ComponentResult.Conflict)
                val acquire = (cp.resolveConflict(shared) as SearchConflictResolution.Backjump).conflict
                shared.popTo(acquire.decisionLevel)
                check(acquire.apply(shared) == SearchLearnedConflictResult.Resume)
                check((rowsGetter.invoke(state) as List<*>).any {
                    it?.javaClass?.simpleName == "LearnedIntegerPropagator"
                })
                shared.popTo(0)
            }
            val acquisitionWork = workGetter.invoke(state) as Long
            val trace = traceClass?.getConstructor(Int::class.java, Int::class.java)?.newInstance(1024, 65536)
            fun snapshotTrace(): JsonArray {
                if (traceClass == null || trace == null) return JsonArray(emptyList())
                return JsonArray((traceClass.getMethod("getEvents").invoke(trace) as List<*>).map { event ->
                    requireNotNull(event)
                    buildJsonObject {
                        put("kind", event.javaClass.getMethod("getKind").invoke(event) as String)
                        val data = event.javaClass.getMethod("getData").invoke(event) as LongArray
                        put("data", JsonArray(data.map { JsonPrimitive(it) }))
                    }
                })
            }
            traceField?.set(state, trace)
            check(shared.push(SearchDecision.IntAtLeast(0, 1)) is ComponentResult.Consistent)
            check(shared.push(SearchDecision.IntAtMost(3, 0)) is ComponentResult.Conflict)
            val conflictWork = workGetter.invoke(state) as Long
            val resolution = cp.resolveConflict(shared) as SearchConflictResolution.Backjump
            shared.popTo(resolution.conflict.decisionLevel)
            check(resolution.conflict.apply(shared) == SearchLearnedConflictResult.Resume)
            val conflictRecord = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("phase", "conflict-applied")
                put("scale", scale)
                put("arm", arm)
                put("sourceOrder", JsonArray(order.map { JsonPrimitive(it) }))
                put("acquisitionWork", acquisitionWork)
                put("conflictWork", conflictWork)
                put("traceAvailable", trace != null)
                if (trace != null) put("trace", snapshotTrace())
            }
            records.appendText("$conflictRecord\n")
            println(conflictRecord)
            val endpoints = ArrayList<JsonObject>()
            var checkWork = 0L
            for (upper in listOf(1L, minOf(3L, scale.toLong()))) {
                shared.popTo(0)
                check(shared.push(SearchDecision.IntAtMost(3, upper)) is ComponentResult.Consistent)
                val values = longArrayOf(upper, upper, (scale + 1) * upper, upper)
                for (variable in values.indices) check(values[variable] in native.intDomain(variable))
                val checker = PropagationSession(problem.withIntDomains(Array(4) { index ->
                    IntDomain(values[index], values[index])
                }))
                check(!checker.isUnsatAtRoot)
                val checkState = checker.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(checker)
                checkWork += workGetter.invoke(checkState) as Long
                endpoints.add(buildJsonObject {
                    put("wUpper", upper)
                    put("domains", JsonArray((0..3).map { variable -> buildJsonObject {
                        put("variable", variable)
                        put("min", native.intDomain(variable).min)
                        put("max", native.intDomain(variable).max)
                    } }))
                    put("cumulativeWork", workGetter.invoke(state) as Long)
                    put("cumulativeNativePointCheckWork", checkWork)
                })
            }
            val stored = rowsGetter.invoke(state) as List<*>
            val storedKinds = stored.map { it?.javaClass?.simpleName ?: "null" }
            val rowCount = storedKinds.count { it == "LearnedIntegerPropagator" }
            shared.popTo(0)
            native.forgetLearnedClauses { _, _ -> false }
            check(native.learnedClauseCount == 0)
            val record = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("phase", "disposed")
                put("scale", scale)
                put("source", source)
                put("sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toByteArray())))
                put("sourceOrder", JsonArray(order.map { JsonPrimitive(it) }))
                put("factorLabels", JsonArray((order.map { listOf("A", "B", "C")[it] } +
                    if (arm == "numeric-surrogate") listOf("A+B") else emptyList()).map { JsonPrimitive(it) }))
                put("arm", arm)
                put("preparation", "finite projection; no presolve or LP; numeric surrogate omits acquisition costs")
                put("pbLearning", false)
                put("acquisitionWork", acquisitionWork)
                put("conflictWork", conflictWork)
                put("cumulativeWork", workGetter.invoke(state) as Long)
                put("nativePointCheckWork", checkWork)
                put("cumulativeElapsedNs", System.nanoTime() - started)
                put("cumulativeThreadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
                put("retainedRowsBeforeDisposal", rowCount)
                put("nativeStoredBeforeDisposal", storedKinds.size)
                put("sharedStoredClauses", shared.learnedClauseCount)
                put("nativeStoredAfterDisposal", native.learnedClauseCount)
                put("traceAvailable", trace != null)
                if (traceClass != null && trace != null) {
                    put("traceTruncated", traceClass.getMethod("getTruncated").invoke(trace) as Boolean)
                    put("trace", snapshotTrace())
                }
                put("endpoints", JsonArray(endpoints))
                put("nativeSourceCheck", true)
                put("witness", "x=w;y=w;z=${scale + 1}*w;w=1,${minOf(3L, scale.toLong())}")
                put("independentWitnessOrProofCheck", false)
                put("osName", System.getProperty("os.name"))
                put("osArch", System.getProperty("os.arch"))
                put("javaVersion", System.getProperty("java.version"))
            }
            records.appendText("$record\n")
            println(record)
        }
    }
}
