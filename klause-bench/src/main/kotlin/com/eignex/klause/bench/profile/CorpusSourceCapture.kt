package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.FetchMethod
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object CorpusSourceCapture {
    internal const val MAX_REQUEST_BYTES = 48 * 1024
    internal const val MAX_CASES = 128
    private const val STREAM_CAP = 1024 * 1024
    private const val TIMEOUT_SECONDS = 1230L
    internal data class Request(val suite: String, val problem: String, val expectedSha256: String)

    @JvmStatic
    @Suppress("TooGenericExceptionCaught")
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("request64=")) { "one request64 argument required" }
        val inherited = File(requireNotNull(System.getenv("OUT")))
        require(inherited.isDirectory) { "OUT must be the profile's existing directory" }
        val out = Files.createDirectory(inherited.toPath().resolve("corpus-source-capture")).toFile()
        val encoded = args[0].substringAfter('=')
        require(encoded.length <= MAX_REQUEST_BYTES * 4 / 3 + 4) { "request is oversized" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_REQUEST_BYTES) { "request is oversized" }
        write(out.resolve("request.json"), bytes)
        try {
            val requests = decode(encoded)
            val resolved = buildJsonObject {
                put("schema", "corpus-original-source-resolved-request-v1")
                put("cases", buildJsonArray {
                    for ((index, input) in requests.withIndex()) {
                        val layout = requireNotNull(Catalog.dynamicSuites.singleOrNull { it.id == input.suite }
                            ?.sourceLayout) { "suite has no original archive layout" }
                        require(layout.collection.fetch == FetchMethod.TarballZst) { "unsupported archive transport" }
                        add(buildJsonObject {
                            put("index", index)
                            put("suite", input.suite)
                            put("problem", input.problem)
                            put("expectedSha256", input.expectedSha256)
                            put("archiveUrl", layout.collection.url)
                            put("collection", layout.collection.id)
                            put("license", layout.collection.license)
                            put("member", layout.member(input.problem))
                        })
                    }
                })
            }
            val request = out.resolve("resolved-request.json").apply { writeText(resolved.toString()) }
            Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
            val resource = "/corpus-source-capture/capture_corpus_sources.py"
            val workerBytes = CorpusSourceCapture::class.java.getResourceAsStream(resource)
                .use { requireNotNull(it).readNBytes(STREAM_CAP + 1) }
            require(workerBytes.size <= STREAM_CAP)
            val worker = out.resolve("capture_corpus_sources.py").apply { writeBytes(workerBytes) }
            out.resolve("planned.json").writeText(buildJsonObject {
                put("schema", "corpus-original-source-capture-plan-v1")
                put("revision", kotlinx.serialization.json.JsonNull)
                put("revisionAuthority", "owned profile admission; installed harness and runtime hashes")
                put("requestSha256", hash(bytes))
                put("resolvedRequestSha256", hash(request.readBytes()))
                put("workerSha256", hash(workerBytes))
                put("sourceOnly", true)
                put("solverCalls", 0)
                put("sourceParserCalls", 0)
                put("checkerCalls", 0)
                put("cases", requests.size)
                put("ownedLimits", buildJsonObject {
                    put("archiveBytes", 256L * 1024 * 1024)
                    put("observedDownloadBytes", 512L * 1024 * 1024)
                    put("totalSourceBytes", 128L * 1024 * 1024)
                    put("sourceBytes", 32L * 1024 * 1024)
                    put("totalWorkerReceiptBytes", 32L * 1024 * 1024)
                    put("decoderStderrBytesPerCollection", STREAM_CAP)
                    put("outputStreamBytes", STREAM_CAP)
                })
            }.toString())
            require(System.getProperty("os.name") == "Linux") { "owned process groups require Linux" }
            val setsid = File("/usr/bin/setsid")
            require(setsid.canExecute() && File("/usr/bin/python3").canExecute() && File("/bin/kill").canExecute()) {
                "owned capture runtime is unavailable"
            }
            val command = listOf(setsid.path, "/usr/bin/python3", "-I", worker.absolutePath,
                request.absolutePath, out.absolutePath)
            execute(command, out)
        } catch (error: Exception) {
            out.resolve("failure.json").writeText(buildJsonObject {
                put("schema", "corpus-original-source-capture-failure-v1")
                put("allRequestedSourcesCaptured", false)
                put("error", error.toString())
            }.toString())
            throw error
        }
    }

    internal fun decode(encoded: String): List<Request> {
        require(encoded.length <= MAX_REQUEST_BYTES * 4 / 3 + 4) { "request is oversized" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_REQUEST_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray().contentEquals(bytes)) { "request must be UTF-8" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.toString() == text) { "request must be canonical JSON without duplicate fields" }
        require(root.keys == setOf("schema", "cases"))
        require(root.getValue("schema").jsonPrimitive.isString &&
            root.getValue("schema").jsonPrimitive.content == "corpus-original-source-request-v1")
        val cases = root.getValue("cases").jsonArray
        require(cases.isNotEmpty() && cases.size <= MAX_CASES)
        val requests = cases.map { value ->
            val input = value.jsonObject
            require(input.keys == setOf("suite", "problem", "expectedSha256"))
            require(input.values.all { it.jsonPrimitive.isString })
            val suite = input.getValue("suite").jsonPrimitive.content
            val problem = input.getValue("problem").jsonPrimitive.content
            val expected = input.getValue("expectedSha256").jsonPrimitive.content
            require(expected.matches(Regex("[0-9a-f]{64}")))
            val layout = requireNotNull(Catalog.dynamicSuites.singleOrNull { it.id == suite }?.sourceLayout)
            layout.member(problem)
            Request(suite, problem, expected)
        }
        require(requests.map { it.suite to it.problem }.distinct().size == requests.size) { "duplicate identity" }
        return requests
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun execute(
        command: List<String>, out: File, cleanupHelper: String = "/usr/bin/python3", afterWorker: () -> Unit = {},
    ) {
        val gate = Any()
        val stopping = AtomicBoolean()
        val overflow = AtomicBoolean()
        val drainFailure = AtomicReference<String?>()
        val cleanupIssues = ConcurrentLinkedQueue<String>()
        val terminationAttempts = ConcurrentLinkedQueue<kotlinx.serialization.json.JsonObject>()
        val interrupted = ConcurrentHashMap.newKeySet<Thread>()
        val groupResolved = AtomicBoolean()
        val processReaped = AtomicBoolean()
        val drained = ArrayList<Thread>()
        val start = System.nanoTime()
        var active: Process? = null
        var exit: Int? = null
        var complete = false
        var timedOut = false
        var cleaned = false
        var primary: Throwable? = null
        var hookRemoved = false
        fun cleanup(label: String, action: () -> Unit) {
            if (Thread.interrupted()) {
                stopping.set(true)
                interrupted.add(Thread.currentThread())
                cleanupIssues.add("$label: containing interruption observed")
            }
            try {
                action()
            } catch (stop: InterruptedException) {
                stopping.set(true)
                interrupted.add(Thread.currentThread())
                cleanupIssues.add("$label: InterruptedException")
            } catch (error: Exception) {
                cleanupIssues.add("$label: $error")
            }
        }
        fun restoreInterrupt() {
            if (interrupted.remove(Thread.currentThread())) Thread.currentThread().interrupt()
        }
        fun killGroup(process: Process) {
            // Explicit ESRCH distinguishes an absent group from a failed termination attempt.
            val script = """
                import os, signal, sys
                try:
                    os.killpg(int(sys.argv[1]), signal.SIGKILL)
                except ProcessLookupError:
                    sys.exit(3)
                except OSError:
                    sys.exit(4)
            """.trimIndent()
            var helper: Process? = null
            var helperWaitComplete = false
            var helperReaped = false
            var helperExit: Int? = null
            try {
                cleanup("group termination") {
                    helper = ProcessBuilder(cleanupHelper, "-I", "-c", script, process.pid().toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    val started = requireNotNull(helper)
                    if (!started.waitFor(5, TimeUnit.SECONDS)) {
                        cleanupIssues.add("group termination helper timed out")
                    } else {
                        helperWaitComplete = true
                        val status = started.exitValue()
                        helperExit = status
                        if (status == 0 || status == 3) groupResolved.set(true)
                        else cleanupIssues.add("group termination helper exit $status")
                    }
                }
            } finally {
                cleanup("group termination helper reap") {
                    helper?.let {
                        if (it.isAlive) {
                            it.destroyForcibly()
                            if (!it.waitFor(5, TimeUnit.SECONDS)) cleanupIssues.add("termination helper is not reaped")
                        }
                        helperReaped = !it.isAlive
                        if (helperReaped) helperExit = it.exitValue()
                    }
                }
                terminationAttempts.add(buildJsonObject {
                    put("helper", cleanupHelper)
                    put("pid", helper?.pid())
                    put("group", process.pid())
                    put("exit", helperExit)
                    put("waitComplete", helperWaitComplete)
                    put("reaped", helperReaped)
                })
                cleanup("worker descendant termination") {
                    process.descendants().use { children -> children.forEach {
                        if (!it.destroyForcibly() && it.isAlive) cleanupIssues.add("worker descendant still alive")
                    } }
                }
                cleanup("worker reap") {
                    process.destroyForcibly()
                    if (process.waitFor(5, TimeUnit.SECONDS)) processReaped.set(true)
                    else cleanupIssues.add("worker is not reaped")
                }
            }
        }
        fun finish() = synchronized(gate) {
            if (cleaned) return@synchronized
            val process = active
            try {
                process?.let(::killGroup)
                for (thread in drained) cleanup("worker stream drain") {
                    thread.join(5000)
                    if (thread.isAlive) cleanupIssues.add("worker stream drain did not complete")
                }
            } finally {
                complete = drained.size == 2 && drained.none { it.isAlive } && drainFailure.get() == null
                try {
                    cleanup("process receipt durability") {
                        write(out.resolve("worker-process.json"), buildJsonObject {
                            put("command", buildJsonArray {
                                command.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                            put("pid", process?.pid())
                            put("exit", exit)
                            put("processWallNs", System.nanoTime() - start)
                            put("timedOut", timedOut)
                            put("containingStop", stopping.get())
                            put("overflow", overflow.get())
                            put("streamsComplete", complete)
                            put("drainFailure", drainFailure.get())
                            put("primaryFailure", primary?.toString())
                            put("groupTerminationEstablished", groupResolved.get())
                            put("workerReaped", processReaped.get())
                            put("terminationAttempts", buildJsonArray { terminationAttempts.forEach { add(it) } })
                            put("cleanupIssues", buildJsonArray {
                                cleanupIssues.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                        }.toString().toByteArray())
                    }
                } finally {
                    active = null
                    cleaned = true
                }
            }
        }
        val shutdown = Thread {
            stopping.set(true)
            try {
                finish()
            } finally {
                restoreInterrupt()
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val process = synchronized(gate) {
                check(!stopping.get()) { "containing capture stopped" }
                ProcessBuilder(command).start().also { active = it }
            }
            fun drain(input: java.io.InputStream, destination: File): Thread = Thread {
                try {
                    FileOutputStream(destination).use { output ->
                        input.use {
                            val buffer = ByteArray(65536)
                            var count = 0
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                val allowed = minOf(size, STREAM_CAP - count)
                                output.write(buffer, 0, allowed)
                                count += allowed
                                if (allowed != size) {
                                    overflow.set(true)
                                    killGroup(process)
                                    break
                                }
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                } catch (error: Exception) {
                    drainFailure.compareAndSet(null, error.toString())
                    killGroup(process)
                } finally {
                    restoreInterrupt()
                }
            }.apply { isDaemon = true }
            synchronized(gate) {
                check(!stopping.get()) { "containing capture stopped" }
                drained += drain(process.inputStream, out.resolve("worker.stdout"))
                drained += drain(process.errorStream, out.resolve("worker.stderr"))
                drained.forEach(Thread::start)
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                timedOut = true
                killGroup(process)
            }
            if (!process.isAlive) exit = process.exitValue()
            afterWorker()
        } catch (error: Throwable) {
            primary = error
            if (error is InterruptedException) stopping.set(true)
            throw error
        } finally {
            try {
                finish()
            } finally {
                try {
                    cleanup("shutdown hook removal") {
                        hookRemoved = Runtime.getRuntime().removeShutdownHook(shutdown)
                        if (!hookRemoved) cleanupIssues.add("shutdown hook was not removed")
                    }
                    cleanup("final cleanup receipt durability") {
                        write(out.resolve("worker-cleanup.json"), buildJsonObject {
                            put("registrationReleased", active == null && cleaned)
                            put("shutdownHookRemoved", hookRemoved)
                            put("primaryFailure", primary?.toString())
                            put("cleanupComplete",
                                cleanupIssues.isEmpty() && groupResolved.get() && processReaped.get())
                            put("terminationAttempts", buildJsonArray { terminationAttempts.forEach { add(it) } })
                            put("issues", buildJsonArray {
                                cleanupIssues.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                        }.toString().toByteArray())
                    }
                } finally {
                    restoreInterrupt()
                }
            }
        }
        check(exit == 0 && complete && !timedOut && !overflow.get() && !stopping.get() &&
            cleanupIssues.isEmpty() && groupResolved.get() && processReaped.get() && hookRemoved
        ) { "capture worker did not complete" }
    }

    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
