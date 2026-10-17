package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object MacContainerCompilationStudy {
    const val IMAGE = "eclipse-temurin@sha256:" +
        "ceb096d5cb78f2913cabd3722fe1b1d30b8d59b075595e25baa13e1850909d5b"
    private const val GIB = 1024L * 1024 * 1024
    private const val MINIMUM_FREE_BYTES = 12 * GIB
    private const val RESERVE_BYTES = 4 * GIB
    private const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024L
    private const val COMMAND_SECONDS = 60L
    private const val PULL_SECONDS = 900L
    private const val STUDY_SECONDS = 10_800L
    private const val POLL_MILLIS = 100L
    private const val OWNER_LABEL = "org.klause.compilation-study.owner"
    private val ID = Regex("[0-9a-f]{64}")

    @JvmStatic
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun main(args: Array<String>) = ProfileCaptureArchive.run {
        val options = parse(args)
        require(System.getProperty("os.name").startsWith("Mac") &&
            System.getProperty("os.arch") in setOf("aarch64", "arm64")) { "Study requires the ARM64 Mac worker" }
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        require(out.usableSpace >= MINIMUM_FREE_BYTES) { "Study needs 12 GiB free before preparation" }
        val root = File(requireNotNull(System.getProperty("klause.workspace.root"))).canonicalFile
        val socket = File(URI(options.endpoint).path)
        require(socket.exists() && !Files.isSymbolicLink(socket.toPath())) { "Local socket is unavailable" }
        val docker = File("/opt/homebrew/bin/docker").canonicalFile
        require(docker.isFile && docker.canExecute())
        val temporary = Files.createTempDirectory(out.parentFile.toPath(), "klause-linux-study-").toFile()
        val config = File(temporary, "client")
        val work = File(temporary, "work")
        val guestOut = File(out, "guest")
        val script = File(out, "run.sh")
        val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
        val cli = File(root, "klause-cli/build/install/klause-cli-jvm/bin/klause-cli").canonicalFile
        val runtime = File(System.getProperty("java.home")).canonicalFile
        val provenance = File(out, "observer-build-provenance.json")
        val cliProvenance = File(out, "observer-cli-provenance.json")
        val prefix = listOf(docker.path, "--config", config.path, "--host", options.endpoint)
        val nonce = UUID.randomUUID().toString()
        val name = "klause-compilation-study-$nonce"
        var created = false
        var started = false
        var removed = false
        var imageAdded = false
        var imageRemoved = false
        var failure: String? = null
        try {
            check(config.mkdir() && work.mkdir() && guestOut.mkdir())
            File(root, "klause-bench/compilation-study/run.sh").copyTo(script)
            InstalledBuild.writeManifest(provenance, bench, runtime)
            InstalledBuild.writeManifest(cliProvenance, cli, runtime)
            val dockerIdentity = CompilationStudyDistributions.identity(docker)
            Reports.writeJson(File(out, "docker-cli-identity.json").path, dockerIdentity)
            val collections = readonlyCollections(out)
            Reports.writeJson(File(out, "requested-options.json").path, options)
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/" +
                "MacContainerCompilationStudy.kt").copyTo(File(out, "MacContainerCompilationStudy.kt"))
            command(out, "docker-version", prefix + listOf("version", "--format", "{{json .Server}}"))
            verifyCapacity(out, prefix)
            val present = command(out, "image-before", prefix + listOf("image", "inspect", "--format", "{{.Id}}",
                IMAGE), required = false) == 0
            if (!present) {
                command(out, "image-pull", prefix + listOf("pull", "--platform", "linux/arm64", IMAGE), PULL_SECONDS)
                imageAdded = true
            }
            command(out, "image-identity", prefix + listOf("image", "inspect", "--format",
                "{\"id\":{{json .Id}},\"os\":{{json .Os}},\"arch\":{{json .Architecture}}," +
                    "\"digests\":{{json .RepoDigests}}}", IMAGE))
            val image = Json.parseToJsonElement(File(out, "image-identity.stdout").readText()).jsonObject
            require(image.getValue("os").jsonPrimitive.content == "linux" &&
                image.getValue("arch").jsonPrimitive.content == "arm64")
            val imageId = image.getValue("id").jsonPrimitive.content
            require(imageId.startsWith("sha256:") && ID.matches(imageId.removePrefix("sha256:")))
            command(out, "driver-head", listOf("git", "-C", root.path, "rev-parse", "HEAD"))
            val driver = File(out, "driver-head.stdout").readText().trim()
            require(driver.matches(Regex("[0-9a-f]{40}")))
            val arguments = createArguments(name, nonce, imageId, driver, work, guestOut, script,
                cli.parentFile.parentFile, bench.parentFile.parentFile, collections, options)
            command(out, "container-create", prefix + arguments)
            created = true
            val id = File(out, "container-create.stdout").readText().trim()
            require(ID.matches(id)) { "Container creation lacks its complete ID" }
            verifyOwner(out, prefix, name, nonce, "container-owner-before")
            started = true
            command(out, "study", prefix + listOf("start", "--attach", id), STUDY_SECONDS)
            command(out, "container-exit", prefix + listOf("inspect", "--format", "{{.State.ExitCode}}", id))
            check(File(out, "container-exit.stdout").readText().trim() == "0") { "Guest study failed" }
            InstalledBuild.readManifest(provenance, bench, runtime, emptyMap())
            InstalledBuild.readManifest(cliProvenance, cli, runtime, emptyMap())
            val finalDockerIdentity = CompilationStudyDistributions.identity(docker)
            Reports.writeJson(File(out, "docker-cli-final-identity.json").path, finalDockerIdentity)
            check(dockerIdentity == finalDockerIdentity) { "Docker CLI bytes changed" }
        } catch (error: Exception) {
            failure = error.toString()
            throw error
        } finally {
            val cleanup = runCatching {
                val found = command(out, "container-owner-final", prefix + listOf("inspect", "--format",
                    "{{index .Config.Labels \"$OWNER_LABEL\"}}", name), required = false) == 0
                if (found) {
                    check(File(out, "container-owner-final.stdout").readText().trim() == nonce)
                    command(out, "container-cleanup", prefix + listOf("container", "rm", "--force", name))
                    removed = true
                } else {
                    check(!created) { "Created container ownership cannot be resolved" }
                }
                if (imageAdded) {
                    // Docker refuses removal when another container still needs the image.
                    command(out, "image-cleanup", prefix + listOf("image", "rm", IMAGE))
                    imageRemoved = true
                }
            }
            val stagingRemoved = if (cleanup.isSuccess) temporary.deleteRecursively() else false
            Reports.writeJson(File(out, "completion.json").path, MacContainerStudyCompletion(
                created, started, removed, imageAdded, imageRemoved, stagingRemoved,
                failure, cleanup.exceptionOrNull()?.toString(),
                if (stagingRemoved) null else temporary.path))
            if (failure == null) {
                cleanup.getOrThrow()
                check(stagingRemoved) { "Owned study staging remains" }
            }
        }
    }

    fun parse(args: Array<String>): MacContainerStudyOptions {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val studyKeys = setOf("selection", "sha256", "control", "candidate", "plan", "plan-sha256")
        val allocationKeys = setOf("endpoint", "control", "candidate", "fixture-allocation")
        require(options.size == args.size && options.keys in listOf(studyKeys + "endpoint",
            studyKeys + setOf("endpoint", "families", "families-sha256"), allocationKeys))
        options["fixture-allocation"]?.let { require(it == "existing-matching") }
        for (key in listOf("sha256", "plan-sha256", "families-sha256")) {
            options[key]?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        }
        for (key in listOf("selection", "plan", "families")) {
            options[key]?.let {
                val address = URI.create(it)
                require(address.scheme == "https" && !address.host.isNullOrBlank() && address.userInfo == null &&
                    address.rawQuery == null && address.rawFragment == null)
            }
        }
        val endpoint = CompilationStudyContainerProbe.parse(arrayOf("endpoint=${options.getValue("endpoint")}"))
        CompilationStudyBuilds.validateHeads(listOf("control", "candidate").associateWith(options::getValue))
        val arguments = if ("fixture-allocation" in options) {
            listOf("fixture-allocation=existing-matching") + args.filterNot {
                it.startsWith("endpoint=") || it.startsWith("fixture-allocation=")
            }
        } else args.filterNot { it.startsWith("endpoint=") }
        return MacContainerStudyOptions(endpoint.endpoint, arguments)
    }

    @Suppress("LongParameterList")
    fun createArguments(
        name: String,
        nonce: String,
        imageId: String,
        driver: String,
        work: File,
        out: File,
        script: File,
        cli: File,
        bench: File,
        collections: List<File>,
        options: MacContainerStudyOptions,
    ): List<String> {
        require(name == "klause-compilation-study-$nonce" && UUID.fromString(nonce).toString() == nonce)
        require(imageId.startsWith("sha256:") && ID.matches(imageId.removePrefix("sha256:")))
        require(driver.matches(Regex("[0-9a-f]{40}")))
        val mounts = listOf(work, out, script, cli, bench) + collections
        require(mounts.all { it.isAbsolute && it.path.none { c -> c == ',' || c.isWhitespace() || c.isISOControl() } })
        fun mount(source: File, destination: String, readOnly: Boolean = false) =
            listOf("--mount", "type=bind,src=${source.path},dst=$destination" + if (readOnly) ",readonly" else "")
        return listOf("create", "--pull", "never", "--name", name, "--label", "$OWNER_LABEL=$nonce", "--init",
            "--memory", "6g", "--memory-swap", "6g", "--cpus", "2", "--cpuset-cpus", "0,1",
            "--pids-limit", "512", "--security-opt", "no-new-privileges", "--cap-drop", "ALL",
            "--cap-add", "CHOWN", "--cap-add", "DAC_OVERRIDE", "--cap-add", "FOWNER",
            "--cap-add", "SETUID", "--cap-add", "SETGID",
            "--env", "JAVA_TOOL_OPTIONS=", "--env", "JDK_JAVA_OPTIONS=", "--env", "_JAVA_OPTIONS=",
            "--entrypoint", "/bin/bash") + mount(work, "/work") + mount(out, "/capture") +
            mount(script, "/study.sh", true) + mount(cli, "/observer-cli", true) +
            mount(bench, "/observer-bench", true) +
            collections.flatMap { mount(it, "/work/corpus/${it.name}", true) } +
            listOf(imageId, "/study.sh", "/work",
                driver, "/capture", "/observer-cli", "/observer-bench") +
            options.studyArguments
    }

    private fun readonlyCollections(out: File): List<File> {
        val corpus = File(requireNotNull(System.getenv("KLAUSE_CORPUS"))).canonicalFile
        require(corpus.isDirectory)
        val collections = corpus.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
        require(collections.size <= 128 && collections.all {
            it.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && !Files.isSymbolicLink(it.toPath())
        })
        Reports.writeJson(File(out, "readonly-corpus-mounts.json").path, collections.map { it.path })
        return collections
    }

    private fun verifyCapacity(out: File, prefix: List<String>) {
        command(out, "guest-capacity", prefix + listOf("info", "--format",
            "{\"os\":{{json .OSType}},\"arch\":{{json .Architecture}}," +
                "\"memory\":{{json .MemTotal}},\"cpus\":{{json .NCPU}}}"))
        val capacity = Json.parseToJsonElement(File(out, "guest-capacity.stdout").readText()).jsonObject
        require(capacity.getValue("os").jsonPrimitive.content == "linux" &&
            capacity.getValue("arch").jsonPrimitive.content in setOf("aarch64", "arm64") &&
            capacity.getValue("memory").jsonPrimitive.content.toLong() >= 6 * GIB &&
            capacity.getValue("cpus").jsonPrimitive.content.toInt() >= 2) { "Guest capacity is insufficient" }
    }

    private fun verifyOwner(out: File, prefix: List<String>, name: String, nonce: String, label: String) {
        command(out, label, prefix + listOf("inspect", "--format", "{{index .Config.Labels \"$OWNER_LABEL\"}}", name))
        check(File(out, "$label.stdout").readText().trim() == nonce) { "Container ownership differs" }
    }

    private fun command(
        out: File,
        name: String,
        arguments: List<String>,
        seconds: Long = COMMAND_SECONDS,
        required: Boolean = true,
    ): Int {
        Reports.writeJson(File(out, "$name.command.json").path, arguments)
        val stdout = File(out, "$name.stdout")
        val stderr = File(out, "$name.stderr")
        val builder = ProcessBuilder(arguments).redirectOutput(stdout).redirectError(stderr)
        builder.environment().keys.removeAll {
            it.startsWith("DOCKER_") || it in setOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy",
                "https_proxy", "all_proxy", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
        }
        val process = builder.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                check(stdout.length() <= MAX_OUTPUT_BYTES && stderr.length() <= MAX_OUTPUT_BYTES) {
                    "Study wrapper output exceeds bounds"
                }
                if (name in setOf("study", "image-pull")) {
                    check(out.usableSpace >= RESERVE_BYTES) { "Study reached its 4 GiB host disk reserve" }
                }
                check(System.nanoTime() < deadline) { "Study wrapper command timed out: $name" }
            }
            check(stdout.length() <= MAX_OUTPUT_BYTES && stderr.length() <= MAX_OUTPUT_BYTES)
            check(!required || process.exitValue() == 0) { "Study wrapper command failed: $name" }
            return process.exitValue()
        } finally {
            if (process.isAlive) CompilationStudyBuilds.kill(process)
            File(out, "$name.exit").writeText("${process.exitValue()}\n")
        }
    }
}

@Serializable
internal data class MacContainerStudyOptions(val endpoint: String, val studyArguments: List<String>)

@Serializable
internal data class MacContainerStudyCompletion(
    val containerCreated: Boolean,
    val studyStarted: Boolean,
    val ownedContainerRemoved: Boolean,
    val imageReferenceAdded: Boolean,
    val addedImageReferenceRemoved: Boolean,
    val ownedStagingRemoved: Boolean,
    val error: String?,
    val cleanupError: String?,
    val retainedOwnedStaging: String?,
    val completed: Boolean = containerCreated && studyStarted && ownedContainerRemoved &&
        (!imageReferenceAdded || addedImageReferenceRemoved) && ownedStagingRemoved &&
        error == null && cleanupError == null,
    val physicalMacCpuAffinityProven: Boolean = false,
    val originalLinuxMeasurementsTransferable: Boolean = false,
    val sourceOutcomesValidated: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
)
