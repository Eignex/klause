package com.eignex.klause.bench.profile

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.koblas.KoblasEngine
import com.eignex.koblas.engine.BuiltinEngines
import com.eignex.koblas.engine.EngineImplementation
import com.eignex.lab.bench.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import jdk.jfr.Configuration
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.*

/** Isolated proposal and kernel diagnostics; timings carry no source-verdict authority. */
fun main(args: Array<String>) {
    val output = System.out
    System.setOut(System.err)
    BenchCli.execute(QuadraticProposalSuite, args, stdout = output)
}

internal object QuadraticProposalSuite : BenchSuite {
    @Volatile private var sink = 0.0

    override fun describe() = BenchDescriptor("quadratic-proposal-profile", "Cold and warm production proposals")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys.all { it in setOf("dimensions", "tokens") })
        val dimensions = (request.parameters["dimensions"] ?: "2,16,64,256,512").split(',').map(String::toInt)
        val tokens = (request.parameters["tokens"] ?: "never,metered").split(',')
        require(dimensions.isNotEmpty() && dimensions.distinct().size == dimensions.size && dimensions.all { it in 2..1024 })
        require(tokens.isNotEmpty() && tokens.distinct().size == tokens.size && tokens.all { it in setOf("never", "metered") })
        return dimensions.flatMap { dimension -> tokens.map { token ->
            val id = "quadratic-proposal/$dimension/$token"
            BenchCase(id, mapOf("dimension" to "$dimension", "token" to token),
                BenchResources(1, 3072, 180), attributes = mapOf("pairKey" to id, "comparisonKey" to id, "problem" to id,
                    "suite" to "quadratic-proposal-profile", "recordSchema" to "quadratic-proposal-profile-v1"))
        } }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val dimension = case.parameters.getValue("dimension").toInt()
        val access = ProposalAccess(dimension, case.parameters.getValue("token") == "metered")
        val cold = access.sample(0)
        val engine = requireNotNull(BuiltinEngines.simd)
        check(engine.id == "jvm-simd")
        val provider = Class.forName("com.eignex.koblas.internal.engine.EngineInspectionKt")
            .getMethod("providerFor", KoblasEngine::class.java, EngineImplementation::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val selected = access.selectedEngine()
        access.sample(1500)
        val samples = List(5) { access.sample(300) }
        val expected = samples.first().work
        check(samples.all { it.work == expected && it.selected == cold.selected })
        val kernels = buildJsonArray {
            for (operation in listOf("dot", "axpy")) {
                for (order in listOf(listOf("scalar", "simd", "sliced-simd"), listOf("sliced-simd", "simd", "scalar"))) {
                    for (mode in order) add(kernel(operation, mode, dimension, engine))
                }
            }
        }
        val recording = Recording(Configuration.getConfiguration("profile"))
        val capture = context.outputDirectory.resolve("proposal.jfr")
        recording.use { it.start(); access.sample(1200); it.stop(); it.dump(capture) }
        val stacks = RecordingFile.readAllEvents(capture).filter { it.eventType.name == "jdk.ExecutionSample" }
            .map { event -> event.stackTrace?.frames?.take(12)?.joinToString("\n") {
                "${it.method.type.name}.${it.method.name}"
            } ?: "<missing>" }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val details = buildJsonObject {
            put("dimension", dimension); put("rows", dimension * 2); put("iterations", 32)
            put("token", case.parameters.getValue("token")); put("selectedProposalEngine", selected)
            put("javaVersion", System.getProperty("java.version"))
            put("jvmArguments", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("simdDotProvider", provider.invoke(null, engine, EngineImplementation.Dot, dimension, 1, 1, true, false).toString())
            put("simdAxpyProvider", provider.invoke(null, engine, EngineImplementation.Axpy, dimension, 1, 1, true, false).toString())
            put("cold", cold.json()); putJsonArray("warm") { samples.forEach { add(it.json()) } }
            put("kernels", kernels)
            putJsonArray("executionSamples") { stacks.forEach { entry -> add(buildJsonObject {
                put("count", entry.value); put("stack", entry.key)
            }) } }
        }
        Files.writeString(context.outputDirectory.resolve("proposal.json"), "$details\n")
        val ns = samples.map { it.elapsedNs.toDouble() / it.calls }.sorted()[2]
        return BenchResult(case.id, BenchStatus.SUCCESS,
            metrics = listOf(BenchMetric("proposalNs", ns, "ns", MetricDirection.LOWER)), details = details,
            artifacts = listOf(context.artifact("proposal.json", "application/json"), context.artifact("proposal.jfr")))
    }

    private fun kernel(operation: String, mode: String, size: Int, simd: KoblasEngine): JsonObject {
        val x = DoubleArray(size) { 0.5 + (it % 17) / 64.0 }
        val y = DoubleArray(size) { 1.0 - (it % 13) / 64.0 }
        fun batch() {
            var sum = 0.0
            repeat(128) {
                if (operation == "dot") {
                    when (mode) {
                        "scalar" -> { var dot = 0.0; for (i in x.indices) dot += x[i] * y[i]; sum += dot }
                        "simd" -> sum += simd.dot(x, 0, y, 0, size)
                        else -> { var offset = 0; while (offset < size) {
                            val count = minOf(256, size - offset)
                            sum += simd.dot(x, offset, y, offset, count); offset += count
                        } }
                    }
                } else {
                    when (mode) {
                        "scalar" -> for (i in x.indices) y[i] += 0.000001 * x[i]
                        "simd" -> simd.axpy(y, 0, 0.000001, x, 0, size)
                        else -> { var offset = 0; while (offset < size) {
                            val count = minOf(256, size - offset)
                            simd.axpy(y, offset, 0.000001, x, offset, count); offset += count
                        } }
                    }
                    sum += y[0]
                }
            }
            sink = sum
        }
        fun timed(milliseconds: Long): Pair<Long, Long> {
            val start = System.nanoTime(); var calls = 0L
            do { batch(); calls += 128 } while (System.nanoTime() - start < milliseconds * 1_000_000)
            return (System.nanoTime() - start) to calls
        }
        timed(100)
        val (ns, calls) = timed(150)
        check(sink.isFinite())
        return buildJsonObject { put("operation", operation); put("mode", mode); put("ns", ns); put("calls", calls) }
    }

    private class ProposalAccess(dimension: Int, metered: Boolean) {
        private val prefix = "com.eignex.klause.arithmetic.quadratic."
        private val controlType = Class.forName(prefix + "QuadraticControl")
        private val modelType = Class.forName(prefix + "QuadraticModel")
        private val rowType = Class.forName(prefix + "QuadraticRow")
        private val relationType = Class.forName(prefix + "QuadraticRowRelation")
        private val candidateType = Class.forName(prefix + "QuadraticCandidates")
        private val propose = candidateType.getMethod("proposeActiveRows", modelType, List::class.java, Int::class.javaPrimitiveType)
        private val work = controlType.getMethod("getWork")
        private var charged = 0L
        private var polls = 0L
        private val meter = WorkMeter { charged += it }
        private val cancellation = if (metered) object : Cancellation {
            override fun isCancelled(): Boolean { polls++; return false }
            override fun workMeter(): WorkMeter = meter
        } else Cancellation.Never
        private val control = controlType.getConstructor(Cancellation::class.java, Long::class.javaObjectType,
            Long::class.javaObjectType).newInstance(cancellation, null, null)
        private val candidate = candidateType.getConstructor(controlType).newInstance(control)
        private val start = List(dimension) { 0.0 }
        private val model: Any
        private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val thread = Thread.currentThread().threadId()

        init {
            val fractions = (-3L..3L).associateWith(BigFraction::ofLong)
            val rows = List(dimension * 2) { i ->
                val coefficients = List(dimension) { j -> fractions.getValue(((i + j) % 7 - 3).toLong()) }
                val equality = i % 8 == 0
                rowType.getConstructor(List::class.java, BigFraction::class.java, relationType).newInstance(
                    coefficients, BigFraction.ofLong(if (equality) 1 else dimension * 4L),
                    relationType.enumConstants.single { it.toString() == if (equality) "EQ" else "LE" })
            }
            model = modelType.getConstructor(List::class.java, List::class.java, BigFraction::class.java, List::class.java)
                .newInstance(List(dimension) { fractions.getValue(-2) },
                    List(dimension) { i -> List(dimension) { j -> fractions.getValue(if (i == j) 2 else 0) } },
                    BigFraction.ZERO, rows)
        }

        fun selectedEngine(): String {
            val field = candidateType.declaredFields.singleOrNull { it.name == "Companion" } ?: return "handwritten"
            val companion = field.apply { isAccessible = true }.get(null)
            val getter = companion.javaClass.methods.singleOrNull { it.name == "getProposalEngine" } ?: return "handwritten"
            getter.isAccessible = true
            return (getter.invoke(companion) as KoblasEngine).id
        }

        fun sample(milliseconds: Long): Sample {
            val beforeWork = work.invoke(control) as Long; val beforeCharge = charged; val beforePoll = polls
            val allocation = bean.getThreadAllocatedBytes(thread); val cpu = bean.getThreadCpuTime(thread)
            val began = System.nanoTime(); var calls = 0L; var selected: List<*>? = null
            do {
                val result = propose.invoke(candidate, model, start, 32) as? List<*> ?: error("proposal declined")
                if (selected != null) check(result == selected)
                selected = result; sink = result.size.toDouble(); calls++
            } while (System.nanoTime() - began < milliseconds * 1_000_000)
            val elapsed = System.nanoTime() - began
            return Sample(calls, elapsed, bean.getThreadCpuTime(thread) - cpu,
                bean.getThreadAllocatedBytes(thread) - allocation, ((work.invoke(control) as Long) - beforeWork) / calls,
                (charged - beforeCharge) / calls, (polls - beforePoll) / calls, selected.toString())
        }
    }

    private data class Sample(val calls: Long, val elapsedNs: Long, val cpuNs: Long, val allocatedBytes: Long,
        val work: Long, val charges: Long, val polls: Long, val selected: String) {
        fun json() = buildJsonObject {
            put("calls", calls); put("elapsedNs", elapsedNs); put("cpuNs", cpuNs); put("allocatedBytes", allocatedBytes)
            put("workPerCall", work); put("chargesPerCall", charges); put("pollsPerCall", polls); put("selected", selected)
        }
    }
}
