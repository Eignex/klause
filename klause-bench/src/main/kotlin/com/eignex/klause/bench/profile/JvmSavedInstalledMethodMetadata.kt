package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal object JvmSavedInstalledMethodMetadata {
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(
            args.size == options.size && options.keys == setOf(
                "head", "manifest", "manifest-sha256", "capture-manifest", "capture-manifest-sha256",
                "archive", "archive-sha256",
            )
        )
        require(options.getValue("head").matches(Regex("[0-9a-f]{40}")))
        for (key in listOf("manifest-sha256", "capture-manifest-sha256", "archive-sha256")) {
            require(options.getValue(key).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val execution = runCatching { inspect(options, out) }
            val completion = runCatching {
                Reports.writeJson(
                    File(out, "completion.json").path,
                    buildJsonObject {
                        put("completed", execution.isSuccess)
                        put("error", execution.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" })
                        put("inputMode", "saved-original-capture")
                        put("requestedSourceHead", options.getValue("head"))
                        put("originalCaptureArchiveSha256", options.getValue("archive-sha256"))
                        put("allOriginalSavedJarBytesMatchedBeforeInspection", execution.isSuccess)
                        put("originalCliRebuiltOrExecuted", false)
                        put("loadedClassSelectionProven", false)
                        put("runtimeGeneratedClassesBound", false)
                        put("fullSourcePhaseCoverageProven", false)
                        put("acceptedSavingsCredit", false)
                    },
                )
            }
            execution.exceptionOrNull()?.let { original -> completion.exceptionOrNull()?.let(original::addSuppressed) }
            execution.getOrThrow()
            completion.getOrThrow()
        }
    }

    @Suppress("LongMethod")
    private fun inspect(options: Map<String, String>, out: File) {
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        for (name in listOf(
            "JvmSavedInstalledMethodMetadata.kt", "JvmInstalledMethodMetadata.kt",
            "JvmRuntimeMethodMetadata.kt", "JvmClassMetadata.kt",
        )) {
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name").copyTo(File(out, name))
        }
        val helper = File(root, "klause-bench/tools/jvm_runtime_archive.py")
        helper.copyTo(File(out, helper.name))
        val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
        val runtime = File(System.getProperty("java.home")).canonicalFile
        val provenance = File(out, "observer-build-provenance.json")
        InstalledBuild.writeManifest(provenance, bench, runtime)
        val manifest = File(out, "captured-original-manifest.json")
        val capture = File(out, "captured-input-manifest.json")
        JvmRuntimeMethodMetadata.download(
            options.getValue("manifest"), manifest, MAX_MANIFEST_BYTES, options.getValue("manifest-sha256"),
        )
        JvmRuntimeMethodMetadata.download(
            options.getValue("capture-manifest"), capture, MAX_MANIFEST_BYTES,
            options.getValue("capture-manifest-sha256"),
        )
        val input = File(options.getValue("archive"))
        require(input.isAbsolute && !Files.isSymbolicLink(input.toPath()) && input.isFile)
        require(input.length() <= MAX_ARCHIVE_BYTES)
        require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("archive-sha256"))
        val privateInputs = File(out, "private-runtime-inputs").apply { check(mkdirs()) }
        val archive = File(privateInputs, "original-capture.tar.gz")
        input.copyTo(archive)
        require(JvmRuntimeMethodMetadata.digest(archive) == options.getValue("archive-sha256"))
        require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("archive-sha256"))
        val temporary = Files.createTempDirectory("klause-saved-jar-inputs-").toFile()
        try {
            val distribution = File(temporary, "distribution")
            val report = File(out, "saved-input-preparation.json")
            val command = listOf(
                "python3", helper.path, "saved-distribution", manifest.path, capture.path, archive.path,
                options.getValue("archive-sha256"), options.getValue("manifest-sha256"),
                options.getValue("head"), distribution.path, report.path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "saved-input-preparation.stdout"))
                .redirectError(File(out, "saved-input-preparation.stderr")).start()
            val finished = process.waitFor(30, TimeUnit.MINUTES)
            if (!finished) CompilationStudyBuilds.kill(process)
            File(out, "saved-input-preparation.exit").writeText("${process.exitValue()}\n")
            check(finished && process.exitValue() == 0) { "Original saved jar preparation failed" }
            val prepared = Reports.json.parseToJsonElement(report.readText()).jsonObject
            check(prepared.getValue("completed").jsonPrimitive.content == "true")
            check(prepared.getValue("allCaptureMemberBytesVerifiedBeforeStaging").jsonPrimitive.content == "true")
            check(prepared.getValue("allOriginalJarBytesMatchRetainedDistribution").jsonPrimitive.content == "true")
            val original: JsonObject = Reports.json.parseToJsonElement(manifest.readText()).jsonObject
                .getValue("provenance").jsonObject.getValue("distribution").jsonObject
            JvmInstalledMethodMetadata.inventory(out, distribution, original)
            require(JvmRuntimeMethodMetadata.digest(archive) == options.getValue("archive-sha256"))
            require(JvmRuntimeMethodMetadata.digest(input) == options.getValue("archive-sha256"))
            InstalledBuild.readManifest(provenance, bench, runtime, emptyMap())
        } finally {
            check(temporary.deleteRecursively()) { "Cannot remove owned saved jar staging" }
        }
    }
}
