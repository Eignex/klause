package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy
import java.security.MessageDigest

/** Measure structurally declined LP certification setup without a floating solve. */
object LpCertificationAdmissionProfile {
    /** Save repeated elapsed and thread-allocation observations under `OUT`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("rows", "attempts", "rounds", "warmup") })
        val rows = (options["rows"] ?: "129").toInt().also { require(it in 129..4096) }
        val attempts = (options["attempts"] ?: "20000").toInt().also { require(it in 1..100000) }
        val rounds = (options["rounds"] ?: "7").toInt().also { require(it in 1..31) }
        val warmup = (options["warmup"] ?: "5000").toInt().also { require(it in 0..100000) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("settings.txt").writeText("rows=$rows\nattempts=$attempts\nrounds=$rounds\nwarmup=$warmup\n" +
            "java=${System.getProperty("java.runtime.version")}\nvm=${System.getProperty("java.vm.name")}\n" +
            "processors=${Runtime.getRuntime().availableProcessors()}\n")
        captureIdentity(out)
        val access = AdmissionAccess(rows)
        val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        require(meter.isThreadAllocatedMemorySupported)
        meter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val report = out.resolve("measurements.tsv")
        report.writeText("round\tmechanism\tattempts\telapsedNs\tallocatedBytes\n")
        val mechanisms = listOf("basis", "continuation")
        mechanisms.forEach { mechanism -> repeat(warmup) { access.attempt(mechanism) } }
        repeat(rounds) { round ->
            for (mechanism in if (round % 2 == 0) mechanisms else mechanisms.reversed()) {
                val beforeBytes = meter.getThreadAllocatedBytes(thread)
                val begun = System.nanoTime()
                repeat(attempts) { access.attempt(mechanism) }
                val elapsed = System.nanoTime() - begun
                val allocated = meter.getThreadAllocatedBytes(thread) - beforeBytes
                val line = "$round\t$mechanism\t$attempts\t$elapsed\t$allocated\n"
                report.appendText(line)
                print(line)
            }
        }
        access.verify()
    }

    private fun captureIdentity(out: File) {
        val root = File(System.getProperty("klause.workspace.root", "."))
        val provenance = root.resolve("klause-bench/build/provenance.json")
        check(provenance.isFile) { "Missing installed CLI/runtime provenance" }
        provenance.copyTo(out.resolve("build-provenance.json"), overwrite = true)
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
        check(classpath.all { it.isFile }) { "Classpath must contain installed jars" }
        fun hashes(files: List<File>): String = files.sortedBy { it.path }.joinToString("\n", postfix = "\n") {
            val digest = MessageDigest.getInstance("SHA-256").digest(it.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
            "$digest  ${it.path}"
        }
        out.resolve("classpath-sha256.txt").writeText(hashes(classpath))
        val compilerRoot = File(System.getProperty("user.home"))
            .resolve(".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable")
        val compiler = compilerRoot.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
        check(compiler.isNotEmpty()) { "Missing compiler artifact identity" }
        out.resolve("compiler-sha256.txt").writeText(hashes(compiler))
        out.resolve("runtime-options.txt").writeText(
            ManagementFactory.getRuntimeMXBean().inputArguments.joinToString("\n", postfix = "\n"),
        )
    }

    // Reflection keeps measurement of private numerical mechanisms out of the published API.
    private class AdmissionAccess(rows: Int) {
        private val prefix = "com.eignex.klause.lp.engine."
        private fun type(name: String) = Class.forName(prefix + name)
        private val builderType = type("LpBuilder")
        private val builder = builderType.getConstructor().newInstance()
        private val modelType = type("LpModel")
        private val basisType = type("Basis")
        private val solverType = type("LpSolver")
        private val statusType = type("VarStatus")
        private val basisCache = type("ExactBasisCache").getConstructor().newInstance()
        private val basisLimits = type("ExactBasisLimits").getConstructor().newInstance()
        private val continuationLimitsType = Class.forName("com.eignex.klause.simplex.exact.ExactContinuationLimits")
        private val continuationLimits = continuationLimitsType.getConstructor().newInstance()
        private val verifyBasis = type("ExactBasisVerifyKt").getMethod("verifyExactBasis", modelType, basisType,
            Integer::class.java, basisCache.javaClass, Cancellation::class.java, basisLimits.javaClass,
            type("LpCertificationObserver"))
        private val captureTarget = type("LpExactContinuationKt").getMethod("captureContinuationTarget", modelType,
            solverType, type("FloatLpResult"), continuationLimitsType, Cancellation::class.java)
        private val model: Any
        private val basis: Any
        private val solver = Proxy.newProxyInstance(solverType.classLoader, arrayOf(solverType)) { _, method, _ ->
            error("Dimension rejection called solver.${method.name}")
        }
        private var lastBasis: Any? = null
        private var lastTarget: Any? = null

        init {
            builderType.getMethod("addVar", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(builder, 0L, 1L, 0L, -1)
            val relation = type("Relation").enumConstants.single { it.toString() == "EQ" }
            val addRow = builderType.getMethod("addRow", IntArray::class.java, LongArray::class.java,
                type("Relation"), Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, type("LpRowPremises"))
            repeat(rows) { addRow.invoke(builder, intArrayOf(0), longArrayOf(1L), relation, 1L, true, null) }
            val sense = type("Sense").enumConstants.single { it.toString() == "MINIMIZE" }
            model = builderType.getMethod("build", type("Sense")).invoke(builder, sense)
            val statuses = ReflectArray.newInstance(statusType, rows + 1)
            val basic = statusType.enumConstants.single { it.toString() == "BASIC" }
            val lower = statusType.enumConstants.single { it.toString() == "AT_LOWER" }
            repeat(rows + 1) { ReflectArray.set(statuses, it, if (it == 0) lower else basic) }
            basis = basisType.getConstructor(IntArray::class.java, statuses.javaClass,
                Boolean::class.javaPrimitiveType).newInstance(IntArray(rows) { it + 1 }, statuses, false)
        }

        fun attempt(mechanism: String) {
            if (mechanism == "basis") {
                lastBasis = verifyBasis.invoke(null, model, basis, null, basisCache,
                    Cancellation.Never, basisLimits, null)
            } else {
                lastTarget = captureTarget.invoke(null, model, solver, null, continuationLimits, Cancellation.Never)
            }
        }

        fun verify() {
            for (result in listOf(requireNotNull(lastBasis), requireNotNull(lastTarget))) {
                val metrics = result.javaClass.getMethod("getMetrics").invoke(result)
                check(metrics.javaClass.getMethod("getDecline").invoke(metrics).toString() == "DIMENSION")
                check(metrics.javaClass.getMethod("getWork").invoke(metrics) == 1L)
                check(metrics.javaClass.getMethod("getAllocation").invoke(metrics) == 0L)
            }
        }
    }
}
