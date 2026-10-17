package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object JvmSavedStudyMethodMetadata {
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    private const val MAX_REPORT_BYTES = 32L * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) = ProfileCaptureArchive.run {
        val options = parse(args)
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        val execution = runCatching { inspect(options, out) }
        val completion = runCatching {
            Reports.writeJson(File(out, "completion.json").path, buildJsonObject {
                put("completed", execution.isSuccess)
                put("error", execution.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" })
                put("inputMode", "saved-two-arm-study")
                put("controlSourceHead", options.getValue("control"))
                put("candidateSourceHead", options.getValue("candidate"))
                put("originalCaptureArchiveSha256", options.getValue("archive-sha256"))
                put("originalCliRebuiltOrExecuted", false)
                put("loadedClassSelectionProven", false)
                put("runtimeGeneratedClassesBound", false)
                put("fullSourcePhaseCoverageProven", false)
                put("acceptedSavingsCredit", false)
            })
        }
        execution.exceptionOrNull()?.let { original -> completion.exceptionOrNull()?.let(original::addSuppressed) }
        execution.getOrThrow()
        completion.getOrThrow()
    }

    internal fun parse(args: Array<String>): Map<String, String> {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf("control", "candidate",
            "capture-manifest", "capture-manifest-sha256", "archive", "archive-sha256"))
        for (name in listOf("control", "candidate")) {
            require(options.getValue(name).matches(Regex("[0-9a-f]{40}")))
        }
        for (name in listOf("capture-manifest-sha256", "archive-sha256")) {
            require(options.getValue(name).matches(Regex("[0-9a-f]{64}")))
        }
        val address = options.getValue("capture-manifest")
        require(address.length <= 4096)
        val uri = URI(address)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
        val path = options.getValue("archive")
        require(path.length <= 4096 && path.none { it.isISOControl() } && File(path).isAbsolute)
        return options
    }

    @Suppress("LongMethod")
    private fun inspect(options: Map<String, String>, out: File) {
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        for (name in listOf("JvmSavedStudyMethodMetadata.kt", "JvmInstalledMethodMetadata.kt",
            "JvmRuntimeMethodMetadata.kt", "JvmClassMetadata.kt")) {
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name")
                .copyTo(File(out, name))
        }
        val helper = File(root, "klause-bench/tools/jvm_runtime_archive.py")
        helper.copyTo(File(out, helper.name))
        Reports.writeJson(File(out, "input-origins.json").path, options)
        val observer = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
        val runtime = File(System.getProperty("java.home")).canonicalFile
        val provenance = File(out, "observer-build-provenance.json")
        InstalledBuild.writeManifest(provenance, observer, runtime)
        val manifest = File(out, "captured-study-manifest.json")
        JvmRuntimeMethodMetadata.download(options.getValue("capture-manifest"), manifest, MAX_MANIFEST_BYTES,
            options.getValue("capture-manifest-sha256"))
        val input = File(options.getValue("archive"))
        require(input.isFile && !Files.isSymbolicLink(input.toPath()) && input.length() <= MAX_ARCHIVE_BYTES)
        require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("archive-sha256"))
        val privateInputs = File(out, "private-runtime-inputs").apply { check(mkdirs()) }
        val archive = File(privateInputs, "original-study.tar.gz")
        input.inputStream().use { source ->
            archive.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= MAX_ARCHIVE_BYTES)
                    target.write(buffer, 0, count)
                }
            }
        }
        require(JvmRuntimeMethodMetadata.digest(archive) == options.getValue("archive-sha256"))
        require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("archive-sha256"))
        val temporary = Files.createTempDirectory("klause-saved-study-jars-").toFile()
        val execution = runCatching {
            val distribution = File(temporary, "distribution")
            val report = File(out, "saved-input-preparation.json")
            val command = listOf("python3", helper.path, "study-distributions", manifest.path,
                options.getValue("capture-manifest-sha256"), archive.path, options.getValue("archive-sha256"),
                options.getValue("control"), options.getValue("candidate"), distribution.path, report.path)
            Reports.writeJson(File(out, "saved-input-preparation.command.json").path, command)
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "saved-input-preparation.stdout"))
                .redirectError(File(out, "saved-input-preparation.stderr")).start()
            val finished = try {
                process.waitFor(30, TimeUnit.MINUTES)
            } finally {
                if (process.isAlive) CompilationStudyBuilds.kill(process)
            }
            File(out, "saved-input-preparation.exit").writeText("${process.exitValue()}\n")
            check(finished && process.exitValue() == 0) { "Original study jar preparation failed" }
            require(report.length() <= MAX_REPORT_BYTES)
            val prepared = Reports.json.parseToJsonElement(report.readText()).jsonObject
            check(prepared.getValue("completed").jsonPrimitive.content == "true")
            check(prepared.getValue("allCaptureMemberBytesVerifiedBeforeStaging").jsonPrimitive.content == "true")
            check(prepared.getValue("bothCompleteOriginalDistributionsVerified").jsonPrimitive.content == "true")
            for (arm in listOf("control", "candidate")) {
                val armOut = File(out, arm).apply { check(mkdirs()) }
                val original = prepared.getValue("originalDistributions").jsonObject.getValue(arm).jsonObject
                Reports.writeJson(File(armOut, "original-distribution.json").path, original)
                JvmInstalledMethodMetadata.inventory(armOut, File(distribution, arm), original)
            }
        }
        val preservation = runCatching {
            require(JvmRuntimeMethodMetadata.digest(archive) == options.getValue("archive-sha256"))
            require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("archive-sha256"))
            require(JvmRuntimeMethodMetadata.digest(manifest) == options.getValue("capture-manifest-sha256"))
            InstalledBuild.readManifest(provenance, observer, runtime, emptyMap())
        }
        val cleanup = runCatching { check(temporary.deleteRecursively()) { "Cannot remove owned study jar staging" } }
        val failure = listOfNotNull(execution.exceptionOrNull(), preservation.exceptionOrNull(),
            cleanup.exceptionOrNull()).firstOrNull()
        if (failure != null) {
            listOfNotNull(execution.exceptionOrNull(), preservation.exceptionOrNull(), cleanup.exceptionOrNull())
                .filter { it !== failure }.forEach(failure::addSuppressed)
            throw failure
        }
    }
}
