package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object CompilationStudyContainerProbe {
    private const val MAX_OUTPUT_BYTES = 1024L * 1024
    private const val MAX_TOOL_BYTES = 256L * 1024 * 1024
    private const val MAX_IMAGES = 1024
    private const val COMMAND_SECONDS = 30L
    private const val POLL_MILLIS = 100L
    private val IMAGE_ID = Regex("sha256:[0-9a-f]{64}")
    private const val IMAGE_FORMAT = "{\"id\":{{json .Id}},\"os\":{{json .Os}}," +
        "\"architecture\":{{json .Architecture}},\"bytes\":{{json .Size}}}"

    @JvmStatic
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun main(args: Array<String>) = ProfileCaptureArchive.run {
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        var inventoryComplete = false
        var guestRequested = false
        var guestPassed = false
        var failure: String? = null
        val temporary = Files.createTempDirectory(out.toPath(), "owned-container-probe-").toFile()
        try {
            val options = parse(args)
            guestRequested = options.image != null
            require(System.getProperty("os.name").startsWith("Mac")) { "Probe requires the Mac profiling worker" }
            val socket = File(URI(options.endpoint).path)
            require(socket.exists() && !Files.isSymbolicLink(socket.toPath())) { "Local socket is unavailable" }
            val docker = File("/opt/homebrew/bin/docker").canonicalFile
            require(docker.isFile && docker.canExecute())
            val cliIdentity = identity(docker)
            Reports.writeJson(File(out, "docker-cli-identity.json").path, cliIdentity)
            Reports.writeJson(File(out, "requested-options.json").path, options)
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/" +
                "CompilationStudyContainerProbe.kt").copyTo(File(out, "CompilationStudyContainerProbe.kt"))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, bench, runtime)
            val config = File(temporary, "client").apply { check(mkdir()) }
            val prefix = listOf(docker.path, "--config", config.path, "--host", options.endpoint)
            command(out, "docker-version", prefix + listOf("version", "--format", "{{json .Server}}"))
            command(out, "image-list", prefix + listOf("image", "ls", "--quiet", "--no-trunc"))
            val listed = File(out, "image-list.stdout").readLines().filter { it.isNotBlank() }
            require(listed.size <= MAX_IMAGES && listed.all(IMAGE_ID::matches)) { "Image inventory exceeds bounds or lacks full IDs" }
            val images = listed.distinct()
            var selectedArchitecture: String? = null
            var selectedOs: String? = null
            if (images.isNotEmpty()) {
                command(out, "image-inspect", prefix + listOf("image", "inspect", "--format", IMAGE_FORMAT) + images)
                val described = File(out, "image-inspect.stdout").readLines().filter { it.isNotBlank() }
                    .map { Json.parseToJsonElement(it).jsonObject }
                require(described.size == images.size &&
                    described.map { it.getValue("id").jsonPrimitive.content }.toSet() == images.toSet())
                described.singleOrNull { it.getValue("id").jsonPrimitive.content == options.image }?.let {
                    selectedArchitecture = it.getValue("architecture").jsonPrimitive.content
                    selectedOs = it.getValue("os").jsonPrimitive.content
                }
            }
            inventoryComplete = true
            options.image?.let { image ->
                require(image in images) { "Requested immutable image is not installed" }
                val hostArchitecture = when (System.getProperty("os.arch")) {
                    "aarch64", "arm64" -> "arm64"
                    "amd64", "x86_64" -> "amd64"
                    else -> error("Unsupported Mac architecture")
                }
                require(selectedOs == "linux" && selectedArchitecture == hostArchitecture) { "Image platform differs" }
                guest(out, temporary, prefix, image)
                guestPassed = true
            }
            val finalCliIdentity = identity(docker)
            Reports.writeJson(File(out, "docker-cli-final-identity.json").path, finalCliIdentity)
            check(finalCliIdentity == cliIdentity) { "Docker CLI bytes changed" }
            InstalledBuild.readManifest(provenance, bench, runtime, emptyMap())
        } catch (error: Exception) {
            failure = error.toString()
            throw error
        } finally {
            val stagingRemoved = temporary.deleteRecursively()
            Reports.writeJson(File(out, "completion.json").path, ContainerProbeCompletion(
                inventoryComplete, guestRequested, guestPassed, stagingRemoved, failure))
            check(stagingRemoved) { "Cannot remove owned container client staging" }
        }
    }

    fun parse(args: Array<String>): ContainerProbeOptions {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys in listOf(setOf("endpoint"), setOf("endpoint", "image")))
        val address = options.getValue("endpoint")
        val uri = URI.create(address)
        require(address.startsWith("unix:///") && uri.scheme == "unix" && uri.rawAuthority == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.startsWith('/') && uri.path.endsWith(".sock") && uri.normalize() == uri &&
            uri.rawPath == uri.path && address.none { it.isWhitespace() || it.isISOControl() })
        val image = options["image"]
        require(image == null || IMAGE_ID.matches(image)) { "Use a complete installed image ID" }
        return ContainerProbeOptions(address, image)
    }

    fun guestArguments(image: String, nonce: File, cid: File): List<String> {
        require(IMAGE_ID.matches(image))
        require(listOf(nonce, cid).all { it.isAbsolute && it.path.none { c -> c == ',' || c.isWhitespace() || c.isISOControl() } })
        return listOf("run", "--pull", "never", "--cidfile", cid.path, "--network", "none", "--read-only",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "256m", "--pids-limit", "64",
            "--cpus", "1", "--cpuset-cpus", "0", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
            "--env", "JAVA_TOOL_OPTIONS=", "--env", "JDK_JAVA_OPTIONS=", "--env", "_JAVA_OPTIONS=",
            "--entrypoint", "/bin/sh", "--mount", "type=bind,src=${nonce.path},dst=/probe-nonce,readonly",
            image, "-c", GUEST_SCRIPT)
    }

    private fun guest(out: File, temporary: File, prefix: List<String>, image: String) {
        val nonce = File(out, "probe-nonce.txt").apply { writeText(UUID.randomUUID().toString() + "\n") }
        val cid = File(temporary, "container.cid")
        check(!cid.exists())
        val attempt = runCatching {
            command(out, "guest-probe", prefix + guestArguments(image, nonce, cid))
            val lines = File(out, "guest-probe.stdout").readLines()
            check(lines.firstOrNull() == nonce.readText().trimEnd() &&
                "probe-os=Linux" in lines && "probe-cpus=0" in lines &&
                lines.any { it.trim() == "java.specification.version = 25" }) { "Guest capability or owned mount differs" }
        }
        val cleanup = runCatching {
            if (cid.exists()) {
                val id = cid.readText().trim()
                check(id.matches(Regex("[0-9a-f]{64}"))) { "Invalid owned container receipt" }
                cid.copyTo(File(out, "owned-container-id.txt"))
                command(out, "owned-container-cleanup", prefix + listOf("container", "rm", "--force", id))
            }
        }
        attempt.exceptionOrNull()?.let { original -> cleanup.exceptionOrNull()?.let(original::addSuppressed) }
        attempt.getOrThrow()
        cleanup.getOrThrow()
    }

    private fun identity(file: File): CaptureFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= MAX_TOOL_BYTES) { "Docker CLI bytes exceed bounds" }
                digest.update(buffer, 0, count)
            }
        }
        return CaptureFile(size, digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) })
    }

    private fun command(out: File, name: String, arguments: List<String>) {
        Reports.writeJson(File(out, "$name.command.json").path, arguments)
        val stdout = File(out, "$name.stdout")
        val stderr = File(out, "$name.stderr")
        val builder = ProcessBuilder(arguments).redirectOutput(stdout).redirectError(stderr)
        builder.environment().keys.removeAll {
            it.startsWith("DOCKER_") || it in setOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")
        }
        val process = builder.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_SECONDS)
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                check(stdout.length() <= MAX_OUTPUT_BYTES && stderr.length() <= MAX_OUTPUT_BYTES) { "Probe output exceeds bounds" }
                check(System.nanoTime() < deadline) { "Probe command timed out" }
            }
            check(stdout.length() <= MAX_OUTPUT_BYTES && stderr.length() <= MAX_OUTPUT_BYTES) { "Probe output exceeds bounds" }
            check(process.exitValue() == 0) { "Probe command failed: $name" }
        } finally {
            if (process.isAlive) CompilationStudyBuilds.kill(process)
            File(out, "$name.exit").writeText("${process.exitValue()}\n")
        }
    }

    private val GUEST_SCRIPT = """
        set -eu
        cat /probe-nonce
        test "$(uname -s)" = Linux
        printf 'probe-os=Linux\n'
        printf 'probe-arch='; uname -m
        printf 'probe-cpus='; sed -n 's/^Cpus_allowed_list:[[:space:]]*//p' /proc/self/status
        command -v taskset
        command -v java
        command -v jfr
        java -XshowSettings:properties -version 2>&1
        /usr/bin/time -v -o /tmp/probe.resources taskset -c 0 /bin/true
        cat /tmp/probe.resources
        jfr configure --input profile --output /tmp/probe.jfc \
          'jdk.Compilation#enabled=true' 'jdk.Compilation#threshold=0ms' \
          'jdk.CompilerPhase#enabled=true' 'jdk.CompilerPhase#threshold=0ms' \
          'jdk.CompilerInlining#enabled=true' \
          'jdk.InitialEnvironmentVariable#enabled=false' 'jdk.InitialSystemProperty#enabled=false'
        cat /tmp/probe.jfc
    """.trimIndent()
}

@Serializable
internal data class ContainerProbeOptions(val endpoint: String, val image: String?)

@Serializable
internal data class ContainerProbeCompletion(
    val imageInventoryComplete: Boolean,
    val guestRequested: Boolean,
    val linuxInterfacesAndOwnedMountProbePassed: Boolean,
    val ownedClientStagingRemoved: Boolean,
    val error: String?,
    val completed: Boolean = imageInventoryComplete && ownedClientStagingRemoved && error == null &&
        (!guestRequested || linuxInterfacesAndOwnedMountProbePassed),
    val physicalMacCpuAffinityProven: Boolean = false,
    val originalLinuxMeasurementsTransferable: Boolean = false,
    val fullCompilationStudyProtocolProven: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
)
