package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object JvmRuntimeMethodMetadata {
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val MAX_CLASSES = 100_000
    private const val MAX_RESOURCES = 200_000
    private const val MAX_REPORT_BYTES = 512L * 1024 * 1024
    private val compactJson = Json(Reports.json) { prettyPrint = false }

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf(
            "manifest", "manifest-sha256", "archive", "archive-sha256",
        ))
        for (name in listOf("manifest-sha256", "archive-sha256")) {
            require(options.getValue(name).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val execution = runCatching { inspect(options, out) }
            val completion = runCatching {
                Reports.writeJson(File(out, "completion.json").path, buildJsonObject {
                    put("completed", execution.isSuccess)
                    put("error", execution.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" })
                    put("runtimeArchiveSha256", options.getValue("archive-sha256"))
                    put("loadedClassSelectionProven", false)
                    put("runtimeGeneratedClassesBound", false)
                    put("fullSourcePhaseCoverageProven", false)
                    put("acceptedSavingsCredit", false)
                })
            }
            execution.exceptionOrNull()?.let { original ->
                completion.exceptionOrNull()?.let(original::addSuppressed)
            }
            execution.getOrThrow()
            completion.getOrThrow()
        }
    }

    private fun inspect(options: Map<String, String>, out: File) {
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        for (name in listOf("JvmRuntimeMethodMetadata.kt", "JvmRuntimeImage.kt", "JvmClassMetadata.kt")) {
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name").copyTo(File(out, name))
        }
        val helper = File(root, "klause-bench/tools/jvm_runtime_archive.py")
        helper.copyTo(File(out, helper.name))
        val runtime = File(System.getProperty("java.home"))
        InstalledBuild.writeManifest(File(out, "observer-build-provenance.json"),
            File(requireNotNull(System.getenv("KLAUSE_BENCH"))), runtime)
        val manifest = File(out, "captured-original-manifest.json")
        download(options.getValue("manifest"), manifest, MAX_MANIFEST_BYTES, options.getValue("manifest-sha256"))
        val original = Reports.json.parseToJsonElement(manifest.readText()).jsonObject
            .getValue("provenance").jsonObject.getValue("runtime").jsonObject
            .mapValues { it.value.jsonPrimitive.content }
        val privateInputs = File(out, "private-runtime-inputs").apply { check(mkdirs()) }
        val archive = File(privateInputs, "runtime.tar.gz")
        download(options.getValue("archive"), archive, MAX_ARCHIVE_BYTES, options.getValue("archive-sha256"))
        val home = File(privateInputs, "runtime")
        val command = listOf("python3", helper.path, manifest.path, archive.path,
            options.getValue("archive-sha256"), home.path, File(out, "runtime-preparation.json").path)
        val process = ProcessBuilder(command).directory(root)
            .redirectOutput(File(out, "runtime-preparation.stdout"))
            .redirectError(File(out, "runtime-preparation.stderr")).start()
        val finished = process.waitFor(30, TimeUnit.MINUTES)
        if (!finished) CompilationStudyBuilds.kill(process)
        File(out, "runtime-preparation.exit").writeText("${process.exitValue()}\n")
        check(finished && process.exitValue() == 0) { "Original runtime preparation failed" }
        val prepared = Reports.json.parseToJsonElement(File(out, "runtime-preparation.json").readText()).jsonObject
        check(prepared.getValue("completed").jsonPrimitive.content == "true")
        check(prepared.getValue("allRuntimeArchiveFilesMatchOriginalManifest").jsonPrimitive.content == "true")
        verifyImageInputs(home, original)
        inventory(home, out)
        verifyImageInputs(home, original)
    }

    private fun verifyImageInputs(home: File, original: Map<String, String>) {
        for (name in listOf("lib/modules", "lib/jrt-fs.jar", "release")) {
            require(digest(File(home, name)) == original.getValue(name)) { "Runtime image input differs: $name" }
        }
    }

    @Suppress("LongMethod")
    private fun inventory(home: File, out: File) {
        var classes = 0
        var resources = 0
        var errors = 0
        var reportBytes = 0L
        withJvmRuntimeImage(home.toPath()) { image ->
            Reports.writeJson(File(out, "image-provider.json").path, buildJsonObject {
                put("providerClass", image.provider().javaClass.name)
                put("providerLoaderClass", image.provider().javaClass.classLoader.javaClass.name)
                put("observerNativeByteOrder", ByteOrder.nativeOrder().toString())
                put("separateProviderAndImageReaderLoaderVerified", true)
                put("scope", "Pinned provider helpers run in the observer; image class inspection parses bytes.")
            })
            val modules = image.getPath("/modules")
            require(Files.isDirectory(modules))
            val declaredModules = File(home, "release").readLines().single { it.startsWith("MODULES=") }
                .substringAfter('=').removeSurrounding("\"").split(' ')
            require(declaredModules.isNotEmpty() && declaredModules.none { it.isBlank() })
            require(declaredModules.toSet().size == declaredModules.size)
            val actualModules = Files.list(modules).use { paths ->
                paths.map { it.fileName.toString() }.toList().toSet()
            }
            require(actualModules == declaredModules.toSet()) { "Image modules differ from original release" }
            for (name in declaredModules) {
                require(Files.isRegularFile(modules.resolve(name).resolve("module-info.class")))
            }
            File(out, "runtime-class-metadata.jsonl").outputStream().buffered().use { output ->
                Files.walk(modules).use { paths ->
                    for (path in paths.iterator()) {
                        if (!Files.isRegularFile(path)) continue
                        require(++resources <= MAX_RESOURCES)
                        if (!path.toString().endsWith(".class")) continue
                        require(++classes <= MAX_CLASSES)
                        val relative = modules.relativize(path)
                        require(relative.nameCount >= 2)
                        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_CLASS_BYTES + 1) }
                        require(bytes.size <= MAX_CLASS_BYTES)
                        val parsed = try {
                            Result.success(jvmClassMetadata(bytes))
                        } catch (error: IllegalArgumentException) {
                            Result.failure(error)
                        }
                        if (parsed.isFailure) errors++
                        val row = buildJsonObject {
                            put("module", relative.getName(0).toString())
                            put("entry", relative.subpath(1, relative.nameCount).toString())
                            put("entryIndex", classes - 1)
                            put("classSha256", sha256(bytes))
                            put("metadata", parsed.getOrNull()?.let {
                                compactJson.encodeToJsonElement(JvmClassMetadata.serializer(), it)
                            } ?: JsonNull)
                            put("error", parsed.exceptionOrNull()?.let { "${it.javaClass.name}: ${it.message}" })
                        }
                        val line = (compactJson.encodeToString(row) + "\n").encodeToByteArray()
                        reportBytes += line.size
                        require(reportBytes <= MAX_REPORT_BYTES)
                        output.write(line)
                    }
                }
            }
        }
        require(classes > 0)
        Reports.writeJson(File(out, "inventory.json").path, buildJsonObject {
            put("classes", classes)
            put("resources", resources)
            put("classErrors", errors)
            put("allModuleClassEntriesVisited", true)
            put("allDeclaredClassMetadataComplete", errors == 0)
            put("scope", "All /modules classes, excluding package aliases. Loaded selection and costs unproved.")
        })
        check(errors == 0) { "Runtime class metadata errors remain in the complete inventory" }
    }

    private fun download(address: String, output: File, maximum: Long, expected: String) {
        require(address.length <= 4096)
        val uri = URI(address)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
        val connection = uri.toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        var size = 0L
        connection.getInputStream().use { source ->
            output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= maximum)
                    target.write(buffer, 0, count)
                }
            }
        }
        require(digest(output) == expected) { "Runtime metadata input hash mismatch" }
    }

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
