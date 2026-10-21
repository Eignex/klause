package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Locale

/** Measures actual completion initialization retries. */
object CompletionInitializationProfile {
    /** Runs the configured cancellation trace and writes its measurements. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options(args)
        val source = source(options.rows)
        val model = model(options.rows)
        val access = CompletionAccess()
        val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        meter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val records = ArrayList<String>()
        metadata(options, source)
        for (round in 0..options.rounds) {
            val modes = if (round % 2 == 0) listOf("retained", "fresh") else listOf("fresh", "retained")
            for (mode in modes) {
                val result = measure(options, access, model, mode) { meter.getThreadAllocatedBytes(thread) }
                val record = "round=$round mode=$mode " + result.first
                records += record
                println("MEASURE $record")
            }
        }
        options.out.resolve("measurements.txt").writeText(records.joinToString("\n", postfix = "\n"))
    }

    private class Options(args: Array<String>) {
        private val values = args.associate { argument ->
            val parts = argument.split('=', limit = 2)
            require(parts.size == 2) { "expected key=value: $argument" }
            parts[0] to parts[1]
        }
        val attempts = values["attempts"]?.toInt() ?: 64
        val rounds = values["rounds"]?.toInt() ?: 5
        val rows = values["rows"]?.toInt() ?: 128
        val charged = when (values["cancel"] ?: "charged") {
            "charged" -> true
            "early" -> false
            else -> error("cancel must be charged or early")
        }
        val out = File(values["out"] ?: System.getenv("OUT") ?: "build/completion-profile").apply { mkdirs() }

        init {
            require(values.keys.all { it in setOf("attempts", "rounds", "rows", "cancel", "out") })
            require(attempts > 0 && rounds > 0 && rows in 1..4096)
        }
    }

    // Reflection keeps measurement of internal consumers out of the published library API.
    private class CompletionAccess {
        private val adapter = Class.forName("com.eignex.klause.solver.pipeline.OpenExactNeighborhood")
        private val condition = Class.forName("com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition")
        private val constructor = adapter.getDeclaredConstructor(Problem::class.java, TheoryParams::class.java,
            LinearObjective::class.java, PreparedSourcePlan::class.java)
        private val conditionConstructor = condition.getDeclaredConstructor(Map::class.java, Map::class.java,
            Map::class.java, BigFraction::class.java)
        private val check = adapter.getMethod("check", condition, Cancellation::class.java)
        private val close = adapter.getMethod("close")
        private val work = adapter.getMethod("getWork")
        private val stats = adapter.getMethod("getStats")
        private val queries = adapter.getDeclaredField("queries").apply { isAccessible = true }
        private val prepared = adapter.methods.firstOrNull { it.name == "getSourcePrepared" }
        private val preparationWork = adapter.methods.firstOrNull { it.name == "getPreparationWork" }
        fun sourcePrepared(owner: Any): Boolean = prepared?.invoke(owner) as? Boolean ?: true
        fun preparationWork(owner: Any): Long = preparationWork?.invoke(owner) as? Long ?: 0
        private val pins = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ONE,
            2 to BigFraction.ofLong(3).reciprocal(), 3 to BigFraction.ofLong(9).reciprocal())
        private val query = conditionConstructor.newInstance(emptyMap<Int, Boolean>(), pins, pins, null)

        fun open(model: Problem): Any = constructor.newInstance(model, TheoryParams(), null, null)
        fun check(owner: Any, stop: Cancellation): Any = check.invoke(owner, query, stop)
        fun close(owner: Any) { close.invoke(owner) }
        fun work(owner: Any): Long = work.invoke(owner) as Long
        fun stats(owner: Any): SolveStats = stats.invoke(owner) as SolveStats
        fun ready(owner: Any): Boolean {
            val service = queries.get(owner)
            return service.javaClass.getDeclaredField("planned").apply { isAccessible = true }.get(service) != null
        }
        fun sample(result: Any): Sample? = if (result.javaClass.simpleName == "Witness") {
            result.javaClass.getMethod("getSample").invoke(result) as Sample
        } else null
    }

    private class Measurement {
        var openingNs = 0L
        var openingBytes = 0L
        var queryNs = 0L
        var queryBytes = 0L
        var interruptedWork = 0L
        var cancelledNativeOps = 0L
        var preparationWork = 0L
    }

    private fun measure(
        options: Options, access: CompletionAccess, model: Problem, mode: String, bytes: () -> Long,
    ): Pair<String, Sample> {
        val measured = Measurement()
        fun open(): Any {
            val allocated = bytes()
            val begun = System.nanoTime()
            return access.open(model).also {
                measured.openingNs += System.nanoTime() - begun
                measured.openingBytes += bytes() - allocated
            }
        }
        fun query(owner: Any, stop: Cancellation): Any {
            val allocated = bytes()
            val begun = System.nanoTime()
            return access.check(owner, stop).also {
                measured.queryNs += System.nanoTime() - begun
                measured.queryBytes += bytes() - allocated
            }
        }
        val retained = if (mode == "retained") open() else null
        try {
            repeat(options.attempts) {
                val owner = retained ?: open()
                try {
                    var operations = 0L
                    var polls = 0
                    val stop = object : Cancellation {
                        private val meter = WorkMeter { units ->
                            if (access.sourcePrepared(owner)) operations += units
                        }
                        override fun isCancelled(): Boolean = if (options.charged) operations > 0 else ++polls >= 3
                        override fun workMeter(): WorkMeter = meter
                    }
                    val before = access.work(owner)
                    val preparedBefore = access.preparationWork(owner)
                    val result = query(owner, stop)
                    check(result.javaClass.simpleName == "Undecided") { "unexpected interrupted verdict" }
                    check(!options.charged || operations > 0) { "no charged native work before interruption" }
                    check(!access.ready(owner)) { "initialization completed before cancellation" }
                    measured.cancelledNativeOps += operations
                    measured.interruptedWork += access.work(owner) - before
                    measured.preparationWork += access.preparationWork(owner) - preparedBefore
                } finally {
                    if (retained == null) access.close(owner)
                }
            }
            val owner = retained ?: open()
            try {
                val preparedBefore = access.preparationWork(owner)
                val sample = checkNotNull(access.sample(query(owner, Cancellation.Never))) {
                    "no witness after interrupted initialization"
                }
                measured.preparationWork += access.preparationWork(owner) - preparedBefore
                val total = if (retained != null) access.work(owner) else measured.interruptedWork + access.work(owner)
                val record = listOf("attempts=${options.attempts}", "openNs=${measured.openingNs}",
                    "openBytes=${measured.openingBytes}", "queryNs=${measured.queryNs}",
                    "queryBytes=${measured.queryBytes}", "interruptedWork=${measured.interruptedWork}", "work=$total",
                    "sourcePreparationWork=${access.stats(owner).smt.sourceLp.preparationWork}",
                    "nativePreparationWork=${measured.preparationWork}",
                    "cancelledNativeOps=${measured.cancelledNativeOps}", "retainedRoots=0",
                    "unknown=${options.attempts}", "sat=1").joinToString(" ")
                return record to sample
            } finally {
                if (retained == null) access.close(owner)
            }
        } finally {
            if (retained != null) access.close(retained)
        }
    }

    private fun model(rows: Int): Problem {
        val factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)) +
            Array(rows) { index ->
                val scale = (index + 1).toDouble()
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                    doubleArrayOf(2.0 * scale, 3.0 * scale), LinearOp.EQ, scale)
            }
        return Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), factors,
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
    }

    private fun source(rows: Int): String = """
        (set-logic QF_LIRA)
        (declare-const i Int)
        (declare-const j Int)
        (declare-const x Real)
        (declare-const y Real)
        (assert (and (<= 0 i) (<= i 1) (<= 0 j) (<= j 1) (distinct i j)))
        (assert (and (<= 0 x) (<= x 1) (<= 0 y) (<= y 1)))
    """.trimIndent() + "\n" + (1..rows).joinToString("\n", postfix = "\n(check-sat)\n") { scale ->
        "(assert (= (+ (* ${2 * scale} x) (* ${3 * scale} y)) $scale))"
    }

    private fun metadata(options: Options, source: String) {
        options.out.resolve("source.smt2").writeText(source)
        println("SOURCE sha256=${hash(source.toByteArray())}")
        println("CONFIG rows=${options.rows} attempts=${options.attempts} rounds=${options.rounds} " +
            "cancel=${if (options.charged) "charged" else "early"}")
        println("RUNTIME ${System.getProperty("java.version")} ${ManagementFactory.getRuntimeMXBean().inputArguments}")
        for (relative in listOf("release", "bin/java", "lib/modules")) {
            val file = File(System.getProperty("java.home"), relative)
            println("RUNTIME_FILE $relative ${file.length()} ${hash(file)}")
        }
        val library = File(Problem::class.java.protectionDomain.codeSource.location.toURI())
        check(library.isFile) { "profile must use the installed library jar" }
        println("LIBRARY ${library.name} sha256=${hash(library)}")
        val tool = File(CompletionInitializationProfile::class.java.protectionDomain.codeSource.location.toURI())
        check(tool.isFile) { "profile must use the installed bench jar" }
        println("TOOL ${tool.name} sha256=${hash(tool)}")
    }

    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
}
