package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

internal object CliLpScopeCapture {
    private const val SOURCE_CAP = 16 * 1024 * 1024
    private const val AGENT_CAP = 512 * 1024
    private const val STREAM_CAP = 128 * 1024
    private const val BASE_OPTIONS = "-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1"

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val request = parse(args)
        val root = CorpusFetcher.workspaceRoot().canonicalFile
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        require(listOf(root, out).all { it.path.none(Char::isWhitespace) })
        val reference = Catalog.uncapped(request.suite).problems.single { it.name == request.problem }
        require(reference.format == Format.SMTLIB && reference.data == null)
        val source = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(SOURCE_CAP + 1) }
        require(source.size <= SOURCE_CAP && sha256(source) == request.sha256)
        val input = File(out, "input.smt2").apply { writeBytes(source) }
        val launcher = SolverInvocation.klauseCliBin().canonicalFile
        val runtime = File(System.getProperty("java.home")).canonicalFile
        val core = launcher.parentFile.parentFile.resolve("lib/klause-jvm-SNAPSHOT.jar")
        val agent = File(out, "scope-agent.jar")
        packAgent(agent)
        val report = File(out, "scope-observation.json")
        val config = File(out, "scope-agent.properties")
        val properties = Properties().apply {
            setProperty("report", report.path)
            setProperty("coreJar", core.path)
            setProperty("agentSha256", sha256(agent.readBytes()))
        }
        val schemas = JarFile(core).use { jar ->
            LpScopeObservationAgent.targetMethods().map { (type, selected) ->
                val entry = requireNotNull(jar.getJarEntry("$type.class"))
                val bytes = jar.getInputStream(entry).use { it.readNBytes(2 * 1024 * 1024 + 1) }
                require(bytes.size <= 2 * 1024 * 1024)
                val schema = LpScopeObservationAgent.schema(bytes, selected)
                val rewritten = LpScopeObservationAgent.rewrite(bytes, selected, javaClass.classLoader)
                properties.setProperty("class.$type", sha256(bytes))
                properties.setProperty("schema.$type", sha256(LpScopeObservationAgent.schemaText(schema).toByteArray()))
                properties.setProperty("rewrite.$type", sha256(rewritten))
                mapOf(
                    "class" to type,
                    "beforeSha256" to sha256(bytes),
                    "afterSha256" to sha256(rewritten),
                    "methods" to schema,
                )
            }
        }
        File(out, "target-schema.json").writeText(LpScopeObservationAgent.json(schemas))
        val fieldSchema = LpScopeObservationAgent.json(LpScopeObservationAgent.fieldSchema(javaClass.classLoader))
        File(out, "field-schema.json").writeText(fieldSchema)
        properties.setProperty("fieldSchemaSha256", sha256(fieldSchema.toByteArray()))
        config.outputStream().use { properties.store(it, null) }
        val command = command(request, launcher, input)
        val environment = childEnvironment(System.getenv(), runtime, agent, config)
        val before = InstalledBuild.capture(launcher, runtime, environment)
        InstalledBuild.writeManifest(File(out, "installed-manifest.json"), launcher, runtime)
        Reports.writeJson(File(out, "build-provenance.json").path, before)
        Reports.writeJson(File(out, "request.json").path, request)
        Reports.writeJson(File(out, "command.json").path, command)
        val started = System.nanoTime()
        val process = ProcessBuilder(command).directory(root).apply {
            environment().clear()
            environment().putAll(environment)
        }.start()
        val overflow = AtomicBoolean(false)
        val readers = ArrayList<Thread>()
        val completed = try {
            readers += drain(process.inputStream, File(out, "cli.stdout"), overflow)
            readers += drain(process.errorStream, File(out, "cli.stderr"), overflow)
            process.waitFor(request.timeout * 2 + 20000L, TimeUnit.MILLISECONDS)
        } finally {
            if (process.isAlive) CompilationStudyBuilds.kill(process)
        }
        for (reader in readers) reader.join(2000L)
        if (readers.any { it.isAlive }) {
            process.inputStream.close()
            process.errorStream.close()
            readers.forEach { it.join(2000L) }
            overflow.set(true)
        }
        Reports.writeJson(
            File(out, "measurement.json").path,
            buildJsonObject {
                put("head", Reports.readGitSha())
                put("scope", "installed-cli-child-scope-and-append-shape-boundaries")
                put("sourceSha256", request.sha256)
                put("sourceBytes", source.size)
                put("timeoutMs", request.timeout)
                put("seed", request.seed)
                put("processors", 1)
                put("childPid", process.pid())
                put("exit", process.exitValue())
                put("watchdog", !completed)
                put("streamsIncomplete", overflow.get())
                put("processElapsedNs", System.nanoTime() - started)
                put("jvmOptions", environment.getValue("KLAUSE_CLI_OPTS"))
                put("agentSha256", sha256(agent.readBytes()))
                put("configSha256", sha256(config.readBytes()))
                put("ordinaryTimingOrRecoveryCredit", false)
            },
        )
        check(completed && process.exitValue() == 0)
        check(sha256(input.readBytes()) == request.sha256)
        check(before == InstalledBuild.capture(launcher, runtime, environment))
        var status = "INCONCLUSIVE"
        if (report.isFile && report.length() in 1L..524288L) {
            val details = try {
                Reports.json.parseToJsonElement(report.readText()) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val arguments = (details?.get("jvmArguments") as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content
            }.orEmpty()
            val identity = details != null && (details["pid"] as? JsonPrimitive)?.content == process.pid().toString() &&
                (details["processors"] as? JsonPrimitive)?.content == "1" &&
                (details["javaCommand"] as? JsonPrimitive)?.content ==
                "com.eignex.klause.cli.MainKt ${command.drop(1).joinToString(" ")}" &&
                arguments.count { it.startsWith("-javaagent:") } == 1 &&
                "-javaagent:${agent.path}=${config.path}" in arguments &&
                listOf("-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1").all { it in arguments } &&
                arguments.none { "StartFlightRecording" in it } &&
                (details["agentSha256"] as? JsonPrimitive)?.content == sha256(agent.readBytes()) &&
                (details["configSha256"] as? JsonPrimitive)?.content == sha256(config.readBytes())
            if (identity && !overflow.get() && (details["status"] as? JsonPrimitive)?.content == "COMPLETE") {
                status = "COMPLETE"
            }
        }
        val controlled = out.listFiles().orEmpty().filter {
            it.isFile && it.name != input.name &&
                !it.name.endsWith(".jfr")
        }.sumOf { it.length() }
        if (controlled > 2L * 1024 * 1024) status = "INCONCLUSIVE"
        Reports.writeJson(
            File(out, "capture.json").path,
            buildJsonObject {
                put("status", status)
                put("controlledBytesBeforeReceipt", controlled)
                put(
                    "reportSha256",
                    if (report.isFile && report.length() <= 524288L) {
                        sha256(report.readBytes())
                    } else {
                        "absent-or-oversized"
                    },
                )
                put("targetSchemaSha256", sha256(File(out, "target-schema.json").readBytes()))
                put("instrumentedClassBodies", true)
                put("independentSourceOrProofCheck", false)
                put("ordinaryTimingOrRecoveryCredit", false)
            },
        )
        println("Scope boundary capture $status; source ${request.sha256}")
    }

    internal fun parse(args: Array<String>): Request {
        val values = args.associate { argument ->
            require('=' in argument)
            argument.substringBefore('=') to argument.substringAfter('=')
        }
        require(values.size == args.size && values.keys == setOf("suite", "problem", "sha256", "timeout", "seed"))
        val suite = values.getValue("suite")
        val problem = values.getValue("problem")
        require(suite.isNotBlank() && problem.isNotBlank() && suite.length <= 256 && problem.length <= 1024)
        require(values.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
        val timeout = requireNotNull(values.getValue("timeout").toLongOrNull())
        require(timeout in 1L..60000L)
        return Request(
            suite,
            problem,
            values.getValue("sha256"),
            timeout,
            requireNotNull(values.getValue("seed").toLongOrNull()),
        )
    }

    internal fun command(request: Request, launcher: File, source: File): List<String> =
        listOf(launcher.path, source.path, "-t", request.timeout.toString(), "-r", request.seed.toString(), "-s")

    internal fun childEnvironment(
        inherited: Map<String, String>,
        runtime: File,
        agent: File,
        config: File,
    ): Map<String, String> = inherited.toMutableMap().apply {
        listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").forEach(::remove)
        put("JAVA_HOME", runtime.path)
        put("JAVA_OPTS", "")
        put("KLAUSE_CLI_OPTS", "$BASE_OPTIONS -javaagent:${agent.path}=${config.path}")
    }

    private fun packAgent(output: File) {
        val source = File(LpScopeObservationAgent.javaClass.protectionDomain.codeSource.location.toURI())
        require(source.isFile)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Premain-Class", LpScopeObservationAgent.javaClass.name)
            mainAttributes.putValue("Can-Redefine-Classes", "false")
            mainAttributes.putValue("Can-Retransform-Classes", "false")
        }
        JarFile(source).use { jar ->
            val selected = jar.entries().asSequence().filter {
                !it.isDirectory &&
                    it.name.startsWith("com/eignex/klause/bench/profile/LpScopeObservation") &&
                    it.name.endsWith(".class")
            }.take(129).toList().sortedBy { it.name }
            require(selected.size in 1..128)
            var total = 0
            JarOutputStream(output.outputStream(), manifest).use { target ->
                for (entry in selected) {
                    val bytes = jar.getInputStream(entry).use { it.readNBytes(AGENT_CAP + 1) }
                    total += bytes.size
                    require(total <= AGENT_CAP)
                    target.putNextEntry(JarEntry(entry.name).apply { time = 0L })
                    target.write(bytes)
                    target.closeEntry()
                }
            }
        }
        require(output.length() <= AGENT_CAP)
    }

    private fun drain(input: InputStream, output: File, incomplete: AtomicBoolean): Thread = Thread {
        try {
            input.use { stream ->
                output.outputStream().use { target ->
                    val buffer = ByteArray(8192)
                    var retained = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        val accepted = minOf(count, STREAM_CAP - retained)
                        target.write(buffer, 0, accepted)
                        retained += accepted
                        if (accepted != count) incomplete.set(true)
                    }
                }
            }
        } catch (_: Exception) {
            incomplete.set(true)
        }
    }.apply {
        isDaemon = true
        start()
    }

    @Serializable
    internal data class Request(
        val suite: String,
        val problem: String,
        val sha256: String,
        val timeout: Long,
        val seed: Long,
    )
}
