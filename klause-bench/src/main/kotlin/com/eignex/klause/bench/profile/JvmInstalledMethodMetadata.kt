package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile

internal object JvmInstalledMethodMetadata {
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val MAX_JAR_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_JAR_BYTES = 1024L * 1024 * 1024
    private const val MAX_CLASSES = 100_000
    private const val MAX_REPORT_BYTES = 512L * 1024 * 1024
    private val compactJson = Json(Reports.json) { prettyPrint = false }

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf("head", "manifest", "manifest-sha256"))
        require(options.getValue("head").matches(Regex("[0-9a-f]{40}")))
        require(options.getValue("manifest-sha256").matches(Regex("[0-9a-f]{64}")))
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            var distributionMatched = false
            val execution = runCatching {
                inspect(options, out) { distributionMatched = true }
            }
            val completion = runCatching {
                val failure = execution.exceptionOrNull()
                val report = buildJsonObject {
                    put("completed", execution.isSuccess)
                    put("error", failure?.let { "${it.javaClass.name}: ${it.message}" })
                    put("requestedSourceHead", options.getValue("head"))
                    put("allInstalledDistributionBytesMatchedBeforeInspection", distributionMatched)
                    put("loadedClassSelectionProven", false)
                    put("runtimeGeneratedClassesBound", false)
                    put("fullSourcePhaseCoverageProven", false)
                    put("acceptedSavingsCredit", false)
                    put(
                        "scope",
                        "Installed class-file metadata only. Class inspection does not load or initialize classes; " +
                            "no solver, source checker, JFR reader or performance analysis runs. Source lines and " +
                            "descriptors do not establish executed phases, inlining, loaded selection or costs.",
                    )
                }
                Reports.writeJson(File(out, "completion.json").path, report)
            }
            execution.exceptionOrNull()?.let { original ->
                completion.exceptionOrNull()?.let(original::addSuppressed)
            }
            execution.getOrThrow()
            completion.getOrThrow()
        }
    }

    private fun inspect(options: Map<String, String>, out: File, distributionVerified: () -> Unit) {
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        val sourceNames = listOf(
            "JvmClassMetadata.kt",
            "JvmInstalledMethodMetadata.kt",
            "CompilationStudyBuilds.kt",
            "ProfileCaptureArchive.kt",
        )
        for (name in sourceNames) {
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name")
                .copyTo(File(out, name))
        }
        val manifest = capturedManifest(options, out)
        val original = manifest.getValue("provenance").jsonObject.getValue("distribution").jsonObject
        require(original.isNotEmpty())
        val runtime = File(System.getProperty("java.home"))
        val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH")))
        InstalledBuild.writeManifest(File(out, "observer-build-provenance.json"), bench, runtime)
        val heads = mapOf("control" to options.getValue("head"), "candidate" to options.getValue("head"))
        CompilationStudyBuilds(root, out).use { workspace ->
            val builds = workspace.prepare(heads)
            for (build in builds.values) {
                val actual = Reports.json.parseToJsonElement(build.provenance.readText()).jsonObject
                    .getValue("provenance").jsonObject.getValue("distribution").jsonObject
                Reports.writeJson(
                    File(out, "${build.arm}-distribution-match.json").path,
                    buildJsonObject {
                    put("matchesOriginal", actual == original)
                    put("expected", original)
                    put("actual", actual)
                }
                )
                require(actual == original) { "Rebuilt distribution differs from the retained original: ${build.arm}" }
            }
            distributionVerified()
            val distribution = File(builds.getValue("control").root, "klause-cli/build/install/klause-cli-jvm")
            inventory(out, distribution, original)
        }
    }

    private fun capturedManifest(options: Map<String, String>, out: File): JsonObject {
        val uri = URI(options.getValue("manifest"))
        require(
            uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.toString().length <= 4096
        )
        val connection = uri.toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val bytes = connection.getInputStream().use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
        require(bytes.size <= MAX_MANIFEST_BYTES && sha256(bytes) == options.getValue("manifest-sha256"))
        File(out, "captured-original-manifest.json").writeBytes(bytes)
        return Reports.json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    @Suppress("LongMethod")
    private fun inventory(out: File, distribution: File, original: JsonObject) {
        val jars = original.keys.filter { it.endsWith(".jar") }.sorted()
        require(jars.isNotEmpty())
        val saved = File(out, "private-installed-jars").apply { check(mkdirs()) }
        var totalJarBytes = 0L
        val counts = InventoryCounts()
        File(out, "class-metadata.jsonl").outputStream().buffered().use { output ->
            for (name in jars) {
                require(name.startsWith("lib/") && '\\' !in name && name.split('/').none { it in setOf("", ".", "..") })
                val file = File(distribution, name).canonicalFile
                require(file.toPath().startsWith(distribution.canonicalFile.toPath()) && file.isFile)
                require(file.length() <= MAX_JAR_BYTES)
                totalJarBytes += file.length()
                require(totalJarBytes <= MAX_TOTAL_JAR_BYTES)
                val expected = original.getValue(name).jsonPrimitive.content
                require(digest(file) == expected)
                val retained = File(saved, name).apply { parentFile.mkdirs() }
                file.copyTo(retained)
                require(digest(retained) == expected)
                recordJarClasses(retained, name, expected, output, counts)
                require(digest(file) == expected && digest(retained) == expected)
            }
        }
        Reports.writeJson(
            File(out, "inventory.json").path,
            buildJsonObject {
            put("jars", jars.size)
            put("classes", counts.classes)
            put("classErrors", counts.errors)
            put("allJarClassEntriesVisited", true)
            put("allDeclaredClassMetadataComplete", counts.errors == 0)
            put(
                "scope",
                "Every class entry, including alternate multi-release entries, is retained separately. " +
                    "No runtime version or class-loader choice is inferred. Original jar bytes remain private " +
                    "for further metadata readers; method and attribute order and duplicate line tables are preserved.",
            )
        }
        )
        check(counts.errors == 0) { "Class metadata errors remain in the complete inventory" }
    }

    private fun recordJarClasses(
        file: File,
        name: String,
        expected: String,
        output: OutputStream,
        counts: InventoryCounts,
    ) {
        JarFile(file, false).use { jar ->
            for ((index, entry) in jar.entries().asSequence().withIndex()) {
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                require(++counts.classes <= MAX_CLASSES)
                val bytes = jar.getInputStream(entry).use { it.readNBytes(MAX_CLASS_BYTES + 1) }
                require(bytes.size <= MAX_CLASS_BYTES)
                val parsed = try {
                    Result.success(jvmClassMetadata(bytes))
                } catch (error: IllegalArgumentException) {
                    Result.failure(error)
                }
                if (parsed.isFailure) counts.errors++
                val row = buildJsonObject {
                    put("jar", name)
                    put("jarSha256", expected)
                    put("entryIndex", index)
                    put("entry", entry.name)
                    put("classSha256", sha256(bytes))
                    put(
                        "metadata",
                        parsed.getOrNull()?.let {
                        compactJson.encodeToJsonElement(JvmClassMetadata.serializer(), it)
                    } ?: JsonNull
                    )
                    put("error", parsed.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" })
                }
                val line = (compactJson.encodeToString(row) + "\n").encodeToByteArray()
                counts.reportBytes += line.size
                require(counts.reportBytes <= MAX_REPORT_BYTES)
                output.write(line)
            }
        }
    }

    private class InventoryCounts(var classes: Int = 0, var errors: Int = 0, var reportBytes: Long = 0)

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
