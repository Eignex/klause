package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.Z3Reference
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
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Measures actual completion initialization retries and checks their exact source witnesses. */
object CompletionInitializationProfile {
    /** Runs the configured cancellation trace and writes measurements and independently checked source points. */
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
        val witnesses = ArrayList<Sample>()
        prepareReference(options.out)
        metadata(options, source)
        for (round in 0..options.rounds) {
            val modes = if (round % 2 == 0) listOf("retained", "fresh") else listOf("fresh", "retained")
            for (mode in modes) {
                val result = measure(options, access, model, mode) { meter.getThreadAllocatedBytes(thread) }
                val record = "round=$round mode=$mode " + result.first
                records += record
                witnesses += result.second
                println("MEASURE $record")
            }
        }
        options.out.resolve("measurements.txt").writeText(records.joinToString("\n", postfix = "\n"))
        validate(options.out, source, witnesses)
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

    private fun prepareReference(out: File) {
        if (System.getProperty("klause.bench.z3") != null) return
        require(System.getProperty("os.name").startsWith("Linux") &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            "set klause.bench.z3 for another platform"
        }
        val url = "https://files.pythonhosted.org/packages/" +
            "b9/0b/f15168475e5493ea44fa3c5e642903f05d2b870db71ad05662ed87a06976/" +
            "z3_solver-4.15.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl"
        val expected = "a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384"
        val wheel = File.createTempFile("completion-reference-", ".whl")
        try {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            connection.getInputStream().use { input -> wheel.outputStream().use { input.copyTo(it) } }
            check(hash(wheel) == expected) { "reference archive hash mismatch" }
            val binary = out.resolve("reference-z3")
            ZipFile(wheel).use { archive ->
                val entry = checkNotNull(archive.getEntry("z3_solver-4.15.3.0.data/data/bin/z3"))
                archive.getInputStream(entry).use { input -> binary.outputStream().use { input.copyTo(it) } }
            }
            check(binary.setExecutable(true)) { "could not make reference executable" }
            System.setProperty("klause.bench.z3", binary.absolutePath)
            out.resolve("reference-provenance.txt").writeText("url=$url\narchiveSha256=$expected\n" +
                "binarySha256=${hash(binary)}\n")
        } finally {
            wheel.delete()
        }
    }

    private fun validate(out: File, source: String, witnesses: List<Sample>) {
        val checks = buildString {
            append(source)
            for (sample in witnesses) {
                val real = checkNotNull(sample.exactReals)
                append("(push 1)\n(assert (= i 0))\n(assert (= j 1))\n")
                append("(assert (= x (/ 1 3)))\n(assert (= y (/ 1 9)))\n")
                append("(assert (= i ${sample.exactIntValue(0)}))\n")
                append("(assert (= j ${sample.exactIntValue(1)}))\n")
                append("(assert (= x ${smt(real[0])}))\n(assert (= y ${smt(real[1])}))\n(check-sat)\n(pop 1)\n")
            }
            append("(push 1)\n(assert (= i j))\n(check-sat)\n(pop 1)\n")
        }
        val input = out.resolve("source-checks.smt2").apply { writeText(checks) }
        val output = out.resolve("source-checks.out")
        val identity = Z3Reference.identity()
        val process = ProcessBuilder(System.getProperty("klause.bench.z3", "z3"), "-smt2", "-T:20", "-memory:512",
            input.absolutePath).redirectOutput(output).redirectError(out.resolve("source-checks.err")).start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "source checking timed out" }
            check(process.exitValue() == 0) { "source checking failed" }
            val actual = output.readLines().map(String::trim).filter(String::isNotEmpty)
            check(actual == List(witnesses.size + 1) { "sat" } + "unsat") { "source checking rejected a record" }
            println("SOURCE_CHECK witnesses=${witnesses.size} binaryIdentity=$identity " +
                "checkerArgs=-smt2,-T:20,-memory:512 exportedProofVerification=false")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun smt(value: BigFraction): String = "(/ ${value.num} ${value.den})"
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
